# Veil Terminal

An Android SSH workstation built around compartments. Remote-facing SSH and terminal code runs in a
protected Microdroid VM; the UI, Keystore signing, host-key policy and network broker stay in the
main app. The VM host is a separate Android package with AVF permissions and no `INTERNET`. The
native process engine remains an explicit fallback, never a silent substitution.

The current stable VM release is `v0.1.0`, validated on a Pixel 8 running GrapheneOS.

```mermaid
flowchart LR
  Remote[(SSH server)]

  subgraph Main["Main APK · io.github.franticplant.veil"]
    UI["UI / SessionRegistry"]
    Keys["Keystore keys<br/>StrongBox / TEE"]
    Pins["Host-key pins"]
    Broker["Native network broker"]
  end

  subgraph VMHost[".vm APK · io.github.franticplant.veil.vm"]
    Host["VmHostService / EngineProxy"]
  end

  subgraph Guest["Protected Microdroid guest"]
    Engine["Rust SSH engine<br/>terminal + broker relay"]
  end

  subgraph Dev["devd APK · development only"]
    Devd["TLS API / journal<br/>logs / metrics / updater"]
  end

  UI -->|engine protocol| Host
  Host <-->|vsock 5000| Engine
  UI --> Keys
  UI --> Pins
  Engine -.->|SignRequest| UI
  Broker -->|TCP| Remote
  Broker -->|connected fd| Host
  Host <-->|vsock 5001| Engine
  Devd -.->|typed Binder| UI
  Devd -.->|VM probe and status| Host
```

Today the main app still holds `INTERNET` and the SSH signing authority. The planned `.net` package
will own networking and `.vault` will own key use; neither is part of this release.

## What it does

- SSH with password, keyboard-interactive or key login. Keys are ECDSA P-256 in Android Keystore,
  using StrongBox by default where available; new keys require strong-biometric authentication for
  each signature. The engine never sees a private key.
- Optional SSH bastion / jump host profiles. The first TCP stream goes to the bastion and the
  worker opens a direct-tcpip channel to the final target.
- Experimental Workers layout: supervisor, separate broker process, spawner, and one short-lived
  worker process per session. Monolith remains the default and stable layout.
- Post-quantum hybrid key exchange (`mlkem768x25519-sha256`) by default, with classical only for
  explicitly allowed hosts.
- Host keys are pinned by the app for both native and VM sessions; a changed key is refused.
- A real terminal: true color, mouse, bracketed paste, scrollback, pinch-to-resize, fullscreen,
  notch coverage, themes, and copy-only OSC 52. The bundled **Hack Nerd Font Mono** gives
  shell prompts, tmux, and editor icons the glyphs they expect without requiring a device font.
- Sessions continue in the background; the VM host is a foreground service.
- VM host metrics, VM console logs and guest uptime are exposed through devd and `veil-ctl`.
- Native engine choice remains available for comparison and recovery; VM failure is reported, not
  hidden.

## Install

For VM mode, install both APKs and grant the one-time AVF permissions:

```sh
scripts/grant-avf
```

Then open Veil → Settings → Engine → **VM (Microdroid)** and restart Veil. Native mode needs only
the main APK. `devd` and the dev APK are development tools and are not required in production.

## Repository map

| Path | What |
|---|---|
| `proto/`, `core/protocol` | Engine protocol schema and checked Rust types |
| `core/engine` | SSH sessions, prompts, framing, rendered frames |
| `core/term` | Terminal emulation boundary |
| `core/caps` | `Dialer`, `HostKeyStore` capability traits |
| `core/obs` | Log rings, counters, process metrics |
| `guest/engine-host` | Engine on a stream; the native host and the VM payload's core |
| `guest/vm-engine` | Microdroid payload and guest broker |
| `android/app` | Main APK: UI, session coordination, Keystore signer, host-key pins |
| `android/vm` | `.vm` APK: AVF host, VM lifecycle, relay, VM metrics |
| `android/engine-client` | Kotlin engine protocol, backends, native broker |
| `android/devd`, `android/devapi`, `dev/ctl` | Development control plane and laptop CLI |
| `docs/design/0001-component-layout.md` | Current and planned component boundaries |

## Build

```sh
scripts/build all           # app dev + prod, devd, .vm APK
scripts/sign prod           # sign and verify the release APK
scripts/sign dev            # dev APK, with DevHooks
scripts/sign vm             # VM host APK
scripts/sign devd           # development agent
scripts/ctl --install       # veil-ctl into dist/ and ~/.local/bin
```

`scripts/build` and `scripts/sign` run in the pinned build environment. Rust checks and Android
tests are described in [docs/dev-control.md](docs/dev-control.md).

## Verify a release

Every release is signed with the pinned Veil key and carries checksums, third-party notices, an
SPDX SBOM and GitHub attestations.

```sh
sha256sum -c SHA256SUMS
gh attestation verify <file> -R franticplant/veil-terminal
```

More: [component layout](docs/design/0001-component-layout.md),
[development control](docs/dev-control.md),
[release notes](docs/release-notes/v0.1.0.md).

## License

Apache-2.0. Dependencies are limited to permissive licenses by `deny.toml`.
