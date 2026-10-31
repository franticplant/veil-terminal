# Decomposing the Microdroid guest

Status: investigation 2026-10-30. This is a plan and a record of what the
guest actually permits. It shaped the experimental Workers layout in
`v0.1.0`; the stable default remains the Monolith payload.

The question is not "which Rust modules can be separate?" but "which
boundaries does Microdroid enforce?" The answer is narrower than a normal
Linux process split.

## What the guest actually is

The payload starts as one process, launched by `microdroid_manager` through
`/system/bin/microdroid_launcher`. The manager:

- runs the launcher with the Microdroid payload UID/GID;
- drops the inheritable capability set and the capability bounding set;
- gives the payload a single SELinux domain, `microdroid_app`;
- does not install a shell or a service manager for the payload.

The stable Monolith payload does everything in one process: engine protocol,
SSH transport, terminal parser, host-key/sign bridge, and the vsock broker.
The experimental Workers layout forks a supervisor, a separate broker
process, a single-threaded spawner and per-session workers inside the same
guest. The broker remains the only component that accepts a second vsock
port.

## Probe evidence

A dev-only probe payload (`guest/vm-probe`) was run in a protected VM and
driven through the dev control plane without rebuilding it for each question.
The important results:

| Probe | Result | Consequence |
|---|---|---|
| `getuid` / `geteuid` | `6000` / `6000` | Payload is an unprivileged, separate UID in this build. |
| `/proc/self/attr/current` | `u:r:microdroid_app:s0` | One SELinux domain for launcher and payload; no per-worker domain. |
| `CapEff` / `CapBnd` | `0` / `0x40e801c1` | No effective capabilities; cannot change UID or mount. |
| `fork()` | works | A process split is possible. |
| `execve("/system/bin/sh")` | `EACCES` | No shell, no exec of helper binaries. Worker roles must be in the same binary (or loaded with `dlopen`). |
| `dlopen("/mnt/apk/.../libveil_vm_engine.so")` | works | The APK's `.so` files can be loaded as modules in-process. This does not create a process boundary by itself. |
| `setuid(6001)` | `EPERM` | All workers would stay UID 6000; no per-worker UID separation. |
| `socketpair(AF_UNIX, SOCK_STREAM)` | works | Unix socket IPC between forked workers is available. |
| `prctl(PR_SET_DUMPABLE, 0)` | works | Blocks same-UID ptrace; it does not block signals. |
| `prctl(PR_GET_SECCOMP)` | `-1` (unavailable) | No seccomp filter in this guest kernel/configuration. |
| seccomp filter install | `EINVAL` | Cannot use seccomp to block `kill`, `ptrace`, or syscalls per worker. |
| Landlock create | `ENOSYS` | No Landlock. |
| `unshare(CLONE_NEWUSER)` | `EINVAL` | User namespaces unavailable. |
| `unshare(CLONE_NEWNS\|NEWPID\|NEWNET)` | `EPERM` | No mount/PID/net namespace separation. |
| cgroup v2 | mounted `rw`, but read/create denied | The hierarchy is not delegated to the payload; cannot create per-worker cgroups. |
| `/proc` | `hidepid=invisible` | The supervisor cannot inspect sibling workers through `/proc`; use `wait4` and worker-reported metrics. |
| `/dev/binder`, `/dev/hwbinder`, `/dev/vndbinder` | absent | No kernel Binder, no servicemanager. |
| vsock `connect` | `EACCES` | Payload may listen on vsock, but cannot initiate a vsock connection. |
| raw vsock listener + accept | works | Host → guest channels are the supported direction. |
| Binder RPC (`AVmPayload_runVsockRpcServer`) | supported by API | Host → guest AIDL server only; not intra-guest IPC. |

`exec` being denied is the most important result: **a worker cannot be a
separate executable or a shell.** A process split inside the guest must be
fork-only. A forked worker can `dlopen` another `.so` from the APK, but it
still shares the same UID, domain, and kernel authority.

The observed UID 6000 is the multi-tenant Microdroid allocation; a
non-multi-tenant AOSP build uses UID 0. In both cases the manager has already
dropped the capability sets (`CapEff=0` here), so a payload cannot change
UID, mount, or create the namespaces needed for a stronger split.

### Launcher and tenant routes tested

