package com.macrobase.app.data.repository

import androidx.room.withTransaction
import com.macrobase.app.core.config.PortabilityConfig
import com.macrobase.app.data.database.UserDatabase
import com.macrobase.app.data.database.entity.CustomFoodEntity
import com.macrobase.app.data.database.entity.DiaryEntryEntity
import com.macrobase.app.data.database.entity.RecipeEntity
import com.macrobase.app.data.database.entity.WaterLogEntity
import com.macrobase.app.data.database.entity.WeightEntryEntity
import com.macrobase.app.data.portability.BackupArchiveManager
import com.macrobase.app.domain.model.BackupCompatibilityDto
import com.macrobase.app.domain.model.BackupManifestDto
import com.macrobase.app.domain.model.BackupRecordCountsDto
import com.macrobase.app.domain.model.BackupValidationResult
import com.macrobase.app.domain.model.CustomFoodBackupDto
import com.macrobase.app.domain.model.DiaryEntryBackupDto
import com.macrobase.app.domain.model.ImportMode
import com.macrobase.app.domain.model.ImportResult
import com.macrobase.app.domain.model.MacroBaseBackupData
import com.macrobase.app.domain.model.Recipe
import com.macrobase.app.domain.model.RecipeBackupDto
import com.macrobase.app.domain.model.UnitSystem
import com.macrobase.app.domain.model.UserPreferences
import com.macrobase.app.domain.model.UserPreferencesBackupDto
import com.macrobase.app.domain.model.WaterLogBackupDto
import com.macrobase.app.domain.model.WeightEntryBackupDto
import com.macrobase.app.domain.repository.GoalsRepository
import com.macrobase.app.domain.repository.PortabilityRepository
import com.macrobase.app.domain.repository.PreferencesRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream
import java.time.Instant
import java.time.LocalDate
import java.util.TimeZone

