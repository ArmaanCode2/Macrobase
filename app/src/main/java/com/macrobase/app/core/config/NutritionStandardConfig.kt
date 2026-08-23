package com.macrobase.app.core.config

/**
 * Standard Daily Reference Values (DRV) for Nutrition Facts calculations (FDA 2,000 kcal standard).
 */
object NutritionStandardConfig {
    const val STANDARD_DAILY_CALORIES: Double = 2000.0

    // Daily Values for % DV calculations
    const val DV_TOTAL_FAT_G: Double = 78.0
    const val DV_SATURATED_FAT_G: Double = 20.0
    const val DV_CHOLESTEROL_MG: Double = 300.0
    const val DV_SODIUM_MG: Double = 2300.0
    const val DV_TOTAL_CARBOHYDRATE_G: Double = 275.0
    const val DV_DIETARY_FIBER_G: Double = 28.0
    const val DV_ADDED_SUGARS_G: Double = 50.0
    const val DV_PROTEIN_G: Double = 50.0

    const val DV_VITAMIN_D_MCG: Double = 20.0
    const val DV_CALCIUM_MG: Double = 1300.0
    const val DV_IRON_MG: Double = 18.0
    const val DV_POTASSIUM_MG: Double = 4700.0
    const val DV_VITAMIN_A_MCG: Double = 900.0
    const val DV_VITAMIN_C_MG: Double = 90.0
    const val DV_PHOSPHORUS_MG: Double = 1250.0
    const val DV_MAGNESIUM_MG: Double = 420.0

    // Caloric energy conversion factors (Atwater general)
    const val CALORIES_PER_GRAM_CARB: Double = 4.0
    const val CALORIES_PER_GRAM_PROTEIN: Double = 4.0
    const val CALORIES_PER_GRAM_FAT: Double = 9.0
}
