package com.steptrackerpro.health

import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.BloodGlucoseRecord
import androidx.health.connect.client.records.BloodPressureRecord
import androidx.health.connect.client.records.BodyTemperatureRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.RespiratoryRateRecord
import androidx.health.connect.client.records.RestingHeartRateRecord
import kotlin.reflect.KClass

/**
 * The vitals Health Connect's vitals guide covers, read-only: this package
 * measures none of them, but an app that counts steps often shows the heart
 * rate a watch recorded on the same walk. Each is one more read permission
 * the app declares and justifies to Play, so none is read unless the app
 * lists it in `healthConnectReadVitals`.
 */
enum class VitalType(
    val jsValue: String,
    val recordClass: KClass<out Record>,
    /** The unit every figure for this type is in, as JS sees it. */
    val unit: String
) {
    HEART_RATE("heartRate", HeartRateRecord::class, "bpm"),
    RESTING_HEART_RATE("restingHeartRate", RestingHeartRateRecord::class, "bpm"),
    OXYGEN_SATURATION("oxygenSaturation", OxygenSaturationRecord::class, "percent"),
    RESPIRATORY_RATE("respiratoryRate", RespiratoryRateRecord::class, "breathsPerMinute"),
    /** Systolic in the main figures, diastolic alongside - see [VitalSummary.diastolic]. */
    BLOOD_PRESSURE("bloodPressure", BloodPressureRecord::class, "mmHg"),
    BODY_TEMPERATURE("bodyTemperature", BodyTemperatureRecord::class, "celsius"),
    BLOOD_GLUCOSE("bloodGlucose", BloodGlucoseRecord::class, "mmolPerL");

    val permission: String get() = HealthPermission.getReadPermission(recordClass)

    companion object {
        /** Recognised names in declaration order; null when one is unknown. */
        fun parse(values: Collection<String>): Set<VitalType>? {
            val out = LinkedHashSet<VitalType>()
            for (value in values) out += entries.firstOrNull { it.jsValue == value } ?: return null
            return entries.filterTo(LinkedHashSet()) { it in out }
        }

        /** Recognised names in declaration order, unknown ones dropped - for config read back from storage. */
        fun parseLenient(values: Collection<String>): Set<VitalType> =
            entries.filterTo(LinkedHashSet()) { type -> type.jsValue in values }

        fun permissions(types: Set<VitalType>): Set<String> = types.mapTo(LinkedHashSet()) { it.permission }
    }
}

/** One measurement: when, what, and which app wrote it. */
data class VitalMeasurement(val time: Long, val value: Double, val packageName: String) {
    fun toMap(): Map<String, Any?> = mapOf("time" to time, "value" to value, "packageName" to packageName)
}

/**
 * Count, range, mean and the latest of one series of measurements, folded
 * one at a time. Pure, JVM-tested.
 */
class VitalStats {
    var count: Int = 0
        private set
    private var min = Double.POSITIVE_INFINITY
    private var max = Double.NEGATIVE_INFINITY
    private var sum = 0.0
    var latest: VitalMeasurement? = null
        private set

    private var aggregateAvg: Double? = null

    fun add(time: Long, value: Double, packageName: String) {
        if (!value.isFinite()) return
        count++
        if (value < min) min = value
        if (value > max) max = value
        sum += value
        offerLatest(time, value, packageName)
    }

    /** A measurement that only competes for [latest] - its figures are in the aggregate already. */
    fun offerLatest(time: Long, value: Double, packageName: String) {
        if (!value.isFinite()) return
        if (latest.let { it == null || time > it.time }) latest = VitalMeasurement(time, value, packageName)
    }

    /**
     * Sets the figures from Health Connect's aggregate rather than from the
     * measurements themselves - for heart rate, whose samples can run to
     * one a second through every workout. [latest] comes from [offerLatest].
     */
    fun setAggregate(count: Long, min: Double?, max: Double?, avg: Double?) {
        this.count = count.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
        this.min = min ?: Double.POSITIVE_INFINITY
        this.max = max ?: Double.NEGATIVE_INFINITY
        sum = if (avg != null) avg * this.count else 0.0
        aggregateAvg = avg?.takeIf { this.count > 0 }
    }

    fun min(): Double? = min.takeIf { count > 0 && it.isFinite() }
    fun max(): Double? = max.takeIf { count > 0 && it.isFinite() }
    fun avg(): Double? = aggregateAvg ?: if (count > 0) sum / count else null

    fun toMap(): Map<String, Any?> = mapOf(
        "count" to count,
        "min" to min(),
        "max" to max(),
        "avg" to avg(),
        "latest" to latest?.toMap()
    )
}

/** What `readHealthConnectVitals()` reports for one type. */
data class VitalSummary(
    val type: VitalType,
    val stats: VitalStats,
    /** Blood pressure only: the diastolic series, [stats] being the systolic one. */
    val diastolic: VitalStats? = null,
    /** More records than one call reads; the figures cover the newest of them. */
    val truncated: Boolean = false
) {
    fun toMap(): Map<String, Any?> = linkedMapOf<String, Any?>(
        "type" to type.jsValue,
        "unit" to type.unit
    ).apply {
        putAll(stats.toMap())
        if (diastolic != null) put("diastolic", diastolic.toMap())
        put("truncated", truncated)
    }
}
