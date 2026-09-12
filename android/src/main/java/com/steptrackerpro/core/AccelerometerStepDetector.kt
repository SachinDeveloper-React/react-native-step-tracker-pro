package com.steptrackerpro.core

import kotlin.math.exp
import kotlin.math.sqrt

/**
 * A software pedometer for phones that expose neither `TYPE_STEP_COUNTER` nor
 * `TYPE_STEP_DETECTOR` - a real population at the bottom of the market
 * (entry-level Tecno, itel, Lava and some Redmi A-series builds), where the
 * accelerometer is the only motion sensor there is.
 *
 * The signal chain, per sample:
 *
 *  1. **Magnitude.** `sqrt(x² + y² + z²)`, so orientation does not matter:
 *     a phone in a pocket, a bag or a hand sees the same walking rhythm.
 *  2. **Gravity removal.** A slow low-pass (τ ≈ 0.5 s) tracks the static
 *     ~9.81 m/s²; subtracting it leaves the linear acceleration of the gait.
 *  3. **Smoothing.** A fast low-pass (τ ≈ 40 ms) knocks the sensor jitter off
 *     without blunting the step impact, which is a 50–150 ms event. Tuned
 *     against impulse-shaped synthetic strikes: 60 ms lost gentle strikes,
 *     30 ms let a car's 5 Hz vibration through.
 *  4. **Peak detection with hysteresis.** Rising through `threshold` arms a
 *     candidate; falling back below a fraction of it fires one. Two crossings
 *     per step, never more, whatever the sample rate.
 *  5. **Cadence gating.** A candidate closer than [minStepIntervalMs] to the
 *     last is bounce, not a step. A candidate further than
 *     [maxStepIntervalMs] from the last ends the walk.
 *  6. **Warm-up.** The first [warmUpSteps] candidates of a walk are held back
 *     and only counted once the run is long enough to be a walk rather than
 *     a phone being picked up. Once walking, every candidate counts.
 *
 * Everything is time-based rather than sample-based, so the detector is
 * indifferent to the delivery rate - 20 Hz on a budget phone, 50 Hz on a
 * flagship, or a batch of the last second arriving at once.
 *
 * Accuracy is a step or two per hundred against a hardware counter on a
 * steady walk, and worse in a vehicle, where road vibration can register as a
 * slow shuffle. A phone that has a hardware counter never reaches this class.
 *
 * Pure Kotlin, so it is tested on the JVM with synthetic gait signals.
 */
class AccelerometerStepDetector(
    /** Metres per second squared above the gravity-removed baseline. */
    private val threshold: Float = DEFAULT_THRESHOLD,
    /** Faster than this between two steps is bounce. 250 ms is 4 steps/s, a sprint. */
    private val minStepIntervalMs: Long = 250L,
    /**
     * Slower than this and the walk has ended. 1.2 s is a shuffle at 0.83
     * steps/s; anything slower is not gait, and a bus or a boat rocking at
     * half a hertz would otherwise pass as one.
     */
    private val maxStepIntervalMs: Long = 1_200L,
    /** Candidates to see before believing the user is walking. */
    private val warmUpSteps: Int = 4,
    /** Jitter smoother time constant, ms. Tuning knob for tests; not exposed in config. */
    private val smoothTauMs: Double = SMOOTH_TAU_MS
) {

    private var gravity = 0f
    private var smoothed = 0f
    private var lastSampleAt = 0L
    private var hasSample = false

    private var armed = false
    private var lastCandidateAt = 0L
    private var pending = 0
    private var walking = false

    /** True while a walk is in progress - used by callers for diagnostics only. */
    val isWalking: Boolean get() = walking

    /**
     * Feeds one accelerometer sample.
     *
     * @param timestampMs the sample's time in any monotonic millisecond base;
     *   only differences are used.
     * @return steps to add to the running total: usually 0 or 1, or
     *   [warmUpSteps] the moment a walk is confirmed and the held-back
     *   candidates are released.
     */
    fun onSample(x: Float, y: Float, z: Float, timestampMs: Long): Int {
        val magnitude = sqrt(x * x + y * y + z * z)
        if (!hasSample) {
            hasSample = true
            gravity = magnitude
            smoothed = 0f
            lastSampleAt = timestampMs
            return 0
        }
        val dtMs = (timestampMs - lastSampleAt).coerceIn(1L, 1_000L)
        lastSampleAt = timestampMs

        // Time-constant filters, so the cut-offs do not move with sample rate.
        val gravityAlpha = exp(-dtMs / GRAVITY_TAU_MS).toFloat()
        gravity = gravityAlpha * gravity + (1f - gravityAlpha) * magnitude
        val linear = magnitude - gravity
        val smoothAlpha = exp(-dtMs / smoothTauMs).toFloat()
        smoothed = smoothAlpha * smoothed + (1f - smoothAlpha) * linear

        if (!armed) {
            if (smoothed > threshold) armed = true
            return 0
        }
        if (smoothed > threshold * RELEASE_FRACTION) return 0
        armed = false
        return onCandidate(timestampMs)
    }

    private fun onCandidate(at: Long): Int {
        val sinceLast = if (lastCandidateAt == 0L) Long.MAX_VALUE else at - lastCandidateAt
        if (sinceLast < minStepIntervalMs) return 0
        if (sinceLast > maxStepIntervalMs) {
            // The previous walk, if any, is over. Whatever was pending never
            // became a walk and is discarded.
            walking = false
            pending = 0
        }
        lastCandidateAt = at
        if (walking) return 1
        pending++
        if (pending < warmUpSteps) return 0
        walking = true
        val released = pending
        pending = 0
        return released
    }

    /** Forgets the current walk and filter state; the next sample starts fresh. */
    fun reset() {
        hasSample = false
        gravity = 0f
        smoothed = 0f
        armed = false
        lastCandidateAt = 0L
        pending = 0
        walking = false
    }

    companion object {
        /**
         * Chosen against synthetic gait: a gentle walk with the phone in a
         * bag peaks around 1.5 m/s² and must count; sensor jitter, a car's
         * 5–12 Hz vibration and a slow rocking must not.
         */
        const val DEFAULT_THRESHOLD = 0.9f

        /** Gravity tracker time constant. Slow enough not to follow a step. */
        private const val GRAVITY_TAU_MS = 500.0

        /** Jitter smoother time constant. Fast enough not to blunt a step. */
        private const val SMOOTH_TAU_MS = 40.0

        /** A candidate fires once the signal drops back below this much of the threshold. */
        private const val RELEASE_FRACTION = 0.3f
    }
}
