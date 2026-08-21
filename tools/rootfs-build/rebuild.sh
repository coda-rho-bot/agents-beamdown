#!/bin/bash
set -e
B=/tmp/zfold-build
LC=$B/rootfs/usr/local/lib/node_modules/@letta-ai/letta-code
cd $B
echo "== 1. install letta-code 0.30.27 (pinned, engines forced) =="
npm install -g --prefix $B/rootfs/usr/local --ignore-scripts --force @letta-ai/letta-code@0.30.27 2>&1 | tail -1
echo "== 2. swap platform packages to arm64 =="
rm -rf $LC/node_modules/@img/sharp-linux-x64 $LC/node_modules/@img/sharp-libvips-linux-x64 $LC/node_modules/@vscode/ripgrep-linux-x64
cd $B/swap
tar -xzf ../img-sharp-linux-arm64-0.34.5.tgz 2>/dev/null; [ -d package ] && mv package sharp-linux-arm64
tar -xzf ../img-sharp-libvips-linux-arm64-1.2.4.tgz 2>/dev/null; [ -d package ] && mv package sharp-libvips-linux-arm64
tar -xzf ../vscode-ripgrep-linux-arm64-1.18.0.tgz 2>/dev/null; [ -d package ] && mv package vscode-ripgrep-linux-arm64
mv -f sharp-linux-arm64 sharp-libvips-linux-arm64 $LC/node_modules/@img/
mv -f vscode-ripgrep-linux-arm64 $LC/node_modules/@vscode/
chmod +x $LC/node_modules/@vscode/vscode-ripgrep-linux-arm64/bin/rg
echo "== 3. zig cross-compile pty.node =="
cd $LC/node_modules/node-pty
export PATH="/tmp/zfold-build/zig-x86_64-linux-0.16.0:$PATH"
export CC="zig cc -target aarch64-linux-gnu.2.36" CXX="zig c++ -target aarch64-linux-gnu.2.36"
npx node-gyp rebuild --arch=arm64 --nodedir=/tmp/zfold-build/rootfs/usr/local 2>&1 | tail -1
echo "== 4. smoke test under qemu =="
timeout 110 $B/qemu-aarch64-static -L $B/rootfs $B/rootfs/usr/local/bin/node $LC/letta.js --version
