package com.macrobase.app.feature.scanner

import com.macrobase.app.domain.model.scanner.NutritionBasis
import com.macrobase.app.domain.model.scanner.NutritionLabelDraft
import com.macrobase.app.feature.scanner.WordLevelOcr.at
import com.macrobase.app.feature.scanner.WordLevelOcr.line
import com.macrobase.app.feature.scanner.WordLevelOcr.lines
import com.macrobase.app.feature.scanner.WordLevelOcr.linesPerCell
import com.macrobase.app.feature.scanner.WordLevelOcr.ocr
import com.macrobase.app.feature.scanner.WordLevelOcr.table
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The audit's scanner findings, on OCR shaped as ML Kit reports it (see [WordLevelOcr]). Each test
 * goes through parse(), so a value only counts if the app's real path produces it.
 */
class AuditScannerTests {

    private val parser = NutritionLabelParser()

    private fun parse(result: OcrResult): NutritionLabelDraft = parser.parse(result)

    private fun assertValue(expected: Double, actual: Double?, name: String) {
        assertNotNull("$name was not read", actual)
        assertEquals(name, expected, actual!!, 0.01)
    }

    // ---------------------------------------------------------------------------------------
    // The fixture itself: the existing golden label, replayed one word per element
    // ---------------------------------------------------------------------------------------

    @Test
    fun theProteinPowderLabelReadsTheSameWordByWord() {
        val columns = listOf(20, 420, 600, 780)
        val draft = parse(
            ocr(
                listOf(
                    line(10, "TRUE.TRUSTED.TESTED" at 100),
                    line(50, "INGREDIENTS: Plant Protein Blend 84.5% (Pea Protein, Rice Protein), Cocoa Powder" at 20),
                    line(90, "NUTRITION FACTS (Approx.)" at 20),
                    line(130, "Serving Size : 30g" at 20, "Per" at 420, "Per Serving" at 600, "Per Serving" at 780),
                    line(170, "Total Servings : 16 (Approx.)" at 20, "100g" at 420, "(30g)" at 600, "*RDA%" at 780)
                ) + table(
                    columns,
                    listOf(
                        listOf("Energy (Kcal)", "423 kcal", "127 kcal", "6.4%"),
                        listOf("Protein (g)", "73.7 g", "22.1 g", "40.9%"),
                        listOf("Carbohydrates (g)", "14.8 g", "4.4 g", "**"),
                        listOf("Total Fat (g)", "7.7 g", "2.3 g", "3.5%"),
                        listOf("Saturated Fatty acids (g)", "2 g", "0.6 g", "**"),
                        listOf("Dietary Fibre (g)", "4.2 g", "1.3 g", "**"),
                        listOf("Total Sugar (g)", "7 g", "2.1 g", "**"),
                        listOf("Sodium (mg)", "540 mg", "162 mg", "5%")
                    ),
                    firstTop = 210
                )
            )
        )

        assertEquals(NutritionBasis.PER_100_G, draft.detectedBasis)
        assertValue(30.0, draft.servingGrams, "serving")
        assertValue(423.0, draft.calories?.value, "calories")
        assertValue(73.7, draft.protein?.value, "protein")
        assertValue(14.8, draft.carbs?.value, "carbs")
        assertValue(7.7, draft.fat?.value, "fat")
        assertValue(2.0, draft.saturatedFat?.value, "saturated fat")
        assertValue(4.2, draft.fiber?.value, "fiber")
        assertValue(7.0, draft.sugar?.value, "sugar")
        assertValue(540.0, draft.sodium?.value, "sodium")
        assertValue(22.1, draft.perServingValues["protein"]?.value, "protein per serving")
    }

    // ---------------------------------------------------------------------------------------
    // BUG-001: every number belongs to the word printed before it
    // ---------------------------------------------------------------------------------------

    private fun sideBySidePanel() = lines(
        "Nutrition Facts",
        "Serving size 1 bar (40g)",
        "Amount per serving",
        "Calories 220",
        "Total Fat 12g 15% Total Carbohydrate 15g 5%",
        "Saturated Fat 3g 15% Dietary Fiber 2g 7%",
        "Trans Fat 0g Total Sugars 9g",
        "Cholesterol 0mg 0% Protein 11g",
        "Sodium 120mg 5%"
    )

