package com.macrobase.app.feature.scanner

import com.macrobase.app.domain.model.scanner.ConfidenceLevel
import com.macrobase.app.domain.model.scanner.NutritionBasis
import com.macrobase.app.domain.model.scanner.NutritionLabelDraft
import com.macrobase.app.domain.model.scanner.ParsedNutrientValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * P3 phase 6: large gallery images, warnings that are never lost, US labels with a "% Daily
 * Value" column and a "Calories from Fat" row, and crop-pass coordinates (BUG-041 to BUG-045).
 */
class P3PhaseSixScannerTests {

    private val parser = NutritionLabelParser()

    // ---------------------------------------------------------------------------------------
    // BUG-041: the %DV number is never a value
    // ---------------------------------------------------------------------------------------

    @Test
    fun aSeparatePercentSignIsADailyValueNotPer100g() {
        val draft = parser.parseText("Amount Per Serving % Daily Value\nSodium 160mg 7 %")

        assertEquals(NutritionBasis.PER_SERVING, draft.sourceBasis)
        assertEquals(160.0, draft.perServingValues.getValue("sodium").value, 0.0)
        // No serving weight, so sodium stays 160 per serving; 7 is the %DV, never a value
        assertEquals(160.0, draft.sodium!!.value, 0.0)
        assertEquals(NutritionBasis.PER_SERVING, draft.sodium!!.basis)
        assertNotEquals(7.0, draft.per100gValues["sodium"]?.value ?: 0.0, 0.0)
        assertEquals(7.0, draft.rdaValues.getValue("sodium"), 0.0)
    }

    @Test
    fun anAttachedPercentSignStillWorks() {
        val draft = parser.parseText("Amount Per Serving % Daily Value\nSodium 160mg 7%")

        assertEquals(160.0, draft.perServingValues.getValue("sodium").value, 0.0)
        assertEquals(160.0, draft.sodium!!.value, 0.0)
        assertEquals(NutritionBasis.PER_SERVING, draft.sodium!!.basis)
    }

    @Test
    fun aUsLabelWithASeparateDailyValueColumnKeepsEveryValue() {
        val draft = parser.parseText(
            """
            Nutrition Facts
            Serving size 1 cup (228g)
            Amount Per Serving
            Calories 250
            % Daily Value*
            Total Fat 12g 18 %
            Sodium 470mg 20 %
            Total Carbohydrate 31g 10 %
            Protein 5g
            """.trimIndent()
        )

        assertPerServing(draft, "fat", 12.0)
        assertPerServing(draft, "sodium", 470.0)
        assertPerServing(draft, "carbs", 31.0)
        // 470 mg in 228 g is 206 mg per 100 g, never the 20 from the %DV column
        assertEquals(206.1, draft.sodium!!.value, 0.1)
    }

    // An EU label: one per-100 g column and a %RI one
    @Test
    fun aPer100gColumnWithReferenceIntakeIsNotMisread() {
        val draft = parser.parseText(
            """
            Nutrition Information
            Per 100g %RI*
            Energy 1046kJ/250kcal 13 %
            Fat 9.2g 13 %
            Salt 0.8g 13 %
            """.trimIndent()
        )

        assertEquals(9.2, draft.fat!!.value, 0.0)
        assertEquals(0.8, draft.salt!!.value, 0.0)
        assertNull(draft.perServingValues["fat"])
    }

    // ---------------------------------------------------------------------------------------
    // BUG-045: "Calories from Fat"
    // ---------------------------------------------------------------------------------------

    private val legacyUsLabel = """
        Nutrition Facts
        Serving Size 2/3 cup (55g)
        Amount Per Serving
        Calories 230
        Calories from Fat 72
        % Daily Value*
        Total Fat 8g 12 %
        Saturated Fat 1g 5 %
        Sodium 160mg 7 %
        Total Carbohydrate 37g 12 %
        Protein 3g
        * Percent Daily Values are based on a 2,000 calorie diet.
        Calories: 2,000 2,500
        Total Fat Less than 65g 80g
    """.trimIndent()

    @Test
    fun caloriesFromFatOnItsOwnRowIsNotFatOrCalories() {
        assertLegacyUsValues(parser.parseText(legacyUsLabel))
    }

