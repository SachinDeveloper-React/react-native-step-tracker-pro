package com.steptrackerpro.core

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.roundToInt

/** What happens to steps a strong flag covers. Values match the JS config. */
enum class IntegrityMode(val jsValue: String) {
    /** Report them; every number stays as counted. The default. */
    FLAG("flag"),

    /**
     * Also take them out of every number this package shows, syncs and
     * resolves. The raw count is kept, and reported as `deviceSteps`.
     */
    EXCLUDE("exclude");

    companion object {
        fun from(value: String?): IntegrityMode = entries.firstOrNull { it.jsValue == value } ?: FLAG
    }
}

/**
 * The thresholds the detector judges a day by. `0` turns a check off. The
 * defaults are starting points meant to be tuned on real data, and chosen to
 * stay clear of honest walking, running and treadmill sessions.
 */
data class IntegrityRules(
    /** A minute above this many timed steps is faster than people walk or run. */
    val maxCadenceSpm: Int = DEFAULT_MAX_CADENCE_SPM,
    /**
     * This many minutes in a row whose counts never move by more than one
     * step from one minute to the next, with no pause, is a machine.
     */
    val steadyCadenceMinutes: Int = DEFAULT_STEADY_MINUTES,
    /** Walking past this many minutes with no pause at all; the excess is flagged. */
    val maxContinuousMinutes: Int = DEFAULT_MAX_CONTINUOUS_MINUTES,
    /** Past this many steps in a day, the excess is flagged. */
    val maxDailySteps: Int = DEFAULT_MAX_DAILY_STEPS,
    /** Steps counted while the phone is plugged in are flagged. */
    val flagWhileCharging: Boolean = true
) {
    companion object {
        const val DEFAULT_MAX_CADENCE_SPM = 200
        const val DEFAULT_STEADY_MINUTES = 30
        const val DEFAULT_MAX_CONTINUOUS_MINUTES = 180
        const val DEFAULT_MAX_DAILY_STEPS = 50_000
    }
}

/**
 * How much a flag counts. Only [STRONG] flags make up `suspectSteps`, and
 * only they are taken out under [IntegrityMode.EXCLUDE]; a [WEAK] flag is
 * evidence for a server that weighs several signals together.
 */
enum class FlagSeverity(val jsValue: String) {
    STRONG("strong"),
    WEAK("weak");

    companion object {
        fun from(value: String?): FlagSeverity = entries.firstOrNull { it.jsValue == value } ?: WEAK
    }
}

/**
 * One stretch of the day the detector found implausible, with the numbers
 * that made it so. [from] is inclusive and [to] exclusive, both epoch ms.
 * [steps] is what this flag alone covers; flags can overlap, so they do not
 * sum to `suspectSteps`.
 */
