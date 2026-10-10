package com.macrobase.app.feature.scanner

import android.graphics.Rect
import com.macrobase.app.domain.model.ServingUnit
import com.macrobase.app.domain.model.scanner.ConfidenceLevel
import com.macrobase.app.domain.model.scanner.NutritionBasis
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P2 phase 3: BUG-019 (kJ read as kcal), BUG-020 (unknown basis assumed per 100 g),
 * BUG-021 (per-serving values relabelled per 100 g), BUG-022 (thousands separators read as decimals).
 */
class P2PhaseThreeScannerTests {

    private val parser = NutritionLabelParser()

    private fun makeLine(text: String, left: Int, top: Int, right: Int, bottom: Int): OcrLine {
        val words = Regex("\\S+").findAll(text).toList()
        val charWidth = (right - left).toFloat() / text.length.coerceAtLeast(1)
        val elements = words.map { m ->
            val wLeft = (left + m.range.first * charWidth).toInt()
            val wRight = (left + (m.range.last + 1) * charWidth).toInt()
            OcrElement(
                text = m.value,
                boundingBox = Rect(wLeft, top, wRight, bottom),
                spatialBounds = NutritionLabelParser.SpatialBox(wLeft, top, wRight, bottom)
            )
        }
        return OcrLine(
            text = text,
            boundingBox = Rect(left, top, right, bottom),
            spatialBounds = NutritionLabelParser.SpatialBox(left, top, right, bottom),
            elements = elements
        )
    }

    private fun parseTable(vararg lines: OcrLine) =
        parser.parse(OcrResult(fullText = lines.joinToString("\n") { it.text }, blocks = emptyList(), lines = lines.toList()))

    // ---------------------------------------------------------------------------------------
    // BUG-019: kilojoules are never stored as kilocalories
    // ---------------------------------------------------------------------------------------

    @Test
    fun euTableWithKjAndKcalOnTheEnergyRowUsesKcal() {
        val draft = parseTable(
            makeLine("NUTRITION INFORMATION", 50, 20, 600, 50),
            makeLine("Serving size: 50g", 50, 60, 300, 90),
            makeLine("Typical values Per 100g Per serving (50g)", 50, 100, 700, 130),
            makeLine("Energy 1046 kJ / 250 kcal | 523 kJ / 125 kcal", 50, 140, 700, 170),
            makeLine("Fat 10.0g 5.0g", 50, 180, 700, 210),
            makeLine("Carbohydrate 30.0g 15.0g", 50, 220, 700, 250),
            makeLine("Protein 9.0g 4.5g", 50, 260, 700, 290),
            makeLine("Sodium 1,580mg 790mg", 50, 300, 700, 330)
        )

        assertEquals(NutritionBasis.PER_100_G, draft.detectedBasis)
        assertEquals(250.0, draft.calories!!.value, 0.01)
        assertEquals("kcal", draft.calories!!.unit)
        assertEquals(125.0, draft.perServingValues["calories"]!!.value, 0.01)
        // BUG-022 in the same table: the thousands separator is not a decimal point
        assertEquals(1580.0, draft.sodium!!.value, 0.01)
    }

