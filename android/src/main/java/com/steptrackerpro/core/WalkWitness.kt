package com.steptrackerpro.core

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * What the accelerometer says is moving the phone, over the last few seconds.
 * Reported as `IntegrityReport.motionGate.verdict`.
 *
 * None of these holds a step back. Only Activity Recognition saying the
 * phone is in a vehicle does that - see [VehicleGate]; [GAIT] is how the
 * accelerometer argues the other way.
 */
enum class MotionVerdict(val jsValue: String) {
    /** No verdict recent enough to speak for now. */
    UNKNOWN("unknown"),

    /** Movement under the noise floor: a phone held steady, or lying down. */
    STILL("still"),

    /** A walking or running rhythm: the same shape, again, a step or a stride later. */
    GAIT("gait"),

    /** Movement with no such rhythm in it. Says nothing about what causes it. */
    VIBRATION("vibration");

    companion object {
        fun from(value: String?): MotionVerdict =
            entries.firstOrNull { it.jsValue == value } ?: UNKNOWN
    }
}

/** The one threshold of [WalkWitness] an app can move; JS presents it as `motionGate.stillVariance`. */
data class WitnessRules(
    /**
     * Below this variance of the accelerometer magnitude, in (m/s²)², the
     * phone is still.
     */
    val stillVariance: Double = DEFAULT_STILL_VARIANCE
) {
    companion object {
        /** 0.14 m/s² RMS: above sensor noise on a table, below any ride. */
        const val DEFAULT_STILL_VARIANCE = 0.02
    }
}

/**
 * Recognises walking from the accelerometer, so that [VehicleGate] can be
 * told - while Activity Recognition still says "in a vehicle" - that the
 * user has in fact got off and is walking away.
 *
 * It answers one question, and only in one direction: *is this, beyond
 * doubt, a person walking?* It is never asked whether something is a
 * vehicle. That question has no safe answer from an accelerometer: the
 * hardware step counter is itself an accelerometer gait classifier, so the
 * moments it is fooled are exactly the moments the signal looks like gait,
 * and "no walking rhythm" is also what cooking, shopping and sport look
 * like. Releases 2.8.0 to 2.8.2 held steps back on "no rhythm"; measured
 * against recorded walks, that threw away 3-15% of real steps on average
 * and most of a walk for a phone in a back pocket.
 *
 * How a second is judged. The magnitude is put on a uniform 25 Hz grid,
 * whatever rate the hub delivers at, and smoothed over 0.16 s - which
 * leaves a step's 0.7-3.3 Hz alone and removes an engine's 8-15 Hz tone,
 * as periodic as any walk and nothing like one. Over the last three
 * seconds the smoothed signal is compared with itself a step-or-stride
 * later (0.32-1.6 s). A walk repeats, in a pocket, a hand or a bag, at the
 * step or at the stride; road vibration does not. That is [MotionVerdict.GAIT]
 * for the second.
 *
 * How walking is confirmed. One second proves nothing - 6% of the seconds
 * on a real bus pass - so [confirmedWalking] asks for [CONFIRM_GAIT_SECONDS]
 * of the last [CONFIRM_SPAN_MS] thirty. On the recordings these numbers were
 * chosen against, every one of 131 real walks (27 people; phone in a hand,
 * a front or back trouser pocket, a shirt pocket, a backpack and a handbag;
 * slow, normal and fast) was confirmed thirty seconds in, and not one
 * minute of 473 on buses, 1,102 in taxis or 308 on trains was. Pedalling a
 * bicycle with the phone in a pocket *is* confirmed: a leg going round is
 * a rhythm, and nothing here can tell it from a stride.
 *
 * Pure Kotlin, so the rules are pinned by JVM tests.
 */
class WalkWitness(val rules: WitnessRules = WitnessRules()) {

    // ---- the uniform series ------------------------------------------------

    /** The last [SERIES_BINS] bin means, oldest at [seriesHead]. */
    private val series = DoubleArray(SERIES_BINS)
    private var seriesHead = 0
    private var seriesSize = 0

    /** The bin being filled: its index on the [BIN_MS] grid, and what has landed in it. */
    private var bin = NO_BIN
    private var binSum = 0.0
    private var binCount = 0
    private var binsSinceVerdict = 0

    // ---- the verdicts ------------------------------------------------------

