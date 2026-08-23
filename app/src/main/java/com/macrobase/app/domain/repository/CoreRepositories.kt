package com.macrobase.app.domain.repository

import com.macrobase.app.domain.model.ConsistencyStatistics
import com.macrobase.app.domain.model.Goal
import com.macrobase.app.domain.model.MacroAveragesStatistics
import com.macrobase.app.domain.model.Recipe
import com.macrobase.app.domain.model.UserPreferences
import com.macrobase.app.domain.model.WaterEntry
import com.macrobase.app.domain.model.WeightEntry
import com.macrobase.app.domain.model.WeightStatistics
import kotlinx.coroutines.flow.Flow
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
}

/**
 * Repository contract for user profile and unit system preferences.
 */
interface PreferencesRepository {
    suspend fun getPreferences(): UserPreferences
    fun observePreferences(): Flow<UserPreferences>
    suspend fun updatePreferences(preferences: UserPreferences)
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
