package com.macrobase.app

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.room.Room
import com.macrobase.app.core.config.PortabilityConfig
import com.macrobase.app.data.database.UserDatabase
import com.macrobase.app.data.portability.BackupArchiveManager
import com.macrobase.app.data.portability.BackupJsonSerializer
import com.macrobase.app.data.repository.GoalsRepositoryImpl
import com.macrobase.app.data.repository.PortabilityRepositoryImpl
import com.macrobase.app.data.repository.PreferencesRepositoryImpl
import com.macrobase.app.domain.model.BackupCompatibilityDto
import com.macrobase.app.domain.model.BackupManifestDto
import com.macrobase.app.domain.model.BackupRecordCountsDto
import com.macrobase.app.domain.model.BackupValidationResult
import com.macrobase.app.domain.model.CustomFoodBackupDto
import com.macrobase.app.domain.model.DiaryEntryBackupDto
import com.macrobase.app.domain.model.FitnessGoal
import com.macrobase.app.domain.model.Goal
import com.macrobase.app.domain.model.GoalBackupDto
import com.macrobase.app.domain.model.GoalTransitionBackupDto
import com.macrobase.app.domain.model.ImportMode
import com.macrobase.app.domain.model.ImportResult
import com.macrobase.app.domain.model.MacroBaseBackupData
import com.macrobase.app.domain.model.RecipeBackupDto
import com.macrobase.app.domain.model.UserPreferences
import com.macrobase.app.domain.model.UserPreferencesBackupDto
import com.macrobase.app.domain.model.WaterLogBackupDto
import com.macrobase.app.domain.model.WeightEntryBackupDto
import com.macrobase.app.domain.repository.PortabilityRepository
import com.macrobase.app.domain.usecase.ExportUserDataUseCase
import com.macrobase.app.domain.usecase.ImportUserDataUseCase
import com.macrobase.app.domain.usecase.ValidateBackupUseCase
import com.macrobase.app.feature.importexport.ImportExportViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.time.LocalDate
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.xml.parsers.DocumentBuilderFactory

/** P2 phase 2 fixtures shared by the test classes below. */
private object BackupFixtures {
    fun manifest() = BackupManifestDto(
        exportedAt = "2026-10-05T08:00:00Z",
        timeZone = "UTC",
        counts = BackupRecordCountsDto()
    )

    fun data(goals: GoalBackupDto? = fullGoals()) = MacroBaseBackupData(
        manifest = manifest(),
        diaryEntries = listOf(diary("d-1", "Oats"), diary("d-2", "Dal")),
        customFoods = listOf(
            CustomFoodBackupDto("cf-1", "Protein Bar", null, 1.0, "SERVING", 210.0, 20.0, 22.0, 7.0, 3.0, null, null, null, null, null, 1_000L)
        ),
        recipes = listOf(RecipeBackupDto("r-1", "Dal Rice", 2, "[]", 450.0, 15.0, 70.0, 10.0, 1_000L)),
        weightEntries = listOf(WeightEntryBackupDto(20_366L, "2025-10-05", 72.5, null, 1_000L)),
        waterEntries = listOf(WaterLogBackupDto(20_366L, "2025-10-05", 250.0, 1_000L)),
        goals = goals,
        preferences = UserPreferencesBackupDto("Asha", "Rao", "Asia/Kolkata", "METRIC", 165.0, 72.5, 65.0, 3000.0)
    )

    fun diary(uuid: String, name: String) = DiaryEntryBackupDto(
        uuid = uuid,
        dateEpochDay = 20_366L,
        dateString = "2025-10-05",
        mealType = "LUNCH",
        foodId = 1_234L,
        foodName = name,
        userQuantity = 1.0,
        servingDescription = "100 g",
        gramWeight = 100.0,
        loggedCalories = 389.0,
        loggedProtein = 16.9,
        loggedCarbs = 66.3,
        loggedFat = 6.9,
        createdAt = 1_000L
    )

