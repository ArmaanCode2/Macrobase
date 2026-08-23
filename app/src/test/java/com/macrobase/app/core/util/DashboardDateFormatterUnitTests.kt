package com.macrobase.app.core.util

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.format.DateTimeFormatter

class DashboardDateFormatterUnitTests {

    private val fixedToday = LocalDate.of(2026, 8, 21) // Friday, August 21, 2026

    @Test
    fun testSelectedDateIsToday_returnsToday() {
        val result = DashboardDateFormatter.formatDashboardDate(
            selectedDate = fixedToday,
            today = fixedToday
        )
        assertEquals("Today", result)
    }

    @Test
    fun testSelectedDateIsYesterday_returnsFormattedDate() {
        val yesterday = fixedToday.minusDays(1) // Thursday, August 20, 2026
        val result = DashboardDateFormatter.formatDashboardDate(
            selectedDate = yesterday,
            today = fixedToday
        )
        assertEquals("Thursday, 08/20", result)
    }

    @Test
    fun testSelectedDateIsTomorrow_returnsFormattedDate() {
        val tomorrow = fixedToday.plusDays(1) // Saturday, August 22, 2026
        val result = DashboardDateFormatter.formatDashboardDate(
            selectedDate = tomorrow,
            today = fixedToday
        )
        assertEquals("Saturday, 08/22", result)
    }

    @Test
    fun testMonthTransition_returnsFormattedDate() {
        val endOfMonth = LocalDate.of(2026, 8, 31) // Monday, August 31
        val startOfNextMonth = LocalDate.of(2026, 9, 1) // Tuesday, September 1

        val result1 = DashboardDateFormatter.formatDashboardDate(
            selectedDate = endOfMonth,
            today = fixedToday
        )
        assertEquals("Monday, 08/31", result1)

        val result2 = DashboardDateFormatter.formatDashboardDate(
            selectedDate = startOfNextMonth,
            today = fixedToday
        )
        assertEquals("Tuesday, 09/01", result2)
    }

    @Test
    fun testYearTransition_returnsFormattedDate() {
        val newYearsEve = LocalDate.of(2026, 12, 31) // Thursday, December 31
        val newYearsDay = LocalDate.of(2027, 1, 1) // Friday, January 1

        val result1 = DashboardDateFormatter.formatDashboardDate(
            selectedDate = newYearsEve,
            today = fixedToday
        )
        assertEquals("Thursday, 12/31", result1)

        val result2 = DashboardDateFormatter.formatDashboardDate(
            selectedDate = newYearsDay,
            today = fixedToday
        )
        assertEquals("Friday, 01/01", result2)
    }

    @Test
    fun testLeapYear_returnsFormattedDate() {
        val leapDay = LocalDate.of(2024, 2, 29) // Thursday, February 29, 2024
        val result = DashboardDateFormatter.formatDashboardDate(
            selectedDate = leapDay,
            today = fixedToday
        )
        assertEquals("Thursday, 02/29", result)
    }
}
