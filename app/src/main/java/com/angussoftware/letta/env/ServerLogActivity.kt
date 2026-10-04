package com.angussoftware.letta.env

import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File

/**
 * Full-screen server log view (v0.4.7, Harry Oct 4: "server logs are not
 * scrollable, server logs should just launch to a new page").
 *
 * Replaces the inline collapsible log card on the phone main screen: the
 * nested ScrollView-inside-ScrollView fought both scrolling and readability.
 * Same precedent as the watch layout's separate log section — the log gets
 * its own screen with its own scroll, so the main screen scrolls one axis
 * and the log scrolls one axis.
 *
 * The log source is unchanged: filesDir/server.log, tail-read via
 * [RandomAccessTail] (64KB cap — bounded main-thread work, same bound the
 * inline card used). A 2s tick refreshes the tail and keeps the view pinned
 * to the bottom while the user is already there.
 */
class ServerLogActivity : androidx.activity.ComponentActivity() {

    private lateinit var C: EnvPalette.Mode
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private lateinit var logView: TextView
    private lateinit var scroll: ScrollView

    private val refresh = object : Runnable {
        override fun run() {
            renderLog()
            handler.postDelayed(this, 2000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        resolvePalette()
        val pad = dp(16)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(C.bg)
        }

        // ---- header: title + Close -----------------------------------------
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(pad, dp(24), pad, dp(8))
        }
        header.addView(TextView(this).apply {
            text = "Server log"
            textSize = 20f
            setTextColor(C.textPrimary)
            typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(Button(this).apply {
            text = "Close"
            textSize = 13f
            backgroundTintList = android.content.res.ColorStateList.valueOf(C.accent.withAlpha(if (isDark()) 0x2A else 0x14))
            setTextColor(C.accent)
            setOnClickListener { finish() }
        })
        root.addView(header)

        // ---- log body: scrollable, monospace, selectable --------------------
        logView = TextView(this).apply {
            textSize = 12f
            typeface = android.graphics.Typeface.MONOSPACE
            setTextColor(C.textSecondary)
            setTextIsSelectable(true)
            setPadding(pad, 0, pad, pad)
        }
        // Horizontal scroll keeps long lines readable without wrapping noise;
        // vertical scroll is the page itself (same nesting as the old card,
        // but here the OUTER scroll is the whole screen — no fight with a
        // parent ScrollView).
        val hScroll = HorizontalScrollView(this).apply { addView(logView) }
        scroll = ScrollView(this).apply { addView(hScroll) }
        root.addView(scroll, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        setContentView(root)
        renderLog()
    }

    override fun onResume() {
        super.onResume()
        handler.post(refresh)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(refresh)
    }

    /**
     * Tail the log file and render. Pin to the bottom when the user is
     * already at the bottom (live tailing); never yank the view while the
     * user is scrolled up reading history.
     */
    private fun renderLog() {
        runCatching {
            val logFile = File(filesDir, "server.log")
            val tail = if (logFile.exists()) RandomAccessTail.tail(logFile, 64 * 1024) else ""
            val atBottom = !scroll.canScrollVertically(1)
            logView.text = tail.ifEmpty { "No log output yet." }
            if (atBottom) scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
        }
    }

    private fun resolvePalette() {
        val night = (resources.configuration.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        C = EnvPalette.forMode(night)
    }

    private fun isDark(): Boolean = C === EnvPalette.forMode(true)

    private fun dp(v: Int): Int = (resources.displayMetrics.density * v).toInt()
}