data class IntegrityFlag(
    val type: String,
    val severity: FlagSeverity,
    val from: Long,
    val to: Long,
    val steps: Int,
    val evidence: Map<String, Any?> = emptyMap()
) {
    /** Stable across re-evaluations of the same day, so an event fires once per flag. */
    val key: String get() = "$type@$from"

    fun toMap(): Map<String, Any?> = mapOf(
        "type" to type,
        "severity" to severity.jsValue,
        "from" to from,
        "to" to to,
        "steps" to steps,
        "evidence" to evidence
    )

    fun toJson(): JSONObject = JSONObject().apply {
        put("type", type)
        put("severity", severity.jsValue)
        put("from", from)
        put("to", to)
        put("steps", steps)
        put("evidence", JSONObject().also { obj -> evidence.forEach { (k, v) -> obj.put(k, v ?: JSONObject.NULL) } })
    }

    companion object {
        const val CADENCE = "cadence"
        const val STEADY_CADENCE = "steady_cadence"
        const val CONTINUOUS = "continuous"
        const val CHARGING = "charging"
        const val IN_VEHICLE = "in_vehicle"
        const val ACTIVITY_STILL = "activity_still"
        const val NIGHT = "night"
        const val SHAKE = "shake"
        const val SWING = "swing"
        const val DAILY_VOLUME = "daily_volume"

        fun fromJson(json: JSONObject): IntegrityFlag {
            val evidence = LinkedHashMap<String, Any?>()
            json.optJSONObject("evidence")?.let { obj ->
                obj.keys().forEach { k -> evidence[k] = obj.opt(k).takeUnless { it == JSONObject.NULL } }
            }
            return IntegrityFlag(
                type = json.optString("type"),
                severity = FlagSeverity.from(json.optString("severity")),
                from = json.optLong("from"),
                to = json.optLong("to"),
                steps = json.optInt("steps"),
                evidence = evidence
            )
        }

        fun listToJson(flags: List<IntegrityFlag>): String =
            JSONArray().also { arr -> flags.forEach { arr.put(it.toJson()) } }.toString()

        fun listFromJson(text: String?): List<IntegrityFlag> {
            if (text.isNullOrEmpty()) return emptyList()
            return runCatching {
                val arr = JSONArray(text)
                (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let(::fromJson) }
            }.getOrDefault(emptyList())
        }
    }
}

/** The detector's verdict on one day's minutes and motion windows. */
data class DayEvaluation(
    val flags: List<IntegrityFlag>,
    /**
     * Steps the strong minute-level flags cover, each minute counted once
     * however many flags cover it. The daily cap is applied on top at read
     * time, against the day's device count, by [FraudDetector.suspectSteps].
     */
    val flaggedSteps: Int
)

/**
 * Judges a day of this device's own steps. Pure - minutes, motion windows
 * and rules in, flags out - so every rule is pinned by JVM tests with
 * synthetic days, and a server can reproduce a verdict from the same inputs.
 *
 * It never changes a count. It says which minutes look like a phone being
 * shaken, swung by a gadget, carried in a car or left on a charger, and how
 * many steps that covers; what happens next is [IntegrityMode]'s business
 * on the device and policy's on a server.
 */
object FraudDetector {

    /** A minute with at least this many timed steps is a minute of real walking. */
    const val ACTIVE_MINUTE_STEPS = 40

    /** Minute-to-minute change a steady run may show; a person drifts by more. */
    const val STEADY_MAX_DELTA_SPM = 1

    /** Charging and activity tags this close together are one flag, not several. */
    private const val TAG_GAP_MINUTES = 1

    /** A car flagged for at least this long is strong; a shorter one is a bus stop's worth of doubt. */
    const val VEHICLE_STRONG_MINUTES = 3

    /** Walking for this long starting between midnight and [NIGHT_END_HOUR] is noted. */
    const val NIGHT_MIN_RUN_MINUTES = 30
    const val NIGHT_END_HOUR = 5

    /**
     * A motion window this fast, this hard and this tonal is a hand shake.
     * 3.3 Hz is 200 steps a minute, the cadence cap; the purity floor keeps a
     * runner, whose harmonics can out-rank the step frequency, out of it.
     */
    const val SHAKE_MIN_HZ = 3.3
    const val SHAKE_MIN_VARIANCE = 20.0
    const val SHAKE_MIN_PEAK_RATIO = 0.25

    /**
     * A near-pure tone at a walking frequency is what a swing gadget
     * produces. `peakRatio` tops out near 0.5 for a pure tone at the
     * sampler's resolution, and a smooth walk - a phone in a backpack -
     * can come close, so this is only ever a weak flag.
     */
    const val SWING_MIN_HZ = 0.8
    const val SWING_MAX_HZ = 2.6
    const val SWING_MIN_PEAK_RATIO = 0.42

    private const val MINUTE = MinuteAttribution.MINUTE_MS

