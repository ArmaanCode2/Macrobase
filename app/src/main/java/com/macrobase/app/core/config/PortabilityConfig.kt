package com.macrobase.app.core.config

/**
 * Portability, Backup Schema, Versioning, and Compatibility Constants.
 */
object PortabilityConfig {
    const val BACKUP_FORMAT_NAME = "MacroBaseBackup"
    // 1.1.0: diary entries carry fiber/sugar/sodium and the custom food and recipe uuids. Additive, so
    // apps reading 1.x still import it and older 1.0.0 backups still import here.
    // 1.2.0: goals.json also carries the fitness strategy, maintenance calories, a scheduled change
    // and the strategy history (BUG-016). Additive in the same way.
    // 1.3.0: diary entries also carry saturated fat, trans fat and cholesterol, and an unknown
    // secondary nutrient is written as null instead of 0.0 (BUG-037). Older backups wrote 0.0 for
    // "unknown", so their zeros are read back as unknown (BackupJsonSerializer.legacyZerosAsUnknown).
    const val BACKUP_FORMAT_VERSION = "1.3.0"
    const val CURRENT_APP_VERSION = "1.0.0"
    const val DATABASE_SCHEMA_VERSION = DatabaseConfig.USER_DATABASE_VERSION
    const val MIN_SUPPORTED_BACKUP_VERSION = "1.0.0"

    const val BACKUP_ZIP_FILENAME_PREFIX = "MacroBase_Backup_"
    const val BACKUP_FILE_EXTENSION = ".zip"
    const val BACKUP_MIME_TYPE = "application/zip"

    // Structured JSON file names within the ZIP archive
    const val FILE_MANIFEST = "manifest.json"
    const val FILE_DIARY = "diary.json"
    const val FILE_CUSTOM_FOODS = "custom_foods.json"
    const val FILE_RECIPES = "recipes.json"
    const val FILE_WEIGHT = "weight.json"
    const val FILE_WATER = "water.json"
    const val FILE_GOALS = "goals.json"
    const val FILE_PREFERENCES = "preferences.json"
}
