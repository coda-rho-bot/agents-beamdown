package com.angussoftware.letta.env

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File

class MainActivity : android.app.Activity() {

    companion object {
        // Single source: LettaEnvironmentService.PREFS etc.
        private val PREFS = LettaEnvironmentService.PREFS
        private val PREF_KEY = LettaEnvironmentService.PREF_KEY
        private val PREF_ENV = LettaEnvironmentService.PREF_ENV
        private val DEFAULT_ENV = LettaEnvironmentService.DEFAULT_ENV
    }

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var statusView: TextView
    private lateinit var logView: TextView
    private val refresh = object : Runnable {
        override fun run() {
            renderStatus()
            handler.postDelayed(this, 2000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        if (prefs.getString(PREF_KEY, "").isNullOrBlank()) {
            showOnboarding()
        } else {
            showMain()
        }
    }

    /** First-run (or reconfigure): Letta API key + environment name. */
    private fun showOnboarding() {
        val pad = (resources.displayMetrics.density * 16).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad * 2, pad, pad)
            gravity = Gravity.CENTER_VERTICAL
        }
        val title = TextView(this).apply {
            text = "Letta Environment"
            textSize = 24f
        }
        val subtitle = TextView(this).apply {
            text = "Connect this phone as a Letta cloud execution environment.\n\n1. Create an API key at letta.com (Settings → API Keys)\n2. Paste it below"
            textSize = 14f
            setPadding(0, pad, 0, pad / 2)
        }
        val keyField = EditText(this).apply {
            hint = "sk-let-…"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine()
        }
        val envField = EditText(this).apply {
            hint = "environment name (e.g. my-phone)"
            setSingleLine()
            setText(getSharedPreferences(PREFS, MODE_PRIVATE).getString(PREF_ENV, DEFAULT_ENV))
        }
        val saveBtn = Button(this).apply {
            text = "Save & Start"
            setOnClickListener {
                val key = keyField.text.toString().trim()
                val env = envField.text.toString().trim().ifEmpty { DEFAULT_ENV }
                if (!key.startsWith("sk-let-")) {
                    Toast.makeText(this@MainActivity, "Key should start with sk-let-", Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                    .putString(PREF_KEY, key)
                    .putString(PREF_ENV, env)
                    .apply()
                // wipe any prior runtime state so a re-key restarts clean
                File(filesDir, "launch-server.sh").delete()
                File(filesDir, "server.log").delete()
                showMain()
                startEnvironment()
            }
        }
        root.addView(title)
        root.addView(subtitle)
        root.addView(keyField)
        root.addView(envField)
        root.addView(saveBtn)
        setContentView(root)
    }

    private fun showMain() {
        val pad = (resources.displayMetrics.density * 16).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad * 2, pad, pad)
        }
        statusView = TextView(this).apply { textSize = 16f }
        logView = TextView(this).apply {
            textSize = 11f
            typeface = android.graphics.Typeface.MONOSPACE
        }
        val startBtn = Button(this).apply {
            text = "Start environment"
            setOnClickListener {
                if (Build.VERSION.SDK_INT >= 33 &&
                    checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                ) {
                    requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1)
                }
                startEnvironment()
            }
        }
        val stopBtn = Button(this).apply {
            text = "Stop"
            setOnClickListener { stopService(Intent(this@MainActivity, LettaEnvironmentService::class.java)) }
        }
        val rekeyBtn = Button(this).apply {
            text = "Change API key / name"
            setOnClickListener { showOnboarding() }
        }
        root.addView(statusView)
        root.addView(startBtn)
        root.addView(stopBtn)
        root.addView(rekeyBtn)
        val scroll = ScrollView(this).apply { addView(logView) }
        root.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
    }

    private fun startEnvironment() {
        startForegroundService(Intent(this, LettaEnvironmentService::class.java))
    }

    override fun onResume() {
        super.onResume()
        handler.post(refresh)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(refresh)
    }

    private fun renderStatus() {
        if (!::statusView.isInitialized) return // onboarding path never builds these views
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val env = prefs.getString(PREF_ENV, DEFAULT_ENV)
        val statusFile = File(filesDir, "status.txt")
        val logFile = File(filesDir, "server.log")
        val statusText = if (statusFile.exists()) statusFile.readText() else "Not started"
        statusView.text = "$statusText\nkey: ${maskKey(prefs.getString(PREF_KEY, ""))}"
        logView.text = if (logFile.exists()) {
            logFile.readLines().takeLast(40).joinToString("\n")
        } else ""
    }

    private fun maskKey(key: String?): String =
        if (key.isNullOrBlank()) "not set" else key.take(10) + "…" + key.takeLast(4)
}
