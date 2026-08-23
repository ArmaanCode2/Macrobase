package com.macrobase.app.data.repository

import com.macrobase.app.data.database.UserDatabase
import com.macrobase.app.data.database.entity.RecipeEntity
import com.macrobase.app.data.database.entity.WaterLogEntity
import com.macrobase.app.data.database.entity.WeightEntryEntity
import com.macrobase.app.domain.model.ConsistencyStatistics
import com.macrobase.app.domain.model.Goal
import com.macrobase.app.domain.model.HeatmapCellData
import com.macrobase.app.domain.model.HeatmapWeek
import com.macrobase.app.domain.model.MacroAveragesStatistics
import com.macrobase.app.domain.model.Nutrition
import com.macrobase.app.domain.model.Recipe
import com.macrobase.app.domain.model.UserPreferences
import com.macrobase.app.domain.model.WaterEntry
import com.macrobase.app.domain.model.WeightEntry
import com.macrobase.app.domain.model.WeightStatistics
import com.macrobase.app.domain.repository.DailyMacroAggregation
import com.macrobase.app.domain.repository.DiaryRepository
import com.macrobase.app.domain.repository.GoalsRepository
import com.macrobase.app.domain.repository.PreferencesRepository
import com.macrobase.app.domain.repository.RecipeRepository
import com.macrobase.app.domain.repository.StatisticsRepository
import com.macrobase.app.domain.repository.WaterRepository
import com.macrobase.app.domain.repository.WeightRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.time.Instant
import java.time.LocalDate

class RecipeRepositoryImpl(
    private val userDatabase: UserDatabase
) : RecipeRepository {

    override suspend fun createRecipe(recipe: Recipe): Long {
        val entity = RecipeEntity(
            id = recipe.id,
            uuid = recipe.uuid,
            name = recipe.name,
            servingsProduced = recipe.servingsProduced,
            ingredientsJson = "[]",
            caloriesPerServing = recipe.nutritionPerServing.calories,
            proteinPerServing = recipe.nutritionPerServing.proteinGrams,
            carbsPerServing = recipe.nutritionPerServing.carbsGrams,
            fatPerServing = recipe.nutritionPerServing.fatGrams,
            createdAt = recipe.createdAt.toEpochMilli()
        )
        return userDatabase.recipeDao().insertRecipe(entity)
    }

    override suspend fun updateRecipe(recipe: Recipe) {
        createRecipe(recipe)
    }

    override suspend fun deleteRecipe(recipeId: Long) {
        userDatabase.recipeDao().deleteRecipe(recipeId)
    }

    override fun observeRecipes(): Flow<List<Recipe>> {
        return userDatabase.recipeDao().observeAllRecipes().map { list ->
            list.map {
                Recipe(
                    id = it.id,
                    uuid = it.uuid,
                    name = it.name,
                    servingsProduced = it.servingsProduced,
                    createdAt = Instant.ofEpochMilli(it.createdAt)
                )
            }
        }
    }

    override suspend fun getRecipes(): List<Recipe> {
        return userDatabase.recipeDao().getAllRecipes().map {
            Recipe(
                id = it.id,
                uuid = it.uuid,
                name = it.name,
                servingsProduced = it.servingsProduced,
                createdAt = Instant.ofEpochMilli(it.createdAt)
            )
        }
    }

    override suspend fun getRecipeById(recipeId: Long): Recipe? {
        return getRecipes().firstOrNull { it.id == recipeId }
    }
}

