package com.macrobase.app.feature.customfoods

import android.os.Parcelable
import com.macrobase.app.domain.model.CustomFood
import com.macrobase.app.domain.model.ServingUnit
import com.macrobase.app.domain.model.scanner.NutritionLabelDraft
import com.macrobase.app.feature.detail.formatQuantityForInput
import kotlinx.parcelize.Parcelize

/**
 * The custom food form, exactly as typed. It lives in the screen's ViewModel and saved state, not in
 * the composition, so rotation, a trip to the scanner and the app being closed all keep it (BUG-002).
 */
@Parcelize
data class CustomFoodForm(
    val name: String = "",
    val brand: String = "",
    val servingSize: String = "1",
    val servingUnit: ServingUnit = ServingUnit.SERVING,
    val customUnitName: String = "",
    val calories: String = "",
    val protein: String = "",
    val carbs: String = "",
    val fat: String = "",
    val saturatedFat: String = "",
    val fiber: String = "",
    val sugar: String = "",
    val sodium: String = ""
) : Parcelable {

    /** This form with what a scanned label gave; what it did not give stays, such as a name typed first. */
    fun withDraft(draft: NutritionLabelDraft): CustomFoodForm = copy(
        name = draft.foodName?.takeIf { it.isNotBlank() } ?: name,
        brand = draft.brand?.takeIf { it.isNotBlank() } ?: brand,
        servingSize = draft.servingSize?.let { formatQuantityForInput(it) } ?: servingSize,
        servingUnit = draft.servingUnit ?: servingUnit,
        calories = draft.calories?.let { formatQuantityForInput(it.value) } ?: calories,
        protein = draft.protein?.let { formatQuantityForInput(it.value) } ?: protein,
        carbs = draft.carbs?.let { formatQuantityForInput(it.value) } ?: carbs,
        fat = draft.fat?.let { formatQuantityForInput(it.value) } ?: fat,
        saturatedFat = draft.saturatedFat?.let { formatQuantityForInput(it.value) } ?: saturatedFat,
        fiber = draft.fiber?.let { formatQuantityForInput(it.value) } ?: fiber,
        sugar = draft.sugar?.let { formatQuantityForInput(it.value) } ?: sugar,
        sodium = draft.sodium?.let { formatQuantityForInput(it.value) } ?: sodium
    )

    companion object {
        /** The stored values exactly; saving unchanged must not alter the food. */
        fun from(food: CustomFood): CustomFoodForm {
            val nutrition = food.nutritionPerServing
            return CustomFoodForm(
                name = food.name,
                brand = food.brand ?: "",
                servingSize = formatQuantityForInput(food.servingSize),
                servingUnit = food.servingUnit,
                customUnitName = food.customUnitName ?: "",
                calories = formatQuantityForInput(nutrition.calories),
                protein = formatQuantityForInput(nutrition.proteinGrams),
                carbs = formatQuantityForInput(nutrition.carbsGrams),
                fat = formatQuantityForInput(nutrition.fatGrams),
                saturatedFat = nutrition.saturatedFatGrams?.let { formatQuantityForInput(it) } ?: "",
                fiber = nutrition.fiberGrams?.let { formatQuantityForInput(it) } ?: "",
                sugar = nutrition.sugarGrams?.let { formatQuantityForInput(it) } ?: "",
                sodium = nutrition.sodiumMg?.let { formatQuantityForInput(it) } ?: ""
            )
        }
    }
}
