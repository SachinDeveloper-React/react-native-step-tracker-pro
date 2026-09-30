package com.steptrackerpro.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Steps late samples owe to closed days are kept with the counter state
 * until they are added to those days. What is stored must read back as
 * exactly what was owed - never more, which would add steps twice.
 */
class LateStepsTest {

    @Test
    fun `what is owed reads back as it was written`() {
        val owed = mapOf("2026-09-29" to 312, "2026-09-28" to 5)
        assertEquals(owed, StepStateStore.decodeLateSteps(StepStateStore.encodeLateSteps(owed)))
    }

    @Test
    fun `nothing owed is stored as nothing`() {
        assertEquals("", StepStateStore.encodeLateSteps(mapOf("2026-09-29" to 0)))
        assertTrue(StepStateStore.decodeLateSteps(null).isEmpty())
        assertTrue(StepStateStore.decodeLateSteps("").isEmpty())
    }

    @Test
    fun `an entry that cannot be read is dropped, not guessed at`() {
        assertEquals(
            mapOf("2026-09-29" to 7),
            StepStateStore.decodeLateSteps("2026-09-29=7;garbage;2026-09-30=x;=5;2026-09-28=-3")
        )
    }
}
