package com.macrobase.app.domain.model

/**
 * Origin source classification of a food entry.
 */
enum class FoodSource(val identifier: String) {
    BUILT_IN("BUILT_IN"),
    CUSTOM_USER("CUSTOM_USER"),
    OPEN_FOOD_FACTS("OPEN_FOOD_FACTS"),
    RECIPE("RECIPE"),
    THIRD_PARTY("THIRD_PARTY");

    companion object {
        fun fromIdentifier(id: String?): FoodSource {
            return entries.firstOrNull { it.identifier.equals(id, ignoreCase = true) } ?: BUILT_IN
        }
    }
}

/**
 * Functional category / type of food.
 */
enum class FoodType {
    GENERIC,
    BRANDED,
    CUSTOM,
    RECIPE_COMPOSITE
}

/**
 * Clean, provider-agnostic domain model representing a food.
 *
 * Fully decoupled from SQLite, Room, and USDA JSON structures.
 */
data class Food(
    val id: Long,
    val uuid: String,
    val source: FoodSource = FoodSource.BUILT_IN,
    val sourceId: String? = null,
    val name: String,
    val normalizedName: String = name.lowercase().trim(),
    val brand: String? = null,
    val category: String? = null,
    val foodType: FoodType = FoodType.GENERIC,
    val barcode: String? = null,
    val isUserOwned: Boolean = false,
    val servingBasis: String = "100g",
    val nutrition: Nutrition,
    val servings: List<Serving> = emptyList(),
    /**
     * True for a food rebuilt from a diary entry's snapshot: [nutrition] is per one unit of
     * the logged serving, so it always scales by portion, never per 100 g.
     */
    val isSnapshot: Boolean = false
) {
    val defaultServing: Serving?
        get() = servings.firstOrNull { it.isDefault } ?: servings.firstOrNull()

    /**
     * A food loaded from the built-in catalog (per-100g nutrition with its own servings),
     * as opposed to custom foods, recipes, or a diary snapshot rebuilt without servings.
     */
    val isCatalogFood: Boolean
        get() = source == FoodSource.BUILT_IN && !isUserOwned && !isSnapshot && servings.isNotEmpty()

    /**
     * Nutrition for [userQuantity] of [serving] (AGENTS.md section 2.1).
     *
     * Custom foods and recipes store nutrition for one default portion; catalog foods
     * store it per 100 g and scale by gram weight.
     */
    fun nutritionFor(serving: Serving, userQuantity: Double): Nutrition {
        if (userQuantity <= 0.0) return Nutrition.ZERO
        val multiplier = if (scalesByPortion) {
            // No ratio to the default portion: refuse rather than guess (BUG-003). Callers
            // check canScale() first and fall back to the entry snapshot.
            portionRatio(serving)?.times(userQuantity) ?: return Nutrition.ZERO
        } else {
            // Catalog nutrition is per 100 g; a portion with no gram weight cannot be scaled,
            // so it must never be treated as one 100 g unit.
            if (isCatalogFood && !serving.hasKnownWeight) return Nutrition.ZERO
            serving.calculateGramMultiplier(userQuantity)
        }
        return nutrition.scale(multiplier)
    }

    /** Custom foods, recipes and snapshots store nutrition per one default portion. */
    private val scalesByPortion: Boolean
        get() = isUserOwned || isSnapshot || source == FoodSource.CUSTOM_USER

    /** Size of [serving] relative to the default portion, or null when it cannot be known. */
    private fun portionRatio(serving: Serving): Double? {
        val defaultServing = servings.firstOrNull { it.isDefault } ?: servings.firstOrNull() ?: serving
        return when {
            // Matched by description, not id: custom foods all reuse serving ids 1 and 2, so an
            // id says nothing about size ("1 cup" stays 1 cup after the food becomes "2 cup")
            normalizeAmounts(serving.description).equals(normalizeAmounts(defaultServing.description), ignoreCase = true) -> 1.0
            serving.gramWeight > 0.0 && defaultServing.gramWeight > 0.0 -> serving.gramWeight / defaultServing.gramWeight
            defaultServing.quantity > 0.0 && serving.quantity > 0.0 && serving.quantity != defaultServing.quantity ->
                serving.quantity / defaultServing.quantity
            else -> null
        }
    }

    /**
     * True when [serving] can be scaled against this food's stored nutrition. A logged serving
     * that no longer matches the food (e.g. its portion was edited since) cannot, and the
     * entry's own snapshot must be used instead.
     */
    fun canScale(serving: Serving): Boolean =
        if (scalesByPortion) portionRatio(serving) != null else !isCatalogFood || serving.hasKnownWeight

    /**
     * True when this looked-up food is the one a diary entry logged. Lookups go by food id,
     * and ids from older versions can point at a different food, so the logged name must match.
     */
    fun isSameFoodAs(logged: Food): Boolean =
        name.trim().equals(logged.name.trim(), ignoreCase = true)

    /**
     * What [nutrition] is measured per, for labels shown next to it: catalog foods are per
     * 100 g; custom foods, recipes and diary snapshots are per their default serving.
     */
    val nutritionBasisLabel: String
        get() = if (isUserOwned || isSnapshot || source == FoodSource.CUSTOM_USER) {
            "per ${defaultServing?.description ?: "1 serving"}"
        } else {
            // Catalog basis from the database, e.g. "100g" -> "per 100 g"
            val basis = BASIS_AMOUNT_AND_UNIT.matchEntire(servingBasis.trim())
            if (basis != null) "per ${basis.groupValues[1]} ${basis.groupValues[2]}" else "per 100 g"
        }

    /** Subtitle for food lists: brand (when present) and the basis of the shown calories. */
    val listSubtitle: String
        get() = listOfNotNull(brand?.takeIf { it.isNotBlank() }, nutritionBasisLabel).joinToString(" \u2022 ")

    /** Serving picker label; catalog portions show their gram weight. */
    fun servingLabel(serving: Serving): String =
        if (isCatalogFood) serving.labelWithWeight else serving.description

    /**
     * Maps the serving stored on a diary entry back to one of this food's servings.
     * A match must agree on gram weight when both weights are known, so a later catalog
     * change never re-bases a historical snapshot. Catalog portions logged before they
     * had weights come back with their original 100 g basis. Falls back to [logged].
     */
    fun resolveLoggedServing(logged: Serving): Serving {
        if (isCatalogFood && !logged.hasKnownWeight) return logged.withLegacyCatalogWeight()
        val loggedDescription = normalizeAmounts(logged.description)
        return servings.firstOrNull {
            normalizeAmounts(it.description).equals(loggedDescription, ignoreCase = true) && it.hasSameWeightAs(logged)
        } ?: (if (logged.hasKnownWeight) {
            servings.firstOrNull { it.hasKnownWeight && kotlin.math.abs(it.gramWeight - logged.gramWeight) < 0.001 }
        } else null) ?: logged
    }

    /** Earlier versions labelled servings "250.0 ml"; current ones say "250 ml". */
    private fun normalizeAmounts(description: String): String =
        description.trim().replace(WHOLE_NUMBER_WITH_ZERO_DECIMALS, "$1")

    private fun Serving.hasSameWeightAs(other: Serving): Boolean =
        !hasKnownWeight || !other.hasKnownWeight || kotlin.math.abs(gramWeight - other.gramWeight) < 0.001

    private companion object {
        val WHOLE_NUMBER_WITH_ZERO_DECIMALS = Regex("""\b(\d+)\.0+\b""")
        val BASIS_AMOUNT_AND_UNIT = Regex("""(\d+(?:\.\d+)?)\s*([a-zA-Z]+)""")
    }
}
