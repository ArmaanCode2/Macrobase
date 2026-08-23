package com.macrobase.app

import com.macrobase.app.core.config.PortabilityConfig
import com.macrobase.app.data.portability.BackupArchiveManager
import com.macrobase.app.data.portability.BackupJsonSerializer
import com.macrobase.app.domain.model.BackupCompatibilityDto
import com.macrobase.app.domain.model.BackupManifestDto
import com.macrobase.app.domain.model.BackupRecordCountsDto
import com.macrobase.app.domain.model.CustomFoodBackupDto
import com.macrobase.app.domain.model.DiaryEntryBackupDto
import com.macrobase.app.domain.model.Goal
import com.macrobase.app.domain.model.GoalBackupDto
import com.macrobase.app.domain.model.ImportMode
import com.macrobase.app.domain.model.MacroBaseBackupData
import com.macrobase.app.domain.model.RecipeBackupDto
import com.macrobase.app.domain.model.UnitSystem
import com.macrobase.app.domain.model.UserPreferences
import com.macrobase.app.domain.model.UserPreferencesBackupDto
import com.macrobase.app.domain.model.WaterLogBackupDto
import com.macrobase.app.domain.model.WeightEntryBackupDto
import com.macrobase.app.domain.repository.PortabilityRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Comprehensive Unit Test Suite for Phase 13: Import & Export / Data Portability.
 */
class PortabilityUnitTests {

    private lateinit var testBackupData: MacroBaseBackupData

    @Before
    fun setUp() {
        val today = LocalDate.of(2026, 8, 19)
        val now = System.currentTimeMillis()

        val diaryEntries = listOf(
            DiaryEntryBackupDto(
                uuid = "diary-uuid-1",
                dateEpochDay = today.toEpochDay(),
                dateString = today.toString(),
                mealType = "BREAKFAST",
                foodId = 101L,
                foodName = "Oatmeal with Honey",
                userQuantity = 1.5,
                servingDescription = "1 cup cooked",
                gramWeight = 234.0,
                loggedCalories = 300.0,
                loggedProtein = 10.5,
                loggedCarbs = 54.0,
                loggedFat = 4.5,
                createdAt = now - 10000
            ),
            DiaryEntryBackupDto(
                uuid = "diary-uuid-2",
                dateEpochDay = today.toEpochDay(),
                dateString = today.toString(),
                mealType = "LUNCH",
                foodId = 102L,
                foodName = "Grilled Chicken Breast",
                userQuantity = 2.0,
                servingDescription = "100 g",
                gramWeight = 200.0,
                loggedCalories = 330.0,
                loggedProtein = 62.0,
                loggedCarbs = 0.0,
                loggedFat = 7.2,
                createdAt = now - 5000
            )
        )

        val customFoods = listOf(
            CustomFoodBackupDto(
                uuid = "custom-food-1",
                name = "Protein Bar Extra",
                brand = "FitBrand",
                servingSize = 1.0,
                servingUnit = "bar (60g)",
                calories = 210.0,
                proteinGrams = 20.0,
                carbsGrams = 22.0,
                fatGrams = 6.0,
                fiberGrams = 10.0,
                sugarGrams = 2.0,
                sodiumMg = 180.0,
                potassiumMg = null,
                calciumMg = null,
                ironMg = null,
                createdAt = now - 20000
            )
        )

        val recipes = listOf(
            RecipeBackupDto(
                uuid = "recipe-1",
                name = "Power Smoothie",
                servingsProduced = 2,
                ingredientsJson = """[{"foodId":101,"quantity":1.0,"unitDescription":"1 cup"}]""",
                caloriesPerServing = 250.0,
                proteinPerServing = 15.0,
                carbsPerServing = 35.0,
                fatPerServing = 4.0,
                createdAt = now - 15000
            )
        )

        val weightEntries = listOf(
            WeightEntryBackupDto(
                dateEpochDay = today.toEpochDay(),
                dateString = today.toString(),
                weightKg = 82.5,
                note = "Morning weigh-in",
                createdAt = now - 8000
            )
        )

        val waterEntries = listOf(
            WaterLogBackupDto(
                dateEpochDay = today.toEpochDay(),
                dateString = today.toString(),
                amountMl = 500.0,
                timestamp = now - 12000
            ),
            WaterLogBackupDto(
                dateEpochDay = today.toEpochDay(),
                dateString = today.toString(),
                amountMl = 250.0,
                timestamp = now - 6000
            )
        )

        val goal = GoalBackupDto(
            dailyCalorieGoal = 2200.0,
            carbPercentage = 45.0,
            proteinPercentage = 30.0,
            fatPercentage = 25.0
        )

        val prefs = UserPreferencesBackupDto(
            firstName = "Alex",
            lastName = "Mercer",
            timeZone = "America/New_York",
            unitSystem = "METRIC",
            heightCm = 180.0,
            currentWeightKg = 82.5,
            targetWeightKg = 78.0,
            dailyWaterGoalMl = 3000.0
        )

        val manifest = BackupManifestDto(
            format = PortabilityConfig.BACKUP_FORMAT_NAME,
            backupVersion = PortabilityConfig.BACKUP_FORMAT_VERSION,
            appVersion = PortabilityConfig.CURRENT_APP_VERSION,
            schemaVersion = PortabilityConfig.DATABASE_SCHEMA_VERSION,
            exportedAt = Instant.now().toString(),
            timeZone = "America/New_York",
            deviceInfo = "Android",
            counts = BackupRecordCountsDto(
                diaryEntries = 2,
                customFoods = 1,
                recipes = 1,
                weightEntries = 1,
                waterEntries = 2,
                goals = 1,
                preferences = 1
            )
        )

        testBackupData = MacroBaseBackupData(
            manifest = manifest,
            diaryEntries = diaryEntries,
            customFoods = customFoods,
            recipes = recipes,
            weightEntries = weightEntries,
            waterEntries = waterEntries,
            goals = goal,
            preferences = prefs
        )
    }