class WeightRepositoryImpl(
    private val userDatabase: UserDatabase
) : WeightRepository {

    override suspend fun addWeightEntry(entry: WeightEntry): Long {
        val entity = WeightEntryEntity(
            id = entry.id,
            dateEpochDay = entry.date.toEpochDay(),
            weightKg = entry.weightKg,
            note = entry.note,
            createdAt = entry.loggedAt.toEpochMilli()
        )
        return userDatabase.weightDao().insertWeightEntry(entity)
    }

    override suspend fun updateWeightEntry(entry: WeightEntry) {
        addWeightEntry(entry)
    }

    override suspend fun deleteWeightEntry(entryId: Long) {
        userDatabase.weightDao().deleteWeightEntry(entryId)
    }

    override suspend fun deleteWeightEntryForDate(date: LocalDate) {
        userDatabase.weightDao().deleteWeightEntryForDate(date.toEpochDay())
    }

    override fun observeWeightForDate(date: LocalDate): Flow<WeightEntry?> {
        return userDatabase.weightDao().getWeightEntryForDate(date.toEpochDay()).map { it?.toDomain() }
    }

    override suspend fun getWeightForDate(date: LocalDate): WeightEntry? {
        return userDatabase.weightDao().getWeightEntrySnapshot(date.toEpochDay())?.toDomain()
    }

    override fun observeWeightHistory(): Flow<List<WeightEntry>> {
        return userDatabase.weightDao().getAllWeightEntries().map { list ->
            list.map { it.toDomain() }
        }
    }

    override suspend fun getWeightHistory(): List<WeightEntry> {
        return observeWeightHistory().first()
    }

    override fun observeWeightRange(startDate: LocalDate, endDate: LocalDate): Flow<List<WeightEntry>> {
        return userDatabase.weightDao().getWeightEntriesBetween(startDate.toEpochDay(), endDate.toEpochDay()).map { list ->
            list.map { it.toDomain() }
        }
    }

    override suspend fun getWeightRange(startDate: LocalDate, endDate: LocalDate): List<WeightEntry> {
        return observeWeightRange(startDate, endDate).first()
    }

    private fun WeightEntryEntity.toDomain(): WeightEntry {
        return WeightEntry(
            id = id,
            date = LocalDate.ofEpochDay(dateEpochDay),
            weightKg = weightKg,
            note = note,
            loggedAt = Instant.ofEpochMilli(createdAt)
        )
    }
}

class WaterRepositoryImpl(
    private val userDatabase: UserDatabase
) : WaterRepository {

    override suspend fun addWaterEntry(entry: WaterEntry): Long {
        val entity = WaterLogEntity(
            id = entry.id,
            dateEpochDay = entry.date.toEpochDay(),
            amountMl = entry.amountMl,
            timestamp = entry.loggedAt.toEpochMilli()
        )
        return userDatabase.waterDao().insertWaterLog(entity)
    }

    override suspend fun updateWaterEntry(entry: WaterEntry) {
        val entity = WaterLogEntity(
            id = entry.id,
            dateEpochDay = entry.date.toEpochDay(),
            amountMl = entry.amountMl,
            timestamp = entry.loggedAt.toEpochMilli()
        )
        userDatabase.waterDao().updateWaterLog(entity)
    }

    override suspend fun deleteWaterEntry(entryId: Long) {
        userDatabase.waterDao().deleteWaterLog(entryId)
    }

    override fun observeWaterTotalForDate(date: LocalDate): Flow<Double> {
        return userDatabase.waterDao().getWaterIntakeForDate(date.toEpochDay()).map { it ?: 0.0 }
    }

    override suspend fun getWaterTotalForDate(date: LocalDate): Double {
        return observeWaterTotalForDate(date).first()
    }

    override fun observeWaterEntriesForDate(date: LocalDate): Flow<List<WaterEntry>> {
        return userDatabase.waterDao().getWaterEntriesForDate(date.toEpochDay()).map { list ->
            list.map { it.toDomain() }
        }
    }

    override suspend fun getWaterEntriesForDate(date: LocalDate): List<WaterEntry> {
        return observeWaterEntriesForDate(date).first()
    }

    override fun observeWaterHistory(startDate: LocalDate, endDate: LocalDate): Flow<List<WaterEntry>> {
        return userDatabase.waterDao().getWaterLogsBetween(startDate.toEpochDay(), endDate.toEpochDay()).map { list ->
            list.map { it.toDomain() }
        }
    }

    override suspend fun getWaterHistory(startDate: LocalDate, endDate: LocalDate): List<WaterEntry> {
        return observeWaterHistory(startDate, endDate).first()
    }

    private fun WaterLogEntity.toDomain(): WaterEntry {
        return WaterEntry(
            id = id,
            date = LocalDate.ofEpochDay(dateEpochDay),
            amountMl = amountMl,
            loggedAt = Instant.ofEpochMilli(timestamp)
        )
    }
}


