package com.macrobase.app.feature.scanner

import com.macrobase.app.domain.model.scanner.ComparisonOperator
import com.macrobase.app.domain.model.scanner.NutritionBasis
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RealLabelImageRegressionTest {

    private val parser = NutritionLabelParser()

    private fun box(l: Int, t: Int, r: Int, b: Int) = NutritionLabelParser.SpatialBox(l, t, r, b)
    private fun elem(text: String, l: Int, t: Int, r: Int, b: Int) = OcrElement(
        text = text,
        boundingBox = null,
        confidence = 0.95f,
        spatialBounds = box(l, t, r, b)
    )
    private fun line(text: String, l: Int, t: Int, r: Int, b: Int, elements: List<OcrElement>) = OcrLine(
        text = text,
        boundingBox = null,
        confidence = 0.95f,
        elements = elements,
        spatialBounds = box(l, t, r, b)
    )

    @Test
    fun testRealImageFixture_ProteinPowderLabel_EndToEnd() {
        // Build OCR output exactly modeling test/4.png with multi-line split headers,
        // INGREDIENTS printed at the top, and dropped decimal points on dark brown rows
        val lines = listOf(
            // Header lines above table
            line(
                "TRUE.TRUSTED.TESTED", 100, 10, 400, 25,
                listOf(elem("TRUE.TRUSTED.TESTED", 100, 10, 400, 25))
            ),
            line(
                "INGREDIENTS: Plant Protein Blend 84.5% (Pea Protein, Rice Protein), Cocoa Powder", 20, 30, 550, 45,
                listOf(elem("INGREDIENTS: Plant Protein Blend 84.5%", 20, 30, 550, 45))
            ),
            line(
                "NUTRITION FACTS (Approx.)", 20, 55, 250, 75,
                listOf(elem("NUTRITION FACTS (Approx.)", 20, 55, 250, 75))
            ),
            // Header Row 1: Split header
            line(
                "Serving Size : 30g Per Per Serving Per Serving", 20, 80, 550, 100,
                listOf(
                    elem("Serving Size : 30g", 20, 80, 150, 100),
                    elem("Per", 280, 80, 310, 100),
                    elem("Per Serving", 370, 80, 440, 100),
                    elem("Per Serving", 480, 80, 550, 100)
                )
            ),
            // Header Row 2: Split header continuation
            line(
                "Total Servings : 16 (Approx.) 100g (30g) *RDA%", 20, 105, 550, 125,
                listOf(
                    elem("Total Servings : 16 (Approx.)", 20, 105, 200, 125),
                    elem("100g", 280, 105, 320, 125),
                    elem("(30g)", 380, 105, 420, 125),
                    elem("*RDA%", 480, 105, 530, 125)
                )
            ),
            // Row 1: Energy
            line(
                "Energy (Kcal) 423 kcal 127 kcal 6.4%", 20, 140, 540, 160,
                listOf(
                    elem("Energy (Kcal)", 20, 140, 130, 160),
                    elem("423 kcal", 280, 140, 345, 160),
                    elem("127 kcal", 380, 140, 445, 160),
                    elem("6.4%", 490, 140, 530, 160)
                )
            ),
            // Row 2: Protein (Brown band - test decimal loss recovery where serving was read as 221!)
            line(
                "Protein (g) 73.7 g 221 g 40.9%", 20, 170, 540, 190,
                listOf(
                    elem("Protein (g)", 20, 170, 115, 190),
                    elem("73.7 g", 285, 170, 330, 190),
                    elem("221 g", 385, 170, 430, 190), // dropped dot test
                    elem("40.9%", 490, 170, 540, 190)
                )
            ),
            // Row 3: Carbohydrates
            line(
                "Carbohydrates (g) 14.8 g 4.4 g **", 20, 200, 530, 220,
                listOf(
                    elem("Carbohydrates (g)", 20, 200, 155, 220),
                    elem("14.8 g", 285, 200, 335, 220),
                    elem("4.4 g", 385, 200, 425, 220),
                    elem("**", 495, 200, 515, 220)
                )
            ),
            // Row 4: Total Fat (Brown band - test decimal loss recovery where serving was read as 23!)
            line(
                "Total Fat (g) 7.7 g 23 g 3.5%", 20, 230, 540, 250,
                listOf(
                    elem("Total Fat (g)", 20, 230, 120, 250),
                    elem("7.7 g", 285, 230, 330, 250),
                    elem("23 g", 385, 230, 420, 250), // dropped dot test
                    elem("3.5%", 490, 230, 530, 250)
                )
            ),
            // Row 5: Saturated Fatty acids
            line(
                "Saturated Fatty acids (g) 2 g 0.6 g **", 20, 260, 530, 280,
                listOf(
                    elem("Saturated Fatty acids (g)", 20, 260, 190, 280),
                    elem("2 g", 290, 260, 315, 280),
                    elem("0.6 g", 385, 260, 425, 280),
                    elem("**", 495, 260, 515, 280)
                )
            ),
            // Row 6: Trans Fatty acids
            line(
                "Trans Fatty acids (g) 0 g 0 g **", 20, 290, 530, 310,
                listOf(
                    elem("Trans Fatty acids (g)", 20, 290, 175, 310),
                    elem("0 g", 290, 290, 315, 310),
                    elem("0 g", 390, 290, 415, 310),
                    elem("**", 495, 290, 515, 310)
                )
            ),
            // Row 7: Cholesterol
            line(
                "Cholesterol (mg) < 1 mg < 0.3 mg **", 20, 320, 530, 340,
                listOf(
                    elem("Cholesterol (mg)", 20, 320, 145, 340),
                    elem("< 1 mg", 280, 320, 335, 340),
                    elem("< 0.3 mg", 380, 320, 440, 340),
                    elem("**", 495, 320, 515, 340)
                )
            ),
            // Row 8: Dietary Fibre
            line(
                "Dietary Fibre (g) 4.2 g 1.3 g **", 20, 350, 530, 370,
                listOf(
                    elem("Dietary Fibre (g)", 20, 350, 145, 370),
                    elem("4.2 g", 285, 350, 330, 370),
                    elem("1.3 g", 385, 350, 425, 370),
                    elem("**", 495, 350, 515, 370)
                )
            ),
            // Row 9: Total Sugar
            line(
                "Total Sugar (g) 7 g 2.1 g **", 20, 380, 530, 400,
                listOf(
                    elem("Total Sugar (g)", 20, 380, 135, 400),
                    elem("7 g", 290, 380, 315, 400),
                    elem("2.1 g", 385, 380, 425, 400),
                    elem("**", 495, 380, 515, 400)
                )
            ),
            // Row 10: Added Sugar
            line(
                "Added Sugar (Sucrose) (g) 0 g 0 g 0%", 20, 410, 535, 430,
                listOf(
                    elem("Added Sugar (Sucrose) (g)", 20, 410, 205, 430),
                    elem("0 g", 290, 410, 315, 430),
                    elem("0 g", 390, 410, 415, 430),
                    elem("0%", 495, 410, 520, 430)
                )
            ),
            // Row 11: Sodium
            line(
                "Sodium (mg) 540 mg 162 mg 5%", 20, 440, 535, 460,
                listOf(
                    elem("Sodium (mg)", 20, 440, 120, 460),
                    elem("540 mg", 280, 440, 340, 460),
                    elem("162 mg", 380, 440, 440, 460),
                    elem("5%", 495, 440, 520, 460)
                )
            ),
            // Below table
            line(
                "BC30 Probiotic - Bacillus coagulans GBI-30, 6086 1 Billion CFU **", 20, 470, 550, 490,
                listOf(elem("BC30 Probiotic", 20, 470, 550, 490))
            )
        )

        val fullText = lines.joinToString("\n") { it.text }
        val ocrResult = OcrResult(fullText, emptyList(), lines)

        val draft = parser.parse(ocrResult)

        // 1. Assert Serving Metadata
        assertEquals("Serving grams must be 30.0", 30.0, draft.servingGrams ?: 0.0, 0.01)

        // 2. Assert Canonical Basis is PER_100_G
        assertEquals("Basis must be PER_100_G", NutritionBasis.PER_100_G, draft.detectedBasis)

        // 3. Assert Canonical Per 100g Values
        assertNotNull("Calories must not be null", draft.calories)
        assertEquals(423.0, draft.calories!!.value, 0.01)

        assertNotNull("Protein must not be null", draft.protein)
        assertEquals(73.7, draft.protein!!.value, 0.01)

        assertNotNull("Carbohydrates must not be null", draft.carbs)
        assertEquals(14.8, draft.carbs!!.value, 0.01)

        assertNotNull("Fat must not be null", draft.fat)
        assertEquals(7.7, draft.fat!!.value, 0.01)

        assertNotNull("Saturated Fat must not be null", draft.saturatedFat)
        assertEquals(2.0, draft.saturatedFat!!.value, 0.01)

        assertNotNull("Trans Fat must not be null", draft.transFat)
        assertEquals(0.0, draft.transFat!!.value, 0.01)

        assertNotNull("Cholesterol must not be null", draft.cholesterol)
        assertEquals("Comparison operator for Cholesterol must be LESS_THAN", ComparisonOperator.LESS_THAN, draft.cholesterol!!.operator)

        assertNotNull("Dietary Fibre must not be null", draft.fiber)
        assertEquals(4.2, draft.fiber!!.value, 0.01)

        assertNotNull("Total Sugar must not be null", draft.sugar)
        assertEquals(7.0, draft.sugar!!.value, 0.01)

        assertNotNull("Added Sugar must not be null", draft.addedSugar)
        assertEquals(0.0, draft.addedSugar!!.value, 0.01)

        assertNotNull("Sodium must not be null", draft.sodium)
        assertEquals(540.0, draft.sodium!!.value, 0.01)

        // 4. Assert Per Serving Values Retained in perServingValues
        assertEquals(127.0, draft.perServingValues["calories"]?.value ?: 0.0, 0.01)
        assertEquals("Recovered protein serving must be 22.1 (not 221)", 22.1, draft.perServingValues["protein"]?.value ?: 0.0, 0.01)
        assertEquals(4.4, draft.perServingValues["carbs"]?.value ?: 0.0, 0.01)
        assertEquals("Recovered fat serving must be 2.3 (not 23)", 2.3, draft.perServingValues["fat"]?.value ?: 0.0, 0.01)
        assertEquals(0.6, draft.perServingValues["saturatedFat"]?.value ?: 0.0, 0.01)
        assertEquals(1.3, draft.perServingValues["fiber"]?.value ?: 0.0, 0.01)
        assertEquals(2.1, draft.perServingValues["sugar"]?.value ?: 0.0, 0.01)
        assertEquals(162.0, draft.perServingValues["sodium"]?.value ?: 0.0, 0.01)

        // 5. Assert RDA segregation
        assertEquals(6.4, draft.rdaValues["calories"] ?: 0.0, 0.01)
        assertEquals(40.9, draft.rdaValues["protein"] ?: 0.0, 0.01)
        assertEquals(3.5, draft.rdaValues["fat"] ?: 0.0, 0.01)
        assertEquals(5.0, draft.rdaValues["sodium"] ?: 0.0, 0.01)

        // 6. Assert Derived Per 1g Values
        val draft1g = draft.withOutputBasis(NutritionBasis.PER_1_G)
        assertEquals(4.23, draft1g.calories!!.value, 0.01)
        assertEquals(0.737, draft1g.protein!!.value, 0.001)
        assertEquals(0.148, draft1g.carbs!!.value, 0.001)
        assertEquals(0.077, draft1g.fat!!.value, 0.001)
        assertEquals(0.042, draft1g.fiber!!.value, 0.001)
        assertEquals(0.07, draft1g.sugar!!.value, 0.001)
        assertEquals(5.4, draft1g.sodium!!.value, 0.01)

        // 7. Assert Debug Data populated
        assertNotNull(draft.debugData)
        assertTrue(draft.debugData!!.columns.size >= 2)
        assertTrue(draft.debugData!!.rows.isNotEmpty())
    }
}
