package com.angussoftware.letta.env

import org.junit.Test
import org.junit.Assert.*
import java.io.File

/**
 * Client-side session gate contract (Oct 8 audit set 5, R7-2). am/pm/keyevent
 * run in the agent shell (never cross the socket), so the SERVICE gate cannot
 * reach them — the agentctl client itself must refuse to run them without an
 * approved session, mirroring what the named twins (launch/key) get
 * server-side. These tests pin that wiring in the shipped client script.
 */
class ClientGateContractTest {

    private fun client(): String {
        val f = File("src/main/assets/agentctl.sh")
        assertTrue("agentctl.sh asset not found (CWD=${File(".").absolutePath})", f.exists())
        return f.readText()
    }

    private fun caseBody(script: String, caseName: String): String {
        // Extract the case branch body: from 'caseName)' up to the next
        // top-level '    word)' case label or 'esac'.
        val marker = "    $caseName)"
        val start = script.indexOf(marker)
        assertTrue("case '$caseName' not found in agentctl.sh", start >= 0)
        val rest = script.substring(start + marker.length)
        val next = Regex("""\n    [a-zA-Z]+\)|\n    help\|""").find(rest)?.range?.start ?: rest.length
        return rest.substring(0, next)
    }

    @Test
    fun `am pm keyevent passthroughs are gated client-side`() {
        val script = client()
        listOf("keyevent", "am", "pm").forEach { c ->
            val body = caseBody(script, c)
            assertTrue(
                "agentctl '$c' must call require_session before exec (audit R7-2)",
                body.contains("require_session"))
        }
    }

    @Test
    fun `require_session is fail-closed on missing service response`() {
        // No status response = refusal. The gate must treat empty/unparseable
        // status as NO session, never as permission.
        val src = client()
        val fn = src.substringAfter("require_session() {").substringBefore("case \"")
        assertTrue(
            "require_session must refuse on empty status response (fail-closed)",
            fn.contains("-z \"\$ST\""))
        assertTrue(
            "require_session must demand a positive sessionActive:true",
            fn.contains("sessionActive") && fn.contains("true"))
    }

    @Test
    fun `ungated commands are not blocked by the client gate`() {
        // The gate must apply ONLY to am/pm/keyevent — status/ping/notify/
        // logtail stay freely usable (a11y down must not brick diagnostics).
        val script = client()
        listOf("notify", "ping", "status", "logtail", "session").forEach { c ->
            val body = caseBody(script, c)
            assertFalse(
                "'$c' must not require a session (it would lock out consent flow itself)",
                body.contains("require_session"))
        }
    }

    @Test
    fun `registry classifies passthroughs and navigation as session-gated`() {
        // The REGISTRY is the single source of truth — its classes must match
        // the client-side reality this set creates (am/pm/keyevent/back/home/
        //notifications = SESSION_GATED, audit R7-2/R7-6).
        val gated = AgentAccessibilityService.REGISTRY
            .filter { it.consent == AgentAccessibilityService.ConsentClass.SESSION_GATED }
            .map { it.name }
        listOf("am", "pm", "keyevent", "back", "home", "notifications").forEach {
            assertTrue("REGISTRY must classify '$it' as SESSION_GATED (audit R7-2/R7-6)", it in gated)
        }
        // notify is the only remaining CLIENT_LOCAL — the app's own channel.
        val clientLocal = AgentAccessibilityService.REGISTRY
            .filter { it.consent == AgentAccessibilityService.ConsentClass.CLIENT_LOCAL }
            .map { it.name }
        assertEquals("only notify remains client-local", listOf("notify"), clientLocal)
    }
}
