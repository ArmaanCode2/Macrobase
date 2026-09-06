package com.macrobase.app.feature.scanner

/**
 * Standard nutrient identifiers recognized across global packaging standards
 * (US FDA, EU/UK, Indian FSSAI, Australia/NZ).
 */
enum class NutrientType {
    ENERGY_KCAL,
    ENERGY_KJ,
    PROTEIN,
    CARBOHYDRATE,
    FIBER,
    TOTAL_SUGAR,
    ADDED_SUGAR,
    TOTAL_FAT,
    SATURATED_FAT,
    TRANS_FAT,
    CHOLESTEROL,
    SODIUM,
    SALT,
    POTASSIUM,
    CALCIUM,
    IRON
}

/**
 * Centralized synonym definitions mapping localized package wording to canonical NutrientType.
 */
object NutritionSynonyms {

    private val SYNONYMS_MAP: Map<NutrientType, List<String>> = mapOf(
        NutrientType.ENERGY_KCAL to listOf(
            "energy (kcal)", "energy kcal", "energy value", "energy",
            "calories (kcal)", "calories", "calorie", "cal", "energetic value",
            "valeur energetique", "brennwert"
        ),
        NutrientType.ENERGY_KJ to listOf(
            "energy (kj)", "energy kj", "kj", "kilojoules"
        ),
        NutrientType.PROTEIN to listOf(
            "total protein", "crude protein", "protein", "proteins", "proteine", "eiweiss"
        ),
        NutrientType.CARBOHYDRATE to listOf(
            "total carbohydrate", "total carbohydrates", "carbohydrate", "carbohydrates",
            "carbohydrat es", "carbohyd rates", "carbohydra tes",
            "total carbs", "carbs", "glucides", "kohlenhydrate", "hydrate de carbone"
        ),
        NutrientType.FIBER to listOf(
            "dietary fiber", "dietary fibre", "dietaryfibre", "crude fiber", "fiber", "fibre",
            "fibres alimentaires", "ballaststoffe"
        ),
        NutrientType.TOTAL_SUGAR to listOf(
            "total sugars", "total sugar", "sugars", "sugar", "of which sugars",
            "dont sucres", "zucker"
        ),
        NutrientType.ADDED_SUGAR to listOf(
            "added sugars", "added sugar", "added sugar (sucrose)", "added sugar(sucrose)",
            "includes added sugars", "added sucres", "sucrose"
        ),
        NutrientType.TOTAL_FAT to listOf(
            "total fat", "total fats", "fat", "fats", "crude fat", "lipids", "lipides", "fett"
        ),
        NutrientType.SATURATED_FAT to listOf(
            "saturated fat", "saturated fatty acids", "saturated fatty acid", "saturated fats", "saturates",
            "of which saturates", "sat fat", "sat. fat", "acides gras satures", "gesattigte fettsauren"
        ),
        NutrientType.TRANS_FAT to listOf(
            "trans fat", "trans fatty acids", "trans fatty acid", "trans fats", "trans-fat", "acides gras trans"
        ),
        NutrientType.CHOLESTEROL to listOf(
            "cholesterol", "cholest.", "cholest", "cholesterin"
        ),
        NutrientType.SODIUM to listOf(
            "sodium", "natrium"
        ),
        NutrientType.SALT to listOf(
            "salt", "equivalent as salt", "sel", "salz"
        ),
        NutrientType.POTASSIUM to listOf(
            "potassium", "potasio", "kalium"
        ),
        NutrientType.CALCIUM to listOf(
            "calcium", "calcio", "kalzium"
        ),
        NutrientType.IRON to listOf(
            "iron", "hierro", "fer", "eisen"
        )
    )

    /**
     * Standard expected unit for each nutrient type.
     */
    fun getExpectedUnit(type: NutrientType): String = when (type) {
        NutrientType.ENERGY_KCAL -> "kcal"
        NutrientType.ENERGY_KJ -> "kJ"
        NutrientType.PROTEIN,
        NutrientType.CARBOHYDRATE,
        NutrientType.FIBER,
        NutrientType.TOTAL_SUGAR,
        NutrientType.ADDED_SUGAR,
        NutrientType.TOTAL_FAT,
        NutrientType.SATURATED_FAT,
        NutrientType.TRANS_FAT,
        NutrientType.SALT -> "g"
        NutrientType.CHOLESTEROL,
        NutrientType.SODIUM,
        NutrientType.POTASSIUM,
        NutrientType.CALCIUM,
        NutrientType.IRON -> "mg"
    }

    /**
     * Identifies nutrient type from raw text label.
     * Orders matches so longer, more specific matches (e.g. "Added Sugars" before "Sugars",
     * "Saturated Fat" before "Fat") take precedence.
     */
    fun matchNutrient(text: String): Pair<NutrientType, String>? {
        val lower = text.lowercase()

        // Order prioritized to ensure nested terms match before broader parent terms
        val priorityOrder = listOf(
            NutrientType.ADDED_SUGAR,
            NutrientType.TOTAL_SUGAR,
            NutrientType.SATURATED_FAT,
            NutrientType.TRANS_FAT,
            NutrientType.TOTAL_FAT,
            NutrientType.CHOLESTEROL,
            NutrientType.SODIUM,
            NutrientType.SALT,
            NutrientType.FIBER,
            NutrientType.CARBOHYDRATE,
            NutrientType.PROTEIN,
            NutrientType.POTASSIUM,
            NutrientType.CALCIUM,
            NutrientType.IRON,
            NutrientType.ENERGY_KJ,
            NutrientType.ENERGY_KCAL
        )

        for (type in priorityOrder) {
            val synonyms = SYNONYMS_MAP[type] ?: continue
            for (syn in synonyms) {
                // Word boundary check
                val regex = Regex("""(?i)\b${Regex.escape(syn)}\b""")
                if (regex.containsMatchIn(lower)) {
                    return Pair(type, syn)
                }
            }
        }

        return null
    }
}
