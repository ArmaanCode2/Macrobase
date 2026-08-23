package com.macrobase.app.core.util

import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

object DashboardDateFormatter {

    val DEFAULT_DATE_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("EEEE, MM/dd")
    private val COMPACT_DATE_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("MM/dd")

    fun formatDashboardDate(
        selectedDate: LocalDate,
        today: LocalDate = LocalDate.now(ZoneId.systemDefault())
    ): String {
        return if (selectedDate == today) {
            "Today"
        } else {
            selectedDate.format(DEFAULT_DATE_FORMATTER)
        }
    }
}
