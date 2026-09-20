package com.angussoftware.letta.env

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
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
    private lateinit var batteryWarningView: TextView
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
                ensureRuntimePermissions()
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
        // Battery warning banner: shown/hidden by renderStatus() on every
        // refresh (2s cadence) so it reacts immediately when the user
        // changes the setting from the system dialog.
        batteryWarningView = TextView(this).apply {
            textSize = 14f
            setTextColor(0xFFB30000.toInt())
            setPadding(0, pad / 2, 0, pad / 2)
            text = "⚠ Battery optimization is ON — Android will kill this app overnight. Tap here to set it to Unrestricted, then confirm in the system dialog."
            setOnClickListener {
                requestBatteryExemption()
            }
        }
        logView = TextView(this).apply {
            textSize = 11f
            typeface = android.graphics.Typeface.MONOSPACE
        }
        val startBtn = Button(this).apply {
            text = "Start environment"
            setOnClickListener {
                ensureRuntimePermissions()
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
        val upgradeBtn = Button(this).apply {
            text = "Upgrade letta-code"
            setOnClickListener {
                android.app.AlertDialog.Builder(this@MainActivity)
                    .setTitle("Upgrade letta-code?")
                    .setMessage("Downloads the latest @letta-ai/letta-code via npm, re-applies the Android patches, and restarts the environment. The server will be offline for a few minutes.")
                    .setPositiveButton("Upgrade") { _, _ ->
                        ensureRuntimePermissions()
                        val intent = Intent(this@MainActivity, LettaEnvironmentService::class.java).apply {
                            action = LettaEnvironmentService.ACTION_UPGRADE
                        }
                        startForegroundService(intent)
                        Toast.makeText(this@MainActivity, "Upgrading — watch the status", Toast.LENGTH_SHORT).show()
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        }
        root.addView(statusView)
        root.addView(batteryWarningView)
        root.addView(startBtn)
        root.addView(stopBtn)
        root.addView(rekeyBtn)
        root.addView(upgradeBtn)
        val scroll = ScrollView(this).apply { addView(logView) }
        root.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
    }

    /**
     * Request any missing runtime permissions before the environment starts.
     * targetSdk 28 = legacy external storage, so READ/WRITE_EXTERNAL_STORAGE
     * runtime grants give the agent's processes full /sdcard path access.
     */
    private fun ensureRuntimePermissions() {
        val wanted = mutableListOf<String>()
        if (checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            wanted.add(android.Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        if (checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            wanted.add(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            wanted.add(android.Manifest.permission.POST_NOTIFICATIONS)
        }
        if (wanted.isNotEmpty()) {
            requestPermissions(wanted.toTypedArray(), 1)
        }
    }

    /**
     * True when the app is exempt from battery optimization (Samsung: battery
     * setting "Unrestricted"). When false, Android will suspend the app under
     * memory/battery pressure — which killed the server overnight twice
     * (Aug 28, Sep 20 2026), leaving the environment offline for hours.
     */
    private fun isBatteryUnrestricted(): Boolean {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(packageName)
    }

    /** One-tap path to the system exemption dialog for THIS app. */
    private fun requestBatteryExemption() {
        try {
            val intent = Intent(
                android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
        } catch (e: Exception) {
            // Some OEM builds block the direct intent — fall back to the
            // full battery-optimization list where the user finds the app.
            try {
                startActivity(Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (e2: Exception) {
                // Last resort: the app's own details page (Samsung's
                // battery setting lives under app info).
                startActivity(Intent(
                    android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:$packageName")
                ))
            }
        }
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
        // Battery warning visibility re-evaluated every refresh tick (2s):
        // clears itself the moment the exemption is granted, no restart needed.
        batteryWarningView.visibility =
            if (isBatteryUnrestricted()) TextView.GONE else TextView.VISIBLE
        logView.text = if (logFile.exists()) {
            logFile.readLines().takeLast(40).joinToString("\n")
        } else ""
    }

    private fun maskKey(key: String?): String =
        if (key.isNullOrBlank()) "not set" else key.take(10) + "…" + key.takeLast(4)
}
