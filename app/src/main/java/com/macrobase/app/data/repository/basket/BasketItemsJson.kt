package com.macrobase.app.data.repository.basket

import android.util.Log
import com.macrobase.app.data.portability.JsonObject
import com.macrobase.app.data.portability.SimpleJsonParser
import com.macrobase.app.data.repository.JsonSupport.decodeNutrition
import com.macrobase.app.data.repository.JsonSupport.encodeNutrition
import com.macrobase.app.data.repository.JsonSupport.number
import com.macrobase.app.data.repository.JsonSupport.quote
import com.macrobase.app.domain.model.Food
import com.macrobase.app.domain.model.FoodSource
import com.macrobase.app.domain.model.FoodType
import com.macrobase.app.domain.model.MealType
import com.macrobase.app.domain.model.Serving
import com.macrobase.app.domain.model.ServingUnit
import com.macrobase.app.domain.model.basket.BasketItem
import java.time.Instant
import java.time.LocalDate

/**
 * File format of the persisted basket. Each item keeps its full food (servings and nutrition
 * basis), the chosen serving and the computed nutrition, so a restored item logs exactly what
 * the user staged. Items are decoded one by one: a damaged item is skipped, not the basket.
 */
object BasketItemsJson {
    private const val VERSION = 1
    private const val TAG = "BasketItemsJson"

    fun encode(items: List<BasketItem>): String =
        "{\"version\":$VERSION,\"items\":" +
            items.joinToString(prefix = "[", postfix = "]", separator = ",") { encodeItem(it) } + "}"

    fun decode(json: String?): List<BasketItem> {
        if (json.isNullOrBlank()) return emptyList()
        val raw = try {
            SimpleJsonParser.parseObject(json).getArray("items") ?: emptyList()
        } catch (e: Exception) {
            Log.w(TAG, "Unreadable basket file: ${e.message}")
            return emptyList()
        }
        return raw.mapNotNull { item ->
            try {
                decodeItem(item as? JsonObject ?: throw IllegalArgumentException("Expected item object"))
            } catch (e: Exception) {
                Log.w(TAG, "Skipping unreadable basket item: ${e.message}")
                null
            }
        }
    }

    private fun encodeItem(item: BasketItem): String = buildString {
        append('{')
        append("\"id\":").append(quote(item.id))
        append(",\"foodNameSnapshot\":").append(quote(item.foodNameSnapshot))
        append(",\"brandSnapshot\":").append(item.brandSnapshot?.let { quote(it) } ?: "null")
        append(",\"quantity\":").append(number(item.quantity))
        append(",\"date\":").append(quote(item.date.toString()))
        append(",\"mealType\":").append(quote(item.mealType.name))
        append(",\"createdAt\":").append(item.createdAt.toEpochMilli())
        append(",\"serving\":").append(encodeServing(item.serving))
        append(",\"calculatedNutrition\":").append(encodeNutrition(item.calculatedNutrition))
        append(",\"food\":").append(encodeFood(item.food))
        append('}')
    }

    private fun decodeItem(o: JsonObject): BasketItem = BasketItem(
        id = o.getString("id") ?: throw IllegalArgumentException("Missing id"),
        food = decodeFood(o.getObject("food") ?: throw IllegalArgumentException("Missing food")),
        foodNameSnapshot = o.getString("foodNameSnapshot") ?: throw IllegalArgumentException("Missing name"),
        brandSnapshot = o.getString("brandSnapshot"),
        quantity = o.getDouble("quantity") ?: throw IllegalArgumentException("Missing quantity"),
        serving = decodeServing(o.getObject("serving") ?: throw IllegalArgumentException("Missing serving")),
        calculatedNutrition = decodeNutrition(o.getObject("calculatedNutrition") ?: throw IllegalArgumentException("Missing nutrition")),
        date = LocalDate.parse(o.getString("date") ?: throw IllegalArgumentException("Missing date")),
        mealType = MealType.fromString(o.getString("mealType")),
        createdAt = Instant.ofEpochMilli(o.getLong("createdAt") ?: 0L)
    )

    private fun encodeFood(f: Food): String = buildString {
        append('{')
        append("\"id\":").append(f.id)
        append(",\"uuid\":").append(quote(f.uuid))
        append(",\"source\":").append(quote(f.source.identifier))
        append(",\"sourceId\":").append(f.sourceId?.let { quote(it) } ?: "null")
        append(",\"name\":").append(quote(f.name))
        append(",\"normalizedName\":").append(quote(f.normalizedName))
        append(",\"brand\":").append(f.brand?.let { quote(it) } ?: "null")
        append(",\"category\":").append(f.category?.let { quote(it) } ?: "null")
        append(",\"foodType\":").append(quote(f.foodType.name))
        append(",\"barcode\":").append(f.barcode?.let { quote(it) } ?: "null")
        append(",\"isUserOwned\":").append(f.isUserOwned)
        append(",\"servingBasis\":").append(quote(f.servingBasis))
        append(",\"isSnapshot\":").append(f.isSnapshot)
        append(",\"nutrition\":").append(encodeNutrition(f.nutrition))
        append(",\"servings\":").append(f.servings.joinToString(prefix = "[", postfix = "]", separator = ",") { encodeServing(it) })
        append('}')
    }

    private fun decodeFood(o: JsonObject): Food {
        val name = o.getString("name") ?: throw IllegalArgumentException("Missing food name")
        return Food(
            id = o.getLong("id") ?: throw IllegalArgumentException("Missing food id"),
            uuid = o.getString("uuid") ?: "",
            source = FoodSource.fromIdentifier(o.getString("source")),
            sourceId = o.getString("sourceId"),
            name = name,
            normalizedName = o.getString("normalizedName") ?: name.lowercase().trim(),
            brand = o.getString("brand"),
            category = o.getString("category"),
            foodType = runCatching { FoodType.valueOf(o.getString("foodType") ?: "") }.getOrDefault(FoodType.GENERIC),
            barcode = o.getString("barcode"),
            isUserOwned = o.getBoolean("isUserOwned") ?: false,
            servingBasis = o.getString("servingBasis") ?: "100g",
            nutrition = decodeNutrition(o.getObject("nutrition") ?: throw IllegalArgumentException("Missing food nutrition")),
            servings = (o.getArray("servings") ?: emptyList()).map { decodeServing(it as JsonObject) },
            isSnapshot = o.getBoolean("isSnapshot") ?: false
        )
    }

    private fun encodeServing(s: Serving): String = buildString {
        append('{')
        append("\"id\":").append(s.id)
        append(",\"description\":").append(quote(s.description))
        append(",\"unit\":").append(quote(s.unit.name))
        append(",\"customUnitName\":").append(s.customUnitName?.let { quote(it) } ?: "null")
        append(",\"quantity\":").append(number(s.quantity))
        append(",\"gramWeight\":").append(number(s.gramWeight))
        append(",\"isDefault\":").append(s.isDefault)
        append(",\"sequence\":").append(s.sequence)
        append('}')
    }

    private fun decodeServing(o: JsonObject) = Serving(
        id = o.getLong("id") ?: 0L,
        description = o.getString("description") ?: "1 serving",
        unit = runCatching { ServingUnit.valueOf(o.getString("unit") ?: "") }.getOrDefault(ServingUnit.SERVING),
        customUnitName = o.getString("customUnitName"),
        quantity = o.getDouble("quantity") ?: 1.0,
        gramWeight = o.getDouble("gramWeight") ?: 0.0,
        isDefault = o.getBoolean("isDefault") ?: false,
        sequence = o.getInt("sequence") ?: 0
    )
}
