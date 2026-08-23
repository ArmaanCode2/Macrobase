package com.macrobase.app.domain.model

import java.time.Instant
import java.time.LocalDate

/**
 * Domain model representing a user body weight measurement entry.
 */
data class WeightEntry(
    val id: Long = 0,
    val date: LocalDate,
    val weightKg: Double,
    val note: String? = null,
    val loggedAt: Instant = Instant.now()
)

/**
 * Domain model representing a water intake hydration entry.
 */
data class WaterEntry(
    val id: Long = 0,
    val date: LocalDate,
    val amountMl: Double,
    val loggedAt: Instant = Instant.now()
)