| Route | Result |
|---|---|
| Public `VirtualMachineDescriptor` parcel/import with an APK custom config | Works; selected a custom config without the missing Java test API methods |
| `run_as_root` custom config | UID/GID 0, but capabilities (including bounding set) zero; `setuid(6001)` still `EPERM` |
| Root in `microdroid_app` with retained SETUID/SETGID | `setuid`/`setgid` still `EPERM` with enforcing SELinux AVCs |
| Custom `Executable` task with an APK binary | `execute_no_trans` AVC; no useful domain transition |
| App executing the `vm` tool | Works; CLI-created debug VMs auto-start adbd |
| Guest adb root through the app's VM Binder | Works on this stock user host; debug `su` is permissive, so not a production domain |
| Android 17 built-in tenants | **Validated**: one tenant UID 10000; two tenants UID 10000/10001; GID 6000; `CapEff=0`; cross-UID `kill`/`ptrace` denied |

The debug adb route proves the kernel credential model but is not a release
security design: debug VMs weaken protected-VM assumptions, and `su` is
deliberately permissive. The tenant route is the production candidate.

## What this means

### Process split narrows access; credential separation needs launcher support

Forked workers provide access confinement through deliberately limited
descriptors and supervisor-enforced protocol gates, alongside address-space
separation and crash containment:

- separate address spaces;
- crash containment;
- per-session lifetime and cleanup;
- separate file-descriptor tables;
- no SSH private keys, no network-dialing authority, no listener (only the
  session control/data FDs);
- supervisor-owned session admission, connection-lease matching and frame
  validation;
- `PR_SET_DUMPABLE=0` and rlimits;
- measurable per-worker RSS (reported by the worker, because `/proc` is
  hidden).

They do **not**, with the current app payload, give:

- different UIDs (`setuid` is denied);
- different SELinux domains (one `microdroid_app` domain);
- seccomp filters (`PR_SET_SECCOMP` is unavailable);
- cgroup memory/CPU limits (cgroups are not delegated);
- namespace isolation (user namespaces are disabled, and the other
  namespaces require capabilities);
- protection from a compromised worker sending signals to its siblings.

A compromised worker can still signal the supervisor or sibling workers. Its
supplied descriptors cover only its own session; the supervisor rejects
cross-session messages and leases and validates frames before forwarding
them. SSH private keys and final signing authority remain on Android. These
mechanisms restrict access through the intended interfaces, but do not
establish distinct kernel security principals or eliminate same-UID denial of
service.

### If we can control the launcher, we can separate UIDs in one VM

A privileged parent can create distinct workers:

- fork the worker while still privileged;
- `setgroups([])`, `setresgid()`, `setresuid()` to a per-role UID;
- clear the capability sets;
- `PR_SET_NO_NEW_PRIVS` and `PR_SET_DUMPABLE=0`;
- pass only the socketpair and the assigned session FD;
- close listener and vsock FDs.

Then a worker cannot signal another UID, and the kernel enforces the UID
boundary even without seccomp. The parent only needs privilege during setup;
it should drop to an unprivileged UID and remain the supervisor.

The blocker is not the idea; it is who is allowed to be that privileged
parent. `microdroid_manager` has `CAP_SETUID`/`CAP_SETGID` and a SELinux
allowance for them. `microdroid_app` does not. The app-facing
`MicrodroidLauncher` task is deliberately started as UID 6000 with
`CapEff=0`; the AOSP CDD tests assert that the payload is not root and has no
effective capabilities. The normal `VirtualMachineConfig.Builder` cannot
select `TaskType::Executable`: `setPayloadConfigPath` and `addExtraApk` are
`@TestApi` and are absent from the runtime builder (confirmed with a
declared-method dump). A public `VirtualMachineDescriptor` parcel/import can
select an APK custom config, but that alone does not create a trusted
executable domain. The tested app-provided `Executable` task gets an
`execute_no_trans` AVC; root in `microdroid_app` still cannot `setuid`; and
`run_as_root` starts UID/GID 0 with all capabilities cleared.

So UID/capability separation inside one Microdroid VM is possible, but it
requires one of:

- a custom payload config / root launcher supplied by a platform or test
  build, with a suitable SELinux domain;
- a Microdroid manager/image change that spawns the worker roles with
  distinct UIDs;
- debug-only `adbd`/root tooling, which is not a production mechanism.

An ordinary distributed APK without the restricted AVF custom-config/tenant
permissions cannot create those UIDs. In that case a separate VM is the
app-only way to get a hard boundary.

