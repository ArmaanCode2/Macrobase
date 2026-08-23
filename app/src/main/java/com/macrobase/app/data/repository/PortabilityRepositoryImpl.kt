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
import com.macrobase.app.domain.model.Goal
import com.macrobase.app.domain.model.GoalBackupDto
import com.macrobase.app.domain.model.ImportMode
import com.macrobase.app.domain.model.ImportResult
import com.macrobase.app.domain.model.MacroBaseBackupData
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
        val currentGoal = goalsRepository.getGoals()
        val currentPrefs = preferencesRepository.getPreferences()

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
                createdAt = e.createdAt
            )
        }

        val customFoodDtos = customFoodEntities.map { f ->
            CustomFoodBackupDto(
                uuid = f.uuid,
                name = f.name,
                brand = f.brand,
                servingSize = f.servingSize,
                servingUnit = f.servingUnit,
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

        val goalDto = GoalBackupDto(
            dailyCalorieGoal = currentGoal.dailyCalorieGoal,
            carbPercentage = currentGoal.carbPercentage,
            proteinPercentage = currentGoal.proteinPercentage,
            fatPercentage = currentGoal.fatPercentage
        )

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
                userDatabase.runInTransaction {
                    kotlinx.coroutines.runBlocking {
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

                        // Insert Diary Entries
                        val diaryEntities = backupData.diaryEntries.map { it.toEntity() }
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
                }

                // Restore Goals & Preferences
                backupData.goals?.let { g ->
                    goalsRepository.updateGoals(
                        Goal(
                            dailyCalorieGoal = g.dailyCalorieGoal,
                            carbPercentage = g.carbPercentage,
                            proteinPercentage = g.proteinPercentage,
                            fatPercentage = g.fatPercentage
                        )
                    )
                    imported++
                }

                backupData.preferences?.let { p ->
                    preferencesRepository.updatePreferences(
                        UserPreferences(
                            firstName = p.firstName,
                            lastName = p.lastName,
                            timeZone = p.timeZone,
                            unitSystem = try { UnitSystem.valueOf(p.unitSystem) } catch (e: Exception) { UnitSystem.METRIC },
                            heightCm = p.heightCm,
                            currentWeightKg = p.currentWeightKg,
                            targetWeightKg = p.targetWeightKg,
                            dailyWaterGoalMl = p.dailyWaterGoalMl
                        )
                    )
                    imported++
                }

                ImportResult(
                    isSuccess = true,
                    mode = ImportMode.OVERWRITE,
                    recordsImported = imported,
                    recordsSkipped = 0,
                    recordsUpdated = 0,
                    conflictsResolved = 0,
                    message = "Successfully restored $imported records in Overwrite mode."
                )
            } else {
                // MERGE Mode: Non-destructive resolution
                userDatabase.runInTransaction {
                    kotlinx.coroutines.runBlocking {
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
                        for (dto in backupData.diaryEntries) {
                            if (!existingDiaryEntries.containsKey(dto.uuid)) {
                                toInsertDiary.add(dto.toEntity())
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
                }

                // Goals & Preferences in Merge mode: Update to backup values
                backupData.goals?.let { g ->
                    goalsRepository.updateGoals(
                        Goal(
                            dailyCalorieGoal = g.dailyCalorieGoal,
                            carbPercentage = g.carbPercentage,
                            proteinPercentage = g.proteinPercentage,
                            fatPercentage = g.fatPercentage
                        )
                    )
                    updated++
                }

                backupData.preferences?.let { p ->
                    preferencesRepository.updatePreferences(
                        UserPreferences(
                            firstName = p.firstName,
                            lastName = p.lastName,
                            timeZone = p.timeZone,
                            unitSystem = try { UnitSystem.valueOf(p.unitSystem) } catch (e: Exception) { UnitSystem.METRIC },
                            heightCm = p.heightCm,
                            currentWeightKg = p.currentWeightKg,
                            targetWeightKg = p.targetWeightKg,
                            dailyWaterGoalMl = p.dailyWaterGoalMl
                        )
                    )
                    updated++
                }

                ImportResult(
                    isSuccess = true,
                    mode = ImportMode.MERGE,
                    recordsImported = imported,
                    recordsSkipped = skipped,
                    recordsUpdated = updated,
                    conflictsResolved = conflicts,
                    message = "Merge complete: $imported imported, $updated updated, $skipped skipped."
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

    // Helper Entity Converters
    private fun CustomFoodBackupDto.toEntity(existingId: Long = 0L) = CustomFoodEntity(
        id = existingId,
        uuid = uuid,
        name = name,
        brand = brand,
        servingSize = servingSize,
        servingUnit = servingUnit,
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

    private fun DiaryEntryBackupDto.toEntity() = DiaryEntryEntity(
        id = 0L,
        uuid = uuid,
        dateEpochDay = dateEpochDay,
        mealType = mealType,
        foodId = foodId,
        foodName = foodName,
        userQuantity = userQuantity,
        servingDescription = servingDescription,
        gramWeight = gramWeight,
        loggedCalories = loggedCalories,
        loggedProtein = loggedProtein,
        loggedCarbs = loggedCarbs,
        loggedFat = loggedFat,
        createdAt = createdAt
    )

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
