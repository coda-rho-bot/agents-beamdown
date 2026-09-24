package com.angussoftware.letta.env

import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.HorizontalScrollView
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
        // Prefix + length + charset — a bare startsWith accepted the literal
        // string "sk-let-" (review task_92 #2). Charset includes = + /
        // (base64 family): real keys carry = padding.
        private val KEY_REGEX = Regex("^sk-let-[A-Za-z0-9+/=_-]{20,}$")
        // Env name is interpolated into a shell script — unquoted spaces broke
        // launch with an opaque exit 1 (review task_92 #4). Restricted charset
        // is the real fix; quoting in the service is defense-in-depth.
        private val ENV_REGEX = Regex("^[a-z0-9][a-z0-9-_]{0,63}$")
    }

    // ---- palette: resolved per uiMode so both dark and light look right ----
    private object C {
        var bg = 0; var surface = 0; var textPrimary = 0; var textSecondary = 0
        var ok = 0; var warn = 0; var error = 0; var accent = 0; var outline = 0
    }

    private fun resolvePalette() {
        val night = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        if (night) {
            C.bg = Color.parseColor("#0E1116"); C.surface = Color.parseColor("#1A2028")
            C.textPrimary = Color.parseColor("#E6EAF0"); C.textSecondary = Color.parseColor("#8B93A1")
            C.ok = Color.parseColor("#4ADE80"); C.warn = Color.parseColor("#FBBF24")
            C.error = Color.parseColor("#F87171"); C.accent = Color.parseColor("#38BDF8")
            C.outline = Color.parseColor("#2A323E")
        } else {
            C.bg = Color.parseColor("#F5F7FA"); C.surface = Color.WHITE
            C.textPrimary = Color.parseColor("#111827"); C.textSecondary = Color.parseColor("#6B7280")
            C.ok = Color.parseColor("#15803D"); C.warn = Color.parseColor("#B45309")
            C.error = Color.parseColor("#B91C1C"); C.accent = Color.parseColor("#0369A1")
            C.outline = Color.parseColor("#E2E6EC")
        }
    }

    private fun dp(v: Int): Int = (resources.displayMetrics.density * v).toInt()

    /** Rounded card surface. */
    private fun card(radius: Int = 14): GradientDrawable = GradientDrawable().apply {
        setColor(C.surface)
        cornerRadius = dp(radius).toFloat()
        setStroke(dp(1), C.outline)
    }

    // ---- views ----
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var statusDot: TextView
    private lateinit var statusLine: TextView
    private lateinit var envLine: TextView
    private var a11yDotView: TextView? = null
    private var a11yTextView: TextView? = null
    private lateinit var batteryBanner: LinearLayout
    private lateinit var batteryBannerText: TextView
    private lateinit var logCard: LinearLayout
    private lateinit var logHeader: LinearLayout
    private lateinit var logChevron: TextView
    private lateinit var logScroll: ScrollView
    private lateinit var logView: TextView
    private var logExpanded = false
    private val refresh = object : Runnable {
        override fun run() {
            renderStatus()
            handler.postDelayed(this, 2000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        resolvePalette()
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        if (prefs.getString(PREF_KEY, "").isNullOrBlank()) {
            showOnboarding()
        } else {
            showMain()
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        resolvePalette()
        recreate() // re-render with new palette (cheap; no state to lose)
    }

    /** First-run (or reconfigure): Letta API key + environment name. */
    private fun showOnboarding() {
        val pad = dp(20)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, dp(40), pad, pad)
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(C.bg)
        }
        val title = TextView(this).apply {
            text = "Letta Environment"
            textSize = 26f
            setTextColor(C.textPrimary)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val subtitle = TextView(this).apply {
            text = "Connect this phone as a Letta cloud execution environment.\n\n1. Create an API key at letta.com (Settings → API Keys)\n2. Paste it below"
            textSize = 14f
            setTextColor(C.textSecondary)
            setPadding(0, pad, 0, pad)
        }
        val fieldCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = card()
            setPadding(pad, pad, pad, pad)
        }
        val keyLabel = TextView(this).apply {
            text = "API key"; textSize = 12f; setTextColor(C.textSecondary)
        }
        val keyField = EditText(this).apply {
            hint = "sk-let-…"
            setHintTextColor(C.textSecondary)
            setTextColor(C.textPrimary)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setSingleLine()
        }
        val envLabel = TextView(this).apply {
            text = "Environment name"; textSize = 12f; setTextColor(C.textSecondary)
            setPadding(0, dp(12), 0, 0)
        }
        val envField = EditText(this).apply {
            hint = "environment name (e.g. my-phone)"
            setHintTextColor(C.textSecondary)
            setTextColor(C.textPrimary)
            setSingleLine()
            setText(getSharedPreferences(PREFS, MODE_PRIVATE).getString(PREF_ENV, DEFAULT_ENV))
        }
        val saveBtn = Button(this).apply {
            text = "Save & Start"
            setOnClickListener {
                val key = keyField.text.toString().trim()
                val env = envField.text.toString().trim().ifEmpty { DEFAULT_ENV }
                if (!KEY_REGEX.matches(key)) {
                    Toast.makeText(this@MainActivity, "Key must look like sk-let-… (letters/digits/dashes, 20+ chars)", Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                if (!ENV_REGEX.matches(env)) {
                    Toast.makeText(this@MainActivity, "Environment name: lowercase letters, digits, - and _ only (max 64 chars)", Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                    .putString(PREF_KEY, key)
                    .putString(PREF_ENV, env)
                    .apply()
                // Re-key/rename with a live server: stop it FIRST so the new
                // config actually takes effect (old flow left the old-key
                // server running — review task_92 #1 / task_93 #3).
                stopService(Intent(this@MainActivity, LettaEnvironmentService::class.java))
                ensureRuntimePermissions()
                showMain()
                startEnvironment()
            }
        }
        fieldCard.addView(keyLabel); fieldCard.addView(keyField)
        fieldCard.addView(envLabel); fieldCard.addView(envField)
        root.addView(title)
        root.addView(subtitle)
        root.addView(fieldCard)
        root.addView(saveBtn)
        val spacer = View(this)
        root.addView(spacer, LinearLayout.LayoutParams(1, dp(24)))
        setContentView(root)
    }

    private fun showMain() {
        val pad = dp(16)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, dp(24), pad, pad)
            setBackgroundColor(C.bg)
        }

        // ---- header ----
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val title = TextView(this).apply {
            text = "Letta Environment"
            textSize = 20f
            setTextColor(C.textPrimary)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        envLine = TextView(this).apply {
            textSize = 13f
            setTextColor(C.textSecondary)
            setPadding(dp(8), 0, 0, 0)
        }
        header.addView(title)
        header.addView(envLine, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(header)
        root.addView(View(this), LinearLayout.LayoutParams(1, dp(10)))

        // ---- status card ----
        val statusCard = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = card()
            setPadding(pad, pad, pad, pad)
            gravity = Gravity.CENTER_VERTICAL
        }
        statusDot = TextView(this).apply {
            text = "●"
            textSize = 18f
            setPadding(0, 0, dp(10), 0)
        }
        val statusCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        statusLine = TextView(this).apply {
            textSize = 15f
            setTextColor(C.textPrimary)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val keyLine = TextView(this).apply {
            textSize = 12f
            setTextColor(C.textSecondary)
        }
        keyLine.tag = "keyline"
        statusCol.addView(statusLine)
        statusCol.addView(keyLine)
        statusCard.addView(statusDot)
        statusCard.addView(statusCol, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(statusCard)
        root.addView(View(this), LinearLayout.LayoutParams(1, dp(10)))

        // ---- battery warning banner (48dp target, tinted, tap to fix) ----
        batteryBanner = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = GradientDrawable().apply {
                setColor(0x26F87171.toInt()) // translucent error tint; renderStatus recolors per mode
                cornerRadius = dp(12).toFloat()
            }
            minimumHeight = dp(48)
            setPadding(pad, dp(10), pad, dp(10))
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            foreground = obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackground))
                .getDrawable(0)?.apply { setBounds(0, 0, 0, 0) }
            setOnClickListener { requestBatteryExemption() }
        }
        batteryBannerText = TextView(this).apply {
            text = "Battery optimization can kill this app overnight. Tap to set it to Unrestricted."
            textSize = 13f
            setTextColor(Color.WHITE)
        }
        batteryBanner.addView(batteryBannerText, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(batteryBanner)

        // ---- actions: two compact rows in one card ----
        val actionCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = card()
            setPadding(dp(10), dp(10), dp(10), dp(10))
        }
        fun actionButton(label: String, danger: Boolean = false): Button = Button(this).apply {
            text = label
            textSize = 14f
            val dark = isDark()
            backgroundTintList = android.content.res.ColorStateList.valueOf(
                if (danger) (if (dark) 0x30F87171.toInt() else 0x14B91C1C.toInt())
                else (if (dark) 0x2A38BDF8.toInt() else 0x140369A1.toInt())
            )
            setTextColor(
                if (danger) (if (dark) 0xFFF0A0A0.toInt() else 0xFFB91C1C.toInt())
                else (if (dark) 0xFF7DD3FC.toInt() else 0xFF0369A1.toInt())
            )
        }
        val row1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val startBtn = actionButton("Start").apply {
            setOnClickListener {
                ensureRuntimePermissions()
                startEnvironment()
            }
        }
        val stopBtn = actionButton("Stop", danger = true).apply {
            setOnClickListener { stopService(Intent(this@MainActivity, LettaEnvironmentService::class.java)) }
        }
        row1.addView(startBtn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row1.addView(View(this), LinearLayout.LayoutParams(dp(8), 1))
        row1.addView(stopBtn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val row2 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(8), 0, 0)
        }
        val rekeyBtn = actionButton("Key / Name").apply {
            setOnClickListener { showOnboarding() }
        }
        val upgradeBtn = actionButton("Upgrade").apply {
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
        row2.addView(rekeyBtn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row2.addView(View(this), LinearLayout.LayoutParams(dp(8), 1))
        row2.addView(upgradeBtn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        actionCard.addView(row1)
        actionCard.addView(row2)

        // ---- phone-control setup card (accessibility off = agent can't drive UI) ----
        val a11yEnabled = isA11yEnabled()
        // Held as fields so the 2s refresh tick can live-update the card
        // (grant/revoke while the app is open reflects within 2s).
        a11yDotView = TextView(this).apply {
            text = "●"
            textSize = 16f
            setTextColor(if (a11yEnabled) C.ok else C.warn)
            setPadding(0, 0, dp(8), 0)
        }
        val a11yDot = a11yDotView!!
        a11yTextView = TextView(this).apply {
            text = if (a11yEnabled) "Phone control: ON — agent can operate apps"
                   else "Phone control: OFF"
            textSize = 14f
            setTextColor(if (a11yEnabled) C.ok else C.textPrimary)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val a11yText = a11yTextView!!
        val a11yBtn = Button(this).apply {
            text = if (a11yEnabled) "Settings" else "Enable"
            textSize = 13f
            setOnClickListener {
                // Full explanation first — informed consent, not a mystery toggle.
                val bodyRes = resources.getIdentifier("a11y_explain_body", "string", packageName)
                val titleRes = resources.getIdentifier("a11y_explain_title", "string", packageName)
                val body = if (bodyRes != 0) getString(bodyRes)
                    else "Grant the Letta Environment Agent accessibility access so it can see the screen, tap, swipe, and type on your behalf. Revocable any time in Settings > Accessibility."
                val title = if (titleRes != 0) getString(titleRes) else "Enable phone control?"
                android.app.AlertDialog.Builder(this@MainActivity)
                    .setTitle(title)
                    .setMessage(body)
                    .setPositiveButton(if (a11yEnabled) "Open Settings" else "Continue") { _, _ ->
                        startA11yGuide()
                    }
                    .setNegativeButton("Not now", null)
                    .show()
            }
        }
        val a11yCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = card()
            setPadding(pad, dp(12), pad, dp(12))
        }
        val a11yHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        a11yHeader.addView(a11yDot)
        a11yHeader.addView(a11yText, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        a11yHeader.addView(a11yBtn)
        a11yCard.addView(a11yHeader)
        root.addView(a11yCard)
        root.addView(View(this), LinearLayout.LayoutParams(1, dp(10)))
        root.addView(actionCard)
        root.addView(View(this), LinearLayout.LayoutParams(1, dp(10)))

        // ---- collapsible log card ----
        logCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = card()
        }
        logHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(pad, dp(12), pad, dp(12))
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            foreground = obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackground)).getDrawable(0)
            setOnClickListener { toggleLog() }
        }
        val logTitle = TextView(this).apply {
            text = "Server log"
            textSize = 14f
            setTextColor(C.textPrimary)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        logChevron = TextView(this).apply {
            text = "▸"
            textSize = 16f
            setTextColor(C.textSecondary)
        }
        logHeader.addView(logTitle, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        logHeader.addView(logChevron)
        logCard.addView(logHeader)

        logView = TextView(this).apply {
            textSize = 12f
            typeface = Typeface.MONOSPACE
            setTextColor(C.textSecondary)
            setTextIsSelectable(true)
        }
        val hScroll = HorizontalScrollView(this).apply { addView(logView) }
        logScroll = ScrollView(this).apply {
            addView(hScroll)
            visibility = View.GONE
        }
        logCard.addView(logScroll, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(220)))
        root.addView(logCard, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        setContentView(root)
    }

    private fun isDark(): Boolean =
        (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES

    private fun toggleLog() {
        logExpanded = !logExpanded
        logScroll.visibility = if (logExpanded) View.VISIBLE else View.GONE
        logChevron.text = if (logExpanded) "▾" else "▸"
    }

    /**
     * True when the app is exempt from battery optimization (Samsung: battery
     * setting "Unrestricted"). When false, Android will suspend the app under
     * memory/battery pressure — killed the server overnight twice (Aug 28,
     * Sep 20 2026).
     */
    /** True when our AgentAccessibilityService is enabled by the user.
     *  Samsung stores the FULL component name (pkg/pkg.AgentAccessibilityService);
     *  AOSP sometimes uses short form (pkg/.AgentAccessibilityService). Match both. */
    private fun isA11yEnabled(): Boolean {
        val setting = android.provider.Settings.Secure.getString(
            contentResolver, android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: ""
        return setting.split(':').any {
            it.contains("AgentAccessibilityService", ignoreCase = true) &&
            it.substringBefore('/').equals(packageName, ignoreCase = true)
        }
    }

    // ---- a11y setup guide ----------------------------------------------------
    // A persistent companion notification walks the user through Settings
    // (their shade stays available while they navigate Samsung's menus), and
    // this activity polls for the grant and celebrates when it lands.

    private var guideActive = false
    private val guideCheck = object : Runnable {
        override fun run() {
            if (!guideActive) return
            if (isA11yEnabled()) { onA11yGranted(); return }
            handler.postDelayed(this, 1000)
        }
    }

    /** Start the guided enable flow: notification companion + toggle polling. */
    private fun startA11yGuide() {
        guideActive = true
        postGuideNotification(
            "1. Open Settings → Accessibility",
            "2. Tap \"Installed apps\" (or \"Downloaded apps\")\n" +
            "3. Tap \"Letta Environment Agent\"\n" +
            "4. Toggle ON → \"Allow\"\n" +
            "\nThis notification updates itself — keep going!")
        handler.postDelayed(guideCheck, 1000)
        runCatching {
            startActivity(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
    }

    private fun onA11yGranted() {
        guideActive = false
        handler.removeCallbacks(guideCheck)
        postGuideNotification(
            "Phone control enabled ✓",
            "The agent can now see the screen, tap, swipe, and type. " +
            "Revoke any time: Settings → Accessibility → Letta Environment Agent.")
        Toast.makeText(this, "Phone control enabled ✓", Toast.LENGTH_LONG).show()
        // Re-render the card state (showMain rebuild is cheap)
        runCatching { showMain() }
    }

    private fun postGuideNotification(title: String, text: String) {
        val nm = getSystemService(android.app.NotificationManager::class.java)
        val n = android.app.Notification.Builder(this, "letta-env-agent-alerts")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText("Open to continue setup")
            .setStyle(android.app.Notification.BigTextStyle().bigText(text))
            .setOngoing(false)
            .setAutoCancel(true)
            .setContentIntent(
                android.app.PendingIntent.getActivity(this, 0,
                    Intent(this, MainActivity::class.java),
                    android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE))
            .build()
        nm.notify(6100, n)
    }

    private fun isBatteryUnrestricted(): Boolean {
        val pm = getSystemService(POWER_SERVICE) as android.os.PowerManager
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
        // Guide poll keeps running while user is in Settings (that's its job);
        // only refresh tick pauses.
    }

    /** Map status.txt content to (color, label) — parse the state= LINE, not the
     *  whole file (review #19 F1: env= line leaked into unknown-state labels). */
    private fun stateOf(raw: String): Pair<Int, String> {
        val stateLine = raw.lineSequence().firstOrNull { it.startsWith("state=") }
            ?.removePrefix("state=")?.trim() ?: ""
        return when {
            stateLine.contains("online") -> C.ok to "Online"
            stateLine.contains("registered") -> C.ok to "Registered"
            stateLine.contains("registering") -> C.warn to "Registering…"
            stateLine.contains("starting") -> C.warn to "Starting…"
            stateLine.contains("extracting") -> C.warn to "Extracting rootfs…"
            stateLine.contains("upgrading") -> C.warn to "Upgrading…"
            stateLine.contains("crashed") -> C.error to "Crashed"
            stateLine.contains("failed") -> C.error to "Failed"
            stateLine.contains("exited") -> C.textSecondary to "Stopped"
            stateLine.contains("stopped (by user)") -> C.textSecondary to "Stopped"
            stateLine.contains("stopping") -> C.textSecondary to "Stopping…"
            else -> C.textSecondary to stateLine.take(28).ifEmpty { "Not started" }
        }
    }

    private fun renderStatus() {
        if (!::statusLine.isInitialized) return // onboarding path never builds these views
        runCatching {
            val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
            val env = prefs.getString(PREF_ENV, DEFAULT_ENV)
            val statusFile = File(filesDir, "status.txt")
            val logFile = File(filesDir, "server.log")
            val rawStatus = if (statusFile.exists()) statusFile.readText() else ""
            val (color, label) = stateOf(rawStatus)
            statusDot.setTextColor(color)
            statusLine.text = label
            statusLine.setTextColor(color)
            envLine.text = env
            (statusLine.parent as? LinearLayout)?.getChildAt(1)?.let { v ->
                (v as? TextView)?.text = "key: ${maskKey(prefs.getString(PREF_KEY, ""))}"
            }
            // Battery banner: re-evaluated each tick; clears itself when granted.
            // Phone-control card: live state each tick (grant/revoke reflects in 2s)
            a11yDotView?.setTextColor(if (isA11yEnabled()) C.ok else C.warn)
            val a11yOn = isA11yEnabled()
            a11yTextView?.apply {
                text = if (a11yOn) "Phone control: ON — agent can operate apps"
                       else "Phone control: OFF"
                setTextColor(if (a11yOn) C.ok else C.textPrimary)
            }

            batteryBanner.visibility =
                if (isBatteryUnrestricted()) View.GONE else View.VISIBLE
            // Review #19 N3: solid error-tinted bg in light mode (white text
            // on 15%-alpha red was unreadable); translucent in dark.
            (batteryBanner.background as? GradientDrawable)?.setColor(
                if (isDark()) 0x26F87171.toInt() else 0xFF991B1B.toInt())
            batteryBannerText.setTextColor(
                if (isDark()) 0xFFFECACA.toInt() else Color.WHITE)

            if (logExpanded) {
                val tail = if (logFile.exists()) {
                    // tail-read: cap to last 64KB to bound main-thread work
                    RandomAccessTail.tail(logFile, 64 * 1024)
                } else ""
                val atBottom = !logScroll.canScrollVertically(1)
                logView.text = tail
                if (atBottom) logScroll.post { logScroll.fullScroll(ScrollView.FOCUS_DOWN) }
            }
        }
    }

    /** Prefix-only masking — old version leaked 3 secret-body chars (task_92 #8). */
    private fun maskKey(key: String?): String =
        if (key.isNullOrBlank()) "not set" else "sk-let-" + "•".repeat(6) + key.takeLast(4)
}

/** Bounded tail read: seek near end, return last complete lines. */
private object RandomAccessTail {
    fun tail(f: File, maxBytes: Int): String {
        java.io.RandomAccessFile(f, "r").use { raf ->
            val start = maxOf(0L, raf.length() - maxBytes)
            raf.seek(start)
            val bytes = ByteArray((raf.length() - start).toInt())
            raf.readFully(bytes)
            val text = String(bytes, Charsets.UTF_8)
            return text.substringAfter("\n").takeLast(8000) // drop partial line, cap chars
        }
    }
}