### Android 17 built-in tenants are the best single-VM route

Android 17 adds a manager-native tenant model. The installed user build
reports `com.android.kvm.ADVANCE_MULTITENANCY` enabled. The manager accepts a
payload config with `tenants`, launches each tenant APK/APEX as a separate
process, and performs the credential change itself:

- tenant UIDs are in `10000..65534`, with the shared Microdroid GID 6000;
- the default SELinux transition is `microdroid_app`;
- capabilities are dropped by the existing manager launcher path;
- per-tenant cgroup configuration is available where supported;
- tenants and `run_as_root` cannot be combined.

This is now the validated single-VM route. A stock Android 17 user build
started one and two tenants from the `.vm` app using the public
`VirtualMachineDescriptor` config import path. The observed result:

```text
tenant 1: uid=10000 gid=6000 CapEff=0 Groups=3009
tenant 2: uid=10001 gid=6000 CapEff=0 Groups=3009

cross-uid from tenant 1:
kill(tenant 2, 0)   = EPERM
kill(tenant 2, 10)  = EPERM
kill(tenant 2, 18)  = 0       # SIGCONT: allowed in the same session
ptrace(tenant 2)    = EPERM
setgroups(0, NULL)  = EPERM
setuid(10001)       = EPERM
cat /proc/<tenant 2>/status = allowed
```

The same checks from tenant 2 against tenant 1 also returned the same result
matrix. The `/proc/<peer>/status` read confirms that different UIDs alone do
not hide peer process status in this guest; the shared readproc group and
same SELinux domain need to be treated as residual exposure. Clearing the
supplementary groups is denied to an unprivileged tenant (`setgroups` EPERM),
so that must be handled by the manager or accepted as a platform limitation.
The Java API builds the tenant APK/idsig arrays itself from the tenant package
names in the payload config. Two implementation details matter:

- **Package visibility:** the `.vm` manifest must declare the tenant packages
  in `<queries>`. Without that, `PackageManager.getApplicationInfo()` throws
  `NameNotFoundException` even though the package is installed in the same
  user.
- **Expected authority:** `expected_authority` is the 64-byte SHA-512 of the
  signing certificate's DER bytes, represented as 128 hex characters. The
  `apksigner` signer-certificate digest is a 32-byte SHA-256 value and is the
  wrong field. Using it produces `PAYLOAD_INVALID_CONFIG` with a certificate
  mismatch.

Remaining caveats from the validated run:

- supplementary group 3009 (readproc) is still inherited;
- `NoNewPrivs=0` and `CapBnd` is non-empty (effective capabilities are zero);
- per-tenant cgroups use `memory.high` (a reclaim/throttle threshold), not
  `memory.max`; enforcement was not measured;
- tenants are installed as separate Android packages, so the first install
  needs a user tap unless preinstalled;
- fixed-at-boot vs dynamic tenant creation is still unverified;
- the validated cross-UID tests used the diagnostic probe; the production
  worker should set `NoNewPrivs` and `Dumpable=0` itself.

### App-only hard isolation still needs separate VMs

If the threat model requires a compromised SSH or terminal parser to be
unable to affect another session's parser **and we must remain stock
app-only**, use one Microdroid VM per trust domain or per session. The AVF
host can run more than one VM; the cost is boot time and memory, and the host
must relay between VMs. That is a later step, not part of the 0.1.x guest
layout.

If we control the guest image/manager, the privileged-launcher layout above
can provide UID/capability separation inside one VM. Android 17's built-in
tenants may provide the same result without a privileged app launcher; test
that before building a custom launcher.

Host-side `isolatedProcess` remains useful for authority-less Android
workers, but it is not a mechanism inside Microdroid.

## App integration

The app exposes the layout as a device-local setting and passes it to `.vm`
through `IVmHost.startEngineWithLayout`:

```text
Settings → Engine → VM layout
  monolith (stable)
  workers  (experimental)
  tenants  (experimental)
```

`VmBackend` sends the layout string to the VM host. The host selects the
matching payload or orchestration; a missing backend fails explicitly, never
as a silent native fallback. In `v0.1.0`, Monolith is the stable default,
Workers is selectable as experimental, and Tenants is not shipped.

## Combined target: tenants for roles, forks for sessions

This is the target layout, not the `v0.1.0` default. The shipped Workers
layout is same-UID and does not provide these tenant boundaries.

The validated tenant result makes the target layout a hybrid:

