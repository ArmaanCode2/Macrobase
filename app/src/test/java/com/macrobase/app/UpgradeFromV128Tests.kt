package com.macrobase.app

import android.database.sqlite.SQLiteDatabase
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.room.Room
import com.macrobase.app.core.config.DatabaseConfig
import com.macrobase.app.core.config.PortabilityConfig
import com.macrobase.app.data.database.UserDatabase
import com.macrobase.app.data.portability.BackupArchiveManager
import com.macrobase.app.data.portability.BackupJsonSerializer
import com.macrobase.app.data.repository.DiaryRepositoryImpl
import com.macrobase.app.data.repository.GoalsRepositoryImpl
import com.macrobase.app.data.repository.PortabilityRepositoryImpl
import com.macrobase.app.data.repository.PreferencesRepositoryImpl
import com.macrobase.app.data.repository.StatisticsRepositoryImpl
import com.macrobase.app.data.repository.WeightRepositoryImpl
import com.macrobase.app.domain.model.BackupManifestDto
import com.macrobase.app.domain.model.BackupRecordCountsDto
import com.macrobase.app.domain.model.DiaryEntryBackupDto
import com.macrobase.app.domain.model.Goal
import com.macrobase.app.domain.model.ImportMode
import com.macrobase.app.domain.model.MealType
import com.macrobase.app.domain.model.UnitSystem
import com.macrobase.app.domain.model.UserPreferences
import com.macrobase.app.domain.repository.GoalsRepository
import com.macrobase.app.testutil.MutableTestClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.charset.StandardCharsets
import java.time.LocalDate
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Updating from v1.2.8 to v1.2.9 with a phone's data in place. The update adds no Room version (the
 * database stays at 5) and only new keys to the profile's DataStore, so the diary, foods, weight,
 * water and profile are read exactly as v1.2.8 left them, and a user who never marks a day sees the
 * same averages as before. A v1.2.8 backup (format 1.4.0) still restores.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UpgradeFromV128Tests {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val context get() = RuntimeEnvironment.getApplication()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val openDatabases = mutableListOf<UserDatabase>()
    private val today = LocalDate.of(2026, 11, 14)
    private val day = LocalDate.of(2026, 11, 10)

    private val schemaDir: File =
        listOf(File("schemas"), File("app/schemas"))
            .map { File(it, UserDatabase::class.java.name) }
            .first { it.isDirectory }

    private val goals = object : GoalsRepository {
        private val state = MutableStateFlow(Goal())
        override suspend fun getGoals() = state.value
        override fun observeGoals() = state
        override suspend fun updateGoals(goals: Goal) { state.value = goals }
    }

    @After
    fun tearDown() {
        openDatabases.forEach { it.close() }
        scope.cancel()
    }

    /** A database file as v1.2.8 left it: version 5, built from app/schemas/5.json, with [seed]'s rows. */
    private fun v128Database(name: String, seed: (SQLiteDatabase) -> Unit) {
        context.deleteDatabase(name)
        val file = context.getDatabasePath(name).apply { parentFile?.mkdirs() }
        val schema = JSONObject(File(schemaDir, "5.json").readText()).getJSONObject("database")
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            val entities = schema.getJSONArray("entities")
            for (i in 0 until entities.length()) {
                val entity = entities.getJSONObject(i)
                val table = entity.getString("tableName")
                db.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
                val indices = entity.optJSONArray("indices") ?: JSONArray()
                for (j in 0 until indices.length()) {
                    db.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", table))
                }
            }
            val setup = schema.getJSONArray("setupQueries")
            for (i in 0 until setup.length()) db.execSQL(setup.getString(i))
            db.version = 5
            seed(db)
        }
    }

    private val diaryColumns = "uuid, dateEpochDay, mealType, foodId, foodName, userQuantity, servingDescription, gramWeight, " +
        "loggedCalories, loggedProtein, loggedCarbs, loggedFat, loggedFiber, loggedSugar, loggedSodium, " +
        "loggedSaturatedFat, loggedTransFat, loggedCholesterol, createdAt"

    @Test
    fun theDatabaseStaysAtVersion5() {
        assertEquals(5, DatabaseConfig.USER_DATABASE_VERSION)
        assertEquals(5, PortabilityConfig.DATABASE_SCHEMA_VERSION)
        assertFalse("A new schema version needs a migration and a v1.2.8 upgrade test", File(schemaDir, "6.json").exists())
    }

    // Every row keeps every value, an unstated nutrient stays null and a stated 0.0 stays 0.0
    @Test
    fun aV128DatabaseOpensWithEveryRowAsItWas() = runBlocking {
        val name = "v128-user.db"
        val epochDay = day.toEpochDay()
        v128Database(name) { db ->
            db.execSQL(
                "INSERT INTO diary_entries ($diaryColumns) VALUES " +
                    "('d-1', $epochDay, 'BREAKFAST', 1896880878330341, 'Poha', 1.5, '1 plate', 150.0, 270.0, 5.4, 46.5, 7.2, 3.1, NULL, 0.0, NULL, NULL, NULL, 1000)"
            )
            db.execSQL(
                "INSERT INTO diary_entries ($diaryColumns) VALUES " +
                    "('d-2', $epochDay, 'DINNER', 100000001, 'My dal', 1.0, '1 bowl', 250.0, 320.0, 18.0, 40.0, 9.0, NULL, 4.0, 610.0, 1.2, 0.0, 0.0, 2000)"
            )
            db.execSQL(
                "INSERT INTO custom_foods (uuid, name, brand, servingSize, servingUnit, customUnitName, calories, proteinGrams, carbsGrams, " +
                    "fatGrams, saturatedFatGrams, fiberGrams, sugarGrams, sodiumMg, potassiumMg, calciumMg, ironMg, createdAt) VALUES " +
                    "('cf-1', 'My dal', NULL, 1.0, 'SERVING', 'bowl', 320.0, 18.0, 40.0, 9.0, NULL, 6.0, 4.0, 610.0, NULL, NULL, NULL, 1000)"
            )
            db.execSQL(
                "INSERT INTO recipes (uuid, name, servingsProduced, ingredientsJson, caloriesPerServing, proteinPerServing, carbsPerServing, " +
                    "fatPerServing, createdAt) VALUES ('r-1', 'Khichdi', 3, '[]', 410.0, 14.0, 70.0, 8.0, 1000)"
            )
            db.execSQL("INSERT INTO weight_entries (dateEpochDay, weightKg, note, createdAt) VALUES ($epochDay, 72.4, 'Morning', 1000)")
            db.execSQL("INSERT INTO water_logs (dateEpochDay, amountMl, timestamp) VALUES ($epochDay, 250.0, 1000)")
        }

        // Room checks the identity hash; a changed schema would refuse to open here
        val room = UserDatabase.create(context, name).also { openDatabases += it }

        val diary = room.diaryDao().getAllEntries()
        assertEquals(listOf("d-1", "d-2"), diary.map { it.uuid })
        val poha = diary[0]
        assertEquals(270.0, poha.loggedCalories, 0.0)
        assertEquals(3.1, poha.loggedFiber!!, 0.0)
        assertNull(poha.loggedSugar)
        assertEquals(0.0, poha.loggedSodium!!, 0.0)
        assertNull(poha.loggedSaturatedFat)
        val dal = diary[1]
        assertNull(dal.loggedFiber)
        assertEquals(610.0, dal.loggedSodium!!, 0.0)
        assertEquals(0.0, dal.loggedCholesterol!!, 0.0)
        val customFood = room.customFoodDao().getAllCustomFoods().single()
        assertNull(customFood.saturatedFatGrams)
        assertEquals("bowl", customFood.customUnitName)
        assertEquals(410.0, room.recipeDao().getAllRecipes().single().caloriesPerServing, 0.0)
        assertEquals(72.4, room.weightDao().getAllWeightEntriesList().single().weightKg, 0.0)
        assertEquals(250.0, room.waterDao().getAllWaterLogsList().single().amountMl, 0.0)
        // Same version, so nothing was migrated and no pre-upgrade copy was made
        assertFalse(File(File(context.filesDir, "database-backups"), "$name.v5").exists())

        // The new Patterns query reads the same rows, the nulls kept
        val diaryRepository = DiaryRepositoryImpl(room, goals)
        val meals = diaryRepository.observeMealNutrients(day, day).first().sortedBy { it.mealType.order }
        assertEquals(listOf(MealType.BREAKFAST, MealType.DINNER), meals.map { it.mealType })
        assertNull(meals[0].sugarGrams)
        assertEquals(0.0, meals[0].sodiumMg!!, 0.0)

        // A user who never marks a day sees the averages v1.2.8 showed
        val statistics = StatisticsRepositoryImpl(diaryRepository, goals, WeightRepositoryImpl(room))
        val averages = statistics.observeMacroAverages(day, day).first()
        assertEquals(1, averages.loggedDaysCount)
        assertEquals(590.0, averages.averageCalories, 1e-9)
        assertEquals(0, statistics.observeFinishedDayAverages(day, day, emptySet()).first().loggedDaysCount)
    }

    // The profile as v1.2.8 stored it: only its own keys, none of the new ones
    @Test
    fun aV128ProfileReadsAsItWasAndKeepsItsValues() = runBlocking {
        val store = PreferenceDataStoreFactory.create(scope = scope) { File(tempFolder.root, "v128-prefs.preferences_pb") }
        store.edit {
            it[stringPreferencesKey("first_name")] = "Asha"
            it[stringPreferencesKey("last_name")] = "Rao"
            it[stringPreferencesKey("time_zone")] = "Asia/Kolkata"
            it[stringPreferencesKey("unit_system")] = "IMPERIAL"
            it[doublePreferencesKey("water_goal_ml")] = 3000.0
            it[doublePreferencesKey("height_cm")] = 165.0
            it[doublePreferencesKey("current_weight_kg")] = 72.5
            it[doublePreferencesKey("target_weight_kg")] = 65.0
            it[intPreferencesKey("seen_rank_rules_version")] = 4
            it[longPreferencesKey("seen_rank_season_start")] = 20_300L
        }
        val before = store.data.first().asMap().mapKeys { it.key.name }
        val prefs = PreferencesRepositoryImpl(context, store, MutableTestClock(today))

        assertEquals(
            UserPreferences(
                firstName = "Asha", lastName = "Rao", timeZone = "Asia/Kolkata", unitSystem = UnitSystem.IMPERIAL,
                dailyWaterGoalMl = 3000.0, heightCm = 165.0, currentWeightKg = 72.5, targetWeightKg = 65.0
            ),
            prefs.getPreferences()
        )
        assertTrue(prefs.observeFinishedDays().first().isEmpty())
        assertFalse(prefs.observeStatsFinishedDaysOnly().first())
        assertEquals(4, prefs.observeSeenRankRulesVersion().first())
        assertTrue(prefs.hasSavedPreferences())

        // Using the new features adds their keys and leaves every old one as it was
        prefs.setDayFinished(day, true)
        prefs.setStatsFinishedDaysOnly(true)
        val after = store.data.first().asMap().mapKeys { it.key.name }
        assertEquals(before, after - setOf("finished_log_days", "stats_finished_days_only"))
        assertEquals(setOf("finished_log_days", "stats_finished_days_only"), after.keys - before.keys)
    }

    /** A backup archive exactly as v1.2.8 wrote it: format 1.4.0, preferences.json without finishedDays. */
    private fun v128Backup(): ByteArray {
        val diary = BackupJsonSerializer.serializeDiaryEntries(
            listOf(
                DiaryEntryBackupDto(
                    uuid = "b-1", dateEpochDay = day.toEpochDay(), dateString = day.toString(), mealType = "LUNCH",
                    foodId = 1_896_880_878_330_341L, foodName = "Rajma", userQuantity = 1.0, servingDescription = "1 bowl",
                    gramWeight = 200.0, loggedCalories = 280.0, loggedProtein = 14.0, loggedCarbs = 40.0, loggedFat = 5.0,
                    createdAt = 1_000L, loggedFiber = 9.0, loggedSugar = null, loggedSodium = 0.0
                )
            )
        )
        val files = linkedMapOf(
            PortabilityConfig.FILE_DIARY to diary,
            PortabilityConfig.FILE_CUSTOM_FOODS to "[]",
            PortabilityConfig.FILE_RECIPES to "[]",
            PortabilityConfig.FILE_WEIGHT to "[]",
            PortabilityConfig.FILE_WATER to "[]",
            PortabilityConfig.FILE_GOALS to "{}",
            PortabilityConfig.FILE_PREFERENCES to """{
  "firstName": "Asha",
  "lastName": "Rao",
  "timeZone": "Asia/Kolkata",
  "unitSystem": "METRIC",
  "heightCm": 165.0,
  "currentWeightKg": 72.5,
  "targetWeightKg": 65.0,
  "dailyWaterGoalMl": 3000.0
}"""
        )
        val manifest = BackupManifestDto(
            backupVersion = "1.4.0",
            appVersion = "1.2.8",
            schemaVersion = 5,
            exportedAt = "2026-11-12T08:00:00Z",
            timeZone = "Asia/Kolkata",
            counts = BackupRecordCountsDto(diaryEntries = 1, preferences = 1),
            checksums = files.mapValues { (_, text) -> BackupArchiveManager.calculateSha256(text) }
        )
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            (mapOf(PortabilityConfig.FILE_MANIFEST to BackupJsonSerializer.serializeManifest(manifest)) + files).forEach { (fileName, text) ->
                zip.putNextEntry(ZipEntry(fileName))
                zip.write(text.toByteArray(StandardCharsets.UTF_8))
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun phone(name: String): Pair<PreferencesRepositoryImpl, PortabilityRepositoryImpl> {
        val db = Room.inMemoryDatabaseBuilder(context, UserDatabase::class.java)
            .allowMainThreadQueries()
            .setQueryExecutor { it.run() }
            .setTransactionExecutor { it.run() }
            .build()
            .also { openDatabases += it }
        val goalsRepository = GoalsRepositoryImpl(context, PreferenceDataStoreFactory.create(scope = scope) { File(tempFolder.root, "$name-goals.preferences_pb") })
        val prefs = PreferencesRepositoryImpl(
            context,
            PreferenceDataStoreFactory.create(scope = scope) { File(tempFolder.root, "$name-prefs.preferences_pb") },
            MutableTestClock(today)
        )
        return prefs to PortabilityRepositoryImpl(db, goalsRepository, prefs)
    }

    @Test
    fun aV128BackupRestoresInBothModes() = runBlocking {
        val read = BackupArchiveManager.extractAndValidateBackup(ByteArrayInputStream(v128Backup()))
        assertTrue(read.errorMessage, read.isValid)
        val backup = read.backupData!!
        assertNull(backup.preferences!!.finishedDays)
        val entry = backup.diaryEntries.single()
        assertEquals(9.0, entry.loggedFiber!!, 0.0)
        assertNull(entry.loggedSugar)

        // Merge keeps this phone's finished days
        val (mergePrefs, mergePhone) = phone("merge")
        mergePrefs.setDayFinished(day, true)
        assertTrue(mergePhone.importUserData(backup, ImportMode.MERGE).isSuccess)
        assertEquals(setOf(day), mergePrefs.observeFinishedDays().first())

        // Replace takes the backup as it is: its profile, and no finished days
        val (replacePrefs, replacePhone) = phone("replace")
        replacePrefs.setDayFinished(day, true)
        val result = replacePhone.importUserData(backup, ImportMode.OVERWRITE)
        assertTrue(result.errorMessage, result.isSuccess)
        assertEquals("Asha", replacePrefs.getPreferences().firstName)
        assertTrue(replacePrefs.observeFinishedDays().first().isEmpty())
        val restored = replacePhone.exportAllUserData().diaryEntries.single()
        assertNull(restored.loggedSugar)
        assertEquals(0.0, restored.loggedSodium!!, 0.0)
    }
}
