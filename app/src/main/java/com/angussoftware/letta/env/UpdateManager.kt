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
 * Install: ACTION_INSTALL_PACKAGE with a FileProvider URI (the dedicated
 * install action — a generic ACTION_VIEW + APK MIME lets file managers
 * register as handlers and hijack the tap into an "Open with" chooser).
 * Android refuses mismatched signatures at install time,
 * so a hijacked feed can't install a different app — signature safety is
 * free. Background activity starts: the background-start restriction keys
 * on the DEVICE's Android version (Android 10+ blocks background starts
 * regardless of targetSdk — targetSdk 28 does NOT exempt an app on a modern
 * device). On modern devices the overnight auto path relies on the
 * accessibility-service exemption: an app with an active, bound
 * AccessibilityService is allowed to start activities from the background
 * (the service must be enabled, and Android can revoke it). On pre-10
 * devices targetSdk 28 keeps the legacy path. Either way the system
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
    const val PREF_UPDATE_SHA_URL = "update_sha_url"   // .sha256 sidecar URL (set 4)
    const val PREF_DISCOVERED_SHA = "update_discovered_sha256" // sidecar hex resolved at discovery (set 4, review 2738 B1)
    const val PREF_UPDATE_MODE = "update_mode"         // MODE_PROMPT | MODE_AUTO_OVERNIGHT
    const val PREF_LAST_CHECK = "update_last_check_ms"
    const val PREF_DL_ID = "update_dl_id"              // DownloadManager enqueue id
    const val PREF_DL_DONE = "update_dl_done"          // download reached SUCCESSFUL
    const val PREF_DL_TAG = "update_dl_tag"            // tag the persisted download belongs to
    const val PREF_INSTALL_FIRED_FOR = "update_install_fired_for" // auto path: installer already opened for this tag (user cancel ≠ ask again every tick)
    const val PREF_NOTIF_FREQ = "update_notif_freq"     // FREQ_ONCE | FREQ_DAILY | FREQ_3DAY | FREQ_OFF
    const val PREF_NOTIF_TAG = "update_notif_tag"      // tag the last update notification was posted for
    const val PREF_NOTIF_AT = "update_notif_at"        // epoch ms of the last update notification

    const val MODE_PROMPT = "prompt"
    const val MODE_AUTO_OVERNIGHT = "auto_overnight"

    // Update-notification reminder frequencies (in-app setting row).
    const val FREQ_OFF = "off"
    const val FREQ_ONCE = "once"
    const val FREQ_DAILY = "daily"
    const val FREQ_3DAY = "every_3_days"
    val FREQ_ORDER = listOf(FREQ_OFF, FREQ_ONCE, FREQ_DAILY, FREQ_3DAY)

    // Public releases-only mirror (release pipeline dual-publishes here).
    // Anonymous fetch — no token needed for release builds. Canonical path
    // carries the rebrand; the legacy letta-environment path stays served by
    // a symlink so existing installs keep updating (audit fix 3).
    const val FEED_URL =
        "https://dl.angussoftware.dev/agents-beamdown/latest.json"

    // Neutral User-Agent for the feed check (audit fix 1): the implicit
    // Dalvik UA ("Dalvik/x.y (Linux; U; Android <ver>; <model> ...)")
    // fingerprints the device make/model to the feed host. App name + version
    // is the standard-neutral form — enough for server logs, no device data.
    const val USER_AGENT_PREFIX = "AgentsBeamdown/"
    private const val CHECK_INTERVAL_MS = 5 * 60 * 60 * 1000L   // spec: ~4-6h
    private const val TICK_MS = 30 * 60 * 1000L                // periodic tick granularity
    private const val OVERNIGHT_START_HOUR = 1                // 01:00 local
    private const val OVERNIGHT_END_HOUR = 5                  // 05:00 local

    private val periodicStarted = AtomicBoolean(false)
    private val checking = AtomicBoolean(false)
    private val receiverRegistered = AtomicBoolean(false)

    /** Release as published on the feed. apkUrl null = release carries no APK asset. */
    data class ReleaseInfo(val tag: String, val apkUrl: String?, val notes: String, val sha256Url: String? = null)

    private fun prefs(context: Context) =
        context.getSharedPreferences(LettaEnvironmentService.PREFS, Context.MODE_PRIVATE)

    /**
     * In-process signal for the FGS to re-render its persistent status
     * notification: fired whenever the known-update tag CHANGES (new release
     * discovered by the 30-min tick / on-open check, or cleared on
     * up-to-date). Same pattern as WatchKeySync.onKeyDelivered (PR #83/#84):
     * static nullable callback, main-looper posted, nulled by the owner's
     * teardown. Indefinite runners see the update hint without ever
     * stopping the server or opening the app.
     */
    var onStatusRerender: (() -> Unit)? = null
    private fun notifyStatusRerender() {
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            onStatusRerender?.invoke()
        }
    }

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
            var shaUrl: String? = null
            var bestSize = -1L
            val assets = obj.optJSONArray("assets")
            if (assets != null) {
                for (i in 0 until assets.length()) {
                    val a = assets.optJSONObject(i) ?: continue
                    val nm = a.optString("name")
                    if (nm.endsWith(".apk")) {
                        val size = a.optLong("size", -1)
                        if (size > bestSize) {
                            bestSize = size
                            apkUrl = a.optString("browser_download_url")
                        }
                    } else if (nm.endsWith(".apk.sha256")) {
                        shaUrl = a.optString("browser_download_url")
                    }
                }
            }
            // bestSize stays internal to picking the APK asset (PRs attach
            // exactly one; largest-wins if that changes) — the unused field
            // was dropped from ReleaseInfo (review 2738 N5).
            ReleaseInfo(tag, apkUrl, obj.optString("body"), shaUrl)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Parse the .sha256 sidecar content. release.yml writes sha256sum
     * format — "<hex>  <filename>\n" — so the hex is the FIRST
     * whitespace-separated token, not the whole line (a whole-line 64-hex
     * match rejects every real sidecar and post-B2 that would refuse all
     * installs; sha256sum -c consumes the same first-token convention).
     * Null when no valid 64-hex token leads the file.
     */
    fun parseSidecarSha(content: String): String? {
        val tok = content.trim().split(Regex("\\s+")).firstOrNull { it.isNotEmpty() } ?: return null
        return if (tok.matches(Regex("^[0-9a-fA-F]{64}$"))) tok.lowercase() else null
    }

    /**
     * Pure install gate (review 2738 B2): distinguish a genuinely legacy
     * release (no sidecar asset ever published — warn + install) from a
     * modern release whose sidecar could not be resolved at discovery
     * (fetch failure, 404, malformed) — the latter REFUSES. An on-path
     * attacker who can serve a swapped APK can trivially suppress the tiny
     * sidecar instead of defeating the hash; treating suppression as
     * "legacy" is fail-open against exactly the adversary this mechanism
     * exists for.
     */
    enum class ShaGate { LEGACY, VERIFY, REFUSE }

    fun shaGate(sidecarUrl: String?, discoveredSha: String?): ShaGate = when {
        sidecarUrl.isNullOrEmpty() -> ShaGate.LEGACY
        discoveredSha.isNullOrEmpty() -> ShaGate.REFUSE
        else -> ShaGate.VERIFY
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

    // ---- update card state machine (phone card + watch pill row) ------------

    /**
     * UI state for the update prompt, shared by the phone update card and
     * the watch update pill row (both rendered by renderUpdateCard on the
     * 2s tick). Pure so the state machine is unit-testable
     * (UpdateManagerTest.updateCardState*), including the watch parity
     * cases: the watch previously had NO card at all — its "stop to
     * update" chip promised a card that never rendered (dead end, Oct 5
     * parity audit blocker #1). Both form factors now share this machine:
     *
     *  - no update known        → HIDDEN
     *  - update, RUNNING        → HIDDEN (queued until idle — install
     *                              requires idle; the "stop to update"
     *                              chip owns the running state)
     *  - download in flight     → DOWNLOADING (any run state — once the
     *                              user taps Download we don't yank the card)
     *  - downloaded             → INSTALL
     *  - update known, IDLE     → DOWNLOAD
     */
    enum class UpdateCardState { HIDDEN, DOWNLOAD, DOWNLOADING, INSTALL }

    fun updateCardState(
        tag: String?, idle: Boolean, downloadId: Long, downloadDone: Boolean
    ): UpdateCardState = when {
        tag.isNullOrEmpty() -> UpdateCardState.HIDDEN
        downloadDone -> UpdateCardState.INSTALL
        downloadId != -1L -> UpdateCardState.DOWNLOADING
        !idle -> UpdateCardState.HIDDEN
        else -> UpdateCardState.DOWNLOAD
    }

    /**
     * True when the persisted download belongs to a different release than
     * the currently known update — the feed moved on while a download sat
     * on disk (mid-download or downloaded-but-not-installed). The stale
     * download must be cleared and the new tag re-offered; a null on
     * either side is "not stale" (no download yet / no known update).
     */
    fun isDownloadStale(dlTag: String?, updateTag: String?): Boolean =
        dlTag != null && updateTag != null && dlTag != updateTag

    /**
     * Pure notification decision (unit-tested in UpdateManagerTest): given
     * the currently known update tag, the user's reminder frequency, and the
     * dedupe state (tag last notified for, epoch ms it was notified at),
     * decide whether an "update available" notification should be posted
     * NOW.
     *
     *  - freq OFF          → never
     *  - no known update   → never
     *  - different tag     → always (first discovery of a new release —
     *                        including the very first one)
     *  - same tag, ONCE    → never (already told about this release)
     *  - same tag, DAILY   → re-notify when 24h elapsed since last notify
     *  - same tag, 3DAY    → re-notify when 72h elapsed
     *  - same tag, unknown freq → treat as OFF (never notify on garbage)
     */
    fun shouldNotify(
        updateTag: String?, freq: String, lastNotifiedTag: String?, lastNotifiedAt: Long, now: Long
    ): Boolean {
        if (updateTag.isNullOrEmpty()) return false
        // Validate the frequency FIRST: an unknown pref value (manual edit,
        // migration gap) is treated as Off — silent, never a guess.
        when (freq) {
            FREQ_ONCE, FREQ_DAILY, FREQ_3DAY -> Unit
            else -> return false // FREQ_OFF and anything unrecognized
        }
        if (updateTag != lastNotifiedTag) return true
        return when (freq) {
            FREQ_ONCE -> false
            FREQ_DAILY -> now - lastNotifiedAt >= 24 * 60 * 60 * 1000L
            FREQ_3DAY -> now - lastNotifiedAt >= 3 * 24 * 60 * 60 * 1000L
            else -> false
        }
    }

    // ---- feed check ----------------------------------------------------------

    /**
     * Fetch the .sha256 sidecar (small text) and return the expected hex,
     * or null when the sidecar is unreachable/malformed. Runs ONLY from
     * background threads (discovery pass); the discovery path decides what
     * a null means (shaGate), never enqueue.
     */
    private fun fetchSidecarHex(url: String): String? {
        return runCatching {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 15_000; conn.readTimeout = 15_000
            try {
                if (conn.responseCode != 200) return null
                parseSidecarSha(conn.inputStream.bufferedReader().use { it.readText() })
            } finally {
                conn.disconnect()
            }
        }.getOrNull()
    }

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
                conn.setRequestProperty("User-Agent", USER_AGENT_PREFIX + BuildConfig.VERSION_NAME)
                val token = BuildConfig.UPDATE_FEED_TOKEN
                if (token.isNotEmpty()) {
                    conn.setRequestProperty("Authorization", "token $token")
                }
                if (conn.responseCode == 200) {
                    val body = conn.inputStream.bufferedReader().use { it.readText() }
                    val rel = parseLatestRelease(body)
                    if (rel != null && rel.apkUrl != null) {
                        if (isNewer(rel.tag, BuildConfig.VERSION_NAME)) {
                            val wasTag = prefs(app).getString(PREF_UPDATE_TAG, null)
                            // Set 4 / review 2738 B1: resolve the expected
                            // sha256 HERE — background thread, and it freezes
                            // the expectation at discovery so a feed flip
                            // between check and download can't touch it.
                            // Empty string = sidecar URL absent (legacy) is
                            // represented by an empty PREF_UPDATE_SHA_URL;
                            // URL present but hex unresolvable persists ""
                            // (shaGate REFUSE at install — fail closed).
                            val shaHex = rel.sha256Url?.let { fetchSidecarHex(it) }.orEmpty()
                            prefs(app).edit()
                                .putString(PREF_UPDATE_TAG, rel.tag)
                                .putString(PREF_UPDATE_URL, rel.apkUrl)
                                .putString(PREF_UPDATE_SHA_URL, rel.sha256Url ?: "")
                                .putString(PREF_DISCOVERED_SHA, shaHex)
                                .apply()
                            // First discovery of a new tag → notify promptly
                            // (don't wait for the next 30-min periodic tick)
                            // and re-render the FGS status notification so
                            // indefinite runners see it in place.
                            if (wasTag != rel.tag) {
                                maybeNotify(app)
                                notifyStatusRerender()
                            }
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
                maybeNotify(app)
                // Watch heartbeat (Oct 7): re-publish cross-device status each
                // tick so the phone's Watch card reflects this device even
                // while its screen is off. publishStatus self-guards: phone
                // side skips (it's the listener), no-link writes fail silently.
                if (app.packageManager.hasSystemFeature(
                        android.content.pm.PackageManager.FEATURE_WATCH)) {
                    runCatching { WatchStatusSync.publishStatus(app) }
                }
            }
        }.apply { isDaemon = true }.start()
    }

    // ---- update-available notifications --------------------------------------

    const val NOTIF_CHANNEL_ID = "letta-env-app-updates"
    private const val NOTIF_ID_UPDATES = 9001

    /**
     * Create (idempotent) the "App updates" notification channel. Users can
     * disable update notifications at the OS level by turning this channel
     * off — that IS the channel control. IMPORTANCE_DEFAULT: visible +
     * no persistent heads-up nagging.
     */
    fun createNotifChannel(context: Context) {
        if (android.os.Build.VERSION.SDK_INT < 26) return
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE)
            as android.app.NotificationManager
        val ch = android.app.NotificationChannel(
            NOTIF_CHANNEL_ID, "App updates", android.app.NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "A new Agents Beamdown version is available"
            setShowBadge(false)
        }
        nm.createNotificationChannel(ch)
    }

    /**
     * Post the "update available" notification when [shouldNotify] says so
     * (dedupe on (tag, last-notified-at) per the user's reminder frequency).
     * Called from the 30-min periodic tick and after each feed check that
     * discovers a newer tag. Tapping opens the app.
     */
    fun maybeNotify(context: Context) {
        val app = context.applicationContext
        val p = prefs(app)
        val tag = p.getString(PREF_UPDATE_TAG, null) ?: return
        val freq = p.getString(PREF_NOTIF_FREQ, FREQ_ONCE) ?: FREQ_ONCE
        val now = System.currentTimeMillis()
        if (!shouldNotify(
                tag, freq,
                p.getString(PREF_NOTIF_TAG, null),
                p.getLong(PREF_NOTIF_AT, 0),
                now
            )
        ) return
        createNotifChannel(app)
        val nm = app.getSystemService(Context.NOTIFICATION_SERVICE)
            as android.app.NotificationManager
        val pi = android.app.PendingIntent.getActivity(
            app, 0,
            android.content.Intent(app, MainActivity::class.java)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or
                android.app.PendingIntent.FLAG_IMMUTABLE
        )
        val n = android.app.Notification.Builder(app, NOTIF_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle("Update available")
            .setContentText("Agents Beamdown $tag — open the app to download and install")
            .setStyle(android.app.Notification.BigTextStyle()
                .bigText("Agents Beamdown $tag — open the app to download and install"))
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()
        runCatching { nm.notify(NOTIF_ID_UPDATES, n) } // POST_NOTIFICATIONS not granted → skip silently
        p.edit()
            .putString(PREF_NOTIF_TAG, tag)
            .putLong(PREF_NOTIF_AT, now)
            .apply()
    }

    /** Reset notification dedupe state (called when availability clears). */
    private fun clearNotifState(context: Context) {
        prefs(context).edit()
            .remove(PREF_NOTIF_TAG)
            .remove(PREF_NOTIF_AT)
            .apply()
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
     * Stale-download supersede: when the persisted download belongs to a
     * tag the feed has since replaced, drop it (file + state) and return
     * true so callers re-offer the new tag. Returns false when there is
     * nothing to supersede.
     */
    private fun supersedeStaleDownload(context: Context): Boolean {
        val p = prefs(context)
        val stale = isDownloadStale(p.getString(PREF_DL_TAG, null), p.getString(PREF_UPDATE_TAG, null))
        if (stale) clearDownload(context)
        return stale
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
        if (supersedeStaleDownload(app)) return
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
        val hadUpdate = !p.getString(PREF_UPDATE_TAG, null).isNullOrEmpty()
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
            .remove(PREF_UPDATE_SHA_URL)
            .remove(PREF_DISCOVERED_SHA)
            .remove(PREF_DL_ID)
            .remove(PREF_DL_DONE)
            .remove(PREF_DL_TAG)
            .remove(PREF_INSTALL_FIRED_FOR)
            .apply()
        if (hadUpdate) notifyStatusRerender()
        // Update installed/withdrawn: drop the posted notification and the
        // dedupe state so the NEXT release notifies from scratch.
        clearNotifState(app)
        runCatching {
            (app.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager)
                .cancel(NOTIF_ID_UPDATES)
        }
    }

    // ---- download ------------------------------------------------------------

    /**
     * Clear any persisted download (cancel in-flight, delete file, drop
     * prefs) WITHOUT touching the known-update state — used when the
     * download turns out to be for a stale tag: the new tag is then
     * re-offered fresh. [clearAvailable] stays the full-reset for when
     * no update is known at all.
     */
    fun clearDownload(context: Context) {
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
            .remove(PREF_DL_ID)
            .remove(PREF_DL_DONE)
            .remove(PREF_DL_TAG)
            .apply()
    }

    /**
     * Enqueue the APK download via DownloadManager. False when no URL known.
     *
     * [allowMetered]: a USER-initiated download is explicit consent to spend
     * data — never hard-lock it on Wi-Fi (field feedback Oct 6: "downloading…
     * forever" on cellular with no way forward read as a broken app). The
     * metered guard stays for every NON-interactive path: the overnight
     * auto-update and feed-triggered prefetches keep allowMetered=false.
     */
    fun enqueueDownload(context: Context, allowMetered: Boolean = false): Boolean {
        val app = context.applicationContext
        val p = prefs(app)
        val url = p.getString(PREF_UPDATE_URL, null) ?: return false
        val tag = p.getString(PREF_UPDATE_TAG, null) ?: return false
        // Set 4 / review 2738 B1: ZERO network here. The expected hex was
        // resolved on the background discovery pass (maybeCheckAsync →
        // PREF_DISCOVERED_SHA); enqueue is called from the UI thread
        // (MainActivity.onUpdateButtonTap) and a synchronous fetch there is
        // NetworkOnMainThreadException — swallowed → empty pref → silent
        // unverified installs. Enqueue also works offline now; the sha
        // decision happens at install (shaGate).
        val req = DownloadManager.Request(Uri.parse(url))
            .setTitle("Agents Beamdown $tag")
            .setDescription("App update")
            .setAllowedOverMetered(allowMetered)
            .setDestinationInExternalFilesDir(app, null, "updates/letta-environment-$tag.apk")
        val id = (app.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(req)
        p.edit()
            .putLong(PREF_DL_ID, id)
            .putBoolean(PREF_DL_DONE, false)
            .putString(PREF_DL_TAG, tag)
            .apply()
        registerCompleteReceiver(app)
        return true
    }

    /**
     * Why a download is stalled, for honest UI: when DownloadManager has the
     * request PAUSED (metered guard waiting for Wi-Fi is the common case —
     * the APK is 347MB and setAllowedOverMetered(false) parks it on
     * cellular), the card must SAY so instead of "Downloading…" forever.
     * Null when not paused / row gone.
     */
    fun downloadPauseReason(context: Context, id: Long): String? = queryDownload(context, id) { c ->
        val status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
        if (status != DownloadManager.STATUS_PAUSED) null
        else when (c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))) {
            DownloadManager.PAUSED_WAITING_FOR_NETWORK -> "waiting for Wi-Fi"
            DownloadManager.PAUSED_QUEUED_FOR_WIFI -> "waiting for Wi-Fi"
            DownloadManager.PAUSED_WAITING_TO_RETRY -> "retrying — check connection"
            else -> "paused"
        }
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
                        // Guard is consumed ONLY if the installer intent
                        // actually fired — a missing install grant must not
                        // burn the one-shot for the night.
                        if (install(app)) {
                            p.edit().putString(PREF_INSTALL_FIRED_FOR, tag).apply()
                        }
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
        // Stale download for an older tag: supersede, then fall through —
        // id==−1 re-enqueues the CURRENT tag on this same tick.
        supersedeStaleDownload(app)
        val id = p.getLong(PREF_DL_ID, -1)
        when {
            id == -1L -> enqueueDownload(app)
            p.getBoolean(PREF_DL_DONE, false) -> {
                // Fire the installer at most ONCE per tag on the auto path —
                // a user cancelling the 2AM system dialog must not have it
                // re-opened every 30 minutes until 5AM. The card's Install
                // button stays available for a manual retry. Guard consumed
                // only on a fired intent (missing install grant doesn't burn
                // the shot — next tick retries after the user grants).
                if (p.getString(PREF_INSTALL_FIRED_FOR, null) != tag) {
                    if (install(app)) {
                        p.edit().putString(PREF_INSTALL_FIRED_FOR, tag).apply()
                    }
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
     * Open the system package installer for the downloaded APK. Returns
     * true only when the installer intent itself fired — false when the
     * one-time "install unknown apps" grant is missing (routes to the
     * per-app grant settings page instead; the grant is a user decision,
     * the app can only ask) or when preconditions fail. The auto-overnight
     * path consumes its one-shot fired-guard ONLY on a true return, so a
     * missing grant doesn't burn the night: once the user grants, the next
     * tick retries the install. Signature safety is enforced by Android
     * itself: an install with a mismatched signing certificate is refused.
     */
    fun install(context: Context): Boolean = installInternal(context)

    /**
     * UI-thread-safe entry (Uncia WILL-HURT Oct 25: hashing ~495MB on main
     * froze the app for seconds). The sha verification + file IO run on a
     * background thread; the installer intents fire from the application
     * context with NEW_TASK, which is thread-safe. Result delivered to
     * onResult on the main looper.
     */
    fun installAsync(context: Context, onResult: (Boolean) -> Unit = {}) {
        val app = context.applicationContext
        Thread {
            val ok = installInternal(app)
            android.os.Handler(android.os.Looper.getMainLooper()).post { onResult(ok) }
        }.also { it.isDaemon = true }.start()
    }

    private fun installInternal(context: Context): Boolean {
        val app = context.applicationContext
        val p = prefs(app)
        // Install-time supersede: the download on disk may be for a tag the
        // feed has since replaced — never install a stale APK. Drop it and
        // let the caller re-offer the new tag.
        if (supersedeStaleDownload(app)) return false
        if (!p.getBoolean(PREF_DL_DONE, false)) return false
        val file = downloadedFile(app) ?: return false
        if (!file.exists()) return false
        // Security (Oct 8 set 4): verify the downloaded APK against the
        // sha256 published in the feed BEFORE handing it to the installer.
        // A compromised feed/CDN serving different bytes under the same tag
        // is caught here (Android's signature check remains the last line
        // for cross-signer swaps; this catches same-signer content swaps a
        // compromised signing key or feed would enable).
        val sidecarUrl = p.getString(PREF_UPDATE_SHA_URL, null)
        val expectedSha = p.getString(PREF_DISCOVERED_SHA, null)
        when (shaGate(sidecarUrl, expectedSha)) {
            ShaGate.LEGACY ->
                // No sidecar asset on the release at all (pre-set-4
                // releases) — warn + install. Only reachable for genuinely
                // legacy releases: modern releases all publish the sidecar,
                // and a URL that WAS present but couldn't be resolved at
                // discovery is REFUSE, not legacy (review 2738 B2).
                android.util.Log.w("UpdateManager", "no sha256 sidecar for this release — installing without content verify (pre-sidecar release?)")
            ShaGate.REFUSE -> {
                // Sidecar listed but hex unresolvable at discovery (fetch
                // failure, 404, malformed). An on-path attacker serving a
                // swapped APK can suppress the tiny sidecar URL while
                // letting the big one through — treating that as legacy
                // would be fail-open against exactly this adversary.
                // Self-heal (review follow-up): clear the discovery so the
                // next 30-min tick re-discovers and re-resolves — a
                // TRANSIENT fetch failure heals in minutes instead of
                // refusing until the 5h gate. The 5h check gate must go
                // too, or the tick would honor the old timestamp and
                // never re-check.
                android.util.Log.e("UpdateManager", "sha256 sidecar unresolvable for $sidecarUrl — refusing install; clearing discovery for re-check")
                file.delete()
                p.edit().remove(PREF_UPDATE_TAG).remove(PREF_UPDATE_URL).remove(PREF_UPDATE_SHA_URL)
                    .remove(PREF_DISCOVERED_SHA).remove(PREF_LAST_CHECK)
                    .remove(PREF_DL_ID).putBoolean(PREF_DL_DONE, false).apply()
                return false
            }
            ShaGate.VERIFY -> {
                val actual = runCatching {
                    val md = java.security.MessageDigest.getInstance("SHA-256")
                    file.inputStream().use { ins ->
                        val buf = ByteArray(1 shl 20)
                        while (true) {
                            val n = ins.read(buf)
                            if (n < 0) break
                            md.update(buf, 0, n)
                        }
                    }
                    md.digest().joinToString("") { "%02x".format(it) }
                }.getOrDefault("")
                if (!actual.equals(expectedSha, ignoreCase = true)) {
                    android.util.Log.e("UpdateManager", "APK sha256 MISMATCH: expected $expectedSha got $actual — refusing install, dropping download")
                    file.delete()
                    p.edit().remove(PREF_DL_ID).putBoolean(PREF_DL_DONE, false).apply()
                    return false
                }
                android.util.Log.i("UpdateManager", "APK sha256 verified ($actual)")
            }
        }
        if (!app.packageManager.canRequestPackageInstalls()) {
            runCatching {
                app.startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:${app.packageName}")
                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
            return false // grant missing — do NOT consume the auto guard
        }
        val uri = FileProvider.getUriForFile(app, "${app.packageName}.fileprovider", file)
        // ACTION_INSTALL_PACKAGE (not ACTION_VIEW + MIME): the generic view
        // intent lets any app that registered for the APK MIME (Acode, Termux
        // on Harry's phone — Oct 7 live finding) hijack the tap into an
        // "Open with" chooser. The dedicated install action is resolvable
        // only by system package installers, so sideload updates go straight
        // to the confirm sheet with no chooser detour.
        val intent = Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        // Background-start honesty: the restriction keys on the DEVICE's
        // Android version, not targetSdk — on Android 10+ this works via the
        // a11y-service exemption (bound AgentAccessibilityService), on
        // pre-10 via the legacy path. runCatching: if the exemption doesn't
        // hold, the manual Install button in the UI is the fallback.
        return runCatching { app.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess
    }

    private fun downloadedFile(context: Context): File? {
        val tag = prefs(context).getString(PREF_UPDATE_TAG, null) ?: return null
        val dir = File(context.getExternalFilesDir(null), "updates")
        return File(dir, "letta-environment-$tag.apk")
    }
}
