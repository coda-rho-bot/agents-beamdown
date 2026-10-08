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