```
Host app
  │ engine protocol, vsock 5000
  ▼
┌──────────────────────────────────────────────────────────────┐
│ Tenant supervisor        UID 10000                           │
│  protocol / session table / frame gate / leases              │
│  host-key and signing bridge (no keys)                       │
│      │                                                       │
│      ├── Tenant broker   UID 10001                           │
│      │      vsock 5001 → endpoint header → session fd        │
│      │                                                       │
│      └── Tenant worker pool UID 10002                        │
│             ├── spawner (fork-only, single-threaded)         │
│             ├── session worker A (SSH + VT)                  │
│             ├── session worker B                             │
│             └── ...                                           │
│                                                              │
│ Cross-tenant IPC: authenticated Unix socket + SO_PEERCRED    │
│ Session stream: SCM_RIGHTS                                   │
└──────────────────────────────────────────────────────────────┘
```

Role boundaries are DAC-enforced by tenant UIDs. Session workers inside the
worker-pool tenant still share UID 10002; they have separate address spaces,
FD tables and crash domains, but can signal one another. If per-session UIDs
are required, use one fixed worker tenant per session or prove dynamic tenant
creation.

### Same-UID worker layout (implemented, experimental)

If tenant packages are not acceptable or unavailable, the same process layout
without distinct UIDs still narrows access and contains crashes. The `workers`
payload uses a pre-forked spawner:

```
Host app ── vsock 5000 ──► supervisor
                             ├── spawner
                             │    └── per-session SSH + VT worker
                             │
                             └── broker process (vsock 5001)
                                  └── accepted stream + SCM_RIGHTS
                                      back to the supervisor
```

The current implementation forks a minimal spawner and a minimal broker before
the supervisor runtime starts. Both are single-threaded and never start Tokio.
The spawner can safely fork one worker per session while the supervisor has
threads. The broker owns vsock 5001 and forwards each accepted stream over a
private Unix socketpair with `SCM_RIGHTS`; the supervisor owns the passive
endpoint/lease queue and the relay. The spawner creates a control socketpair
and a data socketpair for each new worker and passes the supervisor ends back
with `SCM_RIGHTS`. Each worker is single-use: one process handles one session
and exits; the spawner reaps it.
The supervisor enforces the protocol's `max_sessions` live-session bound (16),
so there is no finite worker pool to exhaust and no fallback to in-process
execution. The supervisor relays the broker's connected stream to the data
socket, so a worker never receives the broker's vsock descriptor, engine
listener, or another session's stream. The frontend sends a per-connection
lease through `OpenSession`; the VM host includes it in the broker header, and
the guest broker matches the stream by `(host, port, connection_id)`. The
app sends a versioned `veil-broker-v1` handshake; the guest accepts the old
three-line form for mixed-version compatibility. The endpoint-only fallback
remains only for a zero/legacy lease.

### Components and boundaries

| Component | Split? | Why |
|---|---|---|
| Supervisor: protocol, sessions, frame validation, prompt/sign routing | Keep one | Small trusted authority; must not parse SSH or terminal bytes. |
| Broker: vsock 5001, endpoint routing, relay | Separate process | Owns the guest relay listener; workers receive only supervisor-relayed session streams. Also contains relay crashes. |
| SSH transport + VT parser | One worker per session initially | Restricts supplied channels to one session; separates parser address spaces and lifetimes. |
| VT parser separately | Optional second split | Crash containment only; no seccomp/UID/domain separation. Do it only if measurements justify another process. |
| Signing bridge | Supervisor | No key material; forwards requests to Android. |
| Host-key policy | Android/frontend | Canonical for native and VM; guest only asks. |
| Logs/metrics | Per worker, merged by supervisor | Not a security boundary. |
| Frame gate | Supervisor | Validate every VT frame before it reaches the app; worker output is untrusted and cross-session frames are dropped. |

### IPC and authority

- Use `socketpair(AF_UNIX, SOCK_SEQPACKET)` if available, otherwise stream
  sockets with `veil-wire` framing and hard message limits.
- The spawner passes the worker's control and data socket ends to the
  supervisor with `SCM_RIGHTS`. The supervisor relays the broker's connected
  stream over the data socket; it never passes the vsock descriptor to a
  worker.
- Workers must close the engine and broker listener FDs. A worker cannot
  create a new vsock connection (`EACCES`), but an inherited listener is
  still authority.
