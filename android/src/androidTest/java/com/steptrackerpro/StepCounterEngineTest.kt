package com.steptrackerpro

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.steptrackerpro.core.DateKeys
import com.steptrackerpro.core.DayTotals
import com.steptrackerpro.core.MetricsCalculator
import com.steptrackerpro.core.StepCounterEngine
import com.steptrackerpro.core.StepStateStore
import com.steptrackerpro.core.StepTrackerConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.DayOfWeek

/**
 * Covers the parts that are painful to verify by walking around: reboot
 * handling, midnight rollover, pause/resume and OEM counter resets.
 *
 * Runs on a device or emulator, no step hardware required — samples are fed to
 * the engine directly.
 *
 *   ./gradlew :react-native-step-tracker-pro:connectedAndroidTest
 */
@RunWith(AndroidJUnit4::class)
class StepCounterEngineTest {

    private lateinit var context: Context
    private lateinit var state: StepStateStore
    private lateinit var engine: StepCounterEngine

    private var fakeBoot: Long = 0L

    /** Uptime is fed in too, so nothing depends on the host device's real uptime. */
    private var fakeElapsed: Long = 0L

    /** Wall clock, so gap splitting across midnight is deterministic. */
    private var fakeNow: Long = 0L
    private val rollovers = mutableListOf<Pair<DayTotals, String>>()
    private val backfills = mutableListOf<Map<String, Int>>()
    private val backfillReasons = mutableListOf<StepCounterEngine.BackfillReason>()
    private val observed = mutableListOf<Triple<Long, Long, Int>>()

    private fun now() = fakeNow

    private fun bootAt(dateKey: String, hour: Int) =
        DateKeys.startOfDayMillis(dateKey) + hour * 3_600_000L

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences(StepStateStore.PREFS_NAME, Context.MODE_PRIVATE)
            .edit().clear().commit()

        state = StepStateStore(context)
        fakeBoot = bootAt(DateKeys.today(), 1)
        fakeElapsed = 6 * 3_600_000L
        fakeNow = bootAt(DateKeys.today(), 12)

        // A steady-state device: booted at 01:00 today, anchor already pinned.
        state.activeDate = DateKeys.today()
        state.bootId = fakeBoot
        state.anchorValue = 0f
        state.anchorSteps = 0
        state.stepsToday = 0

