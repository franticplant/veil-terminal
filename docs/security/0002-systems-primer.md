# Systems primer: authority, confinement, and IPC

This is a from-scratch map of the systems concepts behind Veil. It is written
for someone who has not spent years in kernel or Android security. The short
version:

> A process boundary is not the same thing as an authority boundary.
>
> A capability is not the same thing as a permission bit.
>
> A VM boundary is not the same thing as a trust boundary.

Most confusion comes from mixing those three statements up.

## 1. The machine, in one page

### Kernel and user space

The CPU has at least two privilege levels. The **kernel** runs with full
hardware access; ordinary programs run in **user space**. A user-space program
asks the kernel to do things through **system calls** (`open`, `read`, `write`,
`socket`, `fork`, `mmap`, `kill`, `ptrace`, ...).

The kernel is the final enforcer for files, memory, processes, sockets and
signals.

### Process, thread, address space

A **process** is a running program with its own virtual address space, file
descriptor table, credentials, signal state and memory mappings.

A **thread** is a schedulable execution stream inside a process. Threads in one
process share memory and file descriptors.

A **process boundary** gives you:

- separate virtual memory;
- separate file descriptor tables;
- separate crash domain;
- a kernel-enforced identity (credentials).

It does **not** automatically give you separate authority. Two processes with
the same UID can often signal each other.

### File descriptor

A **file descriptor** (FD) is a small integer referring to a kernel object:
a file, pipe, socket, eventfd, device, etc.

The FD is a **handle**. Holding it is often enough to use the object. Passing
an FD to another process is a real delegation of access.

### Syscall

A **syscall** is the boundary crossing from user space into the kernel. Seccomp
filters syscalls. An LSM such as SELinux adds policy checks around kernel
objects. UID/GID checks are also kernel-enforced.

## 2. Identity and permission

### UID and GID

On Unix, every process has a **real UID**, **effective UID**, **saved UID**,
and usually a real/effective/saved GID plus supplementary groups.

The **UID** is the main traditional identity used for:

- `kill` permission;
- `ptrace` permission;
- file ownership checks;
- some IPC checks.

A **GID** is a group identity. Supplementary groups can grant additional file
or IPC access.

**Root** is UID 0. Root is not magic; it has special kernel checks, but modern
Linux splits root's power into capabilities.

### DAC: discretionary access control

Traditional Unix permissions are **DAC**: owner/group/other read/write/execute
bits, plus ACLs.

It is called discretionary because the owner can often change access.

### ACL

An **access control list** is a list of who may do what to an object.

The key question is:

> “Who are you, and does this list allow you?”

ACLs are identity-based. They do not package the right into a transferable
handle.

### Linux capabilities

Linux **capabilities** split root privilege into named powers, such as:

- `CAP_NET_ADMIN`: configure networking;
- `CAP_SYS_PTRACE`: trace other processes;
- `CAP_SETUID`: change UIDs;
- `CAP_SYS_ADMIN`: a broad, dangerous catch-all.

Each process has capability sets:

| Set | Meaning |
|---|---|
| **Permitted** | capabilities the process may use |
| **Effective** | capabilities currently active |
| **Inheritable** | capabilities that can survive `exec` under rules |
| **Bounding** | upper bound on capabilities a process can ever gain |
| **Ambient** | a narrow way to preserve some capabilities across `exec` |

There are also **file capabilities** attached to executables, and rules such as
`no_new_privs`.

In Microdroid payloads, the effective set is often zero and the bounding set is
not empty. That means “cannot use” is not always the same as “cannot ever
gain.”

### setuid and setgid

A **setuid** executable runs with the file owner's UID instead of the caller's
UID. This is how `sudo` and many system utilities historically work.

It is a classic privilege-escalation surface. `no_new_privs` tells the kernel
not to grant new privileges through setuid or file capabilities.

### MAC: mandatory access control

**MAC** is policy the object owner cannot override. SELinux and AppArmor are
Linux MAC systems.

#### SELinux

SELinux labels processes and objects with security contexts. A policy says which
domains may access which types.

In Microdroid, the payload often runs in a single SELinux domain such as
`microdroid_app`. Two processes in that domain do not get a new SELinux
boundary just because they are separate processes.

