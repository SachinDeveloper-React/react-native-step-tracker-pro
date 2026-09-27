package com.steptrackerpro.core

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

/** `healthConnectReadTypes` survives the stored JSON and is normalised on the way in. */
class HealthConnectReadTypesConfigTest {

    @Test
    fun `all three types by default, as before 2_1`() {
        assertEquals(listOf("steps", "distance", "totalCalories"), StepTrackerConfig().healthConnectReadTypes)
    }

    @Test
    fun `config stored before 2_1 reads as all three`() {
        val stored = StepTrackerConfig().toJson().apply { remove("healthConnectReadTypes") }
        assertEquals(
            StepTrackerConfig.DEFAULT_READ_TYPES,
            StepTrackerConfig.fromJson(stored).sanitised().healthConnectReadTypes
        )
    }

    @Test
    fun `round-trips through the stored JSON`() {
        val config = StepTrackerConfig(healthConnectReadTypes = listOf("steps")).sanitised()
        val restored = StepTrackerConfig.fromJson(JSONObject(config.toJson().toString())).sanitised()
        assertEquals(listOf("steps"), restored.healthConnectReadTypes)
    }

    @Test
    fun `sanitising adds steps, drops unknown names and orders the rest`() {
        val config = StepTrackerConfig(
            healthConnectReadTypes = listOf("totalCalories", "heartRate", "totalCalories")
        ).sanitised()
        assertEquals(listOf("steps", "totalCalories"), config.healthConnectReadTypes)
    }
}
