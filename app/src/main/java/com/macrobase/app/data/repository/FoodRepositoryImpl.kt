package com.macrobase.app.data.repository

import com.macrobase.app.data.database.dao.CustomFoodDao
import com.macrobase.app.data.database.entity.CustomFoodEntity
import com.macrobase.app.data.provider.FoodDataProvider
import com.macrobase.app.domain.model.CustomFood
import com.macrobase.app.domain.model.Food
import com.macrobase.app.domain.model.FoodSource
import com.macrobase.app.domain.model.FoodType
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
        if (id >= CUSTOM_FOOD_ID_OFFSET) {
            val customId = id - CUSTOM_FOOD_ID_OFFSET
            val custom = customFoodDao.getCustomFoodById(customId)
            return custom?.toDomainFood()
        }
        val custom = customFoodDao.getCustomFoodById(id)
        if (custom != null) {
            return custom.toDomainFood()
        }
        return localDatabaseProvider.getFoodById(id)
    }

    override suspend fun getFoodByBarcode(barcode: String): Food? {
        return localDatabaseProvider.getFoodByBarcode(barcode)
    }

    override suspend fun getFoodServings(foodId: Long): List<Serving> {
        if (foodId >= CUSTOM_FOOD_ID_OFFSET) {
            val customId = foodId - CUSTOM_FOOD_ID_OFFSET
            val custom = customFoodDao.getCustomFoodById(customId)
            return custom?.toDomainFood()?.servings ?: emptyList()
        }
        val custom = customFoodDao.getCustomFoodById(foodId)
        if (custom != null) {
            return custom.toDomainFood().servings
        }
        return localDatabaseProvider.getFoodServings(foodId)
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
            ironMg = food.nutritionPerServing.ironMg
        )
        val insertedId = customFoodDao.insertCustomFood(entity)
        return CUSTOM_FOOD_ID_OFFSET + insertedId
    }

    override suspend fun deleteCustomFood(id: Long) {
        val actualId = if (id >= CUSTOM_FOOD_ID_OFFSET) id - CUSTOM_FOOD_ID_OFFSET else id
        customFoodDao.deleteCustomFood(actualId)
    }

    private fun CustomFoodEntity.toDomainFood(): Food {
        val unit = try {
            ServingUnit.valueOf(servingUnit)
        } catch (ex: Exception) {
            ServingUnit.fromString(servingUnit)
        }
        val customName = customUnitName ?: if (unit == ServingUnit.CUSTOM && servingUnit != "CUSTOM" && servingUnit != "unit" && servingUnit != "Custom...") servingUnit else null
        val unitLabel = if (unit == ServingUnit.CUSTOM && !customName.isNullOrBlank()) {
            customName
        } else {
            unit.displayName
        }

        val serving = Serving(
            id = 1,
            description = "$servingSize $unitLabel",
            unit = unit,
            customUnitName = customName,
            quantity = servingSize,
            gramWeight = when (unit) {
                ServingUnit.GRAMS -> servingSize
                ServingUnit.KILOGRAMS -> servingSize * 1000.0
                else -> 0.0
            },
            isDefault = true
        )

        return Food(
            id = CUSTOM_FOOD_ID_OFFSET + id,
            uuid = uuid,
            source = FoodSource.CUSTOM_USER,
            name = name,
            brand = brand,
            category = "Custom Foods",
            foodType = FoodType.CUSTOM,
            isUserOwned = true,
            nutrition = Nutrition(
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
            servings = listOf(serving)
        )
    }
}
