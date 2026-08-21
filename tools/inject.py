import sys, zipfile, os, tempfile, shutil

apk, stage = sys.argv[1], sys.argv[2]

# Rewrite APK without any existing lib/arm64-v8a entries (idempotent injection)
tmp = tempfile.mktemp(suffix=".apk")
with zipfile.ZipFile(apk, "r") as zin, zipfile.ZipFile(tmp, "w", zipfile.ZIP_STORED) as zout:
    for item in zin.infolist():
        if item.filename.startswith("lib/arm64-v8a/"):
            continue  # drop old injected entries
        zout.writestr(item, zin.read(item.filename))
shutil.move(tmp, apk)

# Add fresh entries
added = []
with zipfile.ZipFile(apk, "a", zipfile.ZIP_STORED) as zf:
    for root, dirs, files in os.walk(os.path.join(stage, "lib")):
        for f in sorted(files):
            full = os.path.join(root, f)
            arc = os.path.relpath(full, stage)
            zf.write(full, arc)
            added.append(arc)
print(f"rewrote APK, injected {len(added)} libs: {', '.join(sorted(added)[:5])}...")