    fun fullGoals() = GoalBackupDto(
        dailyCalorieGoal = 2100.0,
        carbPercentage = 40.0,
        proteinPercentage = 35.0,
        fatPercentage = 25.0,
        fitnessGoal = "CUTTING",
        maintenanceCalories = 2600.0,
        scheduledFitnessGoal = "BULKING",
        scheduledMaintenanceCalories = 2800.0,
        scheduledEffectiveDate = "2099-01-01",
        transitions = listOf(
            GoalTransitionBackupDto("2000-01-01", "MAINTAINING", 2400.0, 2400.0, 50.0, 25.0, 25.0),
            GoalTransitionBackupDto("2026-09-01", "CUTTING", 2600.0, 2100.0, 40.0, 35.0, 25.0)
        )
    )

    fun archive(data: MacroBaseBackupData): ByteArray =
        ByteArrayOutputStream().also { BackupArchiveManager.createBackupArchive(data, it) }.toByteArray()

    /** Reads every entry of [bytes], lets [edit] change them, and writes them back in order. */
    fun rezip(bytes: ByteArray, edit: (MutableMap<String, String>) -> Unit): ByteArray {
        val entries = linkedMapOf<String, String>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                entries[entry.name] = zip.readBytes().toString(StandardCharsets.UTF_8)
                entry = zip.nextEntry
            }
        }
        edit(entries)
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            entries.forEach { (name, content) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray(StandardCharsets.UTF_8))
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    /** Replaces [file] in an archive and updates its checksum, as a careful hand edit would. */
    fun replaceFileKeepingChecksumValid(entries: MutableMap<String, String>, file: String, content: String) {
        entries[file] = content
        val manifest = BackupJsonSerializer.parseManifest(entries.getValue(PortabilityConfig.FILE_MANIFEST))
        val fixed = manifest.copy(checksums = manifest.checksums + (file to BackupArchiveManager.calculateSha256(content)))
        entries[PortabilityConfig.FILE_MANIFEST] = BackupJsonSerializer.serializeManifest(fixed)
    }

    fun validate(bytes: ByteArray) = BackupArchiveManager.extractAndValidateBackup(ByteArrayInputStream(bytes))

    fun zip(files: List<Pair<String, String>>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            files.forEach { (name, content) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray(StandardCharsets.UTF_8))
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    /**
     * A backup exactly as app v1.0.3 (backup format 1.0.0, schema 1) wrote it: diary entries
     * without fiber, sugar, sodium or food uuids, custom foods without customUnitName, and goals
     * with only the four targets.
     */
    fun v103Archive(): ByteArray {
        val diary = """[
  {
    "uuid": "old-1",
    "dateEpochDay": 20300,
    "dateString": "2025-07-31",
    "mealType": "BREAKFAST",
    "foodId": 100000001,
    "foodName": "Poha",
    "userQuantity": 1.5,
    "servingDescription": "1 bowl",
    "gramWeight": 0.0,
    "loggedCalories": 270.0,
    "loggedProtein": 5.0,
    "loggedCarbs": 50.0,
    "loggedFat": 6.0,
    "createdAt": 1754000000000
  }
]"""
        val customFoods = """[
  {
    "uuid": "cf-old",
    "name": "Poha",
    "brand": null,
    "servingSize": 1.0,
    "servingUnit": "BOWL",
    "calories": 180.0,
    "proteinGrams": 3.3,
    "carbsGrams": 33.3,
    "fatGrams": 4.0,
    "fiberGrams": null,
    "sugarGrams": null,
    "sodiumMg": null,
    "potassiumMg": null,
    "calciumMg": null,
    "ironMg": null,
    "createdAt": 1754000000000
  }
]"""
        val weight = """[
  {
    "dateEpochDay": 20300,
    "dateString": "2025-07-31",
    "weightKg": 74.0,
    "note": null,
    "createdAt": 1754000000000
  }
]"""
        val empty = "[\n\n]"
        val goals = """{
  "dailyCalorieGoal": 1900.0,
  "carbPercentage": 45.0,
  "proteinPercentage": 30.0,
  "fatPercentage": 25.0
}"""
        val preferences = """{
  "firstName": "Asha",
  "lastName": "",
  "timeZone": "Asia/Kolkata",
  "unitSystem": "METRIC",
  "heightCm": 165.0,
  "currentWeightKg": 74.0,
  "targetWeightKg": null,
  "dailyWaterGoalMl": 2500.0
}"""
        val files = listOf(
            PortabilityConfig.FILE_DIARY to diary,
            PortabilityConfig.FILE_CUSTOM_FOODS to customFoods,
            PortabilityConfig.FILE_RECIPES to empty,
            PortabilityConfig.FILE_WEIGHT to weight,
            PortabilityConfig.FILE_WATER to empty,
            PortabilityConfig.FILE_GOALS to goals,
            PortabilityConfig.FILE_PREFERENCES to preferences
        )
        val manifest = BackupManifestDto(
            backupVersion = "1.0.0",
            appVersion = "1.0.0",
            schemaVersion = 1,
            exportedAt = "2025-08-01T06:00:00Z",
            timeZone = "Asia/Kolkata",
            counts = BackupRecordCountsDto(diaryEntries = 1, customFoods = 1, weightEntries = 1, goals = 1, preferences = 1),
            checksums = files.associate { (name, content) -> name to BackupArchiveManager.calculateSha256(content) },
            compatibility = BackupCompatibilityDto("1.0.0", 1)
        )
        return zip(listOf(PortabilityConfig.FILE_MANIFEST to BackupJsonSerializer.serializeManifest(manifest)) + files)
    }
}

