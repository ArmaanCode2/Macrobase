package com.macrobase.app

import com.macrobase.app.core.config.DatabaseConfig
import com.macrobase.app.core.di.appModules
import com.macrobase.app.data.database.UserDatabase
import com.macrobase.app.domain.model.Food
import com.macrobase.app.domain.model.FoodSource
import com.macrobase.app.domain.model.MealType
import com.macrobase.app.domain.model.Nutrition
import com.macrobase.app.domain.model.Serving
import com.macrobase.app.domain.usecase.GetGoalsUseCase
import com.macrobase.app.feature.basket.basketLogLabel
import com.macrobase.app.feature.basket.basketWhenHeader
import com.macrobase.app.feature.basket.basketWhenLabel
import com.macrobase.app.feature.detail.FoodDetailViewModel
import com.macrobase.app.feature.detail.foodSourceLabel
import com.macrobase.app.feature.detail.gramsTotalLabel
import com.macrobase.app.feature.detail.percentOfDailyTarget
import com.macrobase.app.feature.detail.servingGrams
import com.macrobase.app.feature.detail.steppedQuantity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.koinApplication
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.time.LocalDate

/** The redesigned logging flow (design phase 5): Food Detail's amount, target and labels, and the basket's. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DesignLoggingTests {

    private val today = LocalDate.of(2026, 10, 17)

    @Test
    fun theStepperMovesByHalvesAndNeverBelowZero() {
        assertEquals(1.5, steppedQuantity(1.0, 0.5), 0.0)
        assertEquals(0.5, steppedQuantity(1.0, -0.5), 0.0)
        assertEquals(0.0, steppedQuantity(0.25, -0.5), 0.0)
        assertEquals(0.75, steppedQuantity(0.25, 0.5), 0.0)
        // No floating-point dust such as 0.30000000000000004
        assertEquals(1.3, steppedQuantity(0.8, 0.5), 0.0)
    }

    @Test
    fun theGramTotalFollowsTheServing() {
        val bowl = Serving(description = "1 bowl", quantity = 1.0, gramWeight = 150.0)
        assertEquals(225.0, servingGrams(bowl, 1.5)!!, 0.0001)
        assertEquals(50.0, servingGrams(Serving(description = "100 g", quantity = 100.0, gramWeight = 100.0), 50.0)!!, 0.0001)
        assertNull(servingGrams(Serving(description = "1 piece", gramWeight = 0.0), 1.0))
        assertNull(servingGrams(null, 1.0))
        assertEquals("150 g total", gramsTotalLabel(150.0))
        assertEquals("37.5 g total", gramsTotalLabel(37.5))
        assertEquals("33.3 g total", gramsTotalLabel(33.333))
    }

    @Test
    fun theShareOfTheDailyTargetIsRoundedNotTruncated() {
        assertEquals(14, percentOfDailyTarget(272.0, 2000.0))
        assertEquals(10, percentOfDailyTarget(199.0, 2000.0))
        assertNull(percentOfDailyTarget(272.0, 0.0))
    }

    @Test
    fun labelsReadInSentenceCase() {
        val nutrition = Nutrition(calories = 100.0, proteinGrams = 1.0, carbsGrams = 1.0, fatGrams = 1.0)
        val serving = listOf(Serving(description = "1 bowl", gramWeight = 150.0, isDefault = true))
        assertEquals("Built-in", foodSourceLabel(Food(id = 1L, uuid = "a", source = FoodSource.BUILT_IN, name = "Poha", nutrition = nutrition, servings = serving)))
        assertEquals("Custom", foodSourceLabel(Food(id = 100_000_001L, uuid = "b", source = FoodSource.CUSTOM_USER, name = "Shake", nutrition = nutrition, servings = serving, isUserOwned = true)))
        assertEquals("Recipe", foodSourceLabel(Food(id = 200_000_001L, uuid = "c", source = FoodSource.RECIPE, name = "Dal", nutrition = nutrition, servings = serving)))

        assertEquals("Log 1 food", basketLogLabel(1))
        assertEquals("Log 3 foods", basketLogLabel(3))
        assertEquals("Log to Breakfast, Today", basketWhenHeader(today, MealType.BREAKFAST, today))
        assertEquals("Yesterday, mixed meals", basketWhenHeader(today.minusDays(1), null, today))
        assertEquals("Mixed days, Late snack", basketWhenHeader(null, MealType.SNACK, today))
        assertEquals("Mixed (see each item)", basketWhenHeader(null, null, today))
        // The stored-name label the tests already pin stays as it was
        assertEquals("Today, BREAKFAST", basketWhenLabel(today, MealType.BREAKFAST, today))
    }

    // Without it "% of your daily target" would never show on the phone
    @Test
    fun theAppBuildsFoodDetailWithTheGoals() {
        val app = RuntimeEnvironment.getApplication()
        val catalog = listOf(File("src/main/assets/databases/built_in_foods.db"), File("app/src/main/assets/databases/built_in_foods.db"))
            .first { it.isFile }
        val installed = app.getDatabasePath(DatabaseConfig.BUILT_IN_FOODS_DB_NAME)
        installed.parentFile?.mkdirs()
        catalog.copyTo(installed, overwrite = true)
        val koin = koinApplication {
            androidContext(app)
            modules(appModules)
        }.koin
        try {
            val detail = koin.get<FoodDetailViewModel>()
            val goals = FoodDetailViewModel::class.java.getDeclaredField("getGoalsUseCase").apply { isAccessible = true }.get(detail)
            assertTrue(goals is GetGoalsUseCase)
        } finally {
            koin.get<UserDatabase>().close()
            koin.close()
        }
    }
}
