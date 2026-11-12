package com.steptrackerpro.core

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * What the accelerometer says is moving the phone, over the last few seconds.
 * Reported as `IntegrityReport.motionGate.verdict` so a user can watch it
 * change while they walk, sit in a bus, or ride.
 */
enum class MotionVerdict(val jsValue: String) {
    /** Too few samples, or none recent enough to speak for now. */
    UNKNOWN("unknown"),

    /** Movement under the noise floor: a phone held steady, or lying down. */
    STILL("still"),

    /** A walking or running rhythm. */
    GAIT("gait"),

    /**
     * Movement with no gait in it: a bus seat, a car's floor, a bicycle's
     * frame, a train. The one verdict the gate rejects steps on.
     */
    VIBRATION("vibration");

    companion object {
        fun from(value: String?): MotionVerdict =
            entries.firstOrNull { it.jsValue == value } ?: UNKNOWN
    }
}

/**
 * The thresholds [GaitGate] judges a window by. The defaults are starting
 * points chosen against synthetic traces and meant to be tuned on the
 * devices an app actually ships to; JS presents them as `motionGate`.
 */
data class GaitGateRules(
    /** How much of the recent past one verdict is formed from. */
    val windowMs: Long = DEFAULT_WINDOW_MS,
    /** How often a fresh verdict is formed, at most. */
    val slideMs: Long = DEFAULT_SLIDE_MS,
    /**
     * Below this variance of the mean-removed magnitude, in (m/s²)², the
     * phone is held still - and a still phone is never gated, because the
     * hardware counter has no vibration to mistake for a step and an
     * accelerometer that stopped delivering looks exactly like this.
     */
    val stillVariance: Double = DEFAULT_STILL_VARIANCE,
    /**
     * Share of the gait band's energy the strongest frequency must hold for
     * the rhythm to be gait. A walk keeps its energy in one peak and its
     * harmonics; a vehicle smears it across the band.
     */
    val minPeakRatio: Double = DEFAULT_MIN_PEAK_RATIO,
    /**
     * Mean crossings a second above which the signal is buzz rather than
     * gait. A clean oscillation crosses twice per cycle, so a 3 Hz sprint
     * sits near 6; road and engine buzz runs far higher.
     */
    val maxZeroCrossingRate: Double = DEFAULT_MAX_ZERO_CROSSING_RATE,
    /**
     * Unbroken vibration this long before the gate starts rejecting. It is
     * what keeps the first second of a walk out of a vehicle - or out of
     * any other shaking - from being thrown away.
     */
    val engageMs: Long = DEFAULT_ENGAGE_MS,
    /**
     * A verdict further than this from the steps being judged says nothing
     * about them, and they are counted. Every way the gate can go blind -
     * a sensor that stopped delivering, a batch replayed late, a window that
     * never filled - ends here, counting the steps.
     */
    val staleMs: Long = DEFAULT_STALE_MS
) {
    companion object {
        const val DEFAULT_WINDOW_MS = 3_000L
        const val DEFAULT_SLIDE_MS = 1_000L

        /** 0.14 m/s² RMS: above sensor noise on a table, below any vehicle a counter reacts to. */
        const val DEFAULT_STILL_VARIANCE = 0.02

        /**
         * Low on purpose. A walk with the phone in a pocket spreads energy
         * over the step frequency and its harmonics, so its peak holds much
         * less of the band than a pure tone would; what a vehicle does is
         * leave no peak worth the name at all.
         */
        const val DEFAULT_MIN_PEAK_RATIO = 0.12

        const val DEFAULT_MAX_ZERO_CROSSING_RATE = 9.0
        const val DEFAULT_ENGAGE_MS = 2_000L
        const val DEFAULT_STALE_MS = 4_000L
    }
}