/** BUG-017: incomplete or edited archives are refused before anything is restored. */
class P2PhaseTwoArchiveTests {

    @Test
    fun currentExportValidatesWithEveryRecord() {
        val result = BackupFixtures.validate(BackupFixtures.archive(BackupFixtures.data()))

        assertTrue(result.errorMessage, result.isValid)
        val data = result.backupData!!
        assertEquals(2, data.diaryEntries.size)
        assertEquals(1, data.customFoods.size)
        assertEquals(1, data.recipes.size)
        assertEquals(1, data.weightEntries.size)
        assertEquals(1, data.waterEntries.size)
        assertEquals(BackupFixtures.fullGoals(), data.goals)
        assertEquals(PortabilityConfig.BACKUP_FORMAT_VERSION, result.manifest!!.backupVersion)
    }

    @Test
    fun everyFileTheManifestListsMustBePresent() {
        val bytes = BackupFixtures.archive(BackupFixtures.data())
        val files = listOf(
            PortabilityConfig.FILE_DIARY, PortabilityConfig.FILE_CUSTOM_FOODS, PortabilityConfig.FILE_RECIPES,
            PortabilityConfig.FILE_WEIGHT, PortabilityConfig.FILE_WATER, PortabilityConfig.FILE_GOALS,
            PortabilityConfig.FILE_PREFERENCES
        )
        for (file in files) {
            val result = BackupFixtures.validate(BackupFixtures.rezip(bytes) { it.remove(file) })

            assertFalse("$file missing must be refused", result.isValid)
            assertTrue(result.errorMessage, result.errorMessage!!.contains(file))
            assertNull(result.backupData)
        }
    }

    @Test
    fun fileWithFewerRecordsThanTheManifestListsIsRefused() {
        val bytes = BackupFixtures.archive(BackupFixtures.data())
        val oneEntry = BackupJsonSerializer.serializeDiaryEntries(listOf(BackupFixtures.diary("d-1", "Oats")))
        val edited = BackupFixtures.rezip(bytes) {
            BackupFixtures.replaceFileKeepingChecksumValid(it, PortabilityConfig.FILE_DIARY, oneEntry)
        }

        val result = BackupFixtures.validate(edited)

        assertFalse(result.isValid)
        assertTrue(result.errorMessage, result.errorMessage!!.contains("diary.json holds 1 records but the backup lists 2"))
    }

    @Test
    fun missingFileNotListedInChecksumsIsStillRefusedWhenItHadRecords() {
        val bytes = BackupFixtures.archive(BackupFixtures.data())
        val edited = BackupFixtures.rezip(bytes) { entries ->
            entries.remove(PortabilityConfig.FILE_WATER)
            val manifest = BackupJsonSerializer.parseManifest(entries.getValue(PortabilityConfig.FILE_MANIFEST))
            entries[PortabilityConfig.FILE_MANIFEST] = BackupJsonSerializer.serializeManifest(
                manifest.copy(checksums = manifest.checksums - PortabilityConfig.FILE_WATER)
            )
        }

        val result = BackupFixtures.validate(edited)

        assertFalse(result.isValid)
        assertTrue(result.errorMessage, result.errorMessage!!.contains("water.json"))
    }

