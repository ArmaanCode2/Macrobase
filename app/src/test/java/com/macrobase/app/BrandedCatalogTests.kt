package com.macrobase.app

import android.database.sqlite.SQLiteDatabase
import com.macrobase.app.core.config.DatabaseConfig
import com.macrobase.app.data.database.BuiltInDatabaseManager
import com.macrobase.app.data.provider.LocalFoodDatabaseProvider
import com.macrobase.app.domain.model.FoodType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/**
 * The Open Food Facts products in branded_foods.db (tools/catalog/build_branded.py), searched and
 * looked up beside the built-in catalog through LocalFoodDatabaseProvider.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BrandedCatalogTests {

    private lateinit var provider: LocalFoodDatabaseProvider
    private lateinit var main: BuiltInDatabaseManager
    private lateinit var branded: BuiltInDatabaseManager

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        // Installed the way a phone has them, so the managers open them instead of copying from assets
        for (name in listOf(DatabaseConfig.BUILT_IN_FOODS_DB_NAME, DatabaseConfig.BRANDED_FOODS_DB_NAME)) {
            val installed = context.getDatabasePath(name)
            installed.parentFile?.mkdirs()
            asset(name).copyTo(installed, overwrite = true)
        }
        main = BuiltInDatabaseManager(context)
        branded = BuiltInDatabaseManager(context, BuiltInDatabaseManager.Catalog.BRANDED)
        provider = LocalFoodDatabaseProvider(main, branded)
    }

    @Test
    fun brandedCatalog_isItsOwnDatasetAndVersion() = runBlocking {
        val db = branded.getDatabase()
        val metadata = db.rawQuery("SELECT key, value FROM database_metadata", null).use { cursor ->
            buildMap { while (cursor.moveToNext()) put(cursor.getString(0), cursor.getString(1)) }
        }
        assertEquals(BuiltInDatabaseManager.BRANDED_DATASET_NAME, metadata["dataset_name"])
        assertEquals(BuiltInDatabaseManager.BRANDED_DATABASE_VERSION, metadata["database_version"])
        assertTrue("Only ${metadata["total_foods"]} branded products", (metadata["total_foods"]?.toInt() ?: 0) >= 1000)
        assertTrue(
            !BuiltInDatabaseManager.isCurrentCatalog(
                BuiltInDatabaseManager.EXPECTED_DATASET_NAME, BuiltInDatabaseManager.EXPECTED_DATABASE_VERSION,
                expected = BuiltInDatabaseManager.Catalog.BRANDED
            )
        )
    }

    @Test
    fun aBrandSearchFindsTheProductFirst() = runBlocking {
        val found = provider.searchFoods("parle g", 30)
        assertTrue("'parle g' finds ${found.firstOrNull()?.name}", found.first().name.startsWith("Parle-G"))
        assertEquals(FoodType.BRANDED, found.first().foodType)
    }

    @Test
    fun theCatalogsFoodsComeBeforeBrandsThatMatchAsWell() = runBlocking {
        val biscuits = provider.searchFoods("biscuit", 30)
        assertEquals(FoodType.GENERIC, biscuits.first().foodType)
        assertTrue("No branded biscuits", biscuits.any { it.foodType == FoodType.BRANDED })

        // "Maggi" is an alias of the cooked noodles, so they come first and Maggi's own products after
        val maggi = provider.searchFoods("maggi", 30)
        assertEquals("Instant noodles (masala), cooked", maggi.first().name)
        assertTrue(maggi.drop(1).any { it.foodType == FoodType.BRANDED && it.name.startsWith("Maggi") })
    }

    @Test
    fun aBrandedProductIsFoundByIdWithItsServings() = runBlocking {
        val parle = provider.searchFoods("parle g", 5).first()
        val food = provider.getFoodById(parle.id)
        assertNotNull(food)
        assertEquals(parle.name, food!!.name)
        assertEquals("100 g", food.servings.first { it.isDefault }.description)
        assertEquals(food.servings, provider.getFoodServings(parle.id))
    }

    @Test
    fun theCatalogsNeverShareAFoodIdOrUseTheUsersRanges() {
        val ids = { name: String ->
            SQLiteDatabase.openDatabase(asset(name).absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                db.rawQuery("SELECT id FROM foods", null).use { c -> buildSet { while (c.moveToNext()) add(c.getLong(0)) } }
            }
        }
        val mainIds = ids(DatabaseConfig.BUILT_IN_FOODS_DB_NAME)
        val brandedIds = ids(DatabaseConfig.BRANDED_FOODS_DB_NAME)
        assertEquals(emptySet<Long>(), mainIds intersect brandedIds)
        // Custom foods and recipes take 100,000,000 up to 300,000,000 (FoodRepositoryImpl, Recipe)
        assertEquals(emptyList<Long>(), brandedIds.filter { it <= 0 || it in 100_000_000L until 300_000_000L })
    }

    private fun asset(name: String): File =
        listOf(File("src/main/assets/databases/$name"), File("app/src/main/assets/databases/$name")).first { it.isFile }
}
