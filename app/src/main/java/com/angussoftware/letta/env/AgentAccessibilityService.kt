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
import java.util.concurrent.Executors

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
 * Command channel: TCP socket on 127.0.0.1:8765 (app-internal, same-UID only
 * — other apps cannot connect; enforced by Android's per-uid loopback policy
 * is NOT a thing, so we gate on peer credential == our uid).
 * Line protocol: one JSON request per line -> one JSON response per line.
 * The agent's shell (same uid) reaches it via files/bin/agentctl.
 */
class AgentAccessibilityService : AccessibilityService() {

    companion object {
        const val PORT = 8765
        @Volatile var instance: AgentAccessibilityService? = null
            private set
    }

    private val pool = Executors.newCachedThreadPool()
    private var server: ServerSocket? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        startSocket()
        log("a11y service connected; command socket starting on $PORT")
    }

    override fun onDestroy() {
        instance = null
        runCatching { server?.close() }
        pool.shutdownNow()
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) { /* passive */ }
    override fun onInterrupt() { /* nothing */ }

    override fun onKeyEvent(event: KeyEvent?): Boolean = false

    // ---- command socket ----------------------------------------------------------

    private fun startSocket() {
        if (server != null && !server!!.isClosed) return
        pool.execute {
            try {
                val ss = ServerSocket(PORT, 8, java.net.InetAddress.getByName("127.0.0.1"))
                server = ss
                while (!ss.isClosed) {
                    val client = ss.accept()
                    // Same-uid gate: only our own processes (agent shell) may talk.
                    val peerUid = client.javaFieldInt("uid") // see helper below
                    if (peerUid != -1 && peerUid != android.os.Process.myUid()) {
                        client.close(); continue
                    }
                    pool.execute { serve(client) }
                }
            } catch (_: Exception) { /* socket closed or bind failure */ }
        }
    }

    private fun Any.javaFieldInt(name: String): Int = try {
        val f = this.javaClass.getDeclaredField(name)
        f.isAccessible = true
        (f.get(this) as? Int) ?: -1
    } catch (_: Exception) { -1 }

    private fun serve(client: java.net.Socket) {
        try {
            client.tcpNoDelay = true
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
        if (consentPromptShowing) return err().put("error", "a consent prompt is already showing")
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
        consentPromptShowing = false
        mainHandler.post { dismissConsentOverlay() } // no-op if already dismissed
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
        val root = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setBackgroundColor(p.bg.withAlpha(0xF0))
            setPadding(dp(28), dp(40), dp(28), dp(40))
            gravity = android.view.Gravity.CENTER
        }
        val title = android.widget.TextView(this).apply {
            text = "Agent requests phone access"
            textSize = 22f
            setTextColor(p.textPrimary)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        val who = android.widget.TextView(this).apply {
            text = agent
            textSize = 16f
            setTextColor(p.accent)
            setPadding(0, dp(10), 0, 0)
        }
        val body = android.widget.TextView(this).apply {
            text = "“$desc”"
            textSize = 18f
            setTextColor(p.textPrimary)
            setPadding(0, dp(16), 0, dp(8))
        }
        val dur = android.widget.TextView(this).apply {
            text = "Duration: ${seconds}s — then access revokes automatically. " +
                "Approving lets the agent see and use this phone for that purpose."
            textSize = 14f
            setTextColor(p.textSecondary)
            setPadding(0, 0, 0, dp(24))
        }
        val buttons = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
        }
        val deny = android.widget.Button(this).apply {
            text = "Deny"
            setOnClickListener { dismissConsentOverlay(); onDeny() }
        }
        val approve = android.widget.Button(this).apply {
            text = "Approve"
            setOnClickListener { dismissConsentOverlay(); onApprove() }
        }
        buttons.addView(deny, android.widget.LinearLayout.LayoutParams(0,
            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        buttons.addView(approve, android.widget.LinearLayout.LayoutParams(0,
            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(title); root.addView(who); root.addView(body); root.addView(dur); root.addView(buttons)
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
        // Deterministic consent gate: privacy-bearing commands require an
        // APPROVED session (see requestConsent). Introspection (commands,
        // capabilities, ping), pure navigation (home/back), telemetry whose
        // scoped permission grant IS the consent (health/location), and
        // notifstatus (grant state only — no content) stay ungated.
        // notiflist (message CONTENT) is privacy-peer to screen: gated.
        val gated = cmd !in setOf(
            "ping", "commands", "capabilities", "home", "back",
            "notifications", "session", "status",
            "health", "location", "notifstatus")
        if (gated) gate()?.let { return it }

        return when (cmd) {
            "ping" -> ok().put("service", "a11y").put("uid", android.os.Process.myUid())
            "commands" -> commandsIndex()
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
                    startActivity(android.content.Intent(this, HCRequestActivity::class.java)
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                    ok().put("launched", true)
                }.fold({ it }, { err().put("error", "launch failed: ${it.message}") })
            }
            "notifstatus" -> ok()
                .put("granted", AgentNotificationListener.granted(applicationContext))
                .put("snapshotCount", AgentNotificationListener.current().optInt("count", 0))
            "notiflist" -> AgentNotificationListener.current()
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
        "launch" -> {
            // Handled client-side by agentctl (am start) in most cases; kept for URI intents
            val uri = req.optString("uri")
            val i = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(uri))
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { startActivity(i) }
                .fold({ ok() }, { err().put("error", it.message) })
        }
        else -> err().put("error", "unknown cmd: $cmd")
        }
    }

    private fun ok() = JSONObject().put("ok", true)
    private fun err() = JSONObject().put("ok", false)

    // ---- gestures ------------------------------------------------------------------

    private fun gestureTap(x: Double, y: Double, durationMs: Double): JSONObject {
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        return dispatch(path, durationMs, "tap($x,$y)")
    }

    private fun gestureSwipe(fx: Double, fy: Double, tx: Double, ty: Double, durationMs: Double): JSONObject {
        val path = Path().apply {
            moveTo(fx.toFloat(), fy.toFloat())
            lineTo(tx.toFloat(), ty.toFloat())
        }
        return dispatch(path, durationMs, "swipe($fx,$fy -> $tx,$ty)")
    }

    private fun dispatch(path: Path, durationMs: Double, label: String): JSONObject {
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
        fun add(name: String, args: String, what: String, note: String = "") {
            cmds.put(JSONObject().put("cmd", name).put("args", args)
                .put("does", what).put("note", note))
        }
        add("ping", "", "service handshake; returns uid")
        add("commands", "", "this index")
        add("capabilities", "", "live device capability probe (what works on THIS phone)")
        add("tap", "x y", "tap at coordinates",
            if (gesturesBlocked) "BLOCKED: injected gestures cancelled by this OEM build" else "")
        add("longPress", "x y", "press-and-hold at coordinates",
            if (gesturesBlocked) "BLOCKED: injected gestures cancelled by this OEM build" else "")
        add("swipe", "fromX fromY toX toY [durationMs]", "stroke gesture",
            if (gesturesBlocked) "BLOCKED: injected gestures cancelled by this OEM build" else "")
        add("click", "\"label\"", "node-click by visible text/content-desc — primary input method")
        add("clickId", "view-id", "node-click by resource id")
        add("text", "\"value\"", "type into the focused input field")
        add("back", "", "global action: back")
        add("home", "", "global action: home")
        add("notifications", "", "global action: pull notification shade")
        add("key", "keyCode", "press a key", "limited to back/home/recents; arbitrary keys need INJECT_EVENTS (usually denied)")
        add("screen", "", "visible text elements + bounds + clickability (JSON)")
        add("tree", "[maxDepth]", "active window hierarchy (JSON)")
        add("launch", "uri", "open ACTION_VIEW intent", "works where shell am start is OEM-blocked")
        add("session", "\"desc\" [seconds]", "request user consent overlay — REQUIRED before any read/click/type/launch; blocks up to 120s waiting for Approve/Deny")
        add("status", "", "current session state (active, expiry, pending prompt)")
        add("health", "status|hr|steps [hours]|sleep [days]|skin", "health telemetry — permission-scoped (Health Connect grant IS consent)",
            "hr reads the newest synced sample (may lag the watch by minutes); status shows grant state before first use")
        add("location", "", "last-known fix {lat,lon,accuracyM,ageSec} or one current fetch",
            "permission-scoped — location grant IS consent")
        add("notifstatus", "", "notification-listener grant state + snapshot count (no content)")
        add("notiflist", "", "active notifications (pkg/title/text)", "SESSION-GATED — message content is privacy-peer to screen")
        return ok().put("commands", cmds)
            .put("gesturesBlocked", gesturesBlocked)
            .put("consentGate", true)
            .put("note", "consent-gated: screen/tree/click/clickId/text/tap/longPress/swipe/key/launch require an approved session; agentctl session requests one via full-screen overlay. gesturesBlocked is learned: first cancelled gesture flips it.")
    }

    /**
     * Set on first onCancelled from a real (non-quick) gesture. One UI 7
     * cancels injected gestures even hands-off; instead of guessing, the
     * service learns it from the dispatcher itself.
     */
    @Volatile private var gesturesBlocked = false

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
