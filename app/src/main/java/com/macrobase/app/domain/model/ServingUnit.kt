package com.macrobase.app.domain.model

/**
 * Standard serving unit types supported by the application.
 */
enum class ServingUnit(val displayName: String, val isMetric: Boolean) {
    SERVING("serving", false),
    GRAMS("g", true),
    KILOGRAMS("kg", true),
    MILLILITERS("ml", true),
    LITERS("L", true),
    CUP("cup", false),
    TABLESPOON("tablespoon", false),
    TEASPOON("teaspoon", false),
    PIECE("piece", false),
    SLICE("slice", false),
    BOWL("bowl", false),
    GLASS("glass", false),
    OUNCE("ounce", false),
    FLUID_OUNCE("fluid ounce", false),
    CUSTOM("Custom...", false);

    companion object {
        fun fromString(value: String?): ServingUnit {
            if (value.isNullOrBlank()) return SERVING
            val clean = value.trim().lowercase()
            return when {
                clean == "g" || clean == "gram" || clean == "grams" -> GRAMS
                clean == "kg" || clean == "kilogram" || clean == "kilograms" -> KILOGRAMS
                clean == "ml" || clean == "milliliter" || clean == "milliliters" -> MILLILITERS
                clean == "l" || clean == "liter" || clean == "liters" || clean == "litre" || clean == "litres" -> LITERS
                clean == "oz" || clean == "ounce" || clean == "ounces" -> OUNCE
                clean == "fl oz" || clean == "fluid ounce" || clean == "fluid ounces" -> FLUID_OUNCE
                clean == "cup" || clean == "cups" -> CUP
                clean == "tbsp" || clean == "tablespoon" || clean == "tablespoons" -> TABLESPOON
                clean == "tsp" || clean == "teaspoon" || clean == "teaspoons" -> TEASPOON
                clean == "piece" || clean == "pieces" || clean == "item" || clean == "items" -> PIECE
                clean == "slice" || clean == "slices" -> SLICE
                clean == "bowl" || clean == "bowls" -> BOWL
                clean == "glass" || clean == "glasses" -> GLASS
                clean == "serving" || clean == "servings" -> SERVING
                clean == "custom..." || clean == "custom" -> CUSTOM
                else -> CUSTOM
            }
        }
    }
}
