package com.macrobase.app.data.database

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.macrobase.app.core.config.DatabaseConfig
import com.macrobase.app.data.database.dao.CustomFoodDao
import com.macrobase.app.data.database.dao.DiaryDao
import com.macrobase.app.data.database.dao.RecipeDao
import com.macrobase.app.data.database.dao.WaterDao
import com.macrobase.app.data.database.dao.WeightDao
import com.macrobase.app.data.database.entity.CustomFoodEntity
import com.macrobase.app.data.database.entity.DiaryEntryEntity
import com.macrobase.app.data.database.entity.RecipeEntity
import com.macrobase.app.data.database.entity.WaterLogEntity
import com.macrobase.app.data.database.entity.WeightEntryEntity

@Database(
    entities = [
        DiaryEntryEntity::class,
        CustomFoodEntity::class,
        RecipeEntity::class,
        WeightEntryEntity::class,
        WaterLogEntity::class
    ],
    version = DatabaseConfig.USER_DATABASE_VERSION,
    exportSchema = false
)
abstract class UserDatabase : RoomDatabase() {
    abstract fun diaryDao(): DiaryDao
    abstract fun customFoodDao(): CustomFoodDao
    abstract fun recipeDao(): RecipeDao
    abstract fun weightDao(): WeightDao
    abstract fun waterDao(): WaterDao

    companion object {
        /**
         * Migration infrastructure placeholder for future schema evolution.
         * Example: Migration from version 1 to 2 when new columns or tables are introduced.
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE custom_foods ADD COLUMN customUnitName TEXT")
            }
        }
    }
}
