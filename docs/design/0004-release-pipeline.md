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
- **The build environment is pinned.** Builds run in `build/Containerfile`,
  locally and in CI alike:
  - the Debian base image by digest;
  - command-line tools and `rustup-init` by SHA-256;
  - SDK packages by version (sdkmanager checks them against Google's index);
  - Rust by toolchain version;
  - cargo tools by version, with `--locked`.
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

**Caching.** The build container image is cached by the hash of its
Containerfile. The rest of the cache is one directory, keyed on
`Cargo.lock`, every `Cargo.toml`, the Gradle files and the Containerfile. It
holds:

- the cargo registry;
- the compiled Rust dependencies;
- Gradle's dependency, build and configuration caches.

Every run restores the newest such cache, pull requests included. `main`
saves a new one only when the key changes, so a push does not upload
gigabytes, and the image cache is not evicted (the repository limit is
10 GB). Releases do not use this cache.

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
3. **Build.** `scripts/build prod` builds with `VEIL_RELEASE_TAG`. The
   versionName becomes the tag without its `v`. The build refuses a dirty
   tree, or a HEAD that is not the tag's commit. The release caches start
   empty: nothing from earlier runs goes into what ships.
4. **Release check** on the unsigned APK.
5. **Signing.** `scripts/sign prod` signs inside a container without
   network. It refuses a certificate other than the pinned one, and runs the
   release check again on the signed APK.
6. **Attestations.** `SHA256SUMS` is written, then a SLSA build-provenance
   attestation and an SBOM attestation over every file (Sigstore, through
   `actions/attest`).
7. **Publish,** in a separate job that has no signing secrets:
   - checksums and attestations are verified again after the artifact round
     trip, pinned to this workflow;
   - the signing certificate is verified independently, with the runner's
     own `apksigner` against the tag's pinned digest;
   - the release is created with all its assets at once. The tag decides what
     kind of release it is: `vX.Y.Z` is a full release, shown as latest; a tag
     with a suffix (`v0.1.1-pre.1`, `-rc1`) is a pre-release. Between full
     releases, builds are tagged with a suffix. The notes come from
     `docs/release-notes/<tag>.md` when that file exists.

Only the prod APK is published. Dev Veil carries DevHooks and devd is a
remote-control agent, so neither should be one tap away on a release page.
veil-ctl is useful only with devd.

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
- **Container.** Pin the Debian packages too (a snapshot.debian.org date).
  Today only the base image is pinned by digest.
- **Native-library extraction.** Since the engine host became an
  executable, the APK packages libraries uncompressed-for-extraction
  (`useLegacyPackaging`). That is deterministic, but it must be part of the
  comparison.

These are worth doing before the first non-pre-release (v1.0.0): before then,
a stranger has little reason to rebuild.
