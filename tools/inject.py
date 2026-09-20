import sys, zipfile, os, tempfile, shutil

apk, stage = sys.argv[1], sys.argv[2]

# AGP's asset merge step gunzips *.gz assets and strips the ".gz" suffix
# (aapt legacy behaviour): app/src/main/assets/git-arm64.tar.gz is packaged
# as assets/git-arm64.tar, DECOMPRESSED. The git payloads must reach the
# device byte-exact (sha-pinned tarballs; the git wrapper extracts them
# with `tar -xzf`), so the mangled merge output is dropped here and the
# original .gz files are re-added from the stage dir in the same pass that
# injects the native libs.
stage_assets = os.path.join(stage, "assets")
mangled = set()
if os.path.isdir(stage_assets):
    for root, dirs, files in os.walk(stage_assets):
        for f in files:
            if f.endswith(".gz"):
                rel = os.path.relpath(os.path.join(root, f), stage)
                mangled.add(rel[:-3])  # entry name the merge step produced

# Rewrite APK without the old injected libs or the merge-mangled payloads
tmp = tempfile.mktemp(suffix=".apk")
with zipfile.ZipFile(apk, "r") as zin, zipfile.ZipFile(tmp, "w", zipfile.ZIP_STORED) as zout:
    for item in zin.infolist():
        if item.filename.startswith("lib/arm64-v8a/"):
            continue  # drop old injected entries
        if item.filename in mangled:
            continue  # drop gunzipped/renamed copy; re-added below, exact
        zout.writestr(item, zin.read(item.filename))
shutil.move(tmp, apk)

# Add fresh entries (ZIP_STORED: .so must be uncompressed for extraction,
# .gz re-compression is pointless)
added = []
with zipfile.ZipFile(apk, "a", zipfile.ZIP_STORED) as zf:
    for sub in ("lib", "assets"):
        base = os.path.join(stage, sub)
        if not os.path.isdir(base):
            continue
        for root, dirs, files in os.walk(base):
            for f in sorted(files):
                full = os.path.join(root, f)
                arc = os.path.relpath(full, stage)
                zf.write(full, arc)
                added.append(arc)
print(f"rewrote APK, injected {len(added)} files: {', '.join(sorted(added)[:5])}...")
