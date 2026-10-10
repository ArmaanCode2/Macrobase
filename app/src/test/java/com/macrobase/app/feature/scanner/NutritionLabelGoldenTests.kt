package com.macrobase.app.feature.scanner

import android.graphics.Rect
import com.macrobase.app.domain.model.scanner.ComparisonOperator
import com.macrobase.app.domain.model.scanner.ConfidenceLevel
import com.macrobase.app.domain.model.scanner.NutritionBasis
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 18 Golden-Scenario Regression Suite for Nutrition Label Scanner.
 * Verifies global formats (US FDA, Indian FSSAI, European, ANZ), table geometry,
 * multi-column column separation, digit corrections, unit conversions, and edge cases.
 */
class NutritionLabelGoldenTests {

    private lateinit var parser: NutritionLabelParser
    private lateinit var tableReconstructor: TableLayoutReconstructor
    private lateinit var validator: NutritionValidator

    @Before
    fun setUp() {
        parser = NutritionLabelParser()
        tableReconstructor = TableLayoutReconstructor()
        validator = NutritionValidator()
    }

    // SCENARIO 1: Standard US FDA label
    @Test
    fun test01_StandardUsFdaLabel() {
        val ocrText = """
            Nutrition Facts
            8 servings per container
            Serving size 1/2 cup (65g)
            Amount per serving
            Calories 230
            Total Fat 8g
            Saturated Fat 1g
            Trans Fat 0g
            Cholesterol 0mg
            Sodium 160mg
            Total Carbohydrate 37g
            Dietary Fiber 4g
            Total Sugars 12g
            Includes 10g Added Sugars
            Protein 3g
        """.trimIndent()

        val draft = parser.parseText(ocrText)

        assertNotNull(draft.calories)
        assertEquals(353.8, draft.calories!!.value, 1.0) // Normalized to 100g (230 * 100 / 65)
        assertEquals(65.0, draft.servingGrams ?: 0.0, 0.1)

        assertNotNull(draft.fat)
        assertEquals(12.3, draft.fat!!.value, 0.5) // 8 * 100 / 65

        assertNotNull(draft.sodium)
        assertEquals(246.0, draft.sodium!!.value, 5.0) // 160 * 100 / 65

        assertNotNull(draft.carbs)
        assertEquals(56.9, draft.carbs!!.value, 0.5)

        assertNotNull(draft.protein)
        assertEquals(4.6, draft.protein!!.value, 0.5)

        assertNotNull(draft.sugar)
        assertNotNull(draft.fiber)
    }

    // SCENARIO 2: Indian FSSAI label with "Per 100g" and "Per Serving"
    @Test
    fun test02_IndianFssaiLabelWithDualBasis() {
        val lines = listOf(
            makeLine("NUTRITIONAL INFORMATION", 50, 20, 500, 50),
            makeLine("Nutrients Per 100g Per Serve (30g)", 50, 60, 550, 90),
            makeLine("Energy (kcal) 480 144", 50, 100, 550, 130),
            makeLine("Protein (g) 9.0 2.7", 50, 140, 550, 170),
            makeLine("Carbohydrate (g) 65.0 19.5", 50, 180, 550, 210),
            makeLine("Total Sugars (g) 22.0 6.6", 50, 220, 550, 250),
            makeLine("Added Sugars (g) 18.0 5.4", 50, 260, 550, 290),
            makeLine("Total Fat (g) 20.0 6.0", 50, 300, 550, 330),
            makeLine("Sodium (mg) 320 96", 50, 340, 550, 370)
        )

        val ocrResult = OcrResult(
            fullText = lines.joinToString("\n") { it.text },
            blocks = emptyList(),
            lines = lines
        )

        val draft = parser.parse(ocrResult)

        assertEquals(NutritionBasis.PER_100_G, draft.detectedBasis)
        assertEquals(480.0, draft.calories!!.value, 0.1)
        assertEquals(9.0, draft.protein!!.value, 0.1)
        assertEquals(65.0, draft.carbs!!.value, 0.1)
        assertEquals(20.0, draft.fat!!.value, 0.1)
        assertEquals(320.0, draft.sodium!!.value, 0.1)

        // Verify dual-basis map contains both columns
        assertTrue(draft.per100gValues.isNotEmpty())
        assertEquals(480.0, draft.per100gValues["calories"]?.value ?: 0.0, 0.1)
    }