    @Test
    fun aFileThatAppearsTwiceIsRefused() {
        // ZipOutputStream refuses duplicate names, so write a same-length stand-in and rename it in the bytes
        val standIn = "diary.jsoX"
        val bytes = BackupFixtures.rezip(BackupFixtures.archive(BackupFixtures.data())) { it[standIn] = "[]" }
        val patched = String(bytes, Charsets.ISO_8859_1).replace(standIn, PortabilityConfig.FILE_DIARY).toByteArray(Charsets.ISO_8859_1)

        val result = BackupFixtures.validate(patched)

        assertFalse(result.isValid)
        assertTrue(result.errorMessage, result.errorMessage!!.contains("more than once"))
    }

    @Test
    fun backupFromThePreviousFormatStillValidatesWithoutStrategyFields() {
        val oldGoals = """{
  "dailyCalorieGoal": 1800.0,
  "carbPercentage": 45.0,
  "proteinPercentage": 30.0,
  "fatPercentage": 25.0
}"""
        val bytes = BackupFixtures.archive(BackupFixtures.data())
        val old = BackupFixtures.rezip(bytes) { entries ->
            BackupFixtures.replaceFileKeepingChecksumValid(entries, PortabilityConfig.FILE_GOALS, oldGoals)
            val manifest = BackupJsonSerializer.parseManifest(entries.getValue(PortabilityConfig.FILE_MANIFEST))
            entries[PortabilityConfig.FILE_MANIFEST] = BackupJsonSerializer.serializeManifest(manifest.copy(backupVersion = "1.1.0"))
        }

        val result = BackupFixtures.validate(old)

        assertTrue(result.errorMessage, result.isValid)
        assertEquals(GoalBackupDto(1800.0, 45.0, 30.0, 25.0), result.backupData!!.goals)
    }

    @Test
    fun backupWrittenByAppVersion103StillValidates() {
        val result = BackupFixtures.validate(BackupFixtures.v103Archive())

        assertTrue(result.errorMessage, result.isValid)
        val data = result.backupData!!
        assertEquals(1, data.diaryEntries.size)
        assertNull(data.diaryEntries.single().loggedFiber)
        assertEquals(1, data.customFoods.size)
        assertEquals(GoalBackupDto(1900.0, 45.0, 30.0, 25.0), data.goals)
        assertEquals("Asha", data.preferences!!.firstName)
    }

    @Test
    fun archiveWithOnlyAManifestIsRefused() {
        val result = BackupFixtures.validate(BackupFixtures.zip(listOf(PortabilityConfig.FILE_MANIFEST to "{}")))

        assertFalse(result.isValid)
        assertTrue(result.errorMessage, result.errorMessage!!.contains("incomplete"))
    }

    @Test
    fun goalsTheBackupListsButCannotBeReadAreRefused() {
        val bytes = BackupFixtures.archive(BackupFixtures.data())
        for (badGoals in listOf("{}", "{\"dailyCalorieGoal\": 0.0, \"carbPercentage\": 50.0, \"proteinPercentage\": 25.0, \"fatPercentage\": 25.0}")) {
            val edited = BackupFixtures.rezip(bytes) {
                BackupFixtures.replaceFileKeepingChecksumValid(it, PortabilityConfig.FILE_GOALS, badGoals)
            }

            val result = BackupFixtures.validate(edited)

            assertFalse(badGoals, result.isValid)
            assertTrue(result.errorMessage, result.errorMessage!!.contains("goals.json"))
        }
    }

    @Test
    fun goalsSurviveTheFileFormatExactly() {
        val goals = BackupFixtures.fullGoals()
        assertEquals(goals, BackupJsonSerializer.parseGoals(BackupJsonSerializer.serializeGoals(goals)))

        val targetsOnly = GoalBackupDto(1800.0, 45.0, 30.0, 25.0)
        assertEquals(targetsOnly, BackupJsonSerializer.parseGoals(BackupJsonSerializer.serializeGoals(targetsOnly)))
    }
}

/** BUG-018: personal data stays out of Google's cloud backup; device transfer still works. */
class P2PhaseTwoBackupRulesTests {

    private val resDir = listOf(File("src/main/res"), File("app/src/main/res")).first { it.isDirectory }