    @Test
    fun jsonSerialization_serializesAndParsesAllEntitiesDeterministically() {
        // 1. Manifest
        val manifestJson = BackupJsonSerializer.serializeManifest(testBackupData.manifest)
        val parsedManifest = BackupJsonSerializer.parseManifest(manifestJson)
        assertEquals(PortabilityConfig.BACKUP_FORMAT_NAME, parsedManifest.format)
        assertEquals(PortabilityConfig.BACKUP_FORMAT_VERSION, parsedManifest.backupVersion)
        assertEquals(2, parsedManifest.counts.diaryEntries)
        assertEquals(1, parsedManifest.counts.customFoods)

        // 2. Diary
        val diaryJson = BackupJsonSerializer.serializeDiaryEntries(testBackupData.diaryEntries)
        val parsedDiary = BackupJsonSerializer.parseDiaryEntries(diaryJson)
        assertEquals(2, parsedDiary.size)
        assertEquals("diary-uuid-1", parsedDiary[0].uuid)
        assertEquals(300.0, parsedDiary[0].loggedCalories, 0.001)
        assertEquals("Oatmeal with Honey", parsedDiary[0].foodName)

        // 3. Custom Foods
        val customFoodsJson = BackupJsonSerializer.serializeCustomFoods(testBackupData.customFoods)
        val parsedCustomFoods = BackupJsonSerializer.parseCustomFoods(customFoodsJson)
        assertEquals(1, parsedCustomFoods.size)
        assertEquals("custom-food-1", parsedCustomFoods[0].uuid)
        assertEquals("Protein Bar Extra", parsedCustomFoods[0].name)
        assertEquals(20.0, parsedCustomFoods[0].proteinGrams, 0.001)

        // 4. Recipes
        val recipesJson = BackupJsonSerializer.serializeRecipes(testBackupData.recipes)
        val parsedRecipes = BackupJsonSerializer.parseRecipes(recipesJson)
        assertEquals(1, parsedRecipes.size)
        assertEquals("Power Smoothie", parsedRecipes[0].name)
        assertEquals(2, parsedRecipes[0].servingsProduced)

        // 5. Weight
        val weightJson = BackupJsonSerializer.serializeWeightEntries(testBackupData.weightEntries)
        val parsedWeight = BackupJsonSerializer.parseWeightEntries(weightJson)
        assertEquals(1, parsedWeight.size)
        assertEquals(82.5, parsedWeight[0].weightKg, 0.001)

        // 6. Water
        val waterJson = BackupJsonSerializer.serializeWaterLogs(testBackupData.waterEntries)
        val parsedWater = BackupJsonSerializer.parseWaterLogs(waterJson)
        assertEquals(2, parsedWater.size)
        assertEquals(500.0, parsedWater[0].amountMl, 0.001)

        // 7. Goals
        val goalsJson = BackupJsonSerializer.serializeGoals(testBackupData.goals!!)
        val parsedGoals = BackupJsonSerializer.parseGoals(goalsJson)
        assertEquals(2200.0, parsedGoals.dailyCalorieGoal, 0.001)
        assertEquals(45.0, parsedGoals.carbPercentage, 0.001)

        // 8. Preferences
        val prefsJson = BackupJsonSerializer.serializePreferences(testBackupData.preferences!!)
        val parsedPrefs = BackupJsonSerializer.parsePreferences(prefsJson)
        assertEquals("Alex", parsedPrefs.firstName)
        assertEquals("METRIC", parsedPrefs.unitSystem)
        assertEquals(3000.0, parsedPrefs.dailyWaterGoalMl, 0.001)
    }