    // SCENARIO 3: Indian FSSAI label with "Approx. Values"
    @Test
    fun test03_IndianFssaiApproxValues() {
        val ocrText = """
            NUTRITIONAL FACTS
            Approx. Values Per 100g
            Energy: 520 kcal
            Protein: 7.8 g
            Carbohydrate: 68.2 g
            Total Fat: 24.0 g
            Sodium: 410 mg
        """.trimIndent()

        val draft = parser.parseText(ocrText)

        assertEquals(NutritionBasis.PER_100_G, draft.detectedBasis)
        assertEquals(520.0, draft.calories!!.value, 0.1)
        assertEquals(7.8, draft.protein!!.value, 0.1)
        assertEquals(68.2, draft.carbs!!.value, 0.1)
        assertEquals(24.0, draft.fat!!.value, 0.1)
        assertEquals(410.0, draft.sodium!!.value, 0.1)
    }

    // SCENARIO 4: European label with "kJ / kcal" dual energy
    @Test
    fun test04_EuropeanDualEnergyKjKcal() {
        val ocrText = """
            Valeurs nutritionnelles moyennes pour 100g
            Energie 1850 kJ / 440 kcal
            Matieres grasses 14 g
            Glucides 70 g
            Proteines 8.5 g
            Sel 0.8 g
        """.trimIndent()

        val draft = parser.parseText(ocrText)

        assertNotNull(draft.calories)
        assertEquals(440.0, draft.calories!!.value, 0.1)
        assertEquals("kcal", draft.calories!!.unit)
        assertEquals(8.5, draft.protein!!.value, 0.1)
        assertEquals(70.0, draft.carbs!!.value, 0.1)
        assertEquals(14.0, draft.fat!!.value, 0.1)
    }

    // SCENARIO 5: European label with Salt instead of Sodium
    @Test
    fun test05_EuropeanSaltToSodiumEstimation() {
        val ocrText = """
            Nutrition Information
            per 100g
            Energy 200 kcal
            Fat 5 g
            Carbohydrates 30 g
            Protein 8 g
            Salt 1.27 g
        """.trimIndent()

        val draft = parser.parseText(ocrText)

        assertNotNull(draft.salt)
        assertEquals(1.27, draft.salt!!.value, 0.01)

        // 1.27g Salt / 2.54 * 1000 = 500mg Sodium
        assertNotNull(draft.sodium)
        assertEquals(500.0, draft.sodium!!.value, 1.0)
        assertTrue(draft.sodium!!.isEstimated)
    }

    // SCENARIO 6: Australian/NZ label with "Avg Qty Per Serving" and "Avg Qty Per 100g"
    @Test
    fun test06_AnzLabelAvgQtyPerServingAndPer100g() {
        val lines = listOf(
            makeLine("NUTRITION INFORMATION", 50, 20, 500, 50),
            makeLine("Servings per package: 5", 50, 60, 300, 85),
            makeLine("Serving size: 40g", 50, 90, 250, 115),
            makeLine("Avg Qty Per Serving Avg Qty Per 100g", 50, 120, 550, 145),
            makeLine("Energy 680kJ 1700kJ", 50, 150, 550, 175),
            makeLine("Protein 3.2g 8.0g", 50, 180, 550, 205),
            makeLine("Fat, total 4.8g 12.0g", 50, 210, 550, 235),
            makeLine("Carbohydrate 24.0g 60.0g", 50, 240, 550, 265),
            makeLine("Sodium 120mg 300mg", 50, 270, 550, 295)
        )

        val ocrResult = OcrResult(
            fullText = lines.joinToString("\n") { it.text },
            blocks = emptyList(),
            lines = lines
        )

        val draft = parser.parse(ocrResult)
        assertEquals(40.0, draft.servingGrams ?: 0.0, 0.1)
        assertNotNull(draft.protein)
        assertNotNull(draft.carbs)
        assertNotNull(draft.fat)
        assertNotNull(draft.sodium)
    }