    @Test
    fun anzTableInKilojoulesOnlyIsConvertedToKcal() {
        // The golden test06 label
        val draft = parseTable(
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

        val calories = draft.calories!!
        assertEquals(406.3, calories.value, 0.1) // 1700 kJ / 4.184
        assertEquals("kcal", calories.unit)
        assertTrue(calories.isEstimated)
        assertEquals(1700.0, calories.sourceValue!!, 0.01)
        assertEquals("kJ", calories.sourceUnit)
        assertEquals(162.5, draft.perServingValues["calories"]!!.value, 0.1) // 680 kJ / 4.184
        assertTrue(draft.warnings.any { it.contains("converted from 1700.0 kJ") })
    }

    @Test
    fun singleColumnEnergyRowWithKjBeforeKcalUsesKcal() {
        val draft = parser.parseText(
            """
            Nutrition Information
            Per 100g
            Energy 1046 kJ 250 kcal
            Protein 8 g
            Carbohydrate 45 g
            Fat 4 g
            """.trimIndent()
        )

        assertEquals(250.0, draft.calories!!.value, 0.01)
    }

    @Test
    fun energyRowWithUnitsInTheLabelAndPairedFigures() {
        val draft = parser.parseText("Nutrition\nPer 100g\nEnergy (kJ/kcal) 1046/250\nProtein 8 g\nFat 4 g")

        assertEquals(250.0, draft.calories!!.value, 0.01)
    }

    @Test
    fun percentageAfterTheKilojoulesIsNotTheKcalFigure() {
        val draft = parser.parseText("Nutrition\nPer 100g\nEnergy 1046 kJ (12%)\nProtein 8 g\nFat 4 g")

        assertEquals(250.0, draft.calories!!.value, 0.2) // converted from 1046 kJ, never 12
    }

    @Test
    fun kilojoulesSplitByOcrAreStillKilojoules() {
        val draft = parseTable(
            makeLine("NUTRITION INFORMATION", 50, 20, 600, 50),
            makeLine("Serving size: 50g", 50, 60, 300, 90),
            makeLine("Typical values Per 100g Per serving (50g)", 50, 100, 700, 130),
            makeLine("Energy 1046 k J / 250 kcal | 523 k J / 125 kcal", 50, 140, 700, 170),
            makeLine("Fat 10.0g 5.0g", 50, 180, 700, 210),
            makeLine("Protein 9.0g 4.5g", 50, 220, 700, 250)
        )

        assertEquals(250.0, draft.calories!!.value, 0.01)
        assertEquals(125.0, draft.perServingValues["calories"]!!.value, 0.01)
    }

    @Test
    fun splitKilojouleUnitOnASingleColumnLabel() {
        // One kJ/kcal pair and no "kJ" word on the row: only the joined "k J" marks 1046 as kJ
        val draft = parser.parseText("Nutrition\nPer 100g\nEnergy 1046 k J 250 kcal\nProtein 8 g\nFat 4 g")

        assertEquals(250.0, draft.calories!!.value, 0.01)
    }

    @Test
    fun unitsOnlyInTheColumnHeaderAreToldApartBySize() {
        val draft = parseTable(
            makeLine("NUTRITION INFORMATION", 50, 20, 600, 50),
            makeLine("Serving size: 50g", 50, 60, 300, 90),
            makeLine("Typical values Per 100g Per serving (50g)", 50, 100, 700, 130),
            makeLine("Energy 1046 250 523 125", 50, 140, 700, 170),
            makeLine("Fat 10.0g 5.0g", 50, 180, 700, 210),
            makeLine("Protein 9.0g 4.5g", 50, 220, 700, 250)
        )

        assertEquals(250.0, draft.calories!!.value, 0.01)
        assertEquals(125.0, draft.perServingValues["calories"]!!.value, 0.01)
    }

    @Test
    fun kcalColumnsThatHappenToDifferFourfoldAreNotTakenForKilojoules() {
        // 420 kcal per 100 g, 105 kcal per 25 g serving: the ratio is 4.0, but both are kcal
        val draft = parseTable(
            makeLine("NUTRITION INFORMATION", 50, 20, 600, 50),
            makeLine("Serving size: 25g", 50, 60, 300, 90),
            makeLine("Typical values Per 100g Per serving (25g)", 50, 100, 700, 130),
            makeLine("Energy 420 105", 50, 140, 700, 170),
            makeLine("Fat 20.0g 5.0g", 50, 180, 700, 210),
            makeLine("Protein 9.0g 2.3g", 50, 220, 700, 250)
        )

        assertEquals(420.0, draft.calories!!.value, 0.01)
        assertEquals(105.0, draft.perServingValues["calories"]!!.value, 0.01)
    }

    @Test
    fun implausibleCaloriesPer100gAreFlaggedForReview() {
        // A kJ figure printed without its unit
        val draft = parser.parseText("Nutrition\nPer 100g\nEnergy 1046\nProtein 8 g\nFat 4 g")

        assertTrue(draft.calories!!.needsReview)
        assertTrue(draft.warnings.any { it.contains("more than any food contains") })
    }

    @Test
    fun energyUnitsFollowTheWordsPrintedNextToTheNumbers() {
        val units = NutritionNumericParser.energyUnits(
            "Energy 1046 kJ / 250 kcal",
            listOf(
                NutritionNumericParser.EnergyNumber(unit = null, nextText = "kJ"),
                NutritionNumericParser.EnergyNumber(unit = null, nextText = "kcal")
            )
        )
        assertEquals(listOf("kj", "kcal"), units)

        // Units only in the row label, numbers paired in one word
        val paired = NutritionNumericParser.energyUnits(
            "Energy kJ/kcal 1046/250",
            listOf(
                NutritionNumericParser.EnergyNumber(unit = null, nextText = null, indexInWord = 0, numbersInWord = 2),
                NutritionNumericParser.EnergyNumber(unit = null, nextText = null, indexInWord = 1, numbersInWord = 2)
            )
        )
        assertEquals(listOf("kj", "kcal"), paired)

        // A US "Calories 230" row stays as printed
        assertEquals(listOf<String?>(null), NutritionNumericParser.energyUnits("Calories 230", listOf(NutritionNumericParser.EnergyNumber(null, null))))
    }

    // ---------------------------------------------------------------------------------------
    // BUG-020: a label that does not say "per 100 g" is not treated as per 100 g
    // ---------------------------------------------------------------------------------------

    @Test
    fun canadianPerCupLabelIsPerServingNotPer100g() {
        val draft = parser.parseText(
            """
            Nutrition Facts
            Per 1 cup (250 mL)
            Calories 110
            Fat 0 g
            Carbohydrate 26 g
            Protein 2 g
            Sodium 10 mg
            """.trimIndent()
        )

        assertNotEquals(NutritionBasis.PER_100_G, draft.detectedBasis)
        assertEquals(NutritionBasis.PER_SERVING, draft.detectedBasis)
        assertNull(draft.servingGrams)
        assertEquals(1.0, draft.servingSize!!, 0.0)
        assertEquals(ServingUnit.CUP, draft.servingUnit)
        assertEquals(110.0, draft.calories!!.value, 0.01)

        // Saved as "1 cup = 110 kcal", never "100 g = 110 kcal"
        val applied = draft.withOutputBasis(NutritionBasis.PER_100_G)
        assertEquals(NutritionBasis.PER_SERVING, applied.detectedBasis)
        assertEquals(ServingUnit.CUP, applied.servingUnit)
        assertEquals(110.0, applied.calories!!.value, 0.01)
    }

    @Test
    fun ukPerBarHeaderWithGramsIsNormalisedTo100g() {
        val draft = parser.parseText(
            """
            Nutrition
            Per bar (40g)
            Energy 460 kJ / 110 kcal
            Fat 4.0 g
            Carbohydrate 15.0 g
            Protein 3.0 g
            """.trimIndent()
        )

        assertEquals(NutritionBasis.PER_SERVING, draft.sourceBasis)
        assertEquals(NutritionBasis.PER_100_G, draft.detectedBasis)
        assertEquals(40.0, draft.servingGrams!!, 0.0)
        assertEquals(275.0, draft.calories!!.value, 0.1) // 110 * 100 / 40
        assertEquals(10.0, draft.fat!!.value, 0.1) // 4 * 100 / 40
    }

    @Test
    fun servingSizeInMillilitresIsNeverUsedAsGrams() {
        // US beverage wording; 250 mL must not become 250 g
        val draft = parser.parseText("Nutrition Facts\nServing size 1 cup (250 mL)\nCalories 110\nProtein 2 g")

        assertNull(draft.servingGrams)
        assertEquals(NutritionBasis.PER_SERVING, draft.detectedBasis)
        assertEquals(110.0, draft.calories!!.value, 0.01)
        val applied = draft.withOutputBasis(draft.defaultOutputBasis!!)
        assertEquals(1.0, applied.servingSize!!, 0.0)
        assertEquals(ServingUnit.CUP, applied.servingUnit)
        assertEquals(110.0, applied.calories!!.value, 0.01)
    }

    @Test
    fun drinkPer100mlIsSavedFor100ml() {
        val draft = parser.parseText("Nutrition\nper 100 ml\nEnergy 42 kcal\nCarbohydrate 10.6 g")

        assertEquals(listOf(NutritionBasis.PER_100_ML), draft.outputBasisOptions)
        for (requested in listOf(NutritionBasis.PER_100_G, NutritionBasis.PER_1_G, NutritionBasis.PER_100_ML)) {
            val applied = draft.withOutputBasis(requested)
            assertEquals(100.0, applied.servingSize!!, 0.0)
            assertEquals(ServingUnit.MILLILITERS, applied.servingUnit)
            assertEquals(42.0, applied.calories!!.value, 0.01)
        }
    }

    @Test
    fun columnHeaderWithoutPerStillSetsPer100g() {
        val draft = parser.parseText(
            """
            Typical values 100 g Serving 30 g
            Energy 400 kcal 120 kcal
            Protein 10 g 3 g
            """.trimIndent()
        )

        assertEquals(NutritionBasis.PER_100_G, draft.detectedBasis)
        assertEquals(400.0, draft.calories!!.value, 0.01)
    }

    @Test
    fun fractionalAndFluidOunceServingSizes() {
        val half = parser.parseText("Nutrition Facts\nServing size 1/2 bar\nCalories 95\nProtein 7 g")
        assertEquals(0.5, half.servingSize!!, 0.0)

        val drink = parser.parseText("Nutrition Facts\nServing size 8 fl oz (240mL)\nCalories 110\nCarbohydrate 26 g")
        assertEquals(8.0, drink.servingSize!!, 0.0)
        assertEquals(ServingUnit.FLUID_OUNCE, drink.servingUnit)
        assertNull(drink.servingGrams)
    }

    @Test
    fun perServingHeaderInMillilitresStaysOneServing() {
        val draft = parser.parseText("Nutrition\nPer serving (250 ml)\nCalories 120\nProtein 3 g")

        assertEquals(NutritionBasis.PER_SERVING, draft.detectedBasis)
        assertNull(draft.servingGrams)
        // "serving" is not a gram unit: this is 1 serving, not "1 g = 120 kcal"
        assertEquals(ServingUnit.SERVING, draft.servingUnit)
        assertEquals(1.0, draft.servingSize!!, 0.0)
    }

    @Test
    fun labelThatDoesNotSayItsBasisLetsTheUserChoose() {
        val draft = parser.parseText(
            """
            Nutrition Information
            Energy 250 kcal
            Protein 10 g
            Carbohydrate 30 g
            Fat 10 g
            """.trimIndent()
        )

        assertEquals(NutritionBasis.UNKNOWN, draft.detectedBasis)
        assertNotEquals(ConfidenceLevel.HIGH, draft.overallConfidence)
        assertTrue(draft.calories!!.needsReview)
        assertNull(draft.calories!!.normalizedPer100g)
        assertTrue(draft.per100gValues.isEmpty())
        assertTrue(draft.warnings.any { it.contains("per 100 g or per serving") })

        assertNull(draft.defaultOutputBasis)
        assertEquals(listOf(NutritionBasis.PER_100_G, NutritionBasis.PER_SERVING), draft.outputBasisOptions)

        val as100g = draft.withOutputBasis(NutritionBasis.PER_100_G)
        assertEquals(100.0, as100g.servingSize!!, 0.0)
        assertEquals(ServingUnit.GRAMS, as100g.servingUnit)
        assertEquals(250.0, as100g.calories!!.value, 0.01)

        val asServing = draft.withOutputBasis(NutritionBasis.PER_SERVING)
        assertEquals(1.0, asServing.servingSize!!, 0.0)
        assertEquals(ServingUnit.SERVING, asServing.servingUnit)
        assertEquals(250.0, asServing.calories!!.value, 0.01)
    }

    @Test
    fun per100gWrittenInOtherLanguagesIsStillPer100g() {
        for (header in listOf("pour 100 g", "pro 100 g", "je 100 g", "por 100 g", "Typical values 100g contains", "kJ/100g")) {
            val draft = parser.parseText("Nutrition\n$header\nEnergy 300 kcal\nProtein 10 g")
            assertEquals(header, NutritionBasis.PER_100_G, draft.detectedBasis)
        }
        val drink = parser.parseText("Nutrition\nper 100 ml\nEnergy 42 kcal\nCarbohydrate 10.6 g")
        assertEquals(NutritionBasis.PER_100_ML, drink.detectedBasis)
    }

    // ---------------------------------------------------------------------------------------
    // BUG-021: per-serving values without a gram weight stay per serving
    // ---------------------------------------------------------------------------------------

    @Test
    fun barWithoutGramWeightIsSavedPerServing() {
        val draft = parser.parseText(
            """
            Nutrition Facts
            Serving size 1 bar
            Calories 190
            Protein 15g
            Total Fat 7g
            """.trimIndent()
        )

        assertFalse(draft.canConvertToPer100g)
        assertEquals(listOf(NutritionBasis.PER_SERVING), draft.outputBasisOptions)
        assertEquals(NutritionBasis.PER_SERVING, draft.defaultOutputBasis)

        for (requested in listOf(NutritionBasis.PER_100_G, NutritionBasis.PER_1_G, NutritionBasis.PER_SERVING)) {
            val applied = draft.withOutputBasis(requested)
            // Never "100 g = 190 kcal": logging a 40 g bar would then record 76 kcal
            assertEquals(requested.name, NutritionBasis.PER_SERVING, applied.detectedBasis)
            assertEquals(1.0, applied.servingSize!!, 0.0)
            assertEquals(ServingUnit.SERVING, applied.servingUnit)
            assertEquals(190.0, applied.calories!!.value, 0.01)
            assertEquals(15.0, applied.protein!!.value, 0.01)
        }
    }

    @Test
    fun servingWithGramWeightStillOffersPer100gAndPer1g() {
        val draft = parser.parseText(
            """
            NUTRITION FACTS
            Serving Size: 32g
            Approx. Values Per Serving
            Energy: 160 kcal
            Protein: 9.6 g
            """.trimIndent()
        )

        assertTrue(draft.canConvertToPer100g)
        assertEquals(listOf(NutritionBasis.PER_100_G, NutritionBasis.PER_1_G), draft.outputBasisOptions)
        assertEquals(500.0, draft.withOutputBasis(NutritionBasis.PER_100_G).calories!!.value, 0.1)

        // Per serving from a normalised draft: the printed serving and its values
        val perServing = draft.withOutputBasis(NutritionBasis.PER_SERVING)
        assertEquals(32.0, perServing.servingSize!!, 0.0)
        assertEquals(ServingUnit.GRAMS, perServing.servingUnit)
        assertEquals(160.0, perServing.calories!!.value, 0.1)
    }

    // ---------------------------------------------------------------------------------------
    // BUG-022: thousands separators are not decimal points
    // ---------------------------------------------------------------------------------------

    @Test
    fun sodiumWithAThousandsSeparator() {
        val draft = parser.parseText("Serving size 1 cup (240g)\nSodium 1,580mg 69%")

        assertEquals(658.3, draft.sodium!!.value, 1.0) // 1580 mg * 100 / 240 g
    }

    @Test
    fun kilojoulesWithAThousandsSeparator() {
        val draft = parser.parseText("Energy 1,046 kJ\nProtein 8 g\nCarbohydrate 45 g\nFat 4 g")

        assertEquals(250.0, draft.calories!!.value, 0.2) // 1046 kJ / 4.184
    }

    @Test
    fun decimalCommasStillRead() {
        val draft = parser.parseText(
            """
            Valeurs pour 100g
            Energie 350,5 kcal
            Lipides 1,500 g
            Glucides 62,8 g
            Proteines 12,5 g
            Sodium 1,200 mg
            """.trimIndent()
        )

        assertEquals(350.5, draft.calories!!.value, 0.01)
        assertEquals(1.5, draft.fat!!.value, 0.001) // grams: a decimal comma
        assertEquals(62.8, draft.carbs!!.value, 0.01)
        assertEquals(12.5, draft.protein!!.value, 0.01)
        assertEquals(1200.0, draft.sodium!!.value, 0.01) // milligrams: thousands
    }

    @Test
    fun parseDecimalReadsTheSeparatorByUnit() {
        assertEquals(1580.0, NutritionDigitCorrector.parseDecimal("1,580", "mg")!!, 0.0)
        assertEquals(1046.0, NutritionDigitCorrector.parseDecimal("1,046", "kj")!!, 0.0)
        assertEquals(1250.0, NutritionDigitCorrector.parseDecimal("1,250", "kcal")!!, 0.0)
        assertEquals(1.5, NutritionDigitCorrector.parseDecimal("1,500", "g")!!, 0.0)
        assertEquals(1580.0, NutritionDigitCorrector.parseDecimal("1,580", null)!!, 0.0)
        assertEquals(0.125, NutritionDigitCorrector.parseDecimal("0,125", "mg")!!, 0.0)
        assertEquals(25.7, NutritionDigitCorrector.parseDecimal("25,7", "mg")!!, 0.0)
        assertEquals(1234567.0, NutritionDigitCorrector.parseDecimal("1,234,567", null)!!, 0.0)
        assertEquals(1234.5, NutritionDigitCorrector.parseDecimal("1,234.5", null)!!, 0.0)
        assertEquals(1234.5, NutritionDigitCorrector.parseDecimal("1.234,5", null)!!, 0.0)
    }

    @Test
    fun tokensReadThousandsAndMicrograms() {
        val sodium = NutritionNumericParser.extractTokens("1,580mg").single()
        assertEquals(1580.0, sodium.numericValue, 0.0)
        assertEquals("mg", sodium.unit)

        val vitaminD = NutritionNumericParser.extractTokens("5\u00B5g").single()
        assertEquals(5.0, vitaminD.numericValue, 0.0)
        assertEquals("\u00B5g", vitaminD.unit)
    }
}