    @Test
    fun archiveManager_createsAndValidatesZipWithChecksums() {
        val out = ByteArrayOutputStream()
        BackupArchiveManager.createBackupArchive(testBackupData, out)
        val zipBytes = out.toByteArray()
        assertTrue("ZIP archive must be non-empty", zipBytes.isNotEmpty())

        val validation = BackupArchiveManager.extractAndValidateBackup(ByteArrayInputStream(zipBytes))
        assertTrue(validation.isValid)
        assertNull(validation.errorMessage)
        assertNotNull(validation.manifest)
        assertNotNull(validation.backupData)

        val restored = validation.backupData!!
        assertEquals(2, restored.diaryEntries.size)
        assertEquals(1, restored.customFoods.size)
        assertEquals(1, restored.recipes.size)
        assertEquals(1, restored.weightEntries.size)
        assertEquals(2, restored.waterEntries.size)
        assertEquals(2200.0, restored.goals?.dailyCalorieGoal ?: 0.0, 0.001)
        assertEquals("Alex", restored.preferences?.firstName)
    }

    @Test
    fun validation_rejectsMissingManifestCleanly() {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("diary.json"))
            zip.write("[]".toByteArray(StandardCharsets.UTF_8))
            zip.closeEntry()
            zip.finish()
        }

        val result = BackupArchiveManager.extractAndValidateBackup(ByteArrayInputStream(out.toByteArray()))
        assertFalse(result.isValid)
        assertTrue(result.errorMessage!!.contains("Missing required manifest.json"))
    }

    @Test
    fun validation_rejectsChecksumMismatchCleanly() {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            val manifest = BackupManifestDto(
                format = PortabilityConfig.BACKUP_FORMAT_NAME,
                backupVersion = PortabilityConfig.BACKUP_FORMAT_VERSION,
                appVersion = PortabilityConfig.CURRENT_APP_VERSION,
                schemaVersion = 1,
                exportedAt = Instant.now().toString(),
                timeZone = "UTC",
                counts = BackupRecordCountsDto(diaryEntries = 1),
                checksums = mapOf("diary.json" to "0000000000000000000000000000000000000000000000000000000000000000") // Fake hash
            )
            val manifestJson = BackupJsonSerializer.serializeManifest(manifest)
            zip.putNextEntry(ZipEntry("manifest.json"))
            zip.write(manifestJson.toByteArray(StandardCharsets.UTF_8))
            zip.closeEntry()

            zip.putNextEntry(ZipEntry("diary.json"))
            zip.write("[{\"uuid\":\"test\"}]".toByteArray(StandardCharsets.UTF_8))
            zip.closeEntry()
            zip.finish()
        }

        val result = BackupArchiveManager.extractAndValidateBackup(ByteArrayInputStream(out.toByteArray()))
        assertFalse(result.isValid)
        assertTrue(result.errorMessage!!.contains("Checksum mismatch"))
    }

    @Test
    fun validation_rejectsUnsupportedNewerVersionCleanly() {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            val manifest = BackupManifestDto(
                format = PortabilityConfig.BACKUP_FORMAT_NAME,
                backupVersion = "2.0.0", // Higher major version
                appVersion = "2.0.0",
                schemaVersion = 2,
                exportedAt = Instant.now().toString(),
                timeZone = "UTC",
                counts = BackupRecordCountsDto()
            )
            val manifestJson = BackupJsonSerializer.serializeManifest(manifest)
            zip.putNextEntry(ZipEntry("manifest.json"))
            zip.write(manifestJson.toByteArray(StandardCharsets.UTF_8))
            zip.closeEntry()
            zip.finish()
        }

        val result = BackupArchiveManager.extractAndValidateBackup(ByteArrayInputStream(out.toByteArray()))
        assertFalse(result.isValid)
        assertTrue(result.errorMessage!!.contains("newer and unsupported"))
    }

    @Test
    fun mergeAndOverwrite_executeDeterministically() = runBlocking {
        val fakeRepo = FakePortabilityRepository()

        // 1. Initial State
        fakeRepo.addCustomFood(CustomFoodBackupDto("food-1", "Old Apple", null, 1.0, "medium", 95.0, 0.5, 25.0, 0.3, null, null, null, null, null, null, 1000L))

        // 2. Prepare Backup with food-1 (updated newer) and food-2 (new)
        val backupFoods = listOf(
            CustomFoodBackupDto("food-1", "New Crisp Apple", null, 1.0, "medium", 100.0, 0.5, 26.0, 0.3, null, null, null, null, null, null, 2000L),
            CustomFoodBackupDto("food-2", "Banana", null, 1.0, "medium", 105.0, 1.3, 27.0, 0.4, null, null, null, null, null, null, 2000L)
        )
        val backupData = testBackupData.copy(
            customFoods = backupFoods,
            diaryEntries = emptyList(),
            recipes = emptyList(),
            weightEntries = emptyList(),
            waterEntries = emptyList(),
            goals = null,
            preferences = null
        )

        // 3. Test MERGE mode
        val mergeResult = fakeRepo.importUserData(backupData, ImportMode.MERGE)
        assertTrue(mergeResult.isSuccess)
        assertEquals(1, mergeResult.recordsImported) // food-2 inserted
        assertEquals(1, mergeResult.recordsUpdated)  // food-1 updated to newer version

        val mergedFoods = fakeRepo.exportAllUserData().customFoods
        assertEquals(2, mergedFoods.size)
        assertEquals("New Crisp Apple", mergedFoods.first { it.uuid == "food-1" }.name)
        assertEquals("Banana", mergedFoods.first { it.uuid == "food-2" }.name)

        // 4. Test OVERWRITE mode with only 1 custom food
        val overwriteData = testBackupData.copy(
            customFoods = listOf(CustomFoodBackupDto("food-3", "Orange", null, 1.0, "medium", 62.0, 1.2, 15.0, 0.2, null, null, null, null, null, null, 3000L)),
            diaryEntries = emptyList(),
            recipes = emptyList(),
            weightEntries = emptyList(),
            waterEntries = emptyList(),
            goals = null,
            preferences = null
        )
        val overwriteResult = fakeRepo.importUserData(overwriteData, ImportMode.OVERWRITE)
        assertTrue(overwriteResult.isSuccess)

        val overwrittenFoods = fakeRepo.exportAllUserData().customFoods
        assertEquals(1, overwrittenFoods.size)
        assertEquals("food-3", overwrittenFoods[0].uuid)
        assertEquals("Orange", overwrittenFoods[0].name)
    }

    @Test
    fun historicalSnapshot_preservesLoggedCaloriesAcrossFoodChanges() = runBlocking {
        // Diary Entry logged at 300.0 kcal
        val diary = DiaryEntryBackupDto(
            uuid = "diary-snap-1",
            dateEpochDay = 20000L,
            dateString = "2024-10-04",
            mealType = "BREAKFAST",
            foodId = 50L,
            foodName = "Historical Oatmeal",
            userQuantity = 1.0,
            servingDescription = "1 bowl",
            gramWeight = 200.0,
            loggedCalories = 300.0,
            loggedProtein = 8.0,
            loggedCarbs = 50.0,
            loggedFat = 4.0,
            createdAt = 1000L
        )

        // Custom food modified later to 450.0 kcal
        val updatedFood = CustomFoodBackupDto("food-50", "Historical Oatmeal", null, 1.0, "bowl", 450.0, 12.0, 70.0, 6.0, null, null, null, null, null, null, 5000L)

        val backup = testBackupData.copy(
            diaryEntries = listOf(diary),
            customFoods = listOf(updatedFood)
        )

        val fakeRepo = FakePortabilityRepository()
        fakeRepo.importUserData(backup, ImportMode.OVERWRITE)

        val restored = fakeRepo.exportAllUserData()
        assertEquals(300.0, restored.diaryEntries[0].loggedCalories, 0.001)
        assertEquals("Historical Oatmeal", restored.diaryEntries[0].foodName)
        assertEquals(450.0, restored.customFoods[0].calories, 0.001)
    }
}

