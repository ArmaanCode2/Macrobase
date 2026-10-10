package com.macrobase.app.data.repository

import com.macrobase.app.data.database.dao.CustomFoodDao
import com.macrobase.app.data.database.entity.CustomFoodEntity
import com.macrobase.app.data.provider.FoodDataProvider
import com.macrobase.app.domain.model.CustomFood
import com.macrobase.app.domain.model.Food
import com.macrobase.app.domain.model.Nutrition
import com.macrobase.app.domain.model.Serving
import com.macrobase.app.domain.model.ServingUnit
import com.macrobase.app.domain.repository.FoodRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Concrete implementation of FoodRepository coordinating between the read-only built-in catalog
 * and user-created custom foods in Room database.
 */
class FoodRepositoryImpl(
    private val localDatabaseProvider: FoodDataProvider,
    private val customFoodDao: CustomFoodDao
) : FoodRepository {

    companion object {
        const val CUSTOM_FOOD_ID_OFFSET = 100_000_000L

        /**
         * Food id for a restored custom-food diary entry whose food could not be identified.
         * It resolves to no food, so the entry is always shown from its own snapshot.
         */
        const val UNLINKED_CUSTOM_FOOD_ID = CUSTOM_FOOD_ID_OFFSET

        /** Room row id for a food id in the custom range, or null for any other food id. */
        fun customRowIdOrNull(foodId: Long): Long? =
            if (foodId > CUSTOM_FOOD_ID_OFFSET && foodId < com.macrobase.app.domain.model.Recipe.FOOD_ID_OFFSET) {
                foodId - CUSTOM_FOOD_ID_OFFSET
            } else null
    }

    override suspend fun searchFoods(query: String, limit: Int): List<Food> {
        val customMatches = customFoodDao.searchCustomFoods(query, limit).map { it.toDomainFood() }
        val remainingLimit = limit - customMatches.size
        val builtInMatches = if (remainingLimit > 0) {
            localDatabaseProvider.searchFoods(query, remainingLimit)
        } else emptyList()

        return customMatches + builtInMatches
    }

    override suspend fun getFoodById(id: Long): Food? {
        val builtInFood = localDatabaseProvider.getFoodById(id)
        if (builtInFood != null) {
            return builtInFood
        }

        // Only ids in the custom range are custom foods. Smaller ids are recipes or foods from
        // earlier catalogs logged by older versions, never a raw custom-food row id.
        val customId = customRowIdOrNull(id) ?: return null
        return customFoodDao.getCustomFoodById(customId)?.toDomainFood()
    }

    override suspend fun getFoodByBarcode(barcode: String): Food? {
        return localDatabaseProvider.getFoodByBarcode(barcode)
    }

    override suspend fun getFoodServings(foodId: Long): List<Serving> {
        val builtInServings = localDatabaseProvider.getFoodServings(foodId)
        if (builtInServings.isNotEmpty()) {
            return builtInServings
        }

        val customId = customRowIdOrNull(foodId) ?: return emptyList()
        return customFoodDao.getCustomFoodById(customId)?.toDomainFood()?.servings ?: emptyList()
    }

    override suspend fun getRecentFoods(limit: Int): List<Food> {
        return emptyList()
    }

    override suspend fun getFavoriteFoods(): List<Food> {
        return emptyList()
    }

    override fun observeCustomFoods(): Flow<List<Food>> {
        return customFoodDao.observeAllCustomFoods().map { list ->
            list.map { it.toDomainFood() }
        }
    }

    override suspend fun getCustomFoods(): List<Food> {
        return customFoodDao.getAllCustomFoods().map { it.toDomainFood() }
    }

    override suspend fun saveCustomFood(food: CustomFood): Long {
        val actualId = if (food.id >= CUSTOM_FOOD_ID_OFFSET) food.id - CUSTOM_FOOD_ID_OFFSET else food.id
        val entity = CustomFoodEntity(
            id = if (actualId > 0) actualId else 0,
            uuid = food.uuid,
            name = food.name,
            brand = food.brand,
            servingSize = food.servingSize,
            servingUnit = food.servingUnit.name,
            customUnitName = food.customUnitName,
            calories = food.nutritionPerServing.calories,
            proteinGrams = food.nutritionPerServing.proteinGrams,
            carbsGrams = food.nutritionPerServing.carbsGrams,
            fatGrams = food.nutritionPerServing.fatGrams,
            fiberGrams = food.nutritionPerServing.fiberGrams,
            sugarGrams = food.nutritionPerServing.sugarGrams,
            sodiumMg = food.nutritionPerServing.sodiumMg,
            potassiumMg = food.nutritionPerServing.potassiumMg,
            calciumMg = food.nutritionPerServing.calciumMg,
            ironMg = food.nutritionPerServing.ironMg,
            createdAt = food.createdAt.toEpochMilli()
        )
        val insertedId = customFoodDao.insertCustomFood(entity)
        return CUSTOM_FOOD_ID_OFFSET + insertedId
    }

    override suspend fun getCustomFood(id: Long): CustomFood? {
        val actualId = if (id >= CUSTOM_FOOD_ID_OFFSET) id - CUSTOM_FOOD_ID_OFFSET else id
        return customFoodDao.getCustomFoodById(actualId)?.toCustomFood()
    }

    override suspend fun deleteCustomFood(id: Long) {
        val actualId = if (id >= CUSTOM_FOOD_ID_OFFSET) id - CUSTOM_FOOD_ID_OFFSET else id
        customFoodDao.deleteCustomFood(actualId)
    }

    private fun CustomFoodEntity.toDomainFood(): Food =
        toCustomFood().toFood()

    /** Domain custom food; [CustomFood.id] carries the offset id used across the app. */
    private fun CustomFoodEntity.toCustomFood(): CustomFood {
        val unit = try {
            ServingUnit.valueOf(servingUnit)
        } catch (ex: Exception) {
            ServingUnit.fromString(servingUnit)
        }
        val customName = customUnitName ?: if (unit == ServingUnit.CUSTOM && servingUnit != "CUSTOM" && servingUnit != "unit" && servingUnit != "Custom...") servingUnit else null

        return CustomFood(
            id = CUSTOM_FOOD_ID_OFFSET + id,
            uuid = uuid,
            name = name,
            brand = brand,
            servingSize = servingSize,
            servingUnit = unit,
            customUnitName = customName,
            nutritionPerServing = Nutrition(
                calories = calories,
                proteinGrams = proteinGrams,
                carbsGrams = carbsGrams,
                fatGrams = fatGrams,
                fiberGrams = fiberGrams,
                sugarGrams = sugarGrams,
                sodiumMg = sodiumMg,
                potassiumMg = potassiumMg,
                calciumMg = calciumMg,
                ironMg = ironMg
            ),
            createdAt = java.time.Instant.ofEpochMilli(createdAt)
        )
    }
}
