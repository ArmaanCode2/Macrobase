package com.macrobase.app

import com.macrobase.app.core.config.CalendarPerformanceConfig
import com.macrobase.app.domain.model.DiaryEntry
import com.macrobase.app.domain.model.Food
import com.macrobase.app.domain.model.Meal
import com.macrobase.app.domain.model.MealConfiguration
import com.macrobase.app.domain.model.MealType
import com.macrobase.app.domain.model.Nutrition
import com.macrobase.app.domain.model.Serving
import com.macrobase.app.domain.model.ServingUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.util.Locale

class DashboardAndStatisticsFixUnitTests {

    private fun createDummyEntry(name: String, quantity: Double, servingDesc: String, calories: Double = 100.0): DiaryEntry {
        return DiaryEntry(
            id = 1L,
            uuid = "test-uuid",
            date = LocalDate.now(),
            mealType = MealType.BREAKFAST,
            food = Food(id = 1L, uuid = "test-uuid", name = name, nutrition = Nutrition.ZERO),
            serving = Serving(description = servingDesc, unit = ServingUnit.SERVING, gramWeight = 100.0),
            quantity = quantity,
            calculatedNutrition = Nutrition.ZERO.copy(calories = calories)
        )
    }

    private fun formatSubtitle(entry: DiaryEntry): String {
        val qty = entry.quantity
        val qtyStr = if (qty % 1.0 == 0.0) qty.toInt().toString() else String.format(Locale.US, "%.1f", qty)
        val desc = entry.serving.description.trim()

        val regex = Regex("""^(\d+(?:\.\d+)?)\s*(.*)$""")
        val match = regex.find(desc)
        return if (match != null) {
            val baseQty = match.groupValues[1].toDoubleOrNull() ?: 1.0
            val unit = match.groupValues[2].trim()
            if (baseQty == 1.0) {
                if (unit.isNotEmpty()) "$qtyStr $unit" else qtyStr
            } else {
                val total = baseQty * qty
                val totalStr = if (total % 1.0 == 0.0) total.toInt().toString() else String.format(Locale.US, "%.1f", total)
                if (unit.isNotEmpty()) "$totalStr $unit" else totalStr
            }
        } else {
            if (desc.isNotEmpty()) "$qtyStr $desc" else qtyStr
        }
    }

    @Test
    fun mealOrder_testA_coreMealsOnlyWhenSnacksEmpty() {
        val meals = listOf(
            Meal(MealType.BREAKFAST, listOf(createDummyEntry("Bread", 2.0, "Slice"))),
            Meal(MealType.LUNCH, listOf(createDummyEntry("Paneer", 100.0, "g"))),
            Meal(MealType.DINNER, listOf(createDummyEntry("Milk", 250.0, "ml"))),
            Meal(MealType.AM_SNACK, emptyList()),
            Meal(MealType.PM_SNACK, emptyList()),
            Meal(MealType.SNACK, emptyList())
        )

        val visible = MealConfiguration.getVisibleMeals(meals)
        assertEquals(3, visible.size)
        assertEquals(MealType.BREAKFAST, visible[0].type)
        assertEquals(MealType.LUNCH, visible[1].type)
        assertEquals(MealType.DINNER, visible[2].type)
    }

    @Test
    fun mealOrder_testB_amAndPmSnackPopulated_lateSnackEmpty() {
        val meals = listOf(
            Meal(MealType.BREAKFAST, listOf(createDummyEntry("Bread", 4.0, "Slice"))),
            Meal(MealType.AM_SNACK, listOf(createDummyEntry("Banana", 1.0, "Medium"))),
            Meal(MealType.LUNCH, listOf(createDummyEntry("Paneer", 30.0, "g"))),
            Meal(MealType.PM_SNACK, listOf(createDummyEntry("Milk", 400.0, "g"))),
            Meal(MealType.DINNER, listOf(createDummyEntry("Oats", 50.0, "g"))),
            Meal(MealType.SNACK, emptyList())
        )

        val visible = MealConfiguration.getVisibleMeals(meals)
        assertEquals(5, visible.size)
        assertEquals(MealType.BREAKFAST, visible[0].type)
        assertEquals(MealType.AM_SNACK, visible[1].type)
        assertEquals(MealType.LUNCH, visible[2].type)
        assertEquals(MealType.PM_SNACK, visible[3].type)
        assertEquals(MealType.DINNER, visible[4].type)
    }

    @Test
    fun mealOrder_testC_lateSnackPopulated_amAndPmSnackEmpty() {
        val meals = listOf(
            Meal(MealType.BREAKFAST, listOf(createDummyEntry("Bread", 2.0, "Slice"))),
            Meal(MealType.LUNCH, listOf(createDummyEntry("Paneer", 30.0, "g"))),
            Meal(MealType.DINNER, listOf(createDummyEntry("Milk", 200.0, "g"))),
            Meal(MealType.SNACK, listOf(createDummyEntry("Yogurt", 100.0, "g"))),
            Meal(MealType.AM_SNACK, emptyList()),
            Meal(MealType.PM_SNACK, emptyList())
        )

        val visible = MealConfiguration.getVisibleMeals(meals)
        assertEquals(4, visible.size)
        assertEquals(MealType.BREAKFAST, visible[0].type)
        assertEquals(MealType.LUNCH, visible[1].type)
        assertEquals(MealType.DINNER, visible[2].type)
        assertEquals(MealType.SNACK, visible[3].type)
    }

