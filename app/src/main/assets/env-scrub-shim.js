// env-scrub-shim.js — spawn-boundary API-key scrub (design-docs/spawn-boundary-scrub-design.md).
//
// SECURITY (todo 1c786d4e8f8a, [WILL HURT]): the server process receives the
// master LETTA_API_KEY via ProcessBuilder env (correct — the server needs it
// for its own auth). But node passes the FULL environ to every child it
// spawns: any Bash-tool command, npm lifecycle script, or build step inherits
// Harry's master Letta credential in its own env (readable via /proc/environ).
//
// This shim loads via --require INSIDE the server process (before letta.js)
// and patches child_process so that children NEVER receive LETTA_API_KEY:
//   - spawn/spawnSync: scrub options.env if explicitly provided; when env is
//     NOT provided, node would inherit process.env — we intercept by always
//     materializing an explicit env (copy of process.env minus the key), so
//     the inherit path is scrubbed too.
//   - exec/execSync/execFile/execFileSync: these route through child_process
//     internally with env: process.env — the same patch covers them (they
//     call the patched spawn under the hood when we patch the module's
//     exports directly).
//
// Empirical basis (probe Nov 7, 0.34.4-pinned rootfs, 35,470 files): ZERO
// runtime code reads LETTA_API_KEY by name — the exposure is pure inherited-
// variable dead weight. Scrubbing breaks nothing on the current pin.
// Version note (probe #5): 0.34.25+ adds a keychain migration that reads the
// env key — on any pin bump, that migration MUST run before this scrub loads.
//
// The SERVER's own process.env is untouched (log redaction + all classified
// read sites consume the ambient key server-side — see the 129-site
// classification; scrubbing the server env was the INVALIDATED first fix).
const cp = require("child_process");

const KEY = "LETTA_API_KEY";

function scrubEnv(env) {
  if (env === undefined || env === null) {
    // Inherit path: materialize an explicit scrubbed copy.
    const copy = Object.assign({}, process.env);
    delete copy[KEY];
    return copy;
  }
  if (typeof env === "object") {
    const copy = Object.assign({}, env);
    delete copy[KEY];
    return copy;
  }
  return env;
}

function wrapSyncOrAsync(fn, argScrubber) {
  return function (...args) {
    argScrubber(args);
    return fn.apply(this, args);
  };
}

// Patch the module's public surface. letta.js may destructure
// ({spawn} = require("child_process")) BEFORE our --require runs? No —
// --require modules execute before the entry script, so we patch first.
// But defensive: also patch the module namespace's properties if mutable.

// spawn(command[, args][, options]) — SIGNATURE-POSITION semantics (Uncia R1,
// review 3783): the LAST non-function argument IS the options object when it
// sits at a signature position (index 1 or 2); never heuristic-detect by
// known keys. The old heuristic FAILED OPEN on unknown-only keys ({timeout},
// {uid}, {gid}, {signal}): the scrubbed env got pushed as a trailing 4th arg,
// node ignored it, and the child inherited process.env — the key leaked
// through the patch itself. Reproduced by Uncia; fixed by mirroring the
// execFile patcher's scan-from-end position check.
function patchSpawnArgs(args) {
  for (let i = args.length - 1; i >= 0; i--) {
    const a = args[i];
    if (typeof a === "function") continue;
    if (a && typeof a === "object" && !Array.isArray(a) && i >= 1 && i <= 2) {
      // Signature position: options at index 1 (cmd, opts) or 2 (cmd, args, opts).
      args[i] = Object.assign({}, a, { env: scrubEnv(a.env) });
      return;
    }
    break; // first non-function from the end that isn't a plain object
  }
  // No options at a signature position → inherit path. Inject explicit
  // scrubbed env as the options argument (at index 1 or 2) to defeat inherit.
  const opts = { env: scrubEnv(undefined) };
  args.push(opts);
}

// execFile(command[, args][, options][, callback]) — scrub options same shape
// as spawn (exec inherits from execFile internally in modern node; patching
// execFile + spawn covers exec/execSync/execFileSync/spawnSync if they route
// through the same C++ binding — but they bind separately, so patch each).
function patchExecFileArgs(args) {
  // Find the options object: scan from the end for the first non-function arg.
  for (let i = args.length - 1; i >= 0; i--) {
    const a = args[i];
    if (typeof a === "function") continue;
    if (a && typeof a === "object" && !Array.isArray(a)) {
      args[i] = Object.assign({}, a, { env: scrubEnv(a.env) });
      return;
    }
    break; // first non-function from the end that isn't an options object
  }
  // No options object (or it was before callbacks): insert scrubbed env
  // before any trailing callbacks.
  const insertAt = args.findIndex(a => typeof a === "function");
  const opts = { env: scrubEnv(undefined) };
  if (insertAt === -1) args.push(opts); else args.splice(insertAt, 0, opts);
}

for (const name of ["spawn", "execFile", "exec", "spawnSync", "execFileSync", "execSync", "fork"]) {
  const orig = cp[name];
  if (typeof orig !== "function") continue;
  const patcher = (name === "spawn" || name === "spawnSync" || name === "fork") ? patchSpawnArgs : patchExecFileArgs;
  cp[name] = wrapSyncOrAsync(orig, patcher);
}