/**
 * In-memory fake PortabilityRepository for unit testing.
 */
private class FakePortabilityRepository : PortabilityRepository {
    private val diaryMap = mutableMapOf<String, DiaryEntryBackupDto>()
    private val customFoodMap = mutableMapOf<String, CustomFoodBackupDto>()
    private val recipeMap = mutableMapOf<String, RecipeBackupDto>()
    private val weightMap = mutableMapOf<Long, WeightEntryBackupDto>()
    private val waterList = mutableListOf<WaterLogBackupDto>()
    private var goal: GoalBackupDto? = GoalBackupDto(2000.0, 50.0, 25.0, 25.0)
    private var prefs: UserPreferencesBackupDto? = UserPreferencesBackupDto("User", "", "UTC", "METRIC", 175.0, 70.0, 68.0, 2500.0)

    fun addCustomFood(food: CustomFoodBackupDto) {
        customFoodMap[food.uuid] = food
    }

    override suspend fun exportAllUserData(): MacroBaseBackupData {
        val manifest = BackupManifestDto(
            format = PortabilityConfig.BACKUP_FORMAT_NAME,
            backupVersion = PortabilityConfig.BACKUP_FORMAT_VERSION,
            appVersion = PortabilityConfig.CURRENT_APP_VERSION,
            schemaVersion = PortabilityConfig.DATABASE_SCHEMA_VERSION,
            exportedAt = Instant.now().toString(),
            timeZone = "UTC",
            counts = BackupRecordCountsDto(
                diaryEntries = diaryMap.size,
                customFoods = customFoodMap.size,
                recipes = recipeMap.size,
                weightEntries = weightMap.size,
                waterEntries = waterList.size,
                goals = if (goal != null) 1 else 0,
                preferences = if (prefs != null) 1 else 0
            )
        )
        return MacroBaseBackupData(
            manifest = manifest,
            diaryEntries = diaryMap.values.toList(),
            customFoods = customFoodMap.values.toList(),
            recipes = recipeMap.values.toList(),
            weightEntries = weightMap.values.toList(),
            waterEntries = waterList.toList(),
            goals = goal,
            preferences = prefs
        )
    }