class StatisticsRepositoryImpl(
    private val diaryRepository: DiaryRepository,
    private val goalsRepository: GoalsRepository,
    private val weightRepository: WeightRepository
) : StatisticsRepository {

    override fun observeNutritionConsistency(startDate: LocalDate, endDate: LocalDate): Flow<ConsistencyStatistics> {
        return combine(
            diaryRepository.observeNutritionAggregations(startDate, endDate),
            goalsRepository.observeGoals()
        ) { aggregations, goals ->
            val aggMap = aggregations.associateBy { it.date }
            val today = LocalDate.now()
            val allCells = mutableListOf<com.macrobase.app.domain.model.HeatmapCellData>()

            var curr = startDate
            while (!curr.isAfter(endDate)) {
                val agg = aggMap[curr]
                val cals = agg?.calories ?: 0.0
                val goalCals = goals.dailyCalorieGoal
                val isEligible = !curr.isAfter(today)
                val isFuture = curr.isAfter(today)
                val category = if (isFuture) {
                    com.macrobase.app.core.config.CalendarPerformanceConfig.PerformanceCategory.EMPTY_MISSED
                } else {
                    com.macrobase.app.core.config.CalendarPerformanceConfig.evaluatePerformance(cals, goalCals)
                }
                val pct = if (goalCals > 0.0) ((cals / goalCals) * 100.0).toInt() else 0

                allCells.add(
                    com.macrobase.app.domain.model.HeatmapCellData(
                        date = curr,
                        dayOfWeek = curr.dayOfWeek.value,
                        caloriesLogged = cals,
                        dailyGoalCalories = goalCals,
                        percentageOfGoal = pct,
                        category = category,
                        isEligible = isEligible,
                        isFuture = isFuture
                    )
                )
                curr = curr.plusDays(1)
            }

            // Organize into weekly columns (Sun..Sat or Mon..Sun; let's align Sun=0..Sat=6)
            val weeks = mutableListOf<com.macrobase.app.domain.model.HeatmapWeek>()
            val firstDayOffset = startDate.dayOfWeek.value % 7 // 0 for Sunday
            var weekIndex = 0
            var currentWeek = mutableListOf<com.macrobase.app.domain.model.HeatmapCellData?>()

            repeat(firstDayOffset) {
                currentWeek.add(null)
            }

            for (cell in allCells) {
                currentWeek.add(cell)
                if (currentWeek.size == 7) {
                    weeks.add(com.macrobase.app.domain.model.HeatmapWeek(weekIndex++, currentWeek))
                    currentWeek = mutableListOf()
                }
            }

            if (currentWeek.isNotEmpty()) {
                while (currentWeek.size < 7) {
                    currentWeek.add(null)
                }
                weeks.add(com.macrobase.app.domain.model.HeatmapWeek(weekIndex, currentWeek))
            }

            val eligibleCells = allCells.filter { it.isEligible }
            val loggedDays = eligibleCells.count { it.caloriesLogged > 0.0 }
            val score = if (eligibleCells.isNotEmpty()) {
                ((loggedDays.toDouble() / eligibleCells.size.toDouble()) * 100.0).toInt()
            } else 0

            com.macrobase.app.domain.model.ConsistencyStatistics(
                startDate = startDate,
                endDate = endDate,
                totalEligibleDays = eligibleCells.size,
                loggedDaysCount = loggedDays,
                consistencyScorePercentage = score,
                weeks = weeks,
                allCells = allCells
            )
        }
    }

    override suspend fun getNutritionConsistency(startDate: LocalDate, endDate: LocalDate): ConsistencyStatistics {
        return observeNutritionConsistency(startDate, endDate).first()
    }

    override fun observeMacroAverages(startDate: LocalDate, endDate: LocalDate): Flow<com.macrobase.app.domain.model.MacroAveragesStatistics> {
        return combine(
            diaryRepository.observeNutritionAggregations(startDate, endDate),
            goalsRepository.observeGoals()
        ) { aggregations, goals ->
            val loggedDays = aggregations.filter { it.calories > 0.0 }
            val count = loggedDays.size

            if (count == 0) {
                com.macrobase.app.domain.model.MacroAveragesStatistics(
                    startDate = startDate,
                    endDate = endDate,
                    loggedDaysCount = 0,
                    averageCalories = 0.0,
                    averageProteinGrams = 0.0,
                    averageCarbsGrams = 0.0,
                    averageFatGrams = 0.0,
                    actualCarbsPercent = 0.0,
                    actualProteinPercent = 0.0,
                    actualFatPercent = 0.0,
                    targetCarbsPercent = goals.carbPercentage,
                    targetProteinPercent = goals.proteinPercentage,
                    targetFatPercent = goals.fatPercentage,
                    targetCalories = goals.dailyCalorieGoal
                )
            } else {
                val sumCal = loggedDays.sumOf { it.calories }
                val sumP = loggedDays.sumOf { it.proteinGrams }
                val sumC = loggedDays.sumOf { it.carbsGrams }
                val sumF = loggedDays.sumOf { it.fatGrams }

                val avgCal = sumCal / count
                val avgP = sumP / count
                val avgC = sumC / count
                val avgF = sumF / count

                val calP = avgP * 4.0
                val calC = avgC * 4.0
                val calF = avgF * 9.0
                val totalMacroCals = calP + calC + calF

                val actualP = if (totalMacroCals > 0.0) (calP / totalMacroCals) * 100.0 else 0.0
                val actualC = if (totalMacroCals > 0.0) (calC / totalMacroCals) * 100.0 else 0.0
                val actualF = if (totalMacroCals > 0.0) (calF / totalMacroCals) * 100.0 else 0.0

                com.macrobase.app.domain.model.MacroAveragesStatistics(
                    startDate = startDate,
                    endDate = endDate,
                    loggedDaysCount = count,
                    averageCalories = avgCal,
                    averageProteinGrams = avgP,
                    averageCarbsGrams = avgC,
                    averageFatGrams = avgF,
                    actualCarbsPercent = actualC,
                    actualProteinPercent = actualP,
                    actualFatPercent = actualF,
                    targetCarbsPercent = goals.carbPercentage,
                    targetProteinPercent = goals.proteinPercentage,
                    targetFatPercent = goals.fatPercentage,
                    targetCalories = goals.dailyCalorieGoal
                )
            }
        }
    }

    override suspend fun getMacroAverages(startDate: LocalDate, endDate: LocalDate): com.macrobase.app.domain.model.MacroAveragesStatistics {
        return observeMacroAverages(startDate, endDate).first()
    }

    override fun observeWeightStatistics(startDate: LocalDate, endDate: LocalDate): Flow<WeightStatistics> {
        return weightRepository.observeWeightRange(startDate, endDate).map { entries ->
            val sorted = entries.sortedBy { it.date }
            val weights = sorted.map { it.weightKg }
            val start = weights.firstOrNull()
            val current = weights.lastOrNull()
            val delta = if (start != null && current != null) current - start else null
            val percent = if (start != null && start > 0.0 && current != null) ((current - start) / start) * 100.0 else null

            WeightStatistics(
                startDate = startDate,
                endDate = endDate,
                initialWeightKg = start,
                latestWeightKg = current,
                minWeightKg = weights.minOrNull(),
                maxWeightKg = weights.maxOrNull(),
                averageWeightKg = if (weights.isNotEmpty()) weights.average() else null,
                deltaWeightKg = delta,
                percentageChange = percent,
                entries = sorted
            )
        }
    }

    override suspend fun getWeightStatistics(startDate: LocalDate, endDate: LocalDate): WeightStatistics {
        return observeWeightStatistics(startDate, endDate).first()
    }
}
