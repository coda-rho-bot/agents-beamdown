package com.angussoftware.letta.env

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Self-updater pure logic: Forgejo releases/latest parsing and semantic
 * version comparison. No Android framework types touched — these run on
 * the JVM like EnvPaletteTest.
 */
class UpdateManagerTest {

    // ---- parseLatestRelease --------------------------------------------------

    @Test
    fun parsesNormalReleaseWithApkAsset() {
        val json = """
            {"tag_name":"v0.4.0","draft":false,"prerelease":false,
             "body":"notes here",
             "assets":[{"name":"letta-environment-v0.4.0.apk","size":456130000,
                        "browser_download_url":"https://git.angussoftware.dev/coda/letta-environment-android/releases/download/v0.4.0/letta-environment-v0.4.0.apk"}]}
        """.trimIndent()
        val rel = UpdateManager.parseLatestRelease(json)!!
        assertEquals("v0.4.0", rel.tag)
        assertTrue(rel.apkUrl!!.endsWith(".apk"))
        assertEquals("notes here", rel.notes)
    }

    @Test
    fun picksLargestApkWhenMultipleAssets() {
        val json = """
            {"tag_name":"v0.4.0","draft":false,"prerelease":false,"body":"",
             "assets":[
               {"name":"sha256.txt","size":100,"browser_download_url":"https://x/sha256.txt"},
               {"name":"small.apk","size":1000,"browser_download_url":"https://x/small.apk"},
               {"name":"letta-environment-v0.4.0.apk","size":456130000,"browser_download_url":"https://x/big.apk"}]}
        """.trimIndent()
        val rel = UpdateManager.parseLatestRelease(json)!!
        assertEquals("https://x/big.apk", rel.apkUrl)
    }

    @Test
    fun releaseWithoutApkAssetYieldsNullUrl() {
        val json = """{"tag_name":"v0.4.0","draft":false,"prerelease":false,"body":"","assets":[]}"""
        val rel = UpdateManager.parseLatestRelease(json)!!
        assertEquals("v0.4.0", rel.tag)
        assertNull(rel.apkUrl)
    }

    @Test
    fun skipsDraftsAndPrereleases() {
        val draft = """{"tag_name":"v0.4.0","draft":true,"prerelease":false,"assets":[]}"""
        val pre = """{"tag_name":"v0.4.0","draft":false,"prerelease":true,"assets":[]}"""
        assertNull(UpdateManager.parseLatestRelease(draft))
        assertNull(UpdateManager.parseLatestRelease(pre))
    }

    @Test
    fun malformedJsonYieldsNull() {
        assertNull(UpdateManager.parseLatestRelease("not json at all"))
        assertNull(UpdateManager.parseLatestRelease("""{"no_tag":true}"""))
    }

    // ---- isNewer -------------------------------------------------------------

    @Test
    fun semanticCompare() {
        assertTrue(UpdateManager.isNewer("v0.4.0", "0.3.2"))
        assertTrue(UpdateManager.isNewer("0.10.0", "0.9.9"))   // numeric, not lexicographic
        assertTrue(UpdateManager.isNewer("0.3.3", "0.3.2"))
        assertFalse(UpdateManager.isNewer("0.3.2", "0.3.2"))    // equal = not newer
        assertFalse(UpdateManager.isNewer("v0.3.1", "0.3.2"))
        assertFalse(UpdateManager.isNewer("v0.3", "0.3.2"))    // shorter lower
        assertTrue(UpdateManager.isNewer("v0.4", "0.3.9"))     // shorter higher
    }

    @Test
    fun nonNumericComponentsNeverPrompt() {
        assertFalse(UpdateManager.isNewer("banana", "0.3.2"))
        assertFalse(UpdateManager.isNewer("v0.4.0-rc1", "0.3.2"))
        assertFalse(UpdateManager.isNewer(null, "0.3.2"))
        assertFalse(UpdateManager.isNewer("", "0.3.2"))
    }

    // ---- constants (audit hardening) -----------------------------------------

    @Test
    fun feedUrlIsCanonicalAgentsBeamdownPathOverHttps() {
        // Canonical (rebrand) path, HTTPS, JSON doc — the feed URL is a
        // contract with the release pipeline's publish step; a typo here
        // silently bricks every future update check.
        assertEquals(
            "https://dl.angussoftware.dev/agents-beamdown/latest.json",
            UpdateManager.FEED_URL
        )
        assertTrue(UpdateManager.FEED_URL.startsWith("https://"))
        assertTrue(UpdateManager.FEED_URL.endsWith(".json"))
    }

    @Test
    fun userAgentPrefixIsNeutralNoDalvikFingerprint() {
        // Audit fix 1: the UA must be app name + version — it must NOT
        // leak the Dalvik device fingerprint (model/OS string). The prefix
        // is the invariant half; the runtime appends VERSION_NAME.
        assertEquals("AgentsBeamdown/", UpdateManager.USER_AGENT_PREFIX)
        assertFalse(UpdateManager.USER_AGENT_PREFIX.contains("Dalvik"))
        assertFalse(UpdateManager.USER_AGENT_PREFIX.contains("Android"))
    }

    // ---- isDownloadStale ------------------------------------------------------