    @Test
    fun aSideBySidePanelGivesEachNutrientItsOwnNumber() {
        val perServing = parse(sideBySidePanel()).perServingValues

        assertValue(12.0, perServing["fat"]?.value, "fat")
        assertValue(15.0, perServing["carbs"]?.value, "carbs")
        assertValue(3.0, perServing["saturatedFat"]?.value, "saturated fat")
        assertValue(2.0, perServing["fiber"]?.value, "fiber")
        assertValue(0.0, perServing["transFat"]?.value, "trans fat")
        assertValue(9.0, perServing["sugar"]?.value, "sugars")
        assertValue(0.0, perServing["cholesterol"]?.value, "cholesterol")
        assertValue(11.0, perServing["protein"]?.value, "protein")
        assertValue(120.0, perServing["sodium"]?.value, "sodium")
    }

    @Test
    fun aSideBySidePanelScalesToPer100GFromTheRightNumbers() {
        val draft = parse(sideBySidePanel())

        assertEquals(NutritionBasis.PER_100_G, draft.detectedBasis)
        assertValue(550.0, draft.calories?.value, "calories")
        assertValue(27.5, draft.protein?.value, "protein")
        assertValue(37.5, draft.carbs?.value, "carbs")
        assertValue(30.0, draft.fat?.value, "fat")
        assertValue(22.5, draft.sugar?.value, "sugars")
    }

    @Test
    fun aValueReadFromASharedLineAsksForReview() {
        val draft = parse(sideBySidePanel())

        assertTrue(draft.protein!!.needsReview)
        assertTrue(draft.carbs!!.needsReview)
        // Sodium has its row to itself
        assertTrue(!draft.sodium!!.needsReview)
    }

    @Test
    fun aParagraphLabelIsReadWordByWord() {
        val draft = parse(lines("Nutrition per 100g: Energy 450 kcal, Protein 6.0g, Carbohydrate 60g, Fat 20g"))

        assertValue(450.0, draft.calories?.value, "calories")
        assertValue(6.0, draft.protein?.value, "protein")
        assertValue(60.0, draft.carbs?.value, "carbs")
        assertValue(20.0, draft.fat?.value, "fat")
    }

    @Test
    fun aNumberInsideTheLabelsBracketsIsNotTheValue() {
        val draft = parse(lines("Nutrition Information per 100g", "Energy 380 kcal", "Protein (N x 6.25) 12.0g", "Fat 5g"))

        assertValue(12.0, draft.protein?.value, "protein")
    }

    @Test
    fun aValuePrintedInBracketsIsStillAValue() {
        val draft = parse(lines("Nutrition Information per 100g", "Energy 380 kcal", "Sodium (120mg)"))

        assertValue(120.0, draft.sodium?.value, "sodium")
    }

    @Test
    fun ofWhichLinesKeepTheirTwoValuesApart() {
        val draft = parse(lines("Nutrition per 100g", "Energy 400 kcal", "Fat 12 g of which saturates 3 g", "Carbohydrate 60 g of which sugars 5 g"))

        assertValue(12.0, draft.fat?.value, "fat")
        assertValue(3.0, draft.saturatedFat?.value, "saturates")
        assertValue(60.0, draft.carbs?.value, "carbs")
        assertValue(5.0, draft.sugar?.value, "sugars")
    }

    @Test
    fun aNumberPrintedBeforeItsWordStillCounts() {
        val draft = parse(lines("Nutrition Facts", "Serving size 30g", "Calories 120", "Total Sugars 9g Includes 3g Added Sugars"))

        assertValue(9.0, draft.perServingValues["sugar"]?.value, "sugars")
        assertValue(3.0, draft.perServingValues["addedSugar"]?.value, "added sugars")
    }

    // ---------------------------------------------------------------------------------------
    // BUG-012: ingredients above the table
    // ---------------------------------------------------------------------------------------

    @Test
    fun anIngredientsLineAboveTheTableDoesNotStopTheParse() {
        val draft = parse(
            lines(
                "High Protein Muesli",
                "Ingredients: Oats, Almonds, Honey",
                "Nutrition Information per 100g",
                "Energy 420 kcal",
                "Protein 20 g",
                "Carbohydrate 55 g",
                "Fat 12 g"
            )
        )

        assertValue(420.0, draft.calories?.value, "calories")
        assertValue(20.0, draft.protein?.value, "protein")
        assertValue(55.0, draft.carbs?.value, "carbs")
        assertValue(12.0, draft.fat?.value, "fat")
    }

    @Test
    fun ingredientsOnTheVeryFirstLineDoNotStopTheParse() {
        val draft = parse(
            lines(
                "INGREDIENTS: Whey Protein Concentrate, Cocoa",
                "Nutrition Facts per 100g",
                "Energy 380 kcal",
                "Protein 75 g"
            )
        )

        assertValue(380.0, draft.calories?.value, "calories")
        assertValue(75.0, draft.protein?.value, "protein")
    }

