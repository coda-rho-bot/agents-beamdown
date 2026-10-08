package com.angussoftware.letta.env

import android.content.Context
import android.util.Log
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import org.json.JSONObject

/**
 * Watch→phone status heartbeat + phone→watch update nudge over the Wear OS
 * DataClient layer (same channel family as [WatchKeySync], inverse direction).
 *
 * Solves the cross-device update blind spot (Oct 7, Harry): the phone updates
 * itself and never mentions the watch, because each device only knows its own
 * update state. Protocol:
 *
 *  1. WATCH publishes `/beamdown_watch_status` (its app version + the newest
 *     update tag it knows about from its own feed checks).
 *  2. PHONE requests a refresh (`/beamdown_watch_status_req`) when its Watch
 *     card renders, and shows the result: watch version, update available.
 *  3. PHONE "Update watch" button sends `/beamdown_watch_update_nudge` → the
 *     watch listener force-checks the feed and foregrounds the app so the
 *     user sees the update card and taps Download/Install. The watch drives
 *     its own download+install (its updater is local-only by design).
 *
 * DataItem writes are OS-de-duplicated on content — status publishes use a
 * monotonic `ts` field so a re-publish with identical content still delivers
 * (a stale `update_tag`-is-null heartbeat would otherwise stick forever).
 * Nudges use unique paths (same pattern as key sync).
 */
object WatchStatusSync {
    const val TAG = "WatchStatusSync"

    /**
     * In-process signal for the phone UI: the watch re-published its status
     * (or a nudge round-trip completed). MainActivity re-renders the Watch
     * card on main thread — same pattern as WatchKeySync.onKeyDelivered.
     */
    var onStatusChanged: (() -> Unit)? = null
    fun notifyPhoneStatusChanged() {
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            onStatusChanged?.invoke()
        }
    }


    // Stable paths — the listener's manifest intent-filter matches these
    // prefixes; a rename orphans older installs (same contract as key sync).
    const val STATUS_PATH = "/beamdown_watch_status"
    const val REQ_PATH = "/beamdown_watch_status_req"
    const val NUDGE_PATH = "/beamdown_watch_update_nudge"

    // Status DataMap fields (wire format — cross-version compatible).
    const val FIELD_APP_VERSION = "app_version"
    const val FIELD_UPDATE_TAG = "update_tag"
    const val FIELD_ENV_ONLINE = "env_online"
    const val FIELD_ENV_STATE = "env_state"
    const val FIELD_TS = "ts"

    /** Watch: publish current status (app version, known update, env state). */
    fun publishStatus(context: Context) {
        val app = context.applicationContext
        // Phone never publishes — it's the listener side (watch→phone only).
        if (!app.packageManager.hasSystemFeature(
                android.content.pm.PackageManager.FEATURE_WATCH)) return
        val prefs = app.getSharedPreferences(LettaEnvironmentService.PREFS, Context.MODE_PRIVATE)
        val statusFile = java.io.File(app.filesDir, "status.txt")
        val rawStatus = if (statusFile.exists()) statusFile.readText() else ""
        val payload = JSONObject().apply {
            put(FIELD_APP_VERSION, BuildConfig.VERSION_NAME)
            put(FIELD_UPDATE_TAG, prefs.getString(UpdateManager.PREF_UPDATE_TAG, null) ?: JSONObject.NULL)
            put(FIELD_ENV_ONLINE, rawStatus.contains("online"))
            put(FIELD_ENV_STATE, rawStatus.lineSequence()
                .firstOrNull { it.startsWith("state=") }?.removePrefix("state=") ?: "unknown")
            put(FIELD_TS, System.currentTimeMillis())
        }
        val put = PutDataMapRequest.create(STATUS_PATH).apply {
            dataMap.putString("json", payload.toString())
            setUrgent()
        }
        Wearable.getDataClient(app).putDataItem(put.asPutDataRequest()).apply {
            addOnSuccessListener { Log.i(TAG, "status published (v${BuildConfig.VERSION_NAME})") }
            addOnFailureListener { e -> Log.w(TAG, "status publish failed: ${e.message}") }
        }
    }

    /** Phone: ask the watch to re-publish (its answer arrives via DataItem). */
    fun requestStatus(context: Context) {
        val put = PutDataMapRequest.create("$REQ_PATH/${System.currentTimeMillis()}").apply {
            dataMap.putLong(FIELD_TS, System.currentTimeMillis())
            setUrgent()
        }
        Wearable.getDataClient(context).putDataItem(put.asPutDataRequest()).apply {
            addOnSuccessListener { Log.i(TAG, "status request sent") }
            addOnFailureListener { e -> Log.w(TAG, "status request failed: ${e.message}") }
        }
    }

    /**
     * Phone: nudge the watch to check for updates now and surface the update
     * card. Idempotent per tap (unique path per send).
     */
    fun sendUpdateNudge(context: Context) {
        val put = PutDataMapRequest.create("$NUDGE_PATH/${System.currentTimeMillis()}").apply {
            dataMap.putLong(FIELD_TS, System.currentTimeMillis())
            setUrgent()
        }
        Wearable.getDataClient(context).putDataItem(put.asPutDataRequest()).apply {
            addOnSuccessListener { Log.i(TAG, "update nudge sent") }
            addOnFailureListener { e -> Log.w(TAG, "update nudge failed: ${e.message}") }
        }
    }

    /**
     * Phone: parse the watch's last-published status DataItem, if present.
     * Returns null when the watch has never published (fresh install).
     * Synchronous (blocking) — call off the main thread. N2 (review 2698):
     * GMS Task listeners default to the main-thread executor, so a main-thread
     * caller would deadlock until the 10s latch cap — the off-main contract
     * is load-bearing (single call site: daemon thread in
     * MainActivity.probeWatchStatus).
     */
    fun parseStatusBlocking(client: DataClient): JSONObject? {
        // F3/F4 (review 2698): buffer released in finally (a parse throw inside
        // the loop must not leak the DataItemBuffer), and the query URI carries
        // an explicit "*" authority — DataClient treats a host-less wear URI's
        // path as undefined per current docs.
        val buf = try {
            client.getDataItems(
                android.net.Uri.Builder().scheme("wear").authority("*").path(STATUS_PATH).build()
            ).await()
        } catch (e: Exception) {
            Log.w(TAG, "status read failed: ${e.message}")
            return null
        }
        try {
            var out: JSONObject? = null
            for (item in buf) {
                val map = DataMapItem.fromDataItem(item).dataMap
                val json = map.getString("json") ?: continue
                out = JSONObject(json)
            }
            return out
        } catch (e: Exception) {
            Log.w(TAG, "status parse failed: ${e.message}")
            return null
        } finally {
            buf.release()
        }
    }

    /** GMS Task await without the kotlin-coroutines dependency (API 28+). */
    private fun <T> com.google.android.gms.tasks.Task<T>.await(): T {
        val latch = java.util.concurrent.CountDownLatch(1)
        var value: T? = null
        var error: java.lang.Exception? = null
        addOnSuccessListener { value = it; latch.countDown() }
        addOnFailureListener { error = it; latch.countDown() }
        latch.await(10, java.util.concurrent.TimeUnit.SECONDS)
        error?.let { throw it }
        @Suppress("UNCHECKED_CAST")
        return value as T
    }
}

