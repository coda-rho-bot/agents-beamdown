package com.angussoftware.letta.env

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Set-4 client contract (review 2738 B1/B2): the sha256 sidecar mechanism
 * must actually fire on its PRIMARY path (user-initiated enqueue from the
 * UI thread), and an unresolvable sidecar must fail CLOSED.
 *
 * History (B1): enqueueDownload fetched the sidecar synchronously —
 * NetworkOnMainThreadException on its main call path, swallowed by
 * runCatching, leaving an empty expected-sha pref → silent unverified
 * installs while CI stayed green (no test exercised the path). These
 * tests pin the architecture: discovery resolves, enqueue is local-only,
 * install gates on shaGate.
 *
 * Source-contract tests read UpdateManager.kt as a SLICE (comments
 * stripped) per SocketNamespaceContractTest — whole-file matching is
 * vacuous and comment text must neither satisfy nor trip assertions.
 */
class UpdateShaContractTest {

    private fun source(): String {
        val src = java.io.File("src/main/java/com/angussoftware/letta/env/UpdateManager.kt")
            .takeIf { it.exists() }
            ?: java.io.File("../src/main/java/com/angussoftware/letta/env/UpdateManager.kt")
        check(src.exists()) { "UpdateManager.kt not found from test working dir" }
        return src.readText()
    }

    private fun codeOnly(text: String): String =
        text.replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
            .replace(Regex("""//[^\n]*"""), "")

    /** Balanced-paren body for the FIRST top-level fun named [name] in [code]. */
    private fun funBody(code: String, name: String): String {
        val sig = "fun $name("
        val start = code.indexOf(sig)
        check(start >= 0) { "fun $name not found" }
        var depth = 0
        var j = code.indexOf('{', start)
        check(j > 0) { "fun $name has no body" }
        var i = j
        while (i < code.length) {
            when (code[i]) {
                '{' -> depth++
                '}' -> { depth--; if (depth == 0) break }
            }
            i++
        }
        return code.substring(j + 1, i)
    }

    // ---- behavioral: parseSidecarSha -------------------------------------

    @Test
    fun `sidecar parser accepts the exact bytes release yml writes`() {
        // .woodpecker/release.yml: f.write(apk_sha + '  ' + os.path.basename(APK) + '\n')
        val hex = "a".repeat(64)
        assertEquals(hex, UpdateManager.parseSidecarSha("$hex  agents-beamdown-v0.4.33.apk\n"))
    }

    @Test
    fun `sidecar parser accepts bare hex and upper case`() {
        assertEquals("a".repeat(64), UpdateManager.parseSidecarSha("a".repeat(64)))
        assertEquals("abcdef0123456789".repeat(4), UpdateManager.parseSidecarSha("ABCDEF0123456789".repeat(4)))
    }

    @Test
    fun `sidecar parser rejects malformed content`() {
        assertNull(UpdateManager.parseSidecarSha(""))
        assertNull(UpdateManager.parseSidecarSha("\n"))
        assertNull(UpdateManager.parseSidecarSha("deadbeef"))
        assertNull(UpdateManager.parseSidecarSha("z".repeat(64) + "  x.apk"))
        assertNull(UpdateManager.parseSidecarSha("not-a-sidecar at all\n"))
    }

    // ---- behavioral: shaGate (B2) -----------------------------------------

    @Test
    fun `legacy release - no sidecar url - warns and installs`() {
        assertEquals(UpdateManager.ShaGate.LEGACY, UpdateManager.shaGate(null, null))
        assertEquals(UpdateManager.ShaGate.LEGACY, UpdateManager.shaGate("", ""))
    }

    @Test
    fun `listed but unresolvable sidecar refuses`() {
        // On-path attacker suppresses the sidecar while serving the APK:
        // URL was discovered, hex never resolved → REFUSE, not legacy.
        assertEquals(UpdateManager.ShaGate.REFUSE, UpdateManager.shaGate("https://x/a.apk.sha256", null))
        assertEquals(UpdateManager.ShaGate.REFUSE, UpdateManager.shaGate("https://x/a.apk.sha256", ""))
    }