    @Test
    fun ingredientsBelowTheTableStillEndIt() {
        val draft = parse(
            lines(
                "Nutrition Information per 100g",
                "Energy 420 kcal",
                "Protein 20 g",
                "Ingredients: Sugar 40%, Fat 30%, Salt 2%"
            )
        )

        assertNull(draft.fat)
        assertNull(draft.sugar)
    }

    // ---------------------------------------------------------------------------------------
    // BUG-013: claim lines above the table
    // ---------------------------------------------------------------------------------------

    @Test
    fun aClaimAboveTheTableDoesNotTakeTheNutrient() {
        val draft = parse(
            lines(
                "High Protein Bar 20g Protein",
                "Nutrition Information Per 100g",
                "Energy 420 kcal",
                "Protein 33 g",
                "Carbohydrate 30 g",
                "Fat 15 g"
            )
        )

        assertValue(33.0, draft.protein?.value, "protein")
    }

    @Test
    fun aPercentClaimDoesNotBlankTheNutrient() {
        val draft = parse(
            lines(
                "100% Whey Protein",
                "Nutrition Facts",
                "Serving size 1 scoop (30g)",
                "Calories 120",
                "Protein 24g"
            )
        )

        assertValue(24.0, draft.perServingValues["protein"]?.value, "protein")
    }

    @Test
    fun fatAndSugarClaimsDoNotBlankThemEither() {
        val draft = parse(
            lines(
                "97% Fat Free 0% Added Sugar",
                "Nutrition Information per 100g",
                "Energy 90 kcal",
                "Fat 3 g",
                "Sugars 4 g"
            )
        )

        assertValue(3.0, draft.fat?.value, "fat")
        assertValue(4.0, draft.sugar?.value, "sugars")
    }

    @Test
    fun aValueAboveTheHeaderIsStillFoundWhenTheTableHasNone() {
        val draft = parse(lines("Protein 24g", "Nutrition Facts", "Serving size 30g", "Calories 120"))

        assertValue(24.0, draft.perServingValues["protein"]?.value, "protein")
    }

    // ---------------------------------------------------------------------------------------
    // BUG-014: "100" and "ml" are two words
    // ---------------------------------------------------------------------------------------

    @Test
    fun aDrinkWithASpacedHeaderReadsThePer100MlColumn() {
        val columns = listOf(20, 240, 340, 520)
        val draft = parse(
            ocr(
                table(
                    columns,
                    listOf(
                        listOf("Nutrition", "Per 100 ml", "Per Serve (200 ml)", "%RDA"),
                        listOf("Energy", "44", "88", "4%"),
                        listOf("Carbohydrate", "11 g", "22 g", "6%"),
                        listOf("Sugars", "10 g", "20 g", "")
                    )
                )
            )
        )

        assertEquals(NutritionBasis.PER_100_ML, draft.detectedBasis)
        assertValue(44.0, draft.calories?.value, "calories")
        assertValue(11.0, draft.carbs?.value, "carbs")
        assertValue(10.0, draft.sugar?.value, "sugars")
        assertValue(88.0, draft.perServingValues["calories"]?.value, "calories per serve")
    }

    @Test
    fun aSolidFoodWithASpacedHeaderKeepsThePrintedPer100Value() {
        val columns = listOf(20, 240, 340, 520)
        val draft = parse(
            ocr(
                table(
                    columns,
                    listOf(
                        listOf("Nutrients", "Per 100 g", "Per Serve (30 g)", "%RDA"),
                        listOf("Energy", "423 kcal", "127 kcal", "6%"),
                        listOf("Protein", "73.7 g", "22.1 g", "40.9%")
                    )
                )
            )
        )

        assertEquals(NutritionBasis.PER_100_G, draft.detectedBasis)
        assertValue(73.7, draft.protein?.value, "protein")
        assertValue(22.1, draft.perServingValues["protein"]?.value, "protein per serve")
    }

    @Test
    fun anAmountIsNotJoinedToAWordOnTheNextLine() {
        val elements = line(40, "Total 100" at 20).elements + line(80, "g" at 20).elements

        assertEquals(listOf("Total", "100", "g"), LabelRowFilters.withSplitAmountsJoined(elements).map { it.text })
    }

    @Test
    fun anAmountJoinsTheUnitBesideIt() {
        val joined = LabelRowFilters.withSplitAmountsJoined(line(40, "Per 100 ml" at 20).elements)

        assertEquals(listOf("Per", "100ml"), joined.map { it.text })
    }

