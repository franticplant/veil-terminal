# Veil Terminal

An Android SSH workstation that treats remote-facing protocol processing as hostile. The long-term
design runs SSH, terminal parsing and other remote-facing code in separate compartments inside a
pKVM-protected Microdroid VM, with networking, VM control, credentials and the UI split across
separate Android identities. See [docs/design/0000-bootstrap-proposal.md](docs/design/0000-bootstrap-proposal.md).

It is built incrementally: every stage is an APK you can install and use, and authority moves
behind stronger boundaries over time. Today it is a skeleton: the UI talks to the Rust engine
through the engine protocol, in one process.

## Layout

| Path | What |
|---|---|
| `proto/` | The engine protocol schema, shared by Rust and Kotlin |
| `core/protocol` | Rust protocol types, limits, checked decoding |
| `core/engine` | The engine (no Android dependencies) |
| `android/jni` | The JNI byte pipe into the engine (`libveil_jni.so`) |
| `android/engine-client` | Kotlin: `EngineBackend`, `EngineConnection`, the native pipe |
| `android/frontend` | The disposable v0.x UI; depends only on `engine-client` |
| `android/app` | The package: `dev` and `prod` flavors of one application ID |
| `build/Containerfile` | The only build environment, local and CI |
| `scripts/` | Build, sign, release checks |
| `dev/signing` | The public signing certificate and its pinned digest |

## Building

Everything builds in throwaway podman containers; the host needs only podman and git.

```sh
scripts/build dev          # unsigned dev APK (first run builds the container image)
scripts/sign dev           # sign into dist/, verify the signer, run the release checks
scripts/podman-run cargo test --workspace
scripts/podman-run cargo deny check
scripts/podman-run ./android/gradlew -p android testDebugUnitTest ktfmtCheck
```

`dev` and `prod` share the application ID and signing key, so either installs over the other
(within Android's no-downgrade rule). Only `dev` carries the development control plane, and
`scripts/check-release-apk.sh` fails any prod APK that contains it.

## License

Apache-2.0. Dependencies are limited to permissive licenses by `deny.toml`.
