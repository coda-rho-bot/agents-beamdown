package com.angussoftware.letta.env

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WatchStatusSync contract tests (cross-device update awareness, Oct 7).
 * The DataClient transport needs a device pair (hardware-verified via the
 * key-sync precedent); these pin the wire format so a rename can't silently
 * break phone↔watch compatibility — same role as WatchKeySyncTest.
 */
class WatchStatusSyncTest {
    @Test
    fun statusPathsAreStable() {
        // Manifest intent-filter matches these prefixes; changing them orphans
        // older installs (they stop receiving nudges / statuses).
        assertEquals("/beamdown_watch_status", WatchStatusSync.STATUS_PATH)
        assertEquals("/beamdown_watch_status_req", WatchStatusSync.REQ_PATH)
        assertEquals("/beamdown_watch_update_nudge", WatchStatusSync.NUDGE_PATH)
    }

    @Test
    fun fieldNamesAreStable() {
        // Wire format consumed by the phone card — cross-version compatible.
        assertEquals("app_version", WatchStatusSync.FIELD_APP_VERSION)
        assertEquals("update_tag", WatchStatusSync.FIELD_UPDATE_TAG)
        assertEquals("env_online", WatchStatusSync.FIELD_ENV_ONLINE)
        assertEquals("env_state", WatchStatusSync.FIELD_ENV_STATE)
        assertEquals("ts", WatchStatusSync.FIELD_TS)
    }

    @Test
    fun tagIsLogcatSearchable() {
        assertEquals("WatchStatusSync", WatchStatusSync.TAG)
        assertTrue(WatchStatusSync.TAG.length <= 23) // Android log tag limit
    }

    @Test
    fun pathsAreDistinctFromKeySync() {
        // Both listeners filter on path prefixes; an overlap would deliver
        // key-sync events to the status service and vice versa.
        assertTrue(WatchStatusSync.STATUS_PATH != WatchKeySync.KEY_PATH)
        assertTrue(!WatchStatusSync.NUDGE_PATH.startsWith(WatchKeySync.KEY_PATH))
        assertTrue(!WatchKeySync.KEY_PATH.startsWith(WatchStatusSync.STATUS_PATH))
    }
}
