package com.macrobase.app.feature.customfoods

import android.os.Parcelable
import com.macrobase.app.domain.model.CustomFood
import com.macrobase.app.domain.model.ServingUnit
import com.macrobase.app.domain.model.scanner.NutritionLabelDraft
import com.macrobase.app.feature.detail.formatQuantityForInput
import kotlinx.parcelize.Parcelize
import kotlin.reflect.KProperty1

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
    val sodium: String = "",
    val potassium: String = "",
    val calcium: String = "",
    val iron: String = "",
    /**
     * Number texts the app wrote, by field: from a scan or the stored food. "4.125" there is
     * 4.125, never a typed 4,125 (BUG-036).
     */
    val written: Map<String, String> = emptyMap()
) : Parcelable {

    /** [field] read as a number; a text the app wrote there is read as written. */
    internal fun read(field: KProperty1<CustomFoodForm, String>, largeAmount: Boolean = false): FormNumber {
        val text = field.get(this)
        return readFormNumber(text, largeAmount, writtenByApp = written[field.name] == text)
    }

    /** Every number field's text, by field name. */
    private fun numberTexts() = mapOf(
        "servingSize" to servingSize,
        "calories" to calories,
        "protein" to protein,
        "carbs" to carbs,
        "fat" to fat,
        "saturatedFat" to saturatedFat,
        "fiber" to fiber,
        "sugar" to sugar,
        "sodium" to sodium,
        "potassium" to potassium,
        "calcium" to calcium,
        "iron" to iron
    )

    /** This form with what a scanned label gave; what it did not give stays, such as a name typed first. */
    fun withDraft(draft: NutritionLabelDraft): CustomFoodForm {
        val scanned = listOfNotNull(
            draft.servingSize?.let { "servingSize" to it },
            draft.calories?.let { "calories" to it.value },
            draft.protein?.let { "protein" to it.value },
            draft.carbs?.let { "carbs" to it.value },
            draft.fat?.let { "fat" to it.value },
            draft.saturatedFat?.let { "saturatedFat" to it.value },
            draft.fiber?.let { "fiber" to it.value },
            draft.sugar?.let { "sugar" to it.value },
            draft.sodium?.let { "sodium" to it.value },
            draft.potassium?.let { "potassium" to it.value },
            draft.calcium?.let { "calcium" to it.value },
            draft.iron?.let { "iron" to it.value }
        ).associate { (field, value) -> field to formatQuantityForInput(value) }
        return copy(
            name = draft.foodName?.takeIf { it.isNotBlank() } ?: name,
            brand = draft.brand?.takeIf { it.isNotBlank() } ?: brand,
            servingSize = scanned["servingSize"] ?: servingSize,
            servingUnit = draft.servingUnit ?: servingUnit,
            calories = scanned["calories"] ?: calories,
            protein = scanned["protein"] ?: protein,
            carbs = scanned["carbs"] ?: carbs,
            fat = scanned["fat"] ?: fat,
            saturatedFat = scanned["saturatedFat"] ?: saturatedFat,
            fiber = scanned["fiber"] ?: fiber,
            sugar = scanned["sugar"] ?: sugar,
            sodium = scanned["sodium"] ?: sodium,
            potassium = scanned["potassium"] ?: potassium,
            calcium = scanned["calcium"] ?: calcium,
            iron = scanned["iron"] ?: iron,
            written = written + scanned
        )
    }

    companion object {
        /** The stored values exactly; saving unchanged must not alter the food. */
        fun from(food: CustomFood): CustomFoodForm {
            val nutrition = food.nutritionPerServing
            val form = CustomFoodForm(
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
                sodium = nutrition.sodiumMg?.let { formatQuantityForInput(it) } ?: "",
                potassium = nutrition.potassiumMg?.let { formatQuantityForInput(it) } ?: "",
                calcium = nutrition.calciumMg?.let { formatQuantityForInput(it) } ?: "",
                iron = nutrition.ironMg?.let { formatQuantityForInput(it) } ?: ""
            )
            return form.copy(written = form.numberTexts().filterValues { it.isNotEmpty() })
        }
    }
}
