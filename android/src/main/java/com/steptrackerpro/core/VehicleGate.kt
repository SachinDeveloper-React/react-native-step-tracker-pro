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
 *    not granted: the count is the hardware counter's, exactly as the
 *    phone's own health app shows it.
 *  - **Activity Recognition's word stands until it says otherwise.** It
 *    sends a transition only when its state changes, so a ride it has
 *    reported is a ride for as long as it reports nothing else. What the
 *    accelerometer sees only pauses the holding: while it confirms a walk -
 *    down a train's corridor, or off a bus Activity Recognition has not yet
 *    noticed leaving - nothing is held; when the walk stops and nothing has
 *    said the ride is over, holding starts again. A walk once confirmed
 *    goes on at a lower bar than it took to confirm it
 *    ([WalkWitness.stillWalking]): a stride that reads poorly for a few
 *    seconds is not a walk taken for over, with its steps held. 2.9.0
 *    ended the ride instead, and the rest of it was counted: a train
 *    standing at a station, a bus in a jam.
 *  - **On a bicycle the accelerometer has no say.** Pedalling is a rhythm
 *    it cannot tell from a stride: it would confirm a walk thirty seconds
 *    into every ride. Activity Recognition alone ends a bicycle ride and
 *    gives its last steps back. When the same ride is next reported as a
 *    vehicle the accelerometer has its say again, from that moment: what it
 *    saw of the pedalling is not evidence of a walk, and what was held
 *    while it had no say is not its to give back.
 *  - **Held is not gone.** Held steps wait in a ledger for two minutes, and
 *    three things give them back: Activity Recognition saying the user is
 *    now walking or running (everything held in the [EXIT_LOOKBACK_MS]
 *    before - it reports leaving a vehicle about a minute late, and that
 *    minute is the walk away from the bus stop); Activity Recognition
 *    saying anything else that is not a vehicle (the held steps taken in
 *    seconds the accelerometer saw a walking rhythm in); and the
 *    accelerometer confirming a walk on its own.
 *  - **A ride cannot outlive its evidence.** A report that is never
 *    followed by another cannot hold a day's steps: [WALK_ENDS_RIDE_MS] of
 *    unbroken walking, [STILL_ENDS_RIDE_MS] of a phone lying still, or
 *    [VEHICLE_MAX_MS] end it - the first two being things a ride cannot
 *    look like, and that Activity Recognition would have reported had it
 *    been listening.
 *  - **Steps that cannot be placed are counted.** An untimed sample, a
 *    step taken before the ride began, a lump the counter hands over after
 *    a gap that reaches back past the ride's start.
 *
 * What still gets through is the first minute of a ride, before Activity
 * Recognition has noticed it; that is its latency, and the price of not
 * guessing.
 *
 * The ride and the ledger can be saved and restored - [saved], [restore] -
 * so a process the system kills in the middle of a ride does not leak the
 * rest of it, nor keep the walk it was about to give back. Whether what was
 * saved is still to be believed is the caller's to say: only while the
 * request made of Activity Recognition stands, which a reboot or a force
 * stop ends.
 *
 * Pure Kotlin - the clock is whatever the samples carry - so every rule
 * here is pinned by a JVM test.
 */
class VehicleGate(rules: WitnessRules = WitnessRules()) {

