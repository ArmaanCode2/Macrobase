package com.macrobase.app.data.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
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
    // Schemas are written to app/schemas; every version needs its JSON and a migration test
    exportSchema = true
)
abstract class UserDatabase : RoomDatabase() {
    abstract fun diaryDao(): DiaryDao
    abstract fun customFoodDao(): CustomFoodDao
    abstract fun recipeDao(): RecipeDao
    abstract fun weightDao(): WeightDao
    abstract fun waterDao(): WaterDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE custom_foods ADD COLUMN customUnitName TEXT")
            }
        }

        /**
         * Diary snapshots of the secondary nutrients. The columns are nullable with no default:
         * null means "not known" and 0.0 means "none" (AGENTS.md section 2.4, BUG-037). Entries
         * logged before this version never recorded them, so they become null, not 0.0.
         */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE diary_entries ADD COLUMN loggedFiber REAL")
                db.execSQL("ALTER TABLE diary_entries ADD COLUMN loggedSugar REAL")
                db.execSQL("ALTER TABLE diary_entries ADD COLUMN loggedSodium REAL")
                db.execSQL("ALTER TABLE diary_entries ADD COLUMN loggedSaturatedFat REAL")
                db.execSQL("ALTER TABLE diary_entries ADD COLUMN loggedTransFat REAL")
                db.execSQL("ALTER TABLE diary_entries ADD COLUMN loggedCholesterol REAL")
            }
        }

        /** Every migration, in order. The database builder and the migration tests both use this list. */
        val ALL_MIGRATIONS: Array<Migration> = arrayOf(MIGRATION_1_2, MIGRATION_2_3)

        /**
         * The app's user database. There is deliberately no destructive fallback: a missing migration
         * or a downgrade makes Room throw instead of silently deleting every diary entry (BUG-046).
         */
        fun create(context: Context, name: String = DatabaseConfig.USER_DATABASE_NAME): UserDatabase =
            Room.databaseBuilder(context, UserDatabase::class.java, name)
                .addMigrations(*ALL_MIGRATIONS)
                .build()
    }
}
