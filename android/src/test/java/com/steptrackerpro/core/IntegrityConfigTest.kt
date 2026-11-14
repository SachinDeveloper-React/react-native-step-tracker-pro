package com.steptrackerpro.core

import com.steptrackerpro.integrity.DeviceSignals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Config survives the JSON the service rebuilds it from, out-of-range
 * thresholds are clamped rather than trusted, and the small JSON helpers and
 * the emulator heuristic behave.
 */
class IntegrityConfigTest {

    @Test
    fun `fraud detection is off by default and changes nothing`() {
        val config = StepTrackerConfig()
        assertFalse(config.fraudDetectionEnabled)
        assertEquals(IntegrityMode.FLAG, config.integrityMode)
        assertFalse(config.excludeSuspect)
        assertEquals(IntegrityRules(), config.integrityRules())
    }

    @Test
    fun `exclude only takes effect when detection is on`() {
        assertFalse(StepTrackerConfig(fraudMode = "exclude").excludeSuspect)
        assertTrue(StepTrackerConfig(fraudDetectionEnabled = true, fraudMode = "exclude").excludeSuspect)
    }

    @Test
    fun `the fraud fields survive the persisted JSON`() {
        val config = StepTrackerConfig(
            fraudDetectionEnabled = true,
            fraudMode = "exclude",
            fraudMaxCadenceSpm = 220,
            fraudSteadyCadenceMinutes = 45,
            fraudMaxContinuousMinutes = 0,
            fraudMaxDailySteps = 60_000,
            fraudFlagWhileCharging = false,
            fraudActivityRecognition = true
        )
        assertEquals(config, StepTrackerConfig.fromJson(config.toJson()))
    }

    @Test
    fun `json from before 1_5 reads as detection off`() {
        val old = StepTrackerConfig().toJson().apply {
            listOf(
                "fraudDetectionEnabled", "fraudMode", "fraudMaxCadenceSpm", "fraudSteadyCadenceMinutes",
                "fraudMaxContinuousMinutes", "fraudMaxDailySteps", "fraudFlagWhileCharging",
                "fraudActivityRecognition"
            ).forEach { remove(it) }
        }
        assertEquals(StepTrackerConfig(), StepTrackerConfig.fromJson(old))
    }

    @Test
    fun `thresholds are clamped, zero stays off, a bad mode falls back to flag`() {
        val clamped = StepTrackerConfig(
            fraudMode = "remove",
            fraudMaxCadenceSpm = 50,
            fraudSteadyCadenceMinutes = 2,
            fraudMaxContinuousMinutes = 10,
            fraudMaxDailySteps = 10
        ).sanitised()
        assertEquals("flag", clamped.fraudMode)
        assertEquals(100, clamped.fraudMaxCadenceSpm)
        assertEquals(5, clamped.fraudSteadyCadenceMinutes)
        assertEquals(30, clamped.fraudMaxContinuousMinutes)
        assertEquals(1_000, clamped.fraudMaxDailySteps)

        val off = StepTrackerConfig(
            fraudMaxCadenceSpm = 0,
            fraudSteadyCadenceMinutes = -1,
            fraudMaxContinuousMinutes = 0,
            fraudMaxDailySteps = 0
        ).sanitised()
        assertEquals(0, off.fraudMaxCadenceSpm)
        assertEquals(0, off.fraudSteadyCadenceMinutes)
        assertEquals(0, off.fraudMaxContinuousMinutes)
        assertEquals(0, off.fraudMaxDailySteps)
    }

    @Test
    fun `event details round-trip through the JSON column`() {
        val detail = mapOf("jumpMs" to -3_600_000L, "keys" to listOf("gapRecovery", "fraudMode"), "nested" to mapOf("a" to 1))
        val back = JsonMaps.parse(JsonMaps.toJson(detail))
        assertEquals(-3_600_000L, (back["jumpMs"] as Number).toLong())
        assertEquals(listOf("gapRecovery", "fraudMode"), back["keys"])
        assertEquals(1, (back["nested"] as Map<*, *>)["a"])
        // A non-finite number becomes null rather than failing the whole payload.
        assertEquals(null, JsonMaps.parse(JsonMaps.toJson(mapOf("x" to Double.NaN)))["x"])
        assertTrue(JsonMaps.parse("nope").isEmpty())
        assertTrue(JsonMaps.parse(null).isEmpty())
    }

    @Test
    fun `the emulator heuristic knows the stock images and leaves phones alone`() {
        assertTrue(
            DeviceSignals.looksLikeEmulator(
                "google/sdk_gphone64_arm64/emu64a:14/UE1A/123:user/release-keys",
                "sdk_gphone64_arm64", "Google", "google", "emu64a", "sdk_gphone64_arm64", "ranchu"
            )
        )
        assertTrue(
            DeviceSignals.looksLikeEmulator(
                "generic/sdk/generic:8.0.0", "Android SDK built for x86", "unknown",
                "generic", "generic", "sdk", "goldfish"
            )
        )
        assertTrue(DeviceSignals.looksLikeEmulator("x", "x", "Genymotion", "x", "x", "vbox86p", "vbox86"))
        assertFalse(
            DeviceSignals.looksLikeEmulator(
                "samsung/e3qxxx/e3q:14/UP1A.231005.007/S928BXXU1AXB5:user/release-keys",
                "SM-S928B", "samsung", "samsung", "e3q", "e3qxxx", "qcom"
            )
        )
        assertFalse(DeviceSignals.looksLikeEmulator(null, null, null, null, null, null, null))
    }

