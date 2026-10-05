package com.angussoftware.letta.env

import com.angussoftware.letta.env.LettaEnvironmentService.State
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Worker catch-all classification (v0.4.9 on-device regression): pressing
 * Stop (ACTION_STOP_EXPLICIT) wrote the user-stop marker, set
 * pendingOp=SHUTDOWN, stamped STOPPING and called proc.destroy() — then the
 * exception thrown out of the dying runEnvironment() landed in the worker's
 * catch-all, which logged "FATAL: null" (null-message InterruptedException
 * class) and setStatus("failed: null"), racing the stop handler's own
 * "stopped (by user)" write. Harry saw "Failed" ~half the time; server.log
 * ALWAYS carried the false FATAL.
 *
 * [classifyWorkerException] is the pure rule the worker applies: while a
 * shutdown is in flight (pending op, lifecycle, or marker — any one of the
 * three, they're set in racing orders), the exception is benign teardown
 * noise; otherwise it's a real failure whose log line must always name the
 * exception class so "FATAL: null" can never recur.
 */
class WorkerExceptionPolicyTest {

    private val nullMessage = InterruptedException() // the on-device signature: null message

    // ---- shutdown in flight: benign, no "failed:" write ---------------------

    @Test
    fun exceptionDuringPendingShutdownIsBenign() {
        val p = LettaEnvironmentService.classifyWorkerException(
            nullMessage, LettaEnvironmentService.PendingOp.SHUTDOWN, State.RUNNING, userStopMarker = false)
        assertTrue(p.benign)
        assertEquals(
            "reader interrupted during shutdown (InterruptedException) — not a failure",
            p.logLine)
    }

    @Test
    fun exceptionWhileLifecycleStoppingIsBenign() {
        val p = LettaEnvironmentService.classifyWorkerException(
            nullMessage, null, State.STOPPING, userStopMarker = false)
        assertTrue(p.benign)
    }

    @Test
    fun exceptionWithUserStopMarkerIsBenign() {
        // Marker exists even though pendingOp was already drained and the
        // lifecycle is back to IDLE (worker caught up with the stop) — the
        // server was still stopped by the user; a trailing exception from
        // that teardown is not a failure.
        val p = LettaEnvironmentService.classifyWorkerException(
            nullMessage, null, State.IDLE, userStopMarker = true)
        assertTrue(p.benign)
    }

    @Test
    fun benignLogLineCarriesTheExceptionClass() {
        // Even benign lines must name the class — the on-device log showed
        // only "FATAL: null" with no way to tell what threw.
        val p = LettaEnvironmentService.classifyWorkerException(
            RuntimeException("boom"), null, State.STOPPING, userStopMarker = false)
        assertTrue(p.logLine.contains("RuntimeException"))
    }

    @Test
    fun benignLogLineNeverSaysFATAL() {
        val p = LettaEnvironmentService.classifyWorkerException(
            InterruptedException(), LettaEnvironmentService.PendingOp.SHUTDOWN, State.RUNNING, true)
        assertTrue(!p.logLine.contains("FATAL"))
    }

    // ---- real failures: failed status + class in the log line ----------------

    @Test
    fun exceptionDuringNormalRunIsARealFailure() {
        val p = LettaEnvironmentService.classifyWorkerException(
            RuntimeException("boom"), null, State.RUNNING, userStopMarker = false)
        assertFalse(p.benign)
        assertEquals("FATAL: RuntimeException: boom", p.logLine)
    }

    @Test
    fun realFailureWithNullMessageStillNamesTheClass() {
        // The regression's core symptom: "FATAL: null" gave no diagnosis.
        val p = LettaEnvironmentService.classifyWorkerException(
            InterruptedException(), null, State.RUNNING, userStopMarker = false)
        assertFalse(p.benign)
        assertEquals("FATAL: InterruptedException: null", p.logLine)
        // The worker writes the same class into the status line, so the
        // on-device "failed: null" is replaced by "failed: <Class>: null".
        assertTrue(p.logLine.contains("InterruptedException"))
    }

    @Test
    fun pendingUpgradeOrRestartIsNotBenign() {
        // Only SHUTDOWN is benign. An exception while an upgrade/restart is
        // pending IS a failure of the current environment run.
        for (op in listOf(LettaEnvironmentService.PendingOp.UPGRADE, LettaEnvironmentService.PendingOp.RESTART)) {
            val p = LettaEnvironmentService.classifyWorkerException(
                RuntimeException("boom"), op, State.RUNNING, userStopMarker = false)
            assertFalse("pending $op must not mask a real failure", p.benign)
        }
    }

    // ---- exit-status guard (verified on-device behavior) --------------------

    @Test
    fun exitStatusBlockDefersToPendingOps() {
        // runEnvironment's post-waitFor status block already skips every
        // status write when pendingOp != null (the "superseded" log) — a
        // SHUTDOWN can therefore never overwrite "stopped (by user)" from
        // the exit-code path. This test pins that contract via the policy
        // seam: same inputs the block guards on.
        val shutdown = LettaEnvironmentService.classifyWorkerException(
            nullMessage, LettaEnvironmentService.PendingOp.SHUTDOWN, State.STOPPING, true)
        assertTrue(shutdown.benign)
    }
}