    /** One verdict a second for the last [HISTORY] of them, oldest at [historyHead]. */
    private val historyAt = LongArray(HISTORY)
    private val history = arrayOfNulls<MotionVerdict>(HISTORY)
    private var historyHead = 0
    private var historySize = 0

    /** The last verdict formed. [MotionVerdict.UNKNOWN] until a window fills. */
    @Volatile
    var verdict: MotionVerdict = MotionVerdict.UNKNOWN
        private set

    @Volatile
    private var lastVerdictAt: Long = 0L

    /**
     * When the unbroken run of [MotionVerdict.STILL] verdicts up to the last
     * one began; 0 when the last one was not still. Kept apart from the
     * history, which is two minutes long and asked about five.
     */
    private var stillSince: Long = 0L

    private val window = DoubleArray(WINDOW_BINS)

    /**
     * Feeds one accelerometer sample.
     *
     * @param atMs when the sample was taken, epoch ms - the base the step
     *   samples are placed on, so a second here is a second there.
     * @return the fresh verdict when this sample closed a second, else null.
     */
    @Synchronized
    fun onSample(x: Float, y: Float, z: Float, atMs: Long): MotionVerdict? {
        val index = Math.floorDiv(atMs, BIN_MS)
        // Older than the bin being filled: a batch out of order. Its place
        // on the grid has been written already.
        if (bin != NO_BIN && index < bin) return null
        val magnitude = sqrt((x * x + y * y + z * z).toDouble())
        if (bin == NO_BIN || index == bin) {
            bin = index
            binSum += magnitude
            binCount++
            return null
        }
        // The bin that was filling is complete. Android delivers at the
        // fastest rate any app asked for, not the one asked for here, so a
        // bin holds one sample or eight; their mean is the grid's value
        // either way, and averaging is what keeps a faster stream's
        // vibration from folding down onto a step's frequencies.
        val value = binSum / binCount
        val missing = index - bin - 1
        var fresh: MotionVerdict? = null
        if (missing > MAX_FILL_BINS) {
            // A hole: a hub that stopped delivering, a process that slept.
            // What came before cannot be joined to what comes after.
            clearSeries()
        } else {
            fresh = push(value, (bin + 1) * BIN_MS)
            // A bin or two with nothing in it is jitter, or a hub slower
            // than asked; the last value stands in.
            for (k in 1..missing.toInt()) {
                push(value, (bin + 1 + k) * BIN_MS)?.let { fresh = it }
            }
        }
        bin = index
        binSum = magnitude
        binCount = 1
        return fresh
    }

    /**
     * The verdict as it stands for [atMs]; [MotionVerdict.UNKNOWN] when
     * none is within [staleMs] of it.
     */
    @Synchronized
    fun verdictFor(atMs: Long, staleMs: Long = STALE_MS): MotionVerdict =
        if (lastVerdictAt == 0L || abs(atMs - lastVerdictAt) > staleMs) MotionVerdict.UNKNOWN else verdict

    /**
     * In how many of the seconds of the [CONFIRM_SPAN_MS] up to [atMs] the
     * accelerometer showed a walking rhythm. 0 when it has not been
     * watching, or stopped: a witness that saw nothing confirms nothing.
     */
    @Synchronized
    fun gaitSeconds(atMs: Long): Int {
        if (lastVerdictAt == 0L || abs(atMs - lastVerdictAt) > STALE_MS) return 0
        var gait = 0
        for (i in 0 until historySize) {
            val slot = (historyHead + i) % HISTORY
            val at = historyAt[slot]
            if (at > atMs - CONFIRM_SPAN_MS && at <= atMs + STALE_MS && history[slot] == MotionVerdict.GAIT) gait++
        }
        return gait
    }

    /**
     * Whether the accelerometer has shown a walking rhythm in at least
     * [CONFIRM_GAIT_SECONDS] of the [CONFIRM_SPAN_MS] up to [atMs]. False
     * when it has not been watching that long, or stopped.
     */
    fun confirmedWalking(atMs: Long): Boolean = gaitSeconds(atMs) >= CONFIRM_GAIT_SECONDS

    /**
     * Whether a walk already confirmed is still going on at [atMs]: a
     * walking rhythm in at least [KEEP_GAIT_SECONDS] of the last thirty.
     * Lower than it takes to confirm one, on purpose. A walker whose stride
     * reads poorly - a phone being typed on, a loose back pocket - drops
     * below [CONFIRM_GAIT_SECONDS] now and then without having stopped,
     * and a walk taken for over while it goes on has its steps held.
     */
    fun stillWalking(atMs: Long): Boolean = gaitSeconds(atMs) >= KEEP_GAIT_SECONDS