    private fun rules(path: String): List<Element> {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File(resDir, path))
        val nodes = doc.getElementsByTagName("*")
        return (0 until nodes.length).map { nodes.item(it) as Element }
    }

    private fun Element.isUnder(tag: String): Boolean = (parentNode as? Element)?.tagName == tag

    private val userDomains = listOf("root", "file", "database", "sharedpref")

    @Test
    fun cloudBackupOnAndroid12AndLaterExcludesAllAppData() {
        val elements = rules("xml/data_extraction_rules.xml")
        val cloud = elements.filter { it.isUnder("cloud-backup") }

        assertTrue("cloud-backup must not include anything", cloud.none { it.tagName == "include" })
        for (domain in userDomains) {
            assertTrue("cloud-backup must exclude all of $domain", cloud.any { it.tagName == "exclude" && it.getAttribute("domain") == domain && it.getAttribute("path") == "." })
        }
    }

    @Test
    fun deviceTransferOnAndroid12AndLaterStillCopiesUserData() {
        val transfer = rules("xml/data_extraction_rules.xml").filter { it.isUnder("device-transfer") }

        val excludedPaths = transfer.filter { it.tagName == "exclude" }.map { it.getAttribute("domain") to it.getAttribute("path") }
        assertEquals(listOf("database" to "built_in_foods.db"), excludedPaths)
    }

    @Test
    fun android8BacksUpNothing() {
        val elements = rules("xml/backup_rules.xml")

        assertTrue(elements.none { it.tagName == "include" })
        for (domain in userDomains) {
            assertTrue("backup_rules must exclude all of $domain", elements.any { it.tagName == "exclude" && it.getAttribute("domain") == domain && it.getAttribute("path") == "." })
        }
    }

    @Test
    fun android9To11IncludeUserDataOnlyForDeviceTransfer() {
        val includes = rules("xml-v28/backup_rules.xml").filter { it.tagName == "include" }

        assertTrue(includes.any { it.getAttribute("domain") == "database" })
        assertTrue("every include must be device-transfer only", includes.all { it.getAttribute("requireFlags").contains("deviceToDeviceTransfer") })
    }

    @Test
    fun manifestUsesTheseRules() {
        val manifest = File(resDir.parentFile, "AndroidManifest.xml").readText()

        assertTrue(manifest.contains("android:dataExtractionRules=\"@xml/data_extraction_rules\""))
        assertTrue(manifest.contains("android:fullBackupContent=\"@xml/backup_rules\""))
    }
}

/** BUG-017: the restore preview and a failed export. */
@OptIn(ExperimentalCoroutinesApi::class)
class P2PhaseTwoImportExportViewModelTests {

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private class FakePortability(
        private val validation: BackupValidationResult = BackupValidationResult(isValid = false),
        private val failReadingData: Boolean = false
    ) : PortabilityRepository {
        override suspend fun exportAllUserData() = BackupFixtures.data()
        override suspend fun writeBackupArchive(outputStream: OutputStream) {
            if (failReadingData) throw IllegalStateException("database locked")
            outputStream.write("PK".toByteArray())
        }
        override suspend fun validateBackupArchive(inputStream: InputStream) = validation
        override suspend fun importUserData(backupData: MacroBaseBackupData, mode: ImportMode) = ImportResult(true, mode)
    }

    /** A destination that fails part-way through the write, like a full disk. */
    private class FailingOutput : OutputStream() {
        override fun write(b: Int) {
            throw java.io.IOException("No space left on device")
        }
    }

    private fun viewModel(repo: PortabilityRepository) =
        ImportExportViewModel(ExportUserDataUseCase(repo), ValidateBackupUseCase(repo), ImportUserDataUseCase(repo))

    /** The archive is copied to the destination on the IO dispatcher; wait for the result. */
    private fun ImportExportViewModel.awaitExport() = runBlocking {
        withTimeout(5_000) { uiState.first { !it.isExporting } }
    }

    @Test
    fun failureWhileReadingTheDataNeverTouchesTheChosenFile() {
        var opened = 0
        var discarded = 0
        val vm = viewModel(FakePortability(failReadingData = true))

        // The user may have picked an older backup to replace: it must survive this failure
        vm.exportBackup(outputStreamSupplier = { opened++; ByteArrayOutputStream() }, discardOutput = { discarded++; true })
        val state = vm.awaitExport()

        assertEquals(0, opened)
        assertEquals(0, discarded)
        assertNotNull(state.exportErrorMessage)
        assertFalse(state.exportErrorMessage!!.contains("deleted"))
    }

