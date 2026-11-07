package com.macrobase.app.core.navigation

import com.macrobase.app.domain.model.MealType
import java.time.LocalDate

/**
 * Where a new log goes. Every way of starting one takes its route from here, so none picks its own
 * day or meal (BUG-003). A null [date] means today when it is logged, so a screen left open
 * overnight logs to the new day (BUG-040).
 */
data class LogContext(val date: LocalDate?, val meal: MealType) {
    val searchRoute: String get() = Screen.Search.createRoute(meal.name, date?.toEpochDay())
    val waterRoute: String get() = Screen.Water.createRoute(date?.toEpochDay())
    val weightLogRoute: String get() = Screen.Weight.createRoute(date?.toEpochDay(), openLog = true)
}