- Sign requests go worker → supervisor → app. The guest still has no keys.
- The supervisor validates frames from the VT worker with the same
  `check_frame` contract as the frontend.
- Metrics: worker reports its own `/proc/self/status` (RSS, threads, fds)
  because `/proc/<other-pid>` is hidden. The supervisor can use `wait4` for
  exit rusage.

### Limits that actually work

- `RLIMIT_AS` and `RLIMIT_NOFILE` per worker (already applied by
  `engine-host::limits` for the native host; apply per guest worker).
- `RLIMIT_CORE=0`.
- `PR_SET_NO_NEW_PRIVS` and `PR_SET_DUMPABLE=0`.
- `setpriority` to lower priority (the manager starts the payload at
  `-20`; a worker may lower itself, not raise itself).
- Close all unused FDs.
- Worker death is handled with `wait4`; restart policy is a supervisor
  decision. There is no cgroup kill.

Do not claim cgroup/seccomp/Landlock/namespace limits: the probe shows they
are unavailable or not delegated in this guest.

## Binder and the Android API surface

- **Binder RPC over vsock is the supported host‑guest control path.** The
  payload hosts a server with `AVmPayload_runVsockRpcServer`; the host uses
  `VirtualMachine.connectToVsockServer(port)` and an AIDL interface. The
  AOSP Rust test payload (`tests/testapk/src/native/testbinary.rs`) uses
  `vm_payload::run_single_vsock_service`; the demo app uses
  `connectToVsockServer`.
- **The payload cannot connect to a Binder server** and there is no
  servicemanager or `/dev/binder` in the guest. Binder RPC is therefore not
  an intra-guest worker bus.
- **FD passing through Binder RPC is documented as unsupported** in the VM
  Payload API. Use the raw vsock stream or Unix socket FDs for the session
  stream.
- **Available payload APIs** include `AVmPayload_notifyPayloadReady`,
  `AVmPayload_getApkContentsPath`, `AVmPayload_getEncryptedStoragePath`,
  `AVmPayload_getVmInstanceSecret`, and attestation helpers. They are useful
  for storage/identity, not for worker separation.
- **Only native code is supported**; no Java, no SystemServer, no HALs, no
  UI. The allowed NDK subset is libc, logging, and NdkBinder.

## Development probe

The probe is a separate payload, not the engine. It is packaged with `.vm`
and its shell is enabled only in the `dev` VM build (`:vm:assembleDev`) by
`BuildConfig.DEV_PROBE`; the release build returns "diagnostic shell is not
in this build". The guest's fixed `env` probe remains available for
`vm.probe`. The shell is not an exported activity or intent. The VM host
service is already protected by the signature-level `VM_HOST` permission,
and only devd calls the shell.

Use it without rebuilding the payload:

```sh
veil-ctl call vm.shell commands='env'
veil-ctl call vm.shell commands='ps'
veil-ctl call vm.shell commands='stat /sys/fs/cgroup'
veil-ctl call vm.shell commands='syscall 172'
veil-ctl call vm.shell commands='dlopen /mnt/apk/lib/arm64-v8a/libveil_vm_engine.so'
veil-ctl call vm.probeStop
```

`vm.shell` keeps the probe VM running between calls. `vm.probe` remains the
one-shot environment report and stops it.

The guest command set is deliberately a fixed menu plus generic `syscall`,
`prctl`, `dlopen`, and `kill` probes. `exec` is present as a probe, but the
guest returns `EACCES`; arbitrary shell execution is not available inside
this Microdroid configuration.

## Review conclusions

The tenant target is sound as **role isolation plus per-session crash
containment**, not as strong isolation between sessions. The shipped same-UID
Workers layout goes no further than session-scoped protocol and descriptor
access control plus crash containment.

Three risks need explicit treatment:

- **Same-UID session workers share a security boundary.** The worker-pool
  tenant separates the pool from supervisor/broker, not sessions from each
  other.
- **`SO_PEERCRED` identifies a process, not a session.** Every IPC request and
  transferred FD must carry and validate a session ID and expected peer role;
  otherwise a worker can ask for another session's frames or signing action.
  Keep the signing bridge session-scoped even though keys stay on Android.
- **Resource and privilege confinement is incomplete.** Group 3009,
  `NoNewPrivs=0`, the non-empty capability bounding set, and unmeasured cgroups
  remain open until measured and reduced.