    // SCENARIO 7: Single-column label with serving size header
    @Test
    fun test07_SingleColumnLabelServingSizeHeader() {
        val ocrText = """
            Nutrition Facts
            Serving Size: 28g
            Calories: 140
            Total Fat: 9g
            Total Carbohydrate: 12g
            Protein: 4g
            Sodium: 110mg
        """.trimIndent()

        val draft = parser.parseText(ocrText)

        assertEquals(28.0, draft.servingGrams ?: 0.0, 0.1)
        // Normalized to 100g
        assertEquals(500.0, draft.calories!!.value, 1.0) // 140 * 100 / 28
        assertEquals(32.1, draft.fat!!.value, 0.5) // 9 * 100 / 28
        assertEquals(14.3, draft.protein!!.value, 0.5) // 4 * 100 / 28
    }

    // SCENARIO 8: Two-column label with %RDA
    @Test
    fun test08_TwoColumnWithRdaPercentage() {
        val ocrText = """
            Nutrition Facts
            Amount Per Serving    % Daily Value
            Total Fat 8g              10%
            Saturated Fat 1.5g         8%
            Sodium 200mg               9%
            Total Carbohydrate 28g    10%
            Protein 6g
        """.trimIndent()

        val draft = parser.parseText(ocrText)

        assertNotNull(draft.fat)
        assertEquals(8.0, draft.fat!!.value, 0.1)
        assertNotNull(draft.sodium)
        assertEquals(200.0, draft.sodium!!.value, 0.1)
        assertNotNull(draft.carbs)
        assertEquals(28.0, draft.carbs!!.value, 0.1)
        assertNotNull(draft.protein)
        assertEquals(6.0, draft.protein!!.value, 0.1)
    }

    // SCENARIO 9: Three-column label: Per 100g, Per Serving, %RDA
    @Test
    fun test09_ThreeColumnPer100gPerServingRda() {
        val lines = listOf(
            makeLine("NUTRITION INFORMATION", 50, 20, 500, 50),
            makeLine("Per 100g Per Serving %RDA", 50, 60, 580, 90),
            makeLine("Energy (kcal) 400 120 6%", 50, 100, 580, 130),
            makeLine("Protein 30.0g 9.0g 18%", 50, 140, 580, 170),
            makeLine("Carbohydrate 40.0g 12.0g 9%", 50, 180, 580, 210),
            makeLine("Fat 10.0g 3.0g 4%", 50, 220, 580, 250),
            makeLine("Sodium 500mg 150mg 8%", 50, 260, 580, 290)
        )

        val ocrResult = OcrResult(
            fullText = lines.joinToString("\n") { it.text },
            blocks = emptyList(),
            lines = lines
        )

        val draft = parser.parse(ocrResult)
        assertEquals(NutritionBasis.PER_100_G, draft.detectedBasis)
        assertEquals(400.0, draft.calories!!.value, 0.1)
        assertEquals(30.0, draft.protein!!.value, 0.1)
        assertEquals(40.0, draft.carbs!!.value, 0.1)
        assertEquals(10.0, draft.fat!!.value, 0.1)
        assertEquals(500.0, draft.sodium!!.value, 0.1)
    }

    // SCENARIO 10: Label with misread digits (O for 0, l for 1, S for 5, rng for mg)
    @Test
    fun test10_MisreadDigitsCorrection() {
        val ocrText = """
            Nutrition Information
            Per 100g
            Energy 40O kcal
            Prote1n 3Og
            Carbohydrate 5Og
            Total Fat 1O g
            Sodium l90 rng
        """.trimIndent()

        val draft = parser.parseText(ocrText)

        assertNotNull(draft.calories)
        assertEquals(400.0, draft.calories!!.value, 0.1)
        assertNotNull(draft.protein)
        assertEquals(30.0, draft.protein!!.value, 0.1)
        assertNotNull(draft.carbs)
        assertEquals(50.0, draft.carbs!!.value, 0.1)
        assertNotNull(draft.fat)
        assertEquals(10.0, draft.fat!!.value, 0.1)
        assertNotNull(draft.sodium)
        assertEquals(190.0, draft.sodium!!.value, 0.1)
        assertEquals("mg", draft.sodium!!.unit)
    }