class PortabilityRepositoryImpl(
    private val userDatabase: UserDatabase,
    private val goalsRepository: GoalsRepository,
    private val preferencesRepository: PreferencesRepository
) : PortabilityRepository {

    override suspend fun exportAllUserData(): MacroBaseBackupData = withContext(Dispatchers.IO) {
        val diaryEntities = userDatabase.diaryDao().getAllEntries()
        val customFoodEntities = userDatabase.customFoodDao().getAllCustomFoods()
        val recipeEntities = userDatabase.recipeDao().getAllRecipes()
        val weightEntities = userDatabase.weightDao().getAllWeightEntriesList()
        val waterEntities = userDatabase.waterDao().getAllWaterLogsList()
        val currentPrefs = preferencesRepository.getPreferences()

        val customFoodsByRowId = customFoodEntities.associateBy { it.id }
        // Write the uuid only when the row is still the logged food: entries mis-linked by
        // earlier restores must not be exported as authoritative links (import then falls back
        // to name matching).
        fun customFoodUuidFor(e: DiaryEntryEntity): String? =
            FoodRepositoryImpl.customRowIdOrNull(e.foodId)
                ?.let { customFoodsByRowId[it] }
                ?.takeIf { it.name.trim().equals(e.foodName.trim(), ignoreCase = true) }
                ?.uuid
        val recipesByRowId = recipeEntities.associateBy { it.id }
        fun recipeUuidFor(e: DiaryEntryEntity): String? =
            Recipe.rowIdOrNull(e.foodId)
                ?.let { recipesByRowId[it] }
                ?.takeIf { it.name.trim().equals(e.foodName.trim(), ignoreCase = true) }
                ?.uuid
        val diaryDtos = diaryEntities.map { e ->
            DiaryEntryBackupDto(
                uuid = e.uuid,
                dateEpochDay = e.dateEpochDay,
                dateString = LocalDate.ofEpochDay(e.dateEpochDay).toString(),
                mealType = e.mealType,
                foodId = e.foodId,
                foodName = e.foodName,
                userQuantity = e.userQuantity,
                servingDescription = e.servingDescription,
                gramWeight = e.gramWeight,
                loggedCalories = e.loggedCalories,
                loggedProtein = e.loggedProtein,
                loggedCarbs = e.loggedCarbs,
                loggedFat = e.loggedFat,
                createdAt = e.createdAt,
                loggedFiber = e.loggedFiber,
                loggedSugar = e.loggedSugar,
                loggedSodium = e.loggedSodium,
                loggedSaturatedFat = e.loggedSaturatedFat,
                loggedTransFat = e.loggedTransFat,
                loggedCholesterol = e.loggedCholesterol,
                customFoodUuid = customFoodUuidFor(e),
                recipeUuid = recipeUuidFor(e)
            )
        }

        val customFoodDtos = customFoodEntities.map { f ->
            CustomFoodBackupDto(
                uuid = f.uuid,
                name = f.name,
                brand = f.brand,
                servingSize = f.servingSize,
                servingUnit = f.servingUnit,
                customUnitName = f.customUnitName,
                calories = f.calories,
                proteinGrams = f.proteinGrams,
                carbsGrams = f.carbsGrams,
                fatGrams = f.fatGrams,
                fiberGrams = f.fiberGrams,
                sugarGrams = f.sugarGrams,
                sodiumMg = f.sodiumMg,
                potassiumMg = f.potassiumMg,
                calciumMg = f.calciumMg,
                ironMg = f.ironMg,
                createdAt = f.createdAt
            )
        }

        val recipeDtos = recipeEntities.map { r ->
            RecipeBackupDto(
                uuid = r.uuid,
                name = r.name,
                servingsProduced = r.servingsProduced,
                ingredientsJson = r.ingredientsJson,
                caloriesPerServing = r.caloriesPerServing,
                proteinPerServing = r.proteinPerServing,
                carbsPerServing = r.carbsPerServing,
                fatPerServing = r.fatPerServing,
                createdAt = r.createdAt
            )
        }

        val weightDtos = weightEntities.map { w ->
            WeightEntryBackupDto(
                dateEpochDay = w.dateEpochDay,
                dateString = LocalDate.ofEpochDay(w.dateEpochDay).toString(),
                weightKg = w.weightKg,
                note = w.note,
                createdAt = w.createdAt
            )
        }

        val waterDtos = waterEntities.map { w ->
            WaterLogBackupDto(
                dateEpochDay = w.dateEpochDay,
                dateString = LocalDate.ofEpochDay(w.dateEpochDay).toString(),
                amountMl = w.amountMl,
                timestamp = w.timestamp
            )
        }

        // Targets plus strategy, any scheduled change and the strategy history (BUG-016)
        val goalDto = goalsRepository.exportGoalBackup()

        val prefsDto = UserPreferencesBackupDto(
            firstName = currentPrefs.firstName,
            lastName = currentPrefs.lastName,
            timeZone = currentPrefs.timeZone,
            unitSystem = currentPrefs.unitSystem.name,
            heightCm = currentPrefs.heightCm,
            currentWeightKg = currentPrefs.currentWeightKg,
            targetWeightKg = currentPrefs.targetWeightKg,
            dailyWaterGoalMl = currentPrefs.dailyWaterGoalMl
        )

        val manifest = BackupManifestDto(
            format = PortabilityConfig.BACKUP_FORMAT_NAME,
            backupVersion = PortabilityConfig.BACKUP_FORMAT_VERSION,
            appVersion = PortabilityConfig.CURRENT_APP_VERSION,
            schemaVersion = PortabilityConfig.DATABASE_SCHEMA_VERSION,
            exportedAt = Instant.now().toString(),
            timeZone = TimeZone.getDefault().id,
            deviceInfo = "Android",
            counts = BackupRecordCountsDto(
                diaryEntries = diaryDtos.size,
                customFoods = customFoodDtos.size,
                recipes = recipeDtos.size,
                weightEntries = weightDtos.size,
                waterEntries = waterDtos.size,
                goals = 1,
                preferences = 1
            )
        )

        MacroBaseBackupData(
            manifest = manifest,
            diaryEntries = diaryDtos,
            customFoods = customFoodDtos,
            recipes = recipeDtos,
            weightEntries = weightDtos,
            waterEntries = waterDtos,
            goals = goalDto,
            preferences = prefsDto
        )
    }

    override suspend fun writeBackupArchive(outputStream: OutputStream) = withContext(Dispatchers.IO) {
        val data = exportAllUserData()
        BackupArchiveManager.createBackupArchive(data, outputStream)
    }

    override suspend fun validateBackupArchive(inputStream: InputStream): BackupValidationResult = withContext(Dispatchers.IO) {
        BackupArchiveManager.extractAndValidateBackup(inputStream)
    }

    override suspend fun importUserData(backupData: MacroBaseBackupData, mode: ImportMode): ImportResult = withContext(Dispatchers.IO) {
        try {
            var imported = 0
            var skipped = 0
            var updated = 0
            var conflicts = 0

            if (mode == ImportMode.OVERWRITE) {
                // Execute destructive overwrite in transaction
                userDatabase.withTransaction {
                    userDatabase.diaryDao().clearAllEntries()
                    userDatabase.customFoodDao().clearAllCustomFoods()
                    userDatabase.recipeDao().clearAllRecipes()
                    userDatabase.weightDao().clearAllWeightEntries()
                    userDatabase.waterDao().clearAllWaterLogs()

                    // Insert Custom Foods
                    val customEntities = backupData.customFoods.map { it.toEntity() }
                    userDatabase.customFoodDao().insertCustomFoods(customEntities)
                    imported += customEntities.size

                    // Insert Recipes
                    val recipeEntities = backupData.recipes.map { it.toEntity() }
                    userDatabase.recipeDao().insertRecipes(recipeEntities)
                    imported += recipeEntities.size

                    // Insert Diary Entries, pointing custom-food and recipe entries at the restored rows
                    val resolveFoodId = foodIdResolver()
                    val diaryEntities = backupData.diaryEntries.map { it.toEntity(resolveFoodId(it)) }
                    userDatabase.diaryDao().insertEntries(diaryEntities)
                    imported += diaryEntities.size

                    // Insert Weight Entries
                    val weightEntities = backupData.weightEntries.map { it.toEntity() }
                    userDatabase.weightDao().insertWeightEntries(weightEntities)
                    imported += weightEntities.size

                    // Insert Water Logs
                    val waterEntities = backupData.waterEntries.map { it.toEntity() }
                    userDatabase.waterDao().insertWaterLogs(waterEntities)
                    imported += waterEntities.size
                }

                // Restore Goals & Preferences as they were in the backup; a restore is not a
                // strategy change, so nothing is scheduled for tomorrow (BUG-016)
                val settingsNote = restoringSettings {
                    backupData.goals?.let { g ->
                        goalsRepository.restoreGoalBackup(g)
                        imported++
                    }
                    backupData.preferences?.let { p ->
                        preferencesRepository.updatePreferences(p.toPreferences(preferencesRepository.getPreferences()))
                        imported++
                    }
                }

                ImportResult(
                    isSuccess = true,
                    mode = ImportMode.OVERWRITE,
                    recordsImported = imported,
                    recordsSkipped = 0,
                    recordsUpdated = 0,
                    conflictsResolved = 0,
                    message = "Successfully restored $imported records in Overwrite mode.$settingsNote"
                )
            } else {
                // MERGE Mode: Non-destructive resolution
                userDatabase.withTransaction {
                    // 1. Merge Custom Foods
                    val existingCustomFoods = userDatabase.customFoodDao().getAllCustomFoods().associateBy { it.uuid }
                    val toInsertFoods = mutableListOf<CustomFoodEntity>()
                    for (dto in backupData.customFoods) {
                        val existing = existingCustomFoods[dto.uuid]
                        if (existing == null) {
                            toInsertFoods.add(dto.toEntity())
                            imported++
                        } else {
                            if (existing.isIdenticalTo(dto)) {
                                skipped++
                            } else if (dto.createdAt >= existing.createdAt) {
                                toInsertFoods.add(dto.toEntity(existingId = existing.id))
                                updated++
                                conflicts++
                            } else {
                                skipped++
                                conflicts++
                            }
                        }
                    }
                    if (toInsertFoods.isNotEmpty()) {
                        userDatabase.customFoodDao().insertCustomFoods(toInsertFoods)
                    }

                    // 2. Merge Recipes
                    val existingRecipes = userDatabase.recipeDao().getAllRecipes().associateBy { it.uuid }
                    val toInsertRecipes = mutableListOf<RecipeEntity>()
                    for (dto in backupData.recipes) {
                        val existing = existingRecipes[dto.uuid]
                        if (existing == null) {
                            toInsertRecipes.add(dto.toEntity())
                            imported++
                        } else {
                            if (existing.isIdenticalTo(dto)) {
                                skipped++
                            } else if (dto.createdAt >= existing.createdAt) {
                                toInsertRecipes.add(dto.toEntity(existingId = existing.id))
                                updated++
                                conflicts++
                            } else {
                                skipped++
                                conflicts++
                            }
                        }
                    }
                    if (toInsertRecipes.isNotEmpty()) {
                        userDatabase.recipeDao().insertRecipes(toInsertRecipes)
                    }

                    // 3. Merge Diary Entries
                    val existingDiaryEntries = userDatabase.diaryDao().getAllEntries().associateBy { it.uuid }
                    val toInsertDiary = mutableListOf<DiaryEntryEntity>()
                    val resolveFoodId = foodIdResolver()
                    for (dto in backupData.diaryEntries) {
                        if (!existingDiaryEntries.containsKey(dto.uuid)) {
                            toInsertDiary.add(dto.toEntity(resolveFoodId(dto)))
                            imported++
                        } else {
                            skipped++
                        }
                    }
                    if (toInsertDiary.isNotEmpty()) {
                        userDatabase.diaryDao().insertEntries(toInsertDiary)
                    }

                    // 4. Merge Weight Entries (1 entry per date)
                    val existingWeights = userDatabase.weightDao().getAllWeightEntriesList().associateBy { it.dateEpochDay }
                    val toInsertWeights = mutableListOf<WeightEntryEntity>()
                    for (dto in backupData.weightEntries) {
                        val existing = existingWeights[dto.dateEpochDay]
                        if (existing == null) {
                            toInsertWeights.add(dto.toEntity())
                            imported++
                        } else {
                            if (existing.weightKg == dto.weightKg && existing.note == dto.note) {
                                skipped++
                            } else if (dto.createdAt >= existing.createdAt) {
                                toInsertWeights.add(dto.toEntity(existingId = existing.id))
                                updated++
                                conflicts++
                            } else {
                                skipped++
                                conflicts++
                            }
                        }
                    }
                    if (toInsertWeights.isNotEmpty()) {
                        userDatabase.weightDao().insertWeightEntries(toInsertWeights)
                    }

                    // 5. Merge Water Logs
                    val existingWater = userDatabase.waterDao().getAllWaterLogsList()
                        .map { Triple(it.dateEpochDay, it.timestamp, it.amountMl) }.toSet()
                    val toInsertWater = mutableListOf<WaterLogEntity>()
                    for (dto in backupData.waterEntries) {
                        val key = Triple(dto.dateEpochDay, dto.timestamp, dto.amountMl)
                        if (!existingWater.contains(key)) {
                            toInsertWater.add(dto.toEntity())
                            imported++
                        } else {
                            skipped++
                        }
                    }
                    if (toInsertWater.isNotEmpty()) {
                        userDatabase.waterDao().insertWaterLogs(toInsertWater)
                    }
                }

                // Merge keeps this phone's goals and profile. A backup only fills them on a phone
                // where none were saved yet, such as a fresh install (BUG-016).
                var keptGoals = false
                var keptProfile = false
                val settingsNote = restoringSettings {
                    backupData.goals?.let { g ->
                        if (goalsRepository.hasSavedGoals()) {
                            keptGoals = true
                            skipped++
                        } else {
                            goalsRepository.restoreGoalBackup(g)
                            imported++
                        }
                    }
                    backupData.preferences?.let { p ->
                        if (preferencesRepository.hasSavedPreferences()) {
                            keptProfile = true
                            skipped++
                        } else {
                            preferencesRepository.updatePreferences(p.toPreferences(preferencesRepository.getPreferences()))
                            imported++
                        }
                    }
                }

                val kept = when {
                    keptGoals && keptProfile -> "goals and profile were"
                    keptGoals -> "goals were"
                    keptProfile -> "profile was"
                    else -> null
                }
                val keptNote = kept?.let { " Your current $it kept; restore with Overwrite to use the backup's." } ?: ""
                ImportResult(
                    isSuccess = true,
                    mode = ImportMode.MERGE,
                    recordsImported = imported,
                    recordsSkipped = skipped,
                    recordsUpdated = updated,
                    conflictsResolved = conflicts,
                    message = "Merge complete: $imported imported, $updated updated, $skipped skipped.$keptNote$settingsNote"
                )
            }
        } catch (e: Exception) {
            ImportResult(
                isSuccess = false,
                mode = mode,
                errorMessage = "Import failed transactionally: ${e.message ?: "Database error"}"
            )
        }
    }

    /**
     * Goals and profile live outside the database, so they are written after its transaction has
     * committed. If writing them fails, the records are still restored: say exactly that instead
     * of reporting the whole import as failed. Returns a note for the result message.
     */
    private suspend fun restoringSettings(block: suspend () -> Unit): String = try {
        block()
        ""
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        " Your records were restored, but your goals and profile could not be saved (${e.message ?: "storage error"})."
    }

    // Helper Entity Converters

    /** The backup's profile fields over [current], so settings a backup does not carry are kept. */
    private fun UserPreferencesBackupDto.toPreferences(current: UserPreferences) = current.copy(
        firstName = firstName,
        lastName = lastName,
        timeZone = timeZone,
        unitSystem = try { UnitSystem.valueOf(unitSystem) } catch (e: Exception) { UnitSystem.METRIC },
        heightCm = heightCm,
        currentWeightKg = currentWeightKg,
        targetWeightKg = targetWeightKg,
        dailyWaterGoalMl = dailyWaterGoalMl
    )

    private fun CustomFoodBackupDto.toEntity(existingId: Long = 0L) = CustomFoodEntity(
        id = existingId,
        uuid = uuid,
        name = name,
        brand = brand,
        servingSize = servingSize,
        servingUnit = servingUnit,
        customUnitName = customUnitName,
        calories = calories,
        proteinGrams = proteinGrams,
        carbsGrams = carbsGrams,
        fatGrams = fatGrams,
        fiberGrams = fiberGrams,
        sugarGrams = sugarGrams,
        sodiumMg = sodiumMg,
        potassiumMg = potassiumMg,
        calciumMg = calciumMg,
        ironMg = ironMg,
        createdAt = createdAt
    )

    private fun RecipeBackupDto.toEntity(existingId: Long = 0L) = RecipeEntity(
        id = existingId,
        uuid = uuid,
        name = name,
        servingsProduced = servingsProduced,
        ingredientsJson = ingredientsJson,
        caloriesPerServing = caloriesPerServing,
        proteinPerServing = proteinPerServing,
        carbsPerServing = carbsPerServing,
        fatPerServing = fatPerServing,
        createdAt = createdAt
    )

    private fun DiaryEntryBackupDto.toEntity(resolvedFoodId: Long = foodId) = DiaryEntryEntity(
        id = 0L,
        uuid = uuid,
        dateEpochDay = dateEpochDay,
        mealType = mealType,
        foodId = resolvedFoodId,
        foodName = foodName,
        userQuantity = userQuantity,
        servingDescription = servingDescription,
        gramWeight = gramWeight,
        loggedCalories = loggedCalories,
        loggedProtein = loggedProtein,
        loggedCarbs = loggedCarbs,
        loggedFat = loggedFat,
        // Null stays null: older backups lack these, and "unknown" must not become 0.0 (BUG-037)
        loggedFiber = loggedFiber,
        loggedSugar = loggedSugar,
        loggedSodium = loggedSodium,
        loggedSaturatedFat = loggedSaturatedFat,
        loggedTransFat = loggedTransFat,
        loggedCholesterol = loggedCholesterol,
        createdAt = createdAt
    )

    /**
     * Restored custom foods and recipes get new Room row ids, so a diary entry's food id from
     * the backup is stale. Re-point it by the food's uuid; backups without one (custom foods
     * before format 1.1.0, or a link that no longer matched at export) fall back to a unique
     * name match. Call after custom foods and recipes are written.
     *
     * The backup's row id means nothing in this database, so an entry that cannot be matched
     * (food deleted before export, or two foods with the same name) is unlinked rather than
     * kept: it then always opens from its own snapshot, never as another food.
     * Catalog ids and older raw ids are kept.
     */
    private suspend fun foodIdResolver(): (DiaryEntryBackupDto) -> Long {
        val localFoods = userDatabase.customFoodDao().getAllCustomFoods()
        val foodRowIdByUuid = localFoods.associate { it.uuid to it.id }
        val foodsByName = localFoods.groupBy { it.name.trim().lowercase() }
        val localRecipes = userDatabase.recipeDao().getAllRecipes()
        val recipeRowIdByUuid = localRecipes.associate { it.uuid to it.id }
        val recipesByName = localRecipes.groupBy { it.name.trim().lowercase() }
        return { dto ->
            val name = dto.foodName.trim().lowercase()
            when {
                FoodRepositoryImpl.customRowIdOrNull(dto.foodId) != null -> {
                    val rowId = dto.customFoodUuid?.let { foodRowIdByUuid[it] }
                        ?: foodsByName[name]?.singleOrNull()?.id
                    rowId?.let { FoodRepositoryImpl.CUSTOM_FOOD_ID_OFFSET + it } ?: FoodRepositoryImpl.UNLINKED_CUSTOM_FOOD_ID
                }
                Recipe.rowIdOrNull(dto.foodId) != null -> {
                    val rowId = dto.recipeUuid?.let { recipeRowIdByUuid[it] }
                        ?: recipesByName[name]?.singleOrNull()?.id
                    rowId?.let { Recipe.FOOD_ID_OFFSET + it } ?: Recipe.UNLINKED_FOOD_ID
                }
                else -> dto.foodId
            }
        }
    }

    private fun WeightEntryBackupDto.toEntity(existingId: Long = 0L) = WeightEntryEntity(
        id = existingId,
        dateEpochDay = dateEpochDay,
        weightKg = weightKg,
        note = note,
        createdAt = createdAt
    )

    private fun WaterLogBackupDto.toEntity() = WaterLogEntity(
        id = 0L,
        dateEpochDay = dateEpochDay,
        amountMl = amountMl,
        timestamp = timestamp
    )

    private fun CustomFoodEntity.isIdenticalTo(dto: CustomFoodBackupDto): Boolean {
        return name == dto.name &&
                brand == dto.brand &&
                servingSize == dto.servingSize &&
                servingUnit == dto.servingUnit &&
                customUnitName == dto.customUnitName &&
                calories == dto.calories &&
                proteinGrams == dto.proteinGrams &&
                carbsGrams == dto.carbsGrams &&
                fatGrams == dto.fatGrams &&
                fiberGrams == dto.fiberGrams &&
                sugarGrams == dto.sugarGrams &&
                sodiumMg == dto.sodiumMg
    }

    private fun RecipeEntity.isIdenticalTo(dto: RecipeBackupDto): Boolean {
        return name == dto.name &&
                servingsProduced == dto.servingsProduced &&
                ingredientsJson == dto.ingredientsJson &&
                caloriesPerServing == dto.caloriesPerServing &&
                proteinPerServing == dto.proteinPerServing &&
                carbsPerServing == dto.carbsPerServing &&
                fatPerServing == dto.fatPerServing
    }
}
