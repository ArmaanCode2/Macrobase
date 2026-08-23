package com.macrobase.app.data.database

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import com.macrobase.app.core.config.DatabaseConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream

/**
 * Thread-safe manager responsible for copying, validating, versioning,
 * and opening the read-only built-in SQLite food database (built_in_foods.db).
 */
class BuiltInDatabaseManager(
    private val context: Context
) {
    private val mutex = Mutex()
    private var database: SQLiteDatabase? = null

    companion object {
        private const val TAG = "BuiltInDbManager"
        const val EXPECTED_DATABASE_VERSION = "1.0.0"
        const val EXPECTED_SCHEMA_VERSION = "1"
    }

    /**
     * Metadata extracted from the built-in database.
     */
    data class DatabaseInfo(
        val databaseVersion: String,
        val schemaVersion: String,
        val buildTimestamp: String?,
        val totalFoods: Int,
        val totalServings: Int
    )

    /**
     * Retrieves an open read-only instance of the built-in food database.
     * Ensures database is copied and validated on first launch.
     */
    suspend fun getDatabase(): SQLiteDatabase = withContext(Dispatchers.IO) {
        mutex.withLock {
            database?.let { if (it.isOpen) return@withLock it }

            val dbFile = context.getDatabasePath(DatabaseConfig.BUILT_IN_FOODS_DB_NAME)
            ensureDatabaseInstalled(dbFile)

            val openedDb = SQLiteDatabase.openDatabase(
                dbFile.absolutePath,
                null,
                SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS
            )

            validateDatabase(openedDb)
            database = openedDb
            openedDb
        }
    }

    /**
     * Reads database metadata info.
     */
    suspend fun getDatabaseInfo(): DatabaseInfo = withContext(Dispatchers.IO) {
        val db = getDatabase()
        var dbVer = "unknown"
        var schemaVer = "unknown"
        var timestamp: String? = null
        var totalFoods = 0
        var totalServings = 0

        try {
            db.rawQuery("SELECT key, value FROM database_metadata", null).use { cursor ->
                val keyIdx = cursor.getColumnIndexOrThrow("key")
                val valIdx = cursor.getColumnIndexOrThrow("value")
                while (cursor.moveToNext()) {
                    val key = cursor.getString(keyIdx)
                    val value = cursor.getString(valIdx)
                    when (key) {
                        "database_version" -> dbVer = value
                        "schema_version" -> schemaVer = value
                        "build_timestamp" -> timestamp = value
                        "total_foods" -> totalFoods = value.toIntOrNull() ?: 0
                        "total_servings" -> totalServings = value.toIntOrNull() ?: 0
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed reading metadata: ${e.message}", e)
        }

        DatabaseInfo(
            databaseVersion = dbVer,
            schemaVersion = schemaVer,
            buildTimestamp = timestamp,
            totalFoods = totalFoods,
            totalServings = totalServings
        )
    }

    /**
     * Installs or updates the database from assets if missing or invalid.
     */
    private fun ensureDatabaseInstalled(dbFile: File) {
        if (dbFile.exists() && isExistingDatabaseValid(dbFile)) {
            Log.d(TAG, "Reusing existing valid built-in database at ${dbFile.absolutePath}")
            return
        }

        Log.i(TAG, "Installing built-in food database from asset: ${DatabaseConfig.BUILT_IN_FOODS_ASSET_PATH}")
        dbFile.parentFile?.mkdirs()

        val tempFile = File(dbFile.parentFile, "${DatabaseConfig.BUILT_IN_FOODS_DB_NAME}.tmp")
        if (tempFile.exists()) tempFile.delete()

        try {
            context.assets.open(DatabaseConfig.BUILT_IN_FOODS_ASSET_PATH).use { input ->
                FileOutputStream(tempFile).use { output ->
                    copyStream(input, output)
                }
            }

            if (!tempFile.exists() || tempFile.length() == 0L) {
                throw IllegalStateException("Failed copying database asset: temp file is empty")
            }

            // Verify copied database
            if (!isExistingDatabaseValid(tempFile)) {
                tempFile.delete()
                throw IllegalStateException("Copied database asset failed integrity validation")
            }

            // Atomic replacement
            if (dbFile.exists()) dbFile.delete()
            if (!tempFile.renameTo(dbFile)) {
                // Fallback copy if rename fails across file system boundaries
                tempFile.copyTo(dbFile, overwrite = true)
                tempFile.delete()
            }

            Log.i(TAG, "Successfully installed built-in database (${dbFile.length()} bytes)")
        } catch (e: Exception) {
            Log.e(TAG, "Critical error installing database: ${e.message}", e)
            tempFile.delete()
            throw e
        }
    }

    private fun isExistingDatabaseValid(file: File): Boolean {
        if (!file.exists() || file.length() < 1024) return false
        var testDb: SQLiteDatabase? = null
        return try {
            testDb = SQLiteDatabase.openDatabase(
                file.absolutePath,
                null,
                SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS
            )
            validateDatabase(testDb)
            true
        } catch (e: Exception) {
            Log.w(TAG, "Database validation check failed for ${file.name}: ${e.message}")
            false
        } finally {
            try {
                testDb?.close()
            } catch (ignored: Exception) {}
        }
    }

    private fun validateDatabase(db: SQLiteDatabase) {
        // Validate core tables exist
        db.rawQuery("SELECT name FROM sqlite_master WHERE type='table' AND name IN ('foods', 'servings', 'categories', 'foods_fts', 'database_metadata')", null).use { cursor ->
            if (cursor.count < 5) {
                throw IllegalStateException("Database missing required schema tables. Found count: ${cursor.count}")
            }
        }

        // Validate foods count
        db.rawQuery("SELECT COUNT(*) FROM foods", null).use { cursor ->
            if (cursor.moveToFirst()) {
                val count = cursor.getInt(0)
                if (count < 1000) {
                    throw IllegalStateException("Database has insufficient food records: $count")
                }
            }
        }
    }

    private fun copyStream(input: InputStream, output: FileOutputStream) {
        val buffer = ByteArray(64 * 1024)
        var length: Int
        while (input.read(buffer).also { length = it } > 0) {
            output.write(buffer, 0, length)
        }
        output.flush()
    }

    /**
     * Closes the open database connection.
     */
    fun close() {
        try {
            database?.close()
        } catch (ignored: Exception) {}
        database = null
    }
}
