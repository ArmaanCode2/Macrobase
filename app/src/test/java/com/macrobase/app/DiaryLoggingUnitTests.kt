package com.macrobase.app

import com.macrobase.app.domain.model.DailyNutritionSummary
import com.macrobase.app.domain.model.DiaryEntry
import com.macrobase.app.domain.model.Food
import com.macrobase.app.domain.model.Goal
import com.macrobase.app.domain.model.Meal
import com.macrobase.app.domain.model.MealType
import com.macrobase.app.domain.model.Nutrition
import com.macrobase.app.domain.model.Serving
import com.macrobase.app.domain.model.ServingUnit
import com.macrobase.app.domain.repository.DiaryRepository
import com.macrobase.app.domain.repository.GoalsRepository
import com.macrobase.app.domain.usecase.DeleteDiaryEntryUseCase
import com.macrobase.app.domain.usecase.GetDailyDiaryUseCase
import com.macrobase.app.domain.usecase.LogFoodUseCase
import com.macrobase.app.domain.usecase.UpdateDiaryEntryUseCase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * Unit tests for Phase 6 Food Diary, Meal Logging, and Daily Dashboard aggregation.
 */
class DiaryLoggingUnitTests {

    private lateinit var fakeDiaryRepository: FakeDiaryRepository
    private lateinit var getDailyDiaryUseCase: GetDailyDiaryUseCase
    private lateinit var logFoodUseCase: LogFoodUseCase
    private lateinit var updateDiaryEntryUseCase: UpdateDiaryEntryUseCase
    private lateinit var deleteDiaryEntryUseCase: DeleteDiaryEntryUseCase

    private val eggFood = Food(
        id = 94,
        uuid = "egg-uuid",
        name = "Eggs, Grade A, Large, egg whole",
        nutrition = Nutrition(calories = 148.0, proteinGrams = 12.4, carbsGrams = 0.96, fatGrams = 9.96)
    )

    private val oatsFood = Food(
        id = 100,
        uuid = "oats-uuid",
        name = "Rolled Oats",
        nutrition = Nutrition(calories = 389.0, proteinGrams = 16.9, carbsGrams = 66.3, fatGrams = 6.9)
    )

    private val chickenFood = Food(
        id = 200,
        uuid = "chicken-uuid",
        name = "Chicken Breast, cooked",
        nutrition = Nutrition(calories = 165.0, proteinGrams = 31.0, carbsGrams = 0.0, fatGrams = 3.6)
    )

    @Before
    fun setUp() {
        fakeDiaryRepository = FakeDiaryRepository()
        getDailyDiaryUseCase = GetDailyDiaryUseCase(fakeDiaryRepository)
        logFoodUseCase = LogFoodUseCase(fakeDiaryRepository)
        updateDiaryEntryUseCase = UpdateDiaryEntryUseCase(fakeDiaryRepository)
        deleteDiaryEntryUseCase = DeleteDiaryEntryUseCase(fakeDiaryRepository)
    }

