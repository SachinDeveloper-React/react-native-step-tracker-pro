package com.steptrackerpro.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime

/**
 * Synthetic days in, flags out. Each fraud shape the detector exists for is
 * caught, and each honest shape it could be confused with - a walk with its
 * natural drift, a treadmill session, a run, a commute - is left alone.
 */
class FraudDetectorTest {

    private val zone: ZoneId = ZoneOffset.UTC
    private val rules = IntegrityRules()
    private val minute = 60_000L

    /** 10:00 UTC on a fixed day, so the night rule stays out of the way unless asked for. */
    private val morning = ZonedDateTime.of(2026, 9, 14, 10, 0, 0, 0, ZoneOffset.UTC).toInstant().toEpochMilli()
    private val night = ZonedDateTime.of(2026, 9, 14, 1, 0, 0, 0, ZoneOffset.UTC).toInstant().toEpochMilli()

    private fun minutes(start: Long, counts: List<Int>, tag: (Int) -> MinuteSample.(Int) -> MinuteSample = { { this } }) =
        counts.mapIndexed { i, c -> MinuteSample(start + i * minute, c).let { m -> tag(i)(m, c) } }

    /** A person walking: a cadence that drifts by a few steps from minute to minute. */
    private fun humanWalk(n: Int, base: Int = 110, seed: Long = 3): List<Int> {
        var s = seed
        return List(n) {
            s = s * 6364136223846793005L + 1442695040888963407L
            base + ((s ushr 33) % 9).toInt() - 4
        }
    }

    private fun evaluate(m: List<MinuteSample>, w: List<MotionFeatures> = emptyList(), r: IntegrityRules = rules) =
        FraudDetector.evaluate(m, w, r, zone)

    @Test
    fun `an ordinary walk raises nothing`() {
        val result = evaluate(minutes(morning, humanWalk(60)))
        assertTrue(result.flags.isEmpty())
        assertEquals(0, result.flaggedSteps)
    }

    @Test
    fun `a treadmill session holds a steady pace but still drifts, and is left alone`() {
        // 45 minutes at a fixed belt speed: 118-121 steps a minute.
        val counts = List(45) { i -> 118 + (i * 7 % 4) }
        assertTrue(evaluate(minutes(morning, counts)).flags.none { it.type == IntegrityFlag.STEADY_CADENCE })
    }

    @Test
    fun `a swing gadget's metronomic count is flagged as steady cadence`() {
        // 110.5 steps a minute: the minute boundary alternates 110 and 111.
        val counts = List(90) { i -> if (i % 2 == 0) 110 else 111 }
        val result = evaluate(minutes(morning, counts))
        val flag = result.flags.single { it.type == IntegrityFlag.STEADY_CADENCE }
        assertEquals(FlagSeverity.STRONG, flag.severity)
        assertEquals(90, flag.evidence["minutes"])
        assertEquals(counts.sum(), result.flaggedSteps)
    }

    @Test
    fun `a steady stretch shorter than the rule is not a machine`() {
        // The walks either side are at a different pace, so they cannot extend the steady block.
        val counts = humanWalk(20, base = 130) + List(29) { 110 } + humanWalk(20, base = 130, seed = 9)
        assertTrue(evaluate(minutes(morning, counts)).flags.none { it.type == IntegrityFlag.STEADY_CADENCE })
    }

    @Test
    fun `minutes faster than anyone walks or runs are flagged, a run is not`() {
        val run = minutes(morning, List(30) { 172 })
        assertTrue(evaluate(run).flags.none { it.type == IntegrityFlag.CADENCE })

        val shaken = minutes(morning, listOf(110, 260, 280, 115))
        val result = evaluate(shaken)
        val flag = result.flags.single { it.type == IntegrityFlag.CADENCE }
        assertEquals(morning + minute, flag.from)
        assertEquals(morning + 3 * minute, flag.to)
        assertEquals(280, flag.evidence["peakSpm"])
        assertEquals(540, result.flaggedSteps)
    }

    @Test
    fun `untimed lumps never count towards cadence or steadiness`() {
        val m = listOf(MinuteSample(morning, 0, untimedSteps = 5_000))
        val result = evaluate(m)
        assertTrue(result.flags.isEmpty())
        assertEquals(0, result.flaggedSteps)
    }

    @Test
    fun `walking past the continuous limit flags only the excess`() {
        val counts = humanWalk(200)
        val result = evaluate(minutes(morning, counts))
        val flag = result.flags.single { it.type == IntegrityFlag.CONTINUOUS }
        assertEquals(morning + 180 * minute, flag.from)
        assertEquals(counts.drop(180).sum(), flag.steps)
        assertEquals(flag.steps, result.flaggedSteps)
    }

    @Test
    fun `a pause resets the continuous clock`() {
        val counts = humanWalk(120) + listOf(12) + humanWalk(120, seed = 5)
        assertTrue(evaluate(minutes(morning, counts)).flags.none { it.type == IntegrityFlag.CONTINUOUS })
    }

    @Test
    fun `steps counted while charging are flagged, and only those`() {
        val m = minutes(morning, List(10) { 100 }) { i ->
            { c -> if (i in 3..5) copy(chargingSteps = c) else this }
        }
        val result = evaluate(m)
        val flag = result.flags.single { it.type == IntegrityFlag.CHARGING }
        assertEquals(300, flag.steps)
        assertEquals(300, result.flaggedSteps)
        // Off, charging is not a rule.
        assertEquals(0, evaluate(m, r = rules.copy(flagWhileCharging = false)).flaggedSteps)
    }