/**
 * Tells a walking rhythm from a machine's vibration, continuously, from the
 * accelerometer, so steps the hardware counter reports while the phone is
 * being carried by a bus or a bicycle can be kept out of the total as they
 * arrive.
 *
 * Per sliding window - [GaitGateRules.windowMs] of samples, re-judged every
 * [GaitGateRules.slideMs] - the mean-removed accelerometer magnitude gives:
 *
 *  - **variance**, which says whether anything is happening at all;
 *  - **zero-crossing rate**, which a buzz inflates far past what an
 *    oscillation at a human cadence can produce;
 *  - **the strongest frequency in the gait band and its share of that
 *    band's energy**, found by [MotionBand] at the samples' own timestamps,
 *    so a batched delivery needs no resampling.
 *
 * The verdicts are deliberately lopsided: [MotionVerdict.VIBRATION] is the
 * only one that rejects anything, it has to hold for [GaitGateRules.engageMs]
 * before it does, and a single gait window ends it. Every uncertainty -
 * too few samples, a stale window, an untimed sample, a batch covering more
 * time than one window - counts the steps. Losing a real step is worse than
 * keeping a false one, so the gate is built to leak rather than eat.
 *
 * Pure Kotlin apart from the sample feed, so the rules are pinned by JVM
 * tests with synthetic gait and vehicle traces.
 */
class GaitGate(val rules: GaitGateRules = GaitGateRules()) {

    /** Samples for the current window, oldest at [head]. */
    private val capacity =
        ((rules.windowMs / 1_000.0) * MAX_RATE_HZ).toInt().coerceIn(MIN_SAMPLES, MAX_SAMPLES)
    private val magnitudes = DoubleArray(capacity)
    private val times = LongArray(capacity)
    private var head = 0
    private var size = 0

    /** The last verdict formed. [MotionVerdict.UNKNOWN] until a window fills. */
    @Volatile
    var verdict: MotionVerdict = MotionVerdict.UNKNOWN
        private set

    @Volatile
    private var lastVerdictAt: Long = 0L

    /** When the current unbroken run of vibration began; 0 when there is none. */
    @Volatile
    private var vibrationSince: Long = 0L

    /** When the current unbroken run of gait began; 0 when there is none. */
    @Volatile
    private var gaitSince: Long = 0L

    private var lastClassifiedAt: Long = 0L

    /**
     * Feeds one accelerometer sample.
     *
     * @param atMs when the sample was taken, epoch ms - the same base the
     *   step samples are placed on, so a verdict can be matched to the steps
     *   it covers. See [SensorEventTime].
     * @return the fresh verdict when this sample closed a window, else null.
     */
    @Synchronized
    fun onSample(x: Float, y: Float, z: Float, atMs: Long): MotionVerdict? {
        // A sample older than one already seen is a batch arriving out of
        // order; the window it belongs to has been judged or has gone.
        if (size > 0 && atMs < newestAt()) return null
        // Android delivers a sensor at the fastest rate any app on the phone
        // asked for, not the rate asked for here. Past the rate the window
        // is sized for, it would hold less than half a window and never
        // judge anything, so the extra samples are let go.
        if (size > 0 && atMs - newestAt() < MIN_SAMPLE_GAP_MS) return null
        push(sqrt((x * x + y * y + z * z).toDouble()), atMs)
        dropOlderThan(atMs - rules.windowMs)
        if (lastClassifiedAt > 0L && atMs - lastClassifiedAt < rules.slideMs) return null
        if (size < MIN_SAMPLES) return null
        val span = newestAt() - oldestAt()
        // Half a window is the least history a verdict may rest on: enough
        // for a cadence to show, soon enough to catch a ride starting.
        if (span < rules.windowMs / 2) return null
        lastClassifiedAt = atMs

        val next = classify(span)
        val previous = verdict
        verdict = next
        lastVerdictAt = atMs
        vibrationSince = when {
            next != MotionVerdict.VIBRATION -> 0L
            previous == MotionVerdict.VIBRATION && vibrationSince > 0L -> vibrationSince
            // The run starts now, not at the window's start: the window
            // reaching back over a walk that just ended must not count
            // towards engaging the gate.
            else -> atMs
        }
        gaitSince = when {
            next != MotionVerdict.GAIT -> 0L
            previous == MotionVerdict.GAIT && gaitSince > 0L -> gaitSince
            else -> atMs
        }
        return next
    }

