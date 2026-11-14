package com.steptrackerpro.core

/**
 * Keeps the steps a vehicle shakes out of the hardware counter out of the
 * day's total - the way Google Fit does it: *"Google Fit detects when users
 * are doing activities that shouldn't contribute to their step count (like
 * cycling, driving or travelling via public transport) and removes these
 * from the final step count."* The detector is Google's own, Activity
 * Recognition, and this class is what it is given to decide: while it says
 * the phone is in a vehicle or on a bicycle, the steps that arrive are held.
 *
 * Nothing else holds a step. In particular the accelerometer does not: it
 * cannot tell a vehicle from a kitchen, and the releases that let it try
 * (2.8.0-2.8.2) lost real walks. Here it is only ever a witness *for* the
 * walker - see [WalkWitness] - and every doubt counts the step:
 *
 *  - **No Activity Recognition, nothing held.** An app that does not ship
 *    `play-services-location`, a phone without Play Services, a permission
 *    not granted, a process that just restarted: the count is the hardware
 *    counter's, exactly as the phone's own health app shows it.
 *  - **Held is not gone.** Held steps wait in a ledger for two minutes, and
 *    three things give them back: Activity Recognition saying the user is
 *    now walking or running (everything held in the [EXIT_LOOKBACK_MS]
 *    before - it reports leaving a vehicle about a minute late, and that
 *    minute is the walk away from the bus stop); Activity Recognition
 *    saying anything else that is not a vehicle (the held steps taken in
 *    seconds the accelerometer saw a walking rhythm in); and the
 *    accelerometer confirming a walk on its own, which also ends the ride
 *    without waiting to be told.
 *  - **A ride cannot outlive its evidence.** Five minutes of a phone lying
 *    still ends it, as does [VEHICLE_MAX_MS], so a missed "left the
 *    vehicle" cannot hold a day's steps.
 *  - **Steps that cannot be placed are counted.** An untimed sample, a
 *    step taken before the ride began, a lump the counter hands over after
 *    a gap that reaches back past the ride's start.
 *
 * What still gets through is the first minute of a ride, before Activity
 * Recognition has noticed it; that is its latency, and the price of not
 * guessing.
 *
 * Pure Kotlin - the clock is whatever the samples carry - so every rule
 * here is pinned by a JVM test.
 */
class VehicleGate(rules: WitnessRules = WitnessRules()) {

    /** Steps held back from one counter sample, with the span it gave for them. */
    class Held(val fromMs: Long, val toMs: Long, val steps: Int)

    /** Held steps to give back, oldest first, and why. */
    class Release(val held: List<Held>, val reason: String) {
        val steps: Int get() = held.sumOf { it.steps }
    }

    /** What one accelerometer sample led to. */
    class Outcome(
        /** The fresh verdict, when the sample closed a second. */
        val verdict: MotionVerdict?,
        /** Steps to give back, when the witness confirmed a walk. */
        val release: Release?,
        /** Why the ride ended on this sample, when it did: one of the `ENDED_` values. */
        val ended: String?
    )

    @Volatile
    var witness: WalkWitness = WalkWitness(rules)
        private set

    /**
     * When Activity Recognition said the ride began, epoch ms; 0 while it
     * says nothing of the kind, which is what counts every step.
     */
    @Volatile
    var vehicleSince: Long = 0L
        private set

    /** True while steps are being held: the accelerometer is wanted for exactly this long. */
    val holding: Boolean get() = vehicleSince > 0L

    private val ledger = ArrayDeque<Held>()

    /**
     * After a ride ended on something that was not walking - "still", or
     * just "no longer in a vehicle" - what is left in the ledger from
     * [graceFrom] on is still owed to a "walking" that follows before
     * [graceUntil]: the two arrive seconds apart as often as together.
     */
    private var graceFrom = 0L
    private var graceUntil = 0L

    /** New thresholds: the witness starts over, the ride and the ledger stand. */
    @Synchronized
    fun setRules(rules: WitnessRules) {
        if (witness.rules != rules) witness = WalkWitness(rules)
    }

    /**
     * Activity Recognition reported [state] at [atMs].
     *
     * @return held steps to give back, when the report ended a ride.
     */
    @Synchronized
    fun onActivity(state: ActivityState, atMs: Long): Release? {
        if (state.carried) {
            if (!holding) {
                vehicleSince = atMs.coerceAtLeast(1L)
                ledger.clear()
                graceUntil = 0L
                // What the accelerometer saw before the ride is no evidence
                // about a walk that would end it.
                witness.reset()
            }
            return null
        }
        if (holding) {
            vehicleSince = 0L
            val from = atMs - EXIT_LOOKBACK_MS
            if (state.onFoot) {
                // It says the user is walking, and it says so late: the
                // steps held while it made up its mind are that walk.
                val release = take(from, REASON_ON_FOOT) { true }
                ledger.clear()
                return release
            }
            // Still, or nothing in particular: a bus at a red light as
            // easily as a passenger who got off and sat down. Only the
            // steps the accelerometer vouches for come back now.
            val release = take(from, REASON_LEFT_VEHICLE) { witness.gaitAround(it.toMs) }
            graceFrom = from
            graceUntil = atMs + ON_FOOT_GRACE_MS
            return release
        }
        if (state.onFoot && graceUntil > 0L && atMs <= graceUntil) {
            graceUntil = 0L
            val release = take(graceFrom, REASON_ON_FOOT) { true }
            ledger.clear()
            return release
        }
        return null
    }

