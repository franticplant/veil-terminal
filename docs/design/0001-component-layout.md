# Component layout: today, and on the way to Microdroid

Status: describes the code as of 2026-10-27, and how each piece moves. The
target architecture is in [0000](0000-bootstrap-proposal.md), sections 3 to 4.

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
| `core/caps` | `Dialer`, `HostKeyStore` (traits + local implementations) | Engine, local implementations | Traits in the guest; the implementations are Android brokers |
| `guest/engine-host` | `serve()`: one engine on one stream | Shipped in the APK as `libveil_engine_host.so`, run by `HostBackend` over stdio | The Microdroid payload's core (vsock) |
| `android/jni` | Byte pipe into the engine | App process (the "in app" and "service" backends) | Gone (or kept for a local fallback backend) |
| `android/engine-client` | `EngineConnection`, `SessionRegistry`, `ScreenModel`, `LoginSigner`, `NativePipe`, `StreamPipe`, backends (`HostBackend`, `ProcessBackend`, `LocalBackend`) | App | App: the broker ([0002](0002-frontends-and-remote-access.md)) |
| `frontend` | Disposable UI, `SshKeys` (Keystore signer) | App | App; `SshKeys` moves to the Vault package |
| `devd`, `devapi` | Dev control plane: native TLS server (`devd-native`), journal, metrics; its screen in a `:ui` process | Separate package | Separate package; also drives the VM broker |

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
- **Network.** The engine asks a `Dialer`. Today `DirectDialer` connects
  from the app process. Later the Network Broker (the only package with
  `INTERNET`) connects and hands over the stream: an fd within Android,
  or a vsock-forwarded stream into the VM, since fds cannot cross the VM
  boundary (0000 §10.12).
- **Host keys.** The engine asks a `HostKeyStore`. Today `FileHostKeys` is a
  file in the app's data directory. Later the policy core keeps it on the
  Android side and the guest asks over the protocol.
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
3. **The VM.** The `<base>.vm` package boots a Microdroid payload running
   `serve()` on vsock. The app's `VmBackend` gets the vsock fd from AVF and
   wraps it in `StreamPipe`. The probe track (0000 §8b) has to settle the
   permission grant and the guest UID model first.
4. **Guest roles.** Split the engine into SSH and VT workers. The cut is
   between `core/engine` (SSH) and `core/term` (terminal state), joined by
   the same kind of framed stream.

## Not here yet

- The frontend protocol, the gateway and remote access: designed in
  [0002](0002-frontends-and-remote-access.md).
- A capability *ledger* (who granted what to whom, `caps.ledger` in the dev
  API). The traits exist; recording grants comes with the first broker.
- The Vault, Network Broker and VM packages themselves (0000 §3, §9).
- Post-quantum *authentication*. Key exchange is post-quantum already
  (`mlkem768x25519-sha256`, required by default). Signatures stay ECDSA
  P-256 in StrongBox until SSH has interoperable post-quantum signature
  algorithms.
