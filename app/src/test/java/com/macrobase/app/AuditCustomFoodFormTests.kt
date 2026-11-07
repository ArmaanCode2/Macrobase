package com.macrobase.app

import com.macrobase.app.domain.model.CustomFood
import com.macrobase.app.domain.model.Nutrition
import com.macrobase.app.domain.model.ServingUnit
import com.macrobase.app.domain.model.scanner.NutritionBasis
import com.macrobase.app.domain.model.scanner.NutritionLabelDraft
import com.macrobase.app.domain.model.scanner.ParsedNutrientValue
import com.macrobase.app.feature.customfoods.CustomFoodForm
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * BUG-036: a label of 412.5 kcal per 100 g, applied per 1 g, wrote "4.125" into the form, and the
 * form refused it as a thousands separator. A stored food with that value could not be saved again.
 */
class AppWrittenNumberTests {

    private fun perGram(kcal: Double) = NutritionLabelDraft(
        foodName = null,
        detectedBasis = NutritionBasis.PER_1_G,
        servingSize = 1.0,
        servingUnit = ServingUnit.GRAMS,
        calories = ParsedNutrientValue(kcal, "kcal")
    )

    @Test
    fun aNumberTheScanWroteIsReadAsWritten() {
        val form = CustomFoodForm().withDraft(perGram(4.125))
        val read = form.read(CustomFoodForm::calories, largeAmount = true)

        assertEquals("4.125", form.calories)
        assertNull(read.error)
        assertEquals(4.125, read.value!!, 0.0)
    }

    @Test
    fun aStoredNumberIsReadAsWritten() {
        val ghee = CustomFood(
            id = 7L,
            uuid = "ghee",
            name = "Ghee",
            servingSize = 1.0,
            servingUnit = ServingUnit.GRAMS,
            nutritionPerServing = Nutrition(calories = 8.125, proteinGrams = 0.0, carbsGrams = 0.0, fatGrams = 0.9, sodiumMg = 1.125)
        )
        val form = CustomFoodForm.from(ghee)

        assertNull(form.read(CustomFoodForm::calories, largeAmount = true).error)
        assertNull(form.read(CustomFoodForm::sodium, largeAmount = true).error)
    }

    // A leading 0 is never a thousands group: 0.125 kg is an eighth of a kilo
    @Test
    fun aTypedAmountBelowOneIsNeverAThousandsSeparator() {
        assertEquals(0.125, CustomFoodForm(servingSize = "0.125").read(CustomFoodForm::servingSize, largeAmount = true).value!!, 0.0)
    }

    @Test
    fun aTypedThousandsSeparatorIsStillRefused() {
        assertNotNull(CustomFoodForm(calories = "2.000").read(CustomFoodForm::calories, largeAmount = true).error)
        assertNotNull(CustomFoodForm(calories = "2,000").read(CustomFoodForm::calories, largeAmount = true).error)
        // Once the user changes what the scan wrote, it is their typing
        val retyped = CustomFoodForm().withDraft(perGram(4.125)).copy(calories = "1.250")
        assertNotNull(retyped.read(CustomFoodForm::calories, largeAmount = true).error)
    }
}

/** BUG-040: a scanned label's potassium, calcium and iron were parsed, then dropped from the food. */
class ScannedMineralTests {

    private val label = NutritionLabelDraft(
        foodName = null,
        detectedBasis = NutritionBasis.PER_100_G,
        calories = ParsedNutrientValue(120.0, "kcal"),
        potassium = ParsedNutrientValue(300.0, "mg"),
        calcium = ParsedNutrientValue(120.0, "mg"),
        iron = ParsedNutrientValue(2.5, "mg")
    )

    private fun source(path: String): String =
        listOf(File("src/main/java/com/macrobase/app/$path"), File("app/src/main/java/com/macrobase/app/$path"))
            .first { it.isFile }.readText()

    @Test
    fun scannedMineralsFillTheirFields() {
        val form = CustomFoodForm().withDraft(label)

        assertEquals("300", form.potassium)
        assertEquals("120", form.calcium)
        assertEquals("2.5", form.iron)
    }

    @Test
    fun aStoredFoodsMineralsShowInTheForm() {
        val food = CustomFood(
            id = 9L,
            uuid = "bar",
            name = "Bar",
            servingSize = 1.0,
            servingUnit = ServingUnit.SERVING,
            nutritionPerServing = Nutrition(calories = 200.0, proteinGrams = 10.0, carbsGrams = 20.0, fatGrams = 8.0, potassiumMg = 300.0, ironMg = 2.5)
        )
        val form = CustomFoodForm.from(food)

        assertEquals("300", form.potassium)
        assertEquals("not stated stays blank", "", form.calcium)
        assertEquals("2.5", form.iron)
    }

    @Test
    fun theScreenSavesTheMineralsInTheForm() {
        val screen = source("feature/customfoods/CustomFoodsScreen.kt")

        assertTrue(screen.contains("calcium = calciumInput.value"))
        assertFalse(screen.contains("existingFood?.nutritionPerServing?.calciumMg"))
    }
}