    /** Activity Recognition was stopped: whatever it said no longer holds, and nothing is held on it. */
    @Synchronized
    fun onActivityStopped() {
        vehicleSince = 0L
        ledger.clear()
        graceUntil = 0L
    }

    /** One accelerometer sample, while [holding]. */
    @Synchronized
    fun onSample(x: Float, y: Float, z: Float, atMs: Long): Outcome? {
        val fresh = witness.onSample(x, y, z, atMs) ?: return null
        if (!holding) return Outcome(fresh, null, null)
        if (witness.confirmedWalking(atMs)) {
            // Walking, on the accelerometer's own evidence: the ride is
            // over whether or not anyone has said so, and stays over until
            // Activity Recognition reports a vehicle afresh.
            vehicleSince = 0L
            val release = take(atMs - CONFIRM_LOOKBACK_MS, REASON_WALKING) { true }
            ledger.clear()
            graceUntil = 0L
            return Outcome(fresh, release, ENDED_WALKING)
        }
        if (witness.stillFor(atMs) >= STILL_ENDS_RIDE_MS) {
            vehicleSince = 0L
            ledger.clear()
            graceUntil = 0L
            return Outcome(fresh, null, ENDED_STILL)
        }
        return Outcome(fresh, null, null)
    }

    /**
     * How many of [steps] are held: all of them, or none. They were counted
     * between [fromMs] and [toMs], epoch ms, [fromMs] being 0 when there was
     * no previous sample to measure from.
     *
     * @param timed false when only the steps' arrival is known. Nothing
     *   untimed is ever held: there is no saying when it was taken.
     */
    @Synchronized
    fun gated(fromMs: Long, toMs: Long, steps: Int, timed: Boolean): Int {
        if (steps <= 0) return 0
        val since = vehicleSince
        if (since <= 0L) return 0
        if (!timed) return 0
        // Taken before the ride began and reported after: the walk to the seat.
        if (toMs < since) return 0
        if (toMs - since > VEHICLE_MAX_MS) {
            vehicleSince = 0L
            ledger.clear()
            return 0
        }
        // A lump after a gap that reaches back past the ride's start - a
        // dead process, a hub's FIFO flushed at once. Some of it was walked
        // before the ride and there is no telling how much.
        if (fromMs in 1 until since && toMs - fromMs > MinuteAttribution.MAX_TIMED_GAP_MS &&
            steps > MinuteAttribution.LUMP_STEPS
        ) {
            return 0
        }
        ledger.addLast(Held(fromMs, toMs, steps))
        while (ledger.isNotEmpty() && toMs - ledger.first().toMs > LEDGER_MS) ledger.removeFirst()
        return steps
    }

    /** Forgets what the accelerometer has seen - a pause, a stop - and nothing else. */
    @Synchronized
    fun resetWitness() = witness.reset()

    /**
     * Forgets the held steps and leaves the ride standing. For a day that
     * started over from zero - `resetToday()`, a `setCountFrom()` that had
     * to - with a ride in progress: what was held belonged to a count that
     * no longer exists, and giving it back would hand the new one steps it
     * never held.
     */
    @Synchronized
    fun forgetHeld() {
        ledger.clear()
        graceUntil = 0L
    }

    private inline fun take(fromMs: Long, reason: String, wanted: (Held) -> Boolean): Release? {
        val out = ArrayList<Held>()
        val kept = ArrayList<Held>()
        for (held in ledger) {
            if (held.toMs >= fromMs && wanted(held)) out.add(held) else kept.add(held)
        }
        if (out.isEmpty()) return null
        ledger.clear()
        ledger.addAll(kept)
        return Release(out, reason)
    }

    companion object {
        /**
         * How far back from "the user is walking" the held steps are given
         * back. Activity Recognition's own delay in saying so, generously:
         * about a minute between leaving a vehicle and the transition.
         */
        const val EXIT_LOOKBACK_MS = 90_000L

        /**
         * How far back from the accelerometer confirming a walk: the thirty
         * seconds it took to be sure, and as much again for a walk whose
         * first half-minute was harder to read.
         */
        const val CONFIRM_LOOKBACK_MS = 60_000L

        /** How long after a ride ended on "still" a "walking" still claims what is left. */
        const val ON_FOOT_GRACE_MS = 60_000L

        /** How long a held step waits to be given back before it is gone for good. */
        const val LEDGER_MS = 120_000L

        /** A phone lying still this long is not on a ride any more. */
        const val STILL_ENDS_RIDE_MS = 5 * 60_000L

        /** No ride is held for longer than this on one report of its beginning. */
        const val VEHICLE_MAX_MS = 12 * 3_600_000L

        const val REASON_ON_FOOT = "on_foot"
        const val REASON_LEFT_VEHICLE = "left_vehicle"
        const val REASON_WALKING = "walking"

        const val ENDED_WALKING = "walking"
        const val ENDED_STILL = "still"
    }
}
