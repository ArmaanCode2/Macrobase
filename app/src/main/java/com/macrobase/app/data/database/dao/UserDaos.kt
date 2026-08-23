package com.macrobase.app.data.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.macrobase.app.data.database.entity.CustomFoodEntity
import com.macrobase.app.data.database.entity.DiaryEntryEntity
import com.macrobase.app.data.database.entity.RecipeEntity
import com.macrobase.app.data.database.entity.WaterLogEntity
import com.macrobase.app.data.database.entity.WeightEntryEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface DiaryDao {
    @Query("SELECT * FROM diary_entries WHERE dateEpochDay = :epochDay ORDER BY id ASC")
    fun getEntriesForDate(epochDay: Long): Flow<List<DiaryEntryEntity>>

    @Query("SELECT * FROM diary_entries ORDER BY dateEpochDay ASC, id ASC")
    suspend fun getAllEntries(): List<DiaryEntryEntity>

    @Query("SELECT * FROM diary_entries WHERE id = :entryId LIMIT 1")
    suspend fun getEntryById(entryId: Long): DiaryEntryEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEntry(entry: DiaryEntryEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEntries(entries: List<DiaryEntryEntity>)

    @Query("DELETE FROM diary_entries WHERE id = :entryId")
    suspend fun deleteEntry(entryId: Long)

    @Query("DELETE FROM diary_entries")
    suspend fun clearAllEntries()

    @Query("SELECT dateEpochDay, SUM(loggedCalories) as totalCal FROM diary_entries WHERE dateEpochDay BETWEEN :startEpochDay AND :endEpochDay GROUP BY dateEpochDay")
    suspend fun getMonthlyCalorieSummaries(startEpochDay: Long, endEpochDay: Long): List<DateCalorieSummary>

    @Query("SELECT dateEpochDay, SUM(loggedCalories) as totalCal FROM diary_entries WHERE dateEpochDay BETWEEN :startEpochDay AND :endEpochDay GROUP BY dateEpochDay")
    fun observeMonthlyCalorieSummaries(startEpochDay: Long, endEpochDay: Long): Flow<List<DateCalorieSummary>>

    @Query("SELECT dateEpochDay, SUM(loggedCalories) as totalCal, SUM(loggedProtein) as totalProtein, SUM(loggedCarbs) as totalCarbs, SUM(loggedFat) as totalFat FROM diary_entries WHERE dateEpochDay BETWEEN :startEpochDay AND :endEpochDay GROUP BY dateEpochDay")
    suspend fun getDateNutritionSummaries(startEpochDay: Long, endEpochDay: Long): List<DateNutritionSummary>

    @Query("SELECT dateEpochDay, SUM(loggedCalories) as totalCal, SUM(loggedProtein) as totalProtein, SUM(loggedCarbs) as totalCarbs, SUM(loggedFat) as totalFat FROM diary_entries WHERE dateEpochDay BETWEEN :startEpochDay AND :endEpochDay GROUP BY dateEpochDay")
    fun observeDateNutritionSummaries(startEpochDay: Long, endEpochDay: Long): Flow<List<DateNutritionSummary>>
}

data class DateCalorieSummary(
    val dateEpochDay: Long,
    val totalCal: Double
)

data class DateNutritionSummary(
    val dateEpochDay: Long,
    val totalCal: Double,
    val totalProtein: Double,
    val totalCarbs: Double,
    val totalFat: Double
)

@Dao
interface CustomFoodDao {
    @Query("SELECT * FROM custom_foods ORDER BY name ASC")
    fun observeAllCustomFoods(): Flow<List<CustomFoodEntity>>

    @Query("SELECT * FROM custom_foods ORDER BY name ASC")
    suspend fun getAllCustomFoods(): List<CustomFoodEntity>

    @Query("SELECT * FROM custom_foods WHERE name LIKE '%' || :query || '%' LIMIT :limit")
    suspend fun searchCustomFoods(query: String, limit: Int): List<CustomFoodEntity>

    @Query("SELECT * FROM custom_foods WHERE id = :id")
    suspend fun getCustomFoodById(id: Long): CustomFoodEntity?

    @Query("SELECT * FROM custom_foods WHERE uuid = :uuid LIMIT 1")
    suspend fun getCustomFoodByUuid(uuid: String): CustomFoodEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCustomFood(food: CustomFoodEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCustomFoods(foods: List<CustomFoodEntity>)

    @Query("DELETE FROM custom_foods WHERE id = :id")
    suspend fun deleteCustomFood(id: Long)

    @Query("DELETE FROM custom_foods")
    suspend fun clearAllCustomFoods()
}

@Dao
interface RecipeDao {
    @Query("SELECT * FROM recipes ORDER BY name ASC")
    fun observeAllRecipes(): Flow<List<RecipeEntity>>

    @Query("SELECT * FROM recipes ORDER BY name ASC")
    suspend fun getAllRecipes(): List<RecipeEntity>

    @Query("SELECT * FROM recipes WHERE uuid = :uuid LIMIT 1")
    suspend fun getRecipeByUuid(uuid: String): RecipeEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRecipe(recipe: RecipeEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRecipes(recipes: List<RecipeEntity>)

    @Query("DELETE FROM recipes WHERE id = :id")
    suspend fun deleteRecipe(id: Long)

    @Query("DELETE FROM recipes")
    suspend fun clearAllRecipes()
}

@Dao
interface WeightDao {
    @Query("SELECT * FROM weight_entries ORDER BY dateEpochDay ASC")
    fun getAllWeightEntries(): Flow<List<WeightEntryEntity>>

    @Query("SELECT * FROM weight_entries ORDER BY dateEpochDay ASC")
    suspend fun getAllWeightEntriesList(): List<WeightEntryEntity>

    @Query("SELECT * FROM weight_entries WHERE dateEpochDay BETWEEN :startEpochDay AND :endEpochDay ORDER BY dateEpochDay ASC")
    fun getWeightEntriesBetween(startEpochDay: Long, endEpochDay: Long): Flow<List<WeightEntryEntity>>

    @Query("SELECT * FROM weight_entries WHERE dateEpochDay = :epochDay LIMIT 1")
    fun getWeightEntryForDate(epochDay: Long): Flow<WeightEntryEntity?>

    @Query("SELECT * FROM weight_entries WHERE dateEpochDay = :epochDay LIMIT 1")
    suspend fun getWeightEntrySnapshot(epochDay: Long): WeightEntryEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertWeightEntry(entry: WeightEntryEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertWeightEntries(entries: List<WeightEntryEntity>)

    @Query("DELETE FROM weight_entries WHERE id = :id")
    suspend fun deleteWeightEntry(id: Long)

    @Query("DELETE FROM weight_entries WHERE dateEpochDay = :epochDay")
    suspend fun deleteWeightEntryForDate(epochDay: Long)

    @Query("DELETE FROM weight_entries")
    suspend fun clearAllWeightEntries()
}

@Dao
interface WaterDao {
    @Query("SELECT SUM(amountMl) FROM water_logs WHERE dateEpochDay = :epochDay")
    fun getWaterIntakeForDate(epochDay: Long): Flow<Double?>

    @Query("SELECT * FROM water_logs WHERE dateEpochDay = :epochDay ORDER BY timestamp ASC")
    fun getWaterEntriesForDate(epochDay: Long): Flow<List<WaterLogEntity>>

    @Query("SELECT * FROM water_logs ORDER BY dateEpochDay ASC, timestamp ASC")
    suspend fun getAllWaterLogsList(): List<WaterLogEntity>

    @Insert
    suspend fun insertWaterLog(log: WaterLogEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertWaterLogs(logs: List<WaterLogEntity>)

    @androidx.room.Update
    suspend fun updateWaterLog(log: WaterLogEntity)

    @Query("DELETE FROM water_logs WHERE id = :id")
    suspend fun deleteWaterLog(id: Long)

    @Query("DELETE FROM water_logs")
    suspend fun clearAllWaterLogs()

    @Query("SELECT * FROM water_logs WHERE dateEpochDay BETWEEN :startEpochDay AND :endEpochDay ORDER BY dateEpochDay ASC, timestamp ASC")
    fun getWaterLogsBetween(startEpochDay: Long, endEpochDay: Long): Flow<List<WaterLogEntity>>
}