SELinux can deny things UID allows, or allow things UID would deny. It is a
different layer.

#### AppArmor

AppArmor is path/profile-based MAC. It is common on Ubuntu; Android primarily
uses SELinux.

### LSM

**Linux Security Modules** is the kernel framework that hosts SELinux,
AppArmor, Landlock, Yama, etc. An LSM gets hooks on kernel operations and can
allow or deny them.

### seccomp

**seccomp** filters syscalls. A process can install a BPF program that says
“allow these syscalls, return `EPERM` for those.”

It is a **syscall** filter, not a general object-authority system. It can stop
`kill` or `ptrace` calls, but it does not tell you whether a socket belongs to
your session.

In this Microdroid guest, installing a seccomp filter was observed to fail with
`EINVAL`. So we cannot rely on seccomp here.

### Landlock

**Landlock** is a Linux LSM for unprivileged filesystem confinement. A process
can restrict its own future filesystem access.

It is not a full sandbox. It does not isolate networking or process signals.
It was unavailable in this guest (`ENOSYS`).

### Yama

**Yama** is an LSM focused on `ptrace` restrictions. It can limit tracing to
descendants or to processes with `CAP_SYS_PTRACE`.

It is one reason two processes with the same UID may still be blocked from
tracing each other.

### `PR_SET_DUMPABLE`

`prctl(PR_SET_DUMPABLE, 0)` makes a process non-dumpable. Among other things,
this blocks ordinary same-UID `ptrace` and core dumps.

It does **not** block signals. It is not a UID boundary.

### `no_new_privs`

`PR_SET_NO_NEW_PRIVS` says “this process and its descendants may not gain new
privileges through setuid, file capabilities, or similar mechanisms.”

It is a one-way ratchet.

### rlimits

**Resource limits** are per-process limits such as:

- `RLIMIT_NOFILE`: maximum file descriptors;
- `RLIMIT_AS`: maximum address space;
- `RLIMIT_CORE`: core dump size;
- `RLIMIT_NPROC`: process count per UID;
- `RLIMIT_CPU`: CPU time.

They limit resources, not access authority. A process can still use every file
its UID can open.

### chroot and pivot_root

`chroot` changes a process's filesystem root. It is not a security boundary by
itself: a privileged process can often escape, and it says nothing about
sockets, signals or kernel objects.

`pivot_root` is a stricter mount-namespace-oriented operation.

### Namespaces

Namespaces change what a process can **see**:

- **mount**: filesystem tree;
- **PID**: process IDs;
- **network**: interfaces, routes, ports;
- **user**: UID/GID mapping and capabilities inside the namespace;
- **UTS**: hostname;
- **IPC**: System V/POSIX IPC objects;
- **cgroup**: cgroup view;
- **time**: clock offsets.

Namespaces are building blocks for containers. They are not the same as
resource limits, and they can have complicated privilege interactions.

In this guest, user namespaces are unavailable, and other namespaces require
privileges the payload does not have.

### cgroups

**cgroups** account for and limit resources: CPU, memory, PIDs, I/O.

- `memory.max` is a hard limit.
- `memory.high` is a throttle/reclaim threshold, not a hard limit.

A cgroup is not an access-control boundary. It answers “how much?” not “who may
touch this object?”

In this guest, cgroup delegation is unavailable to the payload.

### Containers

A **container** is usually:

- namespaces (isolation of view);
- cgroups (resource limits);
- capabilities and seccomp (privilege reduction);
- a root filesystem;
- sometimes an LSM policy.

A container is not a VM. It normally shares the host kernel.

### gVisor

**gVisor** (`runsc`) is a user-space kernel that intercepts application
syscalls. It provides a stronger boundary than a normal container while still
sharing some host facilities.

It is useful for running test hosts, but it is not the Android app sandbox.

### MicroVM, hypervisor, KVM, pKVM, AVF

A **hypervisor** runs virtual machines. KVM is Linux's virtualization
interface. A **microVM** is a small VM with a minimal device model.

**pKVM** is protected KVM on Android: the host Android kernel cannot read
protected guest memory. **AVF** is Android's Virtualization Framework.
**Microdroid** is a minimal Android-based guest payload environment.

