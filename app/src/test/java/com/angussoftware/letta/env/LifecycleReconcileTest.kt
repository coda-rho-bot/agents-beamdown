package com.angussoftware.letta.env

import com.angussoftware.letta.env.LettaEnvironmentService.State
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Stale-lifecycle reconciliation decision core (v0.4.6 regression): the
 * Start button no-oped on-device because handleStartIntent gated on the RAW
 * lifecycle enum, which was stuck at STOPPING after process death (onDestroy
 * stamps it unconditionally; nothing ever reset it). [reconciledState] is the
 * pure rule the service applies at every onStartCommand entry: a non-IDLE
 * state is only real while it has an owner — live worker thread or live
 * server process. When both are gone the state is a fossil and must fall
 * back to IDLE.
 */
class LifecycleReconcileTest {

    // ---- the regression: stuck STOPPING, everything dead ---------------------

    @Test
    fun stuckStoppingWithNoOwnerFallsBackToIdle() {
        assertEquals(
            State.IDLE,
            LettaEnvironmentService.reconciledState(State.STOPPING, workerAlive = false, procAlive = false))
    }

    @Test
    fun stuckStartingWithNoOwnerFallsBackToIdle() {
        assertEquals(
            State.IDLE,
            LettaEnvironmentService.reconciledState(State.STARTING, workerAlive = false, procAlive = false))
    }

    @Test
    fun stuckRunningWithNoOwnerFallsBackToIdle() {
        assertEquals(
            State.IDLE,
            LettaEnvironmentService.reconciledState(State.RUNNING, workerAlive = false, procAlive = false))
    }

    // ---- live owners keep their state ----------------------------------------

    @Test
    fun stoppingWithLiveWorkerIsReal() {
        assertEquals(
            State.STOPPING,
            LettaEnvironmentService.reconciledState(State.STOPPING, workerAlive = true, procAlive = false))
    }

    @Test
    fun runningWithLiveWorkerIsReal() {
        assertEquals(
            State.RUNNING,
            LettaEnvironmentService.reconciledState(State.RUNNING, workerAlive = true, procAlive = true))
    }

    @Test
    fun runningWithOnlyAdoptedProcessIsReal() {
        // Worker gone but the server process it launched still lives
        // (adopted by a later service instance) — still a real RUNNING.
        assertEquals(
            State.RUNNING,
            LettaEnvironmentService.reconciledState(State.RUNNING, workerAlive = false, procAlive = true))
    }

    @Test
    fun stoppingWithOnlyLiveProcessIsReal() {
        // A stop was requested; the process is still draining — real.
        assertEquals(
            State.STOPPING,
            LettaEnvironmentService.reconciledState(State.STOPPING, workerAlive = false, procAlive = true))
    }

    // ---- idle is a fixed point -------------------------------------------------

    @Test
    fun idleStaysIdle() {
        assertEquals(
            State.IDLE,
            LettaEnvironmentService.reconciledState(State.IDLE, workerAlive = false, procAlive = false))
        assertEquals(
            State.IDLE,
            LettaEnvironmentService.reconciledState(State.IDLE, workerAlive = true, procAlive = true))
    }
}
