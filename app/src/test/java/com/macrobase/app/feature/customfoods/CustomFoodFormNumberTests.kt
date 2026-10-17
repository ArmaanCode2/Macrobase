package com.macrobase.app.feature.customfoods

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class CustomFoodFormNumberTests {

    @Test
    fun blankIsUnknown_notAnError() {
        assertEquals(FormNumber(null, null), readFormNumber(""))
        assertEquals(FormNumber(null, null), readFormNumber("   "))
    }

    @Test
    fun commaAndPointDecimalsBothRead() {
        assertEquals(1.5, readFormNumber("1,5").value!!, 0.0)
        assertEquals(1.5, readFormNumber("1.5").value!!, 0.0)
        assertEquals(0.0, readFormNumber("0").value!!, 0.0)
    }

    // A stored NaN would make every later backup unreadable
    @Test
    fun nanInfinityAndNegativesAreRefused() {
        for (text in listOf("NaN", "Infinity", "1e400", "-2", "abc")) {
            val read = readFormNumber(text)
            assertNull(text, read.value)
            assertNotNull(text, read.error)
        }
    }

    @Test
    fun aThousandsSeparatorIsRefusedOnlyWhereItMeansThousands() {
        assertNull(readFormNumber("2,000", largeAmount = true).value)
        assertEquals("Leave out the thousands separator", readFormNumber("2,000", largeAmount = true).error)
        assertEquals(2000.0, readFormNumber("2000", largeAmount = true).value!!, 0.0)
        assertEquals(2.0, readFormNumber("2,000").value!!, 0.0)
    }
}
