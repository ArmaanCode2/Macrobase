package com.macrobase.app.core.config

/**
 * Portability, Backup Schema, Versioning, and Compatibility Constants.
 */
object PortabilityConfig {
    const val BACKUP_FORMAT_NAME = "MacroBaseBackup"
    const val BACKUP_FORMAT_VERSION = "1.0.0"
    const val CURRENT_APP_VERSION = "1.0.0"
    const val DATABASE_SCHEMA_VERSION = 1
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