    /** Steps held back from one counter sample, with the span it gave for them. */
    data class Held(val fromMs: Long, val toMs: Long, val steps: Int)

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
        /**
         * How holding changed on this sample, when it did: one of the
         * `PAUSED_` values (holding stops, the ride goes on), [RESUMED]
         * (holding starts again), or one of the `ENDED_` values (the ride
         * is over).
         */
        val change: String?
    )

    /** What [saved] hands out and [restore] takes back: the ride and the ledger, not the witness. */
    data class Saved(
        val vehicleSince: Long,
        val carried: ActivityState,
        val ledger: List<Held>,
        val graceFrom: Long,
        val graceUntil: Long
    )

    @Volatile
    var witness: WalkWitness = WalkWitness(rules)
        private set

    /**
     * When Activity Recognition said the ride began, epoch ms; 0 while
     * there is no ride, which is what counts every step.
     */
    @Volatile
    var vehicleSince: Long = 0L
        private set

    /** What is carrying the phone: [ActivityState.IN_VEHICLE] or [ActivityState.ON_BICYCLE] during a ride. */
    @Volatile
    var carried: ActivityState = ActivityState.UNKNOWN
        private set

    /** When the walk the witness is confirming right now was confirmed; 0 when it is not. */
    @Volatile
    private var walkingSince: Long = 0L

    /** A ride is in progress: the accelerometer is wanted for exactly this long. */
    val inRide: Boolean get() = vehicleSince > 0L

    /** Steps are being held: a ride, and no walk confirmed in it right now. */
    val holding: Boolean get() = vehicleSince > 0L && walkingSince == 0L

    /**
     * A walk is being confirmed in the middle of a ride: the steps arriving
     * now are counted, and are walked on the same evidence a release is.
     */
    val walking: Boolean get() = vehicleSince > 0L && walkingSince > 0L

    /**
     * From when the accelerometer's word counts in this ride, epoch ms: 0
     * for all of it, or the moment a ride reported as a bicycle was next
     * reported as a vehicle.
     */
    private var witnessFrom = 0L

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
        if (witness.rules != rules) {
            witness = WalkWitness(rules)
            walkingSince = 0L
        }
    }

    /**
     * Activity Recognition reported [state] at [atMs].
     *
     * @return held steps to give back, when the report ended a ride.
     */
    @Synchronized
    fun onActivity(state: ActivityState, atMs: Long): Release? {
        if (state.carried) {
            if (!inRide) {
                vehicleSince = atMs.coerceAtLeast(1L)
                ledger.clear()
                graceUntil = 0L
                walkingSince = 0L
                witnessFrom = 0L
                // What the accelerometer saw before the ride is no evidence
                // about a walk during it.
                witness.reset()
            } else if (state == ActivityState.ON_BICYCLE) {
                // A bus read as a bicycle in slow traffic is the same ride,
                // and from here on one the accelerometer has no say in.
                walkingSince = 0L
            } else if (carried == ActivityState.ON_BICYCLE) {
                // And back: the accelerometer has its say again, from here.
                // The pedalling it watched would confirm a walk at once, and
                // give back what was held while it had no say.
                witness.reset()
                witnessFrom = atMs
            }
            carried = state
            return null
        }
        if (inRide) {
            val wasBicycle = carried == ActivityState.ON_BICYCLE
            endRide()
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
            // steps the accelerometer vouches for come back now - and on a
            // bicycle it vouches for pedalling, so none do.
            val release = if (wasBicycle) null else take(from, REASON_LEFT_VEHICLE) { witness.gaitAround(it.toMs) }
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
        endRide()
        ledger.clear()
        graceUntil = 0L
    }

    /** One accelerometer sample, while [inRide]. */
    @Synchronized
    fun onSample(x: Float, y: Float, z: Float, atMs: Long): Outcome? {
        val fresh = witness.onSample(x, y, z, atMs) ?: return null
        if (!inRide) return Outcome(fresh, null, null)
        if (witness.stillFor(atMs) >= STILL_ENDS_RIDE_MS) {
            endRide()
            ledger.clear()
            graceUntil = 0L
            return Outcome(fresh, null, ENDED_STILL)
        }
        // Pedalling reads as a stride: on a bicycle the witness is not asked.
        if (carried == ActivityState.ON_BICYCLE) return Outcome(fresh, null, null)
        if (walkingSince == 0L) {
            if (!witness.confirmedWalking(atMs)) return Outcome(fresh, null, null)
            // Walking, on the accelerometer's own evidence: what was held
            // while it made sure comes back, and nothing is held while the
            // walk goes on.
            walkingSince = atMs
            val release = take(maxOf(atMs - CONFIRM_LOOKBACK_MS, witnessFrom), REASON_WALKING) { true }
            return Outcome(fresh, release, PAUSED_WALKING)
        }
        // A walk once confirmed goes on for as long as half the seconds
        // still show it - less than it took to confirm. A stride that reads
        // poorly dips below that bar without the walker having stopped, and
        // taking the walk for over would hold the rest of it.
        if (witness.stillWalking(atMs)) {
            if (atMs - walkingSince >= WALK_ENDS_RIDE_MS) {
                // Minutes of walking are not a ride, whatever was last
                // reported: the report that would have ended it was missed.
                endRide()
                ledger.clear()
                graceUntil = 0L
                return Outcome(fresh, null, ENDED_WALKING)
            }
            return Outcome(fresh, null, null)
        }
        // The walk is over and nothing has said the ride is: a corridor
        // walked down, a seat changed.
        walkingSince = 0L
        return Outcome(fresh, null, RESUMED)
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
        if (toMs - since > VEHICLE_MAX_MS) {
            endRide()
            ledger.clear()
            return 0
        }
        if (walkingSince > 0L) return 0
        if (!timed) return 0
        // Taken before the ride began and reported after: the walk to the seat.
        if (toMs < since) return 0
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
    fun resetWitness() {
        witness.reset()
        walkingSince = 0L
    }

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

    /** The ride and the ledger, to survive the process. */
    @Synchronized
    fun saved(): Saved = Saved(vehicleSince, carried, ledger.toList(), graceFrom, graceUntil)

    /**
     * Takes back what [saved] handed out, as of [nowMs]: a ride past
     * [VEHICLE_MAX_MS] is over, a held step older than the ledger keeps
     * them is gone, and a grace that has run out has run out. The witness
     * starts over: the seconds it saw went with the process.
     */
    @Synchronized
    fun restore(saved: Saved, nowMs: Long) {
        val rideLives = saved.vehicleSince > 0L && saved.carried.carried &&
            nowMs - saved.vehicleSince in 0..VEHICLE_MAX_MS
        vehicleSince = if (rideLives) saved.vehicleSince else 0L
        carried = if (rideLives) saved.carried else ActivityState.UNKNOWN
        walkingSince = 0L
        witnessFrom = 0L
        ledger.clear()
        saved.ledger.filter { nowMs - it.toMs in 0..LEDGER_MS }.forEach { ledger.addLast(it) }
        graceFrom = saved.graceFrom
        graceUntil = if (saved.graceUntil >= nowMs) saved.graceUntil else 0L
        witness.reset()
    }

    private fun endRide() {
        vehicleSince = 0L
        carried = ActivityState.UNKNOWN
        walkingSince = 0L
        witnessFrom = 0L
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

        /**
         * Walking confirmed this long without a break ends a ride Activity
         * Recognition never said was over. Nobody walks three minutes down
         * a bus; off it, Activity Recognition says so within about one.
         */
        const val WALK_ENDS_RIDE_MS = 3 * 60_000L

        /**
         * A phone lying still this long is not on a ride any more. Long, so
         * a train standing at a station or a bus in a jam is still the ride
         * when it moves off: on the recorded train rides, where the phone
         * reads as still four seconds in five, a five-minute rule ended the
         * journey about every eighteen minutes.
         */
        const val STILL_ENDS_RIDE_MS = 30 * 60_000L

        /** No ride is held for longer than this on one report of its beginning. */
        const val VEHICLE_MAX_MS = 12 * 3_600_000L

        const val REASON_ON_FOOT = "on_foot"
        const val REASON_LEFT_VEHICLE = "left_vehicle"
        const val REASON_WALKING = "walking"

        /** The witness confirmed a walk: holding pauses, the ride goes on. */
        const val PAUSED_WALKING = "walking"

        /** The confirmed walk is over and the ride is not: holding starts again. */
        const val RESUMED = "resumed"

        const val ENDED_WALKING = "walked_away"
        const val ENDED_STILL = "still"
    }
}