    // SCENARIO 11: Label with decimal commas ("25,7 g")
    @Test
    fun test11_DecimalCommasSupport() {
        val ocrText = """
            Valeurs pour 100g
            Energie 350,5 kcal
            Proteines 12,5 g
            Glucides 62,8 g
            Lipides 8,4 g
            Sel 0,75 g
        """.trimIndent()

        val draft = parser.parseText(ocrText)

        assertEquals(350.5, draft.calories!!.value, 0.1)
        assertEquals(12.5, draft.protein!!.value, 0.1)
        assertEquals(62.8, draft.carbs!!.value, 0.1)
        assertEquals(8.4, draft.fat!!.value, 0.1)
        assertEquals(0.75, draft.salt!!.value, 0.01)
    }

    // SCENARIO 12: Label with < 0.5 g notation
    @Test
    fun test12_LessThanThresholdNotation() {
        val ocrText = """
            Nutrition Facts
            Per 100g
            Energy 210 kcal
            Protein 15 g
            Carbohydrates < 0.5 g
            Total Fat < 0.5 g
            Sodium 80 mg
        """.trimIndent()

        val draft = parser.parseText(ocrText)

        assertNotNull(draft.carbs)
        assertTrue(draft.carbs!!.isEstimated)
        assertEquals(0.25, draft.carbs!!.value, 0.05) // < 0.5 estimated as 0.25

        assertNotNull(draft.fat)
        assertTrue(draft.fat!!.isEstimated)
        assertEquals(0.25, draft.fat!!.value, 0.05)
    }

    // SCENARIO 13: Label with added sugars indented under total sugars
    @Test
    fun test13_NestedAddedSugarsHierarchy() {
        val ocrText = """
            Nutrition Facts
            Per 100g
            Total Carbohydrate 45g
            Total Sugars 25g
            Includes 18g Added Sugars
            Protein 5g
        """.trimIndent()

        val draft = parser.parseText(ocrText)

        assertNotNull(draft.carbs)
        assertEquals(45.0, draft.carbs!!.value, 0.1)

        assertNotNull(draft.sugar)
        assertEquals(25.0, draft.sugar!!.value, 0.1)

        assertNotNull(draft.addedSugar)
        assertEquals(18.0, draft.addedSugar!!.value, 0.1)
    }

    // SCENARIO 14: Label with saturated fat indented under total fat
    @Test
    fun test14_NestedSaturatedFatHierarchy() {
        val ocrText = """
            Nutrition Facts
            Per 100g
            Total Fat 16g
            Saturated Fat 4.5g
            Trans Fat 0.1g
            Cholesterol 25mg
        """.trimIndent()

        val draft = parser.parseText(ocrText)

        assertNotNull(draft.fat)
        assertEquals(16.0, draft.fat!!.value, 0.1)

        assertNotNull(draft.saturatedFat)
        assertEquals(4.5, draft.saturatedFat!!.value, 0.1)

        assertNotNull(draft.transFat)
        assertEquals(0.1, draft.transFat!!.value, 0.01)

        assertNotNull(draft.cholesterol)
        assertEquals(25.0, draft.cholesterol!!.value, 0.1)
    }

