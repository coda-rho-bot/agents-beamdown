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

# --- primary: static ELF chain -------------------------------------------------
if [ -x "$DX_FILES/bin/git-elf" ] && [ -x "$DX_FILES/gc-shim/git-remote-https" ]; then
    exec "$DX_FILES/bin/git-elf" "$@"
fi

# --- fallback: proot rootfs ----------------------------------------------------
ROOTFS="$DX_FILES/rootfs"
PLUGINS="$DX_FILES/plugins"
PROOT="$PLUGINS/proot-aarch64/bin/proot"
GUEST_GIT="$ROOTFS/usr/bin/git"

die() { echo "git: $*" >&2; exit 1; }

# --- proot plugin: staged tarball first, download fallback -----------------------
if [ ! -x "$PROOT" ]; then
    mkdir -p "$PLUGINS" "$DX_FILES/tmp" 2>/dev/null
    if [ -f "$DX_FILES/proot-aarch64.tar.gz" ]; then
        tar -xzf "$DX_FILES/proot-aarch64.tar.gz" -C "$PLUGINS" || die "proot extract failed"
    else
        TARBALL="$DX_FILES/tmp/proot-aarch64.tar.gz"
        curl -sSL --max-time 120 -o "$TARBALL" \
            "${DX_PLUGIN_URL:-https://github.com/coda-rho-bot/agents-beamdown/releases/download/plugins-v1/proot-aarch64.tar.gz}" \
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
        curl -sSL --max-time 300 -o "$TARBALL" \
            "${DX_GIT_URL:-https://github.com/coda-rho-bot/agents-beamdown/releases/download/plugins-v1/git-arm64.tar.gz}" \
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
