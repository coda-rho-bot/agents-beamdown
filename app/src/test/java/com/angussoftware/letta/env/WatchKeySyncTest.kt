package com.angussoftware.letta.env

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WatchKeySync contract tests. The DataClient transport itself needs a
 * device pair (verified on hardware); these pin the wire format so a
 * rename can't silently break phone↔watch compatibility.
 */
class WatchKeySyncTest {
    @Test
    fun pathPrefixIsStable() {
        // Manifest intent-filter matches this prefix; changing it orphans
        // older watch installs (they stop receiving).
        assertEquals("/beamdown_key_sync", WatchKeySync.KEY_PATH)
    }

    @Test
    fun fieldNamesAreStable() {
        assertEquals("api_key", WatchKeySync.KEY_FIELD)
        assertEquals("env_name", WatchKeySync.ENV_FIELD)
        assertEquals("sent_at", WatchKeySync.TS_FIELD)
    }

    @Test
    fun tagIsLogcatSearchable() {
        assertEquals("WatchKeySync", WatchKeySync.TAG)
        assertTrue(WatchKeySync.TAG.length <= 23) // Android log tag limit
    }

    @Test
    fun capabilityMatchesManifestDeclaration() {
        // res/xml/wear_capabilities.xml and the phone-side query must agree;
        // a mismatch makes every check report "no watch".
        assertEquals("beamdown_watch", WatchKeySync.WATCH_CAPABILITY)
    }
}
