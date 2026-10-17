package com.macrobase.app

import androidx.room.Room
import com.macrobase.app.core.config.DatabaseConfig
import com.macrobase.app.data.database.BuiltInDatabaseManager
import com.macrobase.app.data.database.UserDatabase
import com.macrobase.app.data.database.entity.CustomFoodEntity
import com.macrobase.app.data.database.entity.DiaryEntryEntity
import com.macrobase.app.data.provider.FoodDataProvider
import com.macrobase.app.data.provider.LocalFoodDatabaseProvider
import com.macrobase.app.data.repository.FoodRepositoryImpl
import com.macrobase.app.domain.model.Food
import com.macrobase.app.domain.model.Nutrition
import com.macrobase.app.domain.model.Recipe
import com.macrobase.app.domain.model.Serving
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.sql.Connection
import java.sql.DriverManager

private val catalogFile: File
    get() = listOf(File("src/main/assets/databases/built_in_foods.db"), File("app/src/main/assets/databases/built_in_foods.db"))
        .first { it.isFile }

/** BUG-054: the query keeps every script's letters, and the catalog's own tokenizer folds accents. */
class P4PhaseFourSearchQueryTests {

    private lateinit var catalog: Connection

    @Before
    fun setUp() {
        catalog = DriverManager.getConnection("jdbc:sqlite:${catalogFile.absolutePath}")
    }

    @After
    fun tearDown() {
        catalog.close()
    }

    @Test
    fun wordsBecomeQuotedPrefixTerms() {
        assertEquals("\"greek\"* \"yogurt\"*", LocalFoodDatabaseProvider.sanitizeFtsQuery("greek  yogurt"))
        assertEquals("\"low\"* \"fat\"*", LocalFoodDatabaseProvider.sanitizeFtsQuery("low-fat"))
        // A quote the user typed is not FTS5 syntax
        assertEquals("\"say\"* \"cheese\"*", LocalFoodDatabaseProvider.sanitizeFtsQuery("say \"cheese\""))
    }

    @Test
    fun accentedAndNonLatinLettersAreKept() {
        assertEquals("\"jalape\u00f1o\"*", LocalFoodDatabaseProvider.sanitizeFtsQuery("jalape\u00f1o"))
        // Hindi: the vowel sign is a combining mark and stays in the query
        assertEquals("\"\u0926\u093e\u0932\"*", LocalFoodDatabaseProvider.sanitizeFtsQuery("\u0926\u093e\u0932"))
    }

    @Test
    fun nothingSearchableGivesAnEmptyQuery() {
        assertEquals("", LocalFoodDatabaseProvider.sanitizeFtsQuery("!!!"))
        assertEquals("", LocalFoodDatabaseProvider.sanitizeFtsQuery("  "))
    }

    @Test
    fun theLikeTermIsFoldedLikeTheCatalogsNormalisedNames() {
        assertEquals("puree", LocalFoodDatabaseProvider.foldForNormalizedName("Pur\u00e9e"))
        assertEquals("jalapeno", LocalFoodDatabaseProvider.foldForNormalizedName("Jalape\u00f1o"))
        // Only Latin accents go: an Indic vowel sign is part of the word
        assertEquals("\u0926\u0942\u0927", LocalFoodDatabaseProvider.foldForNormalizedName("\u0926\u0942\u0927"))
    }

    private fun ftsMatches(query: String): Int =
        catalog.prepareStatement("SELECT COUNT(*) FROM foods_fts WHERE foods_fts MATCH ?").use { statement ->
            statement.setString(1, LocalFoodDatabaseProvider.sanitizeFtsQuery(query))
            statement.executeQuery().use { it.next(); it.getInt(1) }
        }

    // The table's unicode61 tokenizer removes the accent, so the accented word finds the plain one
    @Test
    fun anAccentedQueryFindsThePlainSpelling() {
        val plain = ftsMatches("puree")

        assertTrue(plain > 0)
        assertEquals(plain, ftsMatches("pur\u00e9e"))
    }

    // A fragment inside a word has no full-text match, which is why the LIKE search follows
    @Test
    fun aFragmentInsideAWordHasNoFullTextMatchButALikeMatch() {
        assertEquals(0, ftsMatches("ancake"))
        val likeMatches = catalog.prepareStatement("SELECT COUNT(*) FROM foods WHERE normalized_name LIKE '%ancake%'").use { statement ->
            statement.executeQuery().use { it.next(); it.getInt(1) }
        }
        assertTrue(likeMatches > 0)
    }
}