    // ---------------------------------------------------------------------------------------
    // BUG-015: column anchors come from the header row
    // ---------------------------------------------------------------------------------------

    @Test
    fun servingSizeAboveTheTableIsNotAColumn() {
        val columns = listOf(20, 300, 460)
        val draft = parse(
            ocr(
                listOf(line(40, "Serving Size: 30g" at 20)) + table(
                    columns,
                    listOf(
                        listOf("Nutrient", "Per 100g", "%RDA"),
                        listOf("Energy (kcal)", "520", "26"),
                        listOf("Protein (g)", "7.0", "14"),
                        listOf("Fat (g)", "28", "36"),
                        listOf("Carbohydrate (g)", "60", "20")
                    ),
                    firstTop = 80
                )
            )
        )

        assertEquals(NutritionBasis.PER_100_G, draft.detectedBasis)
        assertValue(520.0, draft.calories?.value, "calories")
        assertValue(7.0, draft.protein?.value, "protein")
        assertValue(28.0, draft.fat?.value, "fat")
        assertValue(60.0, draft.carbs?.value, "carbs")
    }

    @Test
    fun aServingSuggestionUnderTheTableDoesNotInvertTheColumns() {
        val columns = listOf(20, 240, 400)
        val draft = parse(
            ocr(
                table(
                    columns,
                    listOf(
                        listOf("Nutrition", "Per 100g", "Per serving"),
                        listOf("Energy", "400 kcal", "120 kcal"),
                        listOf("Protein", "20 g", "6 g"),
                        listOf("Fat", "10 g", "3 g")
                    )
                ) + line(240, "Serving suggestion: with cold milk" at 20)
            )
        )

        assertValue(400.0, draft.calories?.value, "calories")
        assertValue(20.0, draft.protein?.value, "protein")
        assertValue(10.0, draft.fat?.value, "fat")
    }

    // ---------------------------------------------------------------------------------------
    // BUG-016: cells go by position
    // ---------------------------------------------------------------------------------------

    @Test
    fun aRowTheTableDoesNotModelKeepsItsNumbersToItself() {
        // Pitch 36 on 30-pixel boxes: Calcium's numbers are within reach of the Sodium row
        val draft = parse(
            ocr(
                line(40, "Nutrition" at 20, "Per 100g" at 240, "Per serving (30g)" at 400),
                line(80, "Energy" at 20, "400 kcal" at 240, "120 kcal" at 400),
                line(120, "Protein" at 20, "20 g" at 240, "6 g" at 400),
                line(160, "Fat" at 20, "10 g" at 240, "3 g" at 400),
                line(200, "Carbohydrate" at 20, "60 g" at 240, "18 g" at 400),
                line(300, "Sodium" at 20, "540mg" at 240, "162mg" at 400),
                line(336, "Calcium" at 20, "1200mg" at 200, "360mg" at 400)
            )
        )

        assertValue(540.0, draft.sodium?.value, "sodium")
    }

    @Test
    fun aMissedCellDoesNotShiftTheRowLeft() {
        val draft = parse(
            ocr(
                line(40, "Nutrition" at 20, "Per 100g" at 240, "Per serving (30g)" at 380, "%RDA" at 660),
                line(80, "Energy" at 20, "400 kcal" at 240, "120 kcal" at 380, "20%" at 660),
                line(120, "Fat" at 20, "10 g" at 240, "3 g" at 380, "5%" at 660),
                // OCR missed the per-100 cell, and the %RDA printed no sign
                line(160, "Protein" at 20, "6.0" at 380, "12" at 660)
            )
        )

        // 6.0 was read as the serving's figure, and 12 stayed the %RDA
        assertValue(6.0, draft.protein?.sourceValue, "protein as printed")
        assertValue(20.0, draft.protein?.value, "protein scaled from the serving")
        assertValue(12.0, draft.rdaValues["protein"], "%RDA")
    }

    // ---------------------------------------------------------------------------------------
    // BUG-017: the unit printed next to the number counts
    // ---------------------------------------------------------------------------------------

    private fun per100(row: String) = parse(lines("Nutrition Information per 100g", "Energy 380 kcal", row))

    @Test
    fun sodiumPrintedInGramsIsConverted() {
        assertValue(400.0, per100("Sodium 0.4g").sodium?.value, "sodium")
    }

    @Test
    fun aUnitInTheRowLabelCounts() {
        assertValue(400.0, per100("Sodium (g) 0.4").sodium?.value, "sodium")
    }

