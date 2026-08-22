#!/system/bin/sh
# dx — run a command inside the Debian rootfs via proot (chroot illusion, no root).
#
# Installs the proot plugin on first use (downloads from the plugins-v1 release
# using bionic curl, which has working DNS via Android netd).
#
# Usage:
#   dx <command...>          run command in the rootfs
#   dx -c 'shell command'    convenience: runs /bin/bash -c '<command>'
#
# Env overrides:
#   DX_PLUGIN_URL   where to fetch proot-aarch64.tar.gz
#   DX_FILES        app files dir (default: auto-detect)
#
# First run of apt:  dx apt-get update && dx apt-get install -y git
# NEVER run "apt upgrade" — libc6 must stay at the patched version in the rootfs
# (the seccomp byte-patches for rseq/set_robust_list live in libc/ld-linux;
# replacing them kills every glibc process. dx holds libc6 automatically).

set -u

DX_FILES="${DX_FILES:-/data/user/0/com.angussoftware.letta.env/files}"
DX_PLUGIN_URL="${DX_PLUGIN_URL:-https://github.com/coda-rho-bot/letta-environment-android/releases/download/plugins-v1/proot-aarch64.tar.gz}"
ROOTFS="$DX_FILES/rootfs"
PLUGINS="$DX_FILES/plugins"
PROOT="$PLUGINS/proot-aarch64/bin/proot"

die() { echo "dx: $*" >&2; exit 1; }

# --- install plugin on first use -------------------------------------------------
if [ ! -x "$PROOT" ]; then
    echo "dx: installing proot plugin (one-time, ~125KB)..." >&2
    mkdir -p "$PLUGINS" "$DX_FILES/tmp" || die "cannot create $PLUGINS"
    TARBALL="$DX_FILES/tmp/proot-aarch64.tar.gz"
    # bionic curl has working DNS (Android netd) — glibc tools do not (yet)
    curl -sSL --max-time 120 -o "$TARBALL" "$DX_PLUGIN_URL" || die "download failed"
    tar -xzf "$TARBALL" -C "$PLUGINS" || die "extract failed"
    chmod +x "$PROOT" "$PLUGINS/proot-aarch64/libexec/proot/loader" \
              "$PLUGINS/proot-aarch64/libexec/proot/loader32" 2>/dev/null
    rm -f "$TARBALL"
    [ -x "$PROOT" ] || die "proot missing after install"
    echo "dx: proot installed at $PROOT" >&2
fi

[ -d "$ROOTFS" ] || die "rootfs not found at $ROOTFS"

# --- one-time guest setup ---------------------------------------------------------
# DNS: guest glibc reads /etc/resolv.conf -> rootfs file (proot maps it).
if [ ! -f "$ROOTFS/etc/resolv.conf" ] || ! grep -q nameserver "$ROOTFS/etc/resolv.conf" 2>/dev/null; then
    printf 'nameserver 1.1.1.1\nnameserver 8.8.8.8\n' > "$ROOTFS/etc/resolv.conf" || true
fi
# PATH for login-less guests
if [ ! -d "$ROOTFS/usr/local/bin" ]; then mkdir -p "$ROOTFS/usr/local/bin" 2>/dev/null; fi

export LD_LIBRARY_PATH="$PLUGINS/proot-aarch64/lib${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}"
export PROOT_TMP_DIR="$DX_FILES/tmp"
export PROOT_LOADER="$PLUGINS/proot-aarch64/libexec/proot/loader"
export PROOT_LOADER2="$PLUGINS/proot-aarch64/libexec/proot/loader"

# --- convenience: -c 'cmd' ---------------------------------------------------------
if [ "${1:-}" = "-c" ]; then
    shift
    exec "$PROOT" -R "$ROOTFS" /usr/bin/env -i \
        TERM=dumb \
        PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin \
        HOME=/root \
        /bin/bash -c "$*"
fi

exec "$PROOT" -R "$ROOTFS" /usr/bin/env -i \
    TERM=dumb \
    PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin \
    HOME=/root \
    "$@"
