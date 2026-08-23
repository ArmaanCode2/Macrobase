package com.macrobase.app.core.config

/**
 * Centralized Database Names and Version Constants.
 */
object DatabaseConfig {
    // Built-in Static Food Database (Bundled SQLite asset)
    const val BUILT_IN_FOODS_DB_NAME = "built_in_foods.db"
    const val BUILT_IN_FOODS_ASSET_PATH = "databases/built_in_foods.db"
    const val BUILT_IN_FOODS_DB_VERSION = 1

    // User Data Room Database (Diary logs, custom foods, recipes, weight, water)
    const val USER_DATABASE_NAME = "macrobase_user.db"
    const val USER_DATABASE_VERSION = 2

    // Search Configuration
    const val SEARCH_DEBOUNCE_MILLIS = 250L
    const val SEARCH_PAGE_SIZE = 30
}
