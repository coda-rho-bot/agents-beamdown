package com.angussoftware.letta.env

import com.angussoftware.letta.env.LettaEnvironmentService.PinState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Keystore-backed pin decision core (Oct 8 review 2733, option A).
 *
 * The review blocker: verifyLettaJsPin trusted the agent-writable
 * package.json version to distinguish pinned vs upgraded installs — sed the
 * version + drop an implant letta.js and the pin read "upgraded, not
 * applied", patched the implant, and booted it. decidePinState is the pure
 * replacement: only a Keystore-MACed record (service-written, unforgeable by
 * the same-uid agent shell) can bless a version as genuinely upgraded.
 */
class PinStateDecisionTest {

    private val pin = "0.34.4"
    private val upgraded = "0.35.0"
    private val postPatchSha = "a".repeat(64)
    private val implantSha = "b".repeat(64)

    // ---- decision table (every row, fail-closed everywhere) ----------------

    @Test
    fun `no record - pin applies (fresh install or record deleted by attacker)`() {
        // Absent record: fresh pinned install OR attacker deleted the record
        // of a genuine upgrade. Either way the pin re-arms — deletion can
        // only restore protection, never disable it.
        assertEquals(
            PinState.PIN_APPLIES,
            LettaEnvironmentService.decidePinState(
                recordValid = false, recordVersion = "", recordSha = "",
                currentSha = "", pinVersion = pin))
    }

    @Test
    fun `mac-invalid record - pin applies (forged record ignored)`() {
        // Attacker crafts a prefs payload claiming an upgrade; the MAC never
        // verifies without the Keystore key. Fail-closed.
        assertEquals(
            PinState.PIN_APPLIES,
            LettaEnvironmentService.decidePinState(
                recordValid = false, recordVersion = upgraded, recordSha = implantSha,
                currentSha = implantSha, pinVersion = pin))
    }

    @Test
    fun `valid record matching pin version - pin applies (normal pinned install)`() {
        assertEquals(
            PinState.PIN_APPLIES,
            LettaEnvironmentService.decidePinState(
                recordValid = true, recordVersion = pin, recordSha = postPatchSha,
                currentSha = postPatchSha, pinVersion = pin))
    }

    @Test
    fun `valid upgrade record and intact letta js - upgraded unpinned`() {
        assertEquals(
            PinState.UPGRADED_UNPINNED,
            LettaEnvironmentService.decidePinState(
                recordValid = true, recordVersion = upgraded, recordSha = postPatchSha,
                currentSha = postPatchSha, pinVersion = pin))
    }

    @Test
    fun `valid upgrade record but letta js rewritten - upgraded tampered (block)`() {
        // The review-2733 attack one step later: the service DID upgrade to
        // 0.35.0, then a hostile turn swapped letta.js. The recorded
        // post-patch sha catches it — free tamper detection.
        assertEquals(
            PinState.UPGRADED_TAMPERED,
            LettaEnvironmentService.decidePinState(
                recordValid = true, recordVersion = upgraded, recordSha = postPatchSha,
                currentSha = implantSha, pinVersion = pin))
    }

    @Test
    fun `valid upgrade record with empty record sha - upgraded unpinned`() {
        // Degenerate record (no sha captured): version authority alone
        // unpins — never blocks a genuine upgrade over missing telemetry.
        assertEquals(
            PinState.UPGRADED_UNPINNED,
            LettaEnvironmentService.decidePinState(
                recordValid = true, recordVersion = upgraded, recordSha = "",
                currentSha = "", pinVersion = pin))
    }

    // ---- the review blocker itself: version sed no longer disarms the pin --

    @Test
    fun `installed version alone never unpins - verifyLettaJsPin routes through decidePinState`() {
        val t = src()
        val verify = t.substringAfter("private fun verifyLettaJsPin(")
        assertTrue(
            "verifyLettaJsPin must call decidePinState — the installed package.json " +
                "version is evidence, never authority (review 2733 blocker).",
            "decidePinState(" in verify,
        )
        assertFalse(
            "The old bypass (installed version != pin-version -> pin not applied, " +
                "early return) must be gone — that is exactly the sed-0.35.0 hole.",
            Regex("""installed \$\{?installedV\}? != pin-version""").containsMatchIn(t),
        )
        assertTrue(
            "verifyLettaJsPin must read the Keystore-backed upgrade record",
            "readUpgradeRecord()" in verify,
        )
        assertTrue(
            "MAC-invalid record must log a distinctive line (forensics)",
            "MAC INVALID" in verify,
        )
    }

    // ---- Angus ordering amendment: record AFTER patches, post-patch sha ----

