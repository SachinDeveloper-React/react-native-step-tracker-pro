package com.steptrackerpro.core

/**
 * The unit the notification shows distance in, as `notificationDistanceUnit`
 * sets it. `auto` follows where the phone is set up: miles in the countries
 * that give walking distances in them, kilometres everywhere else. Pure, so
 * the choice is pinned by JVM tests.
 */
object DistanceUnit {

    const val KM = "km"
    const val MI = "mi"
    const val AUTO = "auto"

    const val METERS_PER_MILE = 1_609.344

    /** A known setting, or [KM] - what every earlier release showed - for anything else. */
    fun from(value: String?): String = if (value == MI || value == AUTO) value else KM

    /** Whether [setting] shows miles, on a phone whose locale's country is [country]. */
    fun miles(setting: String, country: String?): Boolean = when (setting) {
        MI -> true
        AUTO -> country?.uppercase() in MILE_COUNTRIES
        else -> false
    }

    /** [meters] in the unit [miles] picks. */
    fun convert(meters: Double, miles: Boolean): Double =
        if (miles) meters / METERS_PER_MILE else meters / 1_000.0

    /** The United States, the United Kingdom, Liberia and Myanmar. */
    private val MILE_COUNTRIES = setOf("US", "GB", "LR", "MM")
}