    // OCR often merges the two figures onto one row
    @Test
    fun caloriesFromFatOnTheCalorieRowIsNotFatOrCalories() {
        val merged = legacyUsLabel.replace("Calories 230\nCalories from Fat 72", "Calories 230 Calories from Fat 72")
        assertLegacyUsValues(parser.parseText(merged))
    }

    @Test
    fun theDailyValueFootnoteIsNeverTheCalorieValue() {
        val withoutCalories = legacyUsLabel.replace("Calories 230\nCalories from Fat 72\n", "")
        val draft = parser.parseText(withoutCalories)

        assertNull(draft.perServingValues["calories"])
        assertNull(draft.calories)
    }

    // The footnote's reference rows ("Dietary Fiber 25g 30g") are not this food's values
    @Test
    fun theFootnotesReferenceRowsAreNeverValues() {
        val label = legacyUsLabel + "\nCholesterol Less than 300mg 300mg\nDietary Fiber 25g 30g"
        val draft = parser.parseText(label)

        assertNull(draft.fiber)
        assertNull(draft.cholesterol)
        assertPerServing(draft, "fat", 8.0)
    }

    @Test
    fun theDailyValueHeaderIsNotTheFootnote() {
        assertFalse(LabelRowFilters.isDailyValueFootnote("% Daily Value*"))
        assertFalse(LabelRowFilters.isDailyValueFootnote("Calories 250 % Daily Value*"))
        assertTrue(LabelRowFilters.isDailyValueFootnote("* The % Daily Value (DV) tells you how much a nutrient in"))
        assertTrue(LabelRowFilters.isDailyValueFootnote("a serving of food contributes to a daily diet. 2,000 calories a day is used"))
        assertTrue(LabelRowFilters.isDailyValueFootnote("Calories: 2,000 2,500"))
        assertFalse(LabelRowFilters.isDailyValueFootnote("Sodium 12000mg 25000mg"))
    }

    // An OCR misread the strip cannot recognise is still never the fat row
    @Test
    fun aMisreadCaloriesFromFatRowIsNotFat() {
        val misread = legacyUsLabel.replace("Calories from Fat 72", "Calorles from Fat 72")
        assertPerServing(parser.parseText(misread), "fat", 8.0)
    }

    @Test
    fun caloriesFromFatWithTheNumberJoinedIsRemoved() {
        val joined = legacyUsLabel.replace("Calories from Fat 72", "Calories from Fat72")
        assertLegacyUsValues(parser.parseText(joined))
    }

    // ---------------------------------------------------------------------------------------
    // BUG-044: large gallery images
    // ---------------------------------------------------------------------------------------

    @Test
    fun aFiftyMegapixelPhotoIsDecodedAtAQuarterOfItsSize() {
        assertEquals(4, ScannerBitmapDecoder.calculateInSampleSize(8000, 6000, 2560))
        assertEquals(1, ScannerBitmapDecoder.calculateInSampleSize(2560, 1440, 2560))
        assertEquals(2, ScannerBitmapDecoder.calculateInSampleSize(2561, 100, 2560))
    }

    @Test
    fun runningOutOfMemoryShowsAMessageInsteadOfCrashing() {
        assertEquals(ScannerImage.Failed(GALLERY_TOO_LARGE), loadScannerImage { throw OutOfMemoryError("Failed to allocate") })
        assertEquals(ScannerImage.Failed(GALLERY_NOT_AN_IMAGE), loadScannerImage { null })
        assertEquals(ScannerImage.Failed(GALLERY_UNREADABLE), loadScannerImage { throw IOException("broken file") })
    }

    // ---------------------------------------------------------------------------------------
    // BUG-042: warnings
    // ---------------------------------------------------------------------------------------

    // The range check used to run only on low-confidence scans
    @Test
    fun anImpossibleValueIsCriticalEvenAtHighConfidence() {
        val draft = NutritionLabelDraft(
            detectedBasis = NutritionBasis.PER_100_G,
            calories = per100g(1700.0, "kcal"),
            protein = per100g(10.0, "g"),
            overallConfidence = ConfidenceLevel.HIGH
        )

        val assembled = assembleScanWarnings(draft, qualityWarnings = emptyList())

        assertTrue(assembled.criticalWarnings.any { it.contains("Calories exceed 950") })
        assertTrue(assembled.warnings.none { it.contains("Calories exceed 950") })
    }

