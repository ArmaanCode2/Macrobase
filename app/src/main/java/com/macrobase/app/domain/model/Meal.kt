package com.macrobase.app.domain.model

enum class MealType(val displayName: String, val order: Int) {
    BREAKFAST("BREAKFAST", 1),
    LUNCH("LUNCH", 2),
    DINNER("DINNER", 3),
    AM_SNACK("AM SNACK", 4),
    PM_SNACK("PM SNACK", 5),
    SNACK("LATE SNACK", 6); // Maps old "SNACK" to Late Snack

    companion object {
        fun fromString(name: String?): MealType {
            if (name.isNullOrBlank()) return BREAKFAST
            return entries.firstOrNull {
                it.name.equals(name, ignoreCase = true) ||
                it.displayName.equals(name, ignoreCase = true)
            } ?: BREAKFAST
        }
    }
}

data class Meal(
    val type: MealType,
    val entries: List<DiaryEntry> = emptyList()
) {
    val totalCalories: Double
        get() = entries.sumOf { it.calculatedNutrition.calories }

    val totalProtein: Double
        get() = entries.sumOf { it.calculatedNutrition.proteinGrams }

    val totalCarbs: Double
        get() = entries.sumOf { it.calculatedNutrition.carbsGrams }

    val totalFat: Double
        get() = entries.sumOf { it.calculatedNutrition.fatGrams }

    val aggregatedNutrition: Nutrition
        get() = entries.fold(Nutrition.ZERO) { acc, entry -> acc + entry.calculatedNutrition }
}
