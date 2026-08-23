package com.macrobase.app.domain.repository

import com.macrobase.app.domain.model.CustomFood
import com.macrobase.app.domain.model.Food
import com.macrobase.app.domain.model.Serving

import kotlinx.coroutines.flow.Flow

/**
 * Repository contract for food catalog queries, custom foods, and barcode lookups.
 * Completely provider-agnostic.
 */
interface FoodRepository {
    suspend fun searchFoods(query: String, limit: Int = 30): List<Food>
    suspend fun getFoodById(id: Long): Food?
    suspend fun getFoodByBarcode(barcode: String): Food?
    suspend fun getFoodServings(foodId: Long): List<Serving>
    suspend fun getRecentFoods(limit: Int = 10): List<Food>
    suspend fun getFavoriteFoods(): List<Food>
    fun observeCustomFoods(): Flow<List<Food>>
    suspend fun getCustomFoods(): List<Food>
    suspend fun saveCustomFood(food: CustomFood): Long
    suspend fun deleteCustomFood(id: Long)
}
