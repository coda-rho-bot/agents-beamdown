// fs-shim.js — Android app storage forbids hard links (SELinux EACCES on
// app_data_file link()). letta.js uses write-temp-then-link as its atomic
// publish idiom for ALL lock files (checkout locks, remote-settings locks,
// listener locks). Rather than string-patching each call site per version
// (fragile: esbuild renames identifiers every release), replace link()/
// linkSync() at runtime with an O_EXCL write of the source content —
// the same atomic create-if-absent semantics, Android-legal.
//
// Loaded via --require (same mechanism as dns-shim.js) BEFORE letta.js.
const fs = require("fs");
const fsp = fs.promises;

// Preserve source file mode where the platform lets us (link() would keep
// it). We read the mode stat first; failures fall back to plain write.
function errEEXIST(src, dest) {
  const err = new Error(`EEXIST: file already exists, link '${src}' -> '${dest}'`);
  err.code = "EEXIST"; err.errno = -17; err.path = dest; err.dest = dest; err.src = src;
  return err;
}

async function linkViaWrite(src, dest) {
  // link() does NOT follow symlinks in src on Linux — it hard-links the
  // symlink itself. Emulating THAT exactly is impossible without link();
  // our approximation links the TARGET's content. No caller in letta.js
  // links symlinks (locks are regular files), so this is acceptable —
  // documented divergence.
  const data = await fsp.readFile(src); // preserves ENOENT for missing src
  let mode = 0o644;
  try { mode = (await fsp.stat(src)).mode & 0o777; } catch {}
  try {
    const handle = await fsp.open(dest, "wx", mode);
    await handle.writeFile(data);
    await handle.close();
    return undefined;
  } catch (e) {
    if (e.code === "EEXIST") throw errEEXIST(src, dest);
    throw e;
  }
  // NOTE: unlike real link(), the temp source is NOT removed — real link()
  // doesn't remove it either; callers unlink their own temp files.
}

function linkViaWriteSync(src, dest) {
  const data = fs.readFileSync(src);
  let mode = 0o644;
  try { mode = fs.statSync(src).mode & 0o777; } catch {}
  try {
    const fd = fs.openSync(dest, "wx", mode);
    fs.writeSync(fd, data);
    fs.closeSync(fd);
    return undefined;
  } catch (e) {
    if (e.code === "EEXIST") throw errEEXIST(src, dest);
    throw e;
  }
}

// Patch every entry point the bundle might use. Node 22: require("fs").promises
// === require("node:fs/promises") (same module instance), so patching the
// promises object covers both. ESM `import { link } from "node:fs/promises"`
// destructures at module-init — our preload runs FIRST, so bindings capture
// the patched function. (esbuild bundles that __require("fs") at runtime also
// hit the patched object.)
fsp.link = linkViaWrite;
fs.linkSync = linkViaWriteSync;
const realLinkCb = fs.link; // callback form
fs.link = function androidLink(src, dest, ...rest) {
  const cb = rest[rest.length - 1];
  if (typeof cb === "function") {
    linkViaWrite(src, dest).then(() => cb(null), (e) => cb(e));
    return;
  }
  return realLinkCb(src, dest, ...rest); // promisified no-callback usage
};

// Belt & suspenders: node:fs/promises accessed directly.
try {
  const fspDirect = require("node:fs/promises");
  if (fspDirect !== fsp) fspDirect.link = linkViaWrite;
} catch {}