/**
 * Watch side: answers status requests, reacts to update nudges.
 * Phone-side listener re-parses on every DataEvent so the card stays live.
 * Registered everywhere; each side only reacts to its own paths.
 */
class WatchStatusSyncListenerService : com.google.android.gms.wearable.WearableListenerService() {
    override fun onDataChanged(events: DataEventBuffer) {
        // Security (adversarial review Oct 8): exported service — reject any
        // direct bind whose caller is not Google Play services (same gate as
        // the key-sync listener; forged events could otherwise drive the
        // foreground/Watch-card paths).
        val gmsUid = packageManager.getPackageUid("com.google.android.gms", 0)
        val binderUid = android.os.Binder.getCallingUid()
        if (gmsUid != -1 && binderUid != gmsUid && binderUid != android.os.Process.myUid()) {
            Log.w(WatchStatusSync.TAG, "status-sync bind rejected: uid $binderUid is not GMS")
            return
        }
        val isWatch = packageManager.hasSystemFeature(
            android.content.pm.PackageManager.FEATURE_WATCH
        )
        events.forEach { ev ->
            if (ev.type != DataEvent.TYPE_CHANGED) return@forEach
            val path = ev.dataItem.uri.path.orEmpty()
            val app = applicationContext
            when {
                // Watch: status request → publish now (phone is waiting).
                isWatch && path.startsWith(WatchStatusSync.REQ_PATH) -> {
                    Log.i(WatchStatusSync.TAG, "status request from phone — publishing")
                    WatchStatusSync.publishStatus(app)
                }
                // Watch: update nudge → force feed check + foreground the app
                // at the update card. N1 (review 2698): no maybeNotify here —
                // it read pre-check prefs (stale); maybeCheckAsync itself
                // notifies on new-tag discovery, and the foreground intent is
                // what surfaces the card.
                isWatch && path.startsWith(WatchStatusSync.NUDGE_PATH) -> {
                    Log.i(WatchStatusSync.TAG, "update nudge from phone")
                    UpdateManager.maybeCheckAsync(app, force = true)
                    runCatching {
                        val launch = packageManager.getLaunchIntentForPackage(packageName)
                        launch?.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                        launch?.let { startActivity(it) }
                    }
                }
                // Phone: watch status changed → re-render the card if open.
                // N5 (review 2698): exact-path match, not prefix — a
                // /beamdown_watch_status_req/<ts> echo must not trigger this.
                !isWatch && path == WatchStatusSync.STATUS_PATH -> {
                    WatchStatusSync.notifyPhoneStatusChanged()
                }
            }
        }
    }
}