    @Test
    fun logFood_toAllFiveMealTypes_aggregatesCorrectMealAndDailyTotals() = runBlocking {
        val testDate = LocalDate.of(2026, 8, 19)

        // 1. Log Oats to BREAKFAST (100g -> 389 kcal)
        val entry1 = DiaryEntry(
            id = 1,
            uuid = UUID.randomUUID().toString(),
            date = testDate,
            mealType = MealType.BREAKFAST,
            food = oatsFood,
            serving = Serving(description = "100g", gramWeight = 100.0),
            quantity = 1.0,
            calculatedNutrition = oatsFood.nutrition,
            loggedAt = Instant.now()
        )
        logFoodUseCase(entry1)

        // 2. Log Chicken to LUNCH (100g -> 165 kcal)
        val entry2 = DiaryEntry(
            id = 2,
            uuid = UUID.randomUUID().toString(),
            date = testDate,
            mealType = MealType.LUNCH,
            food = chickenFood,
            serving = Serving(description = "100g", gramWeight = 100.0),
            quantity = 1.0,
            calculatedNutrition = chickenFood.nutrition,
            loggedAt = Instant.now()
        )
        logFoodUseCase(entry2)

        // 3. Log Egg to PM_SNACK (50g -> 74 kcal)
        val eggHalfNutrition = eggFood.nutrition.scale(0.5)
        val entry3 = DiaryEntry(
            id = 3,
            uuid = UUID.randomUUID().toString(),
            date = testDate,
            mealType = MealType.PM_SNACK,
            food = eggFood,
            serving = Serving(description = "1 large egg (50g)", gramWeight = 50.0),
            quantity = 1.0,
            calculatedNutrition = eggHalfNutrition,
            loggedAt = Instant.now()
        )
        logFoodUseCase(entry3)

        // 4. Log Chicken to DINNER (200g -> 330 kcal)
        val chickenDoubleNutrition = chickenFood.nutrition.scale(2.0)
        val entry4 = DiaryEntry(
            id = 4,
            uuid = UUID.randomUUID().toString(),
            date = testDate,
            mealType = MealType.DINNER,
            food = chickenFood,
            serving = Serving(description = "100g", gramWeight = 100.0),
            quantity = 2.0,
            calculatedNutrition = chickenDoubleNutrition,
            loggedAt = Instant.now()
        )
        logFoodUseCase(entry4)

        // 5. Log Oats to SNACK (50g -> 194.5 kcal)
        val oatsHalfNutrition = oatsFood.nutrition.scale(0.5)
        val entry5 = DiaryEntry(
            id = 5,
            uuid = UUID.randomUUID().toString(),
            date = testDate,
            mealType = MealType.SNACK,
            food = oatsFood,
            serving = Serving(description = "50g", gramWeight = 50.0),
            quantity = 1.0,
            calculatedNutrition = oatsHalfNutrition,
            loggedAt = Instant.now()
        )
        logFoodUseCase(entry5)

        // Retrieve aggregated summary
        val summary = getDailyDiaryUseCase(testDate).first()

        assertEquals(MealType.entries.size, summary.meals.size)
        val breakfast = summary.meals.first { it.type == MealType.BREAKFAST }
        val lunch = summary.meals.first { it.type == MealType.LUNCH }
        val pmSnack = summary.meals.first { it.type == MealType.PM_SNACK }
        val dinner = summary.meals.first { it.type == MealType.DINNER }
        val snack = summary.meals.first { it.type == MealType.SNACK }

        assertEquals(389.0, breakfast.totalCalories, 0.1)
        assertEquals(165.0, lunch.totalCalories, 0.1)
        assertEquals(74.0, pmSnack.totalCalories, 0.1)
        assertEquals(330.0, dinner.totalCalories, 0.1)
        assertEquals(194.5, snack.totalCalories, 0.1)

        // Total intake = 389 + 165 + 74 + 330 + 194.5 = 1152.5 kcal
        assertEquals(1152.5, summary.totalCaloriesIntake, 0.1)

        // Total protein = 16.9 + 31.0 + 6.2 + 62.0 + 8.45 = 124.55g
        assertEquals(124.55, summary.totalProteinGrams, 0.1)

        // Total carbs = 66.3 + 0 + 0.48 + 0 + 33.15 = 99.93g
        assertEquals(99.93, summary.totalCarbsGrams, 0.1)

        // Total fat = 6.9 + 3.6 + 4.98 + 7.2 + 3.45 = 26.13g
        assertEquals(26.13, summary.totalFatGrams, 0.1)
    }

    @Test
    fun deleteDiaryEntry_recalculatesTotalsImmediately() = runBlocking {
        val testDate = LocalDate.of(2026, 8, 19)

        val entry = DiaryEntry(
            id = 42,
            uuid = "test-delete-uuid",
            date = testDate,
            mealType = MealType.BREAKFAST,
            food = oatsFood,
            serving = Serving(description = "100g", gramWeight = 100.0),
            quantity = 1.0,
            calculatedNutrition = oatsFood.nutrition,
            loggedAt = Instant.now()
        )
        logFoodUseCase(entry)

        val beforeSummary = getDailyDiaryUseCase(testDate).first()
        assertEquals(389.0, beforeSummary.totalCaloriesIntake, 0.1)
        assertEquals(1, beforeSummary.meals.first { it.type == MealType.BREAKFAST }.entries.size)

        // Delete the entry
        deleteDiaryEntryUseCase(42L)

        val afterSummary = getDailyDiaryUseCase(testDate).first()
        assertEquals(0.0, afterSummary.totalCaloriesIntake, 0.1)
        assertEquals(0, afterSummary.meals.first { it.type == MealType.BREAKFAST }.entries.size)
    }

