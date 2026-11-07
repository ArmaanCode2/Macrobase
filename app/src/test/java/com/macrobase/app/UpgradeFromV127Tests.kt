package com.macrobase.app

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import com.macrobase.app.core.config.DatabaseConfig
import com.macrobase.app.data.database.BuiltInDatabaseManager
import com.macrobase.app.data.database.UserDatabase
import com.macrobase.app.data.database.entity.DiaryEntryEntity
import com.macrobase.app.data.provider.FoodDataProvider
import com.macrobase.app.data.repository.DiaryRepositoryImpl
import com.macrobase.app.data.repository.FoodRepositoryImpl
import com.macrobase.app.data.repository.basket.InMemoryBasketRepository
import com.macrobase.app.domain.model.Food
import com.macrobase.app.domain.model.Goal
import com.macrobase.app.domain.model.Nutrition
import com.macrobase.app.domain.model.Serving
import com.macrobase.app.domain.model.ServingUnit
import com.macrobase.app.domain.repository.GoalsRepository
import com.macrobase.app.domain.usecase.CalculateNutritionForServingUseCase
import com.macrobase.app.domain.usecase.DeleteDiaryEntryUseCase
import com.macrobase.app.domain.usecase.GetDiaryEntryUseCase
import com.macrobase.app.domain.usecase.GetFoodDetailsUseCase
import com.macrobase.app.domain.usecase.UpdateDiaryEntryUseCase
import com.macrobase.app.domain.usecase.basket.AddFoodToBasketUseCase
import com.macrobase.app.domain.usecase.basket.CommitSingleBasketItemUseCase
import com.macrobase.app.domain.usecase.basket.GetBasketItemsUseCase
import com.macrobase.app.domain.usecase.basket.RemoveBasketItemUseCase
import com.macrobase.app.domain.usecase.basket.UpdateBasketItemUseCase
import com.macrobase.app.feature.detail.FoodDetailViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
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
import java.time.LocalDate

