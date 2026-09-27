package com.steptrackerpro.core

/**
 * The evidence `getVerificationSnapshot(date, { include })` can add to a
 * snapshot, next to the totals it always carries.
 */
enum class SnapshotPart(val jsValue: String) {
    /** The day's per-minute buckets, as `getStepMinutes()` returns them. */
    MINUTES("minutes"),

    /** The day's motion signature windows, as `getMotionWindows()` returns them. */
    MOTION_WINDOWS("motionWindows"),

    /** The day's raw Health Connect records, as `getHealthConnectRecords()` returns them. */
    HEALTH_CONNECT_RECORDS("healthConnectRecords");

    companion object {
        /** The parts named; null when any name is unknown. Validated in JS first. */
        fun parse(values: Collection<String>): Set<SnapshotPart>? {
            val out = LinkedHashSet<SnapshotPart>()
            for (value in values) out += entries.firstOrNull { it.jsValue == value } ?: return null
            return out
        }
    }
}
