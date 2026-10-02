# On-device tooling (proot plugin)

Adds on-demand tool installation to the Agents Beamdown without growing the
APK. The agent downloads a ~125KB proot plugin at runtime and gains a
chroot-like Debian userspace — from which `apt-get install git python3 …`
works directly into the persistent rootfs.

## Why proot

Running rootfs binaries via `ld-linux --library-path` works for a single
process, but glibc children die: their `PT_INTERP=/lib/ld-linux-aarch64.so.1`
does not exist on Android's real root ("cannot execute: required file not
found"). proot (Termux bionic build — runs natively, no loader trick) uses
ptrace to translate guest paths, so the whole exec chain resolves inside the
rootfs. Termux's proot-distro is the production precedent.

## Seccomp story

The byte-patches that make glibc survive the app sandbox (rseq → -ENOSYS,
set_robust_list → fake success) live in the rootfs's `libc.so.6` and
`ld-linux` — not in each binary. Any dynamically-linked Debian package
installed via apt inherits them automatically. Two rules:

1. **Never `apt upgrade` / never replace libc6** — the replacement would be
   unpatched and every glibc process would SIGSYS. `dx`/probe hold libc6.
2. Static (musl) binaries are NOT covered — avoid `-static` packages.

## DNS story

Android has no `/etc/resolv.conf`; the Letta server solves this with
`dns-shim.js` (node-only). Inside proot, guest glibc reads the *rootfs's*
`/etc/resolv.conf` (1.1.1.1 / 8.8.8.8) via path translation — so apt, git,
curl all resolve normally.

## Files

- `dx.sh` — the agent-facing wrapper. Installs the plugin on first use
  (bionic curl has DNS), writes guest resolv.conf, execs
  `proot -R <rootfs> env …`. Drop at `files/bin/dx` (already on PATH).
- `probe.sh` — 16-test capability battery. Run on-device before trusting
  the stack: loader-only exec, proot chroot, DNS, apt update, package
  install, git clone over https, libc6 hold.

## Plugin asset

`proot-aarch64.tar.gz` on the [plugins-v1 release] — PRoot 5.1.107.91
(Termux, NDK r29, bionic) + libtalloc 2.4.3 + libandroid-shmem 0.7.

## Unverified (needs on-device probe)

- ptrace under untrusted_app seccomp on One UI 7 (Termux precedent says yes)
- apt/DNS/git-clone end-to-end inside the guest
- apt maintainer scripts inside proot (run via guest /bin/sh — should work)

[plugins-v1 release]: https://github.com/coda-rho-bot/letta-environment-android/releases/tag/plugins-v1
