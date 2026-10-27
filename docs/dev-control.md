# Development control: devd and veil-ctl

Veil's dev builds can be driven from a laptop through a typed API. There is
no shell and no `exec`: every operation is a named method with declared,
checked parameters. This page covers setting it up and the daily loop. The
design and its reasons are in [design/0000](design/0000-bootstrap-proposal.md),
section 5.

## The pieces

| Piece | Where | What |
|---|---|---|
| devd | `android/devd`, package `io.github.franticplant.veil.devd` | The dev agent: listener, pairing, scopes, access log, updater. Never on a production setup. |
| DevHooks | `android/app/src/dev` | A Binder service in Veil's **dev flavor only**; devd reaches Veil through it. A prod APK does not contain it (`scripts/check-release-apk.sh` checks). |
| devapi | `android/devapi` | Method specs, parameter checking, the `IDevHooks` interface. |
| veil-ctl | `dev/ctl` | The laptop CLI. |

devd and Veil are signed with the same key, and DevHooks is guarded by a
signature-level permission. So only devd can call it, and only on a phone
where both come from the Veil key.

## Build

```sh
scripts/build all            # Veil dev + prod and devd, unsigned
scripts/sign dev             # needs VEIL_KEYSTORE / VEIL_KEYSTORE_PASSWORD
scripts/sign devd
scripts/ctl --install        # veil-ctl into dist/ and ~/.local/bin
```

## First install (once per phone)

Android needs a person for the first install of each package.

1. Get devd onto the phone. Use `adb install`, or serve it on a network
   you pick and open the link in the phone's browser:

   ```sh
   scripts/serve-apk dist/veil-devd-<version>.apk <this machine's tether or tailnet address>
   ```

2. Open devd and:
   - allow it to install apps;
   - switch the **Listener** on (it switches itself off after the chosen
     time).
3. Make it reachable from the laptop, in one of two ways:
   - **Tether:** under "Also listen on", pick the tether interface (for
     example `ncm0`).
   - **Tailnet:** devd listens on the phone's loopback; forward a tailnet
     port to `127.0.0.1:7390` (with Tailmesh: an inbound listener). Limit
     who can reach that port with tailnet ACLs.

   devd never listens on all interfaces. Each interface, and loopback, has
   its own switch. devd shows whether each address is listening, the
   interface is down, or the bind failed. After devd updates itself, it
   switches the listener back on with the time that was left.
4. Pair:

   ```sh
   veil-ctl -a <phone>:7390 pair
   ```

   veil-ctl prints a six-digit code. devd shows a code too. Approve on the
   phone only if they match, and choose the scopes and how long the pairing
   lasts. The code is derived from both certificates, so a machine in the
   middle cannot produce matching codes.
5. Install Veil through devd:

   ```sh
   veil-ctl update dist/veil-<version>-dev.apk
   ```

   Android asks for one tap the first time. After that devd is Veil's
   installer and update owner, and updates install without a tap. devd also
   updates itself the same way.

## Security model

- TLS 1.3 with client certificates. devd's key lives in Android Keystore;
  veil-ctl's key lives in the desktop secret service (`secret-tool`, service
  `veil-ctl`). Each side pins the other's certificate at pairing.
- Scopes are granted at approval:

  | Scope | Allows |
  |---|---|
  | `read` | State, health, metrics, logs. |
  | `read.content` | Screens and scrollback (terminal content). |
  | `operate` | Sessions, input, profiles, host keys. |
  | `secrets` | Passing passwords and auth answers. Values are never logged. |
  | `lifecycle` | Restarting the engine or the app. |
  | `scenario` | Built-in test scenarios. |
  | `update` | Uploading and installing builds. |

- Pairings expire. They can be revoked on the phone.
- Listener settings, pairings and the access log can only be changed on the
  phone. The API has no method for its own configuration.
- The updater installs only Veil packages, signed with the pinned Veil
  certificate (`dev/signing/veil-release-cert.sha256`), and never a lower
  `versionCode`. Who may upload (transport authentication) and what may
  install (APK signature) are separate checks.

## Logs and the journal

devd keeps two logs in one persistent, hash-chained journal:

- **What it did.** Listener, pairing, updates and hooks.
- **Every API call.** Client, method, parameter names (never values),
  outcome, duration, and the listener and peer addresses.

Each entry stores the previous entry's hash and its own SHA-256. Editing or
deleting an entry in the middle breaks the chain, and `journal.verify`
reports where.

The phone alone cannot tell when the newest entries were cut off and the
chain rebuilt. `veil-ctl journal` covers that case:

1. It checks the chain.
2. It checks that the entry it recorded last time (its *anchor*) is still
   there with the same hash.
3. It records the current head as the new anchor.

On the phone, devd's **Logs** tab reads the journal, Veil's app log and the
engine log. It highlights syntax, searches, filters by level and component,
follows new entries, expands an entry to every field, and shows the chain's
state.

## Keys

Veil logs in with ECDSA P-256 keys kept in Android Keystore. They live in
StrongBox when the phone has it, otherwise in the TEE, and the private key
never leaves Keystore.

