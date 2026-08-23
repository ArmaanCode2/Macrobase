package com.macrobase.app.domain.model

object MealConfiguration {
    val CoreMeals = setOf(MealType.BREAKFAST, MealType.LUNCH, MealType.DINNER)
    val OptionalMeals = setOf(MealType.AM_SNACK, MealType.PM_SNACK, MealType.SNACK)

    val DisplayOrder = listOf(
        MealType.BREAKFAST,
        MealType.AM_SNACK,
        MealType.LUNCH,
        MealType.PM_SNACK,
        MealType.DINNER,
        MealType.SNACK
    )

    fun getVisibleMeals(meals: List<Meal>): List<Meal> {
        val mealMap = meals.associateBy { it.type }
        return DisplayOrder.mapNotNull { type ->
            val meal = mealMap[type] ?: Meal(type = type, entries = emptyList())
            if (CoreMeals.contains(type) || meal.entries.isNotEmpty()) {
                meal
            } else {
                null
            }
        }
    }
}
