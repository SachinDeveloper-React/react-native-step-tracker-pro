package com.steptrackerpro.core

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What the app says the user's other devices counted is kept by day, apart
 * from this phone's counter state: today's is shown, and a snapshot of a
 * recent day carries its own. Nothing else is kept.
 */
class OtherDevicesTest {

    private val today = "2026-10-04"

    @Test
    fun `a week of days is kept, and nothing older or at zero`() {
        val kept = StepStateStore.keptOtherDevices(
            mapOf(
                "2026-10-04" to 629,
                "2026-10-03" to 0,
                "2026-09-27" to 1_200,
                "2026-09-26" to 800
            ),
            today
        )
        assertEquals(mapOf("2026-09-27" to 1_200, "2026-10-04" to 629), kept)
    }

    @Test
    fun `tomorrow is kept for a server a zone ahead, and nothing past it`() {
        // A loop gone wrong may set every day there is; today is never
        // crowded out by them.
        val byDate = (0 until 40).associate { DateKeys.minusDays("2026-10-20", it) to it + 1 }
        val kept = StepStateStore.keptOtherDevices(byDate, today)
        assertEquals(DateKeys.rangeOf("2026-09-27", "2026-10-05"), kept.keys.toList())
        assertEquals(17, kept[today])
    }

    @Test
    fun `what is kept reads back as it was written`() {
        val kept = mapOf("2026-10-03" to 4_120, "2026-10-04" to 629)
        assertEquals(kept, StepStateStore.decodeLateSteps(StepStateStore.encodeLateSteps(kept)))
    }
}
