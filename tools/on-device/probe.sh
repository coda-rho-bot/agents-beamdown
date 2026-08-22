#!/system/bin/sh
# probe.sh — capability battery for the proot plugin prototype.
# Run from the Bash tool on-device: sh /path/to/probe.sh
# Each test prints PASS/FAIL; output is the evidence base for next steps.

F=/data/user/0/com.angussoftware.letta.env/files
P="$F/plugins/proot-aarch64/bin/proot"
R="$F/rootfs"
LD="$R/lib/aarch64-linux-gnu/ld-linux-aarch64.so.1"
LIBS="$R/lib/aarch64-linux-gnu:$R/usr/lib/aarch64-linux-gnu"
pass=0; fail=0

t() { # t <name> <expected-substring-or-empty> <cmd...>
    # empty <want> = expect exit 0 with no assertion on output
    name="$1"; want="$2"; shift 2
    out="$("$@" 2>&1)"; rc=$?
    if [ -z "$want" ]; then
        if [ $rc -eq 0 ]; then echo "PASS: $name (rc=0)"; pass=$((pass+1));
        else echo "FAIL: $name rc=$rc — $(echo "$out" | head -3 | tr '\n' ' ')"; fail=$((fail+1)); fi
    elif echo "$out" | grep -q "$want"; then
        echo "PASS: $name"; pass=$((pass+1))
    else
        echo "FAIL: $name — got: $(echo "$out" | head -3 | tr '\n' ' ')"; fail=$((fail+1))
    fi
}

echo "== 1. loader-only execution (no proot) =="
t "rootfs-true-via-loader" "" "$LD" --library-path "$LIBS" "$R/usr/bin/true"
t "rootfs-bash-echo-via-loader" "hello-from-debian" "$LD" --library-path "$LIBS" "$R/bin/bash" -c 'echo hello-from-debian'

echo "== 2. bionic proot runs natively =="
export LD_LIBRARY_PATH="$F/plugins/proot-aarch64/lib"
export PROOT_TMP_DIR="$F/tmp"
t "proot-version" "proot" "$P" --version

echo "== 3. proot chroot illusion =="
t "proot-guest-true" "" "$P" -R "$R" /usr/bin/true
t "proot-guest-uname" "Linux" "$P" -R "$R" /bin/uname -s
t "proot-guest-bash" "inside-proot" "$P" -R "$R" /bin/bash -c 'echo inside-proot'

echo "== 4. DNS in glibc context (the dns-shim gap) =="
t "proot-getent-hosts" "has address" "$P" -R "$R" /usr/bin/getent hosts github.com

echo "== 5. apt update in guest =="
t "proot-apt-update" "Get:" "$P" -R "$R" /usr/bin/env DEBIAN_FRONTEND=noninteractive PATH=/usr/sbin:/usr/bin:/sbin:/bin /usr/bin/apt-get update

echo "== 6. install a real package (the money shot) =="
t "apt-install-slg" "Setting up" "$P" -R "$R" /usr/bin/env DEBIAN_FRONTEND=noninteractive PATH=/usr/sbin:/usr/bin:/sbin:/bin /usr/bin/apt-get install -y sl
t "sl-installed" "/usr/bin/sl" "$P" -R "$R" /usr/bin/env PATH=/usr/bin:/bin /bin/sh -c 'command -v sl'
t "git-install" "Setting up git" "$P" -R "$R" /usr/bin/env DEBIAN_FRONTEND=noninteractive PATH=/usr/sbin:/usr/bin:/sbin:/bin /usr/bin/apt-get install -y git
t "git-version" "git version" "$P" -R "$R" /usr/bin/env PATH=/usr/bin:/bin /usr/bin/git --version

echo "== 7. git clone over https (DNS + TLS + subprocess exec in guest) =="
t "git-clone-https" "ci-scripts" "$P" -R "$R" /bin/bash -c 'export PATH=/usr/bin:/bin HOME=/tmp; rm -rf /tmp/clone-test; git clone --depth 1 https://git.angussoftware.dev/coda/ci-scripts /tmp/clone-test >/dev/null 2>&1; ls /tmp/clone-test | head -5'

echo "== 8. hold libc6 (seccomp patches live there — never upgrade) =="
t "apt-mark-hold-libc6" "libc6" "$P" -R "$R" /usr/bin/apt-mark hold libc6

echo ""
echo "== summary: $pass pass / $fail fail =="
