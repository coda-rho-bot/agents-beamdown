package com.angussoftware.letta.env

import org.junit.Test
import org.junit.Assert.*
import java.io.File

/**
 * Consent honesty contract (Oct 8 audit set 5). The consent overlay must
 * state what approval actually grants — FULL device control for the
 * duration — and must not imply approval is scoped to the agent's stated
 * description (it is not; scope enforcement is backlog, not shipped).
 *
 * Companion client-side pins live in [ClientGateContractTest]: the
 * agentctl.sh passthroughs must carry the same gate their named twins have.
 */
class ConsentHonestyContractTest {

    private fun source(): String {
        val f = File("src/main/java/com/angussoftware/letta/env/AgentAccessibilityService.kt")
        assertTrue("AgentAccessibilityService.kt not found (CWD=${File(".").absolutePath})", f.exists())
        return f.readText()
    }

    private fun overlayBody(src: String): String =
        src.substringAfter("val dur = android.widget.TextView(this).apply {")
            .substringBefore("textSize")

    @Test
    fun `overlay must state approval grants full device control`() {
        // Balanced-paren extraction of the dur text block (call-site truth,
        // not a comment — Lesson #508 pattern).
        val body = overlayBody(source())
        assertTrue(
            "consent overlay must tell the user approval grants FULL device control (audit R7-1) — got: ${body.trim().take(200)}",
            body.contains("FULL device control", ignoreCase = true))
        assertTrue(
            "overlay must name concrete capabilities so 'full control' is not abstract",
            body.contains("tap") && body.contains("read the screen", ignoreCase = true))
    }

    @Test
    fun `overlay must not imply approval is scoped to the description`() {
        // The old text said approval "lets the agent see and use this phone
        // for that purpose" — dishonest while grants are unscoped: the
        // description is advisory, approval is full. Audit R7-1.
        val body = overlayBody(source())
        assertFalse(
            "overlay must not scope approval to the stated purpose (it grants full control; audit R7-1)",
            body.contains("for that purpose"))
    }

    @Test
    fun `session actions are paused while a consent prompt is showing`() {
        // While the overlay is up, session-gated commands must be refused —
        // an active session must not keep acting (or tap its own renewal
        // overlay's Approve where injected gestures work) mid-prompt.
        val src = source()
        val dispatch = src.substringAfter("private fun handle(cmd: String, req: JSONObject): JSONObject {")
            .substringBefore("return when (cmd)")
        assertTrue(
            "dispatch must refuse session-gated commands while consentPromptShowing",
            dispatch.contains("consentPromptShowing"))
    }
}
