package com.macrobase.app

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import com.macrobase.app.data.database.UserDatabase
import com.macrobase.app.data.repository.GoalsRepositoryImpl
import com.macrobase.app.data.repository.PortabilityRepositoryImpl
import com.macrobase.app.data.repository.PreferencesRepositoryImpl
import com.macrobase.app.domain.model.BackupManifestDto
import com.macrobase.app.domain.model.BackupRecordCountsDto
import com.macrobase.app.domain.model.CustomFoodBackupDto
import com.macrobase.app.domain.model.ImportMode
import com.macrobase.app.domain.model.MacroBaseBackupData
import com.macrobase.app.domain.model.RecipeBackupDto
import com.macrobase.app.feature.scanner.OcrEnsembleScorer
import com.macrobase.app.feature.scanner.OcrResult
import com.macrobase.app.feature.water.parseWaterInput
import com.macrobase.app.feature.water.waterFromInput
import com.macrobase.app.feature.water.waterInputError
import com.macrobase.app.feature.water.waterPrefillText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.util.Locale

/** BUG-051: a Merge import compares every column of a custom food, minerals included. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class P4PhaseOneMergeImportTests {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private lateinit var db: UserDatabase
    private lateinit var portability: PortabilityRepositoryImpl

    @After
    fun tearDown() {
        if (::db.isInitialized) db.close()
        scope.cancel()
    }

    private fun setUpPhone() {
        val context = RuntimeEnvironment.getApplication()
        val goalsStore = PreferenceDataStoreFactory.create(scope = scope) { File(tempFolder.root, "goals.preferences_pb") }
        val prefsStore = PreferenceDataStoreFactory.create(scope = scope) { File(tempFolder.root, "prefs.preferences_pb") }
        db = Room.inMemoryDatabaseBuilder(context, UserDatabase::class.java)
            .allowMainThreadQueries()
            .setQueryExecutor { it.run() }
            .setTransactionExecutor { it.run() }
            .build()
        val goals = GoalsRepositoryImpl(context, goalsStore)
        portability = PortabilityRepositoryImpl(db, goals, PreferencesRepositoryImpl(context, prefsStore))
    }

    private val bar = CustomFoodBackupDto(
        "cf-1", "Protein Bar", null, 1.0, "SERVING", 210.0, 20.0, 22.0, 7.0, 3.0, 9.0, 120.0,
        potassiumMg = 150.0, calciumMg = 80.0, ironMg = 2.0, createdAt = 1_000L
    )
    private val dalRice = RecipeBackupDto("r-1", "Dal Rice", 2, "[]", 450.0, 15.0, 70.0, 10.0, 1_000L)

    private fun backup(food: CustomFoodBackupDto = bar, recipe: RecipeBackupDto? = null) = MacroBaseBackupData(
        manifest = BackupManifestDto(exportedAt = "2026-10-10T08:00:00Z", timeZone = "UTC", counts = BackupRecordCountsDto()),
        customFoods = listOf(food),
        recipes = listOfNotNull(recipe)
    )

    /** Merges [incoming] onto a phone that holds [onPhone], and returns the stored row. */
    private fun merge(onPhone: CustomFoodBackupDto, incoming: CustomFoodBackupDto) = runBlocking {
        setUpPhone()
        portability.importUserData(backup(onPhone), ImportMode.MERGE)
        val result = portability.importUserData(backup(incoming), ImportMode.MERGE)
        result to db.customFoodDao().getAllCustomFoods().single()
    }

    private fun mergeOntoBar(food: CustomFoodBackupDto) = merge(bar, food)

    @Test
    fun aPotassiumChangeIsImported() {
        val (result, stored) = mergeOntoBar(bar.copy(potassiumMg = 300.0))

        assertTrue(result.errorMessage, result.isSuccess)
        assertEquals(1, result.recordsUpdated)
        assertEquals(300.0, stored.potassiumMg!!, 0.0)
    }

    @Test
    fun aCalciumChangeIsImported() {
        val (result, stored) = mergeOntoBar(bar.copy(calciumMg = 95.0))

        assertEquals(1, result.recordsUpdated)
        assertEquals(95.0, stored.calciumMg!!, 0.0)
    }

    @Test
    fun anIronChangeIsImported() {
        val (result, stored) = mergeOntoBar(bar.copy(ironMg = 3.5))

        assertEquals(1, result.recordsUpdated)
        assertEquals(3.5, stored.ironMg!!, 0.0)
    }

    // A mineral the backup stated and this phone did not is a change too
    @Test
    fun aMineralGoingFromUnknownToKnownIsImported() {
        val (result, stored) = merge(onPhone = bar.copy(ironMg = null), incoming = bar)

        assertEquals(1, result.recordsUpdated)
        assertEquals(2.0, stored.ironMg!!, 0.0)
    }

    @Test
    fun anIdenticalFoodIsSkipped() {
        val (result, _) = mergeOntoBar(bar)

        assertEquals(0, result.recordsUpdated)
        assertEquals(1, result.recordsSkipped)
    }

    // createdAt is not content: it only decides which side wins a conflict
    @Test
    fun aFoodThatDiffersOnlyInCreatedAtIsSkipped() {
        val (result, _) = mergeOntoBar(bar.copy(createdAt = 5_000L))

        assertEquals(0, result.recordsUpdated)
        assertEquals(1, result.recordsSkipped)
    }

    // Whole-row comparison only sees the columns toEntity maps. A column without a backup field
    // would differ on every Merge and be overwritten with null, so each one must have a field
    @Test
    fun everyStoredColumnHasABackupField() {
        fun fields(type: Class<*>) = type.declaredFields
            .filterNot { java.lang.reflect.Modifier.isStatic(it.modifiers) }
            .map { it.name }
            .toSet()

        assertEquals(
            fields(com.macrobase.app.data.database.entity.CustomFoodEntity::class.java) - "id",
            fields(CustomFoodBackupDto::class.java)
        )
        assertEquals(
            fields(com.macrobase.app.data.database.entity.RecipeEntity::class.java) - "id",
            fields(RecipeBackupDto::class.java)
        )
    }

    @Test
    fun anIdenticalRecipeIsSkippedAndAChangedOneIsUpdated() = runBlocking {
        setUpPhone()
        portability.importUserData(backup(recipe = dalRice), ImportMode.MERGE)

        val same = portability.importUserData(backup(recipe = dalRice), ImportMode.MERGE)
        // The food and the recipe are both unchanged
        assertEquals(2, same.recordsSkipped)

        val changed = portability.importUserData(backup(recipe = dalRice.copy(fatPerServing = 12.0)), ImportMode.MERGE)
        assertEquals(1, changed.recordsUpdated)
        assertEquals(12.0, db.recipeDao().getAllRecipes().single().fatPerServing, 0.0)
    }
}

