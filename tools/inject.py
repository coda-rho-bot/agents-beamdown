import sys, zipfile, os, shutil

apk = sys.argv[1]
stage = sys.argv[2]  # dir containing lib/arm64-v8a/...

with zipfile.ZipFile(apk, "a", zipfile.ZIP_STORED) as zf:
    for root, dirs, files in os.walk(os.path.join(stage, "lib")):
        for f in files:
            full = os.path.join(root, f)
            arc = os.path.relpath(full, stage)
            zf.write(full, arc)
            print(f"added {arc} ({os.path.getsize(full)} bytes)")
print("injection complete")
