package com.macrobase.app.domain.repository

import com.macrobase.app.domain.model.CalendarDaySummary
import com.macrobase.app.domain.model.DailyNutritionSummary
import com.macrobase.app.domain.model.DiaryEntry
import com.macrobase.app.domain.model.MealType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.LocalDate

data class DailyMacroAggregation(
    val date: LocalDate,
    val calories: Double,
    val proteinGrams: Double,
    val carbsGrams: Double,
    val fatGrams: Double
)

/**
 * One logged food as the rank scores it. A secondary nutrient is null when the entry did not
 * record it, and 0.0 only when the food has none (AGENTS.md 2.4).
 */
data class LoggedFoodNutrients(
    val date: LocalDate,
    /** The food the entry logged; 0 when the source has no per-food detail. */
    val foodId: Long,
    val foodName: String,
    val calories: Double,
    val proteinGrams: Double,
    val carbsGrams: Double,
    val fatGrams: Double,
    val fiberGrams: Double? = null,
    val sugarGrams: Double? = null,
    val saturatedFatGrams: Double? = null,
    val sodiumMg: Double? = null
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

    /**
     * Deletes every entry in [entryIds], as for a whole meal. This fallback goes one entry at a
     * time; a repository that can should do it in one statement, as DiaryRepositoryImpl does.
     */
    suspend fun deleteEntries(entryIds: List<Long>) {
        entryIds.forEach { deleteEntry(it) }
    }

    /**
     * Moves every entry in [entryIds] to [mealType] on [date]. Only the day and meal change: what
     * each entry logged stays as it was (AGENTS.md section 1.4). This fallback goes one entry at
     * a time, like [deleteEntries].
     */
    suspend fun moveEntries(entryIds: List<Long>, date: LocalDate, mealType: MealType) {
        entryIds.forEach { id -> getEntryById(id)?.let { updateEntry(it.copy(date = date, mealType = mealType)) } }
    }
    suspend fun getMonthlyAdherence(year: Int, month: Int): List<CalendarDaySummary>
    fun observeMonthlyAdherence(year: Int, month: Int): Flow<List<CalendarDaySummary>>
    fun observeNutritionAggregations(startDate: LocalDate, endDate: LocalDate): Flow<List<DailyMacroAggregation>>
    suspend fun getNutritionAggregations(startDate: LocalDate, endDate: LocalDate): List<DailyMacroAggregation>

    /**
     * Every food logged from [startDate] to [endDate]. A repository without per-food detail gives
     * one row per day with the day's totals and the secondary nutrients unknown.
     */
    fun observeLoggedFoodNutrients(startDate: LocalDate, endDate: LocalDate): Flow<List<LoggedFoodNutrients>> =
        observeNutritionAggregations(startDate, endDate).map { days ->
            days.map { LoggedFoodNutrients(it.date, 0L, "", it.calories, it.proteinGrams, it.carbsGrams, it.fatGrams) }
        }
}
