package com.macrobase.app.domain.model

/**
 * Provider-agnostic nutritional facts domain model.
 *
 * Missing or unanalyzed nutrient values are represented as nullable types (null)
 * rather than silently treating them as 0.0.
 */
data class Nutrition(
    // Core Macronutrients
    val calories: Double,
    val proteinGrams: Double,
    val carbsGrams: Double,
    val fatGrams: Double,
    val fiberGrams: Double? = null,
    val sugarGrams: Double? = null,
    val saturatedFatGrams: Double? = null,
    val transFatGrams: Double? = null,
    val cholesterolMg: Double? = null,
    val sodiumMg: Double? = null,

    // Minerals (Nullable)
    val potassiumMg: Double? = null,
    val calciumMg: Double? = null,
    val ironMg: Double? = null,
    val magnesiumMg: Double? = null,
    val phosphorusMg: Double? = null,
    val zincMg: Double? = null,

    // Vitamins (Nullable)
    val vitaminARaeMcg: Double? = null,
    val vitaminCMg: Double? = null,
    val vitaminDMcg: Double? = null,
    val vitaminEMg: Double? = null,
    val vitaminKMcg: Double? = null,
    val vitaminB6Mg: Double? = null,
    val vitaminB12Mcg: Double? = null,
    val folateMcg: Double? = null,

    // Hydration
    val waterGrams: Double? = null
) {
    /**
     * Net carbohydrates = Total Carbohydrates - Dietary Fiber (non-negative).
     */
    val netCarbsGrams: Double
        get() = (carbsGrams - (fiberGrams ?: 0.0)).coerceAtLeast(0.0)

    /**
     * Scales all nutritional values by a given multiplier factor (e.g. from serving weight / 100g).
     */
    fun scale(multiplier: Double): Nutrition {
        if (multiplier == 1.0) return this
        return copy(
            calories = calories * multiplier,
            proteinGrams = proteinGrams * multiplier,
            carbsGrams = carbsGrams * multiplier,
            fatGrams = fatGrams * multiplier,
            fiberGrams = fiberGrams?.times(multiplier),
            sugarGrams = sugarGrams?.times(multiplier),
            saturatedFatGrams = saturatedFatGrams?.times(multiplier),
            transFatGrams = transFatGrams?.times(multiplier),
            cholesterolMg = cholesterolMg?.times(multiplier),
            sodiumMg = sodiumMg?.times(multiplier),
            potassiumMg = potassiumMg?.times(multiplier),
            calciumMg = calciumMg?.times(multiplier),
            ironMg = ironMg?.times(multiplier),
            magnesiumMg = magnesiumMg?.times(multiplier),
            phosphorusMg = phosphorusMg?.times(multiplier),
            zincMg = zincMg?.times(multiplier),
            vitaminARaeMcg = vitaminARaeMcg?.times(multiplier),
            vitaminCMg = vitaminCMg?.times(multiplier),
            vitaminDMcg = vitaminDMcg?.times(multiplier),
            vitaminEMg = vitaminEMg?.times(multiplier),
            vitaminKMcg = vitaminKMcg?.times(multiplier),
            vitaminB6Mg = vitaminB6Mg?.times(multiplier),
            vitaminB12Mcg = vitaminB12Mcg?.times(multiplier),
            folateMcg = folateMcg?.times(multiplier),
            waterGrams = waterGrams?.times(multiplier)
        )
    }

    /**
     * Sums two nutrition objects.
     */
    operator fun plus(other: Nutrition): Nutrition {
        return Nutrition(
            calories = calories + other.calories,
            proteinGrams = proteinGrams + other.proteinGrams,
            carbsGrams = carbsGrams + other.carbsGrams,
            fatGrams = fatGrams + other.fatGrams,
            fiberGrams = addNullable(fiberGrams, other.fiberGrams),
            sugarGrams = addNullable(sugarGrams, other.sugarGrams),
            saturatedFatGrams = addNullable(saturatedFatGrams, other.saturatedFatGrams),
            transFatGrams = addNullable(transFatGrams, other.transFatGrams),
            cholesterolMg = addNullable(cholesterolMg, other.cholesterolMg),
            sodiumMg = addNullable(sodiumMg, other.sodiumMg),
            potassiumMg = addNullable(potassiumMg, other.potassiumMg),
            calciumMg = addNullable(calciumMg, other.calciumMg),
            ironMg = addNullable(ironMg, other.ironMg),
            magnesiumMg = addNullable(magnesiumMg, other.magnesiumMg),
            phosphorusMg = addNullable(phosphorusMg, other.phosphorusMg),
            zincMg = addNullable(zincMg, other.zincMg),
            vitaminARaeMcg = addNullable(vitaminARaeMcg, other.vitaminARaeMcg),
            vitaminCMg = addNullable(vitaminCMg, other.vitaminCMg),
            vitaminDMcg = addNullable(vitaminDMcg, other.vitaminDMcg),
            vitaminEMg = addNullable(vitaminEMg, other.vitaminEMg),
            vitaminKMcg = addNullable(vitaminKMcg, other.vitaminKMcg),
            vitaminB6Mg = addNullable(vitaminB6Mg, other.vitaminB6Mg),
            vitaminB12Mcg = addNullable(vitaminB12Mcg, other.vitaminB12Mcg),
            folateMcg = addNullable(folateMcg, other.folateMcg),
            waterGrams = addNullable(waterGrams, other.waterGrams)
        )
    }

    companion object {
        val ZERO = Nutrition(
            calories = 0.0,
            proteinGrams = 0.0,
            carbsGrams = 0.0,
            fatGrams = 0.0
        )

        private fun addNullable(a: Double?, b: Double?): Double? {
            if (a == null && b == null) return null
            return (a ?: 0.0) + (b ?: 0.0)
        }
    }
}
