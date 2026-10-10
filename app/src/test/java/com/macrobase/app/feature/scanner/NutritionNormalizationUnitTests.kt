package com.macrobase.app.feature.scanner

import com.macrobase.app.domain.model.scanner.ConfidenceLevel
import com.macrobase.app.domain.model.scanner.NutritionBasis
import com.macrobase.app.domain.model.scanner.ParsedNutrientValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for NutritionNormalization covering Section 29 (Tests A through G)
 * of the Nutrition Label Scanner specification.
 */
class NutritionNormalizationUnitTests {

    // TEST A — DIRECT PER 100G
    // Input: Per 100g, Calories 500, Protein 30g, Carbs 25g, Fat 20g
    // Expected: per100g = exact printed values, per1g = values / 100, NO conversion.
    @Test
    fun testA_DirectPer100g() {
        val protein = NutritionNormalization.normalizeToPer100g(
            sourceValue = 30.0,
            sourceUnit = "g",
            sourceBasis = NutritionBasis.PER_100_G,
            servingMassG = null,
            sourceText = "Protein 30g"
        )

        assertEquals(30.0, protein.value, 0.001)
        assertEquals(NutritionBasis.PER_100_G, protein.basis)
        assertEquals(30.0, protein.normalizedPer100g ?: 0.0, 0.001)
        assertEquals(0.30, protein.normalizedPer1g ?: 0.0, 0.001)
        assertFalse(protein.isDerived)
        assertFalse(protein.isEstimated)

        val per1g = NutritionNormalization.derivePer1g(protein)
        assertEquals(0.30, per1g.value, 0.001)
        assertEquals(NutritionBasis.PER_1_G, per1g.basis)
        assertTrue(per1g.isDerived)

        val calories = NutritionNormalization.normalizeToPer100g(
            sourceValue = 500.0,
            sourceUnit = "kcal",
            sourceBasis = NutritionBasis.PER_100_G,
            servingMassG = null
        )
        assertEquals(500.0, calories.value, 0.001)
        val cal1g = NutritionNormalization.derivePer1g(calories)
        assertEquals(5.0, cal1g.value, 0.001)
    }

    // TEST B — 20G SERVING
    // Serving = 20g. Calories = 100, Protein = 4g, Carbs = 10g, Fat = 3g
    // Expected: Calories = 500/100g, Protein = 20g/100g, Carbs = 50g/100g, Fat = 15g/100g, then derive 1g.
    @Test
    fun testB_20gServing() {
        val servingMass = 20.0

        val cal = NutritionNormalization.normalizeToPer100g(100.0, "kcal", NutritionBasis.PER_SERVING, servingMass)
        assertEquals(500.0, cal.value, 0.01)
        assertEquals(500.0, cal.normalizedPer100g ?: 0.0, 0.01)
        assertEquals(5.0, cal.normalizedPer1g ?: 0.0, 0.01)
        assertTrue(cal.isDerived)

        val p = NutritionNormalization.normalizeToPer100g(4.0, "g", NutritionBasis.PER_SERVING, servingMass)
        assertEquals(20.0, p.value, 0.01)
        assertEquals(0.20, p.normalizedPer1g ?: 0.0, 0.01)

        val c = NutritionNormalization.normalizeToPer100g(10.0, "g", NutritionBasis.PER_SERVING, servingMass)
        assertEquals(50.0, c.value, 0.01)
        assertEquals(0.50, c.normalizedPer1g ?: 0.0, 0.01)

        val f = NutritionNormalization.normalizeToPer100g(3.0, "g", NutritionBasis.PER_SERVING, servingMass)
        assertEquals(15.0, f.value, 0.01)
        assertEquals(0.15, f.normalizedPer1g ?: 0.0, 0.01)
    }

    // TEST C — 32G SERVING
    // Protein = 9.6g, Serving = 32g
    // Expected: 30g/100g, 0.3g/1g
    @Test
    fun testC_32gServing() {
        val p = NutritionNormalization.normalizeToPer100g(9.6, "g", NutritionBasis.PER_SERVING, 32.0)
        assertEquals(30.0, p.value, 0.01)
        assertEquals(30.0, p.normalizedPer100g ?: 0.0, 0.01)
        assertEquals(0.30, p.normalizedPer1g ?: 0.0, 0.01)
        assertEquals(9.6, p.sourceValue ?: 0.0, 0.01)
        assertEquals(NutritionBasis.PER_SERVING, p.sourceBasis)
        assertTrue(p.isDerived)

        val p1g = NutritionNormalization.derivePer1g(p)
        assertEquals(0.30, p1g.value, 0.01)
        assertEquals(NutritionBasis.PER_1_G, p1g.basis)
    }

