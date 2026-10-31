# Component layout: today, with the VM

Status: describes the code as of 2026-10-29, after the first protected-VM
release candidate (`v0.1.0-rc.1`). The target architecture is in
[0000](0000-bootstrap-proposal.md), sections 3 to 4.

The rule this layout follows: every boundary that will one day cross a
process or a VM already exists in the code. It carries encoded messages or
goes through a capability trait, never shared objects. Moving a component
then changes the pipe or the implementation behind a trait. The component
itself does not change.

## Components

| Crate / module | What it is | Lives today | Lives in the Microdroid layout |
|---|---|---|---|
| `core/protocol` | Engine protocol types, limits, decoding, `check_frame` (the render contract) | Everywhere | Everywhere |
| `core/wire` | Framing on byte streams | The engine host | Guest payload (vsock), host side |
| `core/term` | Terminal emulator: remote bytes in, plain frames out | Engine, app process | Guest: VT worker |
| `core/engine` | Sessions, SSH (russh), prompts, frame pacing | Native process `veil-engine-host` (default); `:engine` service or app process (JNI) as fallbacks | Guest: SSH worker + supervisor |
| `core/obs` | Log ring, counters | Engine | Every component, exposed over its protocol |
| `core/caps` | `Dialer`, `HostKeyStore` (traits) | Engine; frontend pins are the host-key authority | Traits in the guest; the implementations are Android brokers |
| `guest/engine-host` | `serve()`: one engine on one stream | Shipped in the APK as `libveil_engine_host.so`, run by `HostBackend` over stdio | The Microdroid payload's core (vsock) |
| `guest/vm-engine` | Microdroid payload: engine protocol on 5000 and host broker on 5001 | `.vm` APK payload | The protected guest; the experimental workers payload moves the broker to its own same-UID process |
| `android/jni` | Byte pipe into the engine | App process (the "in app" and "service" backends) | Local fallback only |
| `android/engine-client` | `EngineConnection`, `SessionRegistry`, `ScreenModel`, `LoginSigner`, `NativePipe`, `StreamPipe`, `VmBackend`, `NetBroker`, backends | App | App: coordinator and broker |
| `android/vm` | AVF host, `VmHostService`, `EngineProxy`, VM metrics/logs; no `INTERNET` | `.vm` APK | `.vm` APK |
| `frontend` | Disposable UI, `SshKeys` (Keystore signer), host-key pins | App | App; `SshKeys` moves to the Vault package |
| `devd`, `devapi` | Dev control plane: native TLS server (`devd-native`), journal, metrics; its screen in a `:ui` process | Separate package | Separate package; also drives the VM host |

## Boundaries

```
 frontend ──(SessionRegistry API)── engine-client ──[protocol bytes]── engine
                                         │                               │
                                   LoginSigner ◄──── SignRequest ────────┤
                                   (SshKeys/Keystore)                    │
                                                                 Dialer, HostKeyStore
                                                                   (core/caps traits)
```

- **Engine protocol** (`proto/veil/engine/v1/engine.proto`). Everything the UI
  and the engine say to each other. Today it crosses JNI as whole messages.
  Next it crosses a stream with `veil-wire` framing: `StreamPipe` on the
  Kotlin side, `serve()` on the engine side. Both are implemented and tested
  over a Unix socket.
- **Signing.** The engine never holds private keys. It sends a
  `SignRequest`, and the signer (`LoginSigner`, today `SshKeys` in the app)
  checks the request is a login before Keystore/StrongBox signs it. In the
  layout the signer is the Vault package; the protocol message stays the
  same.
- **Network.** The engine asks a `Dialer`. Native mode dials directly;
  VM mode uses `NetBroker` (a native process in the main APK) to dial the
  SSH endpoint and passes the connected fd to `.vm`, which relays it to the
  guest broker on vsock 5001. Later the Network Broker package owns the
  `INTERNET` permission and the same broker contract.
- **Host keys.** The engine asks a `HostKeyStore`. The frontend pin store is
  authoritative for native and VM sessions; the guest always asks and the
  app answers. The old per-backend `known_hosts` file is not a competing
  trust authority.
- **Rendering.** Only `Frame`s of plain cells leave the engine.
  `ScreenModel` validates every frame with the same rules as
  `veil_protocol::check_frame`. The engine's randomized tests check those
  rules on the Rust side. In the layout this is RenderGate's job.

## Moving to Microdroid, in steps

1. **Engine in a separate Android process. Done.** `HostBackend` starts
   `libveil_engine_host.so` from the native library directory and speaks to
   it over stdio with `StreamPipe`. It runs in 6 MB, where the `:engine` Java
   process took 51 MB on GrapheneOS ([0003](0003-memory-budget.md)). Compartments
   are native processes or VMs for that reason.
2. **Brokered network.** Implement `Dialer` over a request to the app (or
   the Network Broker), which returns a connected stream.
3. **The VM. Done for the first release candidate.** The `<base>.vm`
   package boots a Microdroid payload running `serve()` on vsock 5000; the
   app's `VmBackend` gets the vsock fd from AVF. A separate host broker
   relays network streams over vsock 5001. `.vm` has AVF permissions, no
   `INTERNET` and no keys.
4. **Guest roles.** Split the engine into SSH and VT workers. The cut is
   between `core/engine` (SSH) and `core/term` (terminal state), joined by
   the same kind of framed stream. The first VM release still runs the
   engine as one guest payload. The Microdroid probe in
   [0005](0005-guest-decomposition.md) narrows this: the app payload cannot
   exec, use seccomp/namespaces/cgroups, or change UID. A fork split is
   therefore containment, not full authority isolation. If a privileged
   launcher is available, UID/capability separation inside one VM is
   possible through a privileged launcher or Android 17's built-in tenants;
   on a stock app-only build neither is available automatically, and separate
   VMs are the hard boundary. See 0005 before implementing the split.

## Not here yet

- The frontend protocol, the gateway and remote access: designed in
  [0002](0002-frontends-and-remote-access.md).
- A capability *ledger* (who granted what to whom, `caps.ledger` in the dev
  API). The traits exist; recording grants comes with the first broker.
- The Vault and Network Broker packages (0000 §3, §9). The `.vm` package
  exists; its class split and stricter end-to-end lease routing are follow-up
  work.
- Post-quantum *authentication*. Key exchange is post-quantum already
  (`mlkem768x25519-sha256`, required by default). Signatures stay ECDSA
  P-256 in StrongBox until SSH has interoperable post-quantum signature
  algorithms.
