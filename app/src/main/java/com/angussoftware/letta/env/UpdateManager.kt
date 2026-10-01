package com.angussoftware.letta.env

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Calendar
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Self-updater (ships in v0.4.0; spec Harry Sep 30 2026).
 *
 * Feed: Forgejo releases API of the public releases-only mirror repo
 * (coda/letta-environment-releases — the release pipeline dual-publishes
 * every tag there). Anonymous fetch, no auth. An optional read token can
 * still be baked in at build time via -PupdateFeedToken=...
 * (BuildConfig.UPDATE_FEED_TOKEN) as a debug aid against private feeds;
 * empty token (default) = unauthenticated request, which is correct for
 * the public feed. The token is a build input, never source control.
 *
 * Scheduling: no new always-running process. The periodic tick rides the
 * existing foreground service ([startPeriodicChecks] is idempotent); the
 * feed check itself is 5h-gated ([maybeCheckAsync]), so the 30-min tick only
 * re-evaluates the overnight auto-install conditions between checks.
 *
 * Download: DownloadManager (battle-tested, resumable by design, no
 * permissions needed for the app's own external-files dir). Progress is
 * polled by the UI's existing 2s tick; completion lands via
 * ACTION_DOWNLOAD_COMPLETE on the application context.
 *
 * Install: ACTION_VIEW application/vnd.android.package-archive with a
 * FileProvider URI. Android refuses mismatched signatures at install time,
 * so a hijacked feed can't install a different app — signature safety is
 * free. targetSdk 28 keeps background activity starts allowed, so the
 * service can open the installer for the overnight auto path; the system
 * installer still shows its own confirmation screen (documented honestly
 * in the settings copy — this app cannot silently install itself).
 *
 * Idle gating: the update prompt only renders while the environment is
 * IDLE (no agent session running); while RUNNING the card is queued
 * (hidden) until idle. Overnight auto-install additionally requires
 * charging and the 1–5 AM local window.
 */
object UpdateManager {

    // ---- prefs (shared with MainActivity / LettaEnvironmentService) ----
    const val PREF_UPDATE_TAG = "update_tag"           // latest newer tag, e.g. "v0.4.0"
    const val PREF_UPDATE_URL = "update_url"           // APK asset browser_download_url
    const val PREF_UPDATE_MODE = "update_mode"         // MODE_PROMPT | MODE_AUTO_OVERNIGHT
    const val PREF_LAST_CHECK = "update_last_check_ms"
    const val PREF_DL_ID = "update_dl_id"              // DownloadManager enqueue id
    const val PREF_DL_DONE = "update_dl_done"          // download reached SUCCESSFUL
    const val PREF_INSTALL_FIRED_FOR = "update_install_fired_for" // auto path: installer already opened for this tag (user cancel ≠ ask again every tick)

    const val MODE_PROMPT = "prompt"
    const val MODE_AUTO_OVERNIGHT = "auto_overnight"

    // Public releases-only mirror (release pipeline dual-publishes here).
    // Anonymous fetch — no token needed for release builds.
    private const val FEED_URL =
        "https://git.angussoftware.dev/api/v1/repos/coda/letta-environment-releases/releases/latest"
    private const val CHECK_INTERVAL_MS = 5 * 60 * 60 * 1000L   // spec: ~4-6h
    private const val TICK_MS = 30 * 60 * 1000L                // periodic tick granularity
    private const val OVERNIGHT_START_HOUR = 1                // 01:00 local
    private const val OVERNIGHT_END_HOUR = 5                  // 05:00 local

    private val periodicStarted = AtomicBoolean(false)
    private val checking = AtomicBoolean(false)
    private val receiverRegistered = AtomicBoolean(false)

    /** Release as published on the feed. apkUrl null = release carries no APK asset. */
    data class ReleaseInfo(val tag: String, val apkUrl: String?, val notes: String)

    private fun prefs(context: Context) =
        context.getSharedPreferences(LettaEnvironmentService.PREFS, Context.MODE_PRIVATE)

    // ---- pure logic (unit-tested in UpdateManagerTest) -----------------------

    /**
     * Parse the Forgejo `/releases/latest` payload. Returns null on drafts,
     * prereleases, missing tag, or malformed JSON. apkUrl picks the largest
     * .apk asset (the release pipeline attaches exactly one; largest wins if
     * that ever changes). A release with no APK asset yields apkUrl=null —
     * callers treat that as "not actionable" and skip it.
     */
    fun parseLatestRelease(json: String): ReleaseInfo? {
        return try {
            val obj = JSONObject(json)
            if (obj.optBoolean("draft") || obj.optBoolean("prerelease")) return null
            val tag = obj.optString("tag_name").trim()
            if (tag.isEmpty()) return null
            var apkUrl: String? = null
            var bestSize = -1L
            val assets = obj.optJSONArray("assets")
            if (assets != null) {
                for (i in 0 until assets.length()) {
                    val a = assets.optJSONObject(i) ?: continue
                    if (!a.optString("name").endsWith(".apk")) continue
                    val size = a.optLong("size", -1)
                    if (size > bestSize) {
                        bestSize = size
                        apkUrl = a.optString("browser_download_url")
                    }
                }
            }
            ReleaseInfo(tag, apkUrl, obj.optString("body"))
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Numeric component compare; tolerates a leading v/V on either side.
     * Non-numeric components make the comparison false (never prompt on a
     * tag we can't reason about).
     */
    fun isNewer(latest: String?, current: String): Boolean {
        if (latest.isNullOrBlank()) return false
        val l = latest.trim().removePrefix("v").removePrefix("V").split('.')
        val c = current.trim().removePrefix("v").removePrefix("V").split('.')
        val ln = l.map { it.toIntOrNull() }
        val cn = c.map { it.toIntOrNull() }
        if (ln.any { it == null } || cn.any { it == null }) return false
        for (i in 0 until maxOf(ln.size, cn.size)) {
            val a = ln.getOrNull(i) ?: 0
            val b = cn.getOrNull(i) ?: 0
            if (a != b) return a > b
        }
        return false // equal
    }

    // ---- feed check ----------------------------------------------------------

    /**
     * Async feed check, 5h-gated unless [force]. On success: store the newer
     * release, or clear stale availability when we're current. On failure
     * (offline / 404 private feed): keep previous state.
     */
    fun maybeCheckAsync(context: Context, force: Boolean = false) {
        val app = context.applicationContext
        if (!force) {
            val last = prefs(app).getLong(PREF_LAST_CHECK, 0)
            if (System.currentTimeMillis() - last < CHECK_INTERVAL_MS) return
        }
        if (!checking.compareAndSet(false, true)) return
        Thread {
            try {
                val conn = URL(FEED_URL).openConnection() as HttpURLConnection
                conn.connectTimeout = 10_000
                conn.readTimeout = 15_000
                val token = BuildConfig.UPDATE_FEED_TOKEN
                if (token.isNotEmpty()) {
                    conn.setRequestProperty("Authorization", "token $token")
                }
                if (conn.responseCode == 200) {
                    val body = conn.inputStream.bufferedReader().use { it.readText() }
                    val rel = parseLatestRelease(body)
                    if (rel != null && rel.apkUrl != null) {
                        if (isNewer(rel.tag, BuildConfig.VERSION_NAME)) {
                            prefs(app).edit()
                                .putString(PREF_UPDATE_TAG, rel.tag)
                                .putString(PREF_UPDATE_URL, rel.apkUrl)
                                .apply()
                        } else {
                            clearAvailable(app) // up to date — drop stale prompt
                        }
                    }
                }
            } catch (_: Exception) {
                // offline or blocked feed — keep previous state
            } finally {
                prefs(app).edit()
                    .putLong(PREF_LAST_CHECK, System.currentTimeMillis())
                    .apply()
                checking.set(false)
            }
        }.apply { isDaemon = true }.start()
    }

    /**
     * Idempotent periodic tick, started from the foreground service (which
     * is already always-running via app autostart + BootReceiver — no new
     * process). Every 30 min: feed check (5h-gated inside) + overnight
     * auto-install evaluation.
     */
    fun startPeriodicChecks(context: Context) {
        if (!periodicStarted.compareAndSet(false, true)) return
        val app = context.applicationContext
        Thread {
            while (true) {
                try {
                    Thread.sleep(TICK_MS)
                } catch (_: InterruptedException) {
                    return@Thread
                }
                maybeCheckAsync(app)
                maybeAutoInstall(app)
            }
        }.apply { isDaemon = true }.start()
    }

    /**
     * App-start hook: clears state when the installed version caught up to
     * (or passed) the known release (deletes the old APK), resumes a
     * download that survived process death, then forces a feed check.
     */
    fun onAppStart(context: Context) {
        val app = context.applicationContext
        val p = prefs(app)
        val tag = p.getString(PREF_UPDATE_TAG, null)
        if (tag != null && !isNewer(tag, BuildConfig.VERSION_NAME)) {
            clearAvailable(app)
        }
        val id = p.getLong(PREF_DL_ID, -1)
        if (id != -1L && !p.getBoolean(PREF_DL_DONE, false)) {
            when (downloadStatus(app, id)) {
                DownloadManager.STATUS_SUCCESSFUL ->
                    p.edit().putBoolean(PREF_DL_DONE, true).apply()
                DownloadManager.STATUS_FAILED ->
                    p.edit().remove(PREF_DL_ID).putBoolean(PREF_DL_DONE, false).apply()
                else -> Unit // still running/paused — UI polls it
            }
        }
        maybeCheckAsync(app, force = true)
    }

    /**
     * Reconcile persisted download state from DownloadManager truth. Covers
     * the case where the process died mid-download (its ACTION_DOWNLOAD_
     * COMPLETE receiver is gone): the UI tick calls this before rendering,
     * so a finished download flips to Install instead of sitting at
     * "Downloading… 100%" forever.
     */
    fun reconcileDownloadState(context: Context) {
        val app = context.applicationContext
        val p = prefs(app)
        val id = p.getLong(PREF_DL_ID, -1)
        if (id == -1L || p.getBoolean(PREF_DL_DONE, false)) return
        when (downloadStatus(app, id)) {
            DownloadManager.STATUS_SUCCESSFUL ->
                p.edit().putBoolean(PREF_DL_DONE, true).apply()
            DownloadManager.STATUS_FAILED ->
                p.edit().remove(PREF_DL_ID).putBoolean(PREF_DL_DONE, false).apply()
            else -> Unit // pending/running/paused — UI polls progress
        }
    }

    /** Drop all update state + cancel/delete any active or finished download. */
    fun clearAvailable(context: Context) {
        val app = context.applicationContext
        val p = prefs(app)
        val id = p.getLong(PREF_DL_ID, -1)
        if (id != -1L) {
            runCatching {
                (app.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).remove(id)
            }
        }
        downloadedFile(app)?.delete()
        p.edit()
            .remove(PREF_UPDATE_TAG)
            .remove(PREF_UPDATE_URL)
            .remove(PREF_DL_ID)
            .remove(PREF_DL_DONE)
            .remove(PREF_INSTALL_FIRED_FOR)
            .apply()
    }

    // ---- download ------------------------------------------------------------

    /** Enqueue the APK download via DownloadManager. False when no URL known. */
    fun enqueueDownload(context: Context): Boolean {
        val app = context.applicationContext
        val p = prefs(app)
        val url = p.getString(PREF_UPDATE_URL, null) ?: return false
        val tag = p.getString(PREF_UPDATE_TAG, null) ?: return false
        val req = DownloadManager.Request(Uri.parse(url))
            .setTitle("Letta Environment $tag")
            .setDescription("App update")
            .setDestinationInExternalFilesDir(app, null, "updates/letta-environment-$tag.apk")
        val id = (app.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(req)
        p.edit()
            .putLong(PREF_DL_ID, id)
            .putBoolean(PREF_DL_DONE, false)
            .apply()
        registerCompleteReceiver(app)
        return true
    }

    /** Percent 0..100, or null when the size is unknown / row is gone. */
    fun downloadProgress(context: Context, id: Long): Int? = queryDownload(context, id) { c ->
        val total = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
        val sofar = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
        if (total > 0) (sofar * 100 / total).toInt() else null
    }

    private fun downloadStatus(context: Context, id: Long): Int = queryDownload(context, id) { c ->
        c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
    } ?: DownloadManager.STATUS_FAILED

    private fun <T> queryDownload(context: Context, id: Long, read: (android.database.Cursor) -> T): T? {
        return try {
            val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            dm.query(DownloadManager.Query().setFilterById(id)).use { c ->
                if (c.moveToFirst()) read(c) else null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun registerCompleteReceiver(context: Context) {
        if (!receiverRegistered.compareAndSet(false, true)) return
        context.registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                val app = ctx.applicationContext
                val p = prefs(app)
                val id = p.getLong(PREF_DL_ID, -1)
                if (id == -1L) return
                if (intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1) != id) return
                if (downloadStatus(app, id) == DownloadManager.STATUS_SUCCESSFUL) {
                    p.edit().putBoolean(PREF_DL_DONE, true).apply()
                    // Overnight auto mode: open the installer right away when
                    // the conditions still hold (re-checked — the 436MB
                    // download takes a while and the world may have changed).
                    // Once-per-tag guard: a user cancelling the system dialog
                    // must not see it re-opened on later ticks.
                    val tag = p.getString(PREF_UPDATE_TAG, null)
                    if (p.getString(PREF_UPDATE_MODE, MODE_PROMPT) == MODE_AUTO_OVERNIGHT &&
                        tag != null &&
                        p.getString(PREF_INSTALL_FIRED_FOR, null) != tag &&
                        overnightConditionsHold(app)
                    ) {
                        p.edit().putString(PREF_INSTALL_FIRED_FOR, tag).apply()
                        install(app)
                    }
                } else {
                    p.edit().remove(PREF_DL_ID).putBoolean(PREF_DL_DONE, false).apply()
                }
            }
        }, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE))
    }

    // ---- overnight auto-install ----------------------------------------------

    /**
     * Auto-overnight evaluation (30-min tick + download-complete receiver):
     * download when an update is known and conditions hold; install when the
     * download is done and conditions hold.
     */
    fun maybeAutoInstall(context: Context) {
        val app = context.applicationContext
        val p = prefs(app)
        if (p.getString(PREF_UPDATE_MODE, MODE_PROMPT) != MODE_AUTO_OVERNIGHT) return
        val tag = p.getString(PREF_UPDATE_TAG, null) ?: return
        if (!overnightConditionsHold(app)) return
        val id = p.getLong(PREF_DL_ID, -1)
        when {
            id == -1L -> enqueueDownload(app)
            p.getBoolean(PREF_DL_DONE, false) -> {
                // Fire the installer at most ONCE per tag on the auto path —
                // a user cancelling the 2AM system dialog must not have it
                // re-opened every 30 minutes until 5AM. The card's Install
                // button stays available for a manual retry.
                if (p.getString(PREF_INSTALL_FIRED_FOR, null) != tag) {
                    p.edit().putString(PREF_INSTALL_FIRED_FOR, tag).apply()
                    install(app)
                }
            }
            else -> {
                // In-flight download with no process-death-surviving receiver
                // (ACTION_DOWNLOAD_COMPLETE broadcast may have arrived while
                // nothing was listening): reconcile from DownloadManager truth.
                when (downloadStatus(app, id)) {
                    DownloadManager.STATUS_SUCCESSFUL ->
                        p.edit().putBoolean(PREF_DL_DONE, true).apply()
                    DownloadManager.STATUS_FAILED ->
                        p.edit().remove(PREF_DL_ID).putBoolean(PREF_DL_DONE, false).apply()
                    else -> Unit // still running/paused
                }
            }
        }
    }

    /** 1–5 AM local, charging, and no agent session running. */
    private fun overnightConditionsHold(context: Context): Boolean {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        if (hour < OVERNIGHT_START_HOUR || hour >= OVERNIGHT_END_HOUR) return false
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as android.os.BatteryManager
        if (!bm.isCharging) return false
        return LettaEnvironmentService.isEnvironmentIdle()
    }

    // ---- install -------------------------------------------------------------

    /**
     * Open the system package installer for the downloaded APK. When the
     * one-time "install unknown apps" grant is missing, routes to the
     * per-app grant settings page instead (the grant is a user decision;
     * the app can only ask). Signature safety is enforced by Android itself:
     * an install with a mismatched signing certificate is refused.
     */
    fun install(context: Context) {
        val app = context.applicationContext
        val p = prefs(app)
        if (!p.getBoolean(PREF_DL_DONE, false)) return
        val file = downloadedFile(app) ?: return
        if (!file.exists()) return
        if (!app.packageManager.canRequestPackageInstalls()) {
            runCatching {
                app.startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:${app.packageName}")
                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
            return
        }
        val uri = FileProvider.getUriForFile(app, "${app.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        // targetSdk 28: background activity starts are allowed, so the
        // service context can open the installer for the overnight path.
        runCatching { app.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    private fun downloadedFile(context: Context): File? {
        val tag = prefs(context).getString(PREF_UPDATE_TAG, null) ?: return null
        val dir = File(context.getExternalFilesDir(null), "updates")
        return File(dir, "letta-environment-$tag.apk")
    }
}
