package com.angussoftware.letta.env

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.OutputStream
import java.net.ServerSocket
import android.net.LocalServerSocket
import android.net.LocalSocket
import java.util.concurrent.Executors
import java.util.concurrent.ExecutorService

/**
 * AgentAccessibilityService — turns the phone into an agent-operable device.
 *
 * Capabilities (all user-granted via Settings > Accessibility, no root):
 *  - tap / longPress at coordinates
 *  - swipe (arbitrary path)
 *  - text: type into the focused node (ACTION_SET_TEXT) or paste into fields
 *  - key: press hardware keys (BACK, HOME, ENTER, ...)
 *  - screenshot-text (a11y tree dump: visible text + clickable bounds)
 *  - tree: active window hierarchy (depth-limited JSON)
 *  - launch: start any activity (falls through to am start)
 *
 * Command channel: unix-domain socket at files/agentctl.sock (mode 600,
 * app-internal). Filesystem DAC confines it to our uid; peer credentials
 * verified as defense-in-depth. (The old TCP loopback listener's reflection
 * uid-check never worked on Android — structurally fail-open; fixed.)
 * Line protocol: one JSON request per line -> one JSON response per line.
 * The agent's shell (same uid) reaches it via files/bin/agentctl.
 */
class AgentAccessibilityService : AccessibilityService() {

    /** Consent classification for an agentctl command. Single source of truth —
     *  the gate, the commands index, and the access manifest all render from this. */
    enum class ConsentClass { UNGATED, GRANT_IS_CONSENT, SESSION_GATED, CLIENT_LOCAL }

    data class CommandSpec(
        val name: String,               // canonical wire name (what handle() dispatches)
        val clientAlias: String? = null,// agentctl-facing name when different (e.g. screen)
        val args: String,
        val does: String,
        val note: String = "",
        val consent: ConsentClass
    )

    companion object {
        @Volatile var instance: AgentAccessibilityService? = null
            private set

        /** Every socket command the service implements. order = display order.
         *  am/pm/keyevent never traverse the socket — they run in the agent
         *  shell (agentctl.sh passthroughs) but are SESSION_GATED: the
         *  agentctl client checks the live session state before running them
         *  (audit R7-2 — their named twins launch/key are gated; the
         *  passthroughs must not claim a weaker class than the capability
         *  they expose). They stay listed so the index is the complete
         *  honest surface. */
        val REGISTRY: List<CommandSpec> = listOf(
            CommandSpec("ping", args = "", does = "service handshake; returns uid", consent = ConsentClass.UNGATED),
            CommandSpec("commands", args = "", does = "this index (generated from the command registry — the same source the consent gate enforces)", consent = ConsentClass.UNGATED),
            CommandSpec("access", args = "", does = "complete runtime access manifest: filesystem surface (live-probed), consent model, all commands with consent classes, session semantics — the honest whole picture for a zero-context agent", consent = ConsentClass.UNGATED),
            CommandSpec("capabilities", args = "", does = "live device capability probe (what works on THIS device)", consent = ConsentClass.UNGATED),
            CommandSpec("status", args = "", does = "current session state (active, expiry, pending prompt)", consent = ConsentClass.UNGATED),
            CommandSpec("session", args = "\"desc\" [seconds]", does = "request user consent overlay — REQUIRED before any session-gated command; blocks up to 120s waiting for Approve/Deny", consent = ConsentClass.UNGATED),
            CommandSpec("tap", args = "x y", does = "tap at coordinates", consent = ConsentClass.SESSION_GATED),
            CommandSpec("longPress", args = "x y", does = "press-and-hold at coordinates", consent = ConsentClass.SESSION_GATED),
            CommandSpec("swipe", args = "fromX fromY toX toY [durationMs]", does = "stroke gesture", consent = ConsentClass.SESSION_GATED),
            CommandSpec("click", args = "\"label\"", does = "node-click by visible text/content-desc — primary input method", consent = ConsentClass.SESSION_GATED),
            CommandSpec("clickId", args = "view-id", does = "node-click by resource id", consent = ConsentClass.SESSION_GATED),
            CommandSpec("text", args = "\"value\"", does = "type into the focused input field", consent = ConsentClass.SESSION_GATED),
            CommandSpec("key", args = "keyCode", does = "press a key", note = "limited to back/home/recents; arbitrary keys need INJECT_EVENTS (usually denied)", consent = ConsentClass.SESSION_GATED),
            CommandSpec("back", args = "", does = "global action: back", note = "session-gated (audit R7-6): back can dismiss security/2FA dialogs", consent = ConsentClass.SESSION_GATED),
            CommandSpec("home", args = "", does = "global action: home", note = "session-gated (audit R7-6): navigation is device control", consent = ConsentClass.SESSION_GATED),
            CommandSpec("notifications", args = "", does = "global action: pull notification shade", note = "session-gated (audit R7-6): exposes notification content on screen", consent = ConsentClass.SESSION_GATED),
            CommandSpec("screenshot-text", clientAlias = "screen", args = "", does = "visible text elements + bounds + clickability (JSON)", consent = ConsentClass.SESSION_GATED),
            CommandSpec("tree", args = "[maxDepth]", does = "active window hierarchy (JSON)", consent = ConsentClass.SESSION_GATED),
            CommandSpec("launch", args = "uri", does = "open ACTION_VIEW intent", note = "works where shell am start is OEM-blocked", consent = ConsentClass.SESSION_GATED),
            CommandSpec("health", args = "status|hr|steps [hours]|sleep [days]", does = "health telemetry — permission-scoped (Health Connect grant IS consent)", note = "hr reads the newest synced sample (may lag the watch by minutes); status shows grant state before first use", consent = ConsentClass.GRANT_IS_CONSENT),
            CommandSpec("hcrequest", args = "", does = "launch the in-app Health Connect permission request screen", consent = ConsentClass.SESSION_GATED),
            CommandSpec("location", args = "", does = "last-known fix {lat,lon,accuracyM,ageSec} or one current fetch", note = "permission-scoped — location grant IS consent", consent = ConsentClass.GRANT_IS_CONSENT),
            CommandSpec("notifstatus", args = "", does = "notification-listener grant state + snapshot count (no content)", consent = ConsentClass.UNGATED),
            CommandSpec("notiflist", args = "", does = "active notifications (pkg/title/text)", note = "message content is privacy-peer to screen", consent = ConsentClass.SESSION_GATED),
            CommandSpec("logtail", args = "[bytes]", does = "last N bytes (default 4096, max 16384) of the app's own server.log", note = "NOT session-gated — the app's own lifecycle lines, no user content", consent = ConsentClass.UNGATED),
            // client-local passthroughs (agent shell, not the gated socket):
            CommandSpec("am", args = "<args>", does = "shell am passthrough (client-local)", note = "session-gated client-side (audit R7-2: same capability as launch); agentctl checks session state before running; OEM may block targets", consent = ConsentClass.SESSION_GATED),
            CommandSpec("pm", args = "<args>", does = "shell pm passthrough (client-local)", note = "session-gated client-side (audit R7-2); agentctl checks session state before running", consent = ConsentClass.SESSION_GATED),
            CommandSpec("keyevent", args = "keyCode", does = "shell input keyevent (client-local)", note = "session-gated client-side (audit R7-2: same capability as key); raw key injection, often denied by OEM — prefer back/home", consent = ConsentClass.SESSION_GATED),
            CommandSpec("notify", args = "\"title\" \"text\"", does = "post the app's own notification (client-local broadcast)", note = "always allowed — the app's own channel", consent = ConsentClass.CLIENT_LOCAL),
        )
    }