    /**
     * Whether the second a step at [stepAtMs] was taken in showed a walking
     * rhythm: the verdict whose three seconds have the step in their
     * middle. For choosing, once Activity Recognition has said the ride is
     * over, which of the steps held at its end were the walk away.
     */
    @Synchronized
    fun gaitAround(stepAtMs: Long): Boolean {
        val target = stepAtMs + WINDOW_MS / 2
        var best: MotionVerdict? = null
        var bestDistance = WINDOW_MS / 2 + 1
        for (i in 0 until historySize) {
            val slot = (historyHead + i) % HISTORY
            val distance = abs(historyAt[slot] - target)
            if (distance < bestDistance) {
                bestDistance = distance
                best = history[slot]
            }
        }
        return best == MotionVerdict.GAIT
    }

    /**
     * How long, up to the last verdict, every verdict has been
     * [MotionVerdict.STILL] - 0 when the last one was not, or when none is
     * recent enough to speak for [atMs].
     */
    @Synchronized
    fun stillFor(atMs: Long): Long {
        if (lastVerdictAt == 0L || abs(atMs - lastVerdictAt) > STALE_MS) return 0L
        return if (stillSince == 0L) 0L else lastVerdictAt - stillSince
    }

    /** Forgets the series and every verdict; the next samples start afresh. */
    @Synchronized
    fun reset() {
        clearSeries()
        bin = NO_BIN
        binSum = 0.0
        binCount = 0
        historyHead = 0
        historySize = 0
        verdict = MotionVerdict.UNKNOWN
        lastVerdictAt = 0L
        stillSince = 0L
    }

    // ---- the series --------------------------------------------------------

    private fun clearSeries() {
        seriesHead = 0
        seriesSize = 0
        binsSinceVerdict = 0
        binSum = 0.0
        binCount = 0
    }

    /** Appends one grid value; returns a verdict when it completed a second with a full window behind it. */
    private fun push(value: Double, endMs: Long): MotionVerdict? {
        val slot = (seriesHead + seriesSize) % SERIES_BINS
        series[slot] = value
        if (seriesSize < SERIES_BINS) seriesSize++ else seriesHead = (seriesHead + 1) % SERIES_BINS
        binsSinceVerdict++
        if (binsSinceVerdict < BINS_PER_VERDICT || seriesSize < SERIES_BINS) return null
        binsSinceVerdict = 0
        val next = judge()
        stillSince = when {
            next != MotionVerdict.STILL -> 0L
            // A hole in the record is a stretch nobody watched: the run
            // starts again on this side of it.
            stillSince == 0L || endMs - lastVerdictAt > 2 * VERDICT_MS -> endMs - VERDICT_MS
            else -> stillSince
        }
        verdict = next
        lastVerdictAt = endMs
        val at = (historyHead + historySize) % HISTORY
        historyAt[at] = endMs
        history[at] = next
        if (historySize < HISTORY) historySize++ else historyHead = (historyHead + 1) % HISTORY
        return next
    }

    private fun raw(index: Int): Double = series[(seriesHead + index) % SERIES_BINS]

    // ---- the verdict -------------------------------------------------------

