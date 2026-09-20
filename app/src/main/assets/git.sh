#!/system/bin/sh
# git — run git inside the Debian rootfs via proot.
#
# Why: letta-code 0.32.x MemFS sync spawns `git` for every memory checkout.
# Without a spawnable git on PATH every Bash/Skill/memory tool call dies with
# "spawn git ENOENT". A direct loader-chain wrapper does NOT work for git:
# git-remote-https is glibc and needs /etc/resolv.conf, /usr/lib/git-core
# helpers, and the CA bundle at their compiled-in paths — proot maps all of
# them from the rootfs.
#
# Payload install (first run): the app stages git-arm64.tar.gz (git 2.39.5
# from Debian bookworm + git-core + CA certs) and proot-aarch64.tar.gz to the
# filesDir root — this wrapper extracts them into the rootfs/plugins. Fully
# offline: no download unless the staged tarballs are missing (then falls
# back to the plugins-v1 release URLs, same as dx).
#
# Bind layout: cwd is mapped to /host (git sees the caller's working
# directory, which for the harness is under files/rootfs/root/... — the same
# tree proot -R serves, so relative paths resolve identically). GIT_* env
# (author identity, protocol v2) flows through — proot forwards the
# environment. GIT_EXEC_PATH stays unset so git uses its compiled-in
# /usr/lib/git-core inside the guest.
#
# Source of truth: app/src/main/assets/git.sh — the service copies this to
# files/bin/git on every start. tools/on-device/git.sh mirrors it for
# on-device debugging. Keep the three in sync.

set -u

DX_FILES="${DX_FILES:-/data/user/0/com.angussoftware.letta.env/files}"
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
            "${DX_PLUGIN_URL:-https://github.com/coda-rho-bot/letta-environment-android/releases/download/plugins-v1/proot-aarch64.tar.gz}" \
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
            "${DX_GIT_URL:-https://github.com/coda-rho-bot/letta-environment-android/releases/download/plugins-v1/git-arm64.tar.gz}" \
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