    // SCENARIO 15: Label with no visible table borders (borderless spatial layout)
    @Test
    fun test15_BorderlessTableSpatialLayout() {
        val lines = listOf(
            makeLine("NUTRITION FACTS", 40, 10, 300, 30),
            makeLine("Per 100g", 40, 40, 120, 60),
            makeLine("Energy        380 kcal", 40, 70, 280, 90),
            makeLine("Protein        22 g", 40, 100, 280, 120),
            makeLine("Carbohydrate   48 g", 40, 130, 280, 150),
            makeLine("Fat             8 g", 40, 160, 280, 180),
            makeLine("Sodium        450 mg", 40, 190, 280, 210)
        )

        val ocrResult = OcrResult(
            fullText = lines.joinToString("\n") { it.text },
            blocks = emptyList(),
            lines = lines
        )

        val draft = parser.parse(ocrResult)
        assertEquals(380.0, draft.calories!!.value, 0.1)
        assertEquals(22.0, draft.protein!!.value, 0.1)
        assertEquals(48.0, draft.carbs!!.value, 0.1)
        assertEquals(8.0, draft.fat!!.value, 0.1)
        assertEquals(450.0, draft.sodium!!.value, 0.1)
    }

    // SCENARIO 16: Label with microgram units ("µg", "mcg")
    @Test
    fun test16_MicrogramUnitsHandling() {
        val normMcg = NutritionUnitParser.normalizeUnit("mcg")
        val normGreek = NutritionUnitParser.normalizeUnit("µg")
        val normAlt = NutritionUnitParser.normalizeUnit("μg")

        assertEquals("mcg", normMcg)
        assertEquals("mcg", normGreek)
        assertEquals("mcg", normAlt)

        val convertedMg = NutritionUnitParser.convertUnit(500.0, "mcg", "mg")
        assertEquals(0.5, convertedMg ?: 0.0, 0.001)
    }

    // SCENARIO 17: Label with small text, low contrast warnings
    @Test
    fun test17_ValidatorSanityChecks() {
        // Macro consistency: 10P (40) + 20C (80) + 5F (45) = 165 kcal vs 350 kcal
        val warning = validator.validateMacroConsistency(
            calories = 350.0,
            protein = 10.0,
            carbs = 20.0,
            fat = 5.0
        )
        assertNotNull(warning)
        assertTrue(warning!!.contains("differ notably"))

        // Plausible macro: 10P (40) + 20C (80) + 5F (45) = 165 kcal vs 170 kcal
        val validCheck = validator.validateMacroConsistency(
            calories = 170.0,
            protein = 10.0,
            carbs = 20.0,
            fat = 5.0
        )
        assertNull(validCheck)
    }

    // SCENARIO 18: Image with no nutrition table (rejection test)
    @Test
    fun test18_NonNutritionTextRejection() {
        val nonNutritionText = """
            FRONT OF PACK
            Delicious Chocolate Chip Cookies
            Net Weight 200g
            Keep in a cool dry place
            Store away from direct sunlight
            Best before 12 months from manufacture
        """.trimIndent()

        val draft = parser.parseText(nonNutritionText)

        assertFalse(draft.hasNutrientData)
        assertEquals(0, draft.recognizedFieldCount)
        assertNull(draft.calories)
        assertNull(draft.protein)
        assertNull(draft.fat)
        assertNull(draft.carbs)
    }

    private fun makeLine(text: String, left: Int, top: Int, right: Int, bottom: Int): OcrLine {
        val bounds = Rect(left, top, right, bottom)
        val sBox = NutritionLabelParser.SpatialBox(left, top, right, bottom)
        val words = Regex("\\S+").findAll(text).toList()
        val totalChars = text.length.coerceAtLeast(1)
        val width = right - left
        val charWidth = width.toFloat() / totalChars

        val elements = words.map { m ->
            val wLeft = (left + m.range.first * charWidth).toInt()
            val wRight = (left + (m.range.last + 1) * charWidth).toInt()
            val wBounds = Rect(wLeft, top, wRight, bottom)
            val wBox = NutritionLabelParser.SpatialBox(wLeft, top, wRight, bottom)
            OcrElement(text = m.value, boundingBox = wBounds, spatialBounds = wBox)
        }

        return OcrLine(
            text = text,
            boundingBox = bounds,
            spatialBounds = sBox,
            elements = elements
        )
    }