    private fun judge(): MotionVerdict {
        // Stillness is judged on the grid as it is: orientation and gravity
        // drop out with the mean, so a phone in a pocket, a bag or a hand is
        // judged the same way.
        var sum = 0.0
        for (i in SMOOTH_TAPS - 1 until SERIES_BINS) sum += raw(i)
        val mean = sum / WINDOW_BINS
        var energy = 0.0
        for (i in SMOOTH_TAPS - 1 until SERIES_BINS) {
            val d = raw(i) - mean
            energy += d * d
        }
        if (energy / WINDOW_BINS < rules.stillVariance) return MotionVerdict.STILL

        // Smoothed to a step's frequencies, then mean-removed.
        var smoothedSum = 0.0
        for (i in 0 until WINDOW_BINS) {
            var s = 0.0
            for (k in 0 until SMOOTH_TAPS) s += raw(i + k)
            window[i] = s / SMOOTH_TAPS
            smoothedSum += window[i]
        }
        val smoothedMean = smoothedSum / WINDOW_BINS
        var total = 0.0
        for (i in 0 until WINDOW_BINS) {
            window[i] -= smoothedMean
            total += window[i] * window[i]
        }
        // What is left after smoothing is too faint to be a step, however
        // regular: an engine at idle, a train on welded rail.
        if (total / WINDOW_BINS < WALK_MIN_VARIANCE) return MotionVerdict.VIBRATION

        // The signal against itself a step-or-stride later. A phone in a
        // trouser pocket repeats at the stride, one in a hand at the step,
        // a heel strike rings at twice it; all of them repeat somewhere in
        // this range, and it does not matter where.
        //
        // "Repeats" means it first stops resembling itself and then does
        // again. Anything slow resembles itself a third of a second later
        // without repeating at all - a coach leaning into a bend, a hand
        // drifting - so only what comes after the resemblance has fallen
        // away counts as the rhythm coming back.
        var best = 0.0
        var fellAway = false
        for (lag in 1..MAX_LAG_BINS) {
            var cross = 0.0
            var head = 0.0
            var tail = 0.0
            for (i in 0 until WINDOW_BINS - lag) {
                cross += window[i] * window[i + lag]
                head += window[i] * window[i]
                tail += window[i + lag] * window[i + lag]
            }
            val scale = sqrt(head * tail)
            val r = if (scale > 0.0) cross / scale else 0.0
            if (r < FALLEN_AWAY) fellAway = true
            if (fellAway && lag >= MIN_LAG_BINS && r > best) best = r
        }
        return if (best >= MIN_PERIODICITY) MotionVerdict.GAIT else MotionVerdict.VIBRATION
    }

    companion object {
        /** The grid: 25 Hz, which is what the service asks the accelerometer for. */
        const val BIN_MS = 40L

        /** Three seconds: two strides of a slow walk, soon enough to be useful. */
        const val WINDOW_MS = 3_000L
        const val WINDOW_BINS = (WINDOW_MS / BIN_MS).toInt()

        /** 0.16 s. Passes 3 Hz at half strength, 8 Hz at a fifth, 12 Hz at a twelfth. */
        const val SMOOTH_TAPS = 4

        /** A window of smoothed values needs this many grid values behind it. */
        const val SERIES_BINS = WINDOW_BINS + SMOOTH_TAPS - 1

        const val VERDICT_MS = 1_000L
        const val BINS_PER_VERDICT = (VERDICT_MS / BIN_MS).toInt()

        /** 0.32 s: a sprint's step. Shorter, and an engine's tone repeats too. */
        const val MIN_LAG_BINS = 8

        /** 1.6 s: the stride of a 75-steps-a-minute amble. */
        const val MAX_LAG_BINS = 40

        /** How alike the signal and its later self must be, 0..1. */
        const val MIN_PERIODICITY = 0.6

        /** How unlike itself the signal must have become first, for a later likeness to be a repeat. */
        const val FALLEN_AWAY = 0.2

        /** (m/s²)² of the smoothed signal: 0.39 m/s² RMS, under a walker typing on the phone. */
        const val WALK_MIN_VARIANCE = 0.15

        /** Thirty seconds, of which [CONFIRM_GAIT_SECONDS] must show a walking rhythm. */
        const val CONFIRM_SPAN_MS = 30_000L
        const val CONFIRM_GAIT_SECONDS = 24

        /**
         * Of the same thirty seconds, how many must still show a walking
         * rhythm for a confirmed walk to count as going on: half. On the
         * recorded walks 6 of 131 fell below [CONFIRM_GAIT_SECONDS] after
         * being confirmed and 2 below this - and on the recorded rides no
         * stretch at or above it lasted a minute (49 s in a taxi, 6 s on a
         * bus, none on a train), so a walk that has stopped is seen to
         * have stopped.
         */
        const val KEEP_GAIT_SECONDS = 15

        /** Grid values a hole may be bridged across: 0.2 s, a hub delivering at 5 Hz. */
        const val MAX_FILL_BINS = 5L

        /** A verdict further than this from the moment asked about says nothing about it. */
        const val STALE_MS = 4_000L

        /** Two minutes of verdicts: what [VehicleGate] can ask about after the fact. */
        const val HISTORY = 130

        private const val NO_BIN = Long.MIN_VALUE
    }
}
