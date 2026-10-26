# Veil Terminal: bootstrap proposal

Status: proposal, awaiting decisions (section 9). Written 2026-10-26 after reading the Tailmesh
repository (`~/tailmesh/tailmesh-android`, at `b474a10`) and the architecture deep-dive
(portfolio.enesakar.com.tr/deep-dives/android-pkvm-ssh-workstation/).

Facts checked on this machine, not assumed:

- No `android.system.virtualmachine` classes in the public `android.jar` for API 35, 36 or 37. AVF
  is a system API that ships in the virtualization APEX as a shared library.
- No Rust toolchain is installed yet. Go, the Android SDK (build-tools 35–37) and NDKs 23.1/23.2/30
  are installed.
- `adb devices` is empty. Tailmesh development runs without adb, over the USB tether and its own
  remote-control API.

---

## 1. What to reuse from Tailmesh

Tailmesh's development loop works, and most of its rules carry over directly.

**Build and signing**

- Every build type is signed with the project's own release key. Credentials come from a
  properties file outside the repo, or from environment variables in CI. Never the Android debug
  key. That is why a dev build installs over a release (and back) without losing data.
- `versionCode` is minutes since `SOURCE_DATE_EPOCH`, times 10, with the last digit reserved. This
  keeps it monotonic without a counter file.
- Dev builds get a `-dev` `versionName` suffix and a `DEV_TOOLS` compile-time constant, so a release
  build compiles the dev call sites out.
- Dev-only permissions and receivers live in a manifest overlay that only dev builds include.
- `scripts/check-release-apk.sh` inspects the built APK (manifest permissions, receivers, version
  suffix, `BuildConfig` constant, dex call sites). It has a `--expect-dev` mode, so a check that
  stops recognising the dev piece fails in CI instead of passing silently. **Copy this idea
  first.** It is the structural guarantee the brief asks for.
- Pinned NDK, reproducible builds, a verify script, and build temp files outside the RAM-backed
  `/tmp`.

**The updater (`AppUpdater.kt`)**

- It checks package name, signer set (SHA-256 of certificates) and `versionCode` before handing the
  APK to `PackageInstaller`. Android would refuse the first two anyway. Checking them first turns
  an install that fails half-way into a clear refusal.
- `USER_ACTION_NOT_REQUIRED` on Android 12+, with a notification fallback when Android insists on
  a tap.
- `ACTION_MY_PACKAGE_REPLACED` restores whatever was running after the update.
- The upload is saved first, so a failed install can be retried by path.

**The control server (`remotectl`) and `tailmesh-ctl`**

- A method catalog where each method declares its name, whether it mutates, a description, and its
  parameters. Unknown arguments are refused rather than defaulted.
- Read-only sessions vs control sessions, plus a global "allow changes" switch on the phone.
- Approval on the phone with a matching code shown on both sides. Tokens are single-collection.
  Approval requests expire.
- One global authentication rate limit (not per-address).
- An access log that records argument names, never values. The API can read it but not write it.
- Remote control cannot reconfigure itself: no method for its own settings, sessions or log.
- An auto-off timer, and an ongoing notification while it is on.
- A log ring buffer with `since` / `level` / `component` / `grep` filters and a follow stream.
- `/v1/status` health counters and `perf.stats`.
- The CLI caches a token per address and has `call method k=v`, `logs -f`, `pull` and `update`.
- `scripts/phone-health.sh`, a before/after snapshot around an update.
- Tailnet reach through a Tailmesh **inbound listener** (tailnet port → `127.0.0.1:port`). This
  works today with no new code.

## 2. What not to copy

- **The remote shell** (`sh -c` as the app). It is exactly the `exec(command)` primitive the brief
  rules out. Its hash-chained log is good engineering, but the right answer for Veil is narrow,
  typed methods.
- **Password authentication.** The Tailmesh agent notes even hold the test password in plain text.
  Veil should pair keys instead (section 6).
- **Plain HTTP and the `0.0.0.0` listen option.** Veil binds loopback, or one explicitly chosen
  interface address. Never all interfaces.
- **Dev code present but unreachable in release dex.** Tailmesh's blanket ProGuard keep rule leaves
  `RemoteShell` and `AppUpdater` in the release APK, and the Go shell code ships in the shared AAR.
  Veil puts dev code in a separate source set or package, so a release APK does not contain it at
  all. The release checker then asserts the classes and symbols are absent, not just unreachable.
