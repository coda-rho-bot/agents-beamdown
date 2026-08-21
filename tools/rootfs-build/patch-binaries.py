#!/usr/bin/env python3
"""
patch-binaries.py — reproducible byte patches that make glibc+node survive
Android's app sandbox (untrusted_app seccomp + SELinux).

Every patch was found empirically on a ZFold 7 (Android 15, One UI 7) during
the Aug 20-21 2026 sprint. Apply to a Debian bookworm arm64 rootfs after
node+letta-code are installed (see rebuild.sh).

Patches (arm64 instructions, file-offset == vaddr for these segments):
  1. rseq (293) in ld-linux + libc: svc #0 -> movn w0,#37 (-ENOSYS)
     App seccomp TRAPs rseq at glibc startup -> SIGSYS. glibc tolerates
     ENOSYS (skips rseq registration).
  2. set_robust_list (99) in ld-linux + libc: svc #0 -> movz w0,#0 (success)
     Trapped after libc mapping -> SIGSYS. glibc accepts fake success;
     robust lists only matter for post-crash mutex cleanup.
  3. io_uring probe in node at 0x184142c: BL syscall -> movn w0,#0 (-1)
     App seccomp traps io_uring_setup (425). libuv's caller checks
     `cmn w0,#1` — EXACTLY -1 (-EPERM) — for the clean "kernel blocked
     io_uring" skip path. -38 (ENOSYS) falls into the io_uring_enter path
     -> uv__close(fd<=2) -> assert abort. The exact value is load-bearing.
  4. letta.js: link()/linkSync() lock files -> writeFileSync(flag:"wx")
     Android SELinux denies hardlinks on app_data_file. Two sites.

Do NOT add defensive patches for untrapped syscalls: a close_range -> ENOSYS
patch once made glibc's closefrom fall back to a brute-force close loop that
closed stdin/stdout/stderr and crashed node. Minimal, verified patches only.
"""
import struct, sys, os

MOVN_W0_37 = bytes.fromhex("a0048012")  # movn w0,#37  -> -38 (-ENOSYS)
MOVZ_W0_0 = bytes.fromhex("00008052")   # movz w0,#0   -> 0 (success)
MOVN_W0_0 = bytes.fromhex("00008012")   # movn w0,#0   -> -1 (-EPERM)
SVC = bytes.fromhex("010000d4")         # svc #0

def movz_x8(nr):
    return struct.pack("<I", 0xD2800000 | (nr << 5) | 8)

def patch_svc_after(data, nr, replacement, max_gap=40):
    """Replace svc #0 that follows a `movz x8,#nr` within max_gap bytes."""
    count = 0
    idx = 0
    pat = movz_x8(nr)
    while True:
        i = data.find(pat, idx)
        if i < 0:
            break
        window = data[i + 4 : i + 4 + max_gap]
        j = window.find(SVC)
        if j >= 0 and j % 4 == 0:
            off = i + 4 + j
            data[off : off + 4] = replacement
            count += 1
        idx = i + 4
    return count


def patch_file(path, patches):
    data = bytearray(open(path, "rb").read())
    total = 0
    for label, fn in patches:
        n = fn(data)
        print(f"  {os.path.basename(path)}: {label}: {n} site(s)")
        total += n
    open(path, "wb").write(data)
    return total


def main(rootfs):
    ld = os.path.join(rootfs, "lib/aarch64-linux-gnu/ld-linux-aarch64.so.1")
    libc = os.path.join(rootfs, "lib/aarch64-linux-gnu/libc.so.6")
    node = os.path.join(rootfs, "usr/local/bin/node")
    letta = os.path.join(rootfs, "usr/local/lib/node_modules/@letta-ai/letta-code/letta.js")

    print("1. rseq (293) -> -ENOSYS")
    patch_file(ld, [("rseq", lambda d: patch_svc_after(d, 293, MOVN_W0_37))])
    patch_file(libc, [("rseq", lambda d: patch_svc_after(d, 293, MOVN_W0_37))])

    print("2. set_robust_list (99) -> fake success")
    patch_file(ld, [("set_robust_list", lambda d: patch_svc_after(d, 99, MOVZ_W0_0))])
    patch_file(libc, [("set_robust_list", lambda d: patch_svc_after(d, 99, MOVZ_W0_0))])

    print("3. node io_uring probe BL@0x184142c -> -1")
    data = bytearray(open(node, "rb").read())
    off = 0x184142C
    cur = struct.unpack("<I", data[off : off + 4])[0]
    if (cur >> 26) == 0x25:  # BL opcode
        data[off : off + 4] = MOVN_W0_0
        print(f"  node: io_uring probe BL patched -> movn w0,#0 (-1)")
    elif cur == struct.unpack("<I", MOVN_W0_0)[0]:
        print("  node: already patched")
    else:
        sys.exit(f"FATAL: unexpected instruction at node+{off:#x}: {cur:#x} (not BL) — node build changed, re-derive offset")
    open(node, "wb").write(data)

    print("4. letta.js link() -> writeFileSync wx")
    s = open(letta, "rb").read().decode("utf-8", "surrogateescape")
    n = 0
    # remote-settings-lock (line ~151742)
    if "linkSync(candidatePath, targetPath);" in s:
        s = s.replace("linkSync(candidatePath, targetPath);",
                      'writeFileSync4(targetPath, ownerToken, { flag: "wx" });', 1)
        n += 1
    # manual-instance-lock (line ~277961)
    if "await link3(candidatePath, targetPath);" in s:
        s = s.replace("await link3(candidatePath, targetPath);",
                      'await writeFile15(targetPath, contents, { flag: "wx" });', 1)
        n += 1
    print(f"  letta.js: {n} link site(s) patched")
    open(letta, "wb").write(s.encode("utf-8", "surrogateescape"))

    print("done.")

if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else "/tmp/zfold-build/rootfs")