    @Test
    fun updateDiaryEntry_modifiesPortionAndTotalsWithoutDuplicates() = runBlocking {
        val testDate = LocalDate.of(2026, 8, 19)

        val initialEntry = DiaryEntry(
            id = 55,
            uuid = "entry-55",
            date = testDate,
            mealType = MealType.LUNCH,
            food = chickenFood,
            serving = Serving(description = "100g", gramWeight = 100.0),
            quantity = 1.0,
            calculatedNutrition = chickenFood.nutrition,
            loggedAt = Instant.now()
        )
        logFoodUseCase(initialEntry)

        assertEquals(165.0, getDailyDiaryUseCase(testDate).first().totalCaloriesIntake, 0.1)

        // Update to 2.0 servings (330 kcal)
        val updatedEntry = initialEntry.copy(
            quantity = 2.0,
            calculatedNutrition = chickenFood.nutrition.scale(2.0)
        )
        updateDiaryEntryUseCase(updatedEntry)

        val summary = getDailyDiaryUseCase(testDate).first()
        assertEquals(330.0, summary.totalCaloriesIntake, 0.1)
        assertEquals(1, summary.meals.first { it.type == MealType.LUNCH }.entries.size)
    }

    @Test
    fun multiDayHistoricalLogging_isolatesDatesCompletely() = runBlocking {
        val dayA = LocalDate.of(2026, 8, 18)
        val dayB = LocalDate.of(2026, 8, 19)

        // Log on Day A (Oats: 389 kcal)
        logFoodUseCase(
            DiaryEntry(
                id = 101,
                uuid = "dayA-uuid",
                date = dayA,
                mealType = MealType.BREAKFAST,
                food = oatsFood,
                serving = Serving(description = "100g", gramWeight = 100.0),
                quantity = 1.0,
                calculatedNutrition = oatsFood.nutrition,
                loggedAt = Instant.now()
            )
        )

        // Log on Day B (Chicken: 165 kcal)
        logFoodUseCase(
            DiaryEntry(
                id = 102,
                uuid = "dayB-uuid",
                date = dayB,
                mealType = MealType.LUNCH,
                food = chickenFood,
                serving = Serving(description = "100g", gramWeight = 100.0),
                quantity = 1.0,
                calculatedNutrition = chickenFood.nutrition,
                loggedAt = Instant.now()
            )
        )

        // Verify Day A only contains Oats (389 kcal)
        val summaryA = getDailyDiaryUseCase(dayA).first()
        assertEquals(389.0, summaryA.totalCaloriesIntake, 0.1)
        assertEquals(1, summaryA.meals.first { it.type == MealType.BREAKFAST }.entries.size)
        assertEquals(0, summaryA.meals.first { it.type == MealType.LUNCH }.entries.size)

        // Verify Day B only contains Chicken (165 kcal)
        val summaryB = getDailyDiaryUseCase(dayB).first()
        assertEquals(165.0, summaryB.totalCaloriesIntake, 0.1)
        assertEquals(0, summaryB.meals.first { it.type == MealType.BREAKFAST }.entries.size)
        assertEquals(1, summaryB.meals.first { it.type == MealType.LUNCH }.entries.size)
    }

    @Test
    fun calorieRemainingAndOverageCalculations_workAccurately() {
        val goal = 2000.0

        // 1. Under budget: 1700 intake -> 300 remaining
        val summaryUnder = DailyNutritionSummary(
            date = LocalDate.now(),
            totalCaloriesIntake = 1700.0,
            calorieGoal = goal
        )
        assertFalse(summaryUnder.isOverBudget)
        assertEquals(300.0, summaryUnder.calorieBalance, 0.001)
        assertEquals(0.0, summaryUnder.overBudgetAmount, 0.001)

        // 2. Over budget: 2168 intake -> Over 168
        val summaryOver = DailyNutritionSummary(
            date = LocalDate.now(),
            totalCaloriesIntake = 2168.0,
            calorieGoal = goal
        )
        assertTrue(summaryOver.isOverBudget)
        assertEquals(-168.0, summaryOver.calorieBalance, 0.001)
        assertEquals(168.0, summaryOver.overBudgetAmount, 0.001)
    }

    @Test
    fun emptyDay_rendersZeroIntakeAndEmptyMealListsSafely() = runBlocking {
        val emptyDate = LocalDate.of(2026, 1, 1)
        val summary = getDailyDiaryUseCase(emptyDate).first()

        assertEquals(0.0, summary.totalCaloriesIntake, 0.001)
        assertEquals(0.0, summary.totalProteinGrams, 0.001)
        assertEquals(0.0, summary.totalCarbsGrams, 0.001)
        assertEquals(0.0, summary.totalFatGrams, 0.001)
        assertEquals(MealType.entries.size, summary.meals.size)
        assertTrue("All meal entry lists must be empty", summary.meals.all { it.entries.isEmpty() })
    }
}