    @Test
    fun aUnitPrintedAsItsOwnWordCounts() {
        assertValue(400.0, per100("Sodium 0.4 g").sodium?.value, "sodium")
    }

    @Test
    fun fiberPrintedInMilligramsIsConverted() {
        assertValue(0.5, per100("Dietary Fiber 500 mg").fiber?.value, "fiber")
    }

    @Test
    fun saltPrintedInMilligramsIsConverted() {
        assertValue(0.45, per100("Salt 450 mg").salt?.value, "salt")
    }

    @Test
    fun ironPrintedInMicrogramsIsConverted() {
        assertValue(0.8, per100("Iron 800 mcg").iron?.value, "iron")
    }

    @Test
    fun theUnitAlsoCountsInATable() {
        val draft = parse(
            ocr(
                line(40, "Nutrition" at 20, "Per 100g" at 240, "Per serving (30g)" at 400),
                line(80, "Energy" at 20, "400 kcal" at 240, "120 kcal" at 400),
                line(120, "Sodium" at 20, "0.4 g" at 240, "0.12 g" at 400)
            )
        )

        assertValue(400.0, draft.sodium?.value, "sodium")
    }

    @Test
    fun aNumberInTheWrongKindOfUnitIsNotTheValue() {
        val draft = per100("Protein 5 ml 12 g")

        assertValue(12.0, draft.protein?.value, "protein")
    }

    // ---------------------------------------------------------------------------------------
    // BUG-041: nothing compares the OCR passes, so a tick on a clean row claimed a check no one ran
    // ---------------------------------------------------------------------------------------

    @Test
    fun aRowWithNothingToReviewClaimsNoCheck() {
        val path = "src/main/java/com/macrobase/app/feature/scanner/NutritionLabelScannerScreen.kt"
        val screen = listOf(File(path), File("app/$path")).first { it.isFile }.readText()

        assertFalse(screen.contains("Icons.Default.CheckCircle"))
        assertTrue("a row that needs review still says so", screen.contains("text = \"Check\""))
    }

    // ---------------------------------------------------------------------------------------
    // BUG-035: OCR corrections reached the rows but not the basis, the serving or the header
    // ---------------------------------------------------------------------------------------

    @Test
    fun aMisreadPer100LineIsStillPer100G() {
        val draft = parse(
            lines("Serving Size: 30g", "Approx. values Per 1OOg", "Energy 120 kcal", "Protein 5 g", "Carbohydrate 20 g", "Fat 2 g")
        )

        assertEquals(NutritionBasis.PER_100_G, draft.detectedBasis)
        assertNull("nothing was printed per serving", draft.normalizationNote)
        assertValue(120.0, draft.calories?.value, "calories")
        assertValue(5.0, draft.protein?.value, "protein")
    }

    @Test
    fun aMisreadPer100ColumnHeaderStillNamesItsColumn() {
        val draft = parse(
            ocr(
                listOf(
                    line(10, "Serving Size: 30g" at 20),
                    line(50, "Nutrients" at 20, "Per 1OOg" at 420, "Per Serving" at 600)
                ) + table(
                    listOf(20, 420, 600),
                    misreadHeaderRows,
                    firstTop = 90
                )
            )
        )

        assertEquals(NutritionBasis.PER_100_G, draft.detectedBasis)
        assertValue(400.0, draft.calories?.value, "calories")
        assertValue(9.6, draft.protein?.value, "protein")
        assertValue(2.9, draft.perServingValues["protein"]?.value, "protein per serving")
    }

    private val misreadHeaderRows = listOf(
        listOf("Energy (kcal)", "400", "120"),
        listOf("Protein (g)", "9.6", "2.9"),
        listOf("Carbohydrate (g)", "60.5", "18.2"),
        listOf("Total Fat (g)", "13.2", "4.0")
    )

    // With no gram weight the serving column cannot stand in: the misread header must still name its column
    @Test
    fun aMisreadPer100ColumnWithNoServingWeightKeepsItsOwnValues() {
        val draft = parse(
            ocr(
                listOf(
                    line(10, "Serving size 1 scoop" at 20),
                    line(50, "Nutrients" at 20, "Per 1OOg" at 420, "Per Serving" at 600)
                ) + table(listOf(20, 420, 600), misreadHeaderRows, firstTop = 90)
            )
        )

        assertEquals(NutritionBasis.PER_100_G, draft.detectedBasis)
        assertValue(9.6, draft.protein?.value, "protein")
        assertValue(2.9, draft.perServingValues["protein"]?.value, "protein per serving")
    }