    /** The verdict as it stands for [atMs]; [MotionVerdict.UNKNOWN] when none is recent enough. */
    @Synchronized
    fun verdictFor(atMs: Long): MotionVerdict =
        if (lastVerdictAt == 0L || abs(atMs - lastVerdictAt) > rules.staleMs) {
            MotionVerdict.UNKNOWN
        } else {
            verdict
        }

    /**
     * How many of [steps] the gate rejects: all of them, or none. The steps
     * were counted between [fromMs] and [toMs], epoch ms, [fromMs] being 0
     * when there was no previous sample to measure from.
     */
    @Synchronized
    fun gated(fromMs: Long, toMs: Long, steps: Int): Int {
        if (steps <= 0) return 0
        if (verdictFor(toMs) != MotionVerdict.VIBRATION) return 0
        if (vibrationSince <= 0L || toMs - vibrationSince < rules.engageMs) return 0
        // A delta spanning more than one verdict's reach is a batch or a
        // gap - a dead process, a hub's FIFO flushed at once. One window
        // cannot speak for minutes, so it does not try.
        if (fromMs > 0L && toMs - fromMs > rules.staleMs) return 0
        return steps
    }

    /**
     * Whether the accelerometer has read as walking, without a break, for
     * at least [GAIT_OVERRIDES_VEHICLE_MS] up to [atMs] - long enough to
     * outvote Activity Recognition, whose word that the phone left a
     * vehicle arrives late, and on some phones not at all. See [MotionGate].
     */
    @Synchronized
    fun sustainedGait(atMs: Long): Boolean =
        verdictFor(atMs) == MotionVerdict.GAIT && gaitSince > 0L &&
            atMs - gaitSince >= GAIT_OVERRIDES_VEHICLE_MS

    /** Forgets the window and the verdict; the next samples start a fresh one. */
    @Synchronized
    fun reset() {
        head = 0
        size = 0
        verdict = MotionVerdict.UNKNOWN
        lastVerdictAt = 0L
        vibrationSince = 0L
        gaitSince = 0L
        lastClassifiedAt = 0L
    }

    // ---- the verdict -------------------------------------------------------

    private fun classify(spanMs: Long): MotionVerdict {
        val n = size
        var sum = 0.0
        for (i in 0 until n) sum += magnitudeAt(i)
        val mean = sum / n
        val signal = DoubleArray(n) { magnitudeAt(it) - mean }
        var energy = 0.0
        for (value in signal) energy += value * value
        // Orientation and gravity drop out with the mean, so a phone in a
        // pocket, a bag or a hand is judged the same way.
        if (energy / n < rules.stillVariance) return MotionVerdict.STILL

        // Counted with hysteresis: a crossing is a swing from one side of
        // the band to the other, not every flicker of sensor noise across
        // zero. A gentle walk sampled at 100 Hz chatters across the mean
        // several times on each slow slope, and counted bare, that read as
        // buzz. A vehicle's buzz swings through the band on every cycle and
        // is counted exactly as before.
        if (crossings(signal, n, CROSSING_BAND * sqrt(energy / n)) / (spanMs / 1_000.0) >
            rules.maxZeroCrossingRate
        ) {
            return MotionVerdict.VIBRATION
        }

        // Scanned wider than the gait band on purpose. Restricted to the
        // band, a bus's half-hertz sway leaks across the band's edge and is
        // reported as a strong peak at 1 Hz - the gate would read a coach on
        // a motorway as a slow walk. Scanned wide, the sway peaks where it
        // actually is, outside the band, and is vibration.
        val at = LongArray(n) { timeAt(it) }
        val power = MotionBand.spectrum(signal, at, n, SCAN_MIN_HZ, SCAN_MAX_HZ, BIN_HZ)
        if (power.isEmpty()) return MotionVerdict.UNKNOWN
        var total = 0.0
        var top = 0
        for (i in power.indices) {
            total += power[i]
            if (power[i] > power[top]) top = i
        }
        if (total <= 0.0) return MotionVerdict.UNKNOWN
        return if (isGait(power, top, total)) MotionVerdict.GAIT else MotionVerdict.VIBRATION
    }