- **`-PdevTools` as a bare Gradle property.** Use a product flavor instead (same `applicationId`).
  Dev and prod artifacts then have distinct task and output names and cannot be confused.
- **A control server in the same process as the privileged state.** That is fine for Tailmesh. In
  Veil, the network-facing listener that parses authentication would sit next to session state and,
  later, credentials. See section 5 for the separate dev principal.
- **Arbitrary file download by path** (`/v1/files?path=` under file roots). Use typed artifact IDs
  instead (`diag.bundle`, `capture.get id=…`).
- **Whole-process pprof by default.** Keep profiling, but as typed, scoped methods.
- **Fork baggage.** The Tailscale-derived Makefile, gomobile and the `tailscale.version` plumbing
  don't apply here.

## 3. Repository and module structure

```text
veil-terminal/
  Cargo.toml                 Rust workspace (portable: Android host and Microdroid guest)
  core/
    protocol/                versioned message types for engine <-> frontend (+ .proto schemas)
    caps/                    capability handles, IDs, ledger (who granted what to whom)
    ssh/                     SSH client glue (transport, auth state machine, channels, PTY)
    term/                    VT parser + terminal state + normalized render model
    session/                 session manager, policy hooks (host keys, auth), lifecycle
    engine/                  composes the above; owns no Android APIs; exposes the protocol
    obs/                     structured events, spans keyed by session/component/cap IDs, metrics
  android/
    settings.gradle          (one Gradle build)
    engine-client/           Kotlin: protocol types + EngineBackend interface + LocalBackend (JNI)
    jni/                     Rust cdylib: the thin byte-pipe JNI bridge into core/engine
    frontend/                Compose UI; depends only on engine-client
    app/                     the v0.x single package: Application, engine ForegroundService,
                             credential provider (Keystore), dev flavor hooks
    brokers/                 later: core/, net/, vm/, vault/, rendergate/ as separate packages
  guest/                     later: Microdroid payload, slot supervisors, role workers
  dev/
    devd/                    separate dev-agent package: listener, auth, updater, typed API
    ctl/                     laptop CLI (veil-ctl)
    api/                     dev API schema shared by devd and ctl
    probes/                  platform/AVF probe definitions (see section 8b)
  scripts/                   check-release-apk.sh, phone-health.sh, build/sign/push helpers
  fuzz/                      cargo-fuzz targets (protocol decode, term parser) from day one
  docs/
```

**Package identities.** Decide these now. They cannot change later without losing app data and
Keystore keys.

- `<base>` (frontend, and in v0.x the whole app)
- `<base>.core`, `<base>.net`, `<base>.vm` and `<base>.vault` (later)
- `<base>.devd` (dev agent; never installed on a production setup)

All are signed with the same key, so signature-protected permissions work between them.

## 4. Engine / UI boundary

**Shape.** The frontend talks to an `EngineBackend` that exchanges **serialized protocol
messages**, never live objects.

```text
interface EngineBackend {
  fun open(hello: ClientHello): Connection        // version negotiation, limits, client identity
}
interface Connection {
  suspend fun send(msg: ClientMsg)                 // bounded queue; suspends on backpressure
  val events: Flow<EngineMsg>                      // ordered per session
  fun close()
}
```

`LocalBackend` serializes the messages and passes them through JNI to the Rust engine running in
the app's foreground service. This is the key point: **the in-process path already carries the
bytes that Binder and vsock will carry later**. Moving the engine to another process, package or
Microdroid then changes the pipe, not the API. Do not use UniFFI-style object graphs at this
boundary.

**Messages (v0.1)**

- **Session lifecycle:** `CreateSession{profileRef}` → `SessionId`; `Connect`, `Disconnect`,
  `Close`; `SessionState{phase, error}`.
- **Policy requests (engine asks, frontend/core answers, with a correlation ID):**
  `HostKeyDecision{fingerprint, alg, known/changed}`, `AuthPrompt{password | kbd-interactive}`.
  The answer carries the request ID. Unanswered requests time out.