A VM gives a separate guest kernel and a stronger failure/memory boundary. It
is the strongest option in this design, but heavier than a process.

## 3. IPC, handles, and capability-like things

### Pipe and socketpair

A **pipe** is a one-way byte stream. A **Unix socketpair** is a bidirectional
local connection between two processes.

Sockets can be **stream** (byte stream), **datagram** (message boundaries), or
**seqpacket** (message boundaries on a stream-like socket).

### Unix domain socket

A local socket addressed by a filesystem path or abstract name. It can pass
credentials and file descriptors.

### TCP

A network byte stream. Ordinary TCP has no process identity attached; TLS or
SSH provides authentication and integrity.

### vsock

**vsock** is a socket family for host↔guest communication. It has a context ID
(CID) and port.

In Veil, the host connects to guest vsock ports 5000 (engine protocol) and 5001
(broker). The guest can listen; it cannot initiate arbitrary outbound vsock
connections.

### Binder

**Binder** is Android's IPC mechanism. It carries transactions, object
references and file descriptors between Android processes. Permissions can be
enforced with Android permissions and signature checks.

Binder is not present inside this Microdroid guest, so it is not an intra-guest
worker bus.

### Shared memory

Shared memory lets processes map the same physical pages. It is fast and
dangerous: access to the mapping is access to the data. It bypasses message
boundaries unless you build your own protocol.

### File descriptor passing: `SCM_RIGHTS`

`SCM_RIGHTS` is an ancillary message on a Unix socket that transfers file
descriptors. The receiver gets a duplicate handle to the same kernel object.

This is a real delegation. If you pass a socket, the receiver can read/write it.
If you pass a listener, the receiver can accept connections. Pass only the FD
the receiver needs.

### `SO_PEERCRED` and `SCM_CREDENTIALS`

These expose the peer's PID, UID and GID on a Unix socket.

They answer “who is on the other end?” They do **not** answer “is this request
authorized for session 42?” You still need a role/session/generation check.

### Framing and serialization

A stream has no message boundaries. You need framing: a length prefix, a
delimiter, or a message protocol. Veil uses `veil-wire` (length-prefixed
frames) and protobuf messages.

Framing gives you message boundaries. It does not give you authorization.

## 4. Security architecture words

### Trust boundary

A **trust boundary** is a place where data or authority crosses between
components with different trust.

Examples:

- app → VM host;
- VM host → guest;
- supervisor → worker;
- worker → remote SSH server;
- app → Keystore.

A boundary is not automatically a security boundary. You have to define what
crosses it and what the receiver is allowed to do.

### TCB: trusted computing base

The **TCB** is the set of components that must be correct for your security
claim to hold.

If the supervisor can read all frames and route all prompts, the supervisor is
in the TCB for session confidentiality and integrity.

If the app holds the key, the app is in the TCB for authentication.

### Attack surface

The **attack surface** is the set of inputs an attacker can reach: parsers,
protocols, syscalls, file formats, network endpoints, IPC.

A smaller attack surface is easier to defend, but only if the remaining code is
actually trustworthy.

### Threat model

A **threat model** says:

- what assets matter;
- who the attacker is;
- what they can do;
- how they enter;
- what crosses which boundary;
- what controls exist;
- what remains.

“The VM protects us” is not a threat model.

### Least privilege / least authority

Give each component the minimum authority needed for its job, for the minimum
time, over the minimum objects.

A worker needs one SSH stream, not the network. A broker needs ciphertext, not
frames. The supervisor needs session control, not the key.

### Ambient authority

**Ambient authority** is authority a component can use without being explicitly
given a handle. A process running as UID 6000 can signal every UID-6000 process
unless something else stops it.

Capability systems try to replace ambient authority with explicit handles.

### Capability, ACL, and the overloaded word

There are two common meanings:

1. **Linux capabilities**: named root privileges (`CAP_NET_ADMIN`).
2. **Capability-based security**: an unforgeable handle/token that grants access
   to a specific object.

A file descriptor is a capability-like handle. If you hold the socket FD, you
can use the socket. `FixedDialer` is a capability-like wrapper around one
stream.

An ACL asks “who are you?” A capability says “you hold the key; you may use
this object.”