    @Test
    fun `upgrade record is written after applyLettaJsPatches succeeds - post-patch sha`() {
        val t = src()
        val upg = t.substringAfter("private fun upgradeLetta(): Boolean")
        val patchAt = upg.indexOf("applyLettaJsPatches(nodeModules")
        val recordAt = upg.indexOf("writeUpgradeRecord(resolved")
        assertTrue(
            "writeUpgradeRecord(resolved, sha-of-installed-POST-PATCH-file) must come " +
                "AFTER applyLettaJsPatches in upgradeLetta — a pre-patch record " +
                "false-alarms the tamper check on every upgraded boot (Angus ordering).",
            patchAt in 0 until recordAt,
        )
        val zeroBlock = upg.indexOf("0 patch sites in freshly installed letta.js")
        assertTrue(
            "record write must follow the 0-patch-sites fail-closed return (only a " +
                "successfully patched install gets recorded)",
            zeroBlock in 0 until recordAt,
        )
        // Review 2734 (bytes-in-memory): the recorded sha must hash the
        // patcher's in-memory OUTPUT, never a post-write disk re-read.
        assertFalse(
            "upgradeLetta record sha must NOT re-read the file post-write — sha256Of(File(...)) " +
                "between writeText and record is exactly the review-2734 race (orphaned " +
                "same-uid process swaps an implant; the app MACs ATTACKER bytes).",
            Regex("""sha256Of\(File\(nodeModules, "@letta-ai/letta-code/letta\.js"\)\)""")
                .containsMatchIn(upg),
        )
        assertTrue(
            "upgradeLetta record sha must hash the patcher's in-memory output " +
                "(patched.content.toByteArray) — decision, patch, and record over one lineage",
            Regex("""sha256Of\(patched\.content\.toByteArray\(Charsets\.UTF_8\)\)""")
                .containsMatchIn(upg),
        )
    }

    // ---- review 2734 blocker: one read, one lineage (bytes-in-memory) ------

    @Test
    fun `verifyLettaJsPin reads letta js once and seals bytes into the unpinned outcome`() {
        val t = src()
        val verify = t.substringAfter("private fun verifyLettaJsPin(")
        val reads = Regex("""lettaJs\(rootfsDir\)\.readText\(\)""").findAll(verify).count()
        assertTrue(
            "verifyLettaJsPin must read letta.js exactly ONCE (found $reads) — the decision " +
                "hash and the boot-path bytes must come from one read. Separate reads are the " +
                "review-2734 race: swap an implant between them and the app MACs attacker bytes.",
            reads == 1,
        )
        assertFalse(
            "the pin decision must hash the in-memory bytes (readOnce), never the file — " +
                "sha256Of(lettaJs(rootfsDir)) as decision input is the split-read race",
            Regex("""sha256Of\(lettaJs\(rootfsDir\)\)""").containsMatchIn(verify),
        )
        assertTrue(
            "UPGRADED_UNPINNED outcome must seal the bytes (content = readOnce) so the boot " +
                "path patches the exact lineage the decision hashed",
            Regex("""PinOutcome\(true, state, pinVersion, false, record\.second, record\.third, readOnce\)""")
                .containsMatchIn(verify),
        )
    }

    @Test
    fun `boot path patches the sealed bytes - no disk re-read between verify and record`() {
        val t = src()
        val runEnv = t.substringAfter("private fun runEnvironment(")
        assertTrue(
            "boot path must take the bytes from pinOutcome.content (the sealed lineage)",
            "val unpinnedBytes = pinOutcome.content" in runEnv,
        )
        assertTrue(
            "unpinned branch must patch the in-memory bytes and writeText the result",
            Regex("""applyLettaJsPatches\(unpinnedBytes""").containsMatchIn(runEnv) &&
                Regex("""lettaJs\(rootfsDir\)\.writeText\(res\.content\)""").containsMatchIn(runEnv),
        )
        // The record refresh on the unpinned path must hash the IN-MEMORY
        // lineage. Extraction: from the refresh comment to the launch prep.
        val refreshAt = runEnv.indexOf("Record refresh (app-update drift")
        val libPathAt = runEnv.indexOf("val libPath = libDir.absolutePath")
        assertTrue("refresh block anchors not found — extraction broken", refreshAt in 0 until libPathAt)
        val refresh = runEnv.substring(refreshAt, libPathAt)
        assertFalse(
            "record-refresh sha must NOT hash the disk file — sha256Of(lettaJs(rootfsDir)) " +
                "after the patch writeText is exactly the write-then-reread race",
            Regex("""sha256Of\(lettaJs\(rootfsDir\)\)""").containsMatchIn(refresh),
        )
        assertTrue(
            "record-refresh sha must hash the in-memory patched lineage " +
                "(bootPatched ?: unpinnedBytes)",
            "bootPatched ?: unpinnedBytes" in refresh,
        )
    }

