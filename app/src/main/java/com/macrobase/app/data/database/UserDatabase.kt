package com.macrobase.app.data.database

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.Log
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
import java.io.File

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

        /**
         * Version 3 reached a phone with two `diary_entries` layouts. The 7 Oct 2026 build had
         * `loggedFiber`, `loggedSugar` and `loggedSodium` as `REAL NOT NULL DEFAULT 0.0` and none of
         * the other three; the current layout has all six nullable. Room rejects the old layout
         * under version 3, so that phone crashed on open. This rebuilds the table in the current
         * layout from whichever columns it finds and copies every row; SQLite cannot drop a
         * NOT NULL in place.
         * - A nutrient column that was NOT NULL stored 0.0 for "not recorded" (BUG-037), so its
         *   0.0 becomes null, as a backup from before format 1.3.0 is restored. Other values are kept.
         * - A column the current layout lacks becomes null.
         * - A column this code does not know is kept, with every row, in `diary_entries_v3_backup`.
         * - The AUTOINCREMENT high-water mark is kept, so a deleted entry's id is never reused.
         */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                val notNull = mutableMapOf<String, Boolean>()
                db.query("PRAGMA table_info(`diary_entries`)").use { cursor ->
                    val name = cursor.getColumnIndexOrThrow("name")
                    val flag = cursor.getColumnIndexOrThrow("notnull")
                    while (cursor.moveToNext()) notNull[cursor.getString(name).lowercase()] = cursor.getInt(flag) == 1
                }
                val known = DIARY_COLUMNS.filter { it.lowercase() in notNull }
                val target = known.joinToString(", ") { "`$it`" }
                val source = known.joinToString(", ") {
                    if (it in NUTRIENT_COLUMNS && notNull.getValue(it.lowercase())) "NULLIF(`$it`, 0.0)" else "`$it`"
                }
                if ((notNull.keys - DIARY_COLUMNS.map { it.lowercase() }.toSet()).isNotEmpty()) {
                    db.execSQL("CREATE TABLE `diary_entries_v3_backup` AS SELECT * FROM `diary_entries`")
                }
                var highestId: Long? = null
                db.query("SELECT `seq` FROM `sqlite_sequence` WHERE `name` = 'diary_entries'").use { cursor ->
                    if (cursor.moveToFirst()) highestId = cursor.getLong(0)
                }

                db.execSQL("DROP TABLE IF EXISTS `diary_entries_new`")
                db.execSQL(
                    "CREATE TABLE `diary_entries_new` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`uuid` TEXT NOT NULL, `dateEpochDay` INTEGER NOT NULL, `mealType` TEXT NOT NULL, " +
                        "`foodId` INTEGER NOT NULL, `foodName` TEXT NOT NULL, `userQuantity` REAL NOT NULL, " +
                        "`servingDescription` TEXT NOT NULL, `gramWeight` REAL NOT NULL, " +
                        "`loggedCalories` REAL NOT NULL, `loggedProtein` REAL NOT NULL, `loggedCarbs` REAL NOT NULL, " +
                        "`loggedFat` REAL NOT NULL, `loggedFiber` REAL, `loggedSugar` REAL, `loggedSodium` REAL, " +
                        "`loggedSaturatedFat` REAL, `loggedTransFat` REAL, `loggedCholesterol` REAL, " +
                        "`createdAt` INTEGER NOT NULL)"
                )
                db.execSQL("INSERT INTO `diary_entries_new` ($target) SELECT $source FROM `diary_entries`")
                db.execSQL("DROP TABLE `diary_entries`")
                db.execSQL("ALTER TABLE `diary_entries_new` RENAME TO `diary_entries`")
                highestId?.let {
                    db.execSQL("UPDATE `sqlite_sequence` SET `seq` = MAX(`seq`, ?) WHERE `name` = 'diary_entries'", arrayOf<Any>(it))
                }
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_diary_entries_dateEpochDay` ON `diary_entries` (`dateEpochDay`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_diary_entries_mealType` ON `diary_entries` (`mealType`)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_diary_entries_uuid` ON `diary_entries` (`uuid`)")
            }
        }

        /**
         * Custom foods can state saturated fat, so the rank can score it and the label scanner's
         * reading is kept. Nullable with no default: foods saved before this version never stated
         * it, so they read as "not known" (null), not 0.0 (AGENTS.md section 2.4).
         */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE custom_foods ADD COLUMN saturatedFatGrams REAL")
            }
        }

        /** The columns of `diary_entries` since version 3, in table order. */
        private val DIARY_COLUMNS = listOf(
            "id", "uuid", "dateEpochDay", "mealType", "foodId", "foodName", "userQuantity",
            "servingDescription", "gramWeight", "loggedCalories", "loggedProtein", "loggedCarbs",
            "loggedFat", "loggedFiber", "loggedSugar", "loggedSodium", "loggedSaturatedFat",
            "loggedTransFat", "loggedCholesterol", "createdAt"
        )

        private val NUTRIENT_COLUMNS = setOf(
            "loggedFiber", "loggedSugar", "loggedSodium", "loggedSaturatedFat", "loggedTransFat", "loggedCholesterol"
        )

        /** Every migration, in order. The database builder and the migration tests both use this list. */
        val ALL_MIGRATIONS: Array<Migration> = arrayOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)

        /**
         * The app's user database. There is deliberately no destructive fallback: a missing migration
         * or a downgrade makes Room throw instead of silently deleting every diary entry (BUG-046).
         */
        fun create(context: Context, name: String = DatabaseConfig.USER_DATABASE_NAME): UserDatabase {
            backUpBeforeUpgrade(context, name)
            return Room.databaseBuilder(context, UserDatabase::class.java, name)
                .addMigrations(*ALL_MIGRATIONS)
                .build()
        }

        /**
         * Copies the database file (and its write-ahead log) to `files/database-backups/<name>.v<N>`
         * before Room upgrades it from version N, once per version. A failed upgrade rolls back on
         * its own; this copy covers what a rollback cannot, such as androidx deleting a database
         * whose open keeps failing with an SQLiteException. A failed copy never blocks the app.
         */
        internal fun backUpBeforeUpgrade(context: Context, name: String) {
            val file = context.getDatabasePath(name)
            if (!file.isFile) return
            try {
                val version = SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { it.version }
                if (version <= 0 || version >= DatabaseConfig.USER_DATABASE_VERSION) return
                val backup = File(File(context.filesDir, "database-backups"), "$name.v$version")
                if (backup.exists()) return
                backup.parentFile?.mkdirs()
                File(file.path + "-wal").takeIf { it.isFile }?.copyTo(File(backup.path + "-wal"), overwrite = true)
                file.copyTo(backup)
            } catch (e: Exception) {
                Log.w("UserDatabase", "Could not back up $name before upgrading it", e)
            }
        }
    }
}