    @Test
    fun mealOrder_testD_onlyPmSnackPopulated() {
        val meals = listOf(
            Meal(MealType.BREAKFAST, emptyList()),
            Meal(MealType.AM_SNACK, emptyList()),
            Meal(MealType.LUNCH, emptyList()),
            Meal(MealType.PM_SNACK, listOf(createDummyEntry("Apple", 1.0, "fruit"))),
            Meal(MealType.DINNER, emptyList()),
            Meal(MealType.SNACK, emptyList())
        )

        val visible = MealConfiguration.getVisibleMeals(meals)
        assertEquals(4, visible.size)
        assertEquals(MealType.BREAKFAST, visible[0].type)
        assertEquals(MealType.LUNCH, visible[1].type)
        assertEquals(MealType.PM_SNACK, visible[2].type)
        assertEquals(MealType.DINNER, visible[3].type)
    }

    @Test
    fun mealOrder_allSnacksPopulated_preservesStrictDisplayOrder() {
        val meals = listOf(
            Meal(MealType.BREAKFAST, listOf(createDummyEntry("Egg", 2.0, "Large"))),
            Meal(MealType.AM_SNACK, listOf(createDummyEntry("Almonds", 10.0, "pieces"))),
            Meal(MealType.LUNCH, listOf(createDummyEntry("Rice", 150.0, "g"))),
            Meal(MealType.PM_SNACK, listOf(createDummyEntry("Tea", 1.0, "Cup"))),
            Meal(MealType.DINNER, listOf(createDummyEntry("Chicken", 200.0, "g"))),
            Meal(MealType.SNACK, listOf(createDummyEntry("Casein", 1.0, "Scoop")))
        )

        val visible = MealConfiguration.getVisibleMeals(meals)
        assertEquals(6, visible.size)
        assertEquals(MealType.BREAKFAST, visible[0].type)
        assertEquals(MealType.AM_SNACK, visible[1].type)
        assertEquals(MealType.LUNCH, visible[2].type)
        assertEquals(MealType.PM_SNACK, visible[3].type)
        assertEquals(MealType.DINNER, visible[4].type)
        assertEquals(MealType.SNACK, visible[5].type)
    }

    @Test
    fun mealOrder_zeroCalorieFood_isConsideredNonEmpty() {
        val meals = listOf(
            Meal(MealType.AM_SNACK, listOf(createDummyEntry("Diet Soda", 1.0, "Can", calories = 0.0)))
        )

        val visible = MealConfiguration.getVisibleMeals(meals)
        assertTrue(visible.any { it.type == MealType.AM_SNACK })
    }

    @Test
    fun subtitleFormatting_rendersExactSnapshotRepresentation() {
        // 1. Brown Bread: 4 Slice
        val bread = createDummyEntry("Brown Bread (atta)", 4.0, "Slice")
        assertEquals("4 Slice", formatSubtitle(bread))

        // 2. Milk: 400 g (serving description "100 g" with quantity 4.0 -> "400 g")
        val milk = createDummyEntry("Milk", 4.0, "100 g")
        assertEquals("400 g", formatSubtitle(milk))

        // 3. Paneer: 30 g (serving description "g" with quantity 30.0 -> "30 g")
        val paneer = createDummyEntry("Paneer", 30.0, "g")
        assertEquals("30 g", formatSubtitle(paneer))

        // 4. Chapati: 2 Serving
        val chapati = createDummyEntry("Chapati", 2.0, "Serving")
        assertEquals("2 Serving", formatSubtitle(chapati))

        // 5. Banana: 80 g
        val banana = createDummyEntry("Banana", 80.0, "g")
        assertEquals("80 g", formatSubtitle(banana))

        // 6. Peanut Butter: 20 g
        val pb = createDummyEntry("Peanut Butter", 20.0, "g")
        assertEquals("20 g", formatSubtitle(pb))
    }

    @Test
    fun calendarPerformanceConfig_correctlyCategorizes() {
        val goal = 2000.0

        // 0 cal -> EMPTY_MISSED
        assertEquals(
            CalendarPerformanceConfig.PerformanceCategory.EMPTY_MISSED,
            CalendarPerformanceConfig.evaluatePerformance(0.0, goal)
        )

        // 1600 cal (80%) -> UNDER_BUDGET (<= 85%)
        assertEquals(
            CalendarPerformanceConfig.PerformanceCategory.UNDER_BUDGET,
            CalendarPerformanceConfig.evaluatePerformance(1600.0, goal)
        )

        // 2000 cal (100%) -> OPTIMAL_TARGET (85% .. 105%)
        assertEquals(
            CalendarPerformanceConfig.PerformanceCategory.OPTIMAL_TARGET,
            CalendarPerformanceConfig.evaluatePerformance(2000.0, goal)
        )

        // 2300 cal (115%) -> MODERATE_OVER (105% .. 120%)
        assertEquals(
            CalendarPerformanceConfig.PerformanceCategory.MODERATE_OVER,
            CalendarPerformanceConfig.evaluatePerformance(2300.0, goal)
        )

        // 2600 cal (130%) -> HIGH_OVER_TARGET (> 120%)
        assertEquals(
            CalendarPerformanceConfig.PerformanceCategory.HIGH_OVER_TARGET,
            CalendarPerformanceConfig.evaluatePerformance(2600.0, goal)
        )
    }
}