/**
 * BUG-054 through the real provider and the bundled catalog. Robolectric's SQLite has no FTS5, so
 * every search here takes the LIKE path; the JDBC tests above cover the full-text path.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class P4PhaseFourCatalogSearchTests {

    private lateinit var provider: LocalFoodDatabaseProvider

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        // Installed the way a phone has it, so the manager opens it instead of copying from assets
        val installed = context.getDatabasePath(DatabaseConfig.BUILT_IN_FOODS_DB_NAME)
        installed.parentFile?.mkdirs()
        catalogFile.copyTo(installed, overwrite = true)
        provider = LocalFoodDatabaseProvider(BuiltInDatabaseManager(context))
    }

    @Test
    fun aFragmentInsideAWordFindsTheFoodsThatContainIt() = runBlocking {
        val foods = provider.searchFoods("ancake", 30)

        assertTrue(foods.isNotEmpty())
        assertTrue(foods.joinToString { it.name }, foods.all { it.name.lowercase().contains("ancake") })
    }

    @Test
    fun anAccentedQueryFindsThePlainSpelling() = runBlocking {
        val foods = provider.searchFoods("pur\u00e9e", 30)

        assertTrue(foods.isNotEmpty())
        assertEquals(provider.searchFoods("puree", 30).map { it.id }.toSet(), foods.map { it.id }.toSet())
    }

    @Test
    fun aQueryInAnotherScriptReturnsNothingWithoutFailing() = runBlocking {
        assertTrue(provider.searchFoods("\u0926\u093e\u0932", 30).isEmpty())
        assertTrue(provider.searchFoods("!!!", 30).isEmpty())
    }

    @Test
    fun wholeWordsStillWorkAsBefore() = runBlocking {
        val foods = provider.searchFoods("paneer", 30)

        assertTrue(foods.isNotEmpty())
        assertTrue(foods.all { it.servings.isNotEmpty() })
    }
}

/**
 * BUG-055: "Recently logged" lists the catalog foods logged most recently. Custom foods are listed
 * above it on the Search screen, and recipes are logged from the Recipes screen.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class P4PhaseFourRecentFoodsTests {

    private lateinit var db: UserDatabase
    private lateinit var foods: FoodRepositoryImpl
    private var nextCreatedAt = 1_000L

    /** Built-in foods 1 to 99, each named after its id. */
    private val catalog = object : FoodDataProvider {
        override val providerId = "test"
        override val displayName = "test"
        override val isLocal = true
        override suspend fun searchFoods(query: String, limit: Int) = emptyList<Food>()
        override suspend fun getFoodById(id: Long): Food? =
            if (id in 1L..99L) Food(id = id, uuid = "b-$id", name = "Food $id", nutrition = Nutrition.ZERO) else null
        override suspend fun getFoodByBarcode(barcode: String): Food? = null
        override suspend fun getFoodServings(foodId: Long) = emptyList<Serving>()
    }

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), UserDatabase::class.java)
            .allowMainThreadQueries()
            .setQueryExecutor { it.run() }
            .setTransactionExecutor { it.run() }
            .build()
        foods = FoodRepositoryImpl(catalog, db.customFoodDao(), db.diaryDao())
    }

    @After
    fun tearDown() {
        db.close()
    }

    private suspend fun log(foodId: Long, name: String = "Food $foodId") {
        val createdAt = nextCreatedAt++
        db.diaryDao().insertEntry(
            DiaryEntryEntity(
                uuid = "e-$createdAt",
                dateEpochDay = 20_000L,
                mealType = "LUNCH",
                foodId = foodId,
                foodName = name,
                userQuantity = 1.0,
                servingDescription = "1 serving",
                gramWeight = 100.0,
                loggedCalories = 100.0,
                loggedProtein = 5.0,
                loggedCarbs = 10.0,
                loggedFat = 3.0,
                createdAt = createdAt
            )
        )
    }

    @Test
    fun theNewestFoodComesFirstAndEachAppearsOnce() = runBlocking {
        log(1); log(2); log(1); log(3)

        assertEquals(listOf(3L, 1L, 2L), foods.getRecentFoods(10).map { it.id })
    }

    @Test
    fun theListStopsAtTheLimit() = runBlocking {
        for (id in 1L..12L) log(id)

        assertEquals((12L downTo 3L).toList(), foods.getRecentFoods(10).map { it.id })
    }

    // The Search screen lists every custom food above the recents, so they are not repeated there
    @Test
    fun customFoodsAreLeftOut() = runBlocking {
        val bar = db.customFoodDao().insertCustomFood(
            CustomFoodEntity(uuid = "c-1", name = "Protein Bar", servingSize = 1.0, servingUnit = "SERVING", calories = 210.0, proteinGrams = 20.0, carbsGrams = 22.0, fatGrams = 7.0)
        )
        log(1)
        log(FoodRepositoryImpl.CUSTOM_FOOD_ID_OFFSET + bar)

        assertEquals(listOf(1L), foods.getRecentFoods(10).map { it.id })
    }

    // An id the catalog no longer has is skipped, and the next food takes its place
    @Test
    fun aFoodMissingFromTheCatalogIsSkippedAndTheListStillFills() = runBlocking {
        for (id in 1L..10L) log(id)
        log(500L)

        assertEquals((10L downTo 1L).toList(), foods.getRecentFoods(10).map { it.id })
    }

    // An id from an older catalog can name another food now; only the same food is offered
    @Test
    fun aFoodWhoseNameChangedIsNotOffered() = runBlocking {
        log(4)
        log(5, name = "Old food that had id 5")

        assertEquals(listOf(4L), foods.getRecentFoods(10).map { it.id })
    }

    // Recipes are left out in the query, so many recent recipes never shorten the list
    @Test
    fun recentRecipesDoNotUseUpTheList() = runBlocking {
        for (id in 1L..12L) log(id)
        for (recipe in 1L..30L) log(Recipe.FOOD_ID_OFFSET + recipe)

        assertEquals(10, foods.getRecentFoods(10).size)
    }

    // Food Detail cannot open a recipe id; recipes are logged from the Recipes screen
    @Test
    fun recipesAreLeftOut() = runBlocking {
        log(2)
        log(Recipe.FOOD_ID_OFFSET + 5)
        log(Recipe.UNLINKED_FOOD_ID)

        assertEquals(listOf(2L), foods.getRecentFoods(10).map { it.id })
    }

    @Test
    fun anEmptyDiaryHasNoRecents() = runBlocking {
        assertTrue(foods.getRecentFoods(10).isEmpty())
        assertTrue(FoodRepositoryImpl(catalog, db.customFoodDao()).getRecentFoods(10).isEmpty())
    }
}