    // SCENARIO 19: Real-world label: "Approx. Values Per Serving" with Serving Size 32g
    @Test
    fun test19_ApproxValuesPerServingWithServingSize32g() {
        val ocrText = """
            NUTRITION FACTS
            Serving Size: 32g
            Approx. Values Per Serving
            Energy: 160 kcal
            Protein: 9.6 g
            Carbohydrate: 18.0 g
            Total Fat: 5.5 g
            Sodium: 120 mg
        """.trimIndent()

        val draft = parser.parseText(ocrText)

        assertEquals(NutritionBasis.PER_100_G, draft.detectedBasis)
        assertEquals(NutritionBasis.PER_SERVING, draft.sourceBasis)
        assertNotNull(draft.normalizationNote)

        // 160 * 100 / 32 = 500 kcal
        assertEquals(500.0, draft.calories!!.value, 0.1)
        // 9.6 * 100 / 32 = 30.0 g
        assertEquals(30.0, draft.protein!!.value, 0.1)
        // 18.0 * 100 / 32 = 56.25 g
        assertEquals(56.25, draft.carbs!!.value, 0.1)
        // 5.5 * 100 / 32 = 17.19 g
        assertEquals(17.19, draft.fat!!.value, 0.1)

        // Per 1g derivation
        val p1g = draft.withOutputBasis(NutritionBasis.PER_1_G)
        assertEquals(NutritionBasis.PER_1_G, p1g.detectedBasis)
        assertEquals(0.30, p1g.protein!!.value, 0.01)
        assertEquals(5.0, p1g.calories!!.value, 0.1)
    }

    // SCENARIO 20: Serving size without grams ("1 bar") -> cannot normalize to 100g
    @Test
    fun test20_NoServingMassUnnormalizable() {
        val ocrText = """
            Nutrition Facts
            Serving size 1 bar
            Calories 180
            Protein 15g
            Total Fat 7g
        """.trimIndent()

        val draft = parser.parseText(ocrText)

        assertNull("Serving mass in grams must be null", draft.servingGrams)
        assertEquals(NutritionBasis.PER_SERVING, draft.sourceBasis)
        assertEquals(NutritionBasis.PER_SERVING, draft.detectedBasis)
        assertTrue("Draft must contain warning about missing gram weight", draft.warnings.any { it.contains("grams not found") })
        assertEquals(15.0, draft.protein!!.value, 0.1)
        assertTrue(draft.protein!!.needsReview)
    }

