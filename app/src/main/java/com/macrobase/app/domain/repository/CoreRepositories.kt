package com.macrobase.app.domain.repository

import com.macrobase.app.domain.model.ConsistencyStatistics
import com.macrobase.app.domain.model.Goal
import com.macrobase.app.domain.model.GoalBackupDto
import com.macrobase.app.domain.model.MacroAveragesStatistics
import com.macrobase.app.domain.model.Recipe
import com.macrobase.app.domain.model.UserPreferences
import com.macrobase.app.domain.model.WaterEntry
import com.macrobase.app.domain.model.WeightEntry
import com.macrobase.app.domain.model.WeightStatistics
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import java.time.LocalDate

/**
 * Repository contract for multi-ingredient composite recipes.
 */
interface RecipeRepository {
    suspend fun createRecipe(recipe: Recipe): Long
    suspend fun updateRecipe(recipe: Recipe)
    suspend fun deleteRecipe(recipeId: Long)
    fun observeRecipes(): Flow<List<Recipe>>
    suspend fun getRecipes(): List<Recipe>
    suspend fun getRecipeById(recipeId: Long): Recipe?
}

/**
 * Repository contract for body weight tracking.
 */
interface WeightRepository {
    suspend fun addWeightEntry(entry: WeightEntry): Long
    suspend fun updateWeightEntry(entry: WeightEntry)
    suspend fun deleteWeightEntry(entryId: Long)
    suspend fun deleteWeightEntryForDate(date: LocalDate)
    fun observeWeightForDate(date: LocalDate): Flow<WeightEntry?>
    suspend fun getWeightForDate(date: LocalDate): WeightEntry?
    fun observeWeightHistory(): Flow<List<WeightEntry>>
    suspend fun getWeightHistory(): List<WeightEntry>
    fun observeWeightRange(startDate: LocalDate, endDate: LocalDate): Flow<List<WeightEntry>>
    suspend fun getWeightRange(startDate: LocalDate, endDate: LocalDate): List<WeightEntry>
}

/**
 * Repository contract for water hydration tracking.
 */
interface WaterRepository {
    suspend fun addWaterEntry(entry: WaterEntry): Long
    suspend fun updateWaterEntry(entry: WaterEntry)
    suspend fun deleteWaterEntry(entryId: Long)
    fun observeWaterTotalForDate(date: LocalDate): Flow<Double>
    suspend fun getWaterTotalForDate(date: LocalDate): Double
    fun observeWaterEntriesForDate(date: LocalDate): Flow<List<WaterEntry>>
    suspend fun getWaterEntriesForDate(date: LocalDate): List<WaterEntry>
    fun observeWaterHistory(startDate: LocalDate, endDate: LocalDate): Flow<List<WaterEntry>>
    suspend fun getWaterHistory(startDate: LocalDate, endDate: LocalDate): List<WaterEntry>

    fun observeWaterForDate(date: LocalDate): Flow<Double> = observeWaterTotalForDate(date)
    suspend fun getWaterForDate(date: LocalDate): Double = getWaterTotalForDate(date)
}

/**
 * Repository contract for calorie targets and macronutrient split goals.
 */
interface GoalsRepository {
    suspend fun getGoals(): Goal
    fun observeGoals(): Flow<Goal>
    suspend fun updateGoals(goals: Goal)
    suspend fun getGoalForDate(date: LocalDate): Goal = getGoals()

    /**
     * Drops a strategy change scheduled for tomorrow, so the current strategy stays (BUG-031).
     * Returns false when there was nothing to drop, for example because the change has started.
     * Repositories that schedule changes override this; one that never schedules has nothing to drop.
     */
    suspend fun cancelScheduledGoal(): Boolean = false

    /** [getGoalForDate] for many days at once, in the same order. */
    suspend fun getGoalsForDates(dates: List<LocalDate>): List<Goal> = dates.map { getGoalForDate(it) }

    /**
     * The first day with a saved goal. An earlier day's goal is only a guess, so the rank leaves
     * those days out. Null when every day's goal is known, as for a repository without history.
     */
    suspend fun getGoalHistoryStart(): LocalDate? = null

    /**
     * [getGoalsForDates] as a flow. It follows every change to the stored goals, including one to
     * past days' history that leaves today's goal as it is (BUG-049).
     */
    fun observeGoalsForDates(dates: List<LocalDate>): Flow<List<Goal>> = observeGoals().map { getGoalsForDates(dates) }

    /** Everything a backup needs to restore goals exactly, including the strategy history. */
    suspend fun exportGoalBackup(): GoalBackupDto = GoalBackupDto.from(getGoals())

    /**
     * Replaces this device's goals with a backup's as they were: nothing is scheduled and no
     * history is added (BUG-016). A backup older than format 1.2.0 has only the calorie and macro
     * targets; the strategy, scheduled change and history on this device then stay as they are.
     * This default only sets the targets; GoalsRepositoryImpl restores the full state.
     */
    suspend fun restoreGoalBackup(backup: GoalBackupDto) {
        val current = getGoals()
        updateGoals(
            current.copy(
                dailyCalorieGoal = backup.dailyCalorieGoal,
                carbPercentage = backup.carbPercentage,
                proteinPercentage = backup.proteinPercentage,
                fatPercentage = backup.fatPercentage
            )
        )
    }

    /** False on a device where goals were never saved, such as right after installing. */
    suspend fun hasSavedGoals(): Boolean = true
}

/**
 * Repository contract for user profile and unit system preferences.
 */
interface PreferencesRepository {
    suspend fun getPreferences(): UserPreferences
    fun observePreferences(): Flow<UserPreferences>
    suspend fun updatePreferences(preferences: UserPreferences)

    /** False on a device where the profile was never saved, such as right after installing. */
    suspend fun hasSavedPreferences(): Boolean = true

    /** The stored profile for a backup: fails when it cannot be read, never returns defaults. */
    suspend fun getPreferencesForBackup(): UserPreferences = getPreferences()

    /**
     * The version of the rank rules whose "rank recalculated" notice the user has dismissed, or 0.
     * Kept outside the profile, so backups do not carry it. Repositories that never show the
     * notice report every version as seen.
     */
    fun observeSeenRankRulesVersion(): Flow<Int> = flowOf(Int.MAX_VALUE)
    suspend fun setSeenRankRulesVersion(version: Int) {}

    /**
     * The start, as an epoch day, of the last rank season whose "new season" notice the user
     * dismissed, or [Long.MIN_VALUE]. Kept outside the profile like the rules notice, so a
     * restore on another phone shows it once more. Repositories that never show the notice
     * report every season as seen.
     */
    fun observeSeenRankSeason(): Flow<Long> = flowOf(Long.MAX_VALUE)
    suspend fun setSeenRankSeason(seasonStartEpochDay: Long) {}
}

/**
 * Repository contract for aggregated analytics and consistency statistics.
 */
interface StatisticsRepository {
    fun observeNutritionConsistency(startDate: LocalDate, endDate: LocalDate): Flow<ConsistencyStatistics>
    suspend fun getNutritionConsistency(startDate: LocalDate, endDate: LocalDate): ConsistencyStatistics
    fun observeMacroAverages(startDate: LocalDate, endDate: LocalDate): Flow<MacroAveragesStatistics>
    suspend fun getMacroAverages(startDate: LocalDate, endDate: LocalDate): MacroAveragesStatistics
    fun observeWeightStatistics(startDate: LocalDate, endDate: LocalDate): Flow<WeightStatistics>
    suspend fun getWeightStatistics(startDate: LocalDate, endDate: LocalDate): WeightStatistics
}