    @Volatile private var pool: ExecutorService = Executors.newCachedThreadPool()
    private val socketLifecycle = SocketLifecycle(AndroidSocketBinder()) { log(it) }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        startSocket()
        log("a11y service connected; agentctl unix socket starting")
    }

    override fun onDestroy() {
        instance = null
        socketLifecycle.shutdown(java.io.File(filesDir, "agentctl.sock"))
        pool.shutdownNow()
        pool = Executors.newCachedThreadPool() // fresh pool for a restart
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) { /* passive */ }
    override fun onInterrupt() { /* nothing */ }

    override fun onKeyEvent(event: KeyEvent?): Boolean = false

    // ---- command socket ----------------------------------------------------------

    private fun startSocket() {
        // Unix-domain socket in the app's private files dir, mode 600:
        // filesystem DAC confines connections to our own uid (other apps
        // cannot even reach the socket inode — connect needs write perm).
        // Peer credentials are verified as defense-in-depth below.
        // Replaces the old TCP loopback listener, whose "same-uid" reflection
        // check could never read a peer uid on Android (no such field on
        // java.net.Socket) — the gate was structurally fail-open.
        //
        // Oct 7 v0.4.27: lifecycle moved to SocketLifecycle — single-owner
        // bind, adopt-if-live, unlink-orphan-recovery. Fixes the zombie-inode
        // race where concurrent onServiceConnected binds left a bound-but-
        // unnamed socket and agentctl died with ENOENT until reinstall.
        pool.execute {
            socketLifecycle.bindOrTakeOver<LocalServerSocket>(
                java.io.File(filesDir, "agentctl.sock"),
                onBound = { ss -> serveAcceptLoop(ss) },
                isAlive = { instance != null },
            )
        }
    }

    /** Accept loop for a bound server. Runs on the binding worker thread; exits on close. */
    private fun serveAcceptLoop(ss: LocalServerSocket) {
        while (instance != null) {
            val client = try { ss.accept() } catch (_: Exception) { break }
            // Defense-in-depth: verify peer uid even though the 600
            // socket file already blocks other apps.
            if (!client.peerUidMatches()) {
                runCatching { client.close() }; continue
            }
            pool.execute { serveLocal(client) }
        }
    }

    private fun serveLocal(client: LocalSocket) {
        try {
            val reader = client.inputStream.bufferedReader()
            val out: java.io.OutputStream = client.outputStream
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isBlank()) continue
                val resp = runCatching { execute(org.json.JSONObject(line)) }
                    .recover { org.json.JSONObject().put("ok", false).put("error", it.message ?: it.javaClass.simpleName) }
                    .getOrThrow()
                out.write((resp.toString() + "\n").toByteArray())
                out.flush()
            }
        } catch (_: Exception) { /* client gone */ } finally {
            runCatching { client.close() }
        }
    }

    private fun serve(client: java.net.Socket) {
        try {
            val reader = client.getInputStream().bufferedReader()
            val out: OutputStream = client.getOutputStream()
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isBlank()) continue
                val resp = runCatching { execute(JSONObject(line)) }
                    .recover { JSONObject().put("ok", false).put("error", it.message ?: it.javaClass.simpleName) }
                    .getOrThrow()
                out.write((resp.toString() + "\n").toByteArray())
                out.flush()
            }
        } catch (_: Exception) { /* client gone */ } finally {
            runCatching { client.close() }
        }
    }

    /** Execute one command; returns JSON response. Runs on the service's main thread
     *  via pool -> handler hop for a11y calls (dispatchGesture needs main looper? no —
     *  it needs the service instance; sync is on the callback). Keep it simple: all
     *  a11y calls post to mainHandler and block the socket thread via countdown latch. */
    private fun execute(req: JSONObject): JSONObject {
        val cmd = req.optString("cmd")
        // Session requests block on the USER, not the main thread — bypass the
        // 15s main-handler latch and run the wait on the socket thread.
        // Telemetry commands (health/location/notiflist-status) touch no a11y
        // APIs and make suspend-adjacent IPC calls — socket thread too.
        if (cmd == "session") return requestConsent(req)
        if (cmd.startsWith("health") || cmd == "location" || cmd == "notifstatus") {
            return runCatching { handle(cmd, req) }
                .recover { JSONObject().put("ok", false).put("error", it.message ?: it.javaClass.simpleName) }
                .getOrThrow()
        }
        // Gesture commands (ANR fix): dispatchGesture posts to the a11y
        // pipeline itself and its result callback arrives via binder — the
        // socket thread can await the latch directly. Running them on main
        // would block main on the very callbacks main delivers.
        if (cmd == "tap" || cmd == "longPress" || cmd == "swipe") {
            return runCatching { handle(cmd, req) }
                .recover { JSONObject().put("ok", false).put("error", it.message ?: it.javaClass.simpleName) }
                .getOrThrow()
        }
        val latch = java.util.concurrent.CountDownLatch(1)
        var result = JSONObject().put("ok", false).put("error", "no handler")
        val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
        mainHandler.post {
            result = runCatching { handle(cmd, req) }
                .recover { JSONObject().put("ok", false).put("error", it.message ?: it.javaClass.simpleName) }
                .getOrThrow()
            latch.countDown()
        }
        latch.await(15, java.util.concurrent.TimeUnit.SECONDS)
        return result
    }

    // ---- consent-gated automation ---------------------------------------------------
    //
    // Every privacy-bearing command (reading the screen, clicking, typing,
    // launching apps) is HARD-GATED on an approved session. The agent requests
    // one with `session`; the user sees a full-screen overlay and Approve/Deny.
    // No approval -> the gate refuses. Approval expires after its duration.
    // This is deterministic enforcement in the service, not a prompt guideline.

    private class Consent(val desc: String, val agent: String, val until: Long)

    @Volatile private var consent: Consent? = null
    @Volatile private var consentPromptShowing = false
    /** Single-flight token for the consent prompt (ANR/race fix, Uncia audit
     *  2026-10-22 entry 2). CAS on entry; cleared ONLY after the overlay is
     *  dismissed, so a replacement prompt can never interleave with the old
     *  one's teardown. One prompt on screen, ever, by construction. */
    private val consentGate = java.util.concurrent.atomic.AtomicBoolean(false)

    /** Returns null when allowed; otherwise the refusal JSON to send. */
    private fun gate(): JSONObject? {
        val c = consent
        if (c == null || System.currentTimeMillis() > c.until) {
            consent = null
            return err()
                .put("error", "consent required — no approved session. " +
                    "Request one: agentctl session \"<plain-language description>\" [seconds]")
                .put("consentRequired", true)
        }
        return null
    }

    /** Called on the SOCKET thread. Shows the overlay, waits for the user. */
    private fun requestConsent(req: JSONObject): JSONObject {
        // Single-flight by construction: atomic claim replaces the old
        // check-then-act on @Volatile (two socket threads could both pass it,
        // and the second showConsentOverlay's dismiss-first orphaned the first
        // request's latch with mismatched intent text on screen).
        if (!consentGate.compareAndSet(false, true)) {
            return err().put("error", "a consent prompt is already showing")
        }
        val desc = req.optString("desc", "unspecified agent action")
        val agent = req.optString("agent", "an agent")
        val seconds = req.optInt("seconds", 300).coerceIn(30, 3600)
        val decided = java.util.concurrent.CountDownLatch(1)
        var approved = false
        consentPromptShowing = true
        val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
        mainHandler.post {
            showConsentOverlay(agent, desc, seconds,
                onApprove = {
                    consent = Consent(desc, agent, System.currentTimeMillis() + seconds * 1000L)
                    approved = true; decided.countDown()
                },
                onDeny = { approved = false; decided.countDown() })
        }
        val userAnswered = decided.await(120, java.util.concurrent.TimeUnit.SECONDS)
        // Ordering fix: dismiss the overlay BEFORE releasing the gate. The old
        // order (flag cleared, then dismiss posted) left a window where a new
        // request claimed the flag, showed its overlay (which dismiss-firsts
        // the old one), and the old request's late dismiss then killed the NEW
        // prompt. With gate-release after dismissal, teardown completes first.
        val dismissed = java.util.concurrent.CountDownLatch(1)
        mainHandler.post {
            dismissConsentOverlay() // no-op if already dismissed
            dismissed.countDown()
        }
        dismissed.await(5, java.util.concurrent.TimeUnit.SECONDS)
        consentPromptShowing = false // legacy mirror (specs/help read it)
        consentGate.set(false)
        return when {
            !userAnswered -> err().put("error", "no response within 120s — treated as deny")
            approved -> ok().put("session", desc).put("agent", agent)
                .put("expiresAt", consent!!.until)
                .put("seconds", seconds)
            else -> err().put("error", "denied by user")
        }
    }

    private var consentView: android.view.View? = null

    private fun showConsentOverlay(
        agent: String, desc: String, seconds: Int,
        onApprove: () -> Unit, onDeny: () -> Unit
    ) {
        dismissConsentOverlay()
        val dp = { v: Int -> (v * resources.displayMetrics.density).toInt() }
        // Angus Software Theming tokens (dark set — the overlay scrim is always
        // dark, so the ui mode doesn't apply here).
        val p = EnvPalette.forMode(night = true)
        // Round-screen safety (Galaxy Watch 7, 320dp circle): the inscribed
        // safe rect is ~226dp tall — the full stack (title/agent/desc/duration/
        // buttons + paddings) exceeds it, which clipped Approve off-screen and
        // left nothing scrollable (live-reported Oct 6: "cannot scroll down the
        // request to be able to accept it"). Fixes, in order of effect:
        // 1. ScrollView wrapper — content always reachable regardless of shape.
        // 2. Round-insets: on round screens inset horizontally by ~17% of width
        //    so text at the equator doesn't bleed into the bezel.
        // 3. Tighter paddings + smaller type on watch-sized screens.
        val isRound = resources.configuration.isScreenRound
        // sw<340 ≈ watch-class only (Galaxy Watch 7 = 320dp); 360-392dp phone
        // cover/main displays keep the original phone scale (review F2).
        val small = isRound || resources.configuration.smallestScreenWidthDp < 340
        val root = android.widget.FrameLayout(this).apply {
            setBackgroundColor(p.bg.withAlpha(0xF0))
        }
        val hPad = if (isRound) dp(30) else dp(28)
        val vPad = if (isRound) dp(8) else dp(40)
        val content = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(hPad, vPad, hPad, vPad)
            gravity = android.view.Gravity.CENTER
        }
        val scroller = android.widget.ScrollView(this).apply {
            isVerticalScrollBarEnabled = true
            // Review F1: no child CENTER gravity (ScrollView clamps scroll to
            // [0, child.bottom - viewport]; a centered-taller child starts at
            // negative top → top overflow permanently unreachable). Content's
            // own CENTER gravity centers when short; fillViewport centers the
            // viewport background when content is short without clipping.
            setFillViewport(true)
            addView(content, android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT))
        }
        root.addView(scroller, android.widget.FrameLayout.LayoutParams(
            android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
            android.widget.FrameLayout.LayoutParams.MATCH_PARENT))
        val title = android.widget.TextView(this).apply {
            text = "Agent requests phone access"
            textSize = if (small) 17f else 22f
            setTextColor(p.textPrimary)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        val who = android.widget.TextView(this).apply {
            text = agent
            textSize = if (small) 13f else 16f
            setTextColor(p.accent)
            setPadding(0, dp(6), 0, 0)
        }
        val body = android.widget.TextView(this).apply {
            text = "“$desc”"
            textSize = if (small) 14f else 18f
            setTextColor(p.textPrimary)
            setPadding(0, dp(10), 0, dp(6))
        }
        val dur = android.widget.TextView(this).apply {
            text = "Duration: ${seconds}s — then access revokes automatically. " +
                "Approving grants FULL device control for that time: tap, type, " +
                "launch apps, read the screen. The description above is the " +
                "agent's stated intent — approval is NOT limited to it."
            textSize = if (small) 11f else 14f
            setTextColor(p.textSecondary)
            setPadding(0, 0, 0, dp(14))
        }
        val buttons = android.widget.LinearLayout(this).apply {
            // Round screens (Harry, Oct 6 live feedback): two side-by-side
            // buttons at the circle's narrow bottom cap are cramped — stack
            // vertically so each gets full inset width.
            orientation = if (small)
                android.widget.LinearLayout.VERTICAL
            else android.widget.LinearLayout.HORIZONTAL
        }
        val deny = android.widget.Button(this).apply {
            text = "Deny"
            setOnClickListener { dismissConsentOverlay(); onDeny() }
        }
        val approve = android.widget.Button(this).apply {
            text = "Approve"
            setOnClickListener { dismissConsentOverlay(); onApprove() }
        }
        // HORIZONTAL: width=0 + weight splits the row. VERTICAL (small/round):
        // full-width buttons stacked — each gets the full content width in the
        // narrow bottom cap of the circle.
        if (small) {
            buttons.addView(deny, android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT))
            buttons.addView(approve, android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(6) }) // review nit: inter-button breathing
        } else {
            buttons.addView(deny, android.widget.LinearLayout.LayoutParams(0,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            buttons.addView(approve, android.widget.LinearLayout.LayoutParams(0,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
        // Buttons attach to the scrollable content — always reachable via
        // scroll on round screens, visible outright on rectangular phones.
        content.addView(title); content.addView(who); content.addView(body); content.addView(dur); content.addView(buttons)
        val wm = getSystemService(WINDOW_SERVICE) as android.view.WindowManager
        val lp = android.view.WindowManager.LayoutParams(
            android.view.WindowManager.LayoutParams.MATCH_PARENT,
            android.view.WindowManager.LayoutParams.MATCH_PARENT,
            android.view.WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                or android.view.WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            android.graphics.PixelFormat.TRANSLUCENT)
        runCatching { wm.addView(root, lp) }
            .onFailure { onDeny() } // can't show -> fail closed
        consentView = root
    }

    private fun dismissConsentOverlay() {
        consentView?.let { v ->
            runCatching { (getSystemService(WINDOW_SERVICE) as android.view.WindowManager).removeView(v) }
        }
        consentView = null
    }


    private fun handle(cmd: String, req: JSONObject): JSONObject {
        // Deterministic consent gate, driven by the command REGISTRY — the
        // same table that generates the commands index and the access
        // manifest. Only SESSION_GATED commands require an approved session
        // (see requestConsent). Docs cannot drift from enforcement: they are
        // both projections of this table.
        val spec = REGISTRY.firstOrNull { it.name == cmd || it.clientAlias == cmd }
        if (spec == null) return err().put("error", "unknown cmd: $cmd")
        if (spec.consent == ConsentClass.SESSION_GATED) gate()?.let { return it }
        // While a consent prompt is on screen, session-gated actions are
        // refused entirely: an agent holding an ACTIVE session must not be
        // able to keep acting (or tap its own renewal overlay's Approve on
        // OEMs where injected gestures work) while the user is deciding.
        if (spec.consent == ConsentClass.SESSION_GATED && consentPromptShowing)
            return err().put("error", "a consent prompt is showing — session actions are paused until the user decides")

        return when (cmd) {
            "ping" -> ok().put("service", "a11y").put("uid", android.os.Process.myUid())
            "commands" -> commandsIndex()
            "access" -> accessManifest()
            "capabilities" -> capabilities()
            "status" -> ok()
                .put("sessionActive", consent != null && System.currentTimeMillis() <= consent!!.until)
                .put("sessionDesc", consent?.desc)
                .put("sessionExpiresAt", consent?.until)
                .put("consentPromptShowing", consentPromptShowing)
            // telemetry: permission-scoped reads (spec §4 — grant IS consent)
            "health" -> when (req.optString("sub")) {
                "status" -> ok().put("health", HealthReader.status(applicationContext))
                "hr" -> HealthReader.heartRate(applicationContext)
                "steps" -> HealthReader.steps(applicationContext, req.optLong("hours", 24L))
                "sleep" -> HealthReader.sleep(applicationContext, req.optLong("days", 2L))
                else -> err().put("error", "usage: health status|hr|steps|sleep")
            }
            "location" -> LocationReader.read(applicationContext)
            "hcrequest" -> {
                // Start the HC permission request from the app process (the
                // a11y service IS the app uid — only in-process starts of the
                // non-exported activity pass the uid check; shell/am cannot).
                runCatching {
                    val i = android.content.Intent(this, MainActivity::class.java)
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                        .putExtra("AUTO_HC_REQUEST", true)
                    startActivity(i)
                    ok().put("launched", true)
                }.fold({ it }, { err().put("error", "launch failed: ${it.message}") })
            }
            "notifstatus" -> ok()
                .put("granted", AgentNotificationListener.granted(applicationContext))
                .put("snapshotCount", AgentNotificationListener.current().optInt("count", 0))
            "notiflist" -> AgentNotificationListener.current()
            // App's OWN diagnostic log (lifecycle lines the app writes about
            // itself — no user content): the a11y service shares the app
            // process, so this reads filesDir/server.log where release-build
            // run-as and a11y text caps cannot reach. Ungated by design.
            "logtail" -> logTail(req.optInt("bytes", 4096))
        "tap" -> gestureTap(req.getDouble("x"), req.getDouble("y"), req.optDouble("duration", 50.0))
        "longPress" -> gestureTap(req.getDouble("x"), req.getDouble("y"), 800.0)
        "swipe" -> gestureSwipe(
            req.getDouble("fromX"), req.getDouble("fromY"),
            req.getDouble("toX"), req.getDouble("toY"),
            req.optDouble("duration", 300.0))
        "text" -> setText(req.getString("value"))
        "key" -> pressKey(req.optInt("code", KeyEvent.KEYCODE_BACK))
        "back" -> pressKey(KeyEvent.KEYCODE_BACK)
        "home" -> pressKey(KeyEvent.KEYCODE_HOME)
        "notifications" -> performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS).let { ok().put("performed", it) }
        "screenshot-text" -> visibleText()
        "tree" -> activeTree(req.optInt("maxDepth", 18))
        "click" -> nodeClick(req.getString("text"))
        "clickId" -> nodeClickId(req.getString("id"))
        // am/pm/keyevent are gated client-side (agentctl checks session
        // state before exec). If one arrives over the socket anyway, say so
        // honestly instead of "unknown cmd" — the capability exists, this
        // door just doesn't serve it.
        "am", "pm", "keyevent" -> err()
            .put("error", "$cmd is a client-side passthrough — run it via the agentctl client (which applies the session gate), not the socket")
        "launch" -> {
            // Handled client-side by agentctl (am start) in most cases; kept for URI intents
            val uri = req.optString("uri")
            val i = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(uri))
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { startActivity(i) }
                .fold({ ok() }, { err().put("error", it.message) })
        }
        else -> err().put("error", "unknown cmd: $cmd") // unreachable: REGISTRY lookup gates dispatch
        }
    }

    private fun ok() = JSONObject().put("ok", true)
    private fun err() = JSONObject().put("ok", false)

    // ---- gestures ------------------------------------------------------------------

    private fun gestureTap(x: Double, y: Double, durationMs: Double): JSONObject {
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        return dispatchAwait(path, durationMs, "tap($x,$y)")
    }

    private fun gestureSwipe(fx: Double, fy: Double, tx: Double, ty: Double, durationMs: Double): JSONObject {
        val path = Path().apply {
            moveTo(fx.toFloat(), fy.toFloat())
            lineTo(tx.toFloat(), ty.toFloat())
        }
        return dispatchAwait(path, durationMs, "swipe($fx,$fy -> $tx,$ty)")
    }

    /**
     * ANR fix (Uncia audit 2026-10-22 entry 1): the old dispatch() awaited its
     * own 10s gesture latch while RUNNING ON MAIN — a missed/cancelled
     * callback (OEM quirks; see gesturesBlocked history) wedged main for the
     * full timeout, serially per command = input-event ANR territory.
     * Restructure: dispatchGesture + callback bookkeeping happen wherever the
     * caller runs, but the LATCH WAIT sits here and execute() now routes
     * gesture commands to the socket thread (no main-handler hop) — main is
     * never blocked on a gesture.
     */
    private fun dispatchAwait(path: Path, durationMs: Double, label: String): JSONObject {
        if (gesturesBlocked) return err()
            .put("error", "gestures blocked on this device (learned); use click/clickId")
        val done = java.util.concurrent.CountDownLatch(1)
        var okFlag = false
        val gd = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs.toLong()))
            .build()
        val dispatched = dispatchGesture(gd, object : GestureResultCallback() {
            override fun onCompleted(g: GestureDescription?) { okFlag = true; done.countDown() }
            override fun onCancelled(g: GestureDescription?) {
                okFlag = false; done.countDown()
                gesturesBlocked = true // learned: this OEM cancels injected gestures
            }
        }, null)
        if (!dispatched) return err().put("error", "dispatch failed (service not connected?)")
        done.await(10, java.util.concurrent.TimeUnit.SECONDS)
        return if (okFlag) ok().put("gesture", label) else err().put("error", "gesture cancelled")
    }

    // ---- input ----------------------------------------------------------------------

    private fun pressKey(code: Int): JSONObject {
        // a11y services inject keys via dispatchGesture? No — key injection for a11y
        // is not directly exposed; use GLOBAL_ACTION_BACK for back, else fall back to
        // shell input keyevent via nohup (agentctl handles that). Here: back/home/recents.
        val ga = when (code) {
            KeyEvent.KEYCODE_BACK -> GLOBAL_ACTION_BACK
            KeyEvent.KEYCODE_HOME -> GLOBAL_ACTION_HOME
            KeyEvent.KEYCODE_APP_SWITCH -> GLOBAL_ACTION_RECENTS
            else -> -1
        }
        if (ga == -1) return err().put("error", "use agentctl keyevent for arbitrary keys")
        return ok().put("performed", performGlobalAction(ga))
    }

    private fun setText(value: String): JSONObject {
        val focus = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?: return err().put("error", "no focused input node (tap a field first)")
        val args = android.os.Bundle().apply { putCharSequence(
            AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value) }
        val okB = focus.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        return if (okB) ok() else err().put("error", "SET_TEXT denied by node")
    }

    // ---- screen reading --------------------------------------------------------------

    /** Tail of the app's own server.log — diagnostic self-read, ungated.
     *  Mirrors visibleText's JSON shape: {"ok":true,"tail":"<last N bytes>"}. */
    private fun logTail(bytes: Int): JSONObject {
        val n = bytes.coerceIn(1, 16384)
        val f = File(applicationContext.filesDir, "server.log")
        if (!f.exists()) return ok().put("tail", "")
        return runCatching { RandomAccessTail.tail(f, n, maxChars = n) }
            .fold({ tail -> ok().put("tail", tail) },
                { e -> err().put("error", e.message ?: e.javaClass.simpleName) })
    }


    private fun visibleText(): JSONObject {
        val root = rootInActiveWindow ?: return err().put("error", "no active window")
        val items = JSONArray()
        val seen = HashSet<String>()
        walkNodes(root) { node ->
            val txt = node.text?.toString()?.trim()
            val desc = node.contentDescription?.toString()?.trim()
            val label = txt?.takeIf { it.isNotEmpty() } ?: desc?.takeIf { it.isNotEmpty() } ?: return@walkNodes
            val r = Rect(); node.getBoundsInScreen(r)
            val key = "$label@${r.left},${r.top}"
            if (seen.add(key)) {
                items.put(JSONObject()
                    .put("text", label.take(200))
                    .put("bounds", JSONArray(listOf(r.left, r.top, r.right, r.bottom)))
                    .put("clickable", node.isClickable))
            }
        }
        return ok().put("window", pkgOf(root)).put("count", items.length()).put("items", items)
    }

    private fun activeTree(maxDepth: Int): JSONObject {
        val root = rootInActiveWindow ?: return err().put("error", "no active window")
        return ok().put("window", pkgOf(root)).put("tree", nodeJson(root, 0, maxDepth))
    }

    private fun nodeJson(n: AccessibilityNodeInfo, depth: Int, maxDepth: Int): JSONObject {
        val o = JSONObject()
        val r = Rect(); n.getBoundsInScreen(r)
        o.put("cls", n.className?.toString()?.substringAfterLast('.') ?: "?")
        o.put("b", JSONArray(listOf(r.left, r.top, r.right, r.bottom)))
        n.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { o.put("text", it.take(120)) }
        n.contentDescription?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { o.put("desc", it.take(120)) }
        if (n.isClickable) o.put("click", true)
        if (n.isScrollable) o.put("scroll", true)
        if (n.isEditable) o.put("edit", true)
        if (depth < maxDepth) {
            val kids = JSONArray()
            for (i in 0 until n.childCount) {
                n.getChild(i)?.let { kids.put(nodeJson(it, depth + 1, maxDepth)) }
            }
            if (kids.length() > 0) o.put("children", kids)
        }
        return o
    }

    private fun walkNodes(n: AccessibilityNodeInfo, fn: (AccessibilityNodeInfo) -> Unit) {
        fn(n)
        for (i in 0 until n.childCount) n.getChild(i)?.let { walkNodes(it, fn) }
    }

    private fun pkgOf(root: AccessibilityNodeInfo): String =
        root.packageName?.toString() ?: "?"

    /**
     * Self-describing command index — the registry of every command this
     * service supports, WITH per-device viability learned at runtime. This is
     * the source of truth `agentctl help` defers to; docs can never drift
     * from implementation because the implementation is the doc.
     */
    private fun commandsIndex(): JSONObject {
        val cmds = JSONArray()
        // Rendered from the REGISTRY — single source of truth shared with the
        // consent gate, so the index cannot drift from enforcement.
        for (s in REGISTRY) {
            val gestureNote = if (s.name in setOf("tap", "longPress", "swipe") && gesturesBlocked)
                "BLOCKED: injected gestures cancelled by this OEM build" else s.note
            cmds.put(JSONObject()
                .put("cmd", s.clientAlias ?: s.name)
                .put("wire", s.name)
                .put("args", s.args)
                .put("does", s.does)
                .put("note", gestureNote)
                .put("consent", when (s.consent) {
                    ConsentClass.UNGATED -> "ungated"
                    ConsentClass.GRANT_IS_CONSENT -> "grant-is-consent"
                    ConsentClass.SESSION_GATED -> "session-gated"
                    ConsentClass.CLIENT_LOCAL -> "client-local"
                }))
        }
        return ok().put("commands", cmds)
            .put("gesturesBlocked", gesturesBlocked)
            .put("consentGate", true)
            .put("note", "consent classes: session-gated commands require an approved session (agentctl session requests one via full-screen overlay); grant-is-consent commands run under their scoped permission grant (health/location); ungated = introspection/navigation/app-own-diagnostics; client-local = runs in the agent shell, never crosses the gated socket. gesturesBlocked is learned: first cancelled gesture flips it.")
    }

    /**
     * Set on first onCancelled from a real (non-quick) gesture. One UI 7
     * cancels injected gestures even hands-off; instead of guessing, the
     * service learns it from the dispatcher itself.
     */
    @Volatile private var gesturesBlocked = false

    /**
     * The complete runtime access manifest — the honest whole picture for a
     * zero-context agent landing on this device. Everything here is MEASURED
     * (live probes) or GENERATED (from the same REGISTRY the consent gate
     * enforces), never hand-maintained prose that can drift from reality.
     */
    private fun accessManifest(): JSONObject {
        val m = JSONObject()
        m.put("manifestVersion", 1)
        m.put("device", JSONObject()
            .put("envName", getSharedPreferences("letta_env", MODE_PRIVATE)
                .getString("env_name", "android") ?: "android")
            .put("app", "Agents Beamdown (com.angussoftware.letta.env)")
            .put("deviceClass", if (resources.configuration.isScreenRound) "watch" else "phone")
            .put("uid", android.os.Process.myUid())
            .put("root", false))

        // ---- filesystem: live probes, self-cleaning ----
        fun probeFs(path: File, write: Boolean): JSONObject {
            val r = JSONObject().put("path", path.absolutePath)
            r.put("read", runCatching { path.canRead() }.getOrDefault(false))
            r.put("write", if (write) runCatching {
                // delete-before-probe: a stale marker from a crash or
                // concurrent call must not read as write:false forever
                val t = File(path, ".agentctl-probe")
                t.delete()
                val okW = t.createNewFile(); if (okW) t.delete(); okW
            }.getOrDefault(false) else JSONObject.NULL)
            return r
        }
        m.put("filesystem", JSONArray()
            .put(probeFs(File("/sdcard"), write = true))          // shared storage (legacy-storage targetSdk 28)
            .put(probeFs(filesDir.parentFile ?: filesDir, write = true)) // app-private sandbox
            .put(probeFs(File("/system"), write = false)))         // read-only system partition

        // ---- consent model ----
        m.put("consentModel", JSONObject()
            .put("sessionTtlSeconds", 300)
            .put("promptTimeoutSeconds", 120)
            .put("timeoutBehavior", "deny (fail-closed)")
            .put("classes", JSONObject()
                .put("ungated", "introspection, app-own diagnostics — no consent needed")
                .put("grant-is-consent", "scoped telemetry (health/location) — the Android permission grant IS the consent")
                .put("session-gated", "all UI-level device control — reading, tapping, typing, launching, navigation (back/home/notifications), am/pm/keyevent passthroughs — requires an approved consent overlay session")
                .put("client-local", "runs in the agent shell (never crosses the gated socket); session-gated client-local commands are checked by the agentctl client before running")))

        // ---- the full command surface, straight from the registry ----
        m.put("commands", commandsIndex().optJSONArray("commands") ?: JSONArray())

        // ---- shell context (the OTHER door) ----
        m.put("shellContext", JSONObject()
            .put("note", "agents dispatched to this environment get a full Linux shell (proot rootfs) as the app uid — separate, BROADER trust door: file-level access across the app sandbox and /sdcard, no per-action consent")
            .put("boundaries", JSONArray()
                .put("no root")
                .put("Android app sandbox: other apps' private data invisible")
                .put("/system read-only")))

        return ok().put("access", m)
    }

    /**
     * Live capability probe: run REAL system commands from the app uid and
     * report what actually succeeds on this device build. Replaces
     * remembered generalities with per-device truth. Results cached until
     * service restart.
     */
    private var capabilityCache: JSONObject? = null

    private fun capabilities(): JSONObject {
        capabilityCache?.let { return it }
        val caps = JSONObject()
        fun probe(name: String, vararg cmd: String) {
            val okB = runCatching {
                val p = ProcessBuilder(*cmd).redirectErrorStream(true).start()
                val out = p.inputStream.bufferedReader().readText()
                p.waitFor() == 0 && !out.contains("Permission Denial") && !out.contains("Operation not permitted")
            }.getOrDefault(false)
            caps.put(name, if (okB) "ok" else "denied")
        }
        probe("pm_list", "/system/bin/pm", "list", "packages", "--user", "0")
        probe("am_start", "/system/bin/am", "start", "-a", "android.settings.WIRELESS_SETTINGS")
        probe("settings_read", "/system/bin/settings", "get", "--user", "0", "global", "airplane_mode_on")
        probe("input_keyevent", "/system/bin/input", "keyevent", "KEYCODE_HOME")
        caps.put("a11y_granted", true) // we're running, so yes
        caps.put("screen_read", "ok")  // ditto
        caps.put("am_start_caveat", "Samsung first-party apps (calculator, sbrowser) deny shell-uid starts; use launch cmd")
        capabilityCache = caps
        return ok().put("capabilities", caps)
    }


    /** Node-based click: find node whose text/desc CONTAINS the query (first
     *  match, or clickable ancestor), then performAction(ACTION_CLICK).
     *  Immune to Samsung's injected-gesture cancellation — no touch synthesis. */
    private fun nodeClick(query: String): JSONObject {
        val root = rootInActiveWindow ?: return err().put("error", "no active window")
        val target = findNode(root) { n ->
            (n.text?.toString()?.contains(query, ignoreCase = true) == true) ||
            (n.contentDescription?.toString()?.contains(query, ignoreCase = true) == true)
        } ?: return err().put("error", "no node matching \"$query\"")
        // Walk up to the nearest clickable ancestor if the node itself isn't
        var node = target
        var hops = 0
        while (!node.isClickable && node.parent != null && hops < 6) {
            node = node.parent ?: break; hops++
        }
        if (!node.isClickable) return err()
            .put("error", "match \"$query\" found but neither it nor ancestors clickable")
        val done = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        return if (done) ok().put("clicked", query).put("hops", hops)
               else err().put("error", "ACTION_CLICK denied by node")
    }

    /** Click by view-id resource name (exact). */
    private fun nodeClickId(id: String): JSONObject {
        val root = rootInActiveWindow ?: return err().put("error", "no active window")
        val node = findNode(root) { n -> n.viewIdResourceName == id }
            ?: return err().put("error", "no node with id \"$id\"")
        val done = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        return if (done) ok().put("clicked", id) else err().put("error", "ACTION_CLICK denied")
    }

    private fun findNode(root: AccessibilityNodeInfo, pred: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        var found: AccessibilityNodeInfo? = null
        walkNodes(root) { n -> if (found == null && pred(n)) found = n }
        return found
    }


    private fun log(msg: String) {
        runCatching {
            File(applicationContext.filesDir, "a11y.log").appendText(
                "[${System.currentTimeMillis()}] $msg\n")
        }
    }
}
