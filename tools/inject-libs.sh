#!/bin/bash
# v5: NO PATCHELF. Only naturally lib*.so-named files go in libdir (plain copies,
# byte-patches only — patchelf's dynamic-section rewriting breaks loader hash
# lookups: pthread_mutex_lock@GLIBC_2.17 lookup hits a NULL hash table -> SIGSEGV).
# Original-name libs (libc.so.6 etc) are copied at RUNTIME into files/libs/ by the
# service; the loader maps app_data files fine (mapping was never the blocker).
set -e
R=${ROOTFS:-/tmp/zfold-build/rootfs}
APK=${1:-/home/rhomancer/dev/ai/letta-environment-android/app/build/outputs/apk/debug/app-debug.apk}
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

python3 "$(dirname "$0")/inject.py" "$APK" "$STAGE"
"$BT/zipalign" -f 4 "$APK" "$APK.aligned" && mv "$APK.aligned" "$APK"
"$BT/apksigner" sign --ks "$HOME/.android/debug.keystore" --ks-pass pass:android --key-pass pass:android "$APK"
echo "=== v5 injected + signed: $APK ==="
unzip -l "$APK" | grep -E "lib/arm64-v8a/" | awk '{print $1, $4}'
