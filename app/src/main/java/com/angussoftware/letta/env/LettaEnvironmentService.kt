package com.angussoftware.letta.env

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import java.io.File
import java.nio.file.Files
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Runs the letta server as a cloud execution environment on Android.
 *
 * Architecture (v3 — native lib dir, "everything exec-mapped lives in libdir"):
 *
 * Android's untrusted_app domain DENIES mmap(PROT_EXEC) of app_data_file
 * (files in filesDir), while execve of app_data IS allowed (static binaries
 * run) and the APK native lib dir (apk_data_file label) allows execute
 * mappings unconditionally. Therefore every file the dynamic loader maps —
 * the loader itself, libc & friends, node, guest bash, ripgrep, and all
 * .node native addons — ships in lib/arm64-v8a (injected post-build because
 * AGP filters non-lib*.so names) and is invoked as:
 *
 *   <libdir>/libldlnx.so --library-path <libdir> <libdir>/libnode.so <letta.js> server ...
 *
 * Additional Android fixes layered on top:
 *   - letta.js ships PRE-PATCHED: link()/linkSync() lock files replaced with
 *     writeFileSync(flag:"wx") — SELinux denies hardlinks on app_data_file.
 *   - ld-linux + libc patched: rseq syscall site returns -ENOSYS — the app
 *     seccomp filter (old targetSdk policy) TRAPs syscall 293 → SIGSYS.
 *   - dns-shim.js preloaded via NODE_OPTIONS: no /etc/resolv.conf on Android.
 *   - files/bin/bash wrapper: Bash tool → Debian bash via libdir chain.
 *   - rg wrapper script replaces the glibc rg binary (its PT_INTERP doesn't
 *     exist on the host fs); addons + libvips are symlinked into node_modules.
 */
class LettaEnvironmentService : Service() {

    companion object {
        // Dedicated channel for the persistent environment-running notification so users
        // can control it independently from any future alert channels.
        private const val CHANNEL_ID = "letta-env-status"
        private const val NOTIFICATION_ID = 42
        @Volatile private var proc: Process? = null
        // Lifecycle state. Values: idle -> starting -> running -> stopping.
        // The worker holds "starting"/"running" ONLY while its process lives;
        // it returns to idle in the worker's finally block. Intent handlers
        // (start/upgrade/rekey) CAS from a state they can actually occupy —
        // see onStartCommand. Replaces the old single `starting` boolean,
        // which the worker held for the ENTIRE server lifetime (waitFor
        // blocks in runEnvironment), making Upgrade a silent no-op whenever
        // the server was up (adversarial review Sep 20, finding task_93 #1).
        private val lifecycle = java.util.concurrent.atomic.AtomicReference(State.IDLE)
        private enum class State { IDLE, STARTING, RUNNING, STOPPING }
        // Pending operation set while the server runs; the worker loop acts
        // on it after the current process exits (see worker body).
        private val pendingOp = java.util.concurrent.atomic.AtomicReference<PendingOp>(null)
        private enum class PendingOp { UPGRADE, RESTART, SHUTDOWN }
        const val PREFS = "letta_env"
        const val PREF_KEY = "api_key"
        const val ACTION_UPGRADE = "com.angussoftware.letta.env.action.UPGRADE"
        const val ACTION_RESTART = "com.angussoftware.letta.env.action.RESTART"
        const val ACTION_START_EXPLICIT = "com.angussoftware.letta.env.action.START_EXPLICIT"
        const val ACTION_STOP_EXPLICIT = "com.angussoftware.letta.env.action.STOP_EXPLICIT"
        const val PREF_ENV = "env_name"
        const val DEFAULT_ENV = "android"

        /**
         * True when no agent session/environment process is running (idle,
         * per the v0.2.8 lifecycle state machine). The self-updater gates
         * its prompts and overnight auto-installs on this — never update
         * out from under a live session.
         */
        fun isEnvironmentIdle(): Boolean = lifecycle.get() == State.IDLE
    }

    private fun apiKey(): String =
        getSharedPreferences(PREFS, MODE_PRIVATE).getString(PREF_KEY, "") ?: ""

    private fun envName(): String =
        getSharedPreferences(PREFS, MODE_PRIVATE).getString(PREF_ENV, DEFAULT_ENV) ?: DEFAULT_ENV

    private var worker: Thread? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createChannel()
        startForeground(NOTIFICATION_ID, buildNotification("Starting..."))
        // Self-updater periodic checks ride this already-running foreground
        // service — no new always-running process (spec Sep 30).
        UpdateManager.startPeriodicChecks(this)

        // Stale-status invalidation: an install/kill leaves status.txt claiming
        // "online" from a dead process. On any service start, if no server
        // process is alive, rewrite status from reality BEFORE anything else
        // (Sep 25: watch showed "online" for 25 min with no process).
        if (proc?.isAlive != true) {
            val sf = File(filesDir, "status.txt")
            val cur = if (sf.exists()) sf.readText() else ""
            if (cur.contains("online") || cur.contains("registered")) {
                setStatus("stopped — restarting (stale online status cleared)")
                log("STATUS: cleared stale '${cur.lineSequence().firstOrNull { it.startsWith("state=") }}' (no live process)")
            }
        }

        // agentctl notify: title/text extras on a broadcast routed here
        if (intent?.action == "com.angussoftware.letta.env.AGENT_NOTIFY") {
            postAgentAlert(
                intent.getStringExtra("title") ?: "Agent",
                intent.getStringExtra("text") ?: "")
            return START_NOT_STICKY
        }

