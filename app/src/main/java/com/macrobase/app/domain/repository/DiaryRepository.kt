package com.macrobase.app.domain.repository

import com.macrobase.app.domain.model.CalendarDaySummary
import com.macrobase.app.domain.model.DailyNutritionSummary
import com.macrobase.app.domain.model.DiaryEntry
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate

data class DailyMacroAggregation(
    val date: LocalDate,
    val calories: Double,
    val proteinGrams: Double,
    val carbsGrams: Double,
    val fatGrams: Double
)

/**
 * Repository contract for managing user meal diary logs and calendar adherence data.
 */
interface DiaryRepository {
    suspend fun getDiaryForDate(date: LocalDate): DailyNutritionSummary
    fun observeDiaryForDate(date: LocalDate): Flow<DailyNutritionSummary>
    suspend fun getEntryById(entryId: Long): DiaryEntry?
    suspend fun addEntry(entry: DiaryEntry): Long
    suspend fun addEntries(entries: List<DiaryEntry>)
    suspend fun updateEntry(entry: DiaryEntry)
    suspend fun deleteEntry(entryId: Long)
    suspend fun getMonthlyAdherence(year: Int, month: Int): List<CalendarDaySummary>
    fun observeMonthlyAdherence(year: Int, month: Int): Flow<List<CalendarDaySummary>>
    fun observeNutritionAggregations(startDate: LocalDate, endDate: LocalDate): Flow<List<DailyMacroAggregation>>
    suspend fun getNutritionAggregations(startDate: LocalDate, endDate: LocalDate): List<DailyMacroAggregation>
}