    // Serving lines now pass through the correction: "1oz" is an ounce, not 10 z
    @Test
    fun anOunceServingKeepsItsSize() {
        assertEquals("Serving size 1oz (28g)", NutritionDigitCorrector.correctRowText("Serving size 1oz (28g)"))
        assertEquals("12oz", NutritionDigitCorrector.correctRowText("12oz"))

        val draft = parse(lines("Nutrition Facts", "Serving size 1oz (28g)", "Amount per serving", "Calories 150", "Total Fat 9g", "Protein 2g"))

        assertValue(28.0, draft.servingGrams, "serving grams")
        assertTrue(draft.normalizationNote.toString(), draft.normalizationNote!!.contains("1.0 (28.0g)"))
    }

    // ---------------------------------------------------------------------------------------
    // BUG-031: "per 100g" outside the table made per-serving values per 100 g
    // ---------------------------------------------------------------------------------------

    @Test
    fun aPer100PhraseBelowTheTableLeavesItsValuesPerServing() {
        val draft = parse(
            lines(
                "Nutrition Facts",
                "Serving size 1 scoop",
                "Amount per serving",
                "Calories 120",
                "Total Fat 1g 2%",
                "Protein 24g 48%",
                "Typical Amino Acid Profile per 100g of protein"
            )
        )

        assertEquals(NutritionBasis.PER_SERVING, draft.detectedBasis)
        assertEquals(listOf(NutritionBasis.PER_SERVING), draft.outputBasisOptions)
        assertValue(120.0, draft.calories?.value, "calories")
        assertValue(24.0, draft.protein?.value, "protein")
    }

    @Test
    fun aNetWeightOf100GIsNotAPer100Column() {
        val draft = parse(
            lines(
                "Nutrition Facts",
                "Net Wt. 100g",
                "Serving size 1 scoop",
                "Amount per serving",
                "Calories 120",
                "Total Fat 1g 2%",
                "Protein 24g 48%"
            )
        )

        assertEquals(NutritionBasis.PER_SERVING, draft.detectedBasis)
        assertEquals(listOf(NutritionBasis.PER_SERVING), draft.outputBasisOptions)
        assertValue(24.0, draft.protein?.value, "protein")
    }

    // A serving-only column with a gram weight: the per-serving map keeps the number printed
    @Test
    fun aServingOnlyColumnKeepsItsOwnNumberPerServing() {
        val draft = parse(
            ocr(
                listOf(
                    line(10, "Serving Size: 40g" at 20),
                    line(50, "Nutrients" at 20, "Per Serving" at 420, "%RDA" at 600)
                ) + table(
                    listOf(20, 420, 600),
                    listOf(
                        listOf("Energy (kcal)", "200", "10%"),
                        listOf("Protein (g)", "8", "16%"),
                        listOf("Carbohydrate (g)", "24", "9%"),
                        listOf("Total Fat (g)", "8", "12%")
                    ),
                    firstTop = 90
                )
            )
        )

        assertValue(20.0, draft.protein?.value, "protein per 100 g")
        assertValue(8.0, draft.perServingValues["protein"]?.value, "protein per serving")
        assertValue(8.0, draft.perServingValues["fat"]?.value, "fat per serving")
    }

    // ---------------------------------------------------------------------------------------
    // BUG-034: the column cross-check never ran on the spatial path
    // ---------------------------------------------------------------------------------------

    @Test
    fun aServingColumnThatDisprovesThePer100ValueIsFlagged() {
        val draft = parse(
            ocr(
                listOf(
                    line(10, "Serving Size: 30g" at 20),
                    line(50, "Nutrients" at 20, "Per 100g" at 420, "Per Serving" at 600)
                ) + table(
                    listOf(20, 420, 600),
                    listOf(
                        listOf("Energy (kcal)", "390", "117"),
                        listOf("Protein (g)", "10", "3"),
                        listOf("Carbohydrate (g)", "70", "21"),
                        // 7.7 read as 17.7: per serving it is 2.3, not 5.3
                        listOf("Total Fat (g)", "17.7", "2.3")
                    ),
                    firstTop = 90
                )
            )
        )

        assertValue(17.7, draft.fat?.value, "fat")
        assertTrue("fat should need review", draft.fat!!.needsReview)
        assertTrue(draft.warnings.toString(), draft.warnings.any { it.contains("fat", ignoreCase = true) && it.contains("serving") })
        assertFalse("protein agrees", draft.protein!!.needsReview)
    }

    // ---------------------------------------------------------------------------------------
    // The calories row's "cal" also started "Calcium"
    // ---------------------------------------------------------------------------------------

