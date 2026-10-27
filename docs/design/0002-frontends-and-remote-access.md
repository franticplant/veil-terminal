# Frontends, remote access and the frontend protocol

Status: direction agreed 2026-10-27; nothing here is built yet except the
pieces named as existing. Builds on [0001](0001-component-layout.md).

Goal: use Veil's sessions from somewhere other than the phone's screen, for
example a laptop browser over the tailnet, without widening what an attacker
can do. Two kinds of attacker matter:

- A malicious or compromised **SSH server**, which controls every byte of
  terminal output. The client that displays the session must not be
  exposed to that output's complexity.
- Whoever gets hold of the **remote client or its network path**. That
  person must not be able to become the user's SSH identity.

## Layout: one broker, frontends and workers around it

```
Android (pKVM host side)
┌──────────────────────────────────────────────────────────────────────┐
│ Veil app process: the broker (Java: Keystore/StrongBox live here)     │
│   SessionBroker: owns sessions, grants, journal                       │
│   Signer: semantic checks, approval on the phone                      │
│      │                                                                │
│      │ frontend protocol: attach, input, resize, surface updates      │
│      ├── local UI (Compose; disposable)                               │
│      └── gateway (native process, off by default) ◄── tailnet: browser,
│      │                                                 laptop client  │
│      │ engine protocol                                                │
│      └── workers: SSH + terminal engine (native process today,        │
│          Microdroid VM over vsock later); later graphical workers     │
│                                                                       │
│ devd (dev builds only): the dev plane, never the remote-use path       │
└──────────────────────────────────────────────────────────────────────┘
```

- **The broker holds the authority.** Only the broker signs with keys, opens
  sessions, trusts host keys and changes settings. Workers and frontends can
  only ask. Today `SessionRegistry` in `engine-client` is the broker in all
  but name. The engine already asks for signatures with `SignRequest` instead
  of holding keys.
- **Two protocols, one per direction.**
  - The engine protocol (broker ↔ workers) exists today.
  - The frontend protocol (broker ↔ frontends) is new. The local UI, a
    browser and a future desktop client are all the same kind of client of
    it.
- **The gateway is a separate native process.** A network-facing parser
  must not live in the process that can sign. If the gateway is compromised,
  the attacker gets only what the paired client's grants allow. It reuses
  devd's rustls server and pairing code (`android/devd-native`). It costs a
  few MB and runs only while enabled. Later it can move into a VM of its own.
- **devd is not the remote path.** The dev API has secret-scope methods,
  updates and restarts. Remote use is a prod feature behind the gateway;
  devd stays dev-only.

## Surfaces: three kinds, kept apart

| Surface | On the wire | Hard parsing done by |
|---|---|---|
| Text grid | Cell-state diffs: grapheme, colors, attributes, cursor | The worker |
| Image layer | Decoded RGBA once per image; then placements anchored to cells | The worker |
| Pixel surface | Tiles and copy-rects (GUI and graphical streams, later) | The worker |

The client stays bare-bones. It:

- draws characters into fixed grid cells;
- blits image blocks at cell anchors, over or under the text;
- blits tiles;
- sends input.

It never sees an escape sequence, a font file from the network, an image
codec or a terminal state machine.

### Text grid: state sync, not pixels

We considered streaming text as pixels: a CPU renderer in the worker and a
client that only blits. Text sent as cell state is better:

- It stays crisp at any zoom. Font size and pinch zoom are client-side, with
  no round trip.
- A changed cell is a few bytes where pixels would be hundreds.
- Selection, search and accessibility can happen on the client.
- Most of it exists already. `check_frame` is the render contract, and frames
  between the engine and the app are already acknowledged.

**Acknowledged-state diffs** (the model mosh uses). States are numbered. The
client acknowledges the newest state it has applied. The worker sends the
diff from the last *acknowledged* state to the *current* one, at a capped
rate. Intermediate states are skipped: `seq 1 1000000` costs a handful of
frames. A reconnect, or a client that falls behind, is just a diff from an
older state.

**Containment.** The client renders text with its own fonts, so the
browser's text stack sees the strings. To keep that input dull:

- **Each cell is one validated grapheme cluster with a length cap.**
  Control characters and bidi controls (overrides, embeddings, isolates)
  become visible placeholders.
- **Width is decided by the worker,** never by the client.
- **The client draws per cell at fixed positions,** on a canvas, with
  ligatures off and forced left-to-right. The browser cannot reorder,
  reflow or merge glyphs, so text cannot spoof its own layout.
- **Titles and OSC 8 hyperlinks are separate, sanitized fields.** Links are
  shown, never opened automatically.

### Image layer

Inline images (sixel, the kitty graphics protocol, iTerm2 images) are their
own surface.

- **The worker decodes them, then re-encodes them as raw RGBA** (deflated,
  with size caps). The decoders are the risky part, and they stay in the
  worker.
