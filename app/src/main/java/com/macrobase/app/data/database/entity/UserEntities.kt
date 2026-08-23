package com.macrobase.app.data.database.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "diary_entries",
    indices = [
        Index(value = ["dateEpochDay"]),
        Index(value = ["mealType"]),
        Index(value = ["uuid"], unique = true)
    ]
)
data class DiaryEntryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val uuid: String,
    val dateEpochDay: Long,
    val mealType: String,
    val foodId: Long,
    val foodName: String,
    val userQuantity: Double,
    val servingDescription: String,
    val gramWeight: Double,
    val loggedCalories: Double,
    val loggedProtein: Double,
    val loggedCarbs: Double,
    val loggedFat: Double,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "custom_foods",
    indices = [
        Index(value = ["uuid"], unique = true),
        Index(value = ["name"])
    ]
)
data class CustomFoodEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val uuid: String,
    val name: String,
    val brand: String? = null,
    val servingSize: Double,
    val servingUnit: String,
    val customUnitName: String? = null,
    val calories: Double,
    val proteinGrams: Double,
    val carbsGrams: Double,
    val fatGrams: Double,
    val fiberGrams: Double? = null,
    val sugarGrams: Double? = null,
    val sodiumMg: Double? = null,
    val potassiumMg: Double? = null,
    val calciumMg: Double? = null,
    val ironMg: Double? = null,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "recipes",
    indices = [Index(value = ["uuid"], unique = true)]
)
data class RecipeEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val uuid: String,
    val name: String,
    val servingsProduced: Int,
    val ingredientsJson: String,
    val caloriesPerServing: Double,
    val proteinPerServing: Double,
    val carbsPerServing: Double,
    val fatPerServing: Double,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "weight_entries",
    indices = [Index(value = ["dateEpochDay"], unique = true)]
)
data class WeightEntryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val dateEpochDay: Long,
    val weightKg: Double,
    val note: String? = null,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "water_logs",
    indices = [Index(value = ["dateEpochDay"])]
)
data class WaterLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val dateEpochDay: Long,
    val amountMl: Double,
    val timestamp: Long = System.currentTimeMillis()
)