    // SCENARIO 21: Real test label regression: Per 100g | Per Serving (30g) | Per Serving %RDA
    @Test
    fun test21_ThreeColumnDualBasisAndRdaSeparation() {
        val lines = listOf(
            makeLine("NUTRITIONAL INFORMATION", 50, 20, 600, 50),
            makeLine("Serving Size: 30g", 50, 60, 300, 90),
            makeLine("Nutrients Per 100g Per Serving (30g) Per Serving %RDA", 50, 100, 650, 130),
            makeLine("Energy 423 kcal 127 kcal 6.4%", 50, 140, 650, 170),
            makeLine("Protein 73.7 g 22.1 g 40.9%", 50, 180, 650, 210),
            makeLine("Carbohydrates 14.8 g 4.4 g **", 50, 220, 650, 250),
            makeLine("Total Fat 7.7 g 2.3 g 3.5%", 50, 260, 650, 290),
            makeLine("Saturated Fat 2 g 0.6 g **", 50, 300, 650, 330),
            makeLine("Trans Fat 0 g 0 g 0%", 50, 340, 650, 370),
            makeLine("Cholesterol <1 mg <0.3 mg **", 50, 380, 650, 410),
            makeLine("Dietary Fibre 4.2 g 1.3 g 5%", 50, 420, 650, 450),
            makeLine("Total Sugar 7 g 2.1 g **", 50, 460, 650, 490),
            makeLine("Added Sugar 0 g 0 g 0%", 50, 500, 650, 530),
            makeLine("Sodium 540 mg 162 mg 8.1%", 50, 540, 650, 570)
        )

        val ocrResult = OcrResult(
            fullText = lines.joinToString("\n") { it.text },
            blocks = emptyList(),
            lines = lines
        )

        val draft = parser.parse(ocrResult)

        // Basis and Serving verification
        assertEquals(NutritionBasis.PER_100_G, draft.detectedBasis)
        assertEquals(30.0, draft.servingGrams ?: 0.0, 0.1)

        // Canonical Per 100g values
        assertEquals(423.0, draft.calories!!.value, 0.1)
        assertEquals(73.7, draft.protein!!.value, 0.1)
        assertEquals(14.8, draft.carbs!!.value, 0.1)
        assertEquals(7.7, draft.fat!!.value, 0.1)
        assertEquals(2.0, draft.saturatedFat!!.value, 0.1)
        assertEquals(0.0, draft.transFat!!.value, 0.1)
        assertEquals(ComparisonOperator.LESS_THAN, draft.cholesterol!!.operator)
        assertEquals(1.0, draft.cholesterol!!.sourceValue ?: 0.0, 0.1)
        assertEquals(4.2, draft.fiber!!.value, 0.1)
        assertEquals(7.0, draft.sugar!!.value, 0.1)
        assertEquals(0.0, draft.addedSugar!!.value, 0.1)
        assertEquals(540.0, draft.sodium!!.value, 0.1)

        // Per Serving preserved values
        assertEquals(127.0, draft.perServingValues["calories"]!!.value, 0.1)
        assertEquals(22.1, draft.perServingValues["protein"]!!.value, 0.1)
        assertEquals(4.4, draft.perServingValues["carbs"]!!.value, 0.1)
        assertEquals(2.3, draft.perServingValues["fat"]!!.value, 0.1)
        assertEquals(162.0, draft.perServingValues["sodium"]!!.value, 0.1)
        assertEquals(ComparisonOperator.LESS_THAN, draft.perServingValues["cholesterol"]!!.operator)

        // RDA percentages segregated from nutrient values
        assertEquals(6.4, draft.rdaValues["calories"] ?: 0.0, 0.1)
        assertEquals(40.9, draft.rdaValues["protein"] ?: 0.0, 0.1)
        assertEquals(3.5, draft.rdaValues["fat"] ?: 0.0, 0.1)
        assertEquals(5.0, draft.rdaValues["fiber"] ?: 0.0, 0.1)
        assertEquals(8.1, draft.rdaValues["sodium"] ?: 0.0, 0.1)

        // Derived Per 1g values (per100g / 100.0)
        assertEquals(4.23, draft.per1gValues["calories"]!!.value, 0.01)
        assertEquals(0.737, draft.per1gValues["protein"]!!.value, 0.001)
        assertEquals(0.148, draft.per1gValues["carbs"]!!.value, 0.001)
        assertEquals(0.077, draft.per1gValues["fat"]!!.value, 0.001)
        assertEquals(5.4, draft.per1gValues["sodium"]!!.value, 0.1)
    }

    // SCENARIO 22: Inverted columns in OCR -> auto-corrected via serving relationship
    @Test
    fun test22_TableWideColumnSwapCorrection() {
        val lines = listOf(
            makeLine("NUTRITION FACTS", 50, 20, 600, 50),
            makeLine("Serving Size: 30g", 50, 60, 300, 90),
            // Header columns are declared: Per Serving | Per 100g
            makeLine("Nutrients Per Serving (30g) Per 100g", 50, 100, 650, 130),
            // But data rows are printed: 100g values on Left, Serving values on Right
            makeLine("Energy 423 kcal 127 kcal", 50, 140, 650, 170),
            makeLine("Protein 73.7 g 22.1 g", 50, 180, 650, 210),
            makeLine("Carbohydrates 14.8 g 4.4 g", 50, 220, 650, 250),
            makeLine("Total Fat 7.7 g 2.3 g", 50, 260, 650, 290),
            makeLine("Sodium 540 mg 162 mg", 50, 300, 650, 330)
        )

        val ocrResult = OcrResult(
            fullText = lines.joinToString("\n") { it.text },
            blocks = emptyList(),
            lines = lines
        )

        val draft = parser.parse(ocrResult)

        // Table-wide orientation score detects that 423 * 0.3 = 127 and auto-corrects
        assertEquals(NutritionBasis.PER_100_G, draft.detectedBasis)
        assertEquals(423.0, draft.calories!!.value, 0.1)
        assertEquals(73.7, draft.protein!!.value, 0.1)
        assertEquals(14.8, draft.carbs!!.value, 0.1)
        assertEquals(7.7, draft.fat!!.value, 0.1)
        assertEquals(540.0, draft.sodium!!.value, 0.1)
        assertTrue(draft.warnings.any { it.contains("swapped based on serving relationship") })
    }

