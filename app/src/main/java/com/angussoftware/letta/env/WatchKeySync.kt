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
    const val KEY_PATH = "/beamdown_key_sync"
    const val KEY_FIELD = "api_key"
    const val ENV_FIELD = "env_name"
    const val TS_FIELD = "sent_at"

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
        events.forEach { ev ->
            if (ev.type != DataEvent.TYPE_CHANGED) return@forEach
            val item = ev.dataItem
            if (!item.uri.path.orEmpty().startsWith(WatchKeySync.KEY_PATH)) return@forEach
            val map = DataMapItem.fromDataItem(item).dataMap
            val key = map.getString(WatchKeySync.KEY_FIELD).orEmpty().trim()
            val env = map.getString(WatchKeySync.ENV_FIELD).orEmpty().trim()
            if (key.isNotEmpty()) {
                val prefs = getSharedPreferences(
                    LettaEnvironmentService.PREFS, Context.MODE_PRIVATE
                )
                prefs.edit()
                    .putString(LettaEnvironmentService.PREF_KEY, key)
                    .putString(LettaEnvironmentService.PREF_ENV, env.ifEmpty { LettaEnvironmentService.DEFAULT_ENV })
                    .apply()
                Log.i(WatchKeySync.TAG, "key received from phone (env=${env.ifEmpty { "default" }})")
                // If the app is sitting in onboarding (fresh install flow the
                // key sync is meant to skip), bring it forward so the user
                // sees the main screen instead of a stale key prompt.
                val launch = packageManager.getLaunchIntentForPackage(packageName)
                launch?.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(launch)
            }
        }
    }
}