Capabilities are easy to delegate by passing the handle. Revocation is harder:
once someone has a capability, you need a way to invalidate it.

### Confused deputy

A **confused deputy** is a component with authority that is tricked into using
that authority for someone else.

Examples:

- a supervisor that forwards a signing request without checking the session;
- a broker that hands a stream to the wrong session;
- an app that signs whatever the guest asks because it only checks the key, not
  the destination and exchange;
- a privileged service that follows a path supplied by an unprivileged caller.

Mitigations: explicit session IDs, generation tokens, leases, peer credentials,
narrow interfaces, and “deny by default.”

### Lease, token, generation, epoch

A **lease** is a time-limited grant: “this connection belongs to session 7,
generation 3, until revoked.”

A **token** is a value used to prove a lease.

A **generation** or **epoch** invalidates old state after restart or reconnect.
It prevents replay of an old session’s messages.

A token is only useful if the receiver actually checks it. A string in a header
is not authorization by itself.

### Revocation

**Revocation** is taking authority away. Closing an FD, ending a session,
deleting a grant, or bumping a generation can revoke access.

Revocation is harder with copied capabilities and cached handles. Design the
lifetime deliberately.

### TOCTOU

**Time-of-check to time-of-use**: you check a condition, then something changes
before you use it.

Example: check that a file is safe, then another process replaces it before you
open it. Use atomic operations, handles rather than paths, and locking where
appropriate.

### Isolation vs containment vs prevention

- **Isolation**: separate state/authority so one component cannot affect another.
- **Containment**: limit the damage when something fails or is compromised.
- **Prevention**: stop the action entirely.

A worker process gives **crash containment** and some memory isolation. It does
not prevent a same-UID signal. A VM gives stronger isolation. Neither is magic.

### Crash containment

If one worker crashes, its process dies and the supervisor can report the
session closed. Other worker processes survive. This is valuable even when the
UID is shared.

### Denial of service

A **DoS** is an availability attack: memory exhaustion, CPU spin, fork storm,
FD exhaustion, signal storms, queue flooding.

Isolation does not automatically prevent DoS. Resource limits and admission
control do.

### Side channel

A **side channel** leaks information through timing, memory access patterns,
CPU contention, cache state, etc. It is usually out of scope until your threat
model includes a local attacker.

### Attestation

**Attestation** is evidence about what code/configuration is running. It is not
the same as isolation. A protected VM can attest to a payload while the payload
is still malicious.

Android uses DICE and AVB/dm-verity in this space. A verifier must define what
measurements it trusts, freshness, and what the attestation is bound to.

## 5. Android-specific layers

### Android app sandbox

Each Android app normally gets a unique UID, a SELinux domain, and a private
data directory. The app cannot read another app's private files without a
shared UID, a content provider, or a privileged service.

This is the primary Android isolation boundary, not “the VM.”

### Shared UID

Apps signed with the same key and configured with `sharedUserId` can share a
UID. They share more authority than ordinary apps.

### Signature permission

An Android permission with `protectionLevel="signature"` is granted only to
packages signed with the same certificate. It is a package-integrity check, not
a per-session authorization system.

### Keystore, TEE, StrongBox
Keys created with an auth-per-use policy can require strong biometric
authentication for every signing operation. The hardware authorizes a
cryptographic operation, not a displayed hostname, and Android exposes strong
biometrics rather than a portable fingerprint-only guarantee. Cooldowns and
one-time codes are application policy unless a separate trusted component
enforces them.


Android Keystore can generate and use keys without exporting private key
material.

- **TEE** is a trusted execution environment in the main processor.
- **StrongBox** is a separate secure element on some devices.

Non-exportability prevents key extraction. It does **not** prevent an
authorized compromised app from asking for signatures. Authorization policy
still matters.

### Microdroid / AVF

Microdroid is a minimal Android guest for a native payload. AVF manages the VM.

The payload runs as an unprivileged UID (often 6000), with zero effective
capabilities in this setup, no shell, no exec, no seccomp, no namespaces, no
delegated cgroups.

It can listen on vsock; it cannot initiate arbitrary vsock connections.

### Android 17 tenants

