#!/bin/bash
# v5: NO PATCHELF. Only naturally lib*.so-named files go in libdir (plain copies,
# byte-patches only — patchelf's dynamic-section rewriting breaks loader hash
# lookups: pthread_mutex_lock@GLIBC_2.17 lookup hits a NULL hash table -> SIGSEGV).
# Original-name libs (libc.so.6 etc) are copied at RUNTIME into files/libs/ by the
# service; the loader maps app_data files fine (mapping was never the blocker).
set -e
R=${ROOTFS:-/tmp/zfold-build/rootfs}
APK=${1:-$(dirname "$0")/../app/build/outputs/apk/debug/app-debug.apk}
BT=${BT:-$HOME/Android/Sdk/build-tools/37.0.0}
STAGE=$(mktemp -d)
trap "rm -rf $STAGE" EXIT
mkdir -p "$STAGE/lib/arm64-v8a"
D="$STAGE/lib/arm64-v8a"
LC="$R/usr/local/lib/node_modules/@letta-ai/letta-code/node_modules"

# conforming names only — plain cp, ZERO modification beyond the byte-patches
# already present in the rootfs source (rseq, set_robust_list)
cp "$R/lib/aarch64-linux-gnu/ld-linux-aarch64.so.1" "$D/libldlnx.so"
cp "$R/usr/local/bin/node"                           "$D/libnode.so"
cp "$R/usr/bin/bash"                                 "$D/libguestbash.so"
cp "$R/usr/bin/true"                                 "$D/libtrue.so"
cp "$(dirname "$0")/libsigsys.so"                    "$D/libsigsys.so"

# GUARD: the rootfs tar (Aug 21) predates patch 5/6 (Aug 28, uv__close).
# patch-binaries.py patches the BUILD rootfs, but a build run against a
# rootfs that never received the patch ships an unpatched libnode.so —
# which crash-loops the app at launch (exit 134, uv__close assertion;
# shipped in v0.2.7 release dd594001 and caught live on ZFold 7, Sep 20).
# Verify the patch is present in what we're about to ship; apply it if the
# source has it missing but the offset is sane. FATAL if bytes are alien.
python3 - "$D/libnode.so" <<'PYEOF'
import sys
path = sys.argv[1]
off = 0x1C2EC10 - 0x400000
old = bytes.fromhex("4d020054")
new = bytes.fromhex("ed010054")
data = bytearray(open(path, "rb").read())
cur = bytes(data[off:off+4])
if cur == new:
    print("  libnode: uv__close patch present (already patched)")
elif cur == old:
    data[off:off+4] = new
    open(path, "wb").write(data)
    print("  libnode: uv__close patch APPLIED (source rootfs was unpatched!)")
else:
    sys.exit(f"FATAL: unexpected bytes at libnode+{off:#x}: {cur.hex()} — node build changed, re-derive offset (patch-binaries.py patch 6)")
PYEOF

# git payloads: AGP's asset-merge gunzips *.gz assets and strips the suffix
# (git-arm64.tar.gz ships as assets/git-arm64.tar, decompressed). Re-inject
# the byte-exact .gz files here — the Kotlin staging code and the git wrapper
# both address them by their exact .gz names.
mkdir -p "$STAGE/assets"
cp "$(dirname "$0")/../app/src/main/assets/git-arm64.tar.gz"       "$STAGE/assets/"
cp "$(dirname "$0")/../app/src/main/assets/proot-aarch64.tar.gz"  "$STAGE/assets/"

python3 "$(dirname "$0")/inject.py" "$APK" "$STAGE"
"$BT/zipalign" -f 4 "$APK" "$APK.aligned" && mv "$APK.aligned" "$APK"
"$BT/apksigner" sign --ks "$HOME/.android/debug.keystore" --ks-pass pass:android --key-pass pass:android "$APK"
echo "=== v5 injected + signed: $APK ==="
unzip -l "$APK" | grep -E "lib/arm64-v8a/" | awk '{print $1, $4}'