/**
 * Upgrading from v1.2.7 (catalog 2.4.0, branded 1.0.0) to this version (2.6.0, 1.1.0) with a phone's
 * data in place: the installed catalogs are replaced, and diary entries logged against the old catalogs
 * keep their values when opened, edited or filled in.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UpgradeFromV127Tests {

    private val openDatabases = mutableListOf<UserDatabase>()

    // Plain dosa as catalog 2.6.0 has it: with its cooking water, 75 g a dosa at 183.4 kcal per 100 g.
    // v1.2.7 had it near dry weight, 36.1 g a dosa at 381 kcal per 100 g: the same 137.5 kcal a dosa
    private val dosa = Food(
        id = DOSA_ID, uuid = "dosa", name = "Plain dosa",
        nutrition = Nutrition(183.4, 4.3, 30.0, 4.8, fiberGrams = 2.0, sodiumMg = 250.0),
        servings = listOf(
            Serving(id = 1, description = "100 g", unit = ServingUnit.GRAMS, gramWeight = 100.0, isDefault = true),
            Serving(id = 2, description = "1 dosa", unit = ServingUnit.PIECE, gramWeight = 75.0)
        )
    )

    private val catalog = object : FoodDataProvider {
        override val providerId = "test"
        override val displayName = "test"
        override val isLocal = true
        override suspend fun searchFoods(query: String, limit: Int) = emptyList<Food>()
        // Campa Lemon Flavoured is in branded 1.0.0 and not in 1.1.0
        override suspend fun getFoodById(id: Long): Food? = if (id == DOSA_ID) dosa else null
        override suspend fun getFoodByBarcode(barcode: String): Food? = null
        override suspend fun getFoodServings(foodId: Long) = emptyList<Serving>()
    }

    private val goals = object : GoalsRepository {
        private val state = MutableStateFlow(Goal())
        override suspend fun getGoals() = state.value
        override fun observeGoals() = state
        override suspend fun updateGoals(goals: Goal) { state.value = goals }
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        openDatabases.forEach { it.close() }
        Dispatchers.resetMain()
    }

    private fun newDatabase(): UserDatabase =
        Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), UserDatabase::class.java)
            .allowMainThreadQueries()
            .setQueryExecutor { it.run() }
            .setTransactionExecutor { it.run() }
            .build()
            .also { openDatabases += it }

    /** An entry as v1.2.7 saved it; [fiber] null is a version-2 entry whose fibre was never recorded. */
    private fun oldEntry(foodId: Long, name: String, serving: String, grams: Double, quantity: Double, calories: Double, fiber: Double?) =
        DiaryEntryEntity(
            uuid = "old-$name", dateEpochDay = LocalDate.of(2026, 10, 20).toEpochDay(), mealType = "BREAKFAST",
            foodId = foodId, foodName = name, userQuantity = quantity, servingDescription = serving, gramWeight = grams,
            loggedCalories = calories, loggedProtein = 3.2, loggedCarbs = 22.0, loggedFat = 3.6, loggedFiber = fiber
        )

    private fun editing(db: UserDatabase, entryId: Long, foodId: Long): FoodDetailViewModel {
        val foods = FoodRepositoryImpl(catalog, db.customFoodDao())
        val diary = DiaryRepositoryImpl(db, goals)
        val basket = InMemoryBasketRepository()
        return FoodDetailViewModel(
            GetFoodDetailsUseCase(foods), CalculateNutritionForServingUseCase(),
            AddFoodToBasketUseCase(basket), GetBasketItemsUseCase(basket), UpdateBasketItemUseCase(basket),
            RemoveBasketItemUseCase(basket), CommitSingleBasketItemUseCase(basket, diary),
            UpdateDiaryEntryUseCase(diary), DeleteDiaryEntryUseCase(diary), GetDiaryEntryUseCase(diary)
        ).also { it.loadFood(foodId = foodId, entryId = entryId) }
    }

    @Test
    fun anOldDosaKeepsItsCaloriesAndANewAmountScalesThem() = runBlocking {
        val db = newDatabase()
        val id = db.diaryDao().insertEntry(oldEntry(DOSA_ID, "Plain dosa", "1 dosa", 36.1, 1.0, 137.5, fiber = 1.0))
        val vm = editing(db, id, DOSA_ID)

        assertEquals(137.5, vm.uiState.value.calculatedNutrition.calories, 0.001)
        vm.onQuantityChange("2")
        assertEquals(275.0, vm.uiState.value.calculatedNutrition.calories, 0.001)
    }

    // The catalog's dosa is now wetter per gram: filling an old 36.1 g portion per gram would give it half
    // the fibre a dosa has. Filled by calories, as the rank fills old entries, it gets a dosa's fibre
    @Test
    fun aNeverRecordedNutrientIsFilledByTheEntrysCaloriesNotTheOldPortionsWeight() = runBlocking {
        val db = newDatabase()
        val id = db.diaryDao().insertEntry(oldEntry(DOSA_ID, "Plain dosa", "1 dosa", 36.1, 1.0, 137.5, fiber = null))
        val vm = editing(db, id, DOSA_ID)

        assertEquals(137.5, vm.uiState.value.calculatedNutrition.calories, 0.001)
        assertEquals(2.0 * 137.5 / 183.4, vm.uiState.value.calculatedNutrition.fiberGrams!!, 0.001)
    }

    @Test
    fun anEntryForAProductTheNewCatalogDropsOpensWithItsOwnValues() = runBlocking {
        val db = newDatabase()
        val id = db.diaryDao().insertEntry(oldEntry(CAMPA_LEMON_ID, "Campa Lemon Flavoured", "100 g", 100.0, 2.0, 92.0, fiber = 0.0))
        val vm = editing(db, id, CAMPA_LEMON_ID)

        assertTrue(vm.uiState.value.food!!.isSnapshot)
        assertEquals(92.0, vm.uiState.value.calculatedNutrition.calories, 0.001)
        vm.onQuantityChange("1")
        assertEquals(46.0, vm.uiState.value.calculatedNutrition.calories, 0.001)
    }

    /** The dataset, version and content fingerprint a catalog file says it holds, as the app reads them. */
    private fun metadata(file: File): Triple<String?, String?, String?> =
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            val values = db.rawQuery("SELECT key, value FROM database_metadata", null).use { c ->
                buildMap { while (c.moveToNext()) put(c.getString(0), c.getString(1)) }
            }
            Triple(values["dataset_name"], values["database_version"], values["content_fingerprint"])
        }

    // On a phone each installed catalog carries its version, and the app replaces one that is not the
    // bundled catalog from the APK on first open. Robolectric has no APK assets here, so this checks the
    // decision: v1.2.7's catalogs are not reused, and this version's bundled ones are
    @Test
    fun theCatalogsInstalledByV127AreReplacedAndTheBundledOnesKept() {
        val context = RuntimeEnvironment.getApplication()
        val catalogs = listOf(
            Triple(DatabaseConfig.BUILT_IN_FOODS_DB_NAME, "2.4.0", BuiltInDatabaseManager.Catalog.MAIN),
            Triple(DatabaseConfig.BRANDED_FOODS_DB_NAME, "1.0.0", BuiltInDatabaseManager.Catalog.BRANDED)
        )
        for ((name, oldVersion, which) in catalogs) {
            val bundled = listOf(File("src/main/assets/databases/$name"), File("app/src/main/assets/databases/$name")).first { it.isFile }
            val bundledFingerprint = File(bundled.parentFile, name.removeSuffix(".db") + ".fingerprint").readText().trim()
            val installed = context.getDatabasePath("v127-$name")
            installed.parentFile?.mkdirs()
            bundled.copyTo(installed, overwrite = true)
            SQLiteDatabase.openDatabase(installed.path, null, SQLiteDatabase.OPEN_READWRITE).use {
                it.execSQL("UPDATE database_metadata SET value = ? WHERE key = 'database_version'", arrayOf(oldVersion))
            }

            val (oldSet, oldVer, oldPrint) = metadata(installed)
            assertFalse(name, BuiltInDatabaseManager.isCurrentCatalog(oldSet, oldVer, oldPrint, bundledFingerprint, which))
            val (set, ver, print) = metadata(bundled)
            assertTrue(name, BuiltInDatabaseManager.isCurrentCatalog(set, ver, print, bundledFingerprint, which))
        }
    }

    private companion object {
        const val DOSA_ID = 1_896_880_878_330_400L
        // OFF:8904132952621's id, as import_branded_foods.py derives it
        const val CAMPA_LEMON_ID = 253_779_657_341_016_727L
    }
}
