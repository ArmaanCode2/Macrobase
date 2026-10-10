package com.macrobase.app.data.repository

import com.macrobase.app.data.portability.JsonObject
import com.macrobase.app.data.repository.JsonSupport.decodeNutrition
import com.macrobase.app.data.repository.JsonSupport.encodeNutrition
import com.macrobase.app.data.repository.JsonSupport.number
import com.macrobase.app.data.repository.JsonSupport.quote
import com.macrobase.app.data.portability.SimpleJsonParser
import com.macrobase.app.domain.model.Food
import com.macrobase.app.domain.model.FoodSource
import com.macrobase.app.domain.model.Nutrition
import com.macrobase.app.domain.model.RecipeIngredient
import com.macrobase.app.domain.model.Serving
import com.macrobase.app.domain.model.ServingUnit

/**
 * Stores recipe ingredients in `recipes.ingredientsJson`.
 *
 * Each ingredient keeps a reference to its food, the chosen serving and quantity, and a
 * snapshot of its total computed nutrition. Decoded ingredients scale that snapshot per unit
 * of quantity, so editing or deleting the source food later never changes a saved recipe.
 */
object RecipeIngredientsJson {

    fun encode(ingredients: List<RecipeIngredient>): String =
        ingredients.joinToString(prefix = "[", postfix = "]", separator = ",") { encodeIngredient(it) }

    /** Returns null when [json] is not a readable ingredient list, so callers can fall back. */
    fun decode(json: String?): List<RecipeIngredient>? {
        if (json.isNullOrBlank()) return null
        return try {
            SimpleJsonParser.parseArray(json).map { raw ->
                decodeIngredient(raw as? JsonObject ?: throw IllegalArgumentException("Expected ingredient object"))
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun encodeIngredient(ingredient: RecipeIngredient): String {
        val food = ingredient.food
        val serving = ingredient.serving
        return buildString {
            append('{')
            append("\"foodId\":").append(food.id)
            append(",\"foodUuid\":").append(quote(food.uuid))
            append(",\"foodName\":").append(quote(food.name))
            append(",\"foodSource\":").append(quote(food.source.identifier))
            append(",\"isUserOwned\":").append(food.isUserOwned)
            append(",\"quantity\":").append(number(ingredient.quantity))
            append(",\"serving\":{")
            append("\"description\":").append(quote(serving.description))
            append(",\"unit\":").append(quote(serving.unit.name))
            serving.customUnitName?.let { append(",\"customUnitName\":").append(quote(it)) }
            append(",\"quantity\":").append(number(serving.quantity))
            append(",\"gramWeight\":").append(number(serving.gramWeight))
            append('}')
            append(",\"nutrition\":").append(encodeNutrition(ingredient.nutrition))
            append('}')
        }
    }

    private fun decodeIngredient(obj: JsonObject): RecipeIngredient {
        val servingObj = obj.getObject("serving") ?: throw IllegalArgumentException("Missing serving")
        val total = decodeNutrition(obj.getObject("nutrition") ?: throw IllegalArgumentException("Missing nutrition"))
        val quantity = obj.getDouble("quantity") ?: 1.0
        val perUnit = if (quantity > 0.0) total.scale(1.0 / quantity) else total
        val serving = Serving(
            description = servingObj.getString("description") ?: "1 serving",
            unit = runCatching { ServingUnit.valueOf(servingObj.getString("unit") ?: "") }.getOrDefault(ServingUnit.SERVING),
            customUnitName = servingObj.getString("customUnitName"),
            quantity = servingObj.getDouble("quantity") ?: 1.0,
            gramWeight = servingObj.getDouble("gramWeight") ?: 0.0,
            isDefault = true
        )
        val food = Food(
            id = obj.getLong("foodId") ?: 0L,
            uuid = obj.getString("foodUuid") ?: "",
            source = FoodSource.fromIdentifier(obj.getString("foodSource")),
            name = obj.getString("foodName") ?: "Ingredient",
            isUserOwned = obj.getBoolean("isUserOwned") ?: false,
            // Reference only: nutrition here is per unit of the saved serving, not the food's
            // own basis. RecipeIngredient.nutrition uses the snapshot, never this food.
            nutrition = perUnit,
            servings = listOf(serving)
        )
        return RecipeIngredient(
            food = food,
            serving = serving,
            quantity = quantity,
            snapshotPerUnit = perUnit
        )
    }
}
