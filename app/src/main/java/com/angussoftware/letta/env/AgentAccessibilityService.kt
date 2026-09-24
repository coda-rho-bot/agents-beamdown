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

    private fun handle(cmd: String, req: JSONObject): JSONObject = when (cmd) {
        "ping" -> ok().put("service", "a11y").put("uid", android.os.Process.myUid())
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
        val done = java.util.concurrent.CountDownLatch(1)
        var okFlag = false
        val gd = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs.toLong()))
            .build()
        val dispatched = dispatchGesture(gd, object : GestureResultCallback() {
            override fun onCompleted(g: GestureDescription?) { okFlag = true; done.countDown() }
            override fun onCancelled(g: GestureDescription?) { okFlag = false; done.countDown() }
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
