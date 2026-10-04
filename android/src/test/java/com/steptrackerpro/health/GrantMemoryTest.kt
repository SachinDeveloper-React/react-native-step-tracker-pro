package com.steptrackerpro.health

import com.steptrackerpro.core.StepStateStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The grants check during a rate-limit block. On Android 13 and lower the
 * provider refuses even this check once the quota is used up: the last
 * answer stands, and it is reused like any other rather than asked for
 * again by every caller.
 */
class GrantMemoryTest {

    private var now = 50_000L
    private val grants = GrantMemory({ now }, ttlMs = 5_000L)
    private val steps = setOf("android.permission.health.READ_STEPS")
    private val stepsAndDistance = steps + "android.permission.health.READ_DISTANCE"

    @Test
    fun `an answer is reused for its time, then asked for again`() {
        assertNull(grants.reusable())
        assertFalse(grants.answered(steps))
        assertEquals(steps, grants.reusable())
        now += 4_999L
        assertEquals(steps, grants.reusable())
        now += 1L
        assertNull(grants.reusable())
    }

    @Test
    fun `a failed check keeps the last answer and is reused like one`() {
        grants.answered(steps)
        now += 6_000L
        assertNull(grants.reusable())

        // The provider refuses the check for quota: not a revocation.
        assertEquals(steps, grants.failed(stored = null))
        // And the callers that follow are answered without asking again.
        now += 1_000L
        assertEquals(steps, grants.reusable())
        now += 4_000L
        assertNull(grants.reusable())
    }

    @Test
    fun `the first check of a process falls back on the grants an earlier one read`() {
        // A restart during a block: nothing read in this process yet.
        assertEquals(stepsAndDistance, grants.failed(stored = stepsAndDistance))
        assertEquals(stepsAndDistance, grants.reusable())
        // With nothing ever read, there is nothing to stand on.
        assertEquals(emptySet<String>(), GrantMemory({ now }, 5_000L).failed(stored = null))
    }

    @Test
    fun `a change of grants is reported once, and a failure in between is not one`() {
        assertFalse(grants.answered(steps))
        assertFalse(grants.answered(steps))
        grants.failed(stored = null)
        assertFalse(grants.answered(steps))
        assertTrue(grants.answered(stepsAndDistance))
        assertFalse(grants.answered(stepsAndDistance))
        assertEquals(stepsAndDistance, grants.last)
    }

    @Test
    fun `invalidating asks again at once`() {
        grants.answered(steps)
        grants.invalidate()
        assertNull(grants.reusable())
        // What was read is still known, so a change is still noticed.
        assertTrue(grants.answered(emptySet()))
    }

    @Test
    fun `the grants an earlier process read survive the trip through storage`() {
        assertNull(StepStateStore.decodeGrants(null))
        assertEquals(emptySet<String>(), StepStateStore.decodeGrants(StepStateStore.encodeGrants(emptySet())))
        assertEquals(stepsAndDistance, StepStateStore.decodeGrants(StepStateStore.encodeGrants(stepsAndDistance)))
    }
}