    fun evaluate(
        minutes: List<MinuteSample>,
        windows: List<MotionFeatures>,
        rules: IntegrityRules,
        zone: ZoneId
    ): DayEvaluation {
        val sorted = minutes.filter { it.totalSteps > 0 }.sortedBy { it.minuteStart }
        val byMinute = sorted.associateBy { it.minuteStart }
        val flags = ArrayList<IntegrityFlag>()
        // Minutes a strong flag condemns outright; their timed steps are suspect.
        val whole = HashSet<Long>()
        // Minutes whose vehicle-tagged steps belong to a strong vehicle run.
        val strongVehicle = HashSet<Long>()

        cadence(sorted, rules, flags, whole)
        steady(sorted, rules, flags, whole)
        continuous(sorted, rules, flags, whole)
        if (rules.flagWhileCharging) {
            tagged(sorted, { it.chargingSteps }).forEach { run ->
                flags += tagFlag(IntegrityFlag.CHARGING, FlagSeverity.STRONG, run) { it.chargingSteps }
            }
        }
        tagged(sorted, { it.vehicleSteps }).forEach { run ->
            val strong = spanMinutes(run) >= VEHICLE_STRONG_MINUTES
            if (strong) run.forEach { strongVehicle += it.minuteStart }
            flags += tagFlag(
                IntegrityFlag.IN_VEHICLE, if (strong) FlagSeverity.STRONG else FlagSeverity.WEAK, run
            ) { it.vehicleSteps }
        }
        tagged(sorted, { it.stillSteps }).forEach { run ->
            flags += tagFlag(IntegrityFlag.ACTIVITY_STILL, FlagSeverity.WEAK, run) { it.stillSteps }
        }
        night(sorted, zone, flags)
        windows(windows, byMinute, flags, whole)

        var flagged = 0
        for (m in sorted) {
            var suspect = if (m.minuteStart in whole) m.steps else 0
            if (rules.flagWhileCharging) suspect = maxOf(suspect, m.chargingSteps)
            if (m.minuteStart in strongVehicle) suspect = maxOf(suspect, m.vehicleSteps)
            flagged += suspect.coerceAtMost(m.totalSteps)
        }
        return DayEvaluation(flags.sortedWith(compareBy({ it.from }, { it.type })), flagged)
    }

    /**
     * The day's suspect steps: what the strong minute flags cover, plus
     * whatever the rest of the day's device count still exceeds the daily cap
     * by. Recovered steps are in [deviceSteps] and in no minute, so the cap is
     * the only rule that can reach them - which is right: a recovery can be
     * a counter glitch, and one day must not be handed an implausible total.
     */
    fun suspectSteps(flaggedSteps: Int, deviceSteps: Int, rules: IntegrityRules): Int {
        val device = deviceSteps.coerceAtLeast(0)
        val flagged = flaggedSteps.coerceIn(0, device)
        val excess = if (rules.maxDailySteps > 0) (device - flagged - rules.maxDailySteps).coerceAtLeast(0) else 0
        return flagged + excess
    }

    /** The daily cap as a flag, computed at read time against the current device count. */
    fun dailyVolumeFlag(
        flaggedSteps: Int,
        deviceSteps: Int,
        rules: IntegrityRules,
        dayStart: Long,
        dayEnd: Long
    ): IntegrityFlag? {
        val excess = suspectSteps(flaggedSteps, deviceSteps, rules) - flaggedSteps.coerceIn(0, deviceSteps.coerceAtLeast(0))
        if (excess <= 0) return null
        return IntegrityFlag(
            IntegrityFlag.DAILY_VOLUME, FlagSeverity.STRONG, dayStart, dayEnd, excess,
            mapOf("deviceSteps" to deviceSteps, "limit" to rules.maxDailySteps)
        )
    }

