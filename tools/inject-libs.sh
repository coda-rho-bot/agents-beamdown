#!/bin/bash
# Post-build APK injection: exec-mapped files live in lib/arm64-v8a/ because
# untrusted_app denies mmap(PROT_EXEC) of app_data_file. PackageManager only
# extracts lib*.so-named entries, so every lib is renamed and ALL DT_NEEDED /
# DT_SONAME references are rewritten with patchelf. The glibc libs are also
# rseq-patched (svc -> movn w0,#37 / -ENOSYS) to survive the app seccomp filter.
set -e
R=${ROOTFS:-/tmp/zfold-build/rootfs}
APK=${1:-/home/rhomancer/dev/ai/letta-environment-android/app/build/outputs/apk/debug/app-debug.apk}
BT=${BT:-$HOME/Android/Sdk/build-tools/37.0.0}
PATCHELF=${PATCHELF:-$HOME/.local/bin/patchelf}
STAGE=$(mktemp -d)
trap "rm -rf $STAGE" EXIT
mkdir -p "$STAGE/lib/arm64-v8a"
D="$STAGE/lib/arm64-v8a"
LC="$R/usr/local/lib/node_modules/@letta-ai/letta-code/node_modules"

declare -A RENAMES=(
  [ld-linux-aarch64.so.1]=libldlnx.so
  [libc.so.6]=liblnxc6.so
  [libm.so.6]=liblnxm.so
  [libdl.so.2]=liblnxdl.so
  [libpthread.so.0]=liblnxpt.so
  [libgcc_s.so.1]=liblnxgcc.so
  [libstdc++.so.6]=liblnxcxx.so
  [libtinfo.so.6]=liblnxti.so
  [libresolv.so.2]=liblnxres.so
)

# copy libs with new names
cp "$R/lib/aarch64-linux-gnu/ld-linux-aarch64.so.1" "$D/libldlnx.so"
cp "$R/lib/aarch64-linux-gnu/libc.so.6"              "$D/liblnxc6.so"
cp "$R/lib/aarch64-linux-gnu/libm.so.6"              "$D/liblnxm.so"
cp "$R/lib/aarch64-linux-gnu/libdl.so.2"             "$D/liblnxdl.so"
cp "$R/usr/lib/aarch64-linux-gnu/libpthread.so.0"    "$D/liblnxpt.so"
cp "$R/usr/lib/aarch64-linux-gnu/libgcc_s.so.1"      "$D/liblnxgcc.so"
cp "$R/usr/lib/aarch64-linux-gnu/libstdc++.so.6"     "$D/liblnxcxx.so"
cp "$R/usr/lib/aarch64-linux-gnu/libtinfo.so.6"      "$D/liblnxti.so"
cp "$R/usr/lib/aarch64-linux-gnu/libresolv.so.2"     "$D/liblnxres.so"
cp "$R/usr/local/bin/node"                           "$D/libnode.so"
cp "$R/usr/bin/bash"                                 "$D/libguestbash.so"
cp "$R/usr/bin/true"                                 "$D/libtrue.so"
cp "$LC/node-pty/build/Release/pty.node"             "$D/libptynd.so"
cp "$LC/@img/sharp-linux-arm64/lib/sharp-linux-arm64.node" "$D/libsharpnd.so"
cp "$LC/@img/sharp-libvips-linux-arm64/lib/libvips-cpp.so.8.17.3" "$D/libvipscpp.so"

# sonames for renamed libs
for old in "${!RENAMES[@]}"; do
  new="${RENAMES[$old]}"
  $PATCHELF --set-soname "$new" "$D/$new"
done

# replace NEEDED refs in every dynamic object in the staging dir
for f in "$D"/*; do
  for old in "${!RENAMES[@]}"; do
    new="${RENAMES[$old]}"
    $PATCHELF --replace-needed "$old" "$new" "$f" 2>/dev/null || true
  done
done

python3 "$(dirname "$0")/inject.py" "$APK" "$STAGE"
"$BT/zipalign" -f 4 "$APK" "$APK.aligned" && mv "$APK.aligned" "$APK"
"$BT/apksigner" sign --ks "$HOME/.android/debug.keystore" --ks-pass pass:android --key-pass pass:android "$APK"
echo "=== injected + signed: $APK ==="
unzip -l "$APK" | grep -E "lib/arm64-v8a/" | awk '{print $1, $4}' | sort -k2
