#!/system/bin/sh
# git — spawnable git for letta-code MemFS sync (0.32.x+).
#
# PRIMARY PATH (Sep 28 2026): static ELF chain, no proot. The service installs
#   files/bin/git-elf      — static bionic ELF (NDK API 35) exec'ing the rootfs
#                            glibc loader with real git, --preload dnsport.so
#   files/gc-shim/*        — static ELF shims (git, git-remote-{http,https,ftp,
#                            ftps}) for git's dashed-dispatch child spawns
#   files/libs/dnsport.so  — LD_PRELOAD getaddrinfo() override: raw A/AAAA via
#                            direct syscalls to the local node DNS forwarder on
#                            127.0.0.1:15353 (glibc-internal sendmmsg is NOT
#                            interposable — no PLT; Android has no resolv.conf)
#                            The forwarder (files/dns-forwarder.js) is started
#                            by launch-server.sh via the APK node path.
# proot CANNOT exec from loader-chain processes (its interpreter ENOENTs the
# same way shebang scripts do), so the proot path below is only a fallback for
# contexts that can run it. Sources: tools/watch-git-shims/ (gitmain.c,
# gcshim.c, dnsport.c, dns-forwarder.js + README).
#
# FALLBACK PATH: run git inside the Debian rootfs via proot (payload install on
# first run, cwd mapped to /host, GIT_* env forwarded).
#
# Source of truth: app/src/main/assets/git.sh — the service copies this to
# files/bin/git on every start. tools/on-device/git.sh mirrors it. Keep in sync.

set -u

DX_FILES="${DX_FILES:-/data/user/0/com.angussoftware.letta.env/files}"

die() { echo "git: $*" >&2; exit 1; }

# --- primary: static ELF chain -------------------------------------------------
# Payload self-heal (Oct 7): git-elf execs $ROOTFS/usr/bin/git as a GLIBC
# PROGRAM (loader invocation) — the path must hold the real Debian ELF. A
# wiped/corrupt/script-wrapped payload made the loader fail BEFORE this
# wrapper's own (fallback-only) payload install, so check HERE, in the
# primary path, and extract the staged tarball if the ELF is not present.
# ELF magic check: first 4 bytes 0x7F 'E' 'L' 'F' (a #! wrapper fails it).
# Degrade to the plain -x gate if od is unavailable (is_elf false-negative
# on a good payload must not break the previously-working path).
if command -v od >/dev/null 2>&1; then
    is_elf() { [ -f "$1" ] && [ "$(head -c4 "$1" | od -An -tx1 | tr -d ' \n')" = "7f454c46" ]; }
else
    is_elf() { [ -x "$1" ]; }
fi
if [ ! -x "$DX_FILES/bin/git-elf" ] || [ ! -x "$DX_FILES/gc-shim/git-remote-https" ]; then
    : # primary chain incomplete — fall through to proot fallback below
elif is_elf "$DX_FILES/rootfs/usr/bin/git"; then
    exec "$DX_FILES/bin/git-elf" "$@"
else
    # payload missing or non-ELF — restore from the staged tarball (offline)
    # before exec'ing the chain. Never write a script at that path: the glibc
    # loader cannot exec scripts (live-verified Oct 7, PR #112 closed on it).
    # Corrupt staged tarball: remove it so the NEXT run falls through to the
    # download path instead of dying forever (same pattern as the fallback
    # section below).
    if [ -f "$DX_FILES/git-arm64.tar.gz" ]; then
        if ! tar -xzf "$DX_FILES/git-arm64.tar.gz" -C "$DX_FILES/rootfs"; then
            rm -f "$DX_FILES/git-arm64.tar.gz"
            die "git payload corrupt (primary path) — staged copy removed, retry will download"
        fi
        is_elf "$DX_FILES/rootfs/usr/bin/git" \
            || die "git payload still non-ELF after extract"
        exec "$DX_FILES/bin/git-elf" "$@"
    fi
    # no staged tarball — fall through to the download-based paths below