    /** `shake`, `swing`, or null for a window that looks like nothing in particular. */
    fun classifyWindow(window: MotionFeatures): String? {
        if (window.stepsDuringWindow <= 0) return null
        if (window.dominantFrequencyHz >= SHAKE_MIN_HZ &&
            window.variance >= SHAKE_MIN_VARIANCE &&
            window.peakRatio >= SHAKE_MIN_PEAK_RATIO
        ) {
            return IntegrityFlag.SHAKE
        }
        if (window.dominantFrequencyHz in SWING_MIN_HZ..SWING_MAX_HZ &&
            window.peakRatio >= SWING_MIN_PEAK_RATIO
        ) {
            return IntegrityFlag.SWING
        }
        return null
    }

    // ---- the checks --------------------------------------------------------

    private fun cadence(
        sorted: List<MinuteSample>,
        rules: IntegrityRules,
        flags: MutableList<IntegrityFlag>,
        whole: MutableSet<Long>
    ) {
        if (rules.maxCadenceSpm <= 0) return
        adjacentRuns(sorted.filter { it.steps > rules.maxCadenceSpm }).forEach { run ->
            run.forEach { whole += it.minuteStart }
            flags += IntegrityFlag(
                IntegrityFlag.CADENCE, FlagSeverity.STRONG,
                run.first().minuteStart, run.last().minuteStart + MINUTE,
                run.sumOf { it.steps },
                mapOf(
                    "minutes" to run.size,
                    "peakSpm" to run.maxOf { it.steps },
                    "limitSpm" to rules.maxCadenceSpm
                )
            )
        }
    }

    private fun steady(
        sorted: List<MinuteSample>,
        rules: IntegrityRules,
        flags: MutableList<IntegrityFlag>,
        whole: MutableSet<Long>
    ) {
        if (rules.steadyCadenceMinutes <= 0) return
        for (run in activeRuns(sorted)) {
            var start = 0
            for (i in 1..run.size) {
                val breaks = i == run.size || abs(run[i].steps - run[i - 1].steps) > STEADY_MAX_DELTA_SPM
                if (!breaks) continue
                val span = run.subList(start, i)
                if (span.size >= rules.steadyCadenceMinutes) {
                    span.forEach { whole += it.minuteStart }
                    val mean = span.sumOf { it.steps }.toDouble() / span.size
                    flags += IntegrityFlag(
                        IntegrityFlag.STEADY_CADENCE, FlagSeverity.STRONG,
                        span.first().minuteStart, span.last().minuteStart + MINUTE,
                        span.sumOf { it.steps },
                        mapOf(
                            "minutes" to span.size,
                            "meanSpm" to round1(mean),
                            "minSpm" to span.minOf { it.steps },
                            "maxSpm" to span.maxOf { it.steps }
                        )
                    )
                }
                start = i
            }
        }
    }

    private fun continuous(
        sorted: List<MinuteSample>,
        rules: IntegrityRules,
        flags: MutableList<IntegrityFlag>,
        whole: MutableSet<Long>
    ) {
        val limit = rules.maxContinuousMinutes
        if (limit <= 0) return
        for (run in activeRuns(sorted)) {
            if (run.size <= limit) continue
            val excess = run.subList(limit, run.size)
            excess.forEach { whole += it.minuteStart }
            flags += IntegrityFlag(
                IntegrityFlag.CONTINUOUS, FlagSeverity.STRONG,
                excess.first().minuteStart, excess.last().minuteStart + MINUTE,
                excess.sumOf { it.steps },
                mapOf(
                    "runMinutes" to run.size,
                    "limitMinutes" to limit,
                    "runStartedAt" to run.first().minuteStart
                )
            )
        }
    }

    private fun night(sorted: List<MinuteSample>, zone: ZoneId, flags: MutableList<IntegrityFlag>) {
        for (run in activeRuns(sorted)) {
            if (run.size < NIGHT_MIN_RUN_MINUTES) continue
            val hour = Instant.ofEpochMilli(run.first().minuteStart).atZone(zone).hour
            if (hour >= NIGHT_END_HOUR) continue
            flags += IntegrityFlag(
                IntegrityFlag.NIGHT, FlagSeverity.WEAK,
                run.first().minuteStart, run.last().minuteStart + MINUTE,
                run.sumOf { it.steps },
                mapOf("minutes" to run.size, "startHour" to hour)
            )
        }
    }