Stage order: implement and prove the fork-worker lifecycle **before**
integrating tenant roles, so fork safety, descriptor ownership, reaping and
session routing are isolated from tenant startup and cross-UID IPC. The
completed tenant validation remains the basis for Stage 3.

Acceptance for the fork-worker stage:

- concurrent sessions work;
- killing one worker leaves other sessions usable;
- repeated connect/disconnect leaves no zombies, threads or FD growth;
- workers inherit no unrelated session, listener, or signing descriptors.

Acceptance for the combined role stage:

- clean boot creates supervisor, broker and worker pool with the expected
  UIDs;
- cross-UID denial still passes;
- wrong-peer, cross-session, malformed and unexpected-FD requests are
  rejected;
- a worker cannot obtain another session's signing authorization.

## Staged work

1. Keep the 0.1.0 engine payload monolith.
2. Keep the diagnostic shell behind the dev `.vm` build; do not publish
   the dev variant.
3. Add the pre-forked spawner and a per-session worker behind the `workers`
   layout. **Done (experimental).**
4. Move the broker to its own process and pass session FDs explicitly. **Done
   (experimental, same UID); tenant UID separation remains.**
5. Add tests for worker crash, supervisor survival, FD leaks, frame
   validation, and session cleanup.
6. Measure memory with 1, 2, and 4 workers at 64/96/128 MiB.
7. **Tenant validation: done.** One and two tenants started on the stock
   Android 17 user build with UIDs 10000/10001 and zero effective
   capabilities. Cross-UID `kill` and `ptrace` were denied.
8. **Stage 1 process boundary: implemented (experimental).** The
   `libveil_vm_engine_workers.so` payload starts a supervisor, a broker and a
   pre-forked spawner. The spawner forks one single-use worker per session and
   passes Unix control/data sockets to the supervisor with `SCM_RIGHTS`; the
   supervisor enforces session IDs and the `check_frame` render contract on
   worker output and relays the broker stream without exposing the vsock
   listener. `veil-ctl scenario connect-echo` passes against the test host for
   18 sequential sessions and two simultaneous sessions to the same endpoint;
   workers are reaped by the spawner. Engine logs now merge supervisor,
   broker, spawner and worker entries; the VM console retains process-level
   start/reap lines. The broker is its own same-UID process and hands accepted
   streams to the supervisor with `SCM_RIGHTS`; handoff is bound to a frontend
   connection lease rather than an endpoint FIFO. The supervisor now receives
   the worker PID at handoff and holds a pidfd; closing a session that does not
   exit within two seconds escalates to `SIGKILL`, so close is a bounded
   lifecycle operation rather than only a queued message. One optional SSH
   bastion is implemented: the app prepares the bastion TCP stream, the worker
   authenticates to it, opens `direct-tcpip` to the final target, and runs the
   final SSH transport inside that channel. The first version shares the
   target's username and identity; per-hop identities and multi-hop chains are
   follow-up work. Remaining: explicit crash injection, leak measurements, a
   worker address-space limit, per-hop identity/keys, forwarding, and moving the
   broker role to a tenant UID.
9. Implement the combined layout: tenant supervisor, tenant broker, and a
   worker-pool tenant with fork-per-session workers.
10. Tenant cross-UID tests are done: `kill` and `ptrace` are denied;
   `SIGCONT` is allowed in-session; `setgroups` is denied; peer
   `/proc/<pid>/status` is readable. Add `NoNewPrivs`, measure cgroups, and
   treat group 3009 and the status exposure as residual or manager-side fixes.
11. If tenants are unavailable, prototype a privileged launcher/image for
    UID/capability-separated workers in one VM.
12. For stock app-only without either route, evaluate one VM per session/role
    only if the same-UID containment is insufficient.

## References

- `libs/libvm_payload` README and `include/vm_payload.h` (AOSP
  `packages/modules/Virtualization`).
- `guest/microdroid_manager/src/main.rs`, especially `exec_task`: launcher,
  UID/GID, capability drop.
- `system/sepolicy/microdroid/system/private/microdroid_app.te` and
  `microdroid_payload.te`: one app domain, vsock listen-only, manager/authfs
  Unix sockets.
- `build/microdroid/init.rc`: cgroup v2 mount, no servicemanager, minimal
  services.
- AOSP `android/MicrodroidDemoApp` and `tests/testapk/src/native/testbinary.rs`
  for the Binder RPC + raw vsock idioms.