fi

# --- fallback: proot rootfs ----------------------------------------------------
ROOTFS="$DX_FILES/rootfs"
PLUGINS="$DX_FILES/plugins"
PROOT="$PLUGINS/proot-aarch64/bin/proot"
GUEST_GIT="$ROOTFS/usr/bin/git"

# --- proot plugin: staged tarball first, download fallback -----------------------
if [ ! -x "$PROOT" ]; then
    mkdir -p "$PLUGINS" "$DX_FILES/tmp" 2>/dev/null
    if [ -f "$DX_FILES/proot-aarch64.tar.gz" ]; then
        tar -xzf "$DX_FILES/proot-aarch64.tar.gz" -C "$PLUGINS" || die "proot extract failed"
    else
        TARBALL="$DX_FILES/tmp/proot-aarch64.tar.gz"
        # pinned (set 3): env override removed — a hostile turn could redirect payloads
        curl -sSL --max-time 120 -o "$TARBALL" \
            "https://github.com/coda-rho-bot/agents-beamdown/releases/download/plugins-v1/proot-aarch64.tar.gz" \
            || die "proot download failed"
        tar -xzf "$TARBALL" -C "$PLUGINS" || die "proot extract failed"
        rm -f "$TARBALL"
    fi
    chmod +x "$PROOT" "$PLUGINS/proot-aarch64/libexec/proot/loader" \
              "$PLUGINS/proot-aarch64/libexec/proot/loader32" 2>/dev/null
    [ -x "$PROOT" ] || die "proot missing after install"
fi

# --- git payload: staged tarball first, download fallback ------------------------
# Self-heal: a corrupt staged tarball (interrupted copy, bit rot) is removed
# on extract failure so the next invocation falls through to the download
# path instead of failing offline forever.
if [ ! -x "$GUEST_GIT" ]; then
    if [ -f "$DX_FILES/git-arm64.tar.gz" ]; then
        if ! tar -xzf "$DX_FILES/git-arm64.tar.gz" -C "$ROOTFS"; then
            rm -f "$DX_FILES/git-arm64.tar.gz"
            die "git payload corrupt — removed staged copy, retry will download"
        fi
    else
        TARBALL="$DX_FILES/tmp/git-arm64.tar.gz"
        # pinned (set 3): env override removed — a hostile turn could redirect payloads
        curl -sSL --max-time 300 -o "$TARBALL" \
            "https://github.com/coda-rho-bot/agents-beamdown/releases/download/plugins-v1/git-arm64.tar.gz" \
            || die "git payload download failed"
        tar -xzf "$TARBALL" -C "$ROOTFS" || die "git payload extract failed"
        rm -f "$TARBALL"
    fi
    [ -x "$GUEST_GIT" ] || die "git missing after payload install"
fi

# --- guest DNS: glibc reads /etc/resolv.conf (proot maps it into the guest) -----
if [ ! -f "$ROOTFS/etc/resolv.conf" ] || ! grep -q nameserver "$ROOTFS/etc/resolv.conf" 2>/dev/null; then
    printf 'nameserver 1.1.1.1\nnameserver 8.8.8.8\n' > "$ROOTFS/etc/resolv.conf" || true
fi

export LD_LIBRARY_PATH="$PLUGINS/proot-aarch64/lib${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}"
export PROOT_TMP_DIR="$DX_FILES/tmp"
export PROOT_LOADER="$PLUGINS/proot-aarch64/libexec/proot/loader"
export PROOT_LOADER2="$PLUGINS/proot-aarch64/libexec/proot/loader"

# -R binds host /tmp over the guest's — git writes lockfiles/pack temp files
# to TMPDIR, and the Android host /tmp does not exist for the app uid.
# Bind our own tmp dir over the guest /tmp instead.
exec "$PROOT" -R "$ROOTFS" -b /system -b "$DX_FILES/tmp:/tmp" -b "$(pwd):/host" -w /host \
    /usr/bin/git "$@"
