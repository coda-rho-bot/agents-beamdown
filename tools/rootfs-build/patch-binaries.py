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
  5. letta.js Android shell spawn sites:
     5a. shell-shim shebang "#!/bin/sh" -> "#!/system/bin/sh" (subagent/CLI
         shim; guarded by full template — hf-askpass helper left untouched)
     5b. BashSession beta-tool spawn "/bin/bash" -> SHELL-aware
         (0.32.x persistent shell; absent in 0.30.x, skipped)
  6. node uv__close assert (core.c:646) -> skip fds <= 2 instead of abort
     The assert "fd > STDERR_FILENO" aborts node whenever libuv closes an
     fd that landed in a freed low slot (a stdio fd closed/reused during
     bootstrap). Resurfaced Aug 28 2026: after a Samsung update every
     fresh server launch died at bootstrap (exit 134) while `node
     --version` via pipes worked. Retarget the b.le assert-branch to the
     function epilogue: fds <= 2 are returned unclosed (libuv's own
     "never close stdio" philosophy, minus the abort). uv__close @ vaddr
     0x1c2ec00, file offset = vaddr - 0x400000 (text delta).

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
    # 0.33.x publishInitializedFile: identifiers renamed (link4/writeFile14).
    # Verified live on 0.33.2 (Sep 25 2026): unpatched, the listener lock
    # EACCES-kills the server on every start (hard links forbidden on
    # app_data_file). O_EXCL create is equally atomic and Android-legal.
    if 'await writeFile14(candidatePath, contents, { flag: "wx" });\n    await link4(candidatePath, targetPath);' in s:
        s = s.replace(
            'await writeFile14(candidatePath, contents, { flag: "wx" });\n    await link4(candidatePath, targetPath);',
            'await writeFile14(targetPath, contents, { flag: "wx" }); // ANDROID: link() forbidden on app storage; O_EXCL create is equally atomic\n    // link4 removed for Android', 1)
        n += 1
    # Generic future-proof fallback: any writeFileN(candidate)+linkN(candidate)
    # pair -> direct O_EXCL create (survives identifier renames upstream).
    if n == 0:
        import re as _re
        _pat = _re.compile(r'await (writeFile\d+)\(candidatePath, contents, \{ flag: "wx" \}\);\s*\n(\s*)await link\d+\(candidatePath, targetPath\);')
        for _m in list(_pat.finditer(s)):
            s = s[:_m.start()] + f'await {_m.group(1)}(targetPath, contents, {{ flag: "wx" }}); // ANDROID: link() forbidden on app storage\n{_m.group(2)}// link removed for Android' + s[_m.end():]
            n += 1
    print(f"  letta.js: {n} link site(s) patched")

    print("5. letta.js Android shell spawn sites")
    # 5a. Shell-shim shebang: ensureLettaShimDir embeds `#!/bin/sh\nexec <cmd>`
    #     as the shim body. Android has no /bin/sh at that path, so the shim
    #     cannot be exec'd. Rewrite the full template (guard: the unrelated
    #     hf-askpass helper is array-built and does not match).
    SHIM_OLD = '#!/bin/sh\nexec '
    SHIM_NEW = '#!/system/bin/sh\nexec '
    m = s.count(SHIM_OLD)
    if m:
        s = s.replace(SHIM_OLD, SHIM_NEW)
        print(f"  letta.js: {m} shell-shim shebang site(s) patched")
    else:
        already = s.count(SHIM_NEW)
        print(f"  letta.js: 0 shebang sites found ({already} already /system/bin/sh)")
    # 5b. Persistent-shell beta tool (0.32.x): BashSession spawns an absolute
    #     /bin/bash directly, ignoring SHELL/PATH. Route through SHELL (the
    #     launch script points it at the files/bin/bash wrapper). Guarded:
    #     pattern is absent in 0.30.x and skipped.
    BSESS_OLD = 'cp.spawn("/bin/bash", ["--noprofile", "--norc"]'
    BSESS_NEW = 'cp.spawn(process.env.SHELL || "/bin/bash", ["--noprofile", "--norc"]'
    if BSESS_OLD in s:
        s = s.replace(BSESS_OLD, BSESS_NEW)
        print("  letta.js: BashSession spawn -> SHELL-aware")

    open(letta, "wb").write(s.encode("utf-8", "surrogateescape"))

    print("6. node uv__close assert -> skip fds <= 2")
    # uv__close @ vaddr 0x1c2ec00; .text vaddr->file delta is 0x400000.
    # 1c2ec0c: cmp w0,#2 ; 1c2ec10: b.le 0x1c2ec58 (assert path)
    # Retarget b.le to 0x1c2ec4c (epilogue): 4d020054 -> ed010054.
    off = 0x1C2EC10 - 0x400000
    old = bytes.fromhex("4d020054")
    new = bytes.fromhex("ed010054")
    data = bytearray(open(node, "rb").read())
    cur = bytes(data[off : off + 4])
    if cur == old:
        data[off : off + 4] = new
        open(node, "wb").write(data)
        print("  node: uv__close b.le assert-path -> epilogue (fds <= 2 skipped, not closed)")
    elif cur == new:
        print("  node: already patched")
    else:
        sys.exit(f"FATAL: unexpected bytes at node+{off:#x}: {cur.hex()} — node build changed, re-derive offset")

    print("done.")

if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else "/tmp/zfold-build/rootfs")
