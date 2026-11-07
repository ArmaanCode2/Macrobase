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
 * Thread-safe manager responsible for copying, validating, versioning, and opening one of the app's
 * read-only SQLite food catalogs: the built-in catalog (built_in_foods.db) by default, or
 * [Catalog.BRANDED], the Open Food Facts products (branded_foods.db).
 */
class BuiltInDatabaseManager(
    private val context: Context,
    private val catalog: Catalog = Catalog.MAIN
) {
    private val mutex = Mutex()
    private var database: SQLiteDatabase? = null

    /** The content fingerprint of the catalog in this build's assets, or null if the APK has none. */
    private val bundledFingerprint: String? by lazy {
        try {
            context.assets.open(catalog.fingerprintAssetPath).bufferedReader().use { it.readText().trim() }
        } catch (e: Exception) {
            Log.w(TAG, "No bundled catalog fingerprint: ${e.message}")
            null
        }
    }

    companion object {
        private const val TAG = "BuiltInDbManager"
        const val EXPECTED_DATABASE_VERSION = "2.5.0"
        const val EXPECTED_SCHEMA_VERSION = "2"
        const val EXPECTED_DATASET_NAME = "macrobase_indian_foods_canonical"
        // tools/catalog/build_branded.py DATABASE_VERSION and dataset_name
        const val BRANDED_DATABASE_VERSION = "1.0.0"
        const val BRANDED_DATASET_NAME = "macrobase_branded_foods_off"

        /**
         * An installed catalog is reused only if it is the dataset, version and content bundled with
         * this build; anything else is replaced from assets so catalog fixes reach existing installs,
         * including a test phone that has an earlier build of the same version. Without a bundled
         * fingerprint the version alone decides.
         */
        fun isCurrentCatalog(
            datasetName: String?,
            databaseVersion: String?,
            fingerprint: String? = null,
            bundledFingerprint: String? = null,
            expected: Catalog = Catalog.MAIN
        ): Boolean =
            datasetName == expected.datasetName && databaseVersion == expected.databaseVersion &&
                (bundledFingerprint == null || fingerprint == bundledFingerprint)
    }

    /** One read-only catalog the app ships: its files, and the dataset and version it must hold. */
    data class Catalog(
        val dbName: String,
        val assetPath: String,
        val fingerprintAssetPath: String,
        val datasetName: String,
        val databaseVersion: String,
        val minimumFoods: Int
    ) {
        companion object {
            val MAIN = Catalog(
                DatabaseConfig.BUILT_IN_FOODS_DB_NAME, DatabaseConfig.BUILT_IN_FOODS_ASSET_PATH,
                DatabaseConfig.BUILT_IN_FOODS_FINGERPRINT_ASSET_PATH,
                BuiltInDatabaseManager.EXPECTED_DATASET_NAME, BuiltInDatabaseManager.EXPECTED_DATABASE_VERSION, 500
            )
            val BRANDED = Catalog(
                DatabaseConfig.BRANDED_FOODS_DB_NAME, DatabaseConfig.BRANDED_FOODS_ASSET_PATH,
                DatabaseConfig.BRANDED_FOODS_FINGERPRINT_ASSET_PATH,
                BuiltInDatabaseManager.BRANDED_DATASET_NAME, BuiltInDatabaseManager.BRANDED_DATABASE_VERSION, 100
            )
        }
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

            val dbFile = context.getDatabasePath(catalog.dbName)
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

        Log.i(TAG, "Installing food database from asset: ${catalog.assetPath}")
        dbFile.parentFile?.mkdirs()

        val tempFile = File(dbFile.parentFile, "${catalog.dbName}.tmp")
        if (tempFile.exists()) tempFile.delete()

        try {
            context.assets.open(catalog.assetPath).use { input ->
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

            // Verify dataset, version and content match the catalog bundled with this build
            var datasetName: String? = null
            var databaseVersion: String? = null
            var fingerprint: String? = null
            testDb.rawQuery(
                "SELECT key, value FROM database_metadata WHERE key IN ('dataset_name', 'database_version', 'content_fingerprint')",
                null
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    when (cursor.getString(0)) {
                        "dataset_name" -> datasetName = cursor.getString(1)
                        "database_version" -> databaseVersion = cursor.getString(1)
                        "content_fingerprint" -> fingerprint = cursor.getString(1)
                    }
                }
            }
            isCurrentCatalog(datasetName, databaseVersion, fingerprint, bundledFingerprint, catalog)
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
        db.rawQuery("SELECT name FROM sqlite_master WHERE type='table' AND name IN ('foods', 'servings', 'categories', 'food_search', 'database_metadata')", null).use { cursor ->
            if (cursor.count < 5) {
                throw IllegalStateException("Database missing required schema tables. Found count: ${cursor.count}")
            }
        }

        // Validate foods count
        db.rawQuery("SELECT COUNT(*) FROM foods", null).use { cursor ->
            if (cursor.moveToFirst()) {
                val count = cursor.getInt(0)
                if (count < catalog.minimumFoods) {
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