    @Test
    fun `a car ride is strong, a moment at a bus stop is weak`() {
        val ride = minutes(morning, List(6) { 30 }) { { c -> copy(vehicleSteps = c) } }
        val strong = evaluate(ride)
        assertEquals(FlagSeverity.STRONG, strong.flags.single { it.type == IntegrityFlag.IN_VEHICLE }.severity)
        assertEquals(180, strong.flaggedSteps)

        val stop = minutes(morning, listOf(30)) { { c -> copy(vehicleSteps = c) } }
        val weak = evaluate(stop)
        assertEquals(FlagSeverity.WEAK, weak.flags.single().severity)
        assertEquals(0, weak.flaggedSteps)
    }

    @Test
    fun `a long walk in the small hours is noted but not counted`() {
        val result = evaluate(minutes(night, humanWalk(40)))
        val flag = result.flags.single { it.type == IntegrityFlag.NIGHT }
        assertEquals(FlagSeverity.WEAK, flag.severity)
        assertEquals(0, result.flaggedSteps)
    }

    private fun window(at: Long, hz: Double, variance: Double, peak: Double, steps: Int = 20) =
        MotionFeatures(at, 10_000L, 250, hz, variance, hz * 2, peak, steps)

    @Test
    fun `a shaken window condemns its minute, a run's window does not`() {
        val m = minutes(morning, listOf(120, 150, 120))
        val shake = window(morning + minute + 5_000, 4.0, 31.9, 0.5)
        val result = evaluate(m, listOf(shake))
        val flag = result.flags.single { it.type == IntegrityFlag.SHAKE }
        assertEquals(FlagSeverity.STRONG, flag.severity)
        assertEquals(150, flag.steps)
        assertEquals(150, result.flaggedSteps)

        // A run whose harmonic out-ranks its step frequency: fast, hard, broadband.
        val run = window(morning + minute + 5_000, 5.5, 25.0, 0.19)
        assertNull(FraudDetector.classifyWindow(run))
        // A window with no steps in it is not evidence of fake steps.
        assertNull(FraudDetector.classifyWindow(window(morning, 4.0, 31.9, 0.5, steps = 0)))
    }

    @Test
    fun `a pure tone at walking pace is a weak swing flag`() {
        val m = minutes(morning, listOf(110))
        val result = evaluate(m, listOf(window(morning + 1_000, 1.8, 12.0, 0.5)))
        val flag = result.flags.single()
        assertEquals(IntegrityFlag.SWING, flag.type)
        assertEquals(FlagSeverity.WEAK, flag.severity)
        assertEquals(0, result.flaggedSteps)
    }

    @Test
    fun `a minute covered by several flags is counted once`() {
        // Too fast and on a charger at once.
        val m = minutes(morning, listOf(260)) { { c -> copy(chargingSteps = c) } }
        val result = evaluate(m)
        assertEquals(2, result.flags.size)
        assertEquals(260, result.flaggedSteps)
    }

    @Test
    fun `the daily cap takes the excess of what is left, not the whole day`() {
        assertEquals(0, FraudDetector.suspectSteps(0, 49_000, rules))
        assertEquals(10_000, FraudDetector.suspectSteps(0, 60_000, rules))
        // 8,000 already flagged: the remaining 52,000 is 2,000 over.
        assertEquals(10_000, FraudDetector.suspectSteps(8_000, 60_000, rules))
        // Off is off.
        assertEquals(0, FraudDetector.suspectSteps(0, 90_000, rules.copy(maxDailySteps = 0)))
        // Flagged steps never exceed the day.
        assertEquals(500, FraudDetector.suspectSteps(900, 500, rules))

        val flag = FraudDetector.dailyVolumeFlag(8_000, 60_000, rules, 0L, 1L)!!
        assertEquals(IntegrityFlag.DAILY_VOLUME, flag.type)
        assertEquals(2_000, flag.steps)
        assertNull(FraudDetector.dailyVolumeFlag(0, 10_000, rules, 0L, 1L))
    }

    @Test
    fun `a check set to zero is off`() {
        val off = IntegrityRules(0, 0, 0, 0, flagWhileCharging = false)
        val counts = List(300) { 300 }
        val m = minutes(morning, counts) { { c -> copy(chargingSteps = c) } }
        assertEquals(0, evaluate(m, r = off).flaggedSteps)
        assertFalse(evaluate(m, r = off).flags.any { it.severity == FlagSeverity.STRONG })
    }

    @Test
    fun `flags survive a JSON round trip for storage`() {
        val flags = evaluate(minutes(morning, List(40) { 110 })).flags
        assertTrue(flags.isNotEmpty())
        val back = IntegrityFlag.listFromJson(IntegrityFlag.listToJson(flags))
        assertEquals(flags.map { it.key }, back.map { it.key })
        assertEquals(flags.map { it.steps }, back.map { it.steps })
        assertEquals(flags.first().evidence["minutes"], back.first().evidence["minutes"])
        assertTrue(IntegrityFlag.listFromJson("not json").isEmpty())
        assertTrue(IntegrityFlag.listFromJson(null).isEmpty())
    }
}