    /**
     * Whether the strongest line in [power] is a walk's: the step rate
     * itself, the stride (half of it), or the step's first harmonic (twice
     * it) - with the step rate there in its own right, and the three of
     * them holding [GaitGateRules.minPeakRatio] of the energy.
     *
     * Only asking for the step rate called most real walks vibration. A
     * phone in a trouser pocket rides one leg, so the strongest line is
     * the stride at 0.75-0.9 Hz, below the gait band; a heel strike puts
     * the most energy in the harmonic, above it. Both read as a machine,
     * after two seconds the gate engaged, and the walk was gone.
     *
     * Requiring the step rate to stand at [STEP_LINE_MIN_SHARE] of the
     * strongest line is what keeps a coach's 0.5 Hz sway from passing as
     * the stride of a 1 Hz walk: a sway has nothing at twice its rate but
     * the leakage of its own line.
     */
    private fun isGait(power: DoubleArray, top: Int, total: Double): Boolean {
        val topHz = hzAt(top)
        val candidates = buildList {
            add(topHz) // the strongest line is the step
            add(topHz * 2.0) // it is the stride
            add(topHz / 2.0) // it is the step's harmonic
        }
        for (step in candidates) {
            if (step < MIN_GAIT_HZ - 1e-9 || step > MAX_GAIT_HZ + 1e-9) continue
            val stepPower = near(power, step)
            if (stepPower < STEP_LINE_MIN_SHARE * power[top]) continue
            val family = stepPower + near(power, step / 2.0) + near(power, step * 2.0)
            if (family / total >= rules.minPeakRatio) return true
        }
        return false
    }

    private fun hzAt(index: Int): Double = SCAN_MIN_HZ + index * BIN_HZ

    /**
     * The power at [hz], taken as the strongest of the bins either side of
     * it - three seconds resolve a third of a hertz, so a line spreads over
     * several bins, and half or twice a bin's frequency rarely falls on a
     * bin. Zero outside the scan.
     */
    private fun near(power: DoubleArray, hz: Double): Double {
        val centre = Math.round((hz - SCAN_MIN_HZ) / BIN_HZ).toInt()
        var best = 0.0
        for (i in centre - 1..centre + 1) if (i in power.indices && power[i] > best) best = power[i]
        return best
    }

    /** Swings of [signal] from below -[band] to above +[band], and back. */
    private fun crossings(signal: DoubleArray, n: Int, band: Double): Int {
        var count = 0
        var side = 0
        for (i in 0 until n) {
            val now = when {
                signal[i] > band -> 1
                signal[i] < -band -> -1
                else -> continue
            }
            if (side != 0 && now != side) count++
            side = now
        }
        return count
    }

    // ---- the window --------------------------------------------------------

    private fun push(magnitude: Double, atMs: Long) {
        val slot = (head + size) % capacity
        magnitudes[slot] = magnitude
        times[slot] = atMs
        if (size < capacity) {
            size++
        } else {
            // Full at a delivery rate the window was not sized for: the
            // oldest sample goes, so the window stays the recent past.
            head = (head + 1) % capacity
        }
    }

    private fun dropOlderThan(cutoffMs: Long) {
        while (size > 0 && times[head] < cutoffMs) {
            head = (head + 1) % capacity
            size--
        }
    }

    private fun magnitudeAt(index: Int): Double = magnitudes[(head + index) % capacity]