    @Test
    fun aCalorieMacroMismatchIsOneCriticalWarning() {
        val draft = NutritionLabelDraft(
            detectedBasis = NutritionBasis.PER_100_G,
            calories = per100g(350.0, "kcal"),
            protein = per100g(10.0, "g"),
            carbs = per100g(20.0, "g"),
            fat = per100g(5.0, "g"),
            warnings = listOf("Sodium estimated from 1.0g Salt (400.0 mg)")
        )

        val assembled = assembleScanWarnings(draft, qualityWarnings = listOf("Image is slightly blurry"))

        assertEquals(1, assembled.criticalWarnings.count { it.contains("differ notably") })
        // Parser notes first, then the image-quality hints
        assertEquals(listOf("Sodium estimated from 1.0g Salt (400.0 mg)", "Image is slightly blurry"), assembled.warnings)
    }

    // A value of unknown basis is judged once the user says what it is per
    @Test
    fun anImpossibleValueOfUnknownBasisIsCaughtOnceItHasOne() {
        val draft = NutritionLabelDraft(detectedBasis = NutritionBasis.UNKNOWN, calories = ParsedNutrientValue(1700.0, "kcal"))

        assertTrue(assembleScanWarnings(draft, emptyList()).criticalWarnings.isEmpty())
        val chosen = withRangeWarnings(draft.withOutputBasis(NutritionBasis.PER_100_G))
        assertTrue(chosen.criticalWarnings.any { it.contains("Calories exceed 950") })
        assertTrue(chosen.calories!!.needsReview)
    }

    @Test
    fun perServingAndPer100mlValuesAreRangeChecked() {
        val drink = NutritionLabelDraft(detectedBasis = NutritionBasis.PER_100_ML, calories = ParsedNutrientValue(1700.0, "kcal"))
        val serving = NutritionLabelDraft(
            detectedBasis = NutritionBasis.PER_SERVING,
            servingGrams = 30.0,
            protein = ParsedNutrientValue(40.0, "g")
        )

        assertTrue(withRangeWarnings(drink).criticalWarnings.any { it.contains("Calories exceed 950") })
        // 40 g of protein in a 30 g serving is 133 g per 100 g
        assertTrue(withRangeWarnings(serving).criticalWarnings.any { it.contains("Protein exceeds 100g") })
    }

    // The custom food form now saves the scanned saturated fat, so it is checked like the others
    @Test
    fun saturatedFatIsRangeChecked_andMoreThanTotalFatIsCritical() {
        val misread = NutritionLabelDraft(
            detectedBasis = NutritionBasis.PER_100_G,
            fat = per100g(9.0, "g"),
            saturatedFat = per100g(15.0, "g")
        )
        val checked = withRangeWarnings(misread)
        assertEquals(1, checked.criticalWarnings.count { it == SATURATED_FAT_OVER_TOTAL_WARNING })
        assertTrue(checked.saturatedFat!!.needsReview)
        // Switching the basis does not repeat the warning
        assertEquals(1, withRangeWarnings(checked).criticalWarnings.count { it == SATURATED_FAT_OVER_TOTAL_WARNING })

        val impossible = NutritionLabelDraft(detectedBasis = NutritionBasis.PER_100_G, saturatedFat = per100g(150.0, "g"))
        assertTrue(withRangeWarnings(impossible).criticalWarnings.any { it.contains("Saturated fat exceeds 100g") })

        val fine = NutritionLabelDraft(
            detectedBasis = NutritionBasis.PER_100_G,
            fat = per100g(9.0, "g"),
            saturatedFat = per100g(9.0, "g")
        )
        assertTrue(withRangeWarnings(fine).criticalWarnings.isEmpty())
        assertFalse(withRangeWarnings(fine).saturatedFat!!.needsReview)
    }

    // ---------------------------------------------------------------------------------------
    // BUG-041: which number belongs to the column
    // ---------------------------------------------------------------------------------------

    // With no basis on the label, a second number is more likely a %DV that lost its sign
    @Test
    fun withNoBasisASecondNumberIsNotAPerServingValue() {
        val draft = parser.parseText("Nutrition Facts\nSodium 160mg 7")

        assertNull(draft.perServingValues["sodium"])
    }