    @Test
    fun calciumIsNeverTheEnergyRow() {
        val draft = parse(
            ocr(
                listOf(
                    line(10, "Serving Size: 30g" at 20),
                    line(50, "Nutrients" at 20, "Per 100g" at 420, "Per Serving" at 600)
                ) + table(
                    listOf(20, 420, 600),
                    listOf(
                        listOf("Protein (g)", "10", "3"),
                        listOf("Carbohydrate (g)", "70", "21"),
                        listOf("Total Fat (g)", "7.7", "2.3"),
                        listOf("Calcium (mg)", "120", "36")
                    ),
                    firstTop = 90
                )
            )
        )

        assertNull("no energy row was printed", draft.calories)
        assertValue(120.0, draft.calcium?.value, "calcium")
    }

    // ---------------------------------------------------------------------------------------
    // Review of the fixes above
    // ---------------------------------------------------------------------------------------

    // BUG-031: amino acid tables word their basis many ways, over one line or two
    @Test
    fun anAminoAcidTableBelowSaysNothingAboutTheBasis() {
        val table = listOf("Nutrition Facts", "Serving size 1 scoop", "Amount per serving", "Calories 120", "Total Fat 1g 2%", "Protein 24g 48%")
        for (amino in listOf(listOf("Amino acids per 100 g protein"), listOf("Amino Acid Composition", "(per 100g of protein)"))) {
            val draft = parse(lines(*(table + amino).toTypedArray()))

            assertEquals(amino.toString(), NutritionBasis.PER_SERVING, draft.detectedBasis)
        }
    }

    // BUG-031: an ingredient paragraph with numbers ("sodium bicarbonate 500(ii)") and the maker's
    // address above the table, which puts its header past the first 15 rows
    @Test
    fun anIngredientParagraphAboveThePer100TableDoesNotHideItsHeader() {
        val draft = parse(
            lines(
                "INGREDIENTS: Wheat flour (62%), sugar,",
                "edible vegetable oil (palm), invert syrup,",
                "raising agents [sodium bicarbonate 500(ii)], salt,",
                "milk solids, emulsifier (soy lecithin)",
                "Allergens: Contains wheat, milk and soy",
                "Mfd. by: Good Bakes Pvt. Ltd.",
                "Plot 12, MIDC Industrial Area,",
                "Pune 411001, Maharashtra",
                "FSSAI Lic. No. 10012345000123",
                "Customer care: 1800 123 4567",
                "Best before 6 months from packing",
                "Store in a cool and dry place",
                "Net Qty: 200 g",
                "MRP Rs. 40 (incl. of all taxes)",
                "Batch No. and Pkd. on: see pack",
                "Made in India",
                "Nutrition Information per 100g",
                "Energy 480 kcal",
                "Protein 7 g",
                "Carbohydrate 68 g",
                "Total Fat 20 g"
            )
        )

        assertEquals(NutritionBasis.PER_100_G, draft.detectedBasis)
        assertValue(480.0, draft.calories?.value, "calories")
    }

    // BUG-031: a net weight of 100 g printed beside or above the per-serving header
    @Test
    fun aNetWeightNearThePerServingHeaderIsNotAPer100Column() {
        val layouts = listOf(
            listOf(line(50, "Serving size 1 scoop" at 20), line(90, "Amount per serving" at 20, "Net Wt. 100g" at 420)),
            listOf(line(50, "Net Wt. 100g" at 420), line(90, "Amount per serving" at 20))
        )
        for ((i, header) in layouts.withIndex()) {
            val draft = parse(
                ocr(
                    listOf(line(10, "Nutrition Facts" at 20)) + header + listOf(
                        line(130, "Calories" at 20, "120" at 420),
                        line(170, "Total Fat" at 20, "1g" at 420),
                        line(210, "Protein" at 20, "24g" at 420)
                    )
                )
            )

            assertEquals("layout $i", NutritionBasis.PER_SERVING, draft.detectedBasis)
            assertValue(24.0, draft.protein?.value, "layout $i protein")
        }
    }

