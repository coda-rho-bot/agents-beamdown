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
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
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

    // ---- palette: Angus Software Theming tokens, resolved per uiMode ----
    private lateinit var C: EnvPalette.Mode

    private fun resolvePalette() {
        val night = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        C = EnvPalette.forMode(night)
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
    private lateinit var versionLine: TextView
    private lateinit var envLine: TextView
    private var a11yDotView: TextView? = null
    private var a11yTextView: TextView? = null
    // Nullable: the watch layout has no battery banner (WearOS has no
    // exemption path — ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS is
    // ignored) and no collapsible log (log tail is always visible).
    private var batteryBanner: LinearLayout? = null
    private lateinit var batteryBannerText: TextView
    private var logCard: LinearLayout? = null
    private var logHeader: LinearLayout? = null
    private var logChevron: TextView? = null
    private var logScroll: ScrollView? = null
    private lateinit var logView: TextView
    private var logExpanded = false
    // Self-updater (v0.4.0): update card views — nullable because the card
    // only exists on the main layout (never onboarding), and the tick
    // guards on initialization anyway.
    private var updateCard: LinearLayout? = null
    private var updateText: TextView? = null
    private var updateBtn: Button? = null
    private val refresh = object : Runnable {
        override fun run() {
            renderStatus()
            handler.postDelayed(this, 2000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        resolvePalette()
        // Self-updater: on-start check (clears stale state when we're current,
        // resumes a download that outlived the process, then force-checks the
        // feed). Spec: update check on app start.
        UpdateManager.onAppStart(this)
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        if (prefs.getString(PREF_KEY, "").isNullOrBlank()) {
            if (isWatch()) showOnboardingWatch() else showOnboarding()
        } else {
            // Configured: ensure the service is up. Covers post-install
            // relaunches and WearOS idle-kills where opening the app was
            // previously the ONLY manual recovery path (monkey relaunch
            // often didn't restart the FGS — autostart gap, Sep 25).
            // User-initiated Stop is respected: service start intent
            // re-asserts state without relaunching a stopped server
            // unless config changed (see handleStartIntent).
            // Configured: ensure the service is up (autostart gap fix) via
            // the marker-respecting implicit path — app-open never overrides
            // an explicit user Stop.
            ensureEnvironment()
            if (isWatch()) showMainWatch() else showMain()
        }
    }

    /** Watch detection: PackageManager feature flag — no build flavor needed,
     *  one APK adapts at runtime (phone and watch share this codebase). */
    private fun isWatch(): Boolean =
        packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_WATCH)

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
        val savedKey = getSharedPreferences(PREFS, MODE_PRIVATE).getString(PREF_KEY, "")
        val keyField = EditText(this).apply {
            // Pre-fill saved key (masked) on re-key so the user can change
            // ONLY the env name without re-entering the key (watch parity).
            hint = if (savedKey.isNullOrBlank()) "sk-let-…" else "API key — saved"
            setText(savedKey ?: "")
            setHintTextColor(C.textSecondary)
            setTextColor(C.textPrimary)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setSingleLine()
            // Deterministic mask: setSingleLine() after inputType can clobber
            // the password variation — transformationMethod survives ordering.
            transformationMethod = android.text.method.PasswordTransformationMethod.getInstance()
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
                saveOnboarding(keyField.text.toString().trim(),
                    envField.text.toString().trim().ifEmpty { DEFAULT_ENV })
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

    // ---- watch UI (WearOS) ----------------------------------------------------
    // WearOS design principles — deliberately NOT a shrunk phone layout:
    //   • ONE vertical, bezel-scrollable view (canonical WearOS pattern).
    //     Horizontal swipes are system-owned (left-swipe = back), so no pager.
    //   • Flat black (AMOLED), no cards/outlines — watch UIs are chromeless.
    //   • includeFontPadding=false everywhere: Android's default font padding
    //     adds uneven air that reads as "rough" spacing at watch scale.
    //   • Real drawn shapes (GradientDrawable), not unicode glyphs.
    //   • Pill buttons via TextViews (no Material chrome), ripple feedback,
    //     52dp targets, clear visual hierarchy: filled = primary, tonal = rest.
    private var watchA11yBtn: Button? = null
    private var watchScroll: ScrollView? = null
    private var watchDot: View? = null
    private var watchA11yPill: TextView? = null
    private var watchLogHeader: TextView? = null
    private var watchLogView: TextView? = null

    // Watch palette (AMOLED): explicit, not adapted from phone light/dark.
    private object W {
        const val bg = Color.BLACK
        const val text = 0xFFF2F5FA.toInt()        // primary text
        const val textDim = 0xFF8B93A1.toInt()     // secondary text
        const val textFaint = 0xFF5A6170.toInt()   // tertiary / headers
        const val accent = 0xFF38BDF8.toInt()      // sky
        const val accentFill = 0xFF0EA5E9.toInt()  // filled pill bg
        const val pillNeutral = 0xFF1C2028.toInt() // tonal pill bg
        const val danger = 0xFFF87171.toInt()
        const val dangerFill = 0xFF2A1215.toInt()
    }

    /**
     * Round-screen safe area: the usable region of a circle is the inscribed
     * square — (w - w/√2)/2 ≈ 14.6% of screen width of padding on ALL FOUR
     * sides (BoxInsetLayout formula). Side-only padding lets corners clip.
     */
    private fun applyWatchSafeArea(page: View, baseSides: Int, baseTop: Int, baseBottom: Int) {
        page.setOnApplyWindowInsetsListener { v, insets ->
            val w = resources.displayMetrics.widthPixels
            val round = if (android.os.Build.VERSION.SDK_INT >= 30) insets.isRound else false
            val inset = if (round) (w * 0.146).toInt() else dp(6)
            v.setPadding(inset + baseSides, inset + baseTop, inset + baseSides, inset + baseBottom)
            insets
        }
    }

    /** Tight watch text: no font padding, centered. */
    private fun watchText(
        text: String, size: Float, color: Int,
        bold: Boolean = false, mono: Boolean = false
    ): TextView = TextView(this).apply {
        this.text = text
        textSize = size
        setTextColor(color)
        gravity = Gravity.CENTER
        includeFontPadding = false
        if (bold) typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        if (mono) typeface = Typeface.MONOSPACE
    }

    /** Drawn circle for status (no unicode dots). */
    private fun watchDotView(sizeDp: Int, color: Int): View =
        View(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(color)
            }
            layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
        }

    /**
     * Watch pill button: TextView with pill background, no Material chrome,
     * ripple touch feedback. Styles: filled (primary action, accent bg, dark
     * text), tonal (neutral surface), danger (red tint).
     */
    private fun watchPill(
        label: String, style: Int = 0, onClick: (TextView) -> Unit
    ): TextView = TextView(this).apply {
        text = label
        textSize = 15f
        includeFontPadding = false
        gravity = Gravity.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        minHeight = dp(52)
        val (bg, fg) = when (style) {
            1 -> W.accentFill to Color.BLACK          // filled primary
            2 -> W.dangerFill to W.danger             // danger tonal
            else -> W.pillNeutral to W.text           // neutral tonal
        }
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(26).toFloat()
            setColor(bg)
        }
        setTextColor(fg)
        // ripple feedback (bounds-mode, on top of the pill)
        foreground = android.graphics.drawable.RippleDrawable(
            android.content.res.ColorStateList.valueOf(0x33FFFFFF.toInt()), null, null)
        isClickable = true
        isFocusable = true
        setOnClickListener { onClick(this) }
    }

    /** Small all-caps section header (letterspaced look via spacing char). */
    private fun watchHeader(label: String): TextView =
        watchText(label, 10.5f, W.textFaint, bold = true).apply {
            letterSpacing = 0.15f
            setPadding(0, 0, 0, 0)
        }

    /**
     * Watch main: ONE vertical, bezel-scrollable view — the canonical WearOS
     * pattern. Layout: status hero (top, centered), then all actions, then
     * the log tail.
     */
    private fun showMainWatch() {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setBackgroundColor(W.bg)
        }

        // ---- status hero: drawn dot, label, env, version — tight rhythm ----
        watchDot = watchDotView(26, C.ok).also { dot ->
            (dot.layoutParams as? LinearLayout.LayoutParams)?.apply {
                topMargin = dp(10); bottomMargin = dp(10)
                gravity = Gravity.CENTER_HORIZONTAL
            }
            content.addView(dot)
        }
        statusLine = watchText("", 21f, W.text, bold = true)
        envLine = watchText("", 12.5f, W.textDim).apply {
            setPadding(0, dp(3), 0, 0)
        }
        versionLine = watchText("", 10.5f, W.textFaint).apply {
            setPadding(0, dp(2), 0, 0)
        }
        content.addView(statusLine)
        content.addView(envLine)
        content.addView(versionLine)
        // NOTE: no battery pill on watch — One UI Watch ships NO per-app
        // battery controls (app details: Permissions/Version/Storage only,
        // verified Sep 25 on Galaxy Watch 8), and ACTION_REQUEST_IGNORE_
        // BATTERY_OPTIMIZATIONS doesn't resolve. The FGS (EXEMPTED bucket)
        // is the actual protection; a pill would promise what doesn't exist.
        content.addView(View(this), LinearLayout.LayoutParams(1, dp(22)))
        content.addView(View(this), LinearLayout.LayoutParams(1, dp(22)))

        // ---- actions ----
        content.addView(watchHeader("ACTIONS"))
        content.addView(View(this), LinearLayout.LayoutParams(1, dp(8)))

        val startPill = watchPill("Start", style = 1) {
            ensureRuntimePermissions(); startEnvironment()
        }
        val stopPill = watchPill("Stop", style = 2) {
            stopEnvironment()
        }
        val upgradePill = watchPill("Upgrade letta") {
            // No AlertDialog on watch — direct action + Toast (the upgrade
            // flow reports progress via the status line and notification).
            ensureRuntimePermissions()
            val intent = Intent(this, LettaEnvironmentService::class.java).apply {
                action = LettaEnvironmentService.ACTION_UPGRADE
            }
            startForegroundService(intent)
            Toast.makeText(this, "Upgrading — watch status", Toast.LENGTH_SHORT).show()
        }
        val rekeyPill = watchPill("Key / Name") { showOnboardingWatch() }
        val a11yPill = watchPill("Ally control") {
            if (isA11yEnabled()) {
                runCatching { startActivity(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
            } else {
                startA11yGuide()
            }
        }
        watchA11yPill = a11yPill
        listOf(startPill, stopPill, upgradePill, rekeyPill, a11yPill).forEach { b ->
            content.addView(b, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            content.addView(View(this), LinearLayout.LayoutParams(1, dp(8)))
        }

        // ---- log (collapsible: tap the big header row) ----
        content.addView(View(this), LinearLayout.LayoutParams(1, dp(8)))
        val logHeader = TextView(this).apply {
            text = "Server log  ▾"
            textSize = 14f
            setTextColor(W.textDim)
            includeFontPadding = false
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            gravity = Gravity.CENTER_VERTICAL or Gravity.CENTER_HORIZONTAL
            minHeight = dp(52)
            isClickable = true
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(26).toFloat()
                setColor(W.pillNeutral)
            }
            foreground = android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(0x33FFFFFF.toInt()), null, null)
            setOnClickListener { toggleWatchLog() }
        }
        watchLogHeader = logHeader
        content.addView(logHeader, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        content.addView(View(this), LinearLayout.LayoutParams(1, dp(8)))
        logView = watchText("", 10.5f, W.textDim, mono = true).apply {
            gravity = Gravity.START
        }
        watchLogView = logView
        content.addView(logView, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        logExpanded = true

        val scroll = ScrollView(this).apply {
            addView(content)
            setBackgroundColor(W.bg)
        }
        watchScroll = scroll
        applyWatchSafeArea(scroll, dp(8), 0, dp(2))
        setContentView(scroll)
    }

    /** Expand/collapse the watch log section (tap the big header pill). */
    private fun toggleWatchLog() {
        logExpanded = !logExpanded
        watchLogHeader?.text = if (logExpanded) "Server log  ▾" else "Server log  ▸"
        watchLogView?.visibility = if (logExpanded) View.VISIBLE else View.GONE
    }

    /** Rotary bezel: scroll the vertical view (WearOS-native direction). */
    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (watchScroll != null &&
            (event.source and android.view.InputDevice.SOURCE_ROTARY_ENCODER) != 0) {
            val delta = event.getAxisValue(MotionEvent.AXIS_SCROLL)
            watchScroll?.smoothScrollBy(0, (dp(60) * delta).toInt())
            return true
        }
        return super.onGenericMotionEvent(event)
    }

    /** Watch onboarding: key + env entry, flat black, round-safe, cancellable. */
    private fun showOnboardingWatch() {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setBackgroundColor(W.bg)
        }
        content.addView(watchText("Letta", 20f, W.text, bold = true))
        content.addView(watchText("Connect this watch as an environment.", 11.5f, W.textDim).apply {
            setPadding(0, dp(4), 0, dp(10))
        })
        val savedKey = prefs.getString(PREF_KEY, "")
        val keyField = EditText(this).apply {
            // Pre-fill the saved key (masked) on re-key so the user can
            // change ONLY the env name without re-entering the key.
            hint = if (savedKey.isNullOrBlank()) "API key (sk-let-…)" else "API key — saved"
            setText(savedKey ?: "")
            setHintTextColor(W.textFaint)
            setTextColor(W.text)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setSingleLine()
            // Deterministic mask — setSingleLine() can clobber the password
            // variation from inputType; this survives ordering (watch field).
            transformationMethod = android.text.method.PasswordTransformationMethod.getInstance()
            textSize = 13f
            includeFontPadding = false
            minHeight = dp(44)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(12).toFloat()
                setColor(W.pillNeutral)
            }
            setPadding(dp(12), 0, dp(12), 0)
        }
        val envField = EditText(this).apply {
            hint = "env name"
            setHintTextColor(W.textFaint)
            setTextColor(W.text)
            setSingleLine()
            textSize = 13f
            includeFontPadding = false
            minHeight = dp(44)
            setText(getSharedPreferences(PREFS, MODE_PRIVATE).getString(PREF_ENV, DEFAULT_ENV))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(12).toFloat()
                setColor(W.pillNeutral)
            }
            setPadding(dp(12), 0, dp(12), 0)
        }
        content.addView(keyField, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        content.addView(View(this), LinearLayout.LayoutParams(1, dp(8)))
        content.addView(envField, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        content.addView(View(this), LinearLayout.LayoutParams(1, dp(14)))
        content.addView(watchPill("Save & Start", style = 1) {
            saveOnboarding(keyField.text.toString().trim(),
                envField.text.toString().trim().ifEmpty { DEFAULT_ENV })
        })
        content.addView(View(this), LinearLayout.LayoutParams(1, dp(8)))
        // Cancel: only meaningful when re-keying (a key exists); otherwise no
        // main screen to go back to — hide it on first-run onboarding.
        if (!savedKey.isNullOrBlank()) {
            content.addView(watchPill("Cancel") { showMainWatch() })
        }
        val scroll = ScrollView(this).apply {
            addView(content)
            setBackgroundColor(W.bg)
        }
        applyWatchSafeArea(scroll, dp(4), dp(6), dp(4))
        setContentView(scroll)
    }

    /** Shared onboarding save+start (used by phone and watch variants). */
    private fun saveOnboarding(key: String, env: String) {
        // If the user left the key field empty but a saved key exists, they
        // are renaming only — keep the existing key. (The field pre-fills the
        // saved key, so empty means explicitly cleared; either way the saved
        // key is the safe default for a name-only change.)
        val effectiveKey = key.ifBlank {
            getSharedPreferences(PREFS, MODE_PRIVATE).getString(PREF_KEY, "") ?: ""
        }
        if (!KEY_REGEX.matches(effectiveKey)) {
            Toast.makeText(this, "Key must look like sk-let-… (letters/digits/dashes, 20+ chars)", Toast.LENGTH_LONG).show()
            return
        }
        if (!ENV_REGEX.matches(env)) {
            Toast.makeText(this, "Environment name: lowercase letters, digits, - and _ only (max 64 chars)", Toast.LENGTH_LONG).show()
            return
        }
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putString(PREF_KEY, effectiveKey)
            .putString(PREF_ENV, env)
            .apply()
        // Re-key/rename with a live server: stop it FIRST so the new
        // config actually takes effect (old flow left the old-key
        // server running — review task_92 #1 / task_93 #3).
        stopEnvironment()
        ensureRuntimePermissions()
        // Config change = definite restart: clear any user-stop marker so
        // the launch below isn't filtered by the sticky-stop guard.
        runCatching { File(filesDir, ".user-stopped").delete() }
        if (isWatch()) showMainWatch() else showMain()
        startEnvironment()
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
        // Installed letta-code version + upstream latest (checked async; refreshed each tick).
        versionLine = TextView(this).apply {
            textSize = 12f
            setTextColor(C.textSecondary)
        }
        statusCol.addView(versionLine)
        statusCard.addView(statusDot)
        statusCard.addView(statusCol, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(statusCard)
        root.addView(View(this), LinearLayout.LayoutParams(1, dp(10)))

        // ---- battery warning banner (48dp target, tinted, tap to fix) ----
        batteryBanner = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = GradientDrawable().apply {
                // translucent error tint; renderStatus recolors per mode
                setColor(C.error.withAlpha(0x26))
                cornerRadius = dp(12).toFloat()
            }
            minimumHeight = dp(48)
            setPadding(pad, dp(14), pad, dp(14))
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
        batteryBanner?.addView(batteryBannerText, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
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
            backgroundTintList = android.content.res.ColorStateList.valueOf(
                if (danger) C.error.withAlpha(if (isDark()) 0x30 else 0x14)
                else C.accent.withAlpha(if (isDark()) 0x2A else 0x14)
            )
            setTextColor(if (danger) C.error else C.accent)
        }
        val row1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val startBtn = actionButton("Start").apply {
            setOnClickListener {
                ensureRuntimePermissions()
                startEnvironment()
            }
        }
        val stopBtn = actionButton("Stop", danger = true).apply {
            setOnClickListener { stopEnvironment() }
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
        val upgradeBtn = actionButton("Upgrade letta version").apply {
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

        // ---- self-updater: update card (IDLE only — queued while running) ----
        // Visibility is driven by renderUpdateCard() on the 2s tick: hidden
        // while an agent session runs, shown when idle AND an update is known.
        updateCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = card()
            setPadding(pad, dp(12), pad, dp(12))
            visibility = View.GONE
        }
        updateText = TextView(this).apply {
            textSize = 14f
            setTextColor(C.textPrimary)
        }
        updateBtn = Button(this).apply {
            textSize = 13f
            backgroundTintList = android.content.res.ColorStateList.valueOf(
                C.accent.withAlpha(if (isDark()) 0x2A else 0x14))
            setTextColor(C.accent)
            setOnClickListener {
                val p = getSharedPreferences(PREFS, MODE_PRIVATE)
                when {
                    p.getBoolean(UpdateManager.PREF_DL_DONE, false) -> UpdateManager.install(this@MainActivity)
                    p.getLong(UpdateManager.PREF_DL_ID, -1) != -1L -> Unit // in-flight; tick shows progress
                    else -> UpdateManager.enqueueDownload(this@MainActivity)
                }
            }
        }
        updateCard!!.addView(updateText)
        updateCard!!.addView(updateBtn, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        root.addView(updateCard)
        root.addView(View(this), LinearLayout.LayoutParams(1, dp(10)))

        // ---- self-updater: update-mode setting row ----
        // Default "Prompt when idle"; optional "Install automatically
        // overnight" (charging + idle, 1–5 AM). Both still go through the
        // system installer — Android requires the one-time "install unknown
        // apps" grant for this app, and the installer shows its own
        // confirmation. Honest copy: this app cannot silently install.
        val updateMode = getSharedPreferences(PREFS, MODE_PRIVATE)
            .getString(UpdateManager.PREF_UPDATE_MODE, UpdateManager.MODE_PROMPT)
        val settingsCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = card()
            setPadding(pad, dp(12), pad, dp(12))
        }
        val settingsLabel = TextView(this).apply {
            text = "App updates"
            textSize = 14f
            setTextColor(C.textPrimary)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val modeBtn = Button(this).apply {
            textSize = 13f
            backgroundTintList = android.content.res.ColorStateList.valueOf(
                C.accent.withAlpha(if (isDark()) 0x2A else 0x14))
            setTextColor(C.accent)
            text = if (updateMode == UpdateManager.MODE_AUTO_OVERNIGHT)
                "Mode: Install automatically overnight" else "Mode: Prompt when idle"
            setOnClickListener {
                val p = getSharedPreferences(PREFS, MODE_PRIVATE)
                val newMode = if (p.getString(UpdateManager.PREF_UPDATE_MODE, UpdateManager.MODE_PROMPT) == UpdateManager.MODE_AUTO_OVERNIGHT)
                    UpdateManager.MODE_PROMPT else UpdateManager.MODE_AUTO_OVERNIGHT
                p.edit().putString(UpdateManager.PREF_UPDATE_MODE, newMode).apply()
                text = if (newMode == UpdateManager.MODE_AUTO_OVERNIGHT)
                    "Mode: Install automatically overnight" else "Mode: Prompt when idle"
                val msg = if (newMode == UpdateManager.MODE_AUTO_OVERNIGHT) {
                    "Between 1–5 AM, while charging and no agent session is running, the app " +
                        "downloads the update and opens the system installer. Android still " +
                        "requires the one-time \"install unknown apps\" grant for this app " +
                        "(first install attempt routes you to that setting), and the installer " +
                        "shows its own confirmation screen."
                } else {
                    "The update card appears on the main screen while the environment is idle."
                }
                Toast.makeText(this@MainActivity, msg, Toast.LENGTH_LONG).show()
            }
        }
        val modeExplain = TextView(this).apply {
            text = "Automatic overnight install still needs the one-time system \"install unknown apps\" grant and shows the installer's confirmation screen."
            textSize = 11f
            setTextColor(C.textSecondary)
            setPadding(0, dp(6), 0, 0)
        }
        settingsCard.addView(settingsLabel)
        settingsCard.addView(modeBtn, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        settingsCard.addView(modeExplain)
        root.addView(settingsCard)
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
        logHeader?.addView(logTitle, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        logHeader?.addView(logChevron)
        logCard?.addView(logHeader)

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
        logCard?.addView(logScroll, LinearLayout.LayoutParams(
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
        logScroll?.visibility = if (logExpanded) View.VISIBLE else View.GONE
        logChevron?.text = if (logExpanded) "▾" else "▸"
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
        // Re-render the card state (rebuild is cheap) — WATCH-aware: the old
        // unconditional showMain() put the PHONE layout on a watch screen.
        runCatching { if (isWatch()) showMainWatch() else showMain() }
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

    // ---- letta-code version indicator ------------------------------------------------
    // Installed version read from the rootfs package.json (same source the
    // upgrade path uses); latest from the npm registry, fetched async and
    // cached for an hour (avoid hammering the registry every 2s tick).

    @Volatile private var latestVersion: String? = null
    @Volatile private var latestCheckedAt: Long = 0

    private fun installedVersion(): String = try {
        val pkgJson = File(filesDir, "rootfs/usr/local/lib/node_modules/@letta-ai/letta-code/package.json")
        Regex("\"version\"\\s*:\\s*\"([^\"]+)\"").find(pkgJson.readText())
            ?.groupValues?.get(1) ?: "unknown"
    } catch (_: Exception) { "unknown" }

    private fun fetchLatestVersion() {
        val now = System.currentTimeMillis()
        if (latestVersion != null && now - latestCheckedAt < 3_600_000) return
        latestCheckedAt = now
        Thread {
            try {
                val url = java.net.URL("https://registry.npmjs.org/@letta-ai/letta-code/latest")
                val conn = url.openConnection() as java.net.HttpURLConnection
                conn.connectTimeout = 8000; conn.readTimeout = 8000
                val body = conn.inputStream.bufferedReader().readText()
                Regex("\"version\"\\s*:\\s*\"([^\"]+)\"").find(body)?.groupValues?.get(1)
                    ?.let { latestVersion = it }
            } catch (_: Exception) { /* offline or blocked — keep previous value */ }
        }.apply { isDaemon = true }.start()
    }

    private fun renderVersionLine() {
        fetchLatestVersion()
        val installed = installedVersion()
        val latest = latestVersion
        // Watch: faint base + bright state colors (phone palette is
        // light-mode-adapted — invisible/muddy on the watch's black bg).
        val base = if (isWatch()) W.textFaint else C.textSecondary
        val warn = if (isWatch()) 0xFFFBBF24.toInt() else C.warn
        val ok = if (isWatch()) 0xFF4ADE80.toInt() else C.ok
        versionLine.apply {
            when {
                installed == "unknown" -> {
                    text = "letta-code: not installed"
                    setTextColor(base)
                }
                latest == null -> {
                    text = "letta-code $installed"
                    setTextColor(base)
                }
                latest != installed -> {
                    text = "letta-code $installed — $latest available"
                    setTextColor(warn)
                }
                else -> {
                    text = "letta-code $installed (up to date)"
                    setTextColor(ok)
                }
            }
        }
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

    /** Explicit user Start (button / save) — clears any user-stop marker. */
    private fun startEnvironment() {
        startForegroundService(Intent(this, LettaEnvironmentService::class.java).apply {
            action = LettaEnvironmentService.ACTION_START_EXPLICIT
        })
    }

    /** App-open ensure-start: does NOT clear the user-stop marker — opening
     *  the app after an explicit Stop must not resurrect the server, while
     *  post-install relaunches and idle-kill recovery still bring it up. */
    private fun ensureEnvironment() {
        startForegroundService(Intent(this, LettaEnvironmentService::class.java))
    }

    /** Explicit user Stop — sticky across app re-opens (marker set by the
     *  service; cleared by Start button or config change). */
    private fun stopEnvironment() {
        startForegroundService(Intent(this, LettaEnvironmentService::class.java).apply {
            action = LettaEnvironmentService.ACTION_STOP_EXPLICIT
        })
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

    /** Watch state colors: bright variants for black AMOLED — the phone
     *  palette (C) adapts to light mode and would be invisible on black. */
    private fun watchStateColor(c: Int): Int = when (c) {
        C.ok -> 0xFF4ADE80.toInt()
        C.warn -> 0xFFFBBF24.toInt()
        C.error -> 0xFFF87171.toInt()
        else -> W.text
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
            // Watch layout: statusDot is never assigned (watchDot replaces it)
            // — touching it throws lateinit, runCatching eats the rest of the
            // tick, and NOTHING renders. Guard by layout mode.
            if (isWatch()) {
                statusLine.text = label
                statusLine.setTextColor(watchStateColor(stateOf(rawStatus).first))
                watchDot?.let { dot ->
                    (dot.background as? GradientDrawable)?.setColor(watchStateColor(color))
                }
            } else {
                statusDot.setTextColor(color)
                statusLine.text = label
                statusLine.setTextColor(color)
            }
            envLine.text = env
            // Phone key line is found by TAG ( getChildAt(1) was ambiguous —
            // on the watch layout child 1 is statusLine, so the 2s tick
            // overwrote "Online" with the masked key ).
            ((statusLine.parent as? android.view.ViewGroup)?.findViewWithTag<TextView>("keyline"))?.let { v ->
                (v as? TextView)?.text = "key: ${maskKey(prefs.getString(PREF_KEY, ""))}"
            }
            // Watch hero dot (drawn circle, recolored by state)
            watchDot?.let { dot ->
                (dot.background as? GradientDrawable)?.setColor(color)
            }
            // Watch version line color: keep faint (renderVersionLine may
            // recolor for upgrade prompts on phone; watch stays neutral)
            renderVersionLine()
            renderUpdateCard()
            // Battery banner: re-evaluated each tick; clears itself when granted.
            // Phone-control card: live state each tick (grant/revoke reflects in 2s).
            // Watch layout has neither (nulls) — WearOS ignores the exemption
            // path entirely; the fix is the phone-side Galaxy Wearable grant.
            a11yDotView?.setTextColor(if (isA11yEnabled()) C.ok else C.warn)
            val a11yOn = isA11yEnabled()
            a11yTextView?.apply {
                text = if (a11yOn) "Phone control: ON — agent can operate apps"
                       else "Phone control: OFF"
                setTextColor(if (a11yOn) C.ok else C.textPrimary)
            }
            // Watch Ally-control pill: live label (ON → opens settings; OFF → guide)
            watchA11yPill?.apply { text = if (a11yOn) "Ally control · ON" else "Ally control · OFF" }

            // Watch layout has no battery banner (null) — WearOS ignores the
            // exemption path entirely; the fix is the phone-side Wearable grant.
            batteryBanner?.apply {
                visibility =
                    if (isBatteryUnrestricted()) View.GONE else View.VISIBLE
                // Review #19 N3: solid error-tinted bg in light mode (white text
                // on 15%-alpha red was unreadable); translucent in dark.
                (background as? GradientDrawable)?.setColor(
                    if (isDark()) C.error.withAlpha(0x26) else C.error)
                batteryBannerText.setTextColor(
                    if (isDark()) C.textPrimary else Color.WHITE)
            }

            if (logExpanded) {
                val tail = if (logFile.exists()) {
                    // tail-read: cap to last 64KB to bound main-thread work
                    RandomAccessTail.tail(logFile, 64 * 1024)
                } else ""
                // Phone: nested log scroll; watch: whole-view scroll. On the
                // watch, DON'T auto-scroll to the log bottom — that would
                // yank the user away from the status hero at the top.
                val scroll = logScroll
                if (scroll != null) {
                    val atBottom = !scroll.canScrollVertically(1)
                    logView.text = tail
                    if (atBottom) scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
                } else {
                    logView.text = tail
                }
            }
        }
    }

    /** Prefix-only masking — old version leaked 3 secret-body chars (task_92 #8). */
    private fun maskKey(key: String?): String =
        if (key.isNullOrBlank()) "not set" else "sk-let-" + "•".repeat(6) + key.takeLast(4)

    /**
     * Self-updater card state machine (2s tick):
     *  - no update known            → hidden
     *  - update known, RUNNING      → hidden (queued until idle — spec)
     *  - update known, IDLE         → "vX.Y.Z available — Download"
     *  - downloading                → progress % (any state; once the user
     *                                taps Download we don't yank the card)
     *  - downloaded                 → "Install" (routes to the unknown-apps
     *                                grant settings when missing)
     * Also drives a periodic feed check while the UI is open (5h-gated
     * inside UpdateManager).
     */
    private fun renderUpdateCard() {
        val card = updateCard ?: return
        val tv = updateText ?: return
        val btn = updateBtn ?: return
        UpdateManager.maybeCheckAsync(this)
        UpdateManager.reconcileDownloadState(this)
        val p = getSharedPreferences(PREFS, MODE_PRIVATE)
        val tag = p.getString(UpdateManager.PREF_UPDATE_TAG, null)
        val dlId = p.getLong(UpdateManager.PREF_DL_ID, -1)
        val dlDone = p.getBoolean(UpdateManager.PREF_DL_DONE, false)
        val idle = LettaEnvironmentService.isEnvironmentIdle()
        when {
            tag == null -> card.visibility = View.GONE
            dlDone -> {
                card.visibility = View.VISIBLE
                tv.text = "$tag downloaded"
                tv.setTextColor(C.textPrimary)
                btn.text = "Install"
                btn.isEnabled = true
            }
            dlId != -1L -> {
                val pct = UpdateManager.downloadProgress(this, dlId)
                card.visibility = View.VISIBLE
                tv.text = if (pct != null) "Downloading $tag — $pct%" else "Downloading $tag…"
                tv.setTextColor(C.warn)
                btn.text = "Downloading…"
                btn.isEnabled = false
            }
            !idle -> card.visibility = View.GONE // queued while a session runs
            else -> {
                card.visibility = View.VISIBLE
                tv.text = "$tag available"
                tv.setTextColor(C.textPrimary)
                btn.text = "Download"
                btn.isEnabled = true
            }
        }
    }
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