    private fun timeAt(index: Int): Long = times[(head + index) % capacity]

    private fun oldestAt(): Long = times[head]

    private fun newestAt(): Long = times[(head + size - 1) % capacity]

    companion object {
        /**
         * Fewer samples than this and there is no window worth judging. Low
         * enough that a one-second window still fills on a hub delivering
         * slower than asked: a window that can never fill counts every step,
         * which is safe but useless.
         */
        const val MIN_SAMPLES = 16

        /** Three seconds at 100 Hz; a hub delivering faster loses its oldest samples. */
        const val MAX_SAMPLES = 1_024

        /** What the window is sized for, samples a second. */
        const val MAX_RATE_HZ = 100.0

        /** 60 steps a minute: slower than this is not walking. */
        const val MIN_GAIT_HZ = 1.0

        /** 198 steps a minute, past a sprint's cadence. */
        const val MAX_GAIT_HZ = 3.3

        /** Below a sway's slowest, so a sway peaks at itself and not at the band's edge. */
        const val SCAN_MIN_HZ = 0.3

        /**
         * Twice the gait band's top, so a fast walk's harmonic - where a
         * heel strike puts most of its energy - is inside the scan.
         */
        const val SCAN_MAX_HZ = 6.6

        /**
         * How strong the step rate itself must be, against the strongest
         * line, for that line to be taken as the stride or a harmonic of it.
         */
        const val STEP_LINE_MIN_SHARE = 0.25

        /**
         * The half-width of the band a crossing has to swing through, as a
         * share of the window's RMS.
         */
        const val CROSSING_BAND = 0.15

        /** 1000 / [MAX_RATE_HZ], less a little for jitter in the timestamps. */
        const val MIN_SAMPLE_GAP_MS = 8L

        /**
         * Unbroken walking this long overrides Activity Recognition saying
         * the phone is in a vehicle. Its exit from a car or a bus comes a
         * minute or more after the user has stepped off, and every step of
         * the walk away was being thrown out meanwhile.
         */
        const val GAIT_OVERRIDES_VEHICLE_MS = 10_000L

        /**
         * Coarser than [MotionWindowSampler]'s: three seconds resolve about
         * a third of a hertz whatever the bin, and this scan runs every
         * second for as long as tracking does.
         */
        const val BIN_HZ = 0.1
    }
}

/**
 * The gate as the counting path asks it: the accelerometer's verdict, with
 * Activity Recognition - when the host app ships Play Services and
 * `fraudDetection.activityRecognition` is on - as a shortcut for the one
 * thing it is sure about.
 */
object MotionGate {

    /**
     * How many of [steps] stay out of the day's total.
     *
     * @param inVehicle Activity Recognition says the phone is in a vehicle
     *   or on a bicycle. Enough on its own - it is a far better witness than
     *   three seconds of accelerometer - until the accelerometer has read as
     *   walking for [GaitGate.GAIT_OVERRIDES_VEHICLE_MS] straight: Activity
     *   Recognition reports leaving a vehicle late, and its state is not
     *   aged, so one missed exit used to gate every step that followed.
     * @param timed false when only the steps' arrival is known. Nothing
     *   untimed is ever gated - there is no window to judge it by, and a
     *   screen-off walk arriving in one batch is exactly what would be lost.
     */
    fun gated(
        gate: GaitGate?,
        inVehicle: Boolean,
        fromMs: Long,
        toMs: Long,
        steps: Int,
        timed: Boolean
    ): Int {
        if (steps <= 0) return 0
        // No gate, nothing gated - the Activity Recognition shortcut
        // included. `motionGate.enabled` governs both halves, or an app that
        // turned only the detector on would find steps quietly missing.
        if (gate == null) return 0
        if (!timed) return 0
        if (inVehicle && !gate.sustainedGait(toMs)) return steps
        return gate.gated(fromMs, toMs, steps)
    }
}
