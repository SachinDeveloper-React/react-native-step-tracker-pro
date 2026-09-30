package com.steptrackerpro.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The notification's distance unit: kilometres unless config says miles, or
 * says `auto` on a phone set up where people walk in miles.
 */
class DistanceUnitTest {

    @Test
    fun `an unknown or missing setting is kilometres, as every earlier release showed`() {
        assertEquals(DistanceUnit.KM, DistanceUnit.from(null))
        assertEquals(DistanceUnit.KM, DistanceUnit.from("miles"))
        assertEquals(DistanceUnit.MI, DistanceUnit.from("mi"))
        assertEquals(DistanceUnit.AUTO, DistanceUnit.from("auto"))
    }

    @Test
    fun `auto follows the phone's country, the others do not`() {
        for (country in listOf("US", "GB", "LR", "MM", "us")) {
            assertTrue(country, DistanceUnit.miles(DistanceUnit.AUTO, country))
        }
        for (country in listOf("IN", "DE", "CA", "AU", "", null)) {
            assertFalse(country, DistanceUnit.miles(DistanceUnit.AUTO, country))
        }
        assertTrue(DistanceUnit.miles(DistanceUnit.MI, "DE"))
        assertFalse(DistanceUnit.miles(DistanceUnit.KM, "US"))
    }

    @Test
    fun `metres convert to the unit picked`() {
        assertEquals(1.0, DistanceUnit.convert(1_609.344, miles = true), 1e-9)
        assertEquals(1.609344, DistanceUnit.convert(1_609.344, miles = false), 1e-9)
    }
}