/** BUG-050 (water) and the BUG-052 escapes that change behaviour. */
class P4PhaseOneWaterAndRulesTests {

    @Test
    fun anUnchangedEditKeepsTheExactAmount() {
        for (isImperial in listOf(false, true)) {
            val text = waterPrefillText(250.0, isImperial)
            assertEquals(250.0, waterFromInput(text, 250.0, isImperial)!!, 0.0)
        }
    }

    @Test
    fun imperialPrefillsShowTwoDecimals() {
        assertEquals("8.45", waterPrefillText(250.0, isImperial = true))
        // A whole number of fl oz has no decimals
        assertEquals("8", waterPrefillText(236.5882365, isImperial = true))
        assertEquals("250", waterPrefillText(250.0, isImperial = false))
    }

    // The parser does not depend on the phone's locale; this pins that down for a comma locale
    @Test
    fun commaDecimalsAreAcceptedInAGermanLocale() {
        val original = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            assertEquals(251.38, Math.round(waterFromInput("8,5", 250.0, isImperial = true)!! * 100.0) / 100.0, 0.0)
            assertEquals(250.5, parseWaterInput("250,5", isImperial = false)!!, 0.0)
            assertEquals(250.5, waterFromInput("250,5", 250.0, isImperial = false)!!, 0.0)
        } finally {
            Locale.setDefault(original)
        }
    }

    // "1,500" mL means 1500; reading the comma as a decimal mark would log 1.5 mL
    @Test
    fun aThousandsSeparatorInMillilitresIsRefusedWithAReason() {
        assertNull(parseWaterInput("1,500", isImperial = false))
        assertNull(parseWaterInput("2.000", isImperial = false))
        assertEquals("Leave out the thousands separator", waterInputError("1,500", null, isImperial = false))
        assertEquals(1500.0, parseWaterInput("1500", isImperial = false)!!, 0.0)
        // In fl oz a comma is a decimal mark: 1.5 fl oz
        assertEquals(44.36, Math.round(parseWaterInput("1,500", isImperial = true)!! * 100.0) / 100.0, 0.0)
    }

    // A stored amount that happens to look grouped is still saved unchanged
    @Test
    fun anUnchangedAmountThatLooksGroupedIsKept() {
        val text = waterPrefillText(236.588, isImperial = false)

        assertEquals("236.588", text)
        assertEquals(236.588, waterFromInput(text, 236.588, isImperial = false)!!, 0.0)
        assertNull(waterInputError(text, waterFromInput(text, 236.588, isImperial = false), isImperial = false))
    }

    // The same number typed again, with a comma, is not a change
    @Test
    fun retypingThePrefillWithACommaKeepsTheExactAmount() {
        assertEquals(250.0, waterFromInput("8,45", 250.0, isImperial = true)!!, 0.0)
        assertEquals(250.0, waterFromInput(" 8.450 ", 250.0, isImperial = true)!!, 0.0)
    }

    @Test
    fun anAmountOverTheLimitOrNotPositiveIsRefused() {
        assertEquals("Amount exceeds 10,000 mL maximum threshold", waterInputError("10001", 10001.0, isImperial = false))
        assertEquals("Please enter a valid positive number", waterInputError("0", 0.0, isImperial = false))
        assertNull(waterInputError("250", 250.0, isImperial = false))
    }

    @Test
    fun anythingThatIsNotANumberIsRejected() {
        assertNull(parseWaterInput("", isImperial = false))
        assertNull(parseWaterInput("abc", isImperial = true))
        assertNull(waterFromInput("1e3", 250.0, isImperial = false))
    }

    // The noise characters are now \u escapes inside a raw-string regex. Java's regex engine reads
    // the escapes, so the pattern matches what it matched before; the lint test checks the source
    @Test
    fun escapedNoiseCharactersStillCountAsNoise() {
        val scorer = OcrEnsembleScorer()
        val label = "Nutrition Energy Protein Fat Carbohydrate Sugars Sodium 5 g 10 mg"
        val clean = scorer.scorePass(1, "a", "A", OcrResult(label, emptyList(), emptyList()))
        val noisy = scorer.scorePass(1, "a", "A", OcrResult("$label \u00a7\u00a9\u00ae\u2122", emptyList(), emptyList()))

        // Four noise characters cost 3 points each
        assertTrue(clean.overallQualityScore > 12.0)
        assertEquals(12.0, clean.overallQualityScore - noisy.overallQualityScore, 1e-9)
    }
}