- **The client receives three messages:**
  - `ImageData(id, w, h, pixels)`, once per image;
  - `Place(id, row, col, cells_w, cells_h, z)`;
  - `Remove(id)`.
- **Placements are anchored to cells,** so images scroll with the text.
- **The client runs no image decoder.** It only copies pixels onto a canvas
  layer.

### Pixel surface

For graphical workers later: `Tile(x, y, w, h, deflate(pixels))`,
`CopyRect`, `Resize`.

- Lossless tiles compress very well for UI content, and browsers decompress
  them with the built-in `DecompressionStream`.
- No video codec. Lossy text looks bad, and a software encoder is a large
  component to put in the worker.

### Input, client to worker

The client sends a small, validated set of events:

- **key:** a code plus modifiers;
- **text:** for mobile keyboards and IME composition;
- **pointer** and **scroll;**
- **viewport:** size and scale;
- **paste:** explicit only.

Selection and copy work like this:

- On the text grid, the client can select locally, because it has the
  cells.
- On pixel surfaces, the worker does selection and sends the text in an
  explicit message, checked against policy.

Either way the clipboard is a deliberate side channel; nothing on the screen
can push into it.

## Access: grants per paired client

| Grant | Allows | Default when paired |
|---|---|---|
| view | Watch sessions | yes |
| operate | Input and resize on open sessions | yes |
| open | Start sessions from saved profiles | no |
| clipboard | OSC 52 and paste in both directions | no |

Never over the gateway: creating or exporting keys, settings, trusting host
keys, or anything devd does.

**Key use always comes back to the phone.** If a remote `open` needs a key,
the signer's normal approval runs on the phone. With per-key biometric
gating that becomes a fingerprint, so a stolen browser session can at most
type into sessions that are already open.

Every attach is journaled and shown on the phone, with a one-tap revoke. The
gateway is off by default. When it is on, it listens on chosen interfaces
only (tailnet or tether), the same way devd does.

## Authentication and end-to-end encryption

- **Pairing.** The browser generates a non-extractable identity key with
  WebCrypto and keeps it in IndexedDB. The phone shows the client's key
  fingerprint, and the user confirms it on both sides. Grants are bound to
  that key.
- **Per connection.** A mutually authenticated ephemeral key exchange gives
  fresh AEAD keys, with forward secrecy. It is hybrid X25519 + ML-KEM-768, to
  match the SSH side. Where WebCrypto lacks ML-KEM, a small audited WASM
  module provides it.
- **Where the encryption ends.** It ends in the worker that produces the
  surfaces, not in the gateway. The gateway, and the Android side generally,
  relays ciphertext. The broker still owns pairing records and grants, and it
  still approves every key use. It does not need to see screen content.
  Moving a worker into a VM therefore also takes its screen content out of
  the Android side's reach.
- **Code delivery is the weak point of any web E2EE.** If the gateway serves
  the page's JavaScript, a compromised gateway can serve a version that leaks
  keys. The client is therefore a **single static HTML file kept on the
  laptop**, opened locally and checked against a published hash. The gateway
  serves no code; it is a relay. A phone-served page with a pinned hash is a
  possible fallback, but it weakens "the gateway is untrusted".
- **TLS on top is for the transport only.** Security does not depend on it,
  so the browser may reach the gateway through Tailscale-terminated TLS (a
  Tailmesh change, the user's call) or a one-time certificate trust.

## Rendering on the phone

The phone's own UI becomes a client of the same text-grid protocol, which it
nearly is today: `ScreenModel` consumes checked frames. Images arrive as the
same overlay.

The UI then holds no terminal logic at all, which is what "disposable UI"
means. It also keeps the Java side's memory small (see
[0003](0003-memory-budget.md)).

## Alacritty and renderers

`alacritty_terminal` stays the terminal state machine in the worker.

- Its **damage tracking** (`Term::damage()`) is what drives the text-grid
  diffs.
- Its **renderer** is not a library. It lives in the `alacritty` application
  crate, needs OpenGL, and loads fonts through FreeType/fontconfig. A
  Microdroid VM has no GPU, and C font parsers do not belong in a hardened
  worker.
- If text ever has to go out as pixels (it does not for the plan above), the
  renderer would be a pure-Rust CPU renderer:
  - `swash` for shaping and rasterizing;
  - a glyph atlas;
  - embedded, trusted fonts;
  - `tiny-skia` for overlays;
  - alacritty's built-in box-drawing glyphs, ported (Apache-2.0, with
    attribution).

## Order of work

1. Name the broker. Define the frontend protocol in `proto/`: surfaces,
   acknowledged-state diffs, input, grants.
2. Move the local UI onto it. This is the real investment; it pays off on its
   own (a disposable UI, less Java memory).
3. Sanitizing rules in the worker. Fuzz targets that assert every frame stays
   well-formed for hostile input.
4. Image layer: sixel and kitty decoding in the worker, overlay in the UI.
5. The gateway (native, from devd-native's server and pairing), plus the
   static browser client.
6. Pixel surfaces, when graphical workers arrive.
