#!/usr/bin/env python3
"""
Generate app/src/main/assets/integrity-manifest.txt — the runtime integrity
authority for agent-writable files (review 2731, PR #118).

AUTHORITY MODEL
  The APK asset `integrity-manifest.txt` is the single source of truth.
  Every protected file's sha256 is pinned there; the installer's
  verify-or-restore pass and the boot gate both derive from it (no
  hand-copied expected-set literal in Kotlin). The rootfs copy of a lib is
  treated as UNTRUSTED CACHE — restore bytes come from immutable APK
  sources (direct asset, or members of the pinned rootfs.tar asset).

HASH ORIGINS (documented per the review):
  - dns-shim.js / fs-shim.js / dnsport.so: hashed directly from
    app/src/main/assets/ (APK assets).
  - 8 glibc libs + pty.node: streamed from members of
    app/src/main/assets/rootfs.tar — the CI-pinned authority build
    (sha256 78d4135a…, 193,863,960 bytes, letta-code 0.34.4, Oct 7 2026;
    cross-check .woodpecker/release.yml EXPECTED_SHA if your local
    rootfs.tar's provenance is uncertain).
  - sharp arm64 addons: hashed from the BUILD-STAGED swap tarballs in
    tools/rootfs-build/swap/ because the current pinned rootfs.tar
    predates the arm64 swap step (rebuild.sh step 2) — these entries are
    verify-if-present at runtime. After the next rootfs rebuild folds the
    swap in, regenerate: the hashes will then come from rootfs.tar members
    and the entries become full verify-or-restore.

REGENERATION
  Run after ANY change to a listed source:
      python3 tools/integrity/generate-manifest.py
  Then commit the regenerated asset. The Kotlin gate compares against the
  ASSET, not code constants — no code change accompanies a regen unless
  the entry set itself changes.
"""

import hashlib
import os
import sys
import tarfile

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
ASSETS = os.path.join(ROOT, "app", "src", "main", "assets")
SWAP = os.path.join(ROOT, "tools", "rootfs-build", "swap")
OUT = os.path.join(ASSETS, "integrity-manifest.txt")
ROOTFS_TAR = os.path.join(ASSETS, "rootfs.tar")

# (dest, rootfs_source_path, tar_member_override_or_None)
# tar_member_override: real (non-symlink) member inside rootfs.tar when the
# rootfs path is a symlink (usrmerge/Debian soname links).
ROOTFS_LIBS = [
    ("libs/libc.so.6", "usr/lib/aarch64-linux-gnu/libc.so.6", None),
    ("libs/libm.so.6", "usr/lib/aarch64-linux-gnu/libm.so.6", None),
    ("libs/libdl.so.2", "usr/lib/aarch64-linux-gnu/libdl.so.2", None),
    ("libs/libpthread.so.0", "usr/lib/aarch64-linux-gnu/libpthread.so.0", None),
    ("libs/libgcc_s.so.1", "usr/lib/aarch64-linux-gnu/libgcc_s.so.1", None),
    ("libs/libstdc++.so.6", "usr/lib/aarch64-linux-gnu/libstdc++.so.6",
     "usr/lib/aarch64-linux-gnu/libstdc++.so.6.0.30"),
    ("libs/libtinfo.so.6", "usr/lib/aarch64-linux-gnu/libtinfo.so.6",
     "usr/lib/aarch64-linux-gnu/libtinfo.so.6.4"),
    ("libs/libresolv.so.2", "usr/lib/aarch64-linux-gnu/libresolv.so.2", None),
    ("libs/pty.node",
     "usr/local/lib/node_modules/@letta-ai/letta-code/node_modules/node-pty/build/Release/pty.node",
     None),
]

# (dest, rootfs_source_path, swap_tgz, member_inside_tgz)
# Hashes come from the staged swap tarballs TODAY (rootfs.tar predates the
# swap); the rootfs_source_path is what the runtime restores from once a
# rebuilt rootfs.tar carries the packages. OPTIONAL until then: the current
# pinned rootfs.tar cannot restore them on fresh installs, so the boot gate
# treats absence as tolerated (present-but-wrong still blocks). After the
# next rootfs rebuild folds the swap in, move these entries to ROOTFS_LIBS
# (hash from rootfs.tar) and drop the optional flag — regenerate.
SWAP_STAGED = [
    ("libs/sharp-linux-arm64.node",
     "usr/local/lib/node_modules/@letta-ai/letta-code/node_modules/@img/sharp-linux-arm64/lib/sharp-linux-arm64.node",
     "img-sharp-linux-arm64-0.34.5.tgz", "package/lib/sharp-linux-arm64.node"),
    ("libs/libvips-cpp.so.8.17.3",
     "usr/local/lib/node_modules/@letta-ai/letta-code/node_modules/@img/sharp-libvips-linux-arm64/lib/libvips-cpp.so.8.17.3",
     "img-sharp-libvips-linux-arm64-1.2.4.tgz", "package/lib/libvips-cpp.so.8.17.3"),
]