    @Test
    fun `a flag's key is its type and start, and a day under the cap has no volume flag`() {
        val flag = IntegrityFlag(IntegrityFlag.DAILY_VOLUME, FlagSeverity.STRONG, 0L, 1L, 10)
        assertEquals("daily_volume@0", flag.key)
        assertNull(FraudDetector.dailyVolumeFlag(0, 100, IntegrityRules(), 0L, 1L))
    }

    @Test
    fun `the 2_0 settings survive the persisted JSON and an unknown auth falls back to headers`() {
        val config = StepTrackerConfig(healthConnectReadActiveCalories = true, remoteSyncAuth = "signature")
        assertEquals(config, StepTrackerConfig.fromJson(config.toJson()))
        assertEquals(RemoteSyncAuth.HEADERS, StepTrackerConfig(remoteSyncAuth = "token").sanitised().remoteSyncAuth)
        assertEquals(RemoteSyncAuth.SIGNATURE, StepTrackerConfig(remoteSyncAuth = "signature").sanitised().remoteSyncAuth)
    }

    @Test
    fun `the motion gate is off by default and its one rule is the still variance`() {
        val config = StepTrackerConfig()
        assertFalse(config.motionGateEnabled)
        assertEquals(WitnessRules.DEFAULT_STILL_VARIANCE, config.motionGateRules().stillVariance, 0.0)
        assertEquals(0.05, StepTrackerConfig(motionGateStillVariance = 0.05).motionGateRules().stillVariance, 0.0)
        // The 2.8 classifier's thresholds are still accepted and read back,
        // and none of them reaches the witness: it has no such knobs.
        assertEquals(
            config.motionGateRules(),
            StepTrackerConfig(
                motionGateWindowSeconds = 9,
                motionGateMinPeakRatio = 0.5,
                motionGateMaxZeroCrossingRate = 30.0,
                motionGateEngageSeconds = 60
            ).motionGateRules()
        )
    }

    @Test
    fun `the motion gate asks for Activity Recognition on its own`() {
        // Without it the gate holds nothing, so turning the gate on is
        // asking for it - whatever the integrity tags' own switch says.
        assertFalse(StepTrackerConfig().wantsActivityRecognition)
        assertTrue(StepTrackerConfig(motionGateEnabled = true).wantsActivityRecognition)
        assertTrue(
            StepTrackerConfig(fraudDetectionEnabled = true, fraudActivityRecognition = true).wantsActivityRecognition
        )
        // The tags' switch means nothing with detection off, as before.
        assertFalse(StepTrackerConfig(fraudActivityRecognition = true).wantsActivityRecognition)
    }

    @Test
    fun `the motion gate fields survive the persisted JSON`() {
        val config = StepTrackerConfig(
            motionGateEnabled = true,
            motionGateWindowSeconds = 5,
            motionGateStillVariance = 0.05,
            motionGateMinPeakRatio = 0.2,
            motionGateMaxZeroCrossingRate = 12.0,
            motionGateEngageSeconds = 6
        )
        assertEquals(config, StepTrackerConfig.fromJson(config.toJson()))
    }

    @Test
    fun `out-of-range gate thresholds are clamped, and a NaN falls back`() {
        val wild = StepTrackerConfig(
            motionGateWindowSeconds = 600,
            motionGateStillVariance = 50.0,
            motionGateMinPeakRatio = 1.0,
            motionGateMaxZeroCrossingRate = 0.0,
            motionGateEngageSeconds = -5
        ).sanitised()
        assertEquals(GateDefaults.MAX_WINDOW_SECONDS, wild.motionGateWindowSeconds)
        // A still floor of 50 would call a sprint still. The rest are held
        // to the ranges JS documents, though nothing reads them from 2.9.0.
        assertEquals(1.0, wild.motionGateStillVariance, 0.0)
        assertEquals(0.9, wild.motionGateMinPeakRatio, 0.0)
        assertEquals(1.0, wild.motionGateMaxZeroCrossingRate, 0.0)
        assertEquals(0, wild.motionGateEngageSeconds)
        assertEquals(
            GateDefaults.MIN_WINDOW_SECONDS,
            StepTrackerConfig(motionGateWindowSeconds = 0).sanitised().motionGateWindowSeconds
        )
        // NaN compares false against everything, so clamping it would turn a
        // check off rather than hold it in range.
        val nan = StepTrackerConfig(
            motionGateStillVariance = Double.NaN,
            motionGateMinPeakRatio = Double.NaN,
            motionGateMaxZeroCrossingRate = Double.POSITIVE_INFINITY
        ).sanitised()
        assertEquals(WitnessRules.DEFAULT_STILL_VARIANCE, nan.motionGateStillVariance, 0.0)
        assertEquals(GateDefaults.MIN_PEAK_RATIO, nan.motionGateMinPeakRatio, 0.0)
        assertEquals(GateDefaults.MAX_ZERO_CROSSING_RATE, nan.motionGateMaxZeroCrossingRate, 0.0)
    }

    @Test
    fun `persisted config never carries the headers, which are sealed separately`() {
        val config = StepTrackerConfig(remoteSyncHeaders = mapOf("Authorization" to "Bearer secret"))
        assertFalse(config.toJson(includeHeaders = false).has("remoteSyncHeaders"))
        assertTrue(config.toJson().has("remoteSyncHeaders"))
        // A 1.x config with plaintext headers still reads, so it can be migrated.
        assertEquals("Bearer secret", StepTrackerConfig.fromJson(config.toJson()).remoteSyncHeaders["Authorization"])
    }
}