    // The parser's own "impossible calories" note is critical, and not repeated by the range check
    @Test
    fun theParsersImpossibleCaloriesWarningIsCriticalAndNotRepeated() {
        val draft = parser.parseText("Nutrition\nPer 100g\nEnergy 1046\nProtein 8 g\nFat 4 g")

        val assembled = assembleScanWarnings(draft, qualityWarnings = emptyList())

        assertEquals(1, assembled.criticalWarnings.count { it.contains("kcal", ignoreCase = true) })
        assertTrue(assembled.criticalWarnings.any { it.contains("more than any food contains") })
        assertFalse(assembled.warnings.any { it.contains("more than any food contains") })
    }

    // ---------------------------------------------------------------------------------------
    // BUG-043: crop-pass coordinates
    // ---------------------------------------------------------------------------------------

    @Test
    fun theCropOffsetMovesEveryBox() {
        val element = OcrElement(text = "5g", spatialBounds = box(10, 20, 30, 40))
        val line = OcrLine(text = "Protein 5g", elements = listOf(element), spatialBounds = box(0, 20, 30, 40))
        val ocr = OcrResult("Protein 5g", listOf(OcrBlock(text = "Protein 5g", lines = listOf(line), spatialBounds = box(0, 20, 30, 40))), listOf(line))

        val moved = ocr.offsetBy(100, 300)

        assertEquals(box(100, 320, 130, 340), moved.lines.single().spatialBounds)
        assertEquals(box(110, 320, 130, 340), moved.lines.single().elements.single().spatialBounds)
        assertEquals(box(100, 320, 130, 340), moved.blocks.single().spatialBounds)
        assertEquals(box(110, 320, 130, 340), moved.blocks.single().lines.single().elements.single().spatialBounds)
    }

    // The crop's rows, moved to where they sit in the image, merge with the full pass's rows
    @Test
    fun aCropPassAddsNoDuplicateRows() {
        val scorer = OcrEnsembleScorer()
        val fullRows = listOf("Nutrition Facts" to 100, "Calories 230" to 500, "Protein 5g" to 540, "Total Fat 8g" to 580)
        val full = scorer.scorePass(1, "A", "Full", ocrOf(fullRows, dx = 0, dy = 0))
        // The table crop starts at (100, 450): its own rows begin near the top of the crop
        val cropRows = listOf("Calories 230" to 50, "Protein 5g" to 90, "Total Fat 8g" to 130)
        val crop = scorer.scorePass(2, "TableCrop", "TargetedTableCrop", ocrOf(cropRows, dx = 0, dy = 0).offsetBy(100, 450))

        val merged = scorer.buildEnsemble(listOf(full, crop)).unifiedLines

        assertEquals(listOf("Nutrition Facts", "Calories 230", "Protein 5g", "Total Fat 8g"), merged.map { it.text })
    }

    private fun box(l: Int, t: Int, r: Int, b: Int) = NutritionLabelParser.SpatialBox(l, t, r, b)

    private fun ocrOf(rows: List<Pair<String, Int>>, dx: Int, dy: Int): OcrResult {
        val lines = rows.map { (text, top) ->
            OcrLine(text = text, spatialBounds = box(dx + 20, dy + top, dx + 20 + text.length * 15, dy + top + 20))
        }
        return OcrResult(rows.joinToString("\n") { it.first }, emptyList(), lines)
    }

    private fun per100g(value: Double, unit: String) =
        ParsedNutrientValue(value = value, unit = unit, basis = NutritionBasis.PER_100_G, confidence = ConfidenceLevel.HIGH)

    private fun assertLegacyUsValues(draft: NutritionLabelDraft) {
        assertPerServing(draft, "calories", 230.0)
        assertPerServing(draft, "fat", 8.0)
        assertPerServing(draft, "saturatedFat", 1.0)
        assertPerServing(draft, "sodium", 160.0)
        assertPerServing(draft, "carbs", 37.0)
        assertPerServing(draft, "protein", 3.0)
    }

    private fun assertPerServing(draft: NutritionLabelDraft, key: String, expected: Double) {
        val value = draft.perServingValues[key]?.value
        assertEquals("$key per serving (per100g=${draft.per100gValues[key]?.value})", expected, value ?: Double.NaN, 0.0)
    }
}
