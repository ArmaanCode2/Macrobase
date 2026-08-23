package com.macrobase.app.data.provider

import com.macrobase.app.domain.model.Food
import com.macrobase.app.domain.model.Serving

/**
 * Clean provider abstraction for food data sources.
 *
 * Designed so that multiple providers (built-in SQLite, Open Food Facts, Indian Food DB, etc.)
 * can be plugged into the application architecture without modifying the domain or UI layers.
 */
interface FoodDataProvider {
    val providerId: String
    val displayName: String
    val isLocal: Boolean

    suspend fun searchFoods(query: String, limit: Int = 30): List<Food>
    suspend fun getFoodById(id: Long): Food?
    suspend fun getFoodByBarcode(barcode: String): Food?
    suspend fun getFoodServings(foodId: Long): List<Serving>
}