Manager-native tenants launch separate tenant processes with distinct UIDs
inside one VM. They are the path to role-level UID separation.

Important caveats observed in the probe:

- fixed tenant UIDs, not per-session UIDs;
- cross-UID kill/ptrace denied, but `SIGCONT` was allowed in-session;
- peer `/proc/<pid>/status` was readable;
- group 3009 remained;
- `NoNewPrivs` was not set in the lab;
- cgroups are not a hard per-tenant memory boundary;
- tenant packages usually need a first-install user action unless preinstalled.

### DICE and AVB

**DICE** derives identity from measured boot state. **AVB** verifies Android
images with dm-verity and a verified boot chain.

These help prove what is running. They do not make the running code benign.

## 6. Veil mapping

```text
Android app
  UI, session registry, host-key policy
  Keystore signer
  Network dialing
       │
       ├── .vm host: VM lifecycle and relay; no INTERNET, no keys
       │
       └── Microdroid VM
             supervisor: engine protocol, session IDs, frame gate
             broker: ciphertext stream routing
             spawner: fork/reap workers
             worker: one SSH + terminal session
```

### Current authority

- all guest processes: UID 6000;
- worker per session: separate memory, FDs, SSH keys, terminal state;
- worker signals siblings/supervisor: allowed;
- worker network: no;
- worker identity key: no;
- supervisor frame gate: yes;
- broker: a thread in the supervisor process;
- app/`.vm` relay: sees engine protocol/plaintext frames;
- app owns keys and host policy.

### Target authority

- supervisor tenant UID A;
- broker tenant UID B;
- worker-pool tenant UID C;
- session workers inside C still share UID C;
- role signals mostly denied, `SIGCONT` residual;
- supervisor still sees all frames;
- app still sees terminal content and signs;
- per-session UID or per-session VM only if stronger isolation is required.

### Capability-like objects in Veil

- `Dialer` trait: “I can obtain an approved network stream.”
- `FixedDialer`: one-shot stream capability.
- worker control/data socket: handles to one session’s channels.
- `SCM_RIGHTS`: how the spawner delegates those handles.
- `SignRequest`: a request to the authorization service, not a capability.
- host-key prompt: a policy decision, not a key store.
- session ID: a routing label, not a kernel credential.
- connection token: a lease label; only useful when checked.

## 7. A checklist for reasoning about any component

For each component, ask:

1. **What object does it hold?** memory, FD, socket, key, session?
2. **Who can it signal?** same UID, parent, session?
3. **Who can read its memory?** ptrace, dumpable, same process?
4. **What can it write?** files, sockets, frames, logs?
5. **What can it spend?** CPU, memory, FDs, process slots?
6. **What can it ask for?** signatures, host-key decisions, streams?
7. **How long does the authority last?** one call, one session, forever?
8. **How is it revoked?** close, generation bump, session end, expiry?
9. **What happens if it crashes?** who cleans up?
10. **What happens if it is compromised?** what is the blast radius?
11. **What is trusted to enforce?** kernel, SELinux, UID, app, signer?
12. **What claim are we making?** isolation, containment, or just crash safety?

## 8. Claims to avoid

- “Separate process means secure.”
- “Same UID is fine because the code is trusted.”
- “The broker is isolated” when it is a thread in the supervisor.
- “Tenants isolates sessions” when workers share a tenant UID.
- “The key is in Keystore, so signing is safe.”
- “Protected VM means the app cannot see terminal data.”
- “No INTERNET means no network authority” when a connected FD was passed in.
- “Attestation proves the payload is trustworthy.”
- “A token authorizes a session” when nobody checks it.
- “Capabilities are just root permissions” (two different meanings).

## 9. The bedroom analogy

- **UID** is the name on your badge.
- **DAC** is the list on each door saying who may enter.
- **A capability** is the key to one specific door.
- **A namespace** is a different map of the building.
- **A cgroup** is a quota on electricity.
- **seccomp** is a list of tools you are allowed to pick up.
- **SELinux** is a security guard enforcing a separate policy.
- **A container** is an apartment using the same building utilities.
- **A VM** is a separate building.
- **A lease** is a key that expires.
- **A confused deputy** is a receptionist with a master key who trusts your
  handwritten note.