    @Test
    fun downloadStaleWhenTagMovedOn() {
        assertTrue(UpdateManager.isDownloadStale("v0.4.0", "v0.4.1"))   // feed replaced the tag
        assertTrue(UpdateManager.isDownloadStale("v0.4.1", "v0.4.0"))   // known update went backwards — supersede anyway
    }

    @Test
    fun downloadNotStaleWhenTagsMatch() {
        assertFalse(UpdateManager.isDownloadStale("v0.4.0", "v0.4.0"))
    }

    @Test
    fun downloadNotStaleWhenEitherSideMissing() {
        assertFalse(UpdateManager.isDownloadStale(null, "v0.4.0"))   // no download yet
        assertFalse(UpdateManager.isDownloadStale("v0.4.0", null))   // no known update
        assertFalse(UpdateManager.isDownloadStale(null, null))
    }

    // ---- shouldNotify (update-available notification dedupe/frequency) -----

    private val DAY = 24 * 60 * 60 * 1000L

    @Test
    fun neverWhenNoUpdateKnown() {
        assertFalse(UpdateManager.shouldNotify(null, UpdateManager.FREQ_ONCE, null, 0, 1000))
        assertFalse(UpdateManager.shouldNotify("", UpdateManager.FREQ_DAILY, null, 0, 1000))
    }

    @Test
    fun neverWhenFrequencyOff() {
        assertFalse(UpdateManager.shouldNotify("v0.4.5", UpdateManager.FREQ_OFF, null, 0, 1000))
        // Even a brand-new tag stays silent on Off.
        assertFalse(UpdateManager.shouldNotify("v0.4.5", UpdateManager.FREQ_OFF, "v0.4.4", 0, 1000))
    }

    @Test
    fun firstDiscoveryOfAnyTagAlwaysNotifies() {
        // Never notified at all.
        assertTrue(UpdateManager.shouldNotify("v0.4.5", UpdateManager.FREQ_ONCE, null, 0, 1000))
        // Different tag than the last notification → new release discovered.
        assertTrue(UpdateManager.shouldNotify("v0.4.5", UpdateManager.FREQ_ONCE, "v0.4.4", 999, 1000))
        assertTrue(UpdateManager.shouldNotify("v0.4.5", UpdateManager.FREQ_DAILY, "v0.4.4", 999, 1000))
    }

    @Test
    fun onceNeverReNotifiesForSameTag() {
        assertFalse(UpdateManager.shouldNotify("v0.4.5", UpdateManager.FREQ_ONCE, "v0.4.5", 0, 1000))
        // Even after a long time — "once" means once per release.
        assertFalse(UpdateManager.shouldNotify("v0.4.5", UpdateManager.FREQ_ONCE, "v0.4.5", 0, 100 * DAY))
    }

    @Test
    fun dailyReNotifiesAfter24h() {
        val t0 = 1_000_000L
        assertFalse(UpdateManager.shouldNotify("v0.4.5", UpdateManager.FREQ_DAILY, "v0.4.5", t0, t0 + DAY - 1))   // just short
        assertTrue(UpdateManager.shouldNotify("v0.4.5", UpdateManager.FREQ_DAILY, "v0.4.5", t0, t0 + DAY))        // exactly 24h
        assertTrue(UpdateManager.shouldNotify("v0.4.5", UpdateManager.FREQ_DAILY, "v0.4.5", t0, t0 + 5 * DAY))    // long past
    }

    @Test
    fun threeDayReNotifiesAfter72h() {
        val t0 = 1_000_000L
        assertFalse(UpdateManager.shouldNotify("v0.4.5", UpdateManager.FREQ_3DAY, "v0.4.5", t0, t0 + 3 * DAY - 1))
        assertTrue(UpdateManager.shouldNotify("v0.4.5", UpdateManager.FREQ_3DAY, "v0.4.5", t0, t0 + 3 * DAY))
        assertTrue(UpdateManager.shouldNotify("v0.4.5", UpdateManager.FREQ_3DAY, "v0.4.5", t0, t0 + 10 * DAY))
    }

    @Test
    fun dailyDoesNotFireOn3DayBoundaryEarly() {
        // Daily must not wait 3 days, and 3-day must not fire at 24h.
        val t0 = 1_000_000L
        assertFalse(UpdateManager.shouldNotify("v0.4.5", UpdateManager.FREQ_3DAY, "v0.4.5", t0, t0 + DAY))
        assertTrue(UpdateManager.shouldNotify("v0.4.5", UpdateManager.FREQ_DAILY, "v0.4.5", t0, t0 + DAY))
    }

    @Test
    fun unknownFrequencyTreatedAsSilent() {
        // Garbage pref value (manual edit, migration gap) → never notify.
        assertFalse(UpdateManager.shouldNotify("v0.4.5", "weekly", "v0.4.4", 0, 1000))
        assertFalse(UpdateManager.shouldNotify("v0.4.5", "weekly", null, 0, 1000))
    }

    @Test
    fun frequencyOrderCoversAllValues() {
        // The settings button cycles FREQ_ORDER — every cycle step must be
        // a value shouldNotify understands (no dead states in the cycle).
        assertEquals(
            listOf(UpdateManager.FREQ_OFF, UpdateManager.FREQ_ONCE, UpdateManager.FREQ_DAILY, UpdateManager.FREQ_3DAY),
            UpdateManager.FREQ_ORDER
        )
    }
}