# (dest, apk_asset_name) — hashed straight from the APK asset.
ASSET_FILES = [
    ("dns-shim.js", "dns-shim.js"),
    ("fs-shim.js", "fs-shim.js"),
    ("libs/dnsport.so", "dnsport.so"),
]


def sha256_stream(f):
    h = hashlib.sha256()
    for chunk in iter(lambda: f.read(1 << 16), b""):
        h.update(chunk)
    return h.hexdigest()


def main():
    if not os.path.isfile(ROOTFS_TAR):
        sys.exit(f"FATAL: {ROOTFS_TAR} missing (gitignored — fetch from the "
                 f"internal/rootfs-source release or run the rootfs build)")
    rootfs_sha = sha256_stream(open(ROOTFS_TAR, "rb"))

    entries = []  # (dest, sha, source_spec, tar_member_or_empty, optional, provenance)
    with tarfile.open(ROOTFS_TAR, "r:gz") as rt:
        for dest, rpath, member_override in ROOTFS_LIBS:
            member = "./" + (member_override or rpath)
            f = rt.extractfile(member)
            if f is None:
                sys.exit(f"FATAL: rootfs.tar member {member} not a regular file")
            entries.append((dest, sha256_stream(f), f"rootfs:{rpath}",
                            member_override or "", False, f"rootfs.tar[{member}]"))

    for dest, rpath, tgz, member in SWAP_STAGED:
        path = os.path.join(SWAP, tgz)
        if not os.path.isfile(path):
            sys.exit(f"FATAL: swap tarball {path} missing")
        with tarfile.open(path, "r:gz") as st:
            f = st.extractfile(member)
            if f is None:
                sys.exit(f"FATAL: {tgz} member {member} not a regular file")
            entries.append((dest, sha256_stream(f), f"rootfs:{rpath}", "", True,
                            f"swap/{tgz}[{member}]"))

    for dest, asset in ASSET_FILES:
        path = os.path.join(ASSETS, asset)
        if not os.path.isfile(path):
            sys.exit(f"FATAL: asset {path} missing")
        entries.append((dest, sha256_stream(open(path, "rb")),
                        f"asset:{asset}", "", False, f"apk-asset[{asset}]"))

    # Stable order: filesDir-root entries first, then libs/ alphabetically.
    entries.sort(key=lambda e: (0 if "/" not in e[0] else 1, e[0]))

    lines = [
        "# integrity-manifest v1 — GENERATED by tools/integrity/generate-manifest.py; do not hand-edit",
        "# authority: this APK's assets. dest<TAB>sha256<TAB>source<TAB>[tar-member]",
        "# source: asset:<apk-asset-name> (immutable authority)",
        "#         rootfs:<path-under-filesDir/rootfs> (untrusted cache; the real",
        "#         authority bytes live in the ./<tar-member or path> member of the",
        "#         APK's pinned rootfs.tar asset)",
        "# col5 'optional' = verify-if-present (build cannot restore on fresh",
        "#         installs yet); absence tolerated, wrong content still blocks.",
        f"# rootfs.tar authority sha256 at generation: {rootfs_sha}",
        "# runtime parses the APK asset only; the filesDir copy is diagnostics.",
        "# git-arm64.tar.gz is intentionally NOT listed (payload-resilience +",
        "# self-heal download own it; not hashed at runtime).",
    ]
    for dest, sha, src, member, optional, prov in entries:
        tail = (f"\t{member}" if member else "") + ("\toptional" if optional else "")
        lines.append(f"{dest}\t{sha}\t{src}{tail}")
        lines.append(f"# provenance: {prov}")

    with open(OUT, "w") as f:
        f.write("\n".join(lines) + "\n")
    print(f"wrote {OUT} ({len(entries)} entries)")
    for dest, sha, src, member, optional, prov in entries:
        print(f"  {dest:32s} {sha[:16]}… {prov}" + (" [optional]" if optional else ""))


if __name__ == "__main__":
    main()
