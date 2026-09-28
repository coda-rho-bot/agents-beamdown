package com.angussoftware.letta.env

import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import org.json.JSONArray
import org.json.JSONObject

/**
 * AgentNotificationListener — notification snapshot for `agentctl notiflist`
 * (docs/telemetry-spec.md §2).
 *
 * Grant = user special-access toggle (Settings > Notifications > Advanced >
 * Notification history and access) — no runtime dialog exists. Status truth
 * is the enabled_notification_listeners Secure setting, NOT the connect
 * callback (Samsung fires onListenerConnected before the grant is real after
 * reinstalls — SO #71635731 pattern).
 *
 * Keeps a companion ring of the last 50 active notifications refreshed on
 * post/remove. Reading message content is privacy-peer to `screen`, so the
 * agentctl wire command (notiflist) is session-gated in the a11y service —
 * this class only maintains the snapshot.
 */
class AgentNotificationListener : NotificationListenerService() {

    companion object {
        @Volatile private var snapshot: List<JSONObject> = emptyList()
        @Volatile private var snapshotAt: Long = 0

        /** Latest snapshot for the command channel. */
        fun current(): JSONObject = JSONObject()
            .put("ok", snapshot.isNotEmpty())
            .put("count", snapshot.size)
            .put("snapshotAgeSec", if (snapshotAt == 0L) null
                else (System.currentTimeMillis() - snapshotAt) / 1000)
            .put("items", JSONArray(snapshot))

        /** Truth for status UI: is the listener granted in Secure settings? */
        fun granted(ctx: Context): Boolean {
            val raw = Settings.Secure.getString(ctx.contentResolver,
                "enabled_notification_listeners") ?: return false
            return raw.split(':').any {
                ComponentName.unflattenFromString(it)?.packageName == ctx.packageName
            }
        }

        /** Recovery for the known Samsung vanish-from-list quirk (SO #65708992):
         *  if granted-but-unbound, ask the system to rebind. */
        fun requestRebindIfStale(ctx: Context) {
            if (granted(ctx)) {
                requestRebind(ComponentName(ctx, AgentNotificationListener::class.java))
            }
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        refresh(activeNotifications?.toList() ?: emptyList())
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        refresh(activeNotifications?.toList() ?: emptyList())
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        refresh(activeNotifications?.toList() ?: emptyList())
    }

    private fun refresh(all: List<StatusBarNotification>) {
        val items = all
            .filter { it.packageName != packageName } // never our own
            .take(50)
            .map { sbn ->
                val ex = sbn.notification?.extras
                val title = ex?.getCharSequence(android.app.Notification.EXTRA_TITLE)?.toString()
                val text = ex?.getCharSequence(android.app.Notification.EXTRA_TEXT)?.toString()
                JSONObject()
                    .put("pkg", sbn.packageName)
                    .put("postedAt", sbn.postTime)
                    .apply {
                        title?.takeIf { it.isNotBlank() }?.let { put("title", it.take(200)) }
                        text?.takeIf { it.isNotBlank() }?.let { put("text", it.take(200)) }
                    }
            }
        snapshot = items
        snapshotAt = System.currentTimeMillis()
    }
}
