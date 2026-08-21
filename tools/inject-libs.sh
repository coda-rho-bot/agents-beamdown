#!/bin/bash
# Post-build APK injection: adds exec-mapped files to lib/arm64-v8a/ that AGP's
# jniLibs filter rejects (non-lib*.so names: libc.so.6, *.node, versioned .so).
# Everything the dynamic loader maps must live in the native lib dir because
# untrusted_app denies mmap(PROT_EXEC) of app_data_file.
# Then re-aligns and re-signs the APK.
set -e
R=${ROOTFS:-/tmp/zfold-build/rootfs}
APK=${1:-/home/rhomancer/dev/ai/letta-environment-android/app/build/outputs/apk/debug/app-debug.apk}
BT=${BT:-$HOME/Android/Sdk/build-tools/37.0.0}
STAGE=$(mktemp -d)
trap "rm -rf $STAGE" EXIT
mkdir -p "$STAGE/lib/arm64-v8a"
D="$STAGE/lib/arm64-v8a"

LC="$R/usr/local/lib/node_modules/@letta-ai/letta-code/node_modules"
cp "$R/lib/aarch64-linux-gnu/ld-linux-aarch64.so.1" "$D/"          # patched (rseq -> -ENOSYS)
cp "$R/lib/aarch64-linux-gnu/libc.so.6" "$D/"                       # patched
cp "$R/lib/aarch64-linux-gnu/libm.so.6" "$D/"
cp "$R/lib/aarch64-linux-gnu/libdl.so.2" "$D/"
cp "$R/usr/lib/aarch64-linux-gnu/libpthread.so.0" "$D/"
cp "$R/usr/lib/aarch64-linux-gnu/libgcc_s.so.1" "$D/"
cp "$R/usr/lib/aarch64-linux-gnu/libstdc++.so.6" "$D/"
cp "$R/usr/lib/aarch64-linux-gnu/libtinfo.so.6" "$D/"
cp "$R/usr/lib/aarch64-linux-gnu/libresolv.so.2" "$D/"
cp "$R/usr/local/bin/node" "$D/libnode.so"
cp "$R/usr/bin/bash" "$D/libguestbash.so"
cp "$R/usr/bin/true" "$D/libtrue.so"
cp "$LC/node-pty/build/Release/pty.node" "$D/"
cp "$LC/@img/sharp-linux-arm64/lib/sharp-linux-arm64.node" "$D/"
cp "$LC/@img/sharp-libvips-linux-arm64/lib/libvips-cpp.so.8.17.3" "$D/"
cp "$LC/@vscode/vscode-ripgrep-linux-arm64/bin/rg" "$D/librg.so"

(python3 "$(dirname "$0")/inject.py" "$APK" "$STAGE")
"$BT/zipalign" -f 4 "$APK" "$APK.aligned" && mv "$APK.aligned" "$APK"
"$BT/apksigner" sign --ks "$HOME/.android/debug.keystore" --ks-pass pass:android --key-pass pass:android "$APK"
echo "injected + signed: $APK"
unzip -l "$APK" | grep -E "lib/arm64-v8a/" | head -20