- **How a login is signed.** The engine never sees the key. When a server
  accepts the key, the engine asks the app's signer to sign. The signer
  checks that the bytes are exactly a public-key login for that user and
  key, and refuses anything else.
- **On the phone.** Under Hosts → Keys you can create a key, copy its public
  key into the server's `~/.ssh/authorized_keys`, and choose it in the
  host's settings.
- **From the laptop.** The methods are `keys.list`, `keys.create` and
  `keys.delete`. `sessions.open`, `profiles.save` and `scenario.run` take a
  `key=NAME` parameter.

## Test host

`scripts/testhost start ADDRESS` runs a disposable sshd.

- **Runtime.** It runs under gVisor when runsc is installed, using the
  host's network directly (hostinet), so sshd binds ADDRESS itself and no
  port forward sits in between. `VEIL_TESTHOST_RUNTIME=crun` uses plain
  crun.
- **Accounts.** The users are `tester` and `root`. The password is new on
  each start; `scripts/testhost password` prints it.
- **Host keys.** They stay the same across rebuilds (the
  `veil-testhost-keys` volume).
- **Installing things.** Under gVisor, sudo does not elevate (rootless
  gVisor ignores setuid bits), so log in as root instead. Under crun
  (`VEIL_TESTHOST_RUNTIME=crun`), `sudo` works for tester with the same
  password, at the price of gVisor's isolation.

**Through Tailmesh, use the laptop's MagicDNS name**, for example
`fedora.<tailnet>.ts.net`, not its `100.x` address. With several tailnets,
Tailmesh gives apps synthetic addresses, and a raw `100.x` address does not
lead to the laptop.

## Daily loop

```sh
scripts/build dev && scripts/sign dev
veil-ctl update dist/veil-<version>-dev.apk
veil-ctl status                    # devd, packages, Veil process and engine
veil-ctl scenario connect-echo host=<test host> user=<user> password=<test password>
veil-ctl logs engine -f            # or: logs app, logs devd
veil-ctl journal                   # check devd's journal chain and anchor
veil-ctl call app.exits            # why recent Veil processes ended (crash, ANR, update, ...)
```

The address is remembered from `pair`, or comes from `-a` or `$VEIL_ADDR`.

Use `scripts/build` rather than a bare Gradle build: it sets the version from
git. A bare build comes out as `0.0.0-local`, and the updater refuses it,
because it never installs a downgrade.

## Metrics and memory

devd samples every Veil process (app, engine) and itself every 2 s while its
listener runs, and keeps 30 minutes. The Metrics tab on the phone charts them:

- pinch to zoom the time window;
- drag sideways to scroll back;
- tap, or hold and drag, to read values on all charts at once;
- double tap to return to live.

From the laptop:

```sh
veil-ctl top                       # live table
veil-ctl call metrics.history prefixes:='["veil-engine."]'
veil-ctl call devd.smaps top=40    # devd's PSS by mapping (boot image, heap, native, code)
veil-ctl call devd.threads         # live threads with their stack tops
```

What the numbers mean on GrapheneOS, and the budget, are in
[design/0003](design/0003-memory-budget.md).

## Calling methods

```sh
veil-ctl methods                   # what this pairing may call
veil-ctl methods --all             # everything
veil-ctl call sessions.list
veil-ctl call sessions.open host=example.org user=me
veil-ctl call sessions.waitPrompt id=1
veil-ctl call sessions.answerHostKey id=1 decision=once
veil-ctl call sessions.input id=1 text='uname -a' enter=true
veil-ctl call sessions.input id=1 'keys:=["ctrl+c"]'
veil-ctl call sessions.waitFor id=1 regex='Linux'
veil-ctl screen 1
```

Writing `k=v` passes a string; devd converts it to the parameter's
declared type. Writing `k:=JSON` passes raw JSON, for lists. An unknown
parameter is refused rather than ignored.

## Scenarios

`scenario.run` drives a real session through the same session layer the UI
uses, and returns a report of each step with its timing:

| Scenario | What it does |
|---|---|
| `connect-echo` | Connects, prints a marker and finds it on the screen, resizes and checks the remote agrees, then exits. |
| `resize-storm` | Sends `count` random resizes, then checks that the final size agrees on both sides. |
| `throughput` | Prints `bytes` of output; reports bytes per second and the frames the engine sent. |
| `reconnect` | Connects and closes `count` times, timing each. |

veil-ctl exits with status 1 when a scenario fails.

## Wire format

For other clients. After the TLS handshake, every message is a 4-byte
big-endian length followed by that much UTF-8 JSON:

- A request is `{"id", "method", "params"}`.
- A reply echoes `id` and carries either `result`, or `error` with
  `{code, message}`.
- Error codes: `unauthorized`, `forbidden`, `unknown_method`,
  `invalid_params`, `not_found`, `unavailable`, `failed`, `timeout`,
  `rate_limited`, `internal`.
- Before pairing, a client may call only `devd.hello` and `pair.request`.