/**
 * In-memory Fake Diary Repository for deterministic domain unit testing.
 */
private class FakeDiaryRepository : DiaryRepository {
    private val entriesFlow = MutableStateFlow<Map<Long, DiaryEntry>>(emptyMap())
    private val goal = Goal(dailyCalorieGoal = 2000.0)

    override suspend fun getDiaryForDate(date: LocalDate): DailyNutritionSummary {
        return observeDiaryForDate(date).first()
    }

    override suspend fun getEntryById(entryId: Long): DiaryEntry? {
        return entriesFlow.value[entryId]
    }

    override fun observeDiaryForDate(date: LocalDate): Flow<DailyNutritionSummary> {
        return entriesFlow.asStateFlow().map { entriesMap ->
            val dateEntries = entriesMap.values.filter { it.date == date }
            val grouped = dateEntries.groupBy { it.mealType }

            val meals = MealType.entries.map { type ->
                Meal(type = type, entries = grouped[type] ?: emptyList())
            }

            DailyNutritionSummary(
                date = date,
                totalCaloriesIntake = dateEntries.sumOf { it.calculatedNutrition.calories },
                totalCaloriesBurned = 0.0,
                calorieGoal = goal.dailyCalorieGoal,
                totalProteinGrams = dateEntries.sumOf { it.calculatedNutrition.proteinGrams },
                totalCarbsGrams = dateEntries.sumOf { it.calculatedNutrition.carbsGrams },
                totalFatGrams = dateEntries.sumOf { it.calculatedNutrition.fatGrams },
                totalFiberGrams = dateEntries.sumOf { it.calculatedNutrition.fiberGrams ?: 0.0 },
                totalSugarGrams = dateEntries.sumOf { it.calculatedNutrition.sugarGrams ?: 0.0 },
                totalSodiumMg = dateEntries.sumOf { it.calculatedNutrition.sodiumMg ?: 0.0 },
                totalWaterMl = 0.0,
                waterGoalMl = 2500.0,
                meals = meals
            )
        }
    }

    override suspend fun addEntry(entry: DiaryEntry): Long {
        val id = if (entry.id > 0) entry.id else (entriesFlow.value.keys.maxOrNull() ?: 0L) + 1L
        val persisted = entry.copy(id = id)
        entriesFlow.update { it + (id to persisted) }
        return id
    }

    override suspend fun addEntries(entries: List<DiaryEntry>) {
        entries.forEach { addEntry(it) }
    }

    override suspend fun updateEntry(entry: DiaryEntry) {
        addEntry(entry)
    }

    override suspend fun deleteEntry(entryId: Long) {
        entriesFlow.update { it - entryId }
    }

    override suspend fun getMonthlyAdherence(year: Int, month: Int) = emptyList<com.macrobase.app.domain.model.CalendarDaySummary>()
    override fun observeMonthlyAdherence(year: Int, month: Int): Flow<List<com.macrobase.app.domain.model.CalendarDaySummary>> = kotlinx.coroutines.flow.flowOf(emptyList())

    override fun observeNutritionAggregations(startDate: LocalDate, endDate: LocalDate): Flow<List<com.macrobase.app.domain.repository.DailyMacroAggregation>> {
        return entriesFlow.asStateFlow().map { map ->
            val dateEntries = map.values.filter { !it.date.isBefore(startDate) && !it.date.isAfter(endDate) }
            dateEntries.groupBy { it.date }.map { (date, list) ->
                com.macrobase.app.domain.repository.DailyMacroAggregation(
                    date = date,
                    calories = list.sumOf { it.calculatedNutrition.calories },
                    proteinGrams = list.sumOf { it.calculatedNutrition.proteinGrams },
                    carbsGrams = list.sumOf { it.calculatedNutrition.carbsGrams },
                    fatGrams = list.sumOf { it.calculatedNutrition.fatGrams }
                )
            }.sortedBy { it.date }
        }
    }

    override suspend fun getNutritionAggregations(startDate: LocalDate, endDate: LocalDate): List<com.macrobase.app.domain.repository.DailyMacroAggregation> {
        return observeNutritionAggregations(startDate, endDate).first()
    }
}