    // SCENARIO 23: Decimal loss recovery (e.g. 7.7 g scanned as 77 g)
    @Test
    fun test23_DecimalLossRecovery() {
        val lines = listOf(
            makeLine("NUTRITION INFORMATION", 50, 20, 600, 50),
            makeLine("Serving Size: 30g", 50, 60, 300, 90),
            makeLine("Nutrients Per 100g Per Serving (30g)", 50, 100, 650, 130),
            makeLine("Energy 423 kcal 127 kcal", 50, 140, 650, 170),
            makeLine("Protein 73.7 g 22.1 g", 50, 180, 650, 210),
            // Fat decimal dropped: 77g vs 2.3g
            makeLine("Total Fat 77 g 2.3 g", 50, 220, 650, 250),
            makeLine("Sodium 540 mg 162 mg", 50, 260, 650, 290)
        )

        val ocrResult = OcrResult(
            fullText = lines.joinToString("\n") { it.text },
            blocks = emptyList(),
            lines = lines
        )

        val draft = parser.parse(ocrResult)

        // Contextual decimal recovery detects (77 / 10) * 0.3 = 2.31 ≈ 2.3
        assertEquals(7.7, draft.fat!!.value, 0.1)
        assertTrue(draft.warnings.any { it.contains("Recovered decimal point in fat") })
    }

    // SCENARIO 24: Comparison operators preserved (<1 mg, <0.3 mg)
    @Test
    fun test24_ComparisonOperatorPreserved() {
        val ocrText = """
            Nutrition Facts
            Serving Size: 30g
            Per 100g
            Energy 400 kcal
            Cholesterol <1 mg
            Sodium 500 mg
        """.trimIndent()

        val draft = parser.parseText(ocrText)

        assertNotNull(draft.cholesterol)
        assertEquals(ComparisonOperator.LESS_THAN, draft.cholesterol!!.operator)
        assertEquals(1.0, draft.cholesterol!!.sourceValue ?: 0.0, 0.01)
        assertTrue(draft.cholesterol!!.isEstimated)
    }

    // SCENARIO 25: Varied serving sizes normalization (20g, 38g, 65g)
    @Test
    fun test25_VariedServingSizesNormalization() {
        val ocr20g = """
            Nutrition Facts
            Serving size: 20g
            Per serving
            Energy: 80 kcal
            Protein: 4 g
            Total Fat: 2 g
            Carbohydrate: 12 g
        """.trimIndent()

        val draft20g = parser.parseText(ocr20g)
        assertEquals(NutritionBasis.PER_100_G, draft20g.detectedBasis)
        // 80 * 100 / 20 = 400 kcal
        assertEquals(400.0, draft20g.calories!!.value, 0.1)
        assertEquals(20.0, draft20g.protein!!.value, 0.1)

        val ocr38g = """
            Nutrition Facts
            Serving Size 38g
            Amount per serve
            Calories 190
            Protein 10g
            Fat 5g
            Carbohydrate 25g
        """.trimIndent()

        val draft38g = parser.parseText(ocr38g)
        assertEquals(NutritionBasis.PER_100_G, draft38g.detectedBasis)
        // 190 * 100 / 38 = 500 kcal
        assertEquals(500.0, draft38g.calories!!.value, 0.1)
        // 10 * 100 / 38 = 26.32 g
        assertEquals(26.32, draft38g.protein!!.value, 0.1)
    }
}