        rollovers.clear()
        backfills.clear()
        backfillReasons.clear()
        observed.clear()
        engine = StepCounterEngine(
            state,
            MetricsCalculator(StepTrackerConfig()),
            { fakeElapsed },
            { fakeBoot },
            { fakeNow }
        )
        engine.onDayRollover = { totals, nextDate -> rollovers += totals to nextDate }
        engine.onBackfill = { shares, reason ->
            backfills += shares
            backfillReasons += reason
        }
        engine.onObserved = { from, to, steps -> observed += Triple(from, to, steps) }
    }

    @Test
    fun observedDeltasCarryTheSpanTheyWereTakenIn() {
        val t1 = now()
        engine.onCounterSample(50f, t1)
        val t2 = t1 + 30_000L
        engine.onCounterSample(120f, t2)
        // The first delta has no previous sample to measure from.
        assertEquals(Triple(0L, t1, 50), observed[0])
        assertEquals(Triple(t1, t2, 70), observed[1])
        // Nothing new, nothing observed.
        engine.onCounterSample(120f, t2 + 1_000L)
        assertEquals(2, observed.size)
    }

    @Test
    fun stepsCreditedByRecoveryAreNotObserved() {
        context.getSharedPreferences(StepStateStore.PREFS_NAME, Context.MODE_PRIVATE)
            .edit().clear().commit()
        state.activeDate = DateKeys.today()
        fakeBoot = bootAt(DateKeys.today(), 7)

        // An install's since-boot claim is credited in one go, not watched.
        assertEquals(3000, engine.onCounterSample(3000f, now())!!.steps)
        assertTrue(observed.isEmpty())
        engine.onCounterSample(3025f, now() + 10_000L)
        assertEquals(listOf(25), observed.map { it.third })
    }

    @Test
    fun pausedStepsAreNeverObserved() {
        engine.onCounterSample(100f, now())
        observed.clear()
        engine.setPaused(true)
        assertNull(engine.onCounterSample(400f, now()))
        engine.setPaused(false)
        engine.onCounterSample(410f, now())
        engine.onCounterSample(420f, now() + 5_000L)
        // The resume re-pin credits nothing; only the walk after it is seen.
        assertEquals(listOf(10), observed.map { it.third })
    }

    @Test
    fun detectorStepsAreObservedOneBatchAtATime() {
        engine.onDetectorSample(1, now())
        engine.onDetectorSample(3, now() + 2_000L)
        assertEquals(listOf(1, 3), observed.map { it.third })
        assertEquals(now(), observed[1].first)
    }

    @Test
    fun accumulatesCounterDeltas() {
        assertEquals(50, engine.onCounterSample(50f, now())!!.steps)
        assertEquals(120, engine.onCounterSample(120f, now())!!.steps)
        // An unchanged reading must not produce an event.
        assertNull(engine.onCounterSample(120f, now()))
    }

    @Test
    fun sensorResetWithoutRebootDoesNotDoubleCount() {
        engine.onCounterSample(120f, now())

        // Some OEM HALs restart the counter when the last listener detaches.
        assertNull(engine.onCounterSample(5f, now()))
        assertEquals(120, state.stepsToday)

        assertEquals(130, engine.onCounterSample(15f, now())!!.steps)
    }

    @Test
    fun smallBackwardsJitterIsIgnoredNotReanchored() {
        engine.onCounterSample(1000f, now())

        // A HAL wobble: one step below the last reading, no restart behind it.
        // Re-pinning here used to add the wobble back on the next sample, and
        // a day of them crept the total upward.
        assertNull(engine.onCounterSample(999f, now()))
        assertEquals(1000, state.stepsToday)
        assertEquals(1001, engine.onCounterSample(1001f, now())!!.steps)

        // A real restart still re-pins and keeps the total.
        assertNull(engine.onCounterSample(2f, now()))
        assertEquals(1001, state.stepsToday)
        assertEquals(1011, engine.onCounterSample(12f, now())!!.steps)
    }

    @Test
    fun rebootOnSameDayClaimsStepsTakenSinceBoot() {
        engine.onCounterSample(1200f, now())

        // Rebooted at 02:00 today; the counter restarts from zero.
        fakeBoot = bootAt(DateKeys.today(), 2)

        assertEquals(1280, engine.onCounterSample(80f, now())!!.steps)
    }

    @Test
    fun rebootOnPreviousDaySplitsPreServiceStepsAcrossMidnight() {
        engine.onCounterSample(1200f, now())

        // Booted at 23:00 yesterday and the first sample arrives at 09:00
        // today: the 400 steps since boot straddle midnight. One hour of the
        // ten fell yesterday, so 40 go to yesterday and 360 to today.
        fakeBoot = bootAt(DateKeys.yesterday(), 23)
        fakeNow = bootAt(DateKeys.today(), 9)

        assertEquals(1560, engine.onCounterSample(400f, now())!!.steps)
        assertEquals(listOf(mapOf(DateKeys.yesterday() to 40)), backfills)
        assertEquals(listOf(StepCounterEngine.BackfillReason.REBOOT), backfillReasons)
        // Today's 360 were apportioned, not observed.
        assertEquals(360, state.recoveredToday)

        // Everything after the re-anchor counts normally.
        assertEquals(1590, engine.onCounterSample(430f, now())!!.steps)
        assertEquals(360, state.recoveredToday)
    }

    @Test
    fun rebootOnPreviousDayDropsPreServiceStepsUnderDropPolicy() {
        engine.gapRecovery = StepCounterEngine.GapRecovery.DROP
        engine.onCounterSample(1200f, now())

        fakeBoot = bootAt(DateKeys.yesterday(), 23)
        fakeNow = bootAt(DateKeys.today(), 9)

        // Nothing since boot can be proven to be today's, so all of it goes.
        assertNull(engine.onCounterSample(400f, now()))
        assertEquals(1200, state.stepsToday)
        assertTrue(backfills.isEmpty())

        assertEquals(1230, engine.onCounterSample(430f, now())!!.steps)
    }

    @Test
    fun overnightKillRecoversTheGapOnBothSidesOfMidnight() {
        // Last sample at 22:00 yesterday, then an OEM task killer. The
        // hardware counted 1,000 more by the time the app is opened at 08:00.
        state.activeDate = DateKeys.yesterday()
        state.anchorValue = 0f
        state.anchorSteps = 0
        state.lastRawValue = 4000f
        state.stepsToday = 4000
        state.lastEventAt = bootAt(DateKeys.yesterday(), 22)
        fakeBoot = bootAt(DateKeys.yesterday(), 6)
        fakeNow = bootAt(DateKeys.today(), 8)

        // Two of the ten hours were yesterday: 200 back to yesterday, 800 today.
        val first = engine.onCounterSample(5000f, now())!!
        assertEquals(800, first.steps)
        assertEquals(1, rollovers.size)
        assertEquals(4000, rollovers[0].first.steps)
        assertEquals(listOf(mapOf(DateKeys.yesterday() to 200)), backfills)
        assertEquals(listOf(StepCounterEngine.BackfillReason.GAP), backfillReasons)
        assertEquals(DateKeys.today(), state.activeDate)
        // Every one of today's 800 was apportioned by the gap rule.
        assertEquals(800, first.recoveredSteps)

        // The 50 that follow are observed; the recovered share stays put.
        val next = engine.onCounterSample(5050f, now())!!
        assertEquals(850, next.steps)
        assertEquals(800, next.recoveredSteps)
        assertEquals(800, engine.consumeCommit().recoveredSteps)
    }

    @Test
    fun overnightKillUnderTodayCappedPolicyCreditsAtMostTheCap() {
        engine.gapRecovery = StepCounterEngine.GapRecovery.TODAY_CAPPED
        engine.gapRecoveryMaxSteps = 600
        state.activeDate = DateKeys.yesterday()
        state.anchorValue = 0f
        state.anchorSteps = 0
        state.lastRawValue = 4000f
        state.stepsToday = 4000
        state.lastEventAt = bootAt(DateKeys.yesterday(), 22)
        fakeBoot = bootAt(DateKeys.yesterday(), 6)
        fakeNow = bootAt(DateKeys.today(), 8)

        // 1,000 recovered, 600 kept on today, nothing to yesterday, 400 gone.
        val first = engine.onCounterSample(5000f, now())!!
        assertEquals(600, first.steps)
        assertEquals(600, first.recoveredSteps)
        assertTrue(backfills.isEmpty())
        // Yesterday closed at 4,000 and stays there.
        assertEquals(4000, rollovers[0].first.steps)

        // The cap is on the recovery, not the day: counting continues past it.
        assertEquals(650, engine.onCounterSample(5050f, now())!!.steps)
    }

    @Test
    fun aWeekLongKillCannotMintAWeekOfStepsOnOneDayUnderTodayCapped() {
        engine.gapRecovery = StepCounterEngine.GapRecovery.TODAY_CAPPED
        // Default cap: 20,000.
        state.activeDate = DateKeys.format(DateKeys.parse(DateKeys.today()).minusDays(7))
        state.anchorValue = 0f
        state.anchorSteps = 0
        state.lastRawValue = 1000f
        state.stepsToday = 1000
        state.lastEventAt = bootAt(state.activeDate, 20)
        fakeBoot = bootAt(state.activeDate, 6)
        fakeNow = bootAt(DateKeys.today(), 9)

        // 100,000 on the counter since the last reading a week ago.
        val first = engine.onCounterSample(101_000f, now())!!
        assertEquals(20_000, first.steps)
        assertEquals(20_000, first.recoveredSteps)
        assertTrue(backfills.isEmpty())
    }

    @Test
    fun recoveredShareIsZeroForADayCountedLive() {
        engine.onCounterSample(50f, now())
        val snapshot = engine.onCounterSample(120f, now())!!
        assertEquals(120, snapshot.steps)
        assertEquals(0, snapshot.recoveredSteps)
        assertEquals(0, engine.consumeCommit().recoveredSteps)
    }

    @Test
    fun rolloverClosesTheDayWithItsRecoveredShareAndStartsTheNewDayAtZero() {
        // Yesterday had 300 of its 900 recovered earlier.
        state.activeDate = DateKeys.yesterday()
        state.anchorValue = 4100f
        state.anchorSteps = 0
        state.lastRawValue = 5000f
        state.stepsToday = 900
        state.recoveredToday = 300
        state.lastEventAt = bootAt(DateKeys.yesterday(), 23) + 59 * 60_000L + 30_000L
        fakeBoot = bootAt(DateKeys.yesterday(), 8)
        fakeNow = bootAt(DateKeys.today(), 0) + 30_000L

        val first = engine.onCounterSample(5010f, now())!!
        assertEquals(300, rollovers[0].first.recoveredSteps)
        assertEquals(900, rollovers[0].first.steps)
        // Today's 5 straddled midnight and were apportioned; that is all
        // today has recovered.
        assertEquals(5, first.steps)
        assertEquals(5, first.recoveredSteps)
    }

    @Test
    fun resetTodayClearsTheRecoveredShareWithTheTotal() {
        state.activeDate = DateKeys.yesterday()
        state.anchorValue = 0f
        state.anchorSteps = 0
        state.lastRawValue = 4000f
        state.stepsToday = 4000
        state.lastEventAt = bootAt(DateKeys.yesterday(), 22)
        fakeBoot = bootAt(DateKeys.yesterday(), 6)
        fakeNow = bootAt(DateKeys.today(), 8)
        engine.onCounterSample(5000f, now())
        assertEquals(800, state.recoveredToday)

        engine.resetToday()
        assertEquals(0, state.recoveredToday)
        assertEquals(0, engine.snapshot().recoveredSteps)
    }

    @Test
    fun overnightKillUnderTodayPolicyCreditsEverythingToToday() {
        engine.gapRecovery = StepCounterEngine.GapRecovery.TODAY
        state.activeDate = DateKeys.yesterday()
        state.anchorValue = 0f
        state.anchorSteps = 0
        state.lastRawValue = 4000f
        state.stepsToday = 4000
        state.lastEventAt = bootAt(DateKeys.yesterday(), 22)
        fakeBoot = bootAt(DateKeys.yesterday(), 6)
        fakeNow = bootAt(DateKeys.today(), 8)

        assertEquals(1000, engine.onCounterSample(5000f, now())!!.steps)
        assertTrue(backfills.isEmpty())
    }

    @Test
    fun midnightRolloverFinalisesPreviousDay() {
        // Steady state late yesterday: 900 steps, last reading 5000 at 23:59:30.
        state.activeDate = DateKeys.yesterday()
        state.anchorValue = 4100f
        state.anchorSteps = 0
        state.lastRawValue = 5000f
        state.stepsToday = 900
        state.lastEventAt = bootAt(DateKeys.yesterday(), 23) + 59 * 60_000L + 30_000L
        fakeBoot = bootAt(DateKeys.yesterday(), 8)
        // The next sample lands 30 s after midnight with 10 more steps.
        fakeNow = bootAt(DateKeys.today(), 0) + 30_000L

        val first = engine.onCounterSample(5010f, now())

        assertEquals(1, rollovers.size)
        assertEquals(DateKeys.yesterday(), rollovers[0].first.date)
        assertEquals(900, rollovers[0].first.steps)
        assertEquals(DateKeys.today(), rollovers[0].second)
        assertEquals(DateKeys.today(), state.activeDate)

        // The 10 steps straddled midnight evenly: 5 back to yesterday, 5 today.
        assertEquals(5, first!!.steps)
        assertEquals(listOf(mapOf(DateKeys.yesterday() to 5)), backfills)

        // Yesterday's cumulative reading must not leak into today.
        assertEquals(35, engine.onCounterSample(5040f, now())!!.steps)
    }

    @Test
    fun pausedStepsAreDiscardedAndResumeDoesNotBackfill() {
        engine.onCounterSample(100f, now())

        engine.setPaused(true)
        assertNull(engine.onCounterSample(150f, now()))
        assertEquals(100, state.stepsToday)

        engine.setPaused(false)
        // The first sample after resume re-pins the anchor; the 60 steps taken
        // while paused stay out of the total.
        assertNull(engine.onCounterSample(160f, now()))
        assertEquals(100, state.stepsToday)

        assertEquals(115, engine.onCounterSample(175f, now())!!.steps)
    }

    @Test
    fun detectorFallbackIncrementsDirectly() {
        repeat(5) { engine.onDetectorSample(1, now()) }
        assertEquals(5, state.stepsToday)

        engine.setPaused(true)
        assertNull(engine.onDetectorSample(1, now()))
        assertEquals(5, state.stepsToday)
    }

    @Test
    fun commitThresholdControlsDatabaseWrites() {
        repeat(9) { index -> engine.onCounterSample((index + 1).toFloat(), now()) }
        assertFalse(engine.shouldCommit(10))

        assertNotNull(engine.onCounterSample(10f, now()))
        assertTrue(engine.shouldCommit(10))

        val totals = engine.consumeCommit()
        assertEquals(10, totals.steps)
        assertEquals(DateKeys.today(), totals.date)
        assertFalse(engine.shouldCommit(10))
    }

    @Test
    fun resetTodayClearsTotalButKeepsCountingAfterwards() {
        engine.onCounterSample(500f, now())
        engine.resetToday()

        assertEquals(0, state.stepsToday)
        assertEquals(0, engine.snapshot().steps)

        // Counts on from the last reading rather than replaying the 500: the
        // 10 steps since the reset are today's, the 500 before it are not.
        assertEquals(10, engine.onCounterSample(510f, now())!!.steps)
        assertEquals(30, engine.onCounterSample(530f, now())!!.steps)
    }

    @Test
    fun wallClockJumpIsNotMistakenForAReboot() {
        engine.onCounterSample(5000f, now())
        assertEquals(5000, state.stepsToday)

        // The clock moves forward three hours - a manual change, or an NTP
        // correction on a device whose RTC was wrong at boot. currentBootId() is
        // derived from the wall clock so it moves too, but the counter never
        // restarted and its reading still contains every step in stepsToday.
        fakeBoot = bootAt(DateKeys.today(), 4)

        // Not a reboot, so the one step since the last reading is exactly
        // that - one step - rather than 5,001 steps since boot.
        assertEquals(5001, engine.onCounterSample(5001f, now())!!.steps)
        assertEquals(5011, engine.onCounterSample(5011f, now())!!.steps)
    }

    @Test
    fun restartAfterProcessDeathRecoversStepsCountedWhileGone() {
        engine.onCounterSample(2000f, now())
        engine.onCounterSample(8000f, now())
        assertEquals(8000, state.stepsToday)

        // The OS kills the process and START_STICKY brings the service back: a
        // fresh engine over the same persisted state, and a start that is not a
        // user action. The hardware counted 3,000 more steps in the meantime.
        val restarted = StepCounterEngine(
            state,
            MetricsCalculator(StepTrackerConfig()),
            { fakeElapsed },
            { fakeBoot },
            { fakeNow }
        )
        restarted.setPaused(false)
        restarted.reconcile()

        assertEquals(11000, restarted.onCounterSample(11000f, now())!!.steps)
    }

    @Test
    fun counterSampleAfterDetectorFallbackDoesNotReplayStepsSinceBoot() {
        // A dual-sensor device that fell back to the detector and later managed
        // to register the counter, which has been running since boot.
        repeat(200) { engine.onDetectorSample(1, now()) }
        assertEquals(200, state.stepsToday)

        assertNull(engine.onCounterSample(4000f, now()))
        assertEquals(200, state.stepsToday)

        assertEquals(250, engine.onCounterSample(4050f, now())!!.steps)
    }

    @Test
    fun localDateMovingBackwardsAdoptsTheEarlierDayWithoutClaimingItsSteps() {
        // Flying west across the date line: the state was written under
        // tomorrow's key and the device now reports today.
        val tomorrow = DateKeys.format(DateKeys.parse(DateKeys.today()).plusDays(1))
        state.activeDate = tomorrow
        state.anchorValue = 100f
        state.anchorSteps = 0
        state.lastRawValue = 3100f
        state.stepsToday = 3000

        assertNull(engine.onCounterSample(3100f, now()))

        assertEquals(DateKeys.today(), state.activeDate)
        assertEquals(1, rollovers.size)
        assertEquals(tomorrow, rollovers[0].first.date)
        assertEquals(3000, rollovers[0].first.steps)
        assertEquals(0, state.stepsToday)

        // The caller restores the adopted day's stored total, recovered
        // share included, so the day does not read as fully observed.
        engine.seedActiveDay(DateKeys.today(), 9000, recoveredSteps = 1200)
        assertEquals(9000, state.stepsToday)
        assertEquals(1200, state.recoveredToday)

        // Seeding clears the anchor; the next sample claims exactly the delta
        // since the last reading on top of the seeded total.
        assertEquals(9030, engine.onCounterSample(3130f, now())!!.steps)
        assertEquals(9060, engine.onCounterSample(3160f, now())!!.steps)
    }

    @Test
    fun rebootIsDetectedEvenWhenTheReadingDidNotGoBackwards() {
        // A short previous session leaves the counter low.
        engine.onCounterSample(200f, now())
        assertEquals(200, state.stepsToday)

        // Reboot at 03:00 today. Uptime restarts, and by the time the first
        // sample arrives the device has already out-counted the old session, so
        // the reading alone proves nothing - only the uptime does.
        fakeBoot = bootAt(DateKeys.today(), 3)
        fakeElapsed = 30_000L

        assertEquals(2200, engine.onCounterSample(2000f, now())!!.steps)
    }

    @Test
    fun firstEverSampleMarksWhereCoverageBegins() {
        context.getSharedPreferences(StepStateStore.PREFS_NAME, Context.MODE_PRIVATE)
            .edit().clear().commit()
        state.activeDate = DateKeys.today()
        fakeBoot = bootAt(DateKeys.yesterday(), 7)
        assertEquals(0L, state.coverageStartAt)

        // Counter path: an install at noon covers the day from noon.
        engine.onCounterSample(20_000f, now())
        assertEquals(fakeNow, state.coverageStartAt)

        // Detector path, same rule.
        context.getSharedPreferences(StepStateStore.PREFS_NAME, Context.MODE_PRIVATE)
            .edit().clear().commit()
        state.activeDate = DateKeys.today()
        engine.onDetectorSample(1, now())
        assertEquals(fakeNow, state.coverageStartAt)

        // Midnight clears it: the new day is covered from its start.
        state.activeDate = DateKeys.yesterday()
        engine.reconcile()
        assertEquals(0L, state.coverageStartAt)
    }

    @Test
    fun freshInstallOnAnOldBootDoesNotInventHistory() {
        // Never seen a reading, device up since yesterday morning with
        // 20,000 steps on the counter. None of that is this app's to claim.
        context.getSharedPreferences(StepStateStore.PREFS_NAME, Context.MODE_PRIVATE)
            .edit().clear().commit()
        state.activeDate = DateKeys.today()
        fakeBoot = bootAt(DateKeys.yesterday(), 7)

        assertNull(engine.onCounterSample(20_000f, now()))
        assertEquals(0, state.stepsToday)
        assertTrue(backfills.isEmpty())

        assertEquals(25, engine.onCounterSample(20_025f, now())!!.steps)
    }

    @Test
    fun freshInstallOnTodaysBootClaimsStepsSinceBoot() {
        context.getSharedPreferences(StepStateStore.PREFS_NAME, Context.MODE_PRIVATE)
            .edit().clear().commit()
        state.activeDate = DateKeys.today()
        fakeBoot = bootAt(DateKeys.today(), 7)

        assertEquals(3000, engine.onCounterSample(3000f, now())!!.steps)
        assertTrue(backfills.isEmpty())
    }

    @Test
    fun commitThresholdOfZeroDoesNotCommitOnEverySample() {
        // `pendingCommit >= 0` is true with nothing pending, which would make
        // every sample a database write plus two aggregate queries.
        assertFalse(engine.shouldCommit(0))

        engine.onCounterSample(1f, now())
        assertTrue(engine.shouldCommit(0))

        engine.consumeCommit()
        assertFalse(engine.shouldCommit(0))
    }

    @Test
    fun metricsDeriveStrideFromHeightWhenNotOverridden() {
        val metrics = MetricsCalculator(
            StepTrackerConfig(heightCm = 175.0, weightKg = 75.0, strideLengthM = 0.0)
        )
        // 175cm * 0.414 = 0.7245 m per step
        assertEquals(724.5, metrics.distance(1000), 0.5)
        // 0.57 kcal/kg/km * 75kg * 0.7245km
        assertEquals(30.97, metrics.calories(1000), 0.5)
        assertEquals(50, metrics.goalPercent(5000, 10_000))
    }

    @Test
    fun calendarWeekIsMondayAnchoredAndSevenDaysLong() {
        val (start, end) = DateKeys.calendarWeek()
        assertEquals(DayOfWeek.MONDAY, DateKeys.parse(start).dayOfWeek)
        assertEquals(7, DateKeys.daysBetween(start, end))
        assertTrue(DateKeys.today() in start..end)
    }
}
