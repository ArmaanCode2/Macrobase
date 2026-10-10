package com.macrobase.app

import androidx.room.Room
import com.macrobase.app.data.database.UserDatabase
import com.macrobase.app.data.database.entity.DiaryEntryEntity
import com.macrobase.app.data.provider.FoodDataProvider
import com.macrobase.app.data.repository.DiaryRepositoryImpl
import com.macrobase.app.data.repository.FoodRepositoryImpl
import com.macrobase.app.data.repository.PortabilityRepositoryImpl
import com.macrobase.app.data.repository.basket.InMemoryBasketRepository
import com.macrobase.app.domain.model.CustomFood
import com.macrobase.app.domain.model.Food
import com.macrobase.app.domain.model.FoodSource
import com.macrobase.app.domain.model.Goal
import com.macrobase.app.domain.model.ImportMode
import com.macrobase.app.domain.model.Nutrition
import com.macrobase.app.domain.model.Recipe
import com.macrobase.app.domain.model.Serving
import com.macrobase.app.domain.model.ServingUnit
import com.macrobase.app.domain.model.UserPreferences
import com.macrobase.app.domain.repository.GoalsRepository
import com.macrobase.app.domain.repository.PreferencesRepository
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.time.LocalDate

/**
 * BUG-005 / BUG-009 (diary food identity) and BUG-006 / BUG-007 (backup round trips)
 * against a real Room database, including rows and backups written by earlier versions.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PhaseTwoIdentityAndBackupTests {

    private val openDatabases = mutableListOf<UserDatabase>()

    private val noCatalog = object : FoodDataProvider {
        override val providerId = "test"
        override val displayName = "test"
        override val isLocal = true
        override suspend fun searchFoods(query: String, limit: Int) = emptyList<Food>()
        override suspend fun getFoodById(id: Long): Food? = null
        override suspend fun getFoodByBarcode(barcode: String): Food? = null
        override suspend fun getFoodServings(foodId: Long) = emptyList<Serving>()
    }

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

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        openDatabases.forEach { it.close() }
        Dispatchers.resetMain()
    }

    /** Room runs queries inline so ViewModel coroutines finish before assertions. */
    private fun newDatabase(): UserDatabase =
        Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), UserDatabase::class.java)
            .allowMainThreadQueries()
            .setQueryExecutor { it.run() }
            .setTransactionExecutor { it.run() }
            .build()
            .also { openDatabases += it }

    private fun customFood(name: String, calories: Double) = CustomFood(
        uuid = "uuid-$name",
        name = name,
        servingSize = 100.0,
        servingUnit = ServingUnit.GRAMS,
        nutritionPerServing = Nutrition(calories = calories, proteinGrams = 2.0, carbsGrams = 10.0, fatGrams = 1.0)
    )

    private fun diaryRow(
        uuid: String,
        foodId: Long,
        foodName: String,
        quantity: Double,
        calories: Double,
        servingDescription: String = "Serving",
        gramWeight: Double = 0.0,
        fiber: Double? = null,
        sugar: Double? = null,
        sodium: Double? = null
    ) = DiaryEntryEntity(
        uuid = uuid,
        dateEpochDay = LocalDate.of(2026, 9, 1).toEpochDay(),
        mealType = "LUNCH",
        foodId = foodId,
        foodName = foodName,
        userQuantity = quantity,
        servingDescription = servingDescription,
        gramWeight = gramWeight,
        loggedCalories = calories,
        loggedProtein = 20.0,
        loggedCarbs = 40.0,
        loggedFat = 15.0,
        loggedFiber = fiber,
        loggedSugar = sugar,
        loggedSodium = sodium
    )

    private fun detailViewModel(db: UserDatabase, basket: InMemoryBasketRepository = InMemoryBasketRepository()): FoodDetailViewModel {
        val foods = FoodRepositoryImpl(noCatalog, db.customFoodDao())
        val diary = DiaryRepositoryImpl(db, goals)
        return FoodDetailViewModel(
            GetFoodDetailsUseCase(foods),
            CalculateNutritionForServingUseCase(),
            AddFoodToBasketUseCase(basket),
            GetBasketItemsUseCase(basket),
            UpdateBasketItemUseCase(basket),
            RemoveBasketItemUseCase(basket),
            CommitSingleBasketItemUseCase(basket, diary),
            UpdateDiaryEntryUseCase(diary),
            DeleteDiaryEntryUseCase(diary),
            GetDiaryEntryUseCase(diary)
        )
    }

    // BUG-005: an entry for recipe #1 (raw id 1 from earlier versions) opened custom food #1
    @Test
    fun editLegacyRecipeEntry_neverLoadsCustomFoodWithSameRowId_andSaveKeepsIt() = runBlocking {
        val db = newDatabase()
        val foods = FoodRepositoryImpl(noCatalog, db.customFoodDao())
        assertEquals(FoodRepositoryImpl.CUSTOM_FOOD_ID_OFFSET + 1, foods.saveCustomFood(customFood("Protein shake", 120.0)))
        val entryId = db.diaryDao().insertEntry(diaryRow("recipe-entry", foodId = 1L, foodName = "Dal", quantity = 2.0, calories = 800.0))

        assertNull("Small ids are never raw custom-food row ids", foods.getFoodById(1L))

        val viewModel = detailViewModel(db)
        viewModel.loadFood(foodId = 1L, entryId = entryId)
        val loaded = viewModel.uiState.value
        assertEquals("Dal", loaded.food?.name)
        assertEquals(800.0, loaded.calculatedNutrition.calories, 0.001)

        var saved = false
        viewModel.logFood { saved = true }
        assertTrue(saved)
        val row = checkNotNull(db.diaryDao().getEntryById(entryId))
        assertEquals(1L, row.foodId)
        assertEquals("Dal", row.foodName)
        assertEquals(800.0, row.loggedCalories, 0.001)
    }

    // BUG-009: editing the quantity of a snapshot-only entry double-scaled it
    @Test
    fun editSnapshotEntryQuantity_scalesPerUnitNotTheWholeSnapshot() = runBlocking {
        val db = newDatabase()
        val entryId = db.diaryDao().insertEntry(diaryRow("old", foodId = 7L, foodName = "Old USDA food", quantity = 2.0, calories = 800.0))

        val viewModel = detailViewModel(db)
        viewModel.loadFood(foodId = 7L, entryId = entryId)
        viewModel.onQuantityChange("3")
        assertEquals(1200.0, viewModel.uiState.value.calculatedNutrition.calories, 0.001)

        viewModel.logFood {}
        assertEquals(1200.0, checkNotNull(db.diaryDao().getEntryById(entryId)).loggedCalories, 0.001)
    }

    @Test
    fun editEntry_whoseFoodWasRenamed_keepsTheLoggedFood() = runBlocking {
        val db = newDatabase()
        val foods = FoodRepositoryImpl(noCatalog, db.customFoodDao())
        val shakeId = foods.saveCustomFood(customFood("Protein shake", 120.0))
        val entryId = db.diaryDao().insertEntry(diaryRow("renamed", foodId = shakeId, foodName = "Whey shake", quantity = 1.0, calories = 150.0, servingDescription = "100 g", gramWeight = 100.0))

        val viewModel = detailViewModel(db)
        viewModel.loadFood(foodId = shakeId, entryId = entryId)
        assertEquals("Whey shake", viewModel.uiState.value.food?.name)
        assertEquals(150.0, viewModel.uiState.value.calculatedNutrition.calories, 0.001)
    }

    // A copied entry goes to the basket; editing the basket item must not swap in another food
    @Test
    fun copyEntryWithStaleFoodId_thenEditInBasket_keepsTheLoggedFood() = runBlocking {
        val db = newDatabase()
        val foods = FoodRepositoryImpl(noCatalog, db.customFoodDao())
        val riceId = foods.saveCustomFood(customFood("Rice", 130.0))
        val entryId = db.diaryDao().insertEntry(diaryRow("stale", foodId = riceId, foodName = "Shake", quantity = 2.0, calories = 300.0))
        val basket = InMemoryBasketRepository()

        val editor = detailViewModel(db, basket)
        editor.loadFood(foodId = riceId, entryId = entryId)
        var copiedFoodId = 0L
        var basketItemId = ""
        editor.copyEntry { foodId, _, _, itemId -> copiedFoodId = foodId; basketItemId = itemId }

        val basketEditor = detailViewModel(db, basket)
        basketEditor.loadFood(foodId = copiedFoodId, basketItemId = basketItemId)
        assertEquals("Shake", basketEditor.uiState.value.food?.name)
        assertEquals(300.0, basketEditor.uiState.value.calculatedNutrition.calories, 0.001)

        basketEditor.logFood {}
        val copied = db.diaryDao().getAllEntries().single { it.uuid != "stale" }
        assertEquals("Shake", copied.foodName)
        assertEquals(300.0, copied.loggedCalories, 0.001)
    }

    // BUG-010 must not get worse: filling missing fiber never replaces logged calories
    @Test
    fun editEntryMissingFiber_fillsFiberButKeepsLoggedCalories() = runBlocking {
        val db = newDatabase()
        val foods = FoodRepositoryImpl(noCatalog, db.customFoodDao())
        val oatsId = foods.saveCustomFood(
            customFood("Oats", 300.0).copy(nutritionPerServing = Nutrition(calories = 300.0, proteinGrams = 10.0, carbsGrams = 50.0, fatGrams = 6.0, fiberGrams = 8.0))
        )
        // Logged before the food was edited, and before fiber was recorded (fiber null)
        val entryId = db.diaryDao().insertEntry(diaryRow("oats", foodId = oatsId, foodName = "Oats", quantity = 1.0, calories = 150.0, servingDescription = "100 g", gramWeight = 100.0))

        val viewModel = detailViewModel(db)
        viewModel.loadFood(foodId = oatsId, entryId = entryId)
        val state = viewModel.uiState.value
        assertEquals(150.0, state.calculatedNutrition.calories, 0.001)
        assertEquals(20.0, state.calculatedNutrition.proteinGrams, 0.001)
        assertEquals(8.0, state.calculatedNutrition.fiberGrams!!, 0.001)
    }

    // BUG-037: a logged 0.0 is a real zero, so editing the entry never refills it from the food
    @Test
    fun editEntryWithZeroFiber_keepsTheLoggedZero() = runBlocking {
        val db = newDatabase()
        val foods = FoodRepositoryImpl(noCatalog, db.customFoodDao())
        val oatsId = foods.saveCustomFood(
            customFood("Oats", 300.0).copy(nutritionPerServing = Nutrition(calories = 300.0, proteinGrams = 10.0, carbsGrams = 50.0, fatGrams = 6.0, fiberGrams = 8.0))
        )
        val entryId = db.diaryDao().insertEntry(diaryRow("oats", foodId = oatsId, foodName = "Oats", quantity = 1.0, calories = 150.0, servingDescription = "100 g", gramWeight = 100.0, fiber = 0.0))

        val viewModel = detailViewModel(db)
        viewModel.loadFood(foodId = oatsId, entryId = entryId)
        assertEquals(0.0, viewModel.uiState.value.calculatedNutrition.fiberGrams!!, 0.0)
    }

    @Test
    fun recipeFoods_useTheirOwnIdRange() = runBlocking {
        val db = newDatabase()
        val recipeFood = Recipe(id = 1, name = "Dal", servingsProduced = 2).toFood()
        assertEquals(Recipe.FOOD_ID_OFFSET + 1, recipeFood.id)

        val entryId = db.diaryDao().insertEntry(diaryRow("new-recipe", foodId = recipeFood.id, foodName = "Dal", quantity = 1.0, calories = 400.0))
        val entry = checkNotNull(DiaryRepositoryImpl(db, goals).getEntryById(entryId))
        assertEquals(FoodSource.RECIPE, entry.food.source)
        assertTrue(entry.food.isSnapshot)
        assertFalse(entry.food.isCatalogFood)
        assertNull(FoodRepositoryImpl(noCatalog, db.customFoodDao()).getFoodById(recipeFood.id))
    }

    private suspend fun seedCustomFoodsAndEntries(db: UserDatabase) {
        val foods = FoodRepositoryImpl(noCatalog, db.customFoodDao())
        val zucchini = foods.saveCustomFood(customFood("Zucchini", 17.0))
        val apple = foods.saveCustomFood(customFood("Apple", 52.0))
        db.diaryDao().insertEntry(diaryRow("e-zucchini", zucchini, "Zucchini", 1.0, 17.0, "100 g", 100.0, fiber = 1.0, sugar = 2.5, sodium = 8.0))
        db.diaryDao().insertEntry(diaryRow("e-apple", apple, "Apple", 1.0, 52.0, "100 g", 100.0, fiber = 3.2, sugar = 5.1, sodium = 410.0))
    }

    private suspend fun backupBytes(db: UserDatabase): ByteArray {
        val out = ByteArrayOutputStream()
        PortabilityRepositoryImpl(db, goals, preferences).writeBackupArchive(out)
        return out.toByteArray()
    }

    private suspend fun restore(db: UserDatabase, bytes: ByteArray, mode: ImportMode) {
        val portability = PortabilityRepositoryImpl(db, goals, preferences)
        val validation = portability.validateBackupArchive(ByteArrayInputStream(bytes))
        assertTrue(validation.errorMessage ?: "invalid backup", validation.isValid)
        assertTrue(portability.importUserData(checkNotNull(validation.backupData), mode).isSuccess)
    }

    private suspend fun assertEntriesPointAtTheirOwnFoods(db: UserDatabase) {
        val foods = FoodRepositoryImpl(noCatalog, db.customFoodDao())
        val rows = db.diaryDao().getAllEntries()
        assertEquals(2, rows.size)
        for (row in rows) {
            assertEquals(row.foodName, foods.getFoodById(row.foodId)?.name)
        }
    }

    // BUG-006 + BUG-007, Overwrite restore on the same phone: custom foods get new row ids
    @Test
    fun overwriteRestore_sameDevice_remapsCustomFoodsAndKeepsMicronutrients() = runBlocking {
        val db = newDatabase()
        seedCustomFoodsAndEntries(db)
        restore(db, backupBytes(db), ImportMode.OVERWRITE)

        assertEntriesPointAtTheirOwnFoods(db)
        val apple = db.diaryDao().getAllEntries().single { it.foodName == "Apple" }
        assertEquals(3.2, apple.loggedFiber!!, 0.0)
        assertEquals(5.1, apple.loggedSugar!!, 0.0)
        assertEquals(410.0, apple.loggedSodium!!, 0.0)
    }

    @Test
    fun overwriteRestore_newDevice_remapsCustomFoods() = runBlocking {
        val source = newDatabase()
        seedCustomFoodsAndEntries(source)
        val target = newDatabase()
        // The new phone already used row ids 1 and 2 for foods it then deleted
        val targetFoods = FoodRepositoryImpl(noCatalog, target.customFoodDao())
        targetFoods.deleteCustomFood(targetFoods.saveCustomFood(customFood("Old 1", 1.0)))
        targetFoods.deleteCustomFood(targetFoods.saveCustomFood(customFood("Old 2", 1.0)))

        restore(target, backupBytes(source), ImportMode.OVERWRITE)
        assertEntriesPointAtTheirOwnFoods(target)
    }

    @Test
    fun mergeRestore_intoPhoneWithOtherCustomFoods_remapsCustomFoods() = runBlocking {
        val source = newDatabase()
        seedCustomFoodsAndEntries(source)
        val target = newDatabase()
        FoodRepositoryImpl(noCatalog, target.customFoodDao()).saveCustomFood(customFood("Banana", 89.0))

        restore(target, backupBytes(source), ImportMode.MERGE)
        assertEntriesPointAtTheirOwnFoods(target)
    }

    // Backups written before format 1.1.0: no custom food uuid, no fiber/sugar/sodium
    @Test
    fun oldFormatBackup_stillRestores_matchingCustomFoodsByName() = runBlocking {
        val source = newDatabase()
        seedCustomFoodsAndEntries(source)
        val current = checkNotNull(
            PortabilityRepositoryImpl(source, goals, preferences)
                .validateBackupArchive(ByteArrayInputStream(backupBytes(source))).backupData
        )
        val oldFormat = current.copy(
            diaryEntries = current.diaryEntries.map {
                it.copy(customFoodUuid = null, loggedFiber = null, loggedSugar = null, loggedSodium = null)
            }
        )
        val target = newDatabase()
        val targetFoods = FoodRepositoryImpl(noCatalog, target.customFoodDao())
        targetFoods.deleteCustomFood(targetFoods.saveCustomFood(customFood("Old 1", 1.0)))

        val portability = PortabilityRepositoryImpl(target, goals, preferences)
        assertTrue(portability.importUserData(oldFormat, ImportMode.OVERWRITE).isSuccess)

        assertEntriesPointAtTheirOwnFoods(target)
        // Unknown stays unknown instead of turning into a confident 0.0 (BUG-037)
        assertNull(target.diaryDao().getAllEntries().first().loggedFiber)
    }

    private suspend fun seedTwinYogurts(db: UserDatabase) {
        val foods = FoodRepositoryImpl(noCatalog, db.customFoodDao())
        val brandA = foods.saveCustomFood(customFood("Greek Yogurt", 60.0).copy(uuid = "yogurt-a", brand = "A"))
        val brandB = foods.saveCustomFood(customFood("Greek Yogurt", 120.0).copy(uuid = "yogurt-b", brand = "B"))
        db.diaryDao().insertEntry(diaryRow("e-a", brandA, "Greek Yogurt", 1.0, 60.0, "100 g", 100.0))
        db.diaryDao().insertEntry(diaryRow("e-b", brandB, "Greek Yogurt", 1.0, 120.0, "100 g", 100.0))
    }

    @Test
    fun restore_twinNamedCustomFoods_mapsByUuid() = runBlocking {
        val source = newDatabase()
        seedTwinYogurts(source)
        val target = newDatabase()
        restore(target, backupBytes(source), ImportMode.OVERWRITE)

        val foods = FoodRepositoryImpl(noCatalog, target.customFoodDao())
        for (row in target.diaryDao().getAllEntries()) {
            assertEquals(row.loggedCalories, checkNotNull(foods.getFoodById(row.foodId)).nutrition.calories, 0.001)
        }
    }

    @Test
    fun oldFormatRestore_twinNamedCustomFoods_unlinksInsteadOfGuessing() = runBlocking {
        val source = newDatabase()
        seedTwinYogurts(source)
        val current = checkNotNull(
            PortabilityRepositoryImpl(source, goals, preferences)
                .validateBackupArchive(ByteArrayInputStream(backupBytes(source))).backupData
        )
        val oldFormat = current.copy(diaryEntries = current.diaryEntries.map { it.copy(customFoodUuid = null) })
        val target = newDatabase()
        assertTrue(PortabilityRepositoryImpl(target, goals, preferences).importUserData(oldFormat, ImportMode.OVERWRITE).isSuccess)

        val rows = target.diaryDao().getAllEntries()
        assertTrue(rows.all { it.foodId == FoodRepositoryImpl.UNLINKED_CUSTOM_FOOD_ID })
        val entry = checkNotNull(DiaryRepositoryImpl(target, goals).getEntryById(rows.first { it.uuid == "e-b" }.id))
        assertEquals(FoodSource.CUSTOM_USER, entry.food.source)
        assertEquals(120.0, entry.calculatedNutrition.calories, 0.001)
        assertNull(FoodRepositoryImpl(noCatalog, target.customFoodDao()).getFoodById(FoodRepositoryImpl.UNLINKED_CUSTOM_FOOD_ID))
    }

    @Test
    fun export_misLinkedEntry_doesNotClaimTheWrongFood() = runBlocking {
        val db = newDatabase()
        val zucchini = FoodRepositoryImpl(noCatalog, db.customFoodDao()).saveCustomFood(customFood("Zucchini", 17.0))
        // An earlier restore left "Apple" pointing at the Zucchini row
        db.diaryDao().insertEntry(diaryRow("mislinked", zucchini, "Apple", 1.0, 52.0, "100 g", 100.0))

        val exported = PortabilityRepositoryImpl(db, goals, preferences).exportAllUserData().diaryEntries.single()
        assertNull(exported.customFoodUuid)
    }

    @Test
    fun oldFormatDiaryJson_parsesWithMissingFields() {
        val json = """[{"uuid":"a","dateEpochDay":20000,"dateString":"2024-10-04","mealType":"LUNCH","foodId":100000001,
            "foodName":"Apple","userQuantity":1.0,"servingDescription":"100 g","gramWeight":100.0,
            "loggedCalories":52.0,"loggedProtein":0.3,"loggedCarbs":14.0,"loggedFat":0.2,"createdAt":1}]"""
        val dto = com.macrobase.app.data.portability.BackupJsonSerializer.parseDiaryEntries(json).single()
        assertNull(dto.loggedFiber)
        assertNull(dto.customFoodUuid)
        assertEquals(52.0, dto.loggedCalories, 0.0)
    }

}
