package com.angussoftware.letta.env

import android.content.Context
import android.util.Log
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable

/**
 * Phone→watch API-key handoff over the Wear OS DataClient layer.
 *
 * The key is small, the channel is the platform's phone↔watch pipe (BT or
 * network), and DataItem writes are delivered + de-duplicated by the OS —
 * unique paths per send so a resend is never swallowed as "unchanged".
 *
 * Security posture: the DataApi channel is app-scoped on this device pair;
 * nothing leaves the local wearable link. Same trust domain as typing the
 * key on the watch directly.
 */
object WatchKeySync {
    const val TAG = "WatchKeySync"

    /**
     * In-process delivery signal: fired by the listener when a key lands.
     * The listener service and MainActivity share this process, so an
     * already-RESUMED onboarding screen can be notified directly — the
     * onResume reconcile only fires when the activity was paused, which
     * is exactly the foreground case that silently missed syncs before.
     * Dependency-free callback, main-thread posted (listener runs on its
     * service thread; UI must mutate on main).
     */
    var onKeyDelivered: (() -> Unit)? = null
    fun notifyKeyDelivered() {
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            onKeyDelivered?.invoke()
        }
    }
    const val KEY_PATH = "/beamdown_key_sync"
    const val KEY_FIELD = "api_key"
    const val ENV_FIELD = "env_name"
    const val TS_FIELD = "sent_at"

    /** Capability the watch app advertises (res/xml/wear_capabilities.xml). */
    const val WATCH_CAPABILITY = "beamdown_watch"

    /** Three states the send button can honestly report. */
    enum class WatchState { LINKED, CONNECTED_NOT_LINKED, NO_WATCH }

    /**
     * Phone side: probe the real state of the wearable link.
     *
     * CapabilityClient answers from cache and only knows apps that already
     * registered; NodeClient.getConnectedNodes() reports the live GMS Wear
     * link (BT or network relay) — what DataClient delivery rides on.
     * A paired-but-unlinked watch returns zero nodes; a DataItem queued
     * then has no guaranteed carrier, so we treat it as NO_WATCH.
     */
    fun probeWatch(context: Context, onResult: (WatchState) -> Unit) {
        Wearable.getNodeClient(context).connectedNodes.apply {
            addOnSuccessListener { list ->
                val state = if (list.isEmpty()) WatchState.NO_WATCH else WatchState.LINKED
                Log.i(TAG, "probe: ${list.size} node(s), nearby=${list.any { it.isNearby }} -> $state")
                onResult(state)
            }
            addOnFailureListener { e ->
                Log.w(TAG, "node probe failed: ${e.message}")
                onResult(WatchState.NO_WATCH)
            }
        }
    }

    /** Phone side: push the key (+ env name) to the connected watch. */
    fun sendKey(context: Context, apiKey: String, envName: String, onResult: (Boolean, String) -> Unit) {
        val client = Wearable.getDataClient(context)
        val put = PutDataMapRequest.create("$KEY_PATH/${System.currentTimeMillis()}").apply {
            dataMap.putString(KEY_FIELD, apiKey)
            dataMap.putString(ENV_FIELD, envName)
            dataMap.putLong(TS_FIELD, System.currentTimeMillis())
            setUrgent()
        }
        client.putDataItem(put.asPutDataRequest()).apply {
            addOnSuccessListener {
                Log.i(TAG, "key sync sent")
                onResult(true, "Sent to watch")
            }
            addOnFailureListener { e ->
                Log.w(TAG, "key sync failed: ${e.message}")
                onResult(false, "No watch reachable — is it paired and nearby? (${e.message})")
            }
        }
    }
}

/**
 * Watch side: receives key pushes, saves them as if typed in onboarding.
 * Only meaningful on the watch (phone never receives), safe to register
 * everywhere — the service only reacts to our path prefix.
 */
class WatchKeySyncListenerService : com.google.android.gms.wearable.WearableListenerService() {
    override fun onDataChanged(events: DataEventBuffer) {
        // Security (adversarial review Oct 8 + Angus review 2726): the uid
        // bind-gate attempted here was INERT — GMS delivers onDataChanged via
        // a HandlerThread Runnable (zzag.zze in play-services-wearable 18.2.0),
        // so Binder.getCallingUid() inside the callback is always OUR uid for
        // both legit and forged events. Caller-identity gating is impossible
        // from a WearableListenerService subclass (onBind is final).
        //
        // Working model instead:
        //  1. Sink validation (shape-checked key/env — kills script payloads).
        //  2. USER CONFIRMATION before any pref write: a forged event with a
        //     GENUINE-shaped key can still redirect the agent to an attacker's
        //     Letta account; only the human accepting the last-4 on screen
        //     distinguishes "my phone sent my key" from "someone else's key".
        // The confirmation activity lives in MainActivity (KEY_CONFIRM extra);
        // this service stages the candidate + forwards, never saves directly.
        events.forEach { ev ->
            if (ev.type != DataEvent.TYPE_CHANGED) return@forEach
            val item = ev.dataItem
            if (!item.uri.path.orEmpty().startsWith(WatchKeySync.KEY_PATH)) return@forEach
            val map = DataMapItem.fromDataItem(item).dataMap
            val key = LettaEnvironmentService.validatedKey(map.getString(WatchKeySync.KEY_FIELD)) ?: run {
                Log.w(WatchKeySync.TAG, "key-sync payload failed key-shape validation — discarded")
                return@forEach
            }
            val env = LettaEnvironmentService.validatedEnv(map.getString(WatchKeySync.ENV_FIELD))
                ?: LettaEnvironmentService.DEFAULT_ENV
            // STAGE (not save): pending confirmation in prefs. The confirm
            // dialog in MainActivity shows the key's last 4 chars; only its
            // Accept button commits PREF_KEY/PREF_ENV.
            val prefs = getSharedPreferences(LettaEnvironmentService.PREFS, Context.MODE_PRIVATE)
            prefs.edit()
                .putString(PENDING_KEY, key)
                .putString(PENDING_ENV, env)
                .putLong(PENDING_AT, System.currentTimeMillis())
                .apply()
            Log.i(WatchKeySync.TAG, "key staged from phone (env=$env) — awaiting user confirmation")
            WatchKeySync.notifyKeyDelivered()
            // Forward to the confirmation dialog. The activity is the app's
            // own (non-exported launch intent + extra), so no other app can
            // inject the ACCEPT decision.
            val launch = packageManager.getLaunchIntentForPackage(packageName)
            launch?.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            launch?.putExtra(EXTRA_KEY_CONFIRM, true)
            startActivity(launch)
        }
    }

    companion object {
        const val PENDING_KEY = "pending_sync_key"
        const val PENDING_ENV = "pending_sync_env"
        const val PENDING_AT = "pending_sync_at"
        const val EXTRA_KEY_CONFIRM = "beamdown.KEY_CONFIRM"
        // Staged candidates expire — a notification-era staged key can't
        // ambush the user hours later.
        const val PENDING_TTL_MS = 5 * 60 * 1000L
    }
}