        when (intent?.action) {
            ACTION_UPGRADE -> handleUpgradeIntent()
            ACTION_RESTART -> handleRestartIntent()
            ACTION_START_EXPLICIT -> {
                // Explicit Start button: clears the user-stop marker so the
                // server (re)launches even if previously stopped by the user.
                File(filesDir, ".user-stopped").delete()
                handleStartIntent()
            }
            ACTION_STOP_EXPLICIT -> {
                // Explicit Stop button: sticky marker so app re-opens don't
                // resurrect. onDestroy does NOT set the marker — install-kills
                // also run onDestroy and must not look like user stops.
                runCatching { File(filesDir, ".user-stopped").writeText(Date().toString()) }
                pendingOp.set(PendingOp.SHUTDOWN)
                lifecycle.compareAndSet(State.IDLE, State.STOPPING)
                setStatus("stopped (by user)")
                try { proc?.destroy() } catch (_: Exception) {}
                stopSelf()
            }
            else -> handleStartIntent()
        }
        return START_STICKY
    }

    /**
     * Start when idle. If already running: schedule a restart ONLY when the
     * configuration changed (re-key / rename — MainActivity rewrites prefs
     * before sending this); otherwise it's a stray double-tap — re-assert the
     * current state instead of corrupting it (old bug: setStatus("starting")
     * fired BEFORE the guard, leaving status/notification stuck at "starting"
     * forever while the server ran fine — review task_93 #2).
     */
    private fun handleStartIntent() {
        // User-stop is sticky across app re-opens: if the user pressed Stop,
        // opening the app must NOT silently resurrect the server. The marker
        // is cleared by the Start button (explicit user intent) and by
        // saveOnboarding (config change = definite restart).
        val stopMarker = File(filesDir, ".user-stopped")
        if (stopMarker.exists() && lifecycle.get() == State.IDLE) {
            log("start intent with user-stop marker and no server — staying stopped (explicit Start clears)")
            updateNotification("Stopped — tap Start to run")
            return
        }
        when (lifecycle.get()) {
            State.IDLE -> {
                if (!lifecycle.compareAndSet(State.IDLE, State.STARTING)) return handleStartIntent()
                setStatus("starting")
                spawnWorker()
            }
            State.RUNNING, State.STARTING -> {
                if (configChanged()) {
                    log("config changed — restarting to apply new key/env name")
                    pendingOp.set(PendingOp.RESTART)
                    // Ask the running server to exit; the worker loop relaunches
                    // with the fresh prefs.
                    try { proc?.destroy() } catch (_: Exception) {}
                } else {
                    log("start requested while already ${lifecycle.get().name.lowercase()} — ignoring")
                    // Re-assert real state so status.txt/notification stay truthful.
                    reassertState()
                }
            }
            State.STOPPING -> log("start requested while stopping — ignoring")
        }
    }

    /**
     * Upgrade: when idle, run immediately. When running, mark pending and stop
     * the server — the worker loop performs upgrade + relaunch after exit
     * (old bug: the entire flow was gated on a flag held for the server's
     * whole lifetime, so Upgrade while running was ALWAYS a silent no-op —
     * review task_93 #1).
     */
    private fun handleUpgradeIntent() {
        when (lifecycle.get()) {
            State.IDLE -> {
                if (!lifecycle.compareAndSet(State.IDLE, State.STARTING)) return handleUpgradeIntent()
                setStatus("upgrading letta-code")
                spawnWorker(upgradeFirst = true)
            }
            State.RUNNING, State.STARTING -> {
                if (pendingOp.compareAndSet(null, PendingOp.UPGRADE)) {
                    log("upgrade requested while running — stopping server; upgrade follows")
                    setStatus("stopping for upgrade")
                    try { proc?.destroy() } catch (_: Exception) {}
                } else {
                    log("upgrade already pending — ignoring")
                }
            }
            State.STOPPING -> log("upgrade requested while stopping — ignoring")
        }
    }

    /** Explicit restart (used by rekey flow via start intent; kept for future UI). */
    private fun handleRestartIntent() = handleStartIntent()

    /**
     * Worker: runs the environment; on process exit, drains any pending
     * operation (upgrade / restart) in a loop until none remain, then goes
     * idle. All state transitions originate here or in the intent handlers
     * via CAS — no path leaves lifecycle stuck.
     */
    private fun spawnWorker(upgradeFirst: Boolean = false) {
        worker = Thread {
            var doUpgrade = upgradeFirst
            try {
                while (true) {
                    if (doUpgrade) {
                        try {
                            upgradeLetta()
                        } catch (t: Throwable) {
                            log("UPGRADE FATAL: ${t.message}")
                            setStatus("upgrade failed: ${t.message ?: t.javaClass.simpleName}")
                            updateNotification("Upgrade failed")
                        }
                    }
                    try {
                        runEnvironment()
                    } catch (t: Throwable) {
                        log("FATAL: ${t.message}")
                        setStatus("failed: ${t.message ?: t.javaClass.simpleName}")
                        updateNotification("Failed")
                    }
                    // Process exited. Drain pending op, else idle.
                    when (pendingOp.getAndSet(null)) {
                        PendingOp.UPGRADE -> {
                            log("pending upgrade — running it now")
                            doUpgrade = true
                            continue
                        }
                        PendingOp.RESTART -> {
                            log("pending restart — relaunching with current config")
                            doUpgrade = false
                            continue
                        }
                        // Review #18 F2 (Angus): user Stop during a pending op
                        // must NOT relaunch — the drain loop treats SHUTDOWN
                        // as terminal and the worker exits to idle.
                        PendingOp.SHUTDOWN -> {
                            log("shutdown requested — not relaunching")
                            break
                        }
                        null -> break
                    }
                }
            } finally {
                lifecycle.set(State.IDLE)
            }
        }.also { it.start() }
    }

    /**
     * True when prefs (API key / env name) differ from what the RUNNING server
     * was launched with. The launch snapshot is written by runEnvironment().
     */
    private fun configChanged(): Boolean {
        val snap = File(filesDir, ".launch-config")
        if (!snap.exists()) return true
        val expected = "${apiKey()}\n${envName()}"
        return snap.readText() != expected
    }

    /** Rewrite status.txt + notification from process reality, not intent history. */
    private fun reassertState() {
        val alive = proc?.isAlive == true
        val statusFile = File(filesDir, "status.txt")
        val current = if (statusFile.exists()) statusFile.readText() else ""
        if (!alive) return // worker will transition states momentarily
        if (!current.contains("online") && !current.contains("registered")) {
            // Unknown-but-alive: the tailer's string matching may have missed
            // upstream log wording; state the provable fact.
            setStatus("running (process alive)")
            updateNotification("Running — ${envName()}")
        } else {
            updateNotification("Online — ${envName()}")
        }
    }

    /**
     * Upgrade @letta-ai/letta-code inside the rootfs via npm, re-apply the
     * Android-specific letta.js patches (SELinux forbids the hardlink-based
     * locks), then hand back to runEnvironment() to relaunch the server.
     */
    private fun upgradeLetta() {
        val libDir = File(applicationInfo.nativeLibraryDir)
        val libLoader = File(libDir, "libldlnx.so")
        val rootfsDir = File(filesDir, "rootfs")
        val nodeModules = File(rootfsDir, "usr/local/lib/node_modules")
        val npmCli = File(nodeModules, "npm/bin/npm-cli.js")
        val pkgJson = File(nodeModules, "@letta-ai/letta-code/package.json")

        fun currentVersion(): String = try {
            val m = Regex("\"version\"\\s*:\\s*\"([^\"]+)\"").find(pkgJson.readText())
            m?.groupValues?.get(1) ?: "unknown"
        } catch (_: Exception) { "unknown" }

        val before = currentVersion()
        setStatus("upgrading letta-code (from $before)")
        updateNotification("Upgrading letta-code...")
        log("UPGRADE: current version $before")

        // 1. Stop the running server (it holds the install we're replacing).
        try { proc?.destroy() } catch (_: Exception) {}
        runCatching { proc?.waitFor(5, TimeUnit.SECONDS) }
        proc = null

        // 2. npm install -g @letta-ai/letta-code@latest via the loader chain.
        //    --ignore-scripts: postinstall spawns `node` via process.execPath,
        //    which is the loader invocation here and cannot be re-spawned raw.
        val npmEnv = mapOf(
            "HOME" to File(rootfsDir, "root").absolutePath,
            "TMPDIR" to File(rootfsDir, "tmp").absolutePath,
            "PATH" to File(filesDir, "bin").absolutePath + ":/system/bin:/system/xbin",
            "SHELL" to File(File(filesDir, "bin"), "bash").absolutePath,
            "LD_LIBRARY_PATH" to libDir.absolutePath + ":" + File(filesDir, "libs").absolutePath,
            "NODE_OPTIONS" to "--require ${File(filesDir, "fs-shim.js").absolutePath} --require ${File(filesDir, "dns-shim.js").absolutePath}",
            "UV_USE_IO_URING" to "0"
        )
        val pb = ProcessBuilder(
            libLoader.absolutePath, "--library-path", npmEnv["LD_LIBRARY_PATH"]!!,
            File(libDir, "libnode.so").absolutePath, npmCli.absolutePath,
            "install", "-g", "@letta-ai/letta-code@latest", "--ignore-scripts",
            "--prefix", File(rootfsDir, "usr/local").absolutePath
        ).apply {
            environment().clear()
            environment().putAll(npmEnv)
            redirectErrorStream(true)
        }
        log("UPGRADE: running npm install @letta-ai/letta-code@latest ...")
        val p = try { pb.start() } catch (t: Throwable) {
            log("UPGRADE: npm failed to start: ${t.message}")
            setStatus("upgrade failed: ${t.message}")
            return
        }
        // Stream npm output into server.log.
        val reader = Thread {
            try {
                p.inputStream.bufferedReader().forEachLine { log("npm: $it") }
            } catch (_: Exception) {}
        }.also { it.isDaemon = true; it.start() }
        val finished = p.waitFor(10, TimeUnit.MINUTES)
        if (!finished) {
            p.destroyForcibly()
            log("UPGRADE: npm timed out after 10 minutes")
            setStatus("upgrade failed: npm timeout")
            updateNotification("Upgrade failed")
            return
        }
        val npmExit = p.exitValue()
        log("UPGRADE: npm exit code $npmExit")
        if (npmExit != 0) {
            log("UPGRADE: npm FAILED — restarting with whatever is installed (npm usually leaves the previous version intact)")
            setStatus("npm failed (exit $npmExit) — restarting")
            updateNotification("Upgrade failed — restarting")
            return
        }

        // 3. Re-apply Android patches to the fresh letta.js (hardlink locks
        //    are denied by SELinux on app_data_file). Mirror of patches #4 and
        //    #5 in tools/rootfs-build/patch-binaries.py — keep both in sync.
        // fs-shim.js handles ALL link() emulation at runtime now. String
        // patches CONFLICT with it (patched sites double-create: wx-write
        // from the patch + emulated link's own wx-write = EEXIST crash on
        // boot, Sep 26). Only the launcher/interpreter patches remain needed;
        // the link rewrites must NOT run when the shim is deployed.
        val applied = applyLettaJsPatches(nodeModules, linksHandled = true)
        if (applied == 0) {
            log("UPGRADE: WARNING — 0 patch sites found in new letta.js; " +
                "lock-file hardlinks will fail under SELinux. Upstream code changed — " +
                "update the patch patterns in the service AND patch-binaries.py.")
        }

        val after = currentVersion()
        log("UPGRADE: $before -> $after")
        setStatus("upgraded to $after — restarting")
        updateNotification("Upgraded to $after — restarting")
    }

    /**
     * Idempotent Android patches to letta.js. Mirror of patches #4/#5 in
     * tools/rootfs-build/patch-binaries.py — keep both in sync. Returns the
     * number of patch sites applied (0 = already patched OR upstream changed;
     * callers that just ran npm warn on 0, restart-path callers do not).
     *
     *  - link()/linkSync() lock files -> writeFileSync(flag:"wx") — SELinux
     *    denies hardlinks on app_data_file (2 sites).
     *  - Shell-shim shebang "#!/bin/sh" -> "#!/system/bin/sh" — the subagent
     *    `letta` shim is spawned via its interpreter line; /bin/sh does not
     *    exist on the Android host (1 site, template-guarded).
     *  - BashSession persistent-shell spawn "/bin/bash" -> SHELL-aware —
     *    absolute /bin/bash does not exist on the host (1 site, 0.32.x only).
     */
    private fun applyLettaJsPatches(nodeModules: File, linksHandled: Boolean = false): Int {
        val lettaJs = File(nodeModules, "@letta-ai/letta-code/letta.js")
        if (!lettaJs.exists()) {
            log("PATCH: letta.js not found")
            return 0
        }
        val s = lettaJs.readText()
        var patched = 0
        var out = s

        // --- Android link() rewrite -------------------------------------
        // DEPRECATED: fs-shim.js (NODE_OPTIONS preload) emulates link() at
        // runtime. These string rewrites CONFLICT with it — a patched site
        // writes the target with wx directly, then the shim's emulated link
        // ALSO writes it → EEXIST where the caller expected success → boot
        // crash (observed Sep 26 on watch upgrade). linksHandled=true skips
        // them all; kept only for emergency use (linksHandled=false).
        if (!linksHandled) {
        // SELinux denies hard links on app_data_file. Upstream uses the
        // write-temp-then-link atomic-publish idiom in FIVE places (0.33.x):
        //   1. publishExclusive (file-lock.ts)          — fleet-shared memory
        //      checkout locks, agent locks — KILLS EVERY BASH CALL when unpatched
        //   2. createInitializedTokenLinkSync (remote-settings-lock.ts)
        //   3. createInitializedTokenLink     (remote-settings-lock.ts)
        //   4. publishInitializedFile         (manual-instance-lock.ts)
        //   5. legacy channel-routing migration (linkSync(legacyPath, path))
        // O_EXCL create (flag:"wx") is equally atomic and Android-legal.
        //
        // Strategy: identifier-agnostic regexes so upstream renames
        // (writeFile14→writeFile17 etc.) don't silently break the patcher —
        // the OLD exact-string patches matched 0.33.2 partially and injected
        // stale identifiers from 0.31.x scopes (latent ReferenceErrors).

        // (a) Generic: writeFileN(candidate/temp, X, {flag:"wx"}) followed by
        // linkN(same, target) -> write directly to target with O_EXCL.
        Regex("""(await (?:writeFile\d+)\((\w+), ([\w.]+), \{ flag: "wx" \}\);)\s*\n(\s*)await (?:link\d*)\(\2, (\w+)\);""")
            .findAll(out).toList().let { ms ->
                for (m in ms) {
                    out = out.replaceRange(m.range,
                        "${m.groupValues[1].replace(m.groupValues[2], m.groupValues[5])} // ANDROID: link() forbidden on app storage; O_EXCL create is equally atomic\n${m.groupValues[4]}// link removed for Android")
                    patched++
                }
            }
        // (b) Sync variant: writeFileSyncN(candidate, X, {flag:"wx"}) + linkSync.
        Regex("""((?:writeFileSync\d*)\((\w+), ([\w.]+), \{ flag: "wx" \}\);)\s*\n(\s*)(?:linkSync)\(\2, (\w+)\);""")
            .findAll(out).toList().let { ms ->
                for (m in ms) {
                    out = out.replaceRange(m.range,
                        "${m.groupValues[1].replace(m.groupValues[2], m.groupValues[5])} // ANDROID: link() forbidden\n${m.groupValues[4]}// linkSync removed for Android")
                    patched++
                }
            }
        // (c) publishExclusive (file-lock.ts): writeFile3(temporaryPath, payload, "utf-8")
        //     then link(temporaryPath, path) — payload already in memory; retry
        //     as O_EXCL write, EEXIST = lock held (same semantics as link EEXIST).
        //     NOTE: raw strings need NO escaping for parens (a literal \( here
        //     broke containment matching on the first cut).
        if ("await link(temporaryPath, path8);" in out) {
            out = out.replace(
                """  try {
    await link(temporaryPath, path8);
    return true;
  } catch (error4) {
    if (error4?.code !== "EEXIST")
      throw error4;
    return false;
  } finally {""",
                """  try {
    await writeFile3(path8, payload, { flag: "wx" });
    return true;
  } catch (error4) {
    if (error4?.code !== "EEXIST")
      throw error4;
    return false;
  } finally {""")
            patched++
        }
        // (d) Legacy channel-routing migration: linkSync(legacyPath, path8) —
        //     non-locking file promotion; copy-then-unlink is fine here.
        if ("fs8.linkSync(legacyPath, path8);" in out) {
            out = out.replace(
                "fs8.linkSync(legacyPath, path8);",
                "fs8.copyFileSync(legacyPath, path8); // ANDROID: link() forbidden; copy+unlink for non-atomic migration")
            patched++
        }
        } // end if (!linksHandled)

        // --- Launcher / interpreter fixes --------------------------------
        // Memory git hooks (Sep 28): letta.js hardcodes `#!/usr/bin/env bash`
        // shebangs in its PRE/POST_COMMIT_HOOK_SCRIPT templates — regenerated
        // on every startup, so patching hook files is futile. /usr/bin/env
        // doesn't exist on Android → every commit fails with "cannot run
        // .git/hooks/pre-commit" unless --no-verify. Retarget the shebang at
        // our bash wrapper (kernel CAN exec /system/bin/sh scripts; the chain
        // wrapper → loader → glibc bash works — verified arrays + git hooks).
        // Exactly 2 occurrences in 0.33.x, both the hook templates.
        val hookShebang = "#!/usr/bin/env bash\n"
        val bashWrapper = File(filesDir, "bin/bash").absolutePath
        if (hookShebang in out) {
            out = out.replace(hookShebang, "#!$bashWrapper\n")
            patched++
        }
        if ("#!/bin/sh\nexec " in out) {
            out = out.replace("#!/bin/sh\nexec ", "#!/system/bin/sh\nexec ")
            patched++
        }
        if ("cp.spawn(\"/bin/bash\", [\"--noprofile\", \"--norc\"]" in out) {
            out = out.replace("cp.spawn(\"/bin/bash\", [\"--noprofile\", \"--norc\"]",
                "cp.spawn(process.env.SHELL || \"/bin/bash\", [\"--noprofile\", \"--norc\"]")
            patched++
        }
        if (patched > 0) {
            lettaJs.writeText(out)
            log("PATCH: applied $patched letta.js patch site(s)")
        } else {
            log("PATCH: letta.js already fully patched (or 0 sites matched)")
        }
        return patched
    }

    private fun runEnvironment() {
        if (apiKey().isBlank()) {
            setStatus("no api key — open the app to configure")
            updateNotification("Needs API key")
            log("No API key configured; launch blocked until onboarding completes.")
            return
        }
        val rootfsDir = File(filesDir, "rootfs")
        val marker = File(filesDir, ".rootfs-extracted")
        val libDir = File(applicationInfo.nativeLibraryDir)
        val libLoader = File(libDir, "libldlnx.so")

        // Self-heal: corrupted earlier extraction leaves marker but incomplete rootfs.
        if (marker.exists() && !File(rootfsDir, "usr/local/bin/node").exists()) {
            log("marker exists but node missing — rootfs incomplete, re-extracting")
            marker.delete()
            rootfsDir.deleteRecursively()
        }

        if (!marker.exists()) {
            setStatus("extracting rootfs (first run)")
            log("Extracting rootfs...")
            rootfsDir.mkdirs()
            val assetCopy = File(cacheDir, "rootfs.tgz")
            assets.open("rootfs.tar").use { input ->
                assetCopy.outputStream().use { output -> input.copyTo(output, 1 shl 20) }
            }
            val tar = ProcessBuilder("tar", "-xzf", assetCopy.absolutePath, "-C", rootfsDir.absolutePath)
                .redirectErrorStream(true)
                .start()
            var tarErrLines = 0
            tar.inputStream.bufferedReader().forEachLine {
                tarErrLines++
                if (tarErrLines <= 20) log("tar: $it")
            }
            val tarExit = tar.waitFor()
            if (tarExit != 0) {
                // toybox tar skips /etc/alternatives absolute symlinks and exits 1 — tolerate
                log("tar exited $tarExit ($tarErrLines warning lines) — tolerating")
            }
            assetCopy.delete()
            if (!File(rootfsDir, "usr/local/bin/node").exists()) {
                rootfsDir.deleteRecursively()
                setStatus("failed: rootfs extraction incomplete")
                updateNotification("Extraction failed")
                log("Extraction verification failed — node missing after tar")
                return
            }
            marker.createNewFile()
            log("Rootfs extracted.")
        }

        if (!libLoader.exists()) {
            setStatus("failed: native lib dir missing loader (${libDir.absolutePath})")
            log("FATAL: $libLoader not found — APK injection missing?")
            return
        }

        installSupportFiles(rootfsDir, libDir)
        sweepStaleListenerLocks(rootfsDir)

        // Re-apply letta.js patches on every start: npm-upgraded installs
        // (upgrade button) land unpatched until the NEXT upgrade, and the
        // launcher/interpreter fixes are load-bearing for the Bash tool.
        // fs-shim handles link() at runtime; on fresh installs skip the
        // link-string patches entirely (they conflict — see upgrade path).
        applyLettaJsPatches(File(rootfsDir, "usr/local/lib/node_modules"), linksHandled = true)

        val libPath = libDir.absolutePath
        val lettaJs = File(rootfsDir, "usr/local/lib/node_modules/@letta-ai/letta-code/letta.js").absolutePath

        runDiagnostics(libDir, rootfsDir)

        setStatus("starting letta server")
        log("Launching letta server (env: ${envName()}, libdir: $libPath)...")

        val dnsShim = File(filesDir, "dns-shim.js").absolutePath
        val bashDir = File(filesDir, "bin")

        val filesLibs = File(filesDir, "libs")
        val combinedPath = libPath + ":" + filesLibs.absolutePath
        // Launch via a script that mirrors the PROVEN adb test invocation:
        // shell-redirected stdio (> log 2>&1 < /dev/null) avoids node's
        // uv__close(fd<=2) assertion with Java ProcessBuilder fd plumbing.
        val launchScript = File(filesDir, "launch-server.sh")
        launchScript.writeText(
            "#!/system/bin/sh\n" +
            "cd ${filesDir.absolutePath}\n" +
            "export HOME=${File(rootfsDir, "root").absolutePath}\n" +
            "export TMPDIR=${File(rootfsDir, "tmp").absolutePath}\n" +
            "export PATH=${bashDir.absolutePath}:/system/bin:/system/xbin\n" +
            // SHELL: letta.js launcher resolution (selectAvailableShellLauncher /
            // unixLaunchers) tries process.env.SHELL FIRST; without it the first
            // candidate is an absolute "/bin/bash" that does not exist on the
            // Android host and non-win32 selection returns launchers[0] with no
            // fallback — every Bash tool call dies with ENOENT. Point SHELL at
            // the files/bin/bash wrapper (Debian bash via the libdir loader chain).
            "export SHELL=${bashDir.absolutePath}/bash\n" +
            // LETTA_CODE_BIN: subagent spawning (resolveSubagentLauncher →
            // resolveLettaInvocation) prefers this explicit binary; the defaults
            // would otherwise try to re-exec process.execPath (libnode.so via the
            // loader chain — cannot be re-spawned raw) or execute letta.js as a
            // program (ENOEXEC on Android). The wrapper lives in files/bin/letta.
            "export LETTA_CODE_BIN=${bashDir.absolutePath}/letta\n" +
            "export TERM=dumb\n" +
            "export LETTA_API_KEY=${apiKey()}\n" +
            "export LD_LIBRARY_PATH=$combinedPath\n" +
            "export NODE_OPTIONS=\"--require ${File(filesDir, "fs-shim.js").absolutePath} --require ${File(filesDir, "dns-shim.js").absolutePath}\"\n" +
            "export UV_USE_IO_URING=0\n" +
            // DNS forwarder for the glibc git ELF chain (Sep 28): dnsport.so
            // (LD_PRELOAD getaddrinfo override inside git-elf) sends raw DNS
            // to 127.0.0.1:15353; this node forwarder relays to public
            // resolvers. Must launch via the APK loader path (uv__close assert
            // otherwise) with setsid (dies with the adb shell otherwise).
            // Port check makes it idempotent across restarts. NODE_OPTIONS is
            // unset for the forwarder: fs-shim/dns-shim are for the letta
            // server process, and dns-shim (c-ares setServers) breaks the
            // forwarder's own dgram binds (empirically — it dies at launch
            // with the shims preloaded).
            "OLDNO=\$NODE_OPTIONS; unset NODE_OPTIONS\n" +
            "if ! netstat -an 2>/dev/null | grep -q 127.0.0.1:15353; then\n" +
            "  setsid nohup ${libLoader.absolutePath} --library-path $combinedPath ${File(libDir, "libnode.so").absolutePath} ${File(filesDir, "dns-forwarder.js").absolutePath} > ${File(filesDir, "dns-forwarder.log").absolutePath} 2>&1 < /dev/null &\n" +
            "fi\n" +
            "export NODE_OPTIONS=\$OLDNO\n" +
            "exec ${libLoader.absolutePath} --library-path $combinedPath ${File(libDir, "libnode.so").absolutePath} $lettaJs server --env-name '${envName()}' --debug > ${File(filesDir, "server-stdout.log").absolutePath} 2>&1 < /dev/null\n"
        )
        val stdinFile = File(filesDir, "stdin.txt")
        if (!stdinFile.exists()) stdinFile.writeText("")
        launchScript.setExecutable(true, false)

        val pb = ProcessBuilder(launchScript.absolutePath)

        // Snapshot the config this server launches with: re-key / rename while
        // running must trigger a restart (configChanged), not a silent ignore.
        File(filesDir, ".launch-config").writeText("${apiKey()}\n${envName()}")

        val p = pb.start()
        proc = p
        lifecycle.set(State.RUNNING)
        // Review #18 F1 (Angus): an Upgrade/Restart intent that arrived during
        // STARTING saw proc == null — its destroy() was a no-op and the op sat
        // pending until the next natural exit (hours later). Re-check NOW that
        // the process exists, so the pending op takes effect immediately.
        if (pendingOp.get() != null && pendingOp.get() != PendingOp.SHUTDOWN) {
            log("pending operation present at launch — stopping server to drain it")
            try { p.destroy() } catch (_: Exception) {}
        }
        log("Process started, streaming output (via server-stdout.log)...")

        // Output goes to a file (valid fd for the child); tail it for status.
        // NOTE: tailer binds to THIS process (local val) — the old loop read
        // the shared `proc` var, so an upgrade's proc=null left the stale
        // tailer alive forever, double-appending once the new launch truncated
        // server-stdout.log past its offset (review task_93 #9).
        val outLogFile = java.io.File(filesDir, "server-stdout.log")

        // Cloud-connection watchdog: the server can be process-alive while its
        // cloud WebSocket is half-dead (killed socket, stale registration —
        // observed Sep 25: local traffic flowed, cloud said disconnected, app
        // unselectable as environment). Signal: server-stdout.log stops growing
        // while RUNNING. The letta server logs at least one lifecycle line per
        // ~30s (pong/recv) when healthy; silence past SILENCE_LIMIT = dead link.
        // Remedy: destroy the process; the worker's pending-RESTART loop
        // relaunches fresh and re-registers.
        val watchdog = Thread {
            val silenceLimitMs = 5 * 60_000L
            var quietMs = 0L
            while (p.isAlive && pendingOp.get() == null) {
                Thread.sleep(30_000)
                val lastWrite = outLogFile.lastModified()
                quietMs = if (lastWrite == 0L) quietMs + 30_000 else System.currentTimeMillis() - lastWrite
                if (quietMs >= silenceLimitMs) {
                    log("WATCHDOG: server silent ${quietMs / 1000}s while running — cloud link presumed dead; restarting")
                    setStatus("restarting — cloud connection silent")
                    pendingOp.compareAndSet(null, PendingOp.RESTART)
                    try { p.destroy() } catch (_: Exception) {}
                    return@Thread
                }
            }
        }
        watchdog.isDaemon = true
        watchdog.start()

        // Output goes to a file (valid fd for the child); tail it for status.
        // NOTE: tailer binds to THIS process (local val) — the old loop read
        // the shared `proc` var, so an upgrade's proc=null left the stale
        // tailer alive forever, double-appending once the new launch truncated
        // server-stdout.log past its offset (review task_93 #9).
        val tailer = Thread {
            var pos = 0L
            while (p.isAlive) {
                try {
                    if (outLogFile.length() > pos) {
                        java.io.RandomAccessFile(outLogFile, "r").use { raf ->
                            raf.seek(pos)
                            var line = raf.readLine()
                            while (line != null) {
                                log(line)
                                when {
                                    line.contains("Registering with") -> {
                                        setStatus("registering with Letta Cloud")
                                        updateNotification("Registering...")
                                    }
                                    line.contains("Registered successfully") -> {
                                        setStatus("registered with Letta Cloud")
                                        updateNotification("Registered — online")
                                    }
                                    line.contains("[Listen V2]") -> {
                                        setStatus("online — listener active")
                                        updateNotification("Online — ${envName()}")
                                    }
                                }
                                pos = raf.filePointer
                                line = raf.readLine()
                            }
                        }
                    }
                } catch (_: Exception) {}
                Thread.sleep(500)
            }
        }
        tailer.isDaemon = true
        tailer.start()

        val code = p.waitFor()
        log("letta server exited: $code")
        // Don't overwrite a pending-op status ("stopping for upgrade") with a
        // bare exit code the worker is about to supersede; label user-initiated
        // stops distinctly from crashes (review task_93 #5, partially).
        if (pendingOp.get() != null) {
            log("exit $code superseded by pending operation")
        } else if (code >= 128) {
            setStatus("crashed (signal ${code - 128})")
            updateNotification("Crashed — exit $code")
        } else {
            setStatus("exited: $code")
            updateNotification("Stopped (exit $code)")
        }
    }

    /** In-process mmap-exec matrix: can the ART runtime dlopen exec-mapped files? */
    private fun inProcessMapTests(libDir: File) {
        try {
            System.load(File(libDir, "libzerodep.so").absolutePath)
            log("DIAG sysload-libdir: OK (in-process dlopen of apk lib works)")
        } catch (t: Throwable) {
            log("DIAG sysload-libdir: FAIL ${t.message}")
        }
        try {
            val dataCopy = File(filesDir, "libzerodep-data.so")
            if (!dataCopy.exists()) {
                File(libDir, "libzerodep.so").inputStream().use { input ->
                    dataCopy.outputStream().use { input.copyTo(it) }
                }
            }
            System.load(dataCopy.absolutePath)
            log("DIAG sysload-appdata: OK (in-process dlopen of app_data works)")
        } catch (t: Throwable) {
            log("DIAG sysload-appdata: FAIL ${t.message}")
        }
    }

    /**
     * Staged verification + forensics:
     *   sig-sys / sig-segv  → verify Java exit-value encoding is 128+signal
     *   libdir-true/node    → loader chain sanity
     *   *-catch variants    → LD_PRELOAD SIGSYS catcher prints the trapped
     *                         syscall number (si_syscall) if death occurs
     *                         after preload constructors run
     */
    private fun runDiagnostics(libDir: File, rootfsDir: File) {
        val loader = File(libDir, "libldlnx.so").absolutePath
        val libPath = libDir.absolutePath + ":" + File(filesDir, "libs").absolutePath
        val sigsys = File(libDir, "libsigsys.so").absolutePath
        inProcessMapTests(libDir)
        // auxv dumper: what does the kernel tell this process about its CPU?
        try {
            val auxvBin = File(filesDir, "auxv")
            if (!auxvBin.exists()) {
                assets.open("diag-auxv").use { input -> auxvBin.outputStream().use { input.copyTo(it) } }
                auxvBin.setExecutable(true, false)
            }
            val pa = ProcessBuilder(auxvBin.absolutePath).redirectErrorStream(true).start()
            val ao = pa.inputStream.bufferedReader().readText()
            pa.waitFor()
            log("DIAG auxv: ${ao.replace("\n", " | ").take(400)}")
        } catch (e: Exception) { log("DIAG auxv: FAIL ${e.message}") }
        // strace-lite: ptrace the dying chain, capture every syscall nr
        try {
            val stl = File(filesDir, "stracelite")
            if (!stl.exists()) {
                assets.open("diag-stracelite").use { input -> stl.outputStream().use { input.copyTo(it) } }
                stl.setExecutable(true, false)
            }
            val rl = File(rootfsDir, "lib/aarch64-linux-gnu/ld-linux-aarch64.so.1").absolutePath
            val lp = File(rootfsDir, "lib/aarch64-linux-gnu").absolutePath + ":" + File(rootfsDir, "usr/lib/aarch64-linux-gnu").absolutePath
            val tb = File(rootfsDir, "usr/bin/true").absolutePath
            val lettaJsP = File(rootfsDir, "usr/local/lib/node_modules/@letta-ai/letta-code/letta.js").absolutePath
            val pb3 = ProcessBuilder(stl.absolutePath, File(libDir, "libldlnx.so").absolutePath, "--library-path", libDir.absolutePath + ":" + File(filesDir, "libs").absolutePath, File(libDir, "libnode.so").absolutePath, lettaJsP, "server", "--env-name", envName()).redirectErrorStream(true)
            pb3.environment()["NODE_OPTIONS"] = "--require " + File(filesDir, "fs-shim.js").absolutePath + " --require " + File(filesDir, "dns-shim.js").absolutePath
            pb3.environment()["LETTA_API_KEY"] = apiKey()
            pb3.environment()["HOME"] = File(rootfsDir, "root").absolutePath
            pb3.environment()["TMPDIR"] = File(rootfsDir, "tmp").absolutePath
            pb3.environment()["PATH"] = File(filesDir, "bin").absolutePath + ":/system/bin:/system/xbin"
            pb3.environment().clear()
            val p3 = pb3.start()
            val o3 = StringBuilder()
            val r3 = Thread { try { p3.inputStream.bufferedReader().forEachLine { o3.appendLine(it) } } catch (_: Exception) {} }
            r3.isDaemon = true; r3.start()
            val fin3 = p3.waitFor(15, TimeUnit.SECONDS)
            if (!fin3) { p3.destroyForcibly(); log("DIAG stracelite: TIMEOUT") }
            else {
                val f3 = o3.toString()
                File(filesDir, "diag-stracelite.txt").writeText(f3)
                log("DIAG stracelite: exit=${p3.exitValue()} lines=${f3.lines().size} last10='${f3.trim().lines().takeLast(10).joinToString(" | ")}'")
            }
        } catch (e: Exception) { log("DIAG stracelite: FAIL ${e.message}") }
        // env-clean glibc chain: does clearing the inherited Zygote env fix it?
        try {
            val pb2 = ProcessBuilder(File(rootfsDir, "lib/aarch64-linux-gnu/ld-linux-aarch64.so.1").absolutePath, "--library-path", File(rootfsDir, "lib/aarch64-linux-gnu").absolutePath + ":" + File(rootfsDir, "usr/lib/aarch64-linux-gnu").absolutePath, File(rootfsDir, "usr/bin/true").absolutePath).redirectErrorStream(true)
            pb2.environment().clear()
            pb2.environment()["LD_DEBUG"] = "all"
            val p2 = pb2.start()
            val o2 = StringBuilder()
            val r2 = Thread { try { p2.inputStream.bufferedReader().forEachLine { o2.appendLine(it) } } catch (_: Exception) {} }
            r2.isDaemon = true; r2.start()
            val fin = p2.waitFor(10, TimeUnit.SECONDS)
            if (!fin) { p2.destroyForcibly(); log("DIAG envclean-true: TIMEOUT") }
            else {
                val f2 = o2.toString()
                File(filesDir, "diag-envclean.txt").writeText(f2)
                log("DIAG envclean-true: exit=${p2.exitValue()} len=${f2.length} tail='${f2.trim().takeLast(150)}'")
            }
        } catch (e: Exception) { log("DIAG envclean-true: FAIL ${e.message}") }
        data class T(val name: String, val cmd: List<String>, val env: Map<String, String> = emptyMap())
        val tests = listOf(
            T("sig-sys", listOf("/system/bin/sh", "-c", "kill -SYS \$\$")),
            T("sig-segv", listOf("/system/bin/sh", "-c", "kill -SEGV \$\$")),
            T("exit159", listOf("/system/bin/sh", "-c", "exit 159")),
            T("libdir-true", listOf(loader, "--library-path", libPath, File(libDir, "libtrue.so").absolutePath)),
            T("libdir-true-catch", listOf(loader, "--library-path", libPath, File(libDir, "libtrue.so").absolutePath), mapOf("LD_PRELOAD" to sigsys)),
            T("libdir-true-debug", listOf(loader, "--library-path", libPath, File(libDir, "libtrue.so").absolutePath), mapOf("LD_DEBUG" to "all")),
            T("rootfs-orig-debug", listOf(File(rootfsDir, "lib/aarch64-linux-gnu/ld-linux-aarch64.so.1").absolutePath, "--library-path", File(rootfsDir, "lib/aarch64-linux-gnu").absolutePath + ":" + File(rootfsDir, "usr/lib/aarch64-linux-gnu").absolutePath, File(rootfsDir, "usr/bin/true").absolutePath), mapOf("LD_DEBUG" to "all")),
            T("libdir-node-catch", listOf(loader, "--library-path", libPath, File(libDir, "libnode.so").absolutePath, "--version"), mapOf("LD_PRELOAD" to sigsys))
        )
        for (t in tests) {
            try {
                val pb = ProcessBuilder(t.cmd).redirectErrorStream(true)
                pb.environment().putAll(t.env)
                val p = pb.start()
                val out = StringBuilder()
                val reader = Thread { try { p.inputStream.bufferedReader().forEachLine { out.appendLine(it) } } catch (_: Exception) {} }
                reader.isDaemon = true
                reader.start()
                val finished = p.waitFor(10, TimeUnit.SECONDS)
                if (!finished) {
                    p.destroyForcibly()
                    log("DIAG ${t.name}: TIMEOUT (killed)")
                    continue
                }
                val full = out.toString()
                if (t.name.endsWith("debug")) {
                    File(filesDir, "diag-" + t.name + ".txt").writeText(full)
                }
                log("DIAG ${t.name}: exit=${p.exitValue()} len=${full.length} tail='${full.trim().takeLast(200)}'")
            } catch (e: Exception) {
                log("DIAG ${t.name}: ${e.javaClass.simpleName}: ${e.message}")
            }
        }
    }

    /**
     * Runtime wiring that depends on the per-install libdir path:
     *  - files/bin/bash      → wrapper chaining into libdir Debian bash
     *  - native addons       → symlinks from node_modules into libdir
     *  - files/dns-shim.js   → copied from assets
     */
    private fun installSupportFiles(rootfsDir: File, libDir: File) {
        val filesLibs = File(filesDir, "libs")
        val libPath = libDir.absolutePath + ":" + filesLibs.absolutePath
        val loaderFlag = "${File(libDir, "libldlnx.so").absolutePath} --library-path $libPath"

        // original-name glibc libs: copied from rootfs (byte-patched, never patchelf'd).
        // The loader maps app_data files fine (proven); sonames resolve by original names.
        val libSources = listOf(
            File(rootfsDir, "lib/aarch64-linux-gnu") to listOf("libc.so.6", "libm.so.6", "libdl.so.2"),
            File(rootfsDir, "usr/lib/aarch64-linux-gnu") to listOf("libpthread.so.0", "libgcc_s.so.1", "libstdc++.so.6", "libtinfo.so.6", "libresolv.so.2")
        )
        filesLibs.mkdirs()
        for ((dir, names) in libSources) {
            for (n in names) {
                val dst = File(filesLibs, n)
                val src = File(dir, n)
                if (src.exists() && !dst.exists()) {
                    src.copyTo(dst)
                }
            }
        }
        // native addons into files/libs too (original names)
        val nmRoot0 = File(rootfsDir, "usr/local/lib/node_modules/@letta-ai/letta-code/node_modules")
        val addons = listOf(
            File(nmRoot0, "node-pty/build/Release/pty.node"),
            File(nmRoot0, "@img/sharp-linux-arm64/lib/sharp-linux-arm64.node"),
            File(nmRoot0, "@img/sharp-libvips-linux-arm64/lib/libvips-cpp.so.8.17.3")
        )
        for (a in addons) {
            val dst = File(filesLibs, a.name)
            if (a.exists() && !dst.exists()) a.copyTo(dst)
        }
        log("files/libs populated: ${filesLibs.listFiles()?.size} files")

        val shim = File(filesDir, "dns-shim.js")
        if (!shim.exists()) {
            assets.open("dns-shim.js").use { input ->
                shim.outputStream().use { output -> input.copyTo(output) }
            }
            log("dns-shim.js installed")
        }

        // fs-shim.js — replaces link()/linkSync() with O_EXCL-write emulation
        // (SELinux denies hard links on app storage; letta.js uses
        // write-temp-then-link for ALL its lock files). Runtime preload is
        // version-proof vs string-patching the bundle. Semantics verified:
        // 50-way concurrency, EEXIST/ENOENT, mode preservation, ESM+CJS
        // visibility (Sep 26).
        val fsShim = File(filesDir, "fs-shim.js")
        assets.open("fs-shim.js").use { input ->
            fsShim.outputStream().use { output -> input.copyTo(output) }
        }
        log("fs-shim.js installed")

        val binDir = File(filesDir, "bin")
        binDir.mkdirs()
        val bash = File(binDir, "bash")
        bash.writeText("#!/system/bin/sh\nexec $loaderFlag ${File(libDir, "libguestbash.so").absolutePath} \"$@\"\n")
        bash.setExecutable(true, false)
        log("bash wrapper installed")

        // `letta` wrapper: launcher for letta-code itself. Subagent spawning and
        // `letta` CLI re-invocations (LETTA_CODE_BIN) need a spawnable entry
        // point; process.execPath is the loader chain (cannot be re-spawned raw)
        // and letta.js is not directly executable on Android. Route through the
        // same loader chain the server itself runs under, with the same env the
        // launch script exports (self-contained: some spawn paths scrub env).
        val lettaJsPath = File(rootfsDir, "usr/local/lib/node_modules/@letta-ai/letta-code/letta.js").absolutePath
        val lettaBin = File(binDir, "letta")
        lettaBin.writeText(
            "#!/system/bin/sh\n" +
            "export HOME=\"\${HOME:-${File(rootfsDir, "root").absolutePath}}\"\n" +
            "export TMPDIR=\"\${TMPDIR:-${File(rootfsDir, "tmp").absolutePath}}\"\n" +
            "export PATH=${binDir.absolutePath}:/system/bin:/system/xbin\n" +
            "export NODE_OPTIONS=\"--require ${File(filesDir, "dns-shim.js").absolutePath}\"\n" +
            "export UV_USE_IO_URING=0\n" +
            "exec $loaderFlag ${File(libDir, "libnode.so").absolutePath} \"$lettaJsPath\" \"$@\"\n"
        )
        lettaBin.setExecutable(true, false)
        log("letta wrapper installed")

        // `node` wrapper (Sep 28): letta.js's memory git hooks (pre-commit
        // constraints validator, .cjs) invoke `node` from PATH. Spawned hook
        // processes have no node otherwise — process.execPath is the loader
        // chain and cannot be re-spawned raw. Same pattern as the letta
        // wrapper but exec'ing arbitrary JS args (node script.cjs ...).
        val nodeBin = File(binDir, "node")
        nodeBin.writeText(
            "#!/system/bin/sh\n" +
            "export HOME=\"\${HOME:-${File(rootfsDir, "root").absolutePath}}\"\n" +
            "export TMPDIR=\"\${TMPDIR:-${File(rootfsDir, "tmp").absolutePath}}\"\n" +
            "export PATH=${binDir.absolutePath}:/system/bin:/system/xbin\n" +
            "export UV_USE_IO_URING=0\n" +
            "exec $loaderFlag ${File(libDir, "libnode.so").absolutePath} \"$@\"\n"
        )
        nodeBin.setExecutable(true, false)
        log("node wrapper installed")

        // dx: agent-facing wrapper for running commands inside the proot
        // rootfs (apt, git, …) plus Android system tools via the /system
        // bind-mount (pm, am, settings). Source of truth: tools/on-device/dx.sh
        // — keep in sync. Always rewrite: the script embeds no absolute
        // per-install paths (DX_FILES auto-detects) but refresh keeps it
        // current when the asset ships updates.
        val dx = File(binDir, "dx")
        assets.open("dx.sh").use { input ->
            dx.outputStream().use { output -> input.copyTo(output) }
        }
        dx.setExecutable(true, false)
        log("dx wrapper installed")

        // git: letta-code 0.32.x requires a spawnable `git` on PATH for
        // git-based MemFS sync. The rootfs never shipped git, so every turn
        // died with "spawn git ENOENT". PRIMARY: static ELF chain (git-elf +
        // gc-shim + dnsport.so) — proot cannot exec from loader-chain
        // processes (interpreter ENOENT), so the old proot wrapper failed
        // for every memfs clone. FALLBACK: proot rootfs for contexts that
        // can run it. Source of truth: app/src/main/assets/git.sh — keep in
        // sync with the asset. Always rewrite, same policy as dx above.
        val git = File(binDir, "git")
        assets.open("git.sh").use { input ->
            git.outputStream().use { output -> input.copyTo(output) }
        }
        git.setExecutable(true, false)
        log("git wrapper installed")

        // git-elf + gc-shim + dnsport.so: static ELF git chain (Sep 28).
        // git-elf exec's the rootfs glibc loader with real git and
        // --preload dnsport.so (getaddrinfo override → local DNS forwarder).
        // gc-shim holds static ELF shims for git's dashed-dispatch child
        // spawns (git, git-remote-{http,https,ftp,ftps}) — raw glibc
        // git-core binaries ENOEXEC on Android (no interpreter). Sources:
        // tools/watch-git-shims/. Always rewrite (assets are immutable).
        val gitElf = File(binDir, "git-elf")
        assets.open("git-elf").use { input ->
            gitElf.outputStream().use { output -> input.copyTo(output) }
        }
        gitElf.setExecutable(true, false)
        val gcShimDir = File(filesDir, "gc-shim")
        gcShimDir.mkdirs()
        for (name in listOf("git", "git-remote-ftp", "git-remote-ftps", "git-remote-http", "git-remote-https")) {
            val shim = File(gcShimDir, name)
            assets.open("gc-shim/$name").use { input ->
                shim.outputStream().use { output -> input.copyTo(output) }
            }
            shim.setExecutable(true, false)
        }
        val dnsport = File(File(filesDir, "libs"), "dnsport.so")
        assets.open("dnsport.so").use { input ->
            dnsport.outputStream().use { output -> input.copyTo(output) }
        }
        val dnsForwarder = File(filesDir, "dns-forwarder.js")
        assets.open("dns-forwarder.js").use { input ->
            dnsForwarder.outputStream().use { output -> input.copyTo(output) }
        }
        log("git ELF chain installed (git-elf, gc-shim, dnsport.so, dns-forwarder.js)")

        // agentctl: agent-side client for the accessibility command channel
        // (tap/swipe/text/screen-tree) + am/pm/notify passthroughs. Always
        // rewrite — same policy as dx. Source: app/src/main/assets/agentctl.sh.
        val agentctl = File(binDir, "agentctl")
        assets.open("agentctl.sh").use { input ->
            agentctl.outputStream().use { output -> input.copyTo(output) }
        }
        agentctl.setExecutable(true, false)
        log("agentctl installed")

        // git payload tarballs staged to the filesDir ROOT under their exact
        // asset names — the wrapper looks for $FILES/git-arm64.tar.gz and
        // $FILES/proot-aarch64.tar.gz (offline first-run install; falls back
        // to the GitHub plugins-v1 release URLs when absent). proot is staged
        // too so the wrapper shares dx's plugin install without a download.
        // Large (31.5MB git payload): copy once per install, like rootfs.tar.
        // Partial-copy self-heal: rootfs uses a marker+verify pattern for the
        // same class of problem; here a size check is enough (assets are
        // immutable, so length mismatch = interrupted copy). Rewrite from the
        // asset on mismatch — no manual cleanup path needed.
        for (payloadName in listOf("git-arm64.tar.gz", "proot-aarch64.tar.gz")) {
            val payload = File(filesDir, payloadName)
            val assetSize = assets.open(payloadName).use { it.available().toLong() }
            if (!payload.exists() || payload.length() != assetSize) {
                if (payload.exists()) payload.delete()
                assets.open(payloadName).use { input ->
                    payload.outputStream().use { output -> input.copyTo(output, 1 shl 20) }
                }
                log("$payloadName staged to filesDir (${assetSize} bytes)")
            }
        }

        // ripgrep is statically linked (musl) — execve from app_data works as-is.

        // native addons: symlink into libdir (dlopen'd with PROT_EXEC)
        val nmRoot = File(rootfsDir, "usr/local/lib/node_modules/@letta-ai/letta-code/node_modules")
        val linkTargets = listOf(
            File(nmRoot, "node-pty/build/Release/pty.node"),
            File(nmRoot, "@img/sharp-linux-arm64/lib/sharp-linux-arm64.node"),
            File(nmRoot, "@img/sharp-libvips-linux-arm64/lib/libvips-cpp.so.8.17.3")
        )
        for (t in linkTargets) {
            try {
                val libCopy = File(File(filesDir, "libs"), t.name)
                if (libCopy.exists() && t.exists() && !Files.isSymbolicLink(t.toPath())) {
                    t.delete()
                    Files.createSymbolicLink(t.toPath(), libCopy.toPath())
                    log("linked ${t.name} -> libdir")
                }
            } catch (e: Exception) {
                log("WARN: link failed for ${t.name}: ${e.message}")
            }
        }
    }

    /**
     * Delete listener locks whose owning process is gone. The letta.js lock
     * (writeFileSync wx) assumes clean shutdown; Android kills the whole
     * process tree on force-stop/OOM/reboot without cleanup, and every
     * subsequent start then fails with "already running (pid N)". A lock is
     * stale when its pid is not running, or is running but is not one of our
     * libldlnx/letta processes (pid recycled to an unrelated app — cmdline
     * only reads back for same-uid processes, so unreadable also means not ours).
     */
    private fun sweepStaleListenerLocks(rootfsDir: File) {
        val listeners = File(rootfsDir, "root/.letta/listeners")
        val locks = listeners.listFiles { f -> f.name.endsWith(".lock") } ?: return
        for (lock in locks) {
            try {
                val text = lock.readText()
                val pid = Regex("\"pid\"\\s*:\\s*(\\d+)").find(text)?.groupValues?.get(1)
                if (pid.isNullOrEmpty()) {
                    log("Lock sweep: ${lock.name} has no pid — leaving it (manual review)")
                    continue
                }
                val procDir = File("/proc/$pid")
                val alive = procDir.isDirectory
                val ours = alive && runCatching {
                    val cmdline = File(procDir, "cmdline").readText()
                    cmdline.contains("libldlnx") || cmdline.contains("letta")
                }.getOrDefault(false)
                if (alive && ours) continue
                val reason = if (!alive) "pid $pid not running" else "pid $pid not our process"
                if (lock.delete()) log("Lock sweep: removed stale ${lock.name} ($reason)")
                else log("Lock sweep: FAILED to delete ${lock.name} ($reason)")
            } catch (e: Exception) {
                log("Lock sweep: error on ${lock.name}: ${e.message}")
            }
        }
    }

    private fun log(msg: String) {
        val ts = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        File(filesDir, "server.log").appendText("[$ts] $msg\n")
    }

    private fun setStatus(s: String) {
        File(filesDir, "status.txt").writeText("env=${envName()}\nstate=$s\n")
    }

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Environment Status",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Persistent notification shown while the Agents Beamdown environment is running on this device"
            setShowBadge(false)
        }
        nm.createNotificationChannel(channel)
        // Agent alerts (agentctl notify): heads-up, makes noise — this is the
        // channel the agent uses to reach the human from the environment.
        val alerts = NotificationChannel(
            "letta-env-agent-alerts",
            "Agent Alerts",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Messages the agent sends you from the environment (agentctl notify)"
        }
        nm.createNotificationChannel(alerts)
        // Remove the pre-v0.2.3 generic channel so it does not linger in system settings.
        nm.deleteNotificationChannel("letta-env")
    }

    /** Post an agent-originated alert (from agentctl notify via broadcast). */
    private fun postAgentAlert(title: String, text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        val n = android.app.Notification.Builder(this, "letta-env-agent-alerts")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(android.app.Notification.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(contentIntent())
            .build()
        nm.notify(7000 + (System.currentTimeMillis() % 1000).toInt(), n)
    }

    private fun contentIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val flags = if (Build.VERSION.SDK_INT >= 23) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        return PendingIntent.getActivity(this, 0, intent, flags)
    }

    private fun buildNotification(text: String): Notification {
        // Use the launcher icon as the large icon so the notification is recognisable.
        val largeIcon = try {
            BitmapFactory.decodeResource(resources, R.mipmap.ic_launcher)
        } catch (_: Exception) {
            null
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Agents Beamdown — ${envName()}")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setLargeIcon(largeIcon)
            .setOngoing(true)
            .setContentIntent(contentIntent())
            .setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, buildNotification(text))
    }

    override fun onDestroy() {
        // Review #18 F2/F3 (Angus): Stop must be terminal. Set SHUTDOWN FIRST
        // so the worker's drain loop cannot relaunch. onDestroy also runs for
        // install-kills and system shutdowns — the user-stop MARKER is only
        // written by ACTION_STOP_EXPLICIT (the Stop button), so those paths
        // don't masquerade as user stops.
        pendingOp.set(PendingOp.SHUTDOWN)
        lifecycle.set(State.STOPPING)
        if (lifecycle.get() != State.STOPPING) setStatus("stopped")
        try { proc?.destroy() } catch (_: Exception) {}
        worker?.interrupt() // advisory: waitFor is not interruptible
        super.onDestroy()
    }
}
