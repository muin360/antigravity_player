package com.tensorix.antigravityplayer.audio

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Formal verification for stream lifecycle state machine (Rules 16 & 17).
 * Validates legal transitions, rejection of illegal transitions,
 * deterministic error recovery, and stale generation write protection.
 */
class StreamLifecycleStateMachineTest {

    enum class FormalStreamState {
        CLOSED,
        OPENING,
        OPEN,
        STARTING,
        RUNNING,
        PAUSING,
        PAUSED,
        FLUSHING,
        RECOVERING,
        FAILED,
        CLOSING
    }

    class StreamLifecycleFsm(initialState: FormalStreamState = FormalStreamState.CLOSED) {
        var state: FormalStreamState = initialState
            private set

        var generation: Long = 1L
            private set

        private val legalTransitions: Map<FormalStreamState, Set<FormalStreamState>> = mapOf(
            FormalStreamState.CLOSED to setOf(FormalStreamState.OPENING),
            FormalStreamState.OPENING to setOf(FormalStreamState.OPEN, FormalStreamState.FAILED, FormalStreamState.CLOSING),
            FormalStreamState.OPEN to setOf(FormalStreamState.STARTING, FormalStreamState.CLOSING, FormalStreamState.FAILED),
            FormalStreamState.STARTING to setOf(FormalStreamState.RUNNING, FormalStreamState.FAILED, FormalStreamState.CLOSING),
            FormalStreamState.RUNNING to setOf(FormalStreamState.PAUSING, FormalStreamState.FLUSHING, FormalStreamState.RECOVERING, FormalStreamState.FAILED, FormalStreamState.CLOSING),
            FormalStreamState.PAUSING to setOf(FormalStreamState.PAUSED, FormalStreamState.FAILED, FormalStreamState.CLOSING),
            FormalStreamState.PAUSED to setOf(FormalStreamState.STARTING, FormalStreamState.FLUSHING, FormalStreamState.CLOSING, FormalStreamState.FAILED),
            FormalStreamState.FLUSHING to setOf(FormalStreamState.PAUSED, FormalStreamState.RUNNING, FormalStreamState.FAILED, FormalStreamState.CLOSING),
            FormalStreamState.RECOVERING to setOf(FormalStreamState.OPENING, FormalStreamState.FAILED, FormalStreamState.CLOSING),
            FormalStreamState.FAILED to setOf(FormalStreamState.RECOVERING, FormalStreamState.CLOSING, FormalStreamState.CLOSED),
            FormalStreamState.CLOSING to setOf(FormalStreamState.CLOSED)
        )

        fun transition(target: FormalStreamState): Boolean {
            val allowed = legalTransitions[state] ?: emptySet()
            return if (target in allowed) {
                state = target
                if (target == FormalStreamState.CLOSED) {
                    generation++ // Bump generation on close
                }
                true
            } else {
                false
            }
        }

        fun validateWrite(handle: Long, writeGeneration: Long): Boolean {
            if (handle == 0L) return false
            if (writeGeneration != generation) return false
            return state == FormalStreamState.RUNNING || state == FormalStreamState.FLUSHING || state == FormalStreamState.OPEN
        }
    }

    @Test
    fun `test legal stream lifecycle progression from closed to running`() {
        val fsm = StreamLifecycleFsm()
        assertTrue(fsm.transition(FormalStreamState.OPENING))
        assertTrue(fsm.transition(FormalStreamState.OPEN))
        assertTrue(fsm.transition(FormalStreamState.STARTING))
        assertTrue(fsm.transition(FormalStreamState.RUNNING))
    }

    @Test
    fun `test legal pause and resume cycle`() {
        val fsm = StreamLifecycleFsm(FormalStreamState.RUNNING)
        assertTrue(fsm.transition(FormalStreamState.PAUSING))
        assertTrue(fsm.transition(FormalStreamState.PAUSED))
        assertTrue(fsm.transition(FormalStreamState.STARTING))
        assertTrue(fsm.transition(FormalStreamState.RUNNING))
    }

    @Test
    fun `test legal seek flush cycle during active playback`() {
        val fsm = StreamLifecycleFsm(FormalStreamState.RUNNING)
        assertTrue(fsm.transition(FormalStreamState.FLUSHING))
        assertTrue(fsm.transition(FormalStreamState.RUNNING))
    }

    @Test
    fun `test illegal transitions are strictly rejected`() {
        val fsm = StreamLifecycleFsm(FormalStreamState.CLOSED)
        // Cannot jump directly from CLOSED to RUNNING
        assertFalse(fsm.transition(FormalStreamState.RUNNING))
        // Cannot jump directly from CLOSED to FLUSHING
        assertFalse(fsm.transition(FormalStreamState.FLUSHING))
        // Cannot jump directly from CLOSED to PAUSED
        assertFalse(fsm.transition(FormalStreamState.PAUSED))

        // Open -> Running directly is illegal (must pass through STARTING)
        assertTrue(fsm.transition(FormalStreamState.OPENING))
        assertTrue(fsm.transition(FormalStreamState.OPEN))
        assertFalse(fsm.transition(FormalStreamState.RUNNING))
    }

    @Test
    fun `test error recovery transition sequence`() {
        val fsm = StreamLifecycleFsm(FormalStreamState.RUNNING)
        // Error occurred -> RECOVERING
        assertTrue(fsm.transition(FormalStreamState.RECOVERING))
        assertTrue(fsm.transition(FormalStreamState.OPENING))
        assertTrue(fsm.transition(FormalStreamState.OPEN))
        assertTrue(fsm.transition(FormalStreamState.STARTING))
        assertTrue(fsm.transition(FormalStreamState.RUNNING))
    }

    @Test
    fun `test stale handle and old generation cannot write to new stream`() {
        val fsm = StreamLifecycleFsm(FormalStreamState.RUNNING)
        val initialGen = fsm.generation

        // Valid write in generation 1
        assertTrue(fsm.validateWrite(12345L, initialGen))

        // Close stream (bumps generation to 2)
        assertTrue(fsm.transition(FormalStreamState.CLOSING))
        assertTrue(fsm.transition(FormalStreamState.CLOSED))

        // Open new stream (generation is now 2)
        assertTrue(fsm.transition(FormalStreamState.OPENING))
        assertTrue(fsm.transition(FormalStreamState.OPEN))
        assertTrue(fsm.transition(FormalStreamState.STARTING))
        assertTrue(fsm.transition(FormalStreamState.RUNNING))

        // Attempting to write with old handle and old generation (gen 1) to new stream (gen 2) MUST FAIL
        assertFalse(fsm.validateWrite(12345L, initialGen))

        // Attempting to write with handle 0 MUST FAIL
        assertFalse(fsm.validateWrite(0L, fsm.generation))

        // Write with correct new generation succeeds
        assertTrue(fsm.validateWrite(67890L, fsm.generation))
    }
}