    private fun windows(
        windows: List<MotionFeatures>,
        byMinute: Map<Long, MinuteSample>,
        flags: MutableList<IntegrityFlag>,
        whole: MutableSet<Long>
    ) {
        for (window in windows.sortedBy { it.startedAt }) {
            val type = classifyWindow(window) ?: continue
            val end = window.startedAt + window.durationMs.coerceAtLeast(1L)
            val covered = ArrayList<Long>()
            var minute = MinuteAttribution.minuteOf(window.startedAt)
            while (minute < end) {
                covered += minute
                minute += MINUTE
            }
            val minuteSteps = covered.sumOf { byMinute[it]?.steps ?: 0 }
            val strong = type == IntegrityFlag.SHAKE
            if (strong) covered.filter { byMinute.containsKey(it) }.forEach { whole += it }
            flags += IntegrityFlag(
                type, if (strong) FlagSeverity.STRONG else FlagSeverity.WEAK,
                window.startedAt, end,
                if (minuteSteps > 0) minuteSteps else window.stepsDuringWindow,
                mapOf(
                    "dominantFrequencyHz" to window.dominantFrequencyHz,
                    "variance" to round1(window.variance),
                    "peakRatio" to round3(window.peakRatio),
                    "zeroCrossingRate" to round1(window.zeroCrossingRate),
                    "stepsDuringWindow" to window.stepsDuringWindow
                )
            )
        }
    }

    // ---- grouping ------------------------------------------------------------

    /** Minutes that follow one another with no minute missing between them. */
    private fun adjacentRuns(minutes: List<MinuteSample>, gapMinutes: Int = 0): List<List<MinuteSample>> {
        val runs = ArrayList<List<MinuteSample>>()
        var current = ArrayList<MinuteSample>()
        for (m in minutes) {
            val last = current.lastOrNull()
            if (last != null && m.minuteStart - last.minuteStart > MINUTE * (1 + gapMinutes)) {
                runs += current
                current = ArrayList()
            }
            current += m
        }
        if (current.isNotEmpty()) runs += current
        return runs
    }

    /**
     * Unbroken walking: adjacent minutes, each with [ACTIVE_MINUTE_STEPS]
     * timed steps and nothing untimed. A missing minute, a slow one or a lump
     * ends the run - a lump says the timing is unknown, and a verdict about
     * steadiness or stamina cannot rest on unknown timing.
     */
    private fun activeRuns(sorted: List<MinuteSample>): List<List<MinuteSample>> =
        adjacentRuns(sorted.filter { it.steps >= ACTIVE_MINUTE_STEPS && it.untimedSteps == 0 })

    private fun tagged(sorted: List<MinuteSample>, count: (MinuteSample) -> Int): List<List<MinuteSample>> =
        adjacentRuns(sorted.filter { count(it) > 0 }, gapMinutes = TAG_GAP_MINUTES)

    private fun spanMinutes(run: List<MinuteSample>): Int =
        ((run.last().minuteStart - run.first().minuteStart) / MINUTE).toInt() + 1

    private fun tagFlag(
        type: String,
        severity: FlagSeverity,
        run: List<MinuteSample>,
        count: (MinuteSample) -> Int
    ) = IntegrityFlag(
        type, severity,
        run.first().minuteStart, run.last().minuteStart + MINUTE,
        run.sumOf(count),
        mapOf("minutes" to spanMinutes(run))
    )

    private fun round1(value: Double): Double = (value * 10).roundToInt() / 10.0
    private fun round3(value: Double): Double = (value * 1000).roundToInt() / 1000.0
}
