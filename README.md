# Veil Terminal

An Android SSH workstation that treats remote-facing protocol processing as hostile. The long-term
design runs SSH, terminal parsing and other remote-facing code in separate compartments inside a
pKVM-protected Microdroid VM, with networking, VM control, credentials and the UI split across
separate Android identities. See [docs/design/0000-bootstrap-proposal.md](docs/design/0000-bootstrap-proposal.md) and,
for where each component lives now and how it moves, [docs/design/0001-component-layout.md](docs/design/0001-component-layout.md).

What works today:
- SSH sessions, with password, keyboard-interactive or key login. Keys are
  ECDSA P-256 in Android Keystore or StrongBox, and the engine never sees
  them. Host keys are trust-on-first-use; a changed key is refused.
- A terminal for full-screen programs:
  - 256 and true colors, mouse reporting, bracketed paste;
  - copy-only OSC 52, asked first;
  - scrollback, pinch to change the text size;
  - fullscreen that can cover the notch, all orientations;
  - themes, including AMOLED black.
- Sessions that keep running in the background.
- A typed development API (devd + veil-ctl) for driving the phone from a
  laptop.

It is built incrementally: every stage is an APK you can install and use, and authority moves
behind stronger boundaries over time. Today it is a skeleton: the UI talks to the Rust engine
through the engine protocol, in one process.

## Layout

| Path | What |
|---|---|
| `proto/` | The engine protocol schema, shared by Rust and Kotlin |
| `core/protocol` | Rust protocol types, limits, checked decoding |
| `core/engine` | The engine (no Android dependencies) |
| `core/term` | The terminal emulator boundary: remote bytes in, plain frames out |
| `core/caps` | Capabilities the engine is handed: `Dialer`, `HostKeyStore` |
| `core/obs` | Log ring and counters |
| `core/wire` | Framing for the protocol on streams (process / VM boundary) |
| `guest/engine-host` | The engine as its own process on a stream (the Microdroid payload's core) |
| `android/jni` | The JNI byte pipe into the engine (`libveil_jni.so`) |
| `android/engine-client` | Kotlin: `EngineBackend`, `EngineConnection`, the native pipe |
| `android/frontend` | The disposable v0.x UI; depends only on `engine-client` |
| `android/app` | The package: `dev` and `prod` flavors of one application ID |
| `build/Containerfile` | The only build environment, local and CI |
| `scripts/` | Build, sign, release checks |
| `android/devd` | The dev agent package: listener, pairing, updater (never on a production setup) |
| `android/devapi` | The dev API's method specs and the DevHooks Binder interface |
| `dev/ctl` | `veil-ctl`, the laptop CLI for the dev agent |
| `dev/signing` | The public signing certificate and its pinned digest |

## Building

Locally, everything builds in throwaway podman containers; the host needs only podman and git.
The toolchain is pinned in `build/toolchain.lock`: the container is built from it, and CI installs
the same list on its runners. `scripts/run` runs a command with that toolchain.

```sh
scripts/build dev          # unsigned dev APK (first run builds the container image)
scripts/build devd         # the dev agent; `all` builds dev, prod and devd
scripts/sign dev           # sign into dist/, verify the signer, run the release checks
scripts/ctl --install      # veil-ctl into dist/ and ~/.local/bin
scripts/run cargo test --workspace
scripts/run cargo deny check
scripts/run ./android/gradlew -p android testDebugUnitTest ktfmtCheck
```

`dev` and `prod` share the application ID and signing key, so either installs over the other
(within Android's no-downgrade rule). Only `dev` carries the development control plane, and
`scripts/check-release-apk.sh` fails any prod APK that contains it.

## Driving a phone

Dev builds are controlled from the laptop through a typed API (no shell),
served by the separate dev agent devd and called with `veil-ctl`:

```sh
veil-ctl -a PHONE:7390 pair              # once; approve on the phone
veil-ctl update dist/veil-VERSION-dev.apk
veil-ctl scenario connect-echo host=HOST user=USER password=PASSWORD
veil-ctl logs engine -f
```

Setup, scopes, the method list and the security model are described in
[docs/dev-control.md](docs/dev-control.md).

## License

Apache-2.0. Dependencies are limited to permissive licenses by `deny.toml`.
