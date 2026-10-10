package com.macrobase.app.domain.model

import com.macrobase.app.core.config.PortabilityConfig

/**
 * Manifest included at the root of every MacroBase backup ZIP archive.
 */
data class BackupManifestDto(
    val format: String = PortabilityConfig.BACKUP_FORMAT_NAME,
    val backupVersion: String = PortabilityConfig.BACKUP_FORMAT_VERSION,
    val appVersion: String = PortabilityConfig.CURRENT_APP_VERSION,
    val schemaVersion: Int = PortabilityConfig.DATABASE_SCHEMA_VERSION,
    val exportedAt: String,
    val timeZone: String,
    val deviceInfo: String = "Android",
    val counts: BackupRecordCountsDto,
    val checksums: Map<String, String> = emptyMap(),
    val compatibility: BackupCompatibilityDto = BackupCompatibilityDto()
)

data class BackupRecordCountsDto(
    val diaryEntries: Int = 0,
    val customFoods: Int = 0,
    val recipes: Int = 0,
    val weightEntries: Int = 0,
    val waterEntries: Int = 0,
    val goals: Int = 0,
    val preferences: Int = 0
) {
    val totalRecords: Int
        get() = diaryEntries + customFoods + recipes + weightEntries + waterEntries + goals + preferences
}

data class BackupCompatibilityDto(
    val minSupportedAppVersion: String = PortabilityConfig.MIN_SUPPORTED_BACKUP_VERSION,
    val supportedSchemaVersion: Int = PortabilityConfig.DATABASE_SCHEMA_VERSION
)

/**
 * Portable Backup DTO for Diary Entries.
 */
data class DiaryEntryBackupDto(
    val uuid: String,
    val dateEpochDay: Long,
    val dateString: String,
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
    val createdAt: Long,
    // Added in backup format 1.1.0; null when restoring older backups
    val loggedFiber: Double? = null,
    val loggedSugar: Double? = null,
    val loggedSodium: Double? = null,
    // Added in backup format 1.3.0
    val loggedSaturatedFat: Double? = null,
    val loggedTransFat: Double? = null,
    val loggedCholesterol: Double? = null,
    /** Stable id of the logged custom food; Room row ids change when a backup is restored. */
    val customFoodUuid: String? = null,
    /** Stable id of the logged recipe, for the same reason. */
    val recipeUuid: String? = null
)

/**
 * Portable Backup DTO for Custom Foods.
 */
data class CustomFoodBackupDto(
    val uuid: String,
    val name: String,
    val brand: String?,
    val servingSize: Double,
    val servingUnit: String,
    val calories: Double,
    val proteinGrams: Double,
    val carbsGrams: Double,
    val fatGrams: Double,
    val fiberGrams: Double?,
    val sugarGrams: Double?,
    val sodiumMg: Double?,
    val potassiumMg: Double?,
    val calciumMg: Double?,
    val ironMg: Double?,
    val createdAt: Long,
    val customUnitName: String? = null
)

/**
 * Portable Backup DTO for Recipes.
 */
data class RecipeBackupDto(
    val uuid: String,
    val name: String,
    val servingsProduced: Int,
    val ingredientsJson: String,
    val caloriesPerServing: Double,
    val proteinPerServing: Double,
    val carbsPerServing: Double,
    val fatPerServing: Double,
    val createdAt: Long
)

/**
 * Portable Backup DTO for Weight Entries.
 */
data class WeightEntryBackupDto(
    val dateEpochDay: Long,
    val dateString: String,
    val weightKg: Double,
    val note: String?,
    val createdAt: Long
)

/**
 * Portable Backup DTO for Water Logs.
 */
data class WaterLogBackupDto(
    val dateEpochDay: Long,
    val dateString: String,
    val amountMl: Double,
    val timestamp: Long
)

/**
 * Portable Backup DTO for Goals.
 *
 * Backups before format 1.2.0 carry only the four targets; the strategy fields are then null and
 * a restore keeps the device's own strategy, scheduled change and history (BUG-016).
 */
