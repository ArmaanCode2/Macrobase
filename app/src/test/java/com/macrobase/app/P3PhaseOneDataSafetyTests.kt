package com.macrobase.app

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import com.macrobase.app.core.config.DatabaseConfig
import com.macrobase.app.core.config.PortabilityConfig
import com.macrobase.app.data.database.UserDatabase
import com.macrobase.app.data.database.entity.DiaryEntryEntity
import com.macrobase.app.data.portability.BackupArchiveManager
import com.macrobase.app.data.portability.BackupJsonSerializer
import com.macrobase.app.data.repository.DiaryRepositoryImpl
import com.macrobase.app.data.repository.PortabilityRepositoryImpl
import com.macrobase.app.domain.model.DiaryEntry
import com.macrobase.app.domain.model.DiaryEntryBackupDto
import com.macrobase.app.domain.model.Food
import com.macrobase.app.domain.model.Goal
import com.macrobase.app.domain.model.ImportMode
import com.macrobase.app.domain.model.MealType
import com.macrobase.app.domain.model.Nutrition
import com.macrobase.app.domain.model.Serving
import com.macrobase.app.domain.model.ServingUnit
import com.macrobase.app.domain.model.UserPreferences
import com.macrobase.app.domain.repository.GoalsRepository
import com.macrobase.app.domain.repository.PreferencesRepository
import com.macrobase.app.feature.dashboard.formatOptionalTotal
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.LocalDate
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * P3 phase 1: the user database can never be wiped by a missing migration (BUG-046), and diary
 * snapshots keep "not known" (null) apart from "none" (0.0) through Room, totals and backups (BUG-037).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class P3PhaseOneDataSafetyTests {

    private val context get() = RuntimeEnvironment.getApplication()
    private val openDatabases = mutableListOf<UserDatabase>()
    private val day = LocalDate.of(2026, 9, 1)

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

    private val preferences = object : PreferencesRepository {
        private val state = MutableStateFlow(UserPreferences())
        override suspend fun getPreferences() = state.value
        override fun observePreferences() = state
        override suspend fun updatePreferences(preferences: UserPreferences) { state.value = preferences }
    }

    @After
    fun tearDown() {
        openDatabases.forEach { it.close() }
    }

    // ---------------------------------------------------------------------------------------
    // BUG-046: migrations
    // ---------------------------------------------------------------------------------------

    // The committed code once raised the version to 3 without a 2 -> 3 migration
    @Test
    fun everyVersionStepHasAMigrationAndTheSchemasAreExported() {
        val steps = UserDatabase.ALL_MIGRATIONS.map { it.startVersion to it.endVersion }
        for (version in 1 until DatabaseConfig.USER_DATABASE_VERSION) {
            assertTrue("No migration from $version to ${version + 1}", (version to version + 1) in steps)
        }
        assertTrue(File(schemaDir, "${DatabaseConfig.USER_DATABASE_VERSION}.json").isFile)
        assertTrue("The v2 baseline (v1.0.3) must stay in app/schemas", File(schemaDir, "2.json").isFile)
    }

    /**
     * A schema that reached a phone must never change under the same version number: Room then
     * refuses to open that phone's database. KSP rewrites app/schemas/<current>.json on every
     * build, so an entity change without a version bump fails here. Bump the version and add a
     * migration instead of editing a hash.
     */
    @Test
    fun releasedSchemasNeverChangeInPlace() {
        val pinned = mapOf(
            2 to "f59f038e75597eb71b810e924420cf79", // v1.0.3
            3 to "a9d13f890352564c1adaf487ceb42e6e"
        )
        assertTrue(
            "Pin the identity hash of schema ${DatabaseConfig.USER_DATABASE_VERSION} here",
            DatabaseConfig.USER_DATABASE_VERSION in pinned
        )
        for ((version, hash) in pinned) {
            val actual = JSONObject(File(schemaDir, "$version.json").readText())
                .getJSONObject("database").getString("identityHash")
            assertEquals("app/schemas/$version.json changed in place", hash, actual)
        }
    }

    @Test
    fun upgradingFromVersion2_keepsEveryRow_andRecordsTheNewNutrientsAsUnknown() {
        val name = "p3-migrate-v2.db"
        createDatabaseFromSchema(name, version = 2) { seedVersion2(it) }

        // Opening runs the migration, then Room checks the result against the entities
        val db = UserDatabase.create(context, name).also { openDatabases += it }.openHelper.writableDatabase
        assertEquals(DatabaseConfig.USER_DATABASE_VERSION, db.version)

        db.query(
            "SELECT loggedCalories, loggedFiber, loggedSugar, loggedSodium, loggedSaturatedFat, " +
                "loggedTransFat, loggedCholesterol FROM diary_entries WHERE uuid = 'd1'"
        ).use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(310.0, c.getDouble(0), 0.0)
            for (column in 1..6) assertTrue("column $column should be null", c.isNull(column))
        }
        for (table in listOf("diary_entries", "custom_foods", "recipes", "weight_entries", "water_logs")) {
            db.query("SELECT COUNT(*) FROM $table").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals("rows in $table", 1, c.getInt(0))
            }
        }
        db.query("SELECT customUnitName FROM custom_foods").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("scoop", c.getString(0))
        }
    }

    // Without the destructive fallback, a database Room cannot migrate is an error, not a wipe
    @Test
    fun aDatabaseWithNoMigrationPathFailsLoudlyAndKeepsItsRows() {
        val name = "p3-downgrade.db"
        createDatabaseFromSchema(name, version = 2) { seedVersion2(it) }
        val path = context.getDatabasePath(name)
        SQLiteDatabase.openDatabase(path.path, null, SQLiteDatabase.OPEN_READWRITE).use { it.version = 99 }

        val room = UserDatabase.create(context, name).also { openDatabases += it }
        assertThrows(IllegalStateException::class.java) { room.openHelper.writableDatabase }

        SQLiteDatabase.openDatabase(path.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery("SELECT COUNT(*) FROM diary_entries", null).use { c ->
                assertTrue(c.moveToFirst())
                assertEquals(1, c.getInt(0))
            }
        }
    }

    // ---------------------------------------------------------------------------------------
    // BUG-037: null means "not known", 0.0 means "none"
    // ---------------------------------------------------------------------------------------

    @Test
    fun loggedEntry_keepsUnknownAsNullAndZeroAsZero() = runBlocking {
        val db = newDatabase()
        val diary = DiaryRepositoryImpl(db, goals)
        val nutrition = Nutrition(
            calories = 310.0, proteinGrams = 18.0, carbsGrams = 40.0, fatGrams = 9.0,
            fiberGrams = null, sugarGrams = 0.0, sodiumMg = 412.0,
            saturatedFatGrams = 1.5, transFatGrams = 0.0, cholesterolMg = null
        )
        val id = diary.addEntry(entry("e1", nutrition))

        val reloaded = checkNotNull(diary.getEntryById(id)).calculatedNutrition
        assertNull(reloaded.fiberGrams)
        assertEquals(0.0, reloaded.sugarGrams!!, 0.0)
        assertEquals(412.0, reloaded.sodiumMg!!, 0.0)
        assertEquals(1.5, reloaded.saturatedFatGrams!!, 0.0)
        assertEquals(0.0, reloaded.transFatGrams!!, 0.0)
        assertNull(reloaded.cholesterolMg)
    }

    @Test
    fun dayTotals_areUnknownOnlyWhenNoEntryStatesTheNutrient() = runBlocking {
        val db = newDatabase()
        val diary = DiaryRepositoryImpl(db, goals)
        val base = Nutrition(calories = 100.0, proteinGrams = 1.0, carbsGrams = 1.0, fatGrams = 1.0)
        diary.addEntry(entry("a", base.copy(fiberGrams = null, sugarGrams = null, sodiumMg = 0.0)))
        diary.addEntry(entry("b", base.copy(fiberGrams = 3.0, sugarGrams = null, sodiumMg = null)))

        val summary = diary.getDiaryForDate(day)
        assertEquals(3.0, summary.totalFiberGrams!!, 0.0)
        assertNull(summary.totalSugarGrams)
        assertEquals(0.0, summary.totalSodiumMg!!, 0.0)
    }

    @Test
    fun dashboardShowsADashForAnUnknownTotal() {
        val original = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            assertEquals("-", formatOptionalTotal(null, "g"))
            assertEquals("0.0mg", formatOptionalTotal(0.0, "mg"))
            assertEquals("12.3g", formatOptionalTotal(12.345, "g"))
        } finally {
            Locale.setDefault(original)
        }
    }

    @Test
    fun backupRoundTrip_keepsAllSixNutrients_andKeepsUnknownApartFromZero() = runBlocking {
        val source = newDatabase()
        source.diaryDao().insertEntry(
            diaryRow("known", fiber = 2.5, sugar = 0.0, sodium = 410.0, saturatedFat = 1.2, transFat = 0.0, cholesterol = 35.0)
        )
        source.diaryDao().insertEntry(diaryRow("unknown"))

        val target = newDatabase()
        val portability = PortabilityRepositoryImpl(target, goals, preferences)
        val validation = portability.validateBackupArchive(ByteArrayInputStream(backupBytes(source)))
        assertTrue(validation.errorMessage ?: "invalid backup", validation.isValid)
        assertTrue(portability.importUserData(checkNotNull(validation.backupData), ImportMode.OVERWRITE).isSuccess)

        val rows = target.diaryDao().getAllEntries().associateBy { it.uuid }
        val known = rows.getValue("known")
        assertEquals(2.5, known.loggedFiber!!, 0.0)
        assertEquals(0.0, known.loggedSugar!!, 0.0)
        assertEquals(410.0, known.loggedSodium!!, 0.0)
        assertEquals(1.2, known.loggedSaturatedFat!!, 0.0)
        assertEquals(0.0, known.loggedTransFat!!, 0.0)
        assertEquals(35.0, known.loggedCholesterol!!, 0.0)
        val unknown = rows.getValue("unknown")
        listOf(
            unknown.loggedFiber, unknown.loggedSugar, unknown.loggedSodium,
            unknown.loggedSaturatedFat, unknown.loggedTransFat, unknown.loggedCholesterol
        ).forEach { assertNull(it) }
    }

    // Backups before 1.3.0 came from columns that stored 0.0 for "not known". This is also how
    // data moves off a phone that ran the 7 Oct 2026 build (old version 3 schema).
    @Test
    fun backupInFormat120_restoresWithItsZerosReadAsUnknown_butKeepsRealValues() = runBlocking {
        val source = newDatabase()
        source.diaryDao().insertEntry(diaryRow("old", fiber = 0.0, sugar = 2.0, sodium = 0.0))
        val current = backupBytes(source)

        val legacy = asFormat120(current)
        val legacyEntry = validate(legacy).diaryEntries.single()
        assertNull(legacyEntry.loggedFiber)
        assertEquals(2.0, legacyEntry.loggedSugar!!, 0.0)
        assertNull(legacyEntry.loggedSodium)

        // Overwrite restore into an empty phone, as after reinstalling
        val target = newDatabase()
        val portability = PortabilityRepositoryImpl(target, goals, preferences)
        val validation = portability.validateBackupArchive(ByteArrayInputStream(legacy))
        assertTrue(portability.importUserData(checkNotNull(validation.backupData), ImportMode.OVERWRITE).isSuccess)
        val restored = target.diaryDao().getAllEntries().single()
        assertEquals(310.0, restored.loggedCalories, 0.0)
        assertNull(restored.loggedFiber)
        assertEquals(2.0, restored.loggedSugar!!, 0.0)

        // A current backup states 0.0 only for a real zero, so it stays
        val currentEntry = validate(current).diaryEntries.single()
        assertEquals(0.0, currentEntry.loggedFiber!!, 0.0)
        assertEquals(0.0, currentEntry.loggedSodium!!, 0.0)
    }

    @Test
    fun legacyZeroRule_appliesOnlyToFormatsBefore130() {
        val dto = DiaryEntryBackupDto(
            uuid = "u", dateEpochDay = 0, dateString = "", mealType = "LUNCH", foodId = 1, foodName = "x",
            userQuantity = 1.0, servingDescription = "1", gramWeight = 1.0, loggedCalories = 1.0,
            loggedProtein = 0.0, loggedCarbs = 0.0, loggedFat = 0.0, createdAt = 0,
            loggedFiber = 0.0, loggedSugar = 2.0, loggedSodium = null
        )
        val legacy = BackupJsonSerializer.legacyZerosAsUnknown(listOf(dto), "1.1.0").single()
        assertNull(legacy.loggedFiber)
        assertEquals(2.0, legacy.loggedSugar!!, 0.0)
        assertEquals(dto, BackupJsonSerializer.legacyZerosAsUnknown(listOf(dto), "1.3.0").single())

        assertTrue(BackupJsonSerializer.isFormatBefore("1.2.0", "1.3.0"))
        assertTrue(BackupJsonSerializer.isFormatBefore("1.2", "1.3.0"))
        assertTrue(BackupJsonSerializer.isFormatBefore("garbled", "1.3.0"))
        assertFalse(BackupJsonSerializer.isFormatBefore("1.3.0", "1.3.0"))
        assertFalse(BackupJsonSerializer.isFormatBefore("1.10.0", "1.3.0"))
        assertFalse(BackupJsonSerializer.isFormatBefore("2.0.0", "1.3.0"))
    }

    // ---------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------

    /** Room runs queries inline so the suspend DAO calls finish on the test thread. */
    private fun newDatabase(): UserDatabase =
        Room.inMemoryDatabaseBuilder(context, UserDatabase::class.java)
            .allowMainThreadQueries()
            .setQueryExecutor { it.run() }
            .setTransactionExecutor { it.run() }
            .build()
            .also { openDatabases += it }

    /** Builds a database file exactly as Room created it at [version], from app/schemas. */
    private fun createDatabaseFromSchema(name: String, version: Int, seed: (SQLiteDatabase) -> Unit) {
        context.deleteDatabase(name)
        val file = context.getDatabasePath(name).apply { parentFile?.mkdirs() }
        val schema = JSONObject(File(schemaDir, "$version.json").readText()).getJSONObject("database")
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
            db.version = version
            seed(db)
        }
    }

    /** One row in every table, written with the version 2 columns (app v1.0.3). */
    private fun seedVersion2(db: SQLiteDatabase) {
        db.execSQL(
            "INSERT INTO diary_entries (uuid, dateEpochDay, mealType, foodId, foodName, userQuantity, " +
                "servingDescription, gramWeight, loggedCalories, loggedProtein, loggedCarbs, loggedFat, createdAt) " +
                "VALUES ('d1', 20000, 'LUNCH', 7, 'Dal', 1.5, '1 bowl', 250.0, 310.0, 18.0, 40.0, 9.0, 1)"
        )
        db.execSQL(
            "INSERT INTO custom_foods (uuid, name, servingSize, servingUnit, customUnitName, calories, " +
                "proteinGrams, carbsGrams, fatGrams, createdAt) " +
                "VALUES ('c1', 'Shake', 1.0, 'SERVING', 'scoop', 120.0, 24.0, 3.0, 1.0, 1)"
        )
        db.execSQL(
            "INSERT INTO recipes (uuid, name, servingsProduced, ingredientsJson, caloriesPerServing, " +
                "proteinPerServing, carbsPerServing, fatPerServing, createdAt) " +
                "VALUES ('r1', 'Poha', 2, '[]', 250.0, 6.0, 45.0, 5.0, 1)"
        )
        db.execSQL("INSERT INTO weight_entries (dateEpochDay, weightKg, createdAt) VALUES (20000, 72.5, 1)")
        db.execSQL("INSERT INTO water_logs (dateEpochDay, amountMl, timestamp) VALUES (20000, 250.0, 1)")
    }

    private fun entry(uuid: String, nutrition: Nutrition) = DiaryEntry(
        uuid = uuid,
        date = day,
        mealType = MealType.LUNCH,
        food = Food(id = 7L, uuid = "food-7", name = "Dal", nutrition = nutrition),
        serving = Serving(description = "1 bowl", unit = ServingUnit.SERVING, quantity = 1.0, gramWeight = 250.0),
        quantity = 1.0,
        calculatedNutrition = nutrition
    )

    private fun diaryRow(
        uuid: String,
        fiber: Double? = null,
        sugar: Double? = null,
        sodium: Double? = null,
        saturatedFat: Double? = null,
        transFat: Double? = null,
        cholesterol: Double? = null
    ) = DiaryEntryEntity(
        uuid = uuid,
        dateEpochDay = day.toEpochDay(),
        mealType = "LUNCH",
        foodId = 7L,
        foodName = "Dal",
        userQuantity = 1.0,
        servingDescription = "1 bowl",
        gramWeight = 250.0,
        loggedCalories = 310.0,
        loggedProtein = 18.0,
        loggedCarbs = 40.0,
        loggedFat = 9.0,
        loggedFiber = fiber,
        loggedSugar = sugar,
        loggedSodium = sodium,
        loggedSaturatedFat = saturatedFat,
        loggedTransFat = transFat,
        loggedCholesterol = cholesterol
    )

    private suspend fun backupBytes(db: UserDatabase): ByteArray {
        val out = ByteArrayOutputStream()
        PortabilityRepositoryImpl(db, goals, preferences).writeBackupArchive(out)
        return out.toByteArray()
    }

    private suspend fun validate(bytes: ByteArray) = checkNotNull(
        PortabilityRepositoryImpl(newDatabase(), goals, preferences)
            .validateBackupArchive(ByteArrayInputStream(bytes))
            .also { assertTrue(it.errorMessage ?: "invalid backup", it.isValid) }
            .backupData
    )

    /**
     * The same data as an app writing backup format 1.2.0 exported it: diary.json without the
     * 1.3.0 fields, a 1.2.0 manifest, and a checksum that matches the edited file.
     */
    private fun asFormat120(bytes: ByteArray): ByteArray {
        val files = linkedMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            generateSequence { zip.nextEntry }.forEach { files[it.name] = zip.readBytes() }
        }
        val diary = files.getValue(PortabilityConfig.FILE_DIARY).toString(Charsets.UTF_8)
        val oldDiary = diary.lines()
            .filterNot { line -> listOf("loggedSaturatedFat", "loggedTransFat", "loggedCholesterol").any { "\"$it\"" in line } }
            .joinToString("\n")
        check(oldDiary != diary) { "diary.json has no 1.3.0 fields to remove" }
        val manifest = files.getValue(PortabilityConfig.FILE_MANIFEST).toString(Charsets.UTF_8)
        val edited = manifest
            .replace(
                "\"backupVersion\": \"${PortabilityConfig.BACKUP_FORMAT_VERSION}\"",
                "\"backupVersion\": \"1.2.0\""
            )
            .replace(BackupArchiveManager.calculateSha256(diary), BackupArchiveManager.calculateSha256(oldDiary))
        check(edited.contains("\"1.2.0\"") && edited.contains(BackupArchiveManager.calculateSha256(oldDiary))) {
            "manifest was not rewritten"
        }
        files[PortabilityConfig.FILE_DIARY] = oldDiary.toByteArray(Charsets.UTF_8)
        files[PortabilityConfig.FILE_MANIFEST] = edited.toByteArray(Charsets.UTF_8)
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            files.forEach { (name, content) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(content)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }
}