- **Input:** `Input{session, bytes}` (already key-encoded by the frontend's keymap, bounded size),
  `Paste{text}` (the engine applies bracketed paste), `Resize{cols, rows, px_w, px_h}`, and
  `Mouse` (later).
- **Render:**
  - `Frame{session, seq, base_seq, dims, damage:[RowUpdate{row, cells or runs}], cursor, modes}`.
  - `modes` is only what the UI needs: cursor shape/visibility, mouse reporting, app-cursor keys,
    bracketed paste.
  - The title is sanitized text with a length cap. The bell is an event.
  - Cells use style indices into a per-session style table that is updated in-band.
- **Resync:** `RequestSnapshot{session}` → a full frame. A restarted UI uses it to reattach.
- **Scrollback:** `ScrollbackRange{session, from, count}`, bounded and served on request.
- **Errors** are explicit typed variants: no stringly exceptions across the boundary.

**Backpressure.** Terminal output coalesces naturally, so the engine keeps the latest state and
sends damage since the last frame the UI **acked** (credit = frames in flight, for example 2). A
slow renderer gets fewer, larger frames, never an unbounded queue.

**Hard limits in the protocol types:** max cols/rows, max message size, max cells per frame, max
style-table size and max title length. Validate them on decode on both sides. The decoders are fuzz
targets.

**Serialization.**

- Recommendation: Protocol Buffers. Use `prost` on the Rust side and Wire or protobuf-lite on the
  Kotlin side (all Apache-2.0). They give schema evolution and cross-language codegen.
- Render frames may later move to a hand-rolled compact encoding, or to shared memory between
  Android processes. That is an optimization behind the same type.

**Ownership.**

- The engine lives in a **foreground service**, not in an Activity or ViewModel. The UI can die
  while sessions survive, which matches the "frontend crash" failure scope.
- `core/engine` takes its authorities as handles: a socket factory, a credential signer, a
  host-key store and a clock. In v0.1 these handles are local implementations. Later they become
  the Network Broker, the Vault, and so on. No global singleton owns networking, keys or state.

**Credentials in v0.1.** Password and keyboard-interactive are answered through `AuthPrompt`. Key
auth arrives as a `CredentialSigner` capability (Keystore ECDSA P-256, since StrongBox has no
Ed25519). The engine sends a *semantic* sign request: session, user, algorithm, and the SSH
userauth blob that the signer parses and checks. It never sends raw `sign(bytes)`. That keeps the
Vault shape from the first key-auth commit.

## 5. Dev control API

**Where it runs: a separate dev-agent package, `<base>.devd`.** It owns:

- the network listener, authentication, sessions, the access log and the auto-off timer;
- the updater (it is the installer of record for every Veil package);
- the typed API surface and probes.

The Veil app's **dev flavor** (same `applicationId`, same key) adds a `DevHooks` bound service.

- It is guarded by a signature-level permission that only the dev flavor declares.
- devd calls it over Binder.
- Prod flavor builds contain neither the service nor the permission, and the release checker
  asserts that.

Why a separate package rather than Tailmesh's in-process server:

- The network-facing auth parser does not share a UID with session state or credentials.
- Updating Veil does not kill the updater, so devd can report post-install health and run the
  scenario that follows.
- It forces the dev surface to be IPC-shaped from day one, just like the brokers will be.
- In the multi-package future, one updater must install `core`, `net`, `vm` and `vault`
  atomically (a multi-package `PackageInstaller` session). That cannot live inside one of the
  packages it replaces.
- A production install simply does not have devd, and dev control of prod is structurally
  impossible because prod has no `DevHooks`.

**Implementation recommendation: fork Tailmesh's `remotectl` (Go, BSD-3) into `dev/devd`.**

- Drop the shell, password login and file roots. Keep auth, approvals, rate limiting, the access
  log, the log stream and health.
- devd is a small Kotlin app around it; `veil-ctl` comes from `tailmesh-ctl`.
- Go then exists only in dev tooling and never ships in a Veil APK, which is itself a nice
  structural property.
- Alternative: rewrite in Rust. That is cleaner tech-stack-wise but costs a week for no new
  capability.

**Typed, scoped methods.** Define them in `dev/api` as a schema. devd and ctl generate or share
types from it, so there is no free-form `k=v` guessing. Each method has a scope, and a pairing or
session is granted a set of scopes at approval time:

| Scope | Methods (v0.1 → later) |
|---|---|
| `read` | `build.info` (flavor, versionCode, signer digest, dev flags), `components.list` / `health`, `sessions.list`, `sessions.state`, `metrics.get`, `logs.query` / `follow`, `caps.ledger` (later), `probes.list` / `results` |
| `read.content` | `sessions.screen` (normalized screen as text + cursor), `sessions.scrollback`. Screen content can contain secrets, so this is its own scope. |
| `operate` | `profiles.createTest`, `sessions.create` / `connect` / `disconnect`, `sessions.input`, `sessions.resize`, `sessions.waitFor{regex, timeout}`, `hostkeys.list` / `forget` |
| `secrets` | `profiles.setTestPassword` (test profiles only; values never logged) |
| `lifecycle` | `components.restart{name}` (the component restarts itself through `DevHooks`; one app can't force-stop another), `engine.restart`, `vm.recreate` (later) |
| `scenario` | `scenario.run{name, params}`: built-in, versioned engine scenarios such as connect-echo, resize-storm, throughput, reconnect, and later fd-leak or worker-crash. Results come back as structured reports. |
| `update` | `update.upload`, `update.install{artifactSet}`, `update.status` |
| `probe` | `probes.run{name}` (platform/AVF probes, section 8b) |

There is no `exec`. If a debugging need can't be met by a method, add a narrow method.

**Observability built in from v0.1.**

- `core/obs` uses `tracing` spans carrying `session_id`, `component_id`, `cap_id`, `pid` / `uid`
  and `role`.
- It feeds a bounded ring buffer and counters (bytes in and out per boundary, operation latencies).
- `caps.ledger` starts trivial in v0.1 (everything in one process, but every grant still gets an
  ID, grantor, grantee and kind). That is what will later answer "which broker granted this
  socket".

## 6. Transport: tether and tailnet

**Listener.**

- devd binds `127.0.0.1:<port>` by default.
- Optionally it binds one explicitly selected interface address (the tether, `ncm0`), set on the
  phone. When that interface goes away the listener follows it rather than widening.
- It never binds `0.0.0.0`.
- The loopback port is reachable by **every app on the phone**. Authentication is therefore not
  optional even on loopback, and this is why section 2 drops passwords.

**Tailnet.**

- Expose devd through a Tailmesh inbound listener (tailnet port → loopback), which already works.
- Restrict that port with tailnet ACLs to the laptop's node or tag.
- Limitation: the forward arrives from `127.0.0.1`, so devd cannot see the tailnet peer identity.
  The application-layer auth below carries identity instead.
- Later option: Tailmesh could pass the WhoIs identity (for example a PROXY-protocol header).
  Embedding a second tsnet node in devd is possible, but Tailmesh already owns the VPN slot, and
  devd should not become a tailnet node.

**Authentication: pair once, then mutual TLS with pinned keys.**

1. `veil-ctl pair` generates an Ed25519 client key. devd shows a pairing request with a short code
   on the phone (the Tailmesh approval UX).
2. The user approves and picks scopes. devd pins the client public key, and the client pins devd's
   certificate key, which devd holds in Keystore.
3. Every connection is TLS 1.3 with both sides pinned. No passwords and no bearer tokens on the
   wire to steal.
4. Pairings expire, can be listed and revoked on the phone, and are never manageable through the
   API.

Keep Tailmesh's rules: the global auth rate limit, the auto-off timer, the ongoing notification,
and the access log (names, not values) with every privileged action.

## 7. Signed update workflow

```text
veil-ctl dev  =  build (gradle dev flavor, cargo-ndk) -> sign (our key, apksigner v2+v3+v4)
              -> check-release-apk.sh --expect-dev -> upload artifact set (sha256 per APK)
              -> devd verifies: package in the Veil set, signer digest == pinned Veil cert,
                 versionCode >= installed, all APKs same versionCode
              -> PackageInstaller session (multi-package once there are several),
                 USER_ACTION_NOT_REQUIRED
              -> wait for MY_PACKAGE_REPLACED / health via DevHooks
              -> optional scenario.run -> results + logs back to the laptop
```

- **Transport authentication and APK authenticity stay separate.** mTLS decides who may *upload
  and request* an install. Android's signature check, plus devd's pinned-cert check, decides what
  *can* install.
- devd's pinned-cert check matters for **first installs** of a new component package, where
  Android has no existing signer to compare against.
- **Version rules.**
  - All Veil packages share one `versionCode`, the Tailmesh time scheme taken from the commit time.
  - A dev and a release build of the same commit have equal codes, so they swap freely.
  - Going back to an older release after installing a newer dev build is impossible without an
    uninstall (non-debuggable app on a user build). devd refuses such an install and says why.
- **Self-update of devd** works like Tailmesh's self-update.
- **First install** of each package needs one manual tap, or adb. Silent updates then work because
  devd is the installer of record.
- **Key management.** Create a new Veil signing key; do not reuse Tailmesh's. Enable v3 signing so
  a rotation lineage is possible later. The key identity also feeds Microdroid's payload
  verification and the VM's DICE-derived secrets, so key stability matters more here than for a
  normal app.

## 8. Smallest v0.1 vertical slice

**Goal:** install on the Pixel 8 and get a real interactive shell, driven from the laptop.

1. Repo skeleton (section 3), a Cargo workspace built via `cargo-ndk` from Gradle, and `dev` / `prod`
   flavors with the same `applicationId` and our key.
2. `check-release-apk.sh` with `--expect-dev`, run in CI on both flavors.
3. Rust engine: SSH client (recommended: `russh`, Apache-2.0), host-key TOFU with a known_hosts
   store, password and keyboard-interactive auth, a PTY (`xterm-256color`), shell, and
   window-change.
4. Terminal core: a VT parser and state producing the normalized frames of section 4.
   Recommendation: `alacritty_terminal` (Apache-2.0) behind our own model. Alternative:
   `wezterm-term` (MIT) if Kitty graphics matters early.
5. `LocalBackend` (JNI byte pipe) and the engine in a foreground service (`specialUse` type).
6. Disposable UI:
   - profile list and edit;
   - host-key prompt;
   - password prompt;
   - a terminal surface: a custom `View` drawing the cell grid, with its own `InputConnection` for
     the IME, plus an extra-keys row (Esc, Ctrl, Alt, Tab, arrows);
   - a session switcher and a status line.
7. devd v0.1 with pairing, mTLS and the scopes `read`, `read.content`, `operate`, `secrets` and
   `update`. Methods: `build.info`, `components.health`, `sessions.*`, `sessions.screen`,
   `sessions.waitFor`, `logs.*`, `update.*`.
8. `veil-ctl` plus one scenario: `connect-echo` (connect to a test host, run `echo`, assert screen
   text, resize, disconnect).
9. Fuzz targets for protocol decode and the terminal parser, run locally for now.

**8b. A parallel validation track, started now and kept small.**

Create the real `<base>.vm` package early. In v0.x it contains *only* probes:

- MANAGE_VIRTUAL_MACHINE is then granted to the UID that will own it permanently;
- the probe results stay useful as regression checks after every Android update.

Run the probes through devd (`probes.run`) and order them risk-first:

1. AVF feature and capabilities on this phone in **Android user 10**; protected and non-protected
   support; available Microdroid OS list; memory limits.
2. Compile against the AVF system API from Gradle (stubs, `compileOnly`, `<uses-library
   android:name="android.system.virtualmachine">`). Then `pm grant --user 10 <base>.vm
   android.permission.MANAGE_VIRTUAL_MACHINE` over adb (wireless debugging works), and check
   whether the grant **survives an update** installed through devd.
3. Boot a Rust payload (`AVmPayload_main`) in a non-protected VM, then a protected one. Measure
   boot latency and resident memory.
4. vsock / RpcBinder: round-trip latency and throughput.
5. **Guest privilege model.** Find out:
   - the payload's UID, capabilities and SELinux domain;
   - whether it can fork and exec binaries from the APK;
   - whether it can apply seccomp and rlimits;
   - **whether per-role guest UIDs are achievable** (AVF multi-tenant / payload privilege
     support on this Android version).

   This decides whether the "separate guest UID per role" design holds as drawn.
6. Does an existing VM instance still boot after its APK is updated through the dev loop (instance
   image, rollback protection)? If not, the dev loop needs a `vm.recreate` step.
7. Can another UID that lacks INTERNET use a connected TCP socket that `<base>.net` created and
   passed over Binder (the socket's owner, firewall chains, data saver)?
8. Rust `std`, `tokio` and `russh` running inside the guest.
9. Keystore: StrongBox P-256 sign latency, and attestation.

## 9. Decisions needed before implementation

1. **Package base name** (`<base>`) for all identities. This is permanent.
2. **devd as a separate package** (recommended) vs in-app like Tailmesh.
3. **devd implementation:** fork Go `remotectl` (recommended, fastest, proven) vs a Rust rewrite.
4. **Dev auth:** pairing plus pinned mTLS (recommended) vs Tailmesh-style approval tokens over
   plain HTTP.
5. **Protocol encoding:** protobuf (recommended) vs FlatBuffers vs custom.
6. **SSH library:** `russh` (recommended). Avoid `libssh` (LGPL). libssh2 (BSD) is the fallback.
7. **Terminal core:** `alacritty_terminal` vs `wezterm-term` vs our own on `vte`.
8. **minSdk:** 34 or higher (recommended). AVF and modern Keystore assumptions make older targets
   pointless, and it cuts compatibility code.
9. **Key auth in v0.1** (Keystore P-256 through the signer capability) or v0.2.
10. **Repo hosting, CI, license and commit identity.** Tailmesh commits as `fran` with SSH signing;
    should Veil do the same? Also choose the project license and a `cargo-deny` policy
    (permissive-only allowlist).
11. **New signing key** creation. You run it; I never touch the key material.

## 10. Conflicts with Android platform constraints

1. **VM permission.**
   - `MANAGE_VIRTUAL_MACHINE` is not a normal app permission. On a stock Pixel a sideloaded app
     gets it only through `adb shell pm grant`, a development grant.
   - So adb (cable or wireless debugging) is needed at least once per fresh install of the VM
     package. Uninstalling loses the grant. Whether updates keep it must be verified (probe 2).
   - The AVF API is a system API (absent from public `android.jar`, confirmed above), so the
     build needs stubs.
   - This is a real constraint on distributing Veil's VM mode to anyone else.
2. **Per-role guest UIDs inside Microdroid are unverified.** A normal Microdroid payload runs as
   one unprivileged process. Splitting roles across UIDs depends on AVF features and payload
   privileges that may not exist on this device's version. Probe 5 settles it. The fallback is
   per-role processes sharing one UID, separated by seccomp, rlimits and exact FD sets: weaker,
   but still layered.
3. **Separate Android UIDs mean separate packages**, since `sharedUserId` is deprecated.
   - Each must be installed (the first time by hand), versioned in lockstep and updated
     atomically.
   - One app cannot force-stop another, so restarts are cooperative.
   - Keystore keys are bound to the Vault package: uninstalling it destroys them.
   - Upside: `INTERNET` can be left out of every manifest except `<base>.net`, so the kernel and
     netd enforce "no network authority" for the others.
4. **Silent updates** need Android 12+, devd as the installer of record (update ownership), a
   reasonably current `targetSdk`, and "Install unknown apps" for devd. The first install always
   needs the user.
5. **No downgrades** on a user build for non-debuggable apps. The dev ↔ release interchange works
   only in the non-decreasing `versionCode` direction (section 7).
6. **A protected, non-debuggable VM has no guest console or logcat for us.** All guest
   observability must flow over our own vsock channel. Design `core/obs` export for that now.
7. **Background limits.**
   - Sessions need a foreground service with a declared type (Android 14+).
   - devd's listener needs one too, or Android kills it.
   - Starting a foreground service from the background is restricted, which is why Tailmesh
     restores from `MY_PACKAGE_REPLACED`.
   - Doze will still interrupt idle SSH connections. Plan keepalives and reattach (tmux) rather
     than fighting the OS.
8. **Loopback is shared by all apps.** "Listen on 127.0.0.1" is not access control (section 6).
9. **Tailnet peer identity is lost** through Tailmesh's loopback forward, so identity must be
   application-layer. The brief's "constrained by tailnet ACLs" holds for reachability, not for
   authentication.
10. **Android Protected Confirmation** (mentioned in the deep-dive's roadmap) is deprecated in
    recent Android. Treat it as likely unavailable on the Pixel 8 until a probe says otherwise.
    StrongBox has no Ed25519, so hardware-backed SSH keys are `ecdsa-sha2-nistp256` (or RSA).
11. **The phone runs Veil in Android user 10.** Every adb command needs `--user 10`. AVF support
    for secondary users and profiles is probe 1, before anything else in the VM track.
12. **Cross-VM file descriptors don't exist.** The "one connected TCP socket" capability ends at the
    VM Broker, and the guest gets a byte stream over vsock. The host-side narrowing is still real;
    the doc should just not imply the guest holds the socket.