    @Test
    fun failedWriteDeletesTheHalfWrittenFile() {
        var discarded = 0
        val vm = viewModel(FakePortability())

        vm.exportBackup(outputStreamSupplier = { FailingOutput() }, discardOutput = { discarded++; true })
        val state = vm.awaitExport()

        assertEquals(1, discarded)
        assertTrue(state.exportErrorMessage, state.exportErrorMessage!!.contains("incomplete file was deleted"))
        assertNull(state.exportSuccessMessage)
    }

    @Test
    fun messageDoesNotClaimADeletionThatFailed() {
        val vm = viewModel(FakePortability())

        vm.exportBackup(outputStreamSupplier = { FailingOutput() }, discardOutput = { false })
        val state = vm.awaitExport()

        assertNotNull(state.exportErrorMessage)
        assertFalse(state.exportErrorMessage!!.contains("deleted"))
    }

    @Test
    fun successfulExportWritesTheArchiveAndKeepsTheFile() {
        var discarded = 0
        val destination = ByteArrayOutputStream()
        val vm = viewModel(FakePortability())

        vm.exportBackup(outputStreamSupplier = { destination }, discardOutput = { discarded++; true })
        val state = vm.awaitExport()

        assertEquals(0, discarded)
        assertNotNull(state.exportSuccessMessage)
        assertEquals("PK", destination.toString(Charsets.UTF_8.name()))
    }

    @Test
    fun previewShowsTheRecordsThatWillBeRestored() {
        val data = BackupFixtures.data()
        val claimed = BackupFixtures.manifest().copy(counts = BackupRecordCountsDto(diaryEntries = 1500))
        val vm = viewModel(FakePortability(BackupValidationResult(isValid = true, manifest = claimed, backupData = data)))

        vm.validateAndPreviewBackup { ByteArrayInputStream(ByteArray(0)) }

        val counts = vm.uiState.value.previewData!!.counts
        assertEquals(2, counts.diaryEntries)
        assertEquals(1, counts.customFoods)
        assertEquals(1, counts.goals)
        assertEquals(1, counts.preferences)
    }
}

