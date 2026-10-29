# Release pipeline: what CI builds, signs and attests

Status: written 2026-10-27. CI and the release workflow are in
`.github/workflows/`. The reproducibility check is planned (the last section).
The rules follow Tailmesh's.

## Rules

- **What people install comes from CI.** Locally built APKs are for our own
  phones, through `veil-ctl update`. They are never uploaded to GitHub.
- **One signing key for everything.** The pinned certificate digest is in
  `dev/signing/veil-release-cert.sha256`. Dev and prod builds share the
  package identity and the key, so either installs over the other without
  losing data.
- **The secrets are reachable only from the `release` environment.** That
  environment admits only `main` and `v*` tags. Pull requests, Dependabot's
  included, never reach a job that can sign.
- **Repository rulesets:**
  - `main` takes signed commits only, with no force-pushes;
  - `v*` tags are immutable;
  - a published release cannot be rewritten after the fact.
- **The toolchain is one pinned list, `build/toolchain.lock`.** Both places a
  build runs install exactly that list:
  - **CI** installs it on the runner (`.github/actions/setup-build`). CI builds
    no container image.
  - **Locally**, `build/Containerfile` installs it in the build container,
    whose base image is pinned by digest.

  How each tool is pinned:
  - the JDK by major version;
  - SDK packages and the NDK by version (sdkmanager checks them against
    Google's index);
  - the Rust toolchain by `rust-toolchain.toml` (rustup checks every
    component; the setup action fails if the lock file disagrees);
  - cargo-ndk, cargo-deny and cargo-about as release binaries, checked by
    SHA-256;
  - musl-tools on the runner by apt version.

  `scripts/run` runs a command with that toolchain: directly on the runner in
  CI (`VEIL_HOST_TOOLCHAIN=1`), in the container locally.
- **Actions are pinned by commit SHA.** Dependabot moves the pins, together
  with the Gradle, Cargo and container dependencies. Every update goes through
  CI, including `cargo deny`.

## Every push and pull request (`ci.yml`)

1. Rust: format, clippy with `-D warnings`, tests, then `cargo deny`
   (licenses and advisories).
2. APKs built unsigned. Then Kotlin format, unit tests and lint.
3. The release check. The prod APK must carry no dev pieces: no DevHooks, no
   dev classes, no install permissions, not debuggable. The native engine,
   its host and the third-party notices must be present. The check runs
   inverted on the dev APK, so a broken check cannot pass silently.
4. On pushes to `main`: dev Veil and devd are signed in the `release`
   environment. They are kept as workflow artifacts for 30 days, for
   development; they are never release assets.

A newer push to the same branch or pull request cancels the older run. When
a run fails, the test and lint reports are uploaded.

**Caching.** Four caches, each keyed on what decides its contents:

- **The Rust toolchain and cargo tools,** keyed on the lock file.
- **The NDK,** keyed on its version.
- **The cargo registry and target directory,** keyed on the lock file and
  `Cargo.lock`, falling back to the newest entry for the same toolchain.
- **Gradle's dependency, build and configuration caches,** through
  `setup-gradle`. Only `main` writes them; pull requests read them.

Releases use none of the build caches.

## Third-party notices

Every APK carries `assets/third_party_notices.txt`. It is shown in the app
under Settings → Open-source licenses, and attached to each release as a file
of its own. The `<variant>ThirdPartyNotices` task in `android/app/build.gradle`
generates it from two sources:

- **Java and Kotlin libraries:** every module on the variant's runtime
  classpath, mapped to its license by group. The license texts are in
  `android/notices/`, verbatim, including protobuf's copyright line. A
  dependency from a group that is not in the table fails the build, so a new
  dependency cannot ship without its license.
- **Rust crates in the native engine:** `cargo about`, configured by
  `about.toml` and `about.hbs`. It runs offline, for the Android targets,
  without build or dev dependencies and without our own crates. The licenses
  it accepts match `deny.toml`.

v0.0.1 shipped before the notices existed. v0.0.2 is the first release that
carries them.

## Releases (`release.yml`)

Pushing a tag `vX.Y.Z` does the following:

1. **SBOM.** An SPDX SBOM is generated from the pristine checkout.
2. **Tests and dependency checks.** Rust tests and `cargo deny` run again,
   at the tag.
3. **Build.** `scripts/build all` builds Veil (prod and dev), devd and the
   `.vm` host with `VEIL_RELEASE_TAG`, and `scripts/ctl` builds veil-ctl
   (static, musl). The versionName becomes the tag without its `v`. The build
   refuses a dirty tree, or a HEAD that is not the tag's commit. The release
   caches start empty: nothing from earlier runs goes into what ships.
4. **Release check** on the unsigned prod APK, and inverted on the dev APK.
5. **Signing.** `scripts/sign` signs each APK (`prod`, `dev`, `devd`, `vm`)
   on the runner, in the only job that holds the key. Locally it signs in a
   container without network. It refuses a certificate other than the pinned
   one, and runs the release check again on the signed APK.
6. **Notices and attestations.** Each APK's notices are extracted as a file
   of their own, next to veil-ctl's. `SHA256SUMS` is written, then a SLSA build-provenance
   attestation and an SBOM attestation over every file (Sigstore, through
   `actions/attest`).
7. **Publish,** in a separate job that has no signing secrets:
   - it waits for CI's result on the tagged commit and publishes nothing
     unless CI passed;
   - checksums and attestations are verified again after the artifact round
     trip, pinned to this workflow;
   - the signing certificate is verified independently, with the runner's
     own `apksigner` against the tag's pinned digest;
   - the release is created with all its assets at once. The tag decides what
     kind of release it is: `vX.Y.Z` is a full release, shown as latest; a tag
     with a suffix (`v0.1.1-pre.1`, `v0.1.0-rc.1`) is a pre-release. Between full
     releases, builds are tagged with a suffix. The notes come from
     `docs/release-notes/<tag>.md` when that file exists.

Every target is published:

- Veil;
- Veil dev;
- the `.vm` host;
- devd;
- veil-ctl;
- the third-party notices of each.

The release notes carry a table of the files, saying which one to install
(`veil-X.Y.Z.apk`) and that the `.vm` APK is required for VM mode. Dev Veil
and devd are development tools; the dev APK includes DevHooks, and devd is a
remote-control agent, so both are useful only when you want them.

### Checking a download

```sh
gh attestation verify veil-X.Y.Z.apk -R franticplant/veil-terminal
sha256sum -c SHA256SUMS
apksigner verify --print-certs veil-X.Y.Z.apk   # SHA-256 digest must equal dev/signing/veil-release-cert.sha256
```

The attestation proves which workflow, commit and run produced the file. The
certificate digest proves it is signed with the Veil key. Android enforces
the key on every update, so a different key cannot install over Veil.

## Reproducibility (planned)

Goal, as in Tailmesh: a second runner, which has no secrets, rebuilds the tag
from source in a different directory. It copies the official signature onto
its own build (`apksigcopier`) and checks the result matches the released APK
byte for byte. Anyone can repeat the check with a script. A mismatch fails
that job but does not block the release.

To get there:

- **Rust.**
  - Remap the source paths (`--remap-path-prefix` or `trim-paths`), so the
    checkout and cache paths do not end up in the `.so` files.
  - Check that `strip = "debuginfo"` leaves no build-id that depends on
    paths.
  - Build twice in different directories and diff the outputs.
- **Gradle and R8.**
  - Check that dex, the resource tables and the zip ordering are
    deterministic across two runs.
  - The version is already a function of the commit (commit time and tag), so
    no clock enters the build.
- **Toolchain.** The runner image (Ubuntu) and the JDK distribution are
  pinned only by major version, and the local container's Debian packages
  only by its base image. A rebuild that has to match byte for byte should
  pin these too (for example a snapshot.debian.org date).
- **Native-library extraction.** Since the engine host became an
  executable, the APK packages libraries uncompressed-for-extraction
  (`useLegacyPackaging`). That is deterministic, but it must be part of the
  comparison.

These are worth doing before the first non-pre-release (v1.0.0): before then,
a stranger has little reason to rebuild.
