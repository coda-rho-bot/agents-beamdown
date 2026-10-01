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
}
