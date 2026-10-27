# Memory budget on GrapheneOS

Status: measured 2026-10-27 on a Pixel 8 running GrapheneOS, with devd's
metrics and `devd.smaps`. Targets: **devd ≤ 50 MB, Veil (app + engine) ≤ 75 MB.**

## How we measure

- **PSS** (proportional set size) is the headline number. Shared pages are
  divided among the processes that map them, so PSS summed over processes is
  real RAM use. RSS counts every shared page in full in every process, and
  overstates. Measured on the phone:

  | Process | RSS | PSS |
  |---|---|---|
  | veil-app | 166 MB | 67 MB |
  | devd | 104 MB | 65 MB |

- **What PSS misses:**
  - Pages swapped to zram are not in PSS.
  - PSS moves when other processes start or stop mapping the same
    libraries, which has nothing to do with us.

  So two more numbers belong next to it:
  - **USS** (private pages only): what Android gets back if the process
    dies.
  - **SwapPss.**

  All three come from `/proc/self/smaps_rollup` in one read.
- **CPU %** is Δ CPU time / Δ wall time × 100: the percentage of one core,
  between two samples.
- **Tools:**
  - devd samples every 2 s while its listener runs and keeps 30 minutes. The
    on-phone charts are interactive; from the laptop use `veil-ctl top` or
    `metrics.history`.
  - `veil-ctl call devd.smaps top=40` groups devd's PSS by mapping.
  - `metrics.sample` (in Veil) breaks PSS down by Android's categories.

## GrapheneOS: the price of a Java process

Stock Android forks every app from the zygote, so the framework boot image
and preloaded classes are shared. GrapheneOS starts apps by exec instead, for
ASLR and to keep secrets out of shared memory. Each Java process then carries
a **private** copy of the boot image.

This is devd's service process, with no UI, by mapping:

| Where | MB | Note |
|---|---|---|
| Boot image (`boot-framework.art` and friends) | ~24 | Private on GrapheneOS; shared on stock Android |
| Unlabelled anonymous memory (4000+ mappings) | ~21 | hardened_malloc regions: framework native state and allocator overhead. The allocator reports only ~3 MB allocated |
| Java heap (main space, large objects) | ~8 | Of which 2–6 MB is live |
| Code (APK, framework.jar, libart, ...) | ~6 | File-backed, mostly shared |
| `.bss`, linker, stacks | ~3 | |

**The floor of any Java process here is about 43–48 MB.** Two consequences
follow:

1. Every extra Java process costs ~45 MB. Compartments are therefore native
   processes or VM workers, never Java processes.
2. Whatever must be Java (Keystore, PackageInstaller, the UI) is
   concentrated in as few processes as possible. Those processes keep their
   heaps small.

## Decisions taken

| Change | Before | After |
|---|---|---|
| devd's screen in its own `:ui` process (`UiService` / `IDevdUi`) | 76.7 MB, with UI graphics and heap in the service | Service 58–61 MB with no UI. The screen costs a second process only while it is open |
| Engine as a native process (`veil-engine-host`, packaged as `libveil_engine_host.so`, run over stdio) | `:engine` Java process: 51 MB | **6 MB**, 4–5 threads |
| devd's TLS in Rust (rustls), not Conscrypt | Leaked `ConscryptStatsLogWriter` threads | Steady thread count |
| Bounded journal tail, primitive metric rings | ~112 MB | 76.7 MB |

The engine backend is a setting: Settings → Engine (native / service / in
app); `engine` in `settings.set`. Native is the default.

## Where things stand and where they go

| | Measured now | Estimated after the next steps |
|---|---|---|
| veil-app, terminal on screen | ~67 MB with the UI not yet drawn; plus ~10–20 MB once drawn | ~70–80 MB |
| veil-app, in the background | same | ~50–55 MB, after releasing UI memory |
| veil-engine (native) | 6 MB | 5–8 MB, plus 1–3 MB per session (scrollback) |
| **Veil total** | **73 MB** (134 before) | ~58–65 MB in the background, ~80–90 MB in use |
| devd service | 58–80 MB, ~12% CPU | 45–50 MB, low single-digit CPU |

What gets there:

- **Sampler.** Replace `Debug.MemoryInfo` in devd's 2 s sampler with one read
  of `smaps_rollup`, which gives PSS, USS and SwapPss. MemoryInfo walks every
  mapping, and devd has more than 4000; this is the main suspect for its CPU
  use.
- **Veil.** Release UI memory when Veil goes to the background. Keep
  scrollback bounded (a setting, default about 2000 lines).
- **devd.** Trim Java heap slack. Find out which part of the unlabelled
  native memory is ours.
- **The structural limit.** Veil with the terminal on screen stays above
  75 MB while the UI is a JVM process: the Java floor plus a live UI already
  sit near that. The frontend protocol ([0002](0002-frontends-and-remote-access.md))
  makes the UI a thin client, which is where the remaining saving is.
  devd's always-on part could become a native daemon only if something
  keeps it alive without a Java host process. Android does not offer that
  today, so we do not plan it.
