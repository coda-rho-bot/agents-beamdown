#!/system/bin/sh
# dx — run a command inside the Debian rootfs via proot (chroot illusion, no root).
# v3: -0 fake-root, TMPDIR=/tmp2 (stale-tmpfs shadow), /sdcard bind, libc6 hold.
set -u
DX_FILES="${DX_FILES:-/data/user/0/com.angussoftware.letta.env/files}"
DX_PLUGIN_URL="${DX_PLUGIN_URL:-https://github.com/coda-rho-bot/letta-environment-android/releases/download/plugins-v1/proot-aarch64.tar.gz}"
ROOTFS="$DX_FILES/rootfs"
PLUGINS="$DX_FILES/plugins"
PROOT="$PLUGINS/proot-aarch64/bin/proot"
die() { echo "dx: $*" >&2; exit 1; }
if [ ! -x "$PROOT" ]; then
    mkdir -p "$PLUGINS" "$DX_FILES/tmp" || die "cannot create $PLUGINS"
    TARBALL="$DX_FILES/tmp/proot-aarch64.tar.gz"
    /system/bin/curl -sSL --max-time 120 -o "$TARBALL" "$DX_PLUGIN_URL" || die "download failed"
    tar -xzf "$TARBALL" -C "$PLUGINS" || die "extract failed"
    chmod +x "$PROOT" "$PLUGINS/proot-aarch64/libexec/proot/loader" 2>/dev/null
    rm -f "$TARBALL"
fi
[ -d "$ROOTFS" ] || die "rootfs not found at $ROOTFS"
if [ ! -f "$ROOTFS/etc/resolv.conf" ] || ! grep -q nameserver "$ROOTFS/etc/resolv.conf" 2>/dev/null; then
    printf 'nameserver 1.1.1.1\nnameserver 8.8.8.8\n' > "$ROOTFS/etc/resolv.conf" || true
fi
[ -d "$ROOTFS/usr/local/bin" ] || mkdir -p "$ROOTFS/usr/local/bin" 2>/dev/null
[ -d "$ROOTFS/tmp2" ] || { mkdir -p "$ROOTFS/tmp2"; chmod 1777 "$ROOTFS/tmp2" 2>/dev/null; }
L2C_DIR="$PLUGINS/link2copy"; L2C_SO="$L2C_DIR/liblink2copy.so"
if [ ! -f "$L2C_SO" ]; then
    mkdir -p "$L2C_DIR" 2>/dev/null
    /system/bin/curl -sSL --max-time 60 -o "$L2C_SO.tmp" "https://github.com/coda-rho-bot/letta-environment-android/releases/download/plugins-v1/liblink2copy.so" \
        && mv "$L2C_SO.tmp" "$L2C_SO" || { rm -f "$L2C_SO.tmp"; echo "dx: link2copy shim unavailable — dpkg may fail" >&2; }
fi
L2C_PRELOAD=""
[ -f "$L2C_SO" ] && L2C_PRELOAD="/usr/local/lib/liblink2copy.so"
export LD_LIBRARY_PATH="$PLUGINS/proot-aarch64/lib${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}"
export PROOT_TMP_DIR="$DX_FILES/tmp"
export PROOT_LOADER="$PLUGINS/proot-aarch64/libexec/proot/loader"
export PROOT_LOADER2="$PLUGINS/proot-aarch64/libexec/proot/loader"
if [ -x "$ROOTFS/usr/bin/apt-mark" ] && [ -f "$ROOTFS/var/lib/dpkg/status" ]; then
    "$PROOT" -0 -R "$ROOTFS" -b /system /usr/bin/env -i \
        PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:/system/bin \
        /usr/bin/apt-mark hold libc6 >/dev/null 2>&1 || true
fi
if [ "${1:-}" = "-c" ]; then
    shift
    exec "$PROOT" -0 -R "$ROOTFS" -b /system -b /sdcard /usr/bin/env -i \
        TERM=dumb PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:/system/bin \
        HOME=/root TMPDIR=/tmp2 ${L2C_PRELOAD:+LD_PRELOAD=$L2C_PRELOAD} \
        /bin/bash -c "$*"
fi
exec "$PROOT" -0 -R "$ROOTFS" -b /system -b /sdcard /usr/bin/env -i \
    TERM=dumb PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:/system/bin \
    HOME=/root TMPDIR=/tmp2 ${L2C_PRELOAD:+LD_PRELOAD=$L2C_PRELOAD} \
    "$@"
