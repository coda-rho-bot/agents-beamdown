# Rootfs build pipeline

Reproduces the Debian arm64 rootfs with all byte patches for the Android app.

## One-time host setup

- qemu-user-static (aarch64 emulation for verification)
- Docker (Debian rootfs pull — see pull-rootfs.sh)
- Node 22 on host (npm cross-install)
- zig 0.16 (cross-compile node-pty, stracelite)
- patchelf is NOT used — it corrupts the loader's hash tables (see git history)

## Steps

1. `pull-rootfs.sh` — fetch Debian bookworm arm64 base
2. `rebuild.sh` — install node 22.23.2 + letta-code (pinned 0.30.27), swap
   native deps to arm64 (sharp, ripgrep), zig-build node-pty
3. `patch-binaries.py rootfs/` — apply the four byte patches:
   - rseq -> -ENOSYS (seccomp trap)
   - set_robust_list -> fake success (seccomp trap)
   - node io_uring probe -> -1 (seccomp trap; exact -1 required by libuv)
   - letta.js link() -> writeFileSync wx (SELinux hardlink denial)
4. `tar --hard-dereference -czf ../app/src/main/assets/rootfs.tar -C rootfs .`
5. `../inject-libs.sh` after gradle assembleDebug (post-build APK injection
   of lib*.so-named binaries into lib/arm64-v8a)

## Diagnostic sources

- `stracelite.c` — static musl ptrace harness: syscall trace, fatal regs,
  stack unwind, maps dump. The tool that cracked every sandbox wall.
  Build: `zig cc -target aarch64-linux-musl -static -O2 -o stracelite stracelite.c`
- `sigsys_catch.c` — LD_PRELOAD SIGSYS catcher (needs libdir placement)
- `auxv.c`, `hello.c` — static probes

## Gotchas (hard-won)

- PackageManager only extracts lib*.so names from lib/arm64-v8a
- qemu-user 7.2 false-segfaults large binaries in loader-as-main mode
- Minimal patches only: blanket-patching untrapped syscalls caused a
  close_range fallback that closed stdio and crashed node
- Java ProcessBuilder exitValue = 128+signal (159=SIGSYS, 134=SIGABRT)
