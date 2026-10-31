package com.angussoftware.letta.env

import com.angussoftware.letta.env.LettaEnvironmentService.PayloadStageAction
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * R9-5 (Oct 22 finding #5) — payload staging decision matrix.
 *
 * The seam: payload tarballs (git-arm64.tar.gz, proot-aarch64.tar.gz) were
 * staged with a SIZE-ONLY check and extracted with NO hash — the one
 * delivery channel into rootfs no boot gate covered (usr/bin/git is not
 * manifest-listed, so a tarball-swap implant survives every boot).
 * payloadStageDecision is the pure policy: the I/O wiring in
 * installSupportFiles maps actions to effects. Fail-closed everywhere: an
 * unverifiable payload is never staged, never extracted.
 */
class PayloadStageDecisionTest {

    private val pin = "a".repeat(64)
    private val other = "b".repeat(64)

    @Test
    fun `missing pin is fatal - never stage an unverifiable payload`() {
        assertEquals(
            PayloadStageAction.FATAL_NO_PIN,
            LettaEnvironmentService.payloadStageDecision(null, false, pin, pin),
        )
    }

    @Test
    fun `optional pin is fatal - payload must be full verify-or-restore`() {
        assertEquals(
            PayloadStageAction.FATAL_NO_PIN,
            LettaEnvironmentService.payloadStageDecision(pin, true, pin, pin),
        )
    }

    @Test
    fun `asset failing its own pin is a broken build - never stage`() {
        assertEquals(
            PayloadStageAction.FATAL_BROKEN_BUILD,
            LettaEnvironmentService.payloadStageDecision(pin, false, other, pin),
        )
    }

    @Test
    fun `unreadable asset is a broken build - never stage`() {
        assertEquals(
            PayloadStageAction.FATAL_BROKEN_BUILD,
            LettaEnvironmentService.payloadStageDecision(pin, false, null, pin),
        )
    }

    @Test
    fun `staged copy matching the pin is ok`() {
        assertEquals(
            PayloadStageAction.OK,
            LettaEnvironmentService.payloadStageDecision(pin, false, pin, pin),
        )
    }

    @Test
    fun `absent or tampered staged copy restages from the immutable asset`() {
        assertEquals(
            PayloadStageAction.RESTAGE,
            LettaEnvironmentService.payloadStageDecision(pin, false, pin, null),
        )
        assertEquals(
            PayloadStageAction.RESTAGE,
            LettaEnvironmentService.payloadStageDecision(pin, false, pin, other),
        )
    }
}
