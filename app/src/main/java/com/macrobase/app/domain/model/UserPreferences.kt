package com.macrobase.app.domain.model

/**
 * Domain model representing user profile, unit preferences, and target parameters.
 */
data class UserPreferences(
    val firstName: String = "",
    val lastName: String = "",
    val timeZone: String = "UTC",
    val unitSystem: UnitSystem = UnitSystem.METRIC,
    val dailyWaterGoalMl: Double = 2500.0,
    val heightCm: Double? = null,
    val currentWeightKg: Double? = null,
    val targetWeightKg: Double? = null,
    val themeMode: String = "DARK"
)
