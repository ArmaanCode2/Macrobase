package com.macrobase.app

import com.macrobase.app.core.config.DatabaseConfig
import com.macrobase.app.data.database.BuiltInDatabaseManager
import com.macrobase.app.data.provider.LocalFoodDatabaseProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/**
 * BUG-004: everyday plurals and short phrases, through the provider the app runs: both catalogs on
 * SQLite without FTS5. Every query word had to start a stored word, so "apples" found nothing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PluralAndPhraseSearchTests {

    private lateinit var provider: LocalFoodDatabaseProvider

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        // Installed the way a phone has them, so the managers open them instead of copying from assets
        for (name in listOf(DatabaseConfig.BUILT_IN_FOODS_DB_NAME, DatabaseConfig.BRANDED_FOODS_DB_NAME)) {
            val installed = context.getDatabasePath(name)
            installed.parentFile?.mkdirs()
            listOf(File("src/main/assets/databases/$name"), File("app/src/main/assets/databases/$name"))
                .first { it.isFile }
                .copyTo(installed, overwrite = true)
        }
        provider = LocalFoodDatabaseProvider(
            BuiltInDatabaseManager(context),
            BuiltInDatabaseManager(context, BuiltInDatabaseManager.Catalog.BRANDED)
        )
    }

    private fun search(query: String): List<String> = runBlocking { provider.searchFoods(query, 30).map { it.name } }

    @Test
    fun aPluralFindsTheFoodItNames() {
        assertEquals("Apple", search("apples").first())
        assertEquals("Banana", search("bananas").first())
        assertEquals("Onion", search("onions").first())
        assertEquals("Potato", search("potatoes").first())
        assertTrue(search("samosas").first().contains("samosa", ignoreCase = true))
        assertTrue(search("dosas").first().contains("dosa", ignoreCase = true))
        assertTrue(search("rotis").first().contains("roti", ignoreCase = true))
    }

    // "tomatoes" found one food already, so retrying only on an empty result would not help it
    @Test
    fun aPluralThatFindsOneFoodStillFindsThePlainOne() {
        val found = search("tomatoes")

        assertEquals("Tomato", found.first())
        assertTrue(found.any { it.startsWith("Stuffed tomatoes") })
    }

    @Test
    fun aPhraseWithAConnectorWordFindsItsFoods() {
        assertTrue(search("dal and rice").isNotEmpty())
        assertTrue(search("tea with milk").first().contains("tea", ignoreCase = true))
    }

    @Test
    fun aPhraseNoFoodHoldsAllOfFindsTheClosestFoods() {
        assertEquals("Chinese fried rice", search("egg fried rice").first())
    }

    // Everything above is for queries that found too little; these found the right thing already
    @Test
    fun aWordThatEndsInSStaysOnItsOwnFoodsFirst() {
        val found = search("peas")

        assertTrue(found.first(), found.first().contains("pea", ignoreCase = true))
        assertFalse(found.take(5).any { it.contains("peanut", ignoreCase = true) })
    }

    @Test
    fun aFoodTheCatalogHoldsAsIsKeepsItsPlace() {
        assertEquals("Apple", search("apple").first())
        assertEquals("Banana", search("banana").first())
    }

    @Test
    fun aBrandedSearchIsNotAnsweredByOneOfItsWords() {
        // "g" alone starts most of the catalog; the product's full name must keep the results to itself
        assertTrue(search("parle g").first().contains("parle", ignoreCase = true))
        assertFalse(search("parle g").any { it.contains("ghee", ignoreCase = true) })
    }

    @Test
    fun wordsNoFoodHoldsStillFindNothing() {
        assertTrue(search("xyzzyq").isEmpty())
        assertTrue(search("xyzzyq qwertyz").isEmpty())
    }

    @Test
    fun oneUnknownWordDoesNotEmptyTheResult() {
        assertTrue(search("xyzzyq rice").any { it.contains("rice", ignoreCase = true) })
    }

    @Test
    fun onlyConnectorWordsAreNotAFood() {
        assertTrue(search("and the").none { it.equals("Apple", ignoreCase = true) })
    }

    @Test
    fun singularFormsCoverTheEverydayEndings() {
        val forms = mapOf(
            "apples" to "apple",
            "tomatoes" to "tomato",
            "potatoes" to "potato",
            "mangoes" to "mango",
            "berries" to "berry",
            "cookies" to "cookie",
            "sandwiches" to "sandwich",
            "dishes" to "dish",
            "leaves" to "leaf",
            "idlis" to "idli",
            "rotis" to "roti",
            "dosas" to "dosa",
            "peas" to "pea",
            // Not plurals
            "hummus" to "hummus",
            "swiss" to "swiss",
            "gas" to "gas",
            "dal" to "dal",
            "rice" to "rice"
        )

        forms.forEach { (word, singular) -> assertEquals(word, singular, LocalFoodDatabaseProvider.singularOf(word)) }
    }
}