    @Test
    fun `resolved sidecar verifies`() {
        assertEquals(UpdateManager.ShaGate.VERIFY, UpdateManager.shaGate("https://x/a.apk.sha256", "a".repeat(64)))
    }

    // ---- source contract: enqueue makes no network (B1) -------------------

    @Test
    fun `enqueueDownload performs no network i o`() {
        val body = funBody(codeOnly(source()), "enqueueDownload")
        val networkApis = listOf(
            "URL(", "openConnection(", "HttpURLConnection",
            "urlConnection", "fetchSidecarHex(", "fetchExpectedSha("
        )
        for (api in networkApis) {
            assertFalse(
                "enqueueDownload must not touch network APIs (found '$api') — B1: " +
                    "enqueue runs on the UI thread; a sync fetch is NetworkOnMainThreadException, " +
                    "swallowed → empty sha pref → silent unverified install",
                body.contains(api)
            )
        }
    }

    // ---- source contract: discovery resolves the sidecar ------------------

    @Test
    fun `maybeCheckAsync resolves the sidecar hex on the discovery pass`() {
        val body = funBody(codeOnly(source()), "maybeCheckAsync")
        assertTrue(
            "discovery must resolve the sidecar hex at release discovery " +
                "(fetchSidecarHex) so the expectation is frozen before any " +
                "UI-thread path needs it",
            body.contains("fetchSidecarHex(")
        )
        assertTrue(
            "discovery must persist the resolved hex to PREF_DISCOVERED_SHA",
            body.contains("PREF_DISCOVERED_SHA")
        )
    }

    // ---- source contract: install fail-closed on unresolvable sidecar -----

    @Test
    fun `install gates on shaGate with a REFUSE branch`() {
        // Threading refactor (Oct 27): the sha-gate body lives in
        // installInternal; install() is a delegating wrapper. The contract
        // follows the implementation — the REFUSE branch it asserts is
        // upstream of the installer intent regardless of which name holds it.
        val body = funBody(codeOnly(source()), "installInternal")
        assertTrue(
            "install must consult shaGate (B2: URL-present-but-unresolved " +
                "must refuse, not fall back to the legacy warning)",
            body.contains("shaGate(")
        )
        assertTrue(
            "install must handle ShaGate.REFUSE explicitly",
            body.contains("ShaGate.REFUSE")
        )
        // Self-heal (review follow-up): the refusal path must clear the
        // discovery state INCLUDING the 5h check gate, or the next 30-min
        // tick honors the old timestamp and never re-checks — user taps a
        // refusing button for hours.
        val refuseSlice = body.substringAfter("ShaGate.REFUSE ->").substringBefore("ShaGate.VERIFY")
        assertTrue(
            "REFUSE branch must clear PREF_LAST_CHECK (self-heal via next tick)",
            refuseSlice.contains("PREF_LAST_CHECK")
        )
        assertTrue(
            "REFUSE branch must delete the downloaded file",
            refuseSlice.contains("file.delete()")
        )
    }

    // ---- source contract: pipeline sidecar upload is fatal -----------------

    @Test
    fun `release yml makes sidecar upload failure fatal`() {
        val yml = java.io.File(".woodpecker/release.yml")
            .takeIf { it.exists() }
            ?: java.io.File("../.woodpecker/release.yml")
        check(yml.exists()) { "release.yml not found from test working dir" }
        val text = yml.readText()
        // The for/else: upload_sha failure on ALL routes must fatal(), not print.
        val idx = text.indexOf("sha upload route {base} failed")
        check(idx >= 0) { "upload_sha failure print not found — renamed?" }
        val shaLoop = text.substring(idx)
        assertTrue(
            "sha upload failure must reach fatal() (review 2738 B2) — a " +
                "sidecar-less tag would refuse every install post-B2",
            shaLoop.contains("fatal(")
        )
    }
}