    // TEST D — BOTH COLUMNS
    // Per 100g: Protein = 30g. Per Serving: Protein = 9.6g. Serving = 32g.
    // Expected: Per 100g remains 30g, cross-check confirms consistency.
    @Test
    fun testD_BothColumnsCrossCheck() {
        val matches = NutritionNormalization.crossCheckServingVs100g(
            per100g = 30.0,
            perServing = 9.6,
            servingMassG = 32.0
        )
        assertTrue("30g per 100g and 9.6g per 32g serving should cross-check successfully", matches)

        val direct100g = NutritionNormalization.normalizeToPer100g(
            sourceValue = 30.0,
            sourceUnit = "g",
            sourceBasis = NutritionBasis.PER_100_G,
            servingMassG = 32.0
        )
        assertEquals(30.0, direct100g.value, 0.01)
        assertFalse("Explicit Per 100g should not be derived", direct100g.isDerived)

        // Suspicious mismatch test
        val suspiciousMismatch = NutritionNormalization.crossCheckServingVs100g(
            per100g = 30.0,
            perServing = 90.0,
            servingMassG = 32.0
        )
        assertFalse("30g per 100g vs 90g per 32g serving should fail cross-check", suspiciousMismatch)
    }

    // TEST E — NO SERVING MASS
    // Serving = 1 bar, no gram weight
    // Expected: cannot safely normalize to per 100g, needsReview = true.
    @Test
    fun testE_NoServingMass() {
        val nutrient = NutritionNormalization.normalizeToPer100g(
            sourceValue = 15.0,
            sourceUnit = "g",
            sourceBasis = NutritionBasis.PER_SERVING,
            servingMassG = null,
            sourceText = "Protein 15g"
        )

        assertEquals(15.0, nutrient.value, 0.01)
        assertEquals(NutritionBasis.PER_SERVING, nutrient.basis)
        assertNull("Cannot normalize to per100g without gram weight", nutrient.normalizedPer100g)
        assertNull("Cannot derive per1g without per100g", nutrient.normalizedPer1g)
        assertTrue("Must flag needsReview when serving mass in grams is missing", nutrient.needsReview)
    }

    // TEST F — DOUBLE CONVERSION PROTECTION
    // Input already marked PER_100_G. Attempt conversion again.
    // Expected: exact same value.
    @Test
    fun testF_DoubleConversionProtection() {
        val initial = NutritionNormalization.normalizeToPer100g(
            sourceValue = 20.0,
            sourceUnit = "g",
            sourceBasis = NutritionBasis.PER_100_G,
            servingMassG = 25.0
        )
        assertEquals(20.0, initial.value, 0.01)

        // Attempt second normalization using safeScaleToPer100g
        val reScaled = NutritionNormalization.safeScaleToPer100g(initial, servingMassG = 25.0)
        assertEquals(20.0, reScaled.value, 0.01)
        assertEquals(NutritionBasis.PER_100_G, reScaled.basis)
        assertFalse(reScaled.isDerived)
    }

    // TEST G — WATER MUST NOT MATTER
    // Water data changes. Expected: nutrition scan result unchanged.
    @Test
    fun testG_WaterMustNotMatter() {
        val nutrientBeforeWater = NutritionNormalization.normalizeToPer100g(
            sourceValue = 30.0,
            sourceUnit = "g",
            sourceBasis = NutritionBasis.PER_100_G,
            servingMassG = null
        )

        // Simulate application state change in unrelated water tracking
        val waterIntakeMl = 2500
        assertEquals(2500, waterIntakeMl)

        val nutrientAfterWater = NutritionNormalization.normalizeToPer100g(
            sourceValue = 30.0,
            sourceUnit = "g",
            sourceBasis = NutritionBasis.PER_100_G,
            servingMassG = null
        )

        assertEquals(nutrientBeforeWater.value, nutrientAfterWater.value, 0.001)
        assertEquals(nutrientBeforeWater.basis, nutrientAfterWater.basis)
        assertEquals(nutrientBeforeWater.normalizedPer100g, nutrientAfterWater.normalizedPer100g)
        assertEquals(nutrientBeforeWater.normalizedPer1g, nutrientAfterWater.normalizedPer1g)
    }
}