    // BUG-034: a serving column that is per serving with milk disagrees everywhere. That is one note
    // about the column, not every per 100 g value marked wrong
    @Test
    fun aServingColumnWithMilkFlagsNoPer100Value() {
        val draft = parse(
            ocr(
                listOf(
                    line(10, "Serving Size: 30g" at 20),
                    line(50, "Nutrients" at 20, "Per 100g" at 420, "Per Serving*" at 600)
                ) + table(
                    listOf(20, 420, 600),
                    listOf(
                        listOf("Energy (kcal)", "380", "179"),
                        listOf("Protein (g)", "8", "6.6"),
                        listOf("Carbohydrate (g)", "80", "30.2"),
                        listOf("Total Fat (g)", "2", "2.7"),
                        listOf("Sugars (g)", "20", "12")
                    ),
                    firstTop = 90
                ) + listOf(line(330, "*30g with 125ml semi-skimmed milk" at 20))
            )
        )

        for (value in listOf(draft.calories, draft.protein, draft.carbs, draft.fat, draft.sugar)) {
            assertFalse(value.toString(), value!!.needsReview)
        }
        assertEquals(draft.warnings.toString(), 1, draft.warnings.count { it.contains("does not match") })
    }

    // A two-column row with only its serving cell: the per-serving map keeps the printed number
    @Test
    fun aRowWithOnlyAServingCellKeepsItsServingNumber() {
        val draft = parse(
            ocr(
                listOf(
                    line(10, "Serving Size: 30g" at 20),
                    line(50, "Nutrients" at 20, "Per 100g" at 420, "Per Serving" at 600)
                ) + table(
                    listOf(20, 420, 600),
                    listOf(
                        listOf("Energy (kcal)", "400", "120"),
                        listOf("Protein (g)", "10", "3"),
                        listOf("Carbohydrate (g)", "60", "18"),
                        listOf("Total Fat (g)", "13.3", "4"),
                        listOf("Sodium (mg)", "", "36")
                    ),
                    firstTop = 90
                )
            )
        )

        assertValue(36.0, draft.perServingValues["sodium"]?.value, "sodium per serving")
        assertValue(120.0, draft.sodium?.value, "sodium per 100 g")
    }

    // ---------------------------------------------------------------------------------------
    // BUG-033: a decimal comma in the serving ("12,5 g") was read as 5 g
    // ---------------------------------------------------------------------------------------

    @Test
    fun aServingSizeWithADecimalCommaIsReadWhole() {
        val draft = parse(lines("Serving size 12,5 g", "Energy 20 kcal", "Protein 2 g", "Carbohydrate 2 g"))

        assertValue(12.5, draft.servingGrams, "serving grams")
        assertValue(160.0, draft.calories?.value, "calories per 100 g")
        assertValue(16.0, draft.protein?.value, "protein per 100 g")
    }

    @Test
    fun aPortionWithADecimalCommaIsReadWhole() {
        val draft = parse(lines("Per portion (12,5 g)", "Energy 20 kcal", "Protein 2 g", "Carbohydrate 2 g"))

        assertValue(12.5, draft.servingGrams, "serving grams")
    }

    // ---------------------------------------------------------------------------------------
    // BUG-032: a kJ-only label printed per serving first took the serving's energy as per 100 g
    // ---------------------------------------------------------------------------------------

    private val kilojouleRows = listOf(
        listOf("Energy", "680 kJ", "1700 kJ"),
        listOf("Protein", "3.2 g", "8.0 g"),
        listOf("Fat", "4.8 g", "12.0 g"),
        listOf("Carbohydrate", "24.0 g", "60.0 g")
    )

    @Test
    fun aKilojouleLabelPrintedPerServingFirstReadsEachColumn() {
        val draft = parse(
            ocr(
                listOf(
                    line(10, "Serving size: 40 g" at 20),
                    line(50, "Quantity per Serving" at 360, "Quantity per 100 g" at 620)
                ) + table(listOf(20, 360, 620), kilojouleRows, firstTop = 90)
            )
        )

        assertEquals(NutritionBasis.PER_100_G, draft.detectedBasis)
        assertEquals(406.3, draft.calories!!.value, 0.1)
        assertEquals(162.5, draft.perServingValues["calories"]!!.value, 0.1)
        assertValue(8.0, draft.protein?.value, "protein")
    }

    // ML Kit often gives each cell of a wide table its own line
    @Test
    fun aKilojouleLabelReadAsOneLinePerCellReadsEachColumn() {
        val draft = parse(
            ocr(
                listOf(line(10, "Serving size: 40 g" at 20)) +
                    linesPerCell(50, "Quantity per Serving" at 360, "Quantity per 100 g" at 620) +
                    kilojouleRows.flatMapIndexed { i, row ->
                        linesPerCell(90 + i * WordLevelOcr.PITCH, row[0] at 20, row[1] at 360, row[2] at 620)
                    }
            )
        )

        assertEquals(406.3, draft.calories!!.value, 0.1)
        assertValue(8.0, draft.protein?.value, "protein")
    }
}