data class GoalBackupDto(
    val dailyCalorieGoal: Double,
    val carbPercentage: Double,
    val proteinPercentage: Double,
    val fatPercentage: Double,
    val fitnessGoal: String? = null,
    val maintenanceCalories: Double? = null,
    val scheduledFitnessGoal: String? = null,
    val scheduledMaintenanceCalories: Double? = null,
    /** ISO date (yyyy-MM-dd) the scheduled strategy takes effect. */
    val scheduledEffectiveDate: String? = null,
    /** Strategy history, oldest first; null when the backup did not record it. */
    val transitions: List<GoalTransitionBackupDto>? = null
) {
    /** A calorie target above 0 and non-negative macro shares, as the Goals screen allows. */
    val hasUsableTargets: Boolean
        get() = dailyCalorieGoal.isFinite() && dailyCalorieGoal > 0.0 &&
            listOf(carbPercentage, proteinPercentage, fatPercentage).all { it.isFinite() && it >= 0.0 }

    companion object {
        /** The goal as the app sees it now, without history. */
        fun from(goal: Goal) = GoalBackupDto(
            dailyCalorieGoal = goal.dailyCalorieGoal,
            carbPercentage = goal.carbPercentage,
            proteinPercentage = goal.proteinPercentage,
            fatPercentage = goal.fatPercentage,
            fitnessGoal = goal.fitnessGoal.name,
            maintenanceCalories = goal.maintenanceCalories,
            scheduledFitnessGoal = goal.scheduledFitnessGoal?.name,
            scheduledMaintenanceCalories = goal.scheduledMaintenanceCalories,
            scheduledEffectiveDate = goal.scheduledEffectiveDate?.toString()
        )
    }
}

/** One strategy change: the goals in force from [effectiveDate] (ISO date) on. */
data class GoalTransitionBackupDto(
    val effectiveDate: String,
    val fitnessGoal: String,
    val maintenanceCalories: Double,
    val dailyCalorieGoal: Double,
    val carbPercentage: Double,
    val proteinPercentage: Double,
    val fatPercentage: Double
)

/**
 * Portable Backup DTO for User Preferences.
 */
data class UserPreferencesBackupDto(
    val firstName: String,
    val lastName: String,
    val timeZone: String,
    val unitSystem: String,
    val heightCm: Double?,
    val currentWeightKg: Double?,
    val targetWeightKg: Double?,
    val dailyWaterGoalMl: Double
)

/**
 * Complete in-memory aggregate container for all portable user data.
 */
data class MacroBaseBackupData(
    val manifest: BackupManifestDto,
    val diaryEntries: List<DiaryEntryBackupDto> = emptyList(),
    val customFoods: List<CustomFoodBackupDto> = emptyList(),
    val recipes: List<RecipeBackupDto> = emptyList(),
    val weightEntries: List<WeightEntryBackupDto> = emptyList(),
    val waterEntries: List<WaterLogBackupDto> = emptyList(),
    val goals: GoalBackupDto? = null,
    val preferences: UserPreferencesBackupDto? = null
)

/**
 * Result of validating an archive prior to restoration.
 */
data class BackupValidationResult(
    val isValid: Boolean,
    val manifest: BackupManifestDto? = null,
    val backupData: MacroBaseBackupData? = null,
    val errorMessage: String? = null
)

/**
 * Backup preview presented to the user prior to committing restoration.
 */
data class BackupPreview(
    val exportedAt: String,
    val appVersion: String,
    val backupVersion: String,
    val counts: BackupRecordCountsDto,
    val backupData: MacroBaseBackupData
)

/**
 * Restoration Mode: MERGE (non-destructive) vs OVERWRITE (complete replacement).
 */
enum class ImportMode {
    MERGE,
    OVERWRITE
}

/**
 * Result metrics after executing a backup restoration.
 */
data class ImportResult(
    val isSuccess: Boolean,
    val mode: ImportMode,
    val recordsImported: Int = 0,
    val recordsSkipped: Int = 0,
    val recordsUpdated: Int = 0,
    val conflictsResolved: Int = 0,
    val message: String = "",
    val errorMessage: String? = null
)
