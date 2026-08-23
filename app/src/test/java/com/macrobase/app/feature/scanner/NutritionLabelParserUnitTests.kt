package com.macrobase.app.feature.scanner

import android.graphics.Rect
import com.macrobase.app.domain.model.ServingUnit
import com.macrobase.app.domain.model.scanner.ConfidenceLevel
import com.macrobase.app.domain.model.scanner.NutritionBasis
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class NutritionLabelParserUnitTests {

    private lateinit var parser: NutritionLabelParser

    @Before
    fun setUp() {
        parser = NutritionLabelParser()
    }

    @Test
    fun test1_StandardPer100gLabel() {
        val ocrText = """
            Nutrition Information
            Per 100 g
            Energy 500 kcal
            Protein 10 g
            Carbohydrate 60 g
            Total Sugars 15 g
            Fat 20 g
            Sodium 300 mg
        """.trimIndent()

        val draft = parser.parseText(ocrText)

        assertEquals(NutritionBasis.PER_100_G, draft.detectedBasis)
        println("DRAFT CALORIES: ${draft.calories}")
        assertEquals(500.0, draft.calories!!.value, 0.01)
        assertEquals("kcal", draft.calories!!.unit)
        assertEquals("kcal", draft.calories!!.unit)

        assertNotNull(draft.protein)
        assertEquals(10.0, draft.protein!!.value, 0.01)

        assertNotNull(draft.carbs)
        assertEquals(60.0, draft.carbs!!.value, 0.01)

        assertNotNull(draft.sugar)
        assertEquals(15.0, draft.sugar!!.value, 0.01)

        assertNotNull(draft.fat)
        assertEquals(20.0, draft.fat!!.value, 0.01)

        assertNotNull(draft.sodium)
        assertEquals(300.0, draft.sodium!!.value, 0.01)

        assertNull(draft.fiber)
        assertNull(draft.cholesterol)
    }

    @Test
    fun test2_PerServingWithNormalizationToPer100g() {
        val ocrText = """
            Serving Size 30 g
            Energy 150 kcal
            Protein 5 g
            Carbohydrate 20 g
            Fat 6 g
        """.trimIndent()

        val draft = parser.parseText(ocrText)

        // 30g serving normalized to 100g (100 / 30 = 3.3333x)
        assertEquals(NutritionBasis.PER_100_G, draft.detectedBasis)
        assertEquals(30.0, draft.servingGrams ?: 0.0, 0.01)

        assertNotNull(draft.calories)
        assertEquals(500.0, draft.calories!!.value, 0.1) // 150 * 100 / 30 = 500

        assertNotNull(draft.protein)
        assertEquals(16.67, draft.protein!!.value, 0.1) // 5 * 100 / 30 = 16.67

        assertNotNull(draft.carbs)
        assertEquals(66.67, draft.carbs!!.value, 0.1) // 20 * 100 / 30 = 66.67

        assertNotNull(draft.fat)
        assertEquals(20.0, draft.fat!!.value, 0.1) // 6 * 100 / 30 = 20.0
    }

    @Test
    fun test3_TwoColumnLabelPer100gAndPerServing() {
        val ocrText = """
            Nutritional Values Per 100g      Per Serving
            Energy             500 kcal      150 kcal
            Protein            10 g          3 g
            Carbohydrate       60 g          18 g
            Fat                20 g          6 g
            Sodium             500 mg        150 mg
        """.trimIndent()

        val draft = parser.parseText(ocrText)

        assertEquals(NutritionBasis.PER_100_G, draft.detectedBasis)
        assertNotNull(draft.calories)
        assertEquals(500.0, draft.calories!!.value, 0.01)

        assertNotNull(draft.protein)
        assertEquals(10.0, draft.protein!!.value, 0.01)

        assertNotNull(draft.carbs)
        assertEquals(60.0, draft.carbs!!.value, 0.01)

        assertNotNull(draft.fat)
        assertEquals(20.0, draft.fat!!.value, 0.01)

        assertNotNull(draft.sodium)
        assertEquals(500.0, draft.sodium!!.value, 0.01)
    }

    @Test
    fun test4_DualEnergyKjAndKcal() {
        val ocrText = """
            Nutritional Values
            Per 100g
            Energy 1045 kJ / 250 kcal
            Protein 8 g
            Carbohydrate 45 g
            Total Fat 4 g
        """.trimIndent()

        val draft = parser.parseText(ocrText)

        assertNotNull(draft.calories)
        assertEquals(250.0, draft.calories!!.value, 0.01)
    }

    @Test
    fun test5_OcrMisreadsNormalization() {
        val ocrText = """
            Energy 10O kcal
            Prote1n I.5 g
            Carbohydrat e 10.O g
            Sodlum 5O mg
            Cholestero1 1O mg
        """.trimIndent()

        val draft = parser.parseText(ocrText)

        assertNotNull(draft.calories)
        assertEquals(100.0, draft.calories!!.value, 0.01) // 10O -> 100

        assertNotNull(draft.protein)
        assertEquals(1.5, draft.protein!!.value, 0.01) // I.5 -> 1.5

        assertNotNull(draft.carbs)
        assertEquals(10.0, draft.carbs!!.value, 0.01) // 10.O -> 10.0

        assertNotNull(draft.sodium)
        assertEquals(50.0, draft.sodium!!.value, 0.01) // 5O -> 50

        assertNotNull(draft.cholesterol)
        assertEquals(10.0, draft.cholesterol!!.value, 0.01) // 1O -> 10
    }

    @Test
    fun test4b_KjOnlyEnergyConversion() {
        val ocrText = """
            Energy 1045 kJ
            Protein 8 g
            Carbohydrate 45 g
            Fat 4 g
        """.trimIndent()

        val draft = parser.parseText(ocrText)

        assertNotNull(draft.calories)
        // 1045 / 4.184 = 249.76 kcal -> ~249.8 kcal
        assertEquals(249.8, draft.calories!!.value, 0.2)
        assertTrue(draft.calories!!.isEstimated)
    }

    @Test
    fun test5_IndianPackagedFoodLabel() {
        val ocrText = """
            NUTRITION INFORMATION
            Approx. Values per 100g
            Energy 490 kcal
            Protein 7.0 g
            Total Carbohydrate 68.0 g
            Total Sugars 23.5 g
            Added Sugars 21.0 g
            Total Fat 21.0 g
            Saturated Fat 10.0 g
            Trans Fat 0 g
            Cholesterol 0 mg
            Sodium 240 mg
        """.trimIndent()

        val draft = parser.parseText(ocrText)

        assertEquals(NutritionBasis.PER_100_G, draft.detectedBasis)
        assertEquals(490.0, draft.calories?.value ?: 0.0, 0.01)
        assertEquals(7.0, draft.protein?.value ?: 0.0, 0.01)
        assertEquals(68.0, draft.carbs?.value ?: 0.0, 0.01)
        assertEquals(23.5, draft.sugar?.value ?: 0.0, 0.01)
        assertEquals(21.0, draft.addedSugar?.value ?: 0.0, 0.01)
        assertEquals(21.0, draft.fat?.value ?: 0.0, 0.01)
        assertEquals(10.0, draft.saturatedFat?.value ?: 0.0, 0.01)
        assertEquals(0.0, draft.transFat?.value ?: 0.0, 0.01)
        assertEquals(0.0, draft.cholesterol?.value ?: 0.0, 0.01)
        assertEquals(240.0, draft.sodium?.value ?: 0.0, 0.01)
    }

    @Test
    fun test6_UkEuropeanSaltToSodiumEstimation() {
        val ocrText = """
            Typical Values per 100g
            Energy 360 kcal
            Fat 5.0 g
            Saturates 1.2 g
            Carbohydrate 70.0 g
            Sugars 3.5 g
            Fibre 8.0 g
            Protein 12.0 g
            Salt 0.76 g
        """.trimIndent()

        val draft = parser.parseText(ocrText)

        assertEquals(360.0, draft.calories?.value ?: 0.0, 0.01)
        assertEquals(12.0, draft.protein?.value ?: 0.0, 0.01)
        assertEquals(70.0, draft.carbs?.value ?: 0.0, 0.01)
        assertEquals(5.0, draft.fat?.value ?: 0.0, 0.01)
        assertEquals(1.2, draft.saturatedFat?.value ?: 0.0, 0.01)
        assertEquals(8.0, draft.fiber?.value ?: 0.0, 0.01)
        assertEquals(3.5, draft.sugar?.value ?: 0.0, 0.01)

        assertNotNull(draft.salt)
        assertEquals(0.76, draft.salt!!.value, 0.01)

        // Estimated Sodium = 0.76 / 2.54 * 1000 ≈ 299.2 mg
        assertNotNull(draft.sodium)
        assertEquals(299.2, draft.sodium!!.value, 0.5)
        assertTrue(draft.sodium!!.isEstimated)
    }

    @Test
    fun test7_PartialLabelOnlySodiumDetected() {
        val ocrText = """
            Sodium 500 mg
            Ingredients list
            Batch #120349
        """.trimIndent()

        val draft = parser.parseText(ocrText)

        assertNull(draft.calories)
        assertNull(draft.protein)
        assertNull(draft.carbs)
        assertNull(draft.fat)
        assertNotNull(draft.sodium)
        assertEquals(500.0, draft.sodium!!.value, 0.01)
        assertEquals(1, draft.recognizedFieldCount)
        assertTrue(draft.hasNutrientData)
    }

    @Test
    fun test8_LessThanValuesAndOvermatchFix() {
        val ocrText = """
            Per 100g
            Energy 200 kcal
            Protein 5 g
            Total Carbohydrate <1 g
            Sugars <0.5 g
            Fat < 1 g
            Natural flavors 50 mg
            Select harvest 10 mg
        """.trimIndent()

        val draft = parser.parseText(ocrText)
        
        // Check less than logic (value / 2)
        assertEquals(0.5, draft.carbs?.value ?: 0.0, 0.01)
        assertTrue(draft.carbs?.isEstimated == true)
        
        assertEquals(0.25, draft.sugar?.value ?: 0.0, 0.01)
        assertTrue(draft.sugar?.isEstimated == true)
        
        assertEquals(0.5, draft.fat?.value ?: 0.0, 0.01)
        assertTrue(draft.fat?.isEstimated == true)

        // Check overmatch fix ("Natural" should not match Sodium 'na', "Select" should not match Salt 'sel')
        assertNull(draft.sodium)
        assertNull(draft.salt)
    }

    @Test
    fun test9_LabelWithExtraUnrelatedAndBilingualText() {
        val ocrText = """
            MANUFACTURED BY ABC FOODS PVT LTD
            LIC NO. 10012022000123
            POIDS NET / NET WT 200g
            VALEURS NUTRITIONNELLES / NUTRITIONAL VALUES
            Per 100g
            ENERGIE / ENERGY 420 kcal
            PROTEINES / PROTEIN 8.4 g
            GLUCIDES / CARBOHYDRATE 54.0 g
            LIPIDES / FAT 18.0 g
            SODIUM 310 mg
            KEEP IN COOL PLACE
        """.trimIndent()

        val draft = parser.parseText(ocrText)

        assertEquals(420.0, draft.calories?.value ?: 0.0, 0.01)
        assertEquals(8.4, draft.protein?.value ?: 0.0, 0.01)
        assertEquals(54.0, draft.carbs?.value ?: 0.0, 0.01)
        assertEquals(18.0, draft.fat?.value ?: 0.0, 0.01)
        assertEquals(310.0, draft.sodium?.value ?: 0.0, 0.01)
    }

    @Test
    fun test9_OcrSpacingAndCommaErrors() {
        val ocrText = """
            Per 100g
            Energy 245,5 kcal
            Protein 12,5 g
            Total Fat 3,8g
            Total Carbohydrate 40,2 g
            Sodium 120mg
        """.trimIndent()

        val draft = parser.parseText(ocrText)

        assertEquals(245.5, draft.calories?.value ?: 0.0, 0.01)
        assertEquals(12.5, draft.protein?.value ?: 0.0, 0.01)
        assertEquals(3.8, draft.fat?.value ?: 0.0, 0.01)
        assertEquals(40.2, draft.carbs?.value ?: 0.0, 0.01)
        assertEquals(120.0, draft.sodium?.value ?: 0.0, 0.01)
    }

    @Test
    fun test10_2DSpatialBlockClusteringReconstruction() {
        // Simulates ML Kit splitting label names (left block) and values (right block) into separate lines
        val lines = listOf(
            // Left Column (Label Names)
            OcrLine("Energy", spatialBounds = NutritionLabelParser.SpatialBox(50, 100, 200, 130)),
            OcrLine("Protein", spatialBounds = NutritionLabelParser.SpatialBox(50, 140, 200, 170)),
            OcrLine("Total Fat", spatialBounds = NutritionLabelParser.SpatialBox(50, 180, 200, 210)),
            OcrLine("Total Carbohydrate", spatialBounds = NutritionLabelParser.SpatialBox(50, 220, 200, 250)),
            OcrLine("Sodium", spatialBounds = NutritionLabelParser.SpatialBox(50, 260, 200, 290)),

            // Right Column (Nutrient Values)
            OcrLine("500 kcal", spatialBounds = NutritionLabelParser.SpatialBox(300, 100, 450, 130)),
            OcrLine("10 g", spatialBounds = NutritionLabelParser.SpatialBox(300, 140, 450, 170)),
            OcrLine("20 g", spatialBounds = NutritionLabelParser.SpatialBox(300, 180, 450, 210)),
            OcrLine("60 g", spatialBounds = NutritionLabelParser.SpatialBox(300, 220, 450, 250)),
            OcrLine("500 mg", spatialBounds = NutritionLabelParser.SpatialBox(300, 260, 450, 290))
        )

        val ocrResult = OcrResult(
            fullText = lines.joinToString("\n") { it.text },
            blocks = emptyList(),
            lines = lines
        )

        val draft = parser.parse(ocrResult)

        // Verify that 2D spatial reconstruction correctly aligned every row
        assertNotNull("Calories must be extracted via 2D spatial alignment", draft.calories)
        assertEquals(500.0, draft.calories!!.value, 0.01)

        assertNotNull("Protein must be extracted via 2D spatial alignment", draft.protein)
        assertEquals(10.0, draft.protein!!.value, 0.01)

        assertNotNull("Fat must be extracted via 2D spatial alignment", draft.fat)
        assertEquals(20.0, draft.fat!!.value, 0.01)

        assertNotNull("Carbs must be extracted via 2D spatial alignment", draft.carbs)
        assertEquals(60.0, draft.carbs!!.value, 0.01)

        assertNotNull("Sodium must be extracted via 2D spatial alignment", draft.sodium)
        assertEquals(500.0, draft.sodium!!.value, 0.01)
    }

    @Test
    fun test11_PunctuationAndParenthesesDecorations() {
        val ocrText = """
            Per 100g
            Energy (kcal): 350
            Protein (g) = 14.5
            Carbohydrate (g) - 62.0
            Total Fat (g): 4.2
            Sodium (mg): 150
        """.trimIndent()

        val draft = parser.parseText(ocrText)

        assertEquals(350.0, draft.calories?.value ?: 0.0, 0.01)
        assertEquals(14.5, draft.protein?.value ?: 0.0, 0.01)
        assertEquals(62.0, draft.carbs?.value ?: 0.0, 0.01)
        assertEquals(4.2, draft.fat?.value ?: 0.0, 0.01)
        assertEquals(150.0, draft.sodium?.value ?: 0.0, 0.01)
    }

    @Test
    fun test12_DifferentFieldOrder() {
        val ocrText = """
            Per 100g
            Sodium 400 mg
            Fat 15 g
            Carbohydrate 50 g
            Protein 8 g
            Calories 370
        """.trimIndent()

        val draft = parser.parseText(ocrText)

        assertEquals(370.0, draft.calories?.value ?: 0.0, 0.01)
        assertEquals(8.0, draft.protein?.value ?: 0.0, 0.01)
        assertEquals(50.0, draft.carbs?.value ?: 0.0, 0.01)
        assertEquals(15.0, draft.fat?.value ?: 0.0, 0.01)
        assertEquals(400.0, draft.sodium?.value ?: 0.0, 0.01)
    }

    @Test
    fun test13_LargeLabelWithMicronutrients() {
        val ocrText = """
            Nutrition Facts
            Per 100g
            Calories 200
            Protein 10g
            Carbohydrates 30g
            Fat 4g
            Dietary Fiber 5g
            Total Sugars 8g
            Sodium 250mg
            Potassium 400mg
            Calcium 120mg
            Iron 3.5mg
        """.trimIndent()

        val draft = parser.parseText(ocrText)

        assertEquals(200.0, draft.calories?.value ?: 0.0, 0.01)
        assertEquals(10.0, draft.protein?.value ?: 0.0, 0.01)
        assertEquals(30.0, draft.carbs?.value ?: 0.0, 0.01)
        assertEquals(4.0, draft.fat?.value ?: 0.0, 0.01)
        assertEquals(5.0, draft.fiber?.value ?: 0.0, 0.01)
        assertEquals(8.0, draft.sugar?.value ?: 0.0, 0.01)
        assertEquals(250.0, draft.sodium?.value ?: 0.0, 0.01)
        assertEquals(400.0, draft.potassium?.value ?: 0.0, 0.01)
        assertEquals(120.0, draft.calcium?.value ?: 0.0, 0.01)
        assertEquals(3.5, draft.iron?.value ?: 0.0, 0.01)
        assertTrue(draft.recognizedFieldCount >= 10)
    }

    @Test
    fun test14_SmallMinimalLabel() {
        val ocrText = """
            Per 100g
            Calories 90
            Protein 3g
            Fat 1g
        """.trimIndent()

        val draft = parser.parseText(ocrText)

        assertEquals(90.0, draft.calories?.value ?: 0.0, 0.01)
        assertEquals(3.0, draft.protein?.value ?: 0.0, 0.01)
        assertEquals(1.0, draft.fat?.value ?: 0.0, 0.01)
        assertNull(draft.carbs)
        assertNull(draft.sodium)
    }
}
