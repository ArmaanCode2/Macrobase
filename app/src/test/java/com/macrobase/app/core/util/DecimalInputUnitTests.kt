package com.macrobase.app.core.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.ZoneId
import java.util.Locale
import java.util.TimeZone

class DecimalInputUnitTests {

    @Test
    fun commaAndDotDecimalsBothParse() {
        assertEquals(72.5, parseDecimalInput("72,5")!!, 0.0)
        assertEquals(72.5, parseDecimalInput("72.5")!!, 0.0)
        assertEquals(72.5, parseDecimalInput("  72.5 ")!!, 0.0)
        assertEquals(0.5, parseDecimalInput(",5")!!, 0.0)
        assertEquals(2.0, parseDecimalInput("2.")!!, 0.0)
        // Range checks belong to the caller; parsing keeps the sign
        assertEquals(-5.0, parseDecimalInput("-5")!!, 0.0)
    }

    @Test
    fun malformedOrNonFiniteInputIsNull() {
        for (text in listOf("", "  ", "-", "abc", "1,2,3", "1.2,3", "NaN", "Infinity", "1e999", "1e3", "5d", "0x10", "7 kg")) {
            assertNull("\"$text\" should not parse", parseDecimalInput(text))
        }
    }

    @Test
    fun formattedValuesReadBackInACommaLocale() {
        val original = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            assertEquals("72.5", formatDecimalInput(72.5))
            assertEquals("72.50", formatDecimalInput(72.5, decimals = 2))
            assertEquals(72.5, parseDecimalInput(formatDecimalInput(72.5))!!, 0.0)
        } finally {
            Locale.setDefault(original)
        }
    }

    @Test
    fun deviceClockFollowsTheTimeZoneTheDeviceIsSetTo() {
        val original = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Kolkata"))
            assertEquals(ZoneId.of("Asia/Kolkata"), DeviceClock.zone)
            TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"))
            assertEquals(ZoneId.of("America/New_York"), DeviceClock.zone)
        } finally {
            TimeZone.setDefault(original)
        }
    }
}