    @Test
    fun `upgrade record write failure fails loud - upgradeLetta returns false`() {
        // Review 2734 non-blocker (a): a swallowed record-write failure booted
        // an unrecorded upgrade that the next boot silently rolled back.
        val t = src()
        val upg = t.substringAfter("private fun upgradeLetta(): Boolean")
        val guardAt = upg.indexOf("if (!recordOk)")
        assertTrue(
            "record-write outcome must be checked (val recordOk + if (!recordOk)) — swallowing it " +
                "boots an unrecorded upgrade",
            guardAt >= 0,
        )
        val failReturn = upg.indexOf("return false", guardAt)
        assertTrue(
            "failed record write must return false (loud, same as 0-patch-sites) — do not boot",
            failReturn in guardAt until guardAt + 400,
        )
    }

    @Test
    fun `writeUpgradeRecord is boolean and refuses degenerate rows`() {
        // Review 2734 non-blocker (b): writer-side guard against empty
        // version/sha — a MAC over "0.35.0|" is exactly the shape
        // decidePinState treats as "version authority alone unpins".
        val t = src()
        assertTrue(
            "writeUpgradeRecord must return Boolean (callers fail loud on false)",
            "private fun writeUpgradeRecord(version: String, lettaJsSha256: String): Boolean" in t,
        )
        val w = t.substringAfter("private fun writeUpgradeRecord(")
        assertTrue(
            "writer must guard empty version/sha before minting a MAC",
            "version.isEmpty() || lettaJsSha256.isEmpty()" in w,
        )
    }

    @Test
    fun `in-memory sha256Of matches file sha256Of for identical bytes`() {
        // Equivalence of the two hash entry points: the lineage hash
        // (ByteArray) and the file hash must agree byte-for-byte, or the
        // record would never match a re-derived disk hash at next boot.
        val bytes = ByteArray(200_000) { (it % 251).toByte() }  // > one 64K chunk
        val tmp = kotlin.io.path.createTempFile("sha", ".bin").toFile()
        try {
            tmp.writeBytes(bytes)
            assertEquals(
                LettaEnvironmentService.sha256Of(tmp),
                LettaEnvironmentService.sha256Of(bytes),
            )
        } finally {
            tmp.delete()
        }
    }

    @Test
    fun `boot path writes first-boot record only when absent - after patching`() {
        val t = src()
        val runEnv = t.substringAfter("private fun runEnvironment(")
        val patchAt = runEnv.indexOf("applyLettaJsPatches(")
        val firstBootAt = runEnv.indexOf("writeFirstBootRecord")
        assertTrue(
            "first-boot record write must come after boot-path patching (post-patch bytes)",
            patchAt in 0 until firstBootAt,
        )
        val verify = t.substringAfter("private fun verifyLettaJsPin(")
        assertTrue(
            "writeFirstBootRecord must be derived from record absence (record == null) — " +
                "existing records are never rewritten by the boot path",
            Regex("""PinOutcome\(true, PinState\.PIN_APPLIES, pinVersion, record == null\)""")
                .containsMatchIn(verify),
        )
    }

    // ---- resolve-step timeout: stream-then-wait, not decorative ----------

    @Test
    fun `npm view resolve uses stream then wait with real timeout`() {
        val t = src()
        val resolve = t.substringAfter("val resolvePb = ProcessBuilder(")
            .substringBefore("val pb = ProcessBuilder(")
        val waitAt = resolve.indexOf("waitFor(60, TimeUnit.SECONDS)")
        val killAt = resolve.indexOf("destroyForcibly()")
        assertTrue(
            "resolve step must waitFor(60s) then destroyForcibly — readText-before-waitFor " +
                "made the timeout decorative (hung npm view blocked the worker).",
            waitAt in 0 until killAt,
        )
        assertFalse(
            "resolve step must not read the stream to completion before waitFor",
            Regex("""\.bufferedReader\(\)\.readText\(\)""").containsMatchIn(resolve),
        )
    }

    // ---- keystore key hygiene ----------------------------------------------

    @Test
    fun `upgrade record uses fixed keystore alias and hmac sha256`() {
        val t = src()
        assertTrue(
            "fixed Keystore alias required (spec: lettaUpgradeRecordV1)",
            "\"lettaUpgradeRecordV1\"" in t,
        )
        assertTrue(
            "MAC must be HmacSHA256 under AndroidKeyStore",
            "KEY_ALGORITHM_HMAC_SHA256" in t && "\"AndroidKeyStore\"" in t,
        )
    }

    private fun src(): String {
        val f = java.io.File("src/main/java/com/angussoftware/letta/env/LettaEnvironmentService.kt")
            .takeIf { it.exists() }
            ?: java.io.File("../src/main/java/com/angussoftware/letta/env/LettaEnvironmentService.kt")
        check(f != null) { "LettaEnvironmentService.kt not found from test working dir" }
        return f.readText()
    }
}