/** BUG-016: restoring a backup never schedules a strategy change; Merge keeps this phone's settings. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class P2PhaseTwoGoalsRestoreTests {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val databases = mutableListOf<UserDatabase>()
    private val tomorrow = LocalDate.now().plusDays(1)

    @After
    fun tearDown() {
        databases.forEach { it.close() }
        scope.cancel()
    }

    private class Phone(
        val goals: GoalsRepositoryImpl,
        val goalsStore: DataStore<Preferences>,
        val preferences: PreferencesRepositoryImpl,
        val portability: PortabilityRepositoryImpl
    )

    private fun phone(name: String): Phone {
        val context = RuntimeEnvironment.getApplication()
        val goalsStore = PreferenceDataStoreFactory.create(scope = scope) { File(tempFolder.root, "$name-goals.preferences_pb") }
        val prefsStore = PreferenceDataStoreFactory.create(scope = scope) { File(tempFolder.root, "$name-prefs.preferences_pb") }
        val db = Room.inMemoryDatabaseBuilder(context, UserDatabase::class.java)
            .allowMainThreadQueries()
            .setQueryExecutor { it.run() }
            .setTransactionExecutor { it.run() }
            .build()
            .also { databases += it }
        val goals = GoalsRepositoryImpl(context, goalsStore)
        val preferences = PreferencesRepositoryImpl(context, prefsStore)
        return Phone(goals, goalsStore, preferences, PortabilityRepositoryImpl(db, goals, preferences))
    }

    /** A phone already on Cutting at 2,600 kcal maintenance, with that change in its history. */
    private suspend fun Phone.onCutting() {
        goalsStore.edit {
            it[doublePreferencesKey("daily_calorie_goal")] = 2100.0
            it[doublePreferencesKey("carb_percentage")] = 40.0
            it[doublePreferencesKey("protein_percentage")] = 35.0
            it[doublePreferencesKey("fat_percentage")] = 25.0
            it[stringPreferencesKey("fitness_goal")] = "CUTTING"
            it[doublePreferencesKey("maintenance_calories")] = 2600.0
            it[stringPreferencesKey("transitions_history")] =
                "2000-01-01|MAINTAINING|2400.0|2400.0|50.0|25.0|25.0\n2026-09-01|CUTTING|2600.0|2100.0|40.0|35.0|25.0"
        }
    }

    private fun oldFormatBackup() = BackupFixtures.data(goals = GoalBackupDto(1800.0, 45.0, 30.0, 25.0))

    @Test
    fun overwriteWithAnOldBackupKeepsThePhonesStrategyAndSchedulesNothing() = runBlocking {
        val phone = phone("a")
        phone.onCutting()
        val historyBefore = phone.goals.exportGoalBackup().transitions

        val result = phone.portability.importUserData(oldFormatBackup(), ImportMode.OVERWRITE)

        assertTrue(result.errorMessage, result.isSuccess)
        val goal = phone.goals.getGoals()
        assertEquals(FitnessGoal.CUTTING, goal.fitnessGoal)
        assertEquals(2600.0, goal.maintenanceCalories, 0.0)
        assertNull(goal.scheduledFitnessGoal)
        assertEquals(FitnessGoal.CUTTING, phone.goals.getGoalForDate(tomorrow).fitnessGoal)
        // No transition added or changed; the records take the restored targets (BUG-024)
        val historyAfter = phone.goals.exportGoalBackup().transitions!!
        assertEquals(historyBefore!!.map { it.effectiveDate to it.fitnessGoal }, historyAfter.map { it.effectiveDate to it.fitnessGoal })
        assertTrue(historyAfter.all { it.dailyCalorieGoal == 1800.0 && it.carbPercentage == 45.0 })
        // The backup's targets are restored
        assertEquals(1800.0, goal.dailyCalorieGoal, 0.0)
        assertEquals(45.0, goal.carbPercentage, 0.0)
    }

    @Test
    fun mergeKeepsThePhonesGoalsAndProfile() = runBlocking {
        val phone = phone("b")
        phone.onCutting()
        phone.preferences.updatePreferences(UserPreferences(firstName = "Ravi"))
        val goalsBefore = phone.goals.exportGoalBackup()

        val result = phone.portability.importUserData(BackupFixtures.data(), ImportMode.MERGE)

        assertTrue(result.errorMessage, result.isSuccess)
        assertEquals(goalsBefore, phone.goals.exportGoalBackup())
        assertEquals("Ravi", phone.preferences.getPreferences().firstName)
        assertTrue(result.message, result.message.contains("goals and profile were kept"))
    }

    @Test
    fun mergeIntoAFreshPhoneFillsGoalsAndProfile() = runBlocking {
        val phone = phone("c")
        assertFalse(phone.goals.hasSavedGoals())
        assertFalse(phone.preferences.hasSavedPreferences())

        val result = phone.portability.importUserData(BackupFixtures.data(), ImportMode.MERGE)

        assertTrue(result.errorMessage, result.isSuccess)
        assertEquals(BackupFixtures.fullGoals(), phone.goals.exportGoalBackup())
        assertEquals("Asha", phone.preferences.getPreferences().firstName)
        assertFalse(result.message, result.message.contains("kept"))
    }

    @Test
    fun exportThenOverwriteOnAnotherPhoneRestoresGoalsExactly() = runBlocking {
        val source = phone("src")
        source.onCutting()
        // A change to Bulking, scheduled for tomorrow, is also carried over
        source.goals.updateGoals(source.goals.getGoals().copy(fitnessGoal = FitnessGoal.BULKING, maintenanceCalories = 2800.0))
        val expected = source.goals.exportGoalBackup()
        val bytes = ByteArrayOutputStream().also { source.portability.writeBackupArchive(it) }.toByteArray()

        val target = phone("dst")
        target.goals.updateGoals(Goal(dailyCalorieGoal = 2500.0))
        val validation = target.portability.validateBackupArchive(ByteArrayInputStream(bytes))
        assertTrue(validation.errorMessage, validation.isValid)
        val result = target.portability.importUserData(validation.backupData!!, ImportMode.OVERWRITE)

        assertTrue(result.errorMessage, result.isSuccess)
        assertEquals(expected, target.goals.exportGoalBackup())
        assertEquals(FitnessGoal.CUTTING, target.goals.getGoals().fitnessGoal)
        assertEquals(FitnessGoal.BULKING, target.goals.getGoalForDate(tomorrow).fitnessGoal)
    }

    @Test
    fun restoreLeavesOutHistoryRecordsItCannotRead() = runBlocking {
        val phone = phone("e")
        val damaged = BackupFixtures.fullGoals().copy(
            transitions = listOf(
                GoalTransitionBackupDto("not-a-date", "CUTTING", 2600.0, 2100.0, 40.0, 35.0, 25.0),
                GoalTransitionBackupDto("2026-08-01", "CUT|TING", 2600.0, 2100.0, 40.0, 35.0, 25.0),
                GoalTransitionBackupDto("2026-09-01", "CUTTING", 2600.0, 2100.0, 40.0, 35.0, 25.0)
            )
        )

        phone.goals.restoreGoalBackup(damaged)

        val history = phone.goals.exportGoalBackup().transitions!!
        assertEquals(listOf("2026-09-01" to "CUTTING"), history.map { it.effectiveDate to it.fitnessGoal })
    }

    @Test
    fun unknownStrategyNameKeepsThePhonesStrategyInsteadOfMaintaining() = runBlocking {
        val phone = phone("h")
        phone.onCutting()
        val historyBefore = phone.goals.exportGoalBackup().transitions

        phone.goals.restoreGoalBackup(BackupFixtures.fullGoals().copy(fitnessGoal = "RECOMPING", dailyCalorieGoal = 1950.0))

        val goal = phone.goals.getGoals()
        assertEquals(FitnessGoal.CUTTING, goal.fitnessGoal)
        assertEquals(2600.0, goal.maintenanceCalories, 0.0)
        assertNull(goal.scheduledFitnessGoal)
        // Same transitions (dates and strategies); only the targets are the backup's
        assertEquals(
            historyBefore!!.map { it.effectiveDate to it.fitnessGoal },
            phone.goals.exportGoalBackup().transitions!!.map { it.effectiveDate to it.fitnessGoal }
        )
        // The targets are still restored
        assertEquals(1950.0, goal.dailyCalorieGoal, 0.0)
    }

    @Test
    fun backupWithoutHistoryKeepsThePhonesHistory() = runBlocking {
        val phone = phone("i")
        phone.onCutting()
        val historyBefore = phone.goals.exportGoalBackup().transitions

        phone.goals.restoreGoalBackup(BackupFixtures.fullGoals().copy(transitions = null))

        assertEquals(historyBefore, phone.goals.exportGoalBackup().transitions)
        assertEquals(FitnessGoal.CUTTING, phone.goals.getGoals().fitnessGoal)
    }

    @Test
    fun mergeMessageNamesOnlyWhatWasKept() = runBlocking {
        val phone = phone("j")
        phone.onCutting() // goals saved, profile never saved

        val result = phone.portability.importUserData(BackupFixtures.data(), ImportMode.MERGE)

        assertEquals("Asha", phone.preferences.getPreferences().firstName)
        assertTrue(result.message, result.message.contains("Your current goals were kept"))
        assertFalse(result.message, result.message.contains("profile"))
    }

    @Test
    fun backupFromAppVersion103RestoresOntoAPhoneOnCutting() = runBlocking {
        val phone = phone("k")
        phone.onCutting()
        val validation = phone.portability.validateBackupArchive(ByteArrayInputStream(BackupFixtures.v103Archive()))
        assertTrue(validation.errorMessage, validation.isValid)

        val result = phone.portability.importUserData(validation.backupData!!, ImportMode.OVERWRITE)

        assertTrue(result.errorMessage, result.isSuccess)
        val exported = phone.portability.exportAllUserData()
        assertEquals(listOf("old-1"), exported.diaryEntries.map { it.uuid })
        assertEquals(listOf("Poha"), exported.customFoods.map { it.name })
        val goal = phone.goals.getGoals()
        assertEquals(FitnessGoal.CUTTING, goal.fitnessGoal)
        assertEquals(1900.0, goal.dailyCalorieGoal, 0.0)
        assertEquals(FitnessGoal.CUTTING, phone.goals.getGoalForDate(tomorrow).fitnessGoal)
    }

    @Test
    fun savedGoalsAreDetected() = runBlocking {
        val phone = phone("f")
        assertFalse(phone.goals.hasSavedGoals())

        phone.goals.updateGoals(Goal(dailyCalorieGoal = 1900.0))

        assertTrue(phone.goals.hasSavedGoals())
    }
}
