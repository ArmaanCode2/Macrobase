package com.macrobase.app.data.repository

import com.macrobase.app.data.database.UserDatabase
import com.macrobase.app.data.database.entity.DiaryEntryEntity
import com.macrobase.app.domain.model.CalendarDaySummary
import com.macrobase.app.domain.model.DailyNutritionSummary
import com.macrobase.app.domain.model.DiaryEntry
import com.macrobase.app.domain.model.Food
import com.macrobase.app.domain.model.Meal
import com.macrobase.app.domain.model.MealType
import com.macrobase.app.domain.model.Nutrition
import com.macrobase.app.domain.model.Serving
import com.macrobase.app.domain.model.ServingUnit
import com.macrobase.app.domain.repository.DailyMacroAggregation
import com.macrobase.app.domain.repository.DiaryRepository
import com.macrobase.app.domain.repository.GoalsRepository
import com.macrobase.app.domain.repository.PreferencesRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth

/**
 * Concrete implementation of DiaryRepository managing daily meals, logs, and calendar adherence.
 */
class DiaryRepositoryImpl(
    private val userDatabase: UserDatabase,
    private val goalsRepository: GoalsRepository,
    private val preferencesRepository: PreferencesRepository? = null
) : DiaryRepository {

    override suspend fun getDiaryForDate(date: LocalDate): DailyNutritionSummary {
        return observeDiaryForDate(date).first()
    }

    override suspend fun getEntryById(entryId: Long): DiaryEntry? {
        return userDatabase.diaryDao().getEntryById(entryId)?.toDomain()
    }

    override fun observeDiaryForDate(date: LocalDate): Flow<DailyNutritionSummary> {
        val epochDay = date.toEpochDay()
        val diaryDao = userDatabase.diaryDao()
        val waterDao = userDatabase.waterDao()

        val prefsFlow = preferencesRepository?.observePreferences() ?: kotlinx.coroutines.flow.flowOf(com.macrobase.app.domain.model.UserPreferences())

        return combine(
            diaryDao.getEntriesForDate(epochDay),
            waterDao.getWaterIntakeForDate(epochDay),
            goalsRepository.observeGoals(),
            prefsFlow
        ) { entries, waterMl, goals, prefs ->
            val domainEntries = entries.map { it.toDomain() }
            val groupedByMeal = domainEntries.groupBy { it.mealType }

            val meals = MealType.entries.map { type ->
                Meal(type = type, entries = groupedByMeal[type] ?: emptyList())
            }

            val totalCalories = domainEntries.sumOf { it.calculatedNutrition.calories }
            val totalProtein = domainEntries.sumOf { it.calculatedNutrition.proteinGrams }
            val totalCarbs = domainEntries.sumOf { it.calculatedNutrition.carbsGrams }
            val totalFat = domainEntries.sumOf { it.calculatedNutrition.fatGrams }
            val totalFiber = domainEntries.sumOf { it.calculatedNutrition.fiberGrams ?: 0.0 }
            val totalSugar = domainEntries.sumOf { it.calculatedNutrition.sugarGrams ?: 0.0 }
            val totalSodium = domainEntries.sumOf { it.calculatedNutrition.sodiumMg ?: 0.0 }

            DailyNutritionSummary(
                date = date,
                totalCaloriesIntake = totalCalories,
                totalCaloriesBurned = 0.0,
                calorieGoal = goals.dailyCalorieGoal,
                totalProteinGrams = totalProtein,
                totalCarbsGrams = totalCarbs,
                totalFatGrams = totalFat,
                totalFiberGrams = totalFiber,
                totalSugarGrams = totalSugar,
                totalSodiumMg = totalSodium,
                totalWaterMl = waterMl ?: 0.0,
                waterGoalMl = prefs.dailyWaterGoalMl,
                meals = meals
            )
        }
    }

    override suspend fun addEntry(entry: DiaryEntry): Long {
        val entity = DiaryEntryEntity(
            id = entry.id,
            uuid = entry.uuid,
            dateEpochDay = entry.date.toEpochDay(),
            mealType = entry.mealType.name,
            foodId = entry.food.id,
            foodName = entry.food.name,
            userQuantity = entry.quantity,
            servingDescription = entry.serving.description,
            gramWeight = entry.serving.gramWeight,
            loggedCalories = entry.calculatedNutrition.calories,
            loggedProtein = entry.calculatedNutrition.proteinGrams,
            loggedCarbs = entry.calculatedNutrition.carbsGrams,
            loggedFat = entry.calculatedNutrition.fatGrams,
            createdAt = entry.loggedAt.toEpochMilli()
        )
        return userDatabase.diaryDao().insertEntry(entity)
    }

    override suspend fun addEntries(entries: List<DiaryEntry>) {
        val entities = entries.map { entry ->
            DiaryEntryEntity(
                id = entry.id,
                uuid = entry.uuid,
                dateEpochDay = entry.date.toEpochDay(),
                mealType = entry.mealType.name,
                foodId = entry.food.id,
                foodName = entry.food.name,
                userQuantity = entry.quantity,
                servingDescription = entry.serving.description,
                gramWeight = entry.serving.gramWeight,
                loggedCalories = entry.calculatedNutrition.calories,
                loggedProtein = entry.calculatedNutrition.proteinGrams,
                loggedCarbs = entry.calculatedNutrition.carbsGrams,
                loggedFat = entry.calculatedNutrition.fatGrams,
                createdAt = entry.loggedAt.toEpochMilli()
            )
        }
        userDatabase.diaryDao().insertEntries(entities)
    }

    override suspend fun updateEntry(entry: DiaryEntry) {
        addEntry(entry)
    }

    override suspend fun deleteEntry(entryId: Long) {
        userDatabase.diaryDao().deleteEntry(entryId)
    }

    override suspend fun getMonthlyAdherence(year: Int, month: Int): List<CalendarDaySummary> {
        val yearMonth = YearMonth.of(year, month)
        val startDate = yearMonth.atDay(1)
        val endDate = yearMonth.atEndOfMonth()

        val goals = goalsRepository.getGoals()
        val summaries = userDatabase.diaryDao().getMonthlyCalorieSummaries(
            startDate.toEpochDay(),
            endDate.toEpochDay()
        ).associate { it.dateEpochDay to it.totalCal }

        val result = mutableListOf<CalendarDaySummary>()
        var curr = startDate
        while (!curr.isAfter(endDate)) {
            val ep = curr.toEpochDay()
            val cal = summaries[ep] ?: 0.0
            result.add(
                CalendarDaySummary(
                    date = curr,
                    loggedCalories = cal,
                    calorieGoal = goals.dailyCalorieGoal,
                    hasLogs = cal > 0.0
                )
            )
            curr = curr.plusDays(1)
        }
        return result
    }

    override fun observeMonthlyAdherence(year: Int, month: Int): Flow<List<CalendarDaySummary>> {
        val yearMonth = YearMonth.of(year, month)
        val startDate = yearMonth.atDay(1)
        val endDate = yearMonth.atEndOfMonth()

        return combine(
            userDatabase.diaryDao().observeMonthlyCalorieSummaries(startDate.toEpochDay(), endDate.toEpochDay()),
            goalsRepository.observeGoals()
        ) { summariesList, goals ->
            val summaries = summariesList.associate { it.dateEpochDay to it.totalCal }
            val result = mutableListOf<CalendarDaySummary>()
            var curr = startDate
            while (!curr.isAfter(endDate)) {
                val ep = curr.toEpochDay()
                val cal = summaries[ep] ?: 0.0
                result.add(
                    CalendarDaySummary(
                        date = curr,
                        loggedCalories = cal,
                        calorieGoal = goals.dailyCalorieGoal,
                        hasLogs = cal > 0.0
                    )
                )
                curr = curr.plusDays(1)
            }
            result
        }
    }

    override fun observeNutritionAggregations(startDate: LocalDate, endDate: LocalDate): Flow<List<DailyMacroAggregation>> {
        return userDatabase.diaryDao().observeDateNutritionSummaries(startDate.toEpochDay(), endDate.toEpochDay()).map { list ->
            list.map {
                DailyMacroAggregation(
                    date = LocalDate.ofEpochDay(it.dateEpochDay),
                    calories = it.totalCal,
                    proteinGrams = it.totalProtein,
                    carbsGrams = it.totalCarbs,
                    fatGrams = it.totalFat
                )
            }
        }
    }

    override suspend fun getNutritionAggregations(startDate: LocalDate, endDate: LocalDate): List<DailyMacroAggregation> {
        return observeNutritionAggregations(startDate, endDate).first()
    }

    private fun DiaryEntryEntity.toDomain(): DiaryEntry {
        val unit = ServingUnit.fromString(servingDescription)
        val serving = Serving(
            description = servingDescription,
            unit = unit,
            quantity = userQuantity,
            gramWeight = gramWeight
        )

        val nutrition = Nutrition(
            calories = loggedCalories,
            proteinGrams = loggedProtein,
            carbsGrams = loggedCarbs,
            fatGrams = loggedFat
        )

        return DiaryEntry(
            id = id,
            uuid = uuid,
            date = LocalDate.ofEpochDay(dateEpochDay),
            mealType = MealType.fromString(mealType),
            food = Food(
                id = foodId,
                uuid = uuid,
                name = foodName,
                nutrition = nutrition
            ),
            serving = serving,
            quantity = userQuantity,
            calculatedNutrition = nutrition,
            loggedAt = Instant.ofEpochMilli(createdAt)
        )
    }
}