    override suspend fun writeBackupArchive(outputStream: java.io.OutputStream) {
        val data = exportAllUserData()
        BackupArchiveManager.createBackupArchive(data, outputStream)
    }

    override suspend fun validateBackupArchive(inputStream: java.io.InputStream) =
        BackupArchiveManager.extractAndValidateBackup(inputStream)

    override suspend fun importUserData(backupData: MacroBaseBackupData, mode: ImportMode): com.macrobase.app.domain.model.ImportResult {
        var imported = 0
        var updated = 0
        var skipped = 0
        var conflicts = 0

        if (mode == ImportMode.OVERWRITE) {
            diaryMap.clear()
            customFoodMap.clear()
            recipeMap.clear()
            weightMap.clear()
            waterList.clear()

            backupData.diaryEntries.forEach { diaryMap[it.uuid] = it; imported++ }
            backupData.customFoods.forEach { customFoodMap[it.uuid] = it; imported++ }
            backupData.recipes.forEach { recipeMap[it.uuid] = it; imported++ }
            backupData.weightEntries.forEach { weightMap[it.dateEpochDay] = it; imported++ }
            backupData.waterEntries.forEach { waterList.add(it); imported++ }
            goal = backupData.goals
            prefs = backupData.preferences
            return com.macrobase.app.domain.model.ImportResult(true, ImportMode.OVERWRITE, imported, 0, 0, 0, "Overwrite successful")
        } else {
            backupData.customFoods.forEach { f ->
                val ex = customFoodMap[f.uuid]
                if (ex == null) {
                    customFoodMap[f.uuid] = f
                    imported++
                } else if (f.createdAt >= ex.createdAt) {
                    customFoodMap[f.uuid] = f
                    updated++
                    conflicts++
                } else {
                    skipped++
                }
            }
            backupData.diaryEntries.forEach { d ->
                if (!diaryMap.containsKey(d.uuid)) {
                    diaryMap[d.uuid] = d
                    imported++
                } else {
                    skipped++
                }
            }
            return com.macrobase.app.domain.model.ImportResult(true, ImportMode.MERGE, imported, skipped, updated, conflicts, "Merge successful")
        }
    }
}
