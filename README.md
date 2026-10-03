# Agents Beamdown for Android

Run a full [Letta Code](https://docs.letta.com) server on your Android phone as a
**cloud execution environment** — agents on your Letta server can run tools, execute
commands, and work autonomously on the device. Register it, tap Start, and your phone
appears in `letta environments list` ready for `--computer`-targeted crons and dispatches.

```
┌─────────────┐  wss   ┌──────────────┐
│ Letta Cloud │ ◄────► │  Your phone  │
│  (api.letta)│        │ APK → node   │
└─────────────┘        └──────────────┘
```

- **Foreground service** — runs while screened-off (subject to OEM battery management)
- **Full agent stack on-device** — Bash tool (Debian), file tools, memory (MemFS),
  secrets injection, skills
- **No root required**

## Quick start

1. **Get a key** — create an account at [letta.com](https://letta.com) (free plan available), then Settings → API Keys → create a key (`sk-let-…`)
2. **Install the app** — download the APK from [dl.angussoftware.dev/agents-beamdown](https://dl.angussoftware.dev/agents-beamdown) (phone + Wear OS watch, same file; install guides included)
3. **Connect** — open the app, name your environment, paste the key, tap Start
4. **Dispatch** — your device now appears in `letta environments list` on your Letta server

Privacy: local processing, consent-gated device telemetry, no backend — [policy](https://legal.angussoftware.com/privacy-agents-beamdown/).

## How it works (the short version)

Android's app sandbox blocks normal approaches (no proot, no exec from app data at
modern targetSdk). This app ships a Debian bookworm arm64 rootfs with Node 22 and
letta-code, launched via the **dynamic-loader trick** (`ld-linux.so --library-path …`),
with byte-patches that make glibc and node survive the app-domain seccomp filter
(rseq, set_robust_list, io_uring probes → each returns the exact value its caller
needs to take a clean fallback path). Four binaries in the APK's native lib dir
(extraction-filter compliant), original-name glibc libs staged at runtime.

Full forensic history in the commit log — ten distinct sandbox walls, each found and
fixed empirically. `tools/rootfs-build/patch-binaries.py` is the reproducible patch
set with detailed comments.

## Build

Requirements: Linux host, Android SDK, Node 22, Docker, `qemu-user-static`, zig 0.16.

```bash
# 1. Build the patched rootfs (see tools/rootfs-build/README.md)
cd tools/rootfs-build
./pull-rootfs.sh && ./rebuild.sh && ./patch-binaries.py /path/to/rootfs
tar --hard-dereference -czf ../../app/src/main/assets/rootfs.tar -C /path/to/rootfs .

# 2. Build the APK (injects the four lib*.so binaries into lib/arm64-v8a post-build)
cd ../..
./gradlew assembleDebug
./tools/inject-libs.sh app/build/outputs/apk/debug/app-debug.apk

# 3. Install
adb install app/build/outputs/apk/debug/app-debug.apk
```

Tap **Start environment**. The notification shows registration status; check
`letta environments list` from your desktop.

## Version support matrix

| Component | Pinned | Why |
|---|---|---|
| letta-code | 0.30.27 | byte patches target exact offsets |
| Node | 22.23.2 | io_uring probe offset |
| glibc | 2.36 (bookworm) | rseq/set_robust_list patch sites |

`patch-binaries.py` fails loudly when offsets don't match. Re-deriving patches for a
new letta-code version: run the stracelite harness (in `tools/rootfs-build/`) on the
new build to locate the probe sites, update offsets. The rseq/set_robust_list patches
are pattern-searched and version-resilient within glibc 2.3x.

## Known limits

- **OEM battery management**: Samsung (and others) freeze/kill background work;
  exempt the app (Settings → Battery → Unrestricted) for reliable long-run operation.
- **API key entered at first run** (v0.2.0 onboarding) — builds are key-free;
  the app prompts for your key + environment name on first launch.
- Single architecture (arm64-v8a). Debug-signed; release signing is a TODO.
- Diagnostics suite runs at every service start (adds ~5s; will be debug-gated).

## License

Apache-2.0 (matches the embedded `@letta-ai/letta-code` package).
