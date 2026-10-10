package com.macrobase.app

import androidx.room.Room
import com.macrobase.app.data.database.UserDatabase
import com.macrobase.app.data.database.entity.DiaryEntryEntity
import com.macrobase.app.data.database.entity.RecipeEntity
import com.macrobase.app.data.provider.FoodDataProvider
import com.macrobase.app.data.repository.DiaryFoodLinkRepair
import com.macrobase.app.data.repository.DiaryRepositoryImpl
import com.macrobase.app.data.repository.FoodRepositoryImpl
import com.macrobase.app.data.repository.PortabilityRepositoryImpl
import com.macrobase.app.data.repository.basket.InMemoryBasketRepository
import com.macrobase.app.domain.model.CustomFood
import com.macrobase.app.domain.model.Food
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
import com.macrobase.app.feature.basket.extractUnitDescription
import com.macrobase.app.feature.detail.parseQuantityInput
import com.macrobase.app.feature.detail.FoodDetailViewModel
import com.macrobase.app.feature.detail.formatQuantityForInput
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
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.time.LocalDate

/**
 * Completes BUG-003, BUG-006, BUG-035 and BUG-036 (BUG-034 is covered by NutrientRoundingLintTest).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PartialFixCompletionTests {

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

    private fun newDatabase(): UserDatabase =
        Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), UserDatabase::class.java)
            .allowMainThreadQueries()
            .setQueryExecutor { it.run() }
            .setTransactionExecutor { it.run() }
            .build()
            .also { openDatabases += it }

    private fun customFood(name: String, calories: Double, size: Double = 100.0, unit: ServingUnit = ServingUnit.GRAMS, uuid: String = "uuid-$name") =
        CustomFood(
            uuid = uuid,
            name = name,
            servingSize = size,
            servingUnit = unit,
            nutritionPerServing = Nutrition(calories = calories, proteinGrams = 2.0, carbsGrams = 10.0, fatGrams = 1.0)
        )

    private fun diaryRow(uuid: String, foodId: Long, foodName: String, quantity: Double, calories: Double, serving: String = "100 g", grams: Double = 100.0) =
        DiaryEntryEntity(
            uuid = uuid,
            dateEpochDay = LocalDate.of(2026, 9, 1).toEpochDay(),
            mealType = "LUNCH",
            foodId = foodId,
            foodName = foodName,
            userQuantity = quantity,
            servingDescription = serving,
            gramWeight = grams,
            loggedCalories = calories,
            loggedProtein = 20.0,
            loggedCarbs = 40.0,
            loggedFat = 15.0
        )

    // BUG-006: entries mis-linked by restores in earlier versions are re-pointed in place
    @Test
    fun linkRepair_repointsMisLinkedEntries_andTouchesNothingElse() = runBlocking {
        val db = newDatabase()
        val foods = FoodRepositoryImpl(noCatalog, db.customFoodDao())
        val zucchini = foods.saveCustomFood(customFood("Zucchini", 17.0))
        val apple = foods.saveCustomFood(customFood("Apple", 52.0))
        foods.saveCustomFood(customFood("Greek Yogurt", 60.0, uuid = "y-a"))
        foods.saveCustomFood(customFood("Greek Yogurt", 120.0, uuid = "y-b"))

        val mislinked = db.diaryDao().insertEntry(diaryRow("apple-on-zucchini", zucchini, "Apple", 2.0, 104.0))
        val missing = db.diaryDao().insertEntry(diaryRow("apple-missing-row", FoodRepositoryImpl.CUSTOM_FOOD_ID_OFFSET + 99, "Apple", 1.0, 52.0))
        val correct = db.diaryDao().insertEntry(diaryRow("zucchini-ok", zucchini, "Zucchini", 1.0, 17.0))
        val ambiguous = db.diaryDao().insertEntry(diaryRow("yogurt-on-apple", apple, "Greek Yogurt", 1.0, 60.0))
        val before = db.diaryDao().getAllEntries().associateBy { it.id }

        val repair = DiaryFoodLinkRepair(db)
        assertEquals(2, repair.run())
        assertEquals(0, repair.run())

        val after = db.diaryDao().getAllEntries().associateBy { it.id }
        assertEquals(apple, after.getValue(mislinked).foodId)
        assertEquals(apple, after.getValue(missing).foodId)
        assertEquals(zucchini, after.getValue(correct).foodId)
        assertEquals("Ambiguous names are left alone", apple, after.getValue(ambiguous).foodId)
        for ((id, row) in after) {
            assertEquals("Only the food id may change", before.getValue(id).copy(foodId = row.foodId), row)
        }
    }

    // A food renamed after logging, or a different food created later with the old name,
    // is never adopted by the repair
    @Test
    fun linkRepair_ignoresRenamesAndFoodsCreatedAfterTheEntry() = runBlocking {
        val db = newDatabase()
        val foods = FoodRepositoryImpl(noCatalog, db.customFoodDao())
        val original = foods.saveCustomFood(customFood("Paneer", 265.0).copy(createdAt = java.time.Instant.ofEpochMilli(1_000)))
        val entryId = db.diaryDao().insertEntry(diaryRow("paneer", original, "Paneer", 1.0, 265.0).copy(createdAt = 2_000))
        val renamed = checkNotNull(foods.getCustomFood(original)).copy(name = "Paneer (homemade)")
        foods.saveCustomFood(renamed)
        foods.saveCustomFood(customFood("Paneer", 300.0, uuid = "paneer-new").copy(createdAt = java.time.Instant.ofEpochMilli(3_000)))

        assertEquals(0, DiaryFoodLinkRepair(db).run())
        assertEquals(original, checkNotNull(db.diaryDao().getEntryById(entryId)).foodId)
    }

    @Test
    fun linkRepair_runsOnlyOncePerInstall() = runBlocking {
        val db = newDatabase()
        val foods = FoodRepositoryImpl(noCatalog, db.customFoodDao())
        val zucchini = foods.saveCustomFood(customFood("Zucchini", 17.0).copy(createdAt = java.time.Instant.ofEpochMilli(1_000)))
        val apple = foods.saveCustomFood(customFood("Apple", 52.0).copy(createdAt = java.time.Instant.ofEpochMilli(1_000)))
        db.diaryDao().insertEntry(diaryRow("apple", zucchini, "Apple", 1.0, 52.0))

        var done = false
        val repair = DiaryFoodLinkRepair(db)
        assertEquals(1, repair.runOnce(isDone = { done }, markDone = { done = true }))
        assertTrue(done)
        db.diaryDao().insertEntry(diaryRow("apple-2", zucchini, "Apple", 1.0, 52.0))
        assertEquals(0, repair.runOnce(isDone = { done }, markDone = { done = true }))
        assertEquals(zucchini, db.diaryDao().getAllEntries().single { it.uuid == "apple-2" }.foodId)
        assertEquals(apple, db.diaryDao().getAllEntries().single { it.uuid == "apple" }.foodId)
    }

    // BUG-034: the dashboard strip agrees with the rounded intake it shows
    @Test
    fun dashboardBalance_isConsistentWithRoundedIntake() {
        val atHalfUnder = com.macrobase.app.domain.model.DailyNutritionSummary(date = LocalDate.of(2026, 9, 1), totalCaloriesIntake = 1999.5, calorieGoal = 2000.0)
        assertEquals(0, atHalfUnder.displayedCalorieBalance)
        assertFalse(atHalfUnder.isOverBudget)

        val atHalfOver = atHalfUnder.copy(totalCaloriesIntake = 2000.5)
        assertEquals(-1, atHalfOver.displayedCalorieBalance)
        assertTrue(atHalfOver.isOverBudget)

        // Shown as 2000 of 2000: not over, even though the raw intake is 0.4 above the goal
        val justAbove = atHalfUnder.copy(totalCaloriesIntake = 2000.4)
        assertEquals(0, justAbove.displayedCalorieBalance)
        assertFalse(justAbove.isOverBudget)

        val almost = atHalfUnder.copy(totalCaloriesIntake = 1999.8)
        assertEquals(0, almost.displayedCalorieBalance)
    }

    // BUG-003: a custom serving keeps its own size even though custom foods reuse serving ids
    @Test
    fun customPortion_changedSinceLogging_cannotBeScaledById() {
        val now = customFood("Soup", 300.0, size = 2.0, unit = ServingUnit.CUP).copy(id = 100_000_001L).toFood()
        val loggedAsOneCup = customFood("Soup", 180.0, size = 1.0, unit = ServingUnit.CUP).copy(id = 100_000_001L).toFood().defaultServing!!
        assertEquals(now.defaultServing!!.id, loggedAsOneCup.id)
        assertFalse(now.canScale(loggedAsOneCup))
    }

    @Test
    fun quantityFormatting_hasNoFloatNoise() {
        assertEquals("0.3", formatQuantityForInput(0.1 + 0.2))
        assertEquals("10000000", formatQuantityForInput(1e7))
        assertEquals("199.6", formatQuantityForInput(199.6))
        assertEquals(1.5, parseQuantityInput("1,5")!!, 0.0)
    }

    // BUG-006: recipe entries follow their recipe to its new row id on restore
    @Test
    fun restore_relinksRecipeEntriesToTheirRestoredRecipes() = runBlocking {
        val source = newDatabase()
        source.recipeDao().insertRecipe(RecipeEntity(uuid = "r-old", name = "Old curry", servingsProduced = 2, ingredientsJson = "[]", caloriesPerServing = 1.0, proteinPerServing = 1.0, carbsPerServing = 1.0, fatPerServing = 1.0))
        val dalId = source.recipeDao().insertRecipe(RecipeEntity(uuid = "r-dal", name = "Dal", servingsProduced = 4, ingredientsJson = "[]", caloriesPerServing = 400.0, proteinPerServing = 20.0, carbsPerServing = 50.0, fatPerServing = 12.0))
        source.recipeDao().deleteRecipe(1)
        source.diaryDao().insertEntry(diaryRow("dal-entry", Recipe.FOOD_ID_OFFSET + dalId, "Dal", 1.0, 400.0, "Serving", 0.0))

        val bytes = ByteArrayOutputStream().also { PortabilityRepositoryImpl(source, goals, preferences).writeBackupArchive(it) }.toByteArray()
        val target = newDatabase()
        val portability = PortabilityRepositoryImpl(target, goals, preferences)
        val validation = portability.validateBackupArchive(ByteArrayInputStream(bytes))
        assertTrue(portability.importUserData(checkNotNull(validation.backupData), ImportMode.OVERWRITE).isSuccess)

        val restoredDal = target.recipeDao().getAllRecipes().single { it.uuid == "r-dal" }
        assertTrue("Fixture must change the recipe row id", restoredDal.id != dalId)
        assertEquals(Recipe.FOOD_ID_OFFSET + restoredDal.id, target.diaryDao().getAllEntries().single().foodId)
    }

    // BUG-003: no silent "1 portion" guess for a serving the food cannot scale
    @Test
    fun customFood_unscalableServing_isRefusedNotGuessed() {
        val cup = customFood("Soup", 180.0, size = 1.0, unit = ServingUnit.CUP).copy(id = 100_000_001L).toFood()
        val foreign = Serving(description = "1 bowl", unit = ServingUnit.BOWL, quantity = 1.0, gramWeight = 0.0)

        assertFalse(cup.canScale(foreign))
        assertEquals(0.0, cup.nutritionFor(foreign, 2.0).calories, 0.0)
        assertTrue(cup.canScale(cup.defaultServing!!))
        assertEquals(360.0, cup.nutritionFor(cup.defaultServing!!, 2.0).calories, 0.001)
    }

    @Test
    fun recipeLoggedServing_scalesInTheBasket() {
        val food = Recipe(id = 3, name = "Dal", servingsProduced = 4, savedNutritionPerServing = Nutrition(400.0, 20.0, 50.0, 12.0)).toFood()
        val logged = food.servings.first().copy(description = "Serving", gramWeight = 0.0)
        assertTrue(food.canScale(logged))
        assertEquals(1000.0, food.nutritionFor(logged, 2.5).calories, 0.001)
    }

    @Test
    fun editEntry_whoseCustomPortionChanged_usesItsSnapshot() = runBlocking {
        val db = newDatabase()
        val foods = FoodRepositoryImpl(noCatalog, db.customFoodDao())
        // Logged as "1 cup" at 180 kcal x2; the food has since been changed to "2 cup"
        val soupId = foods.saveCustomFood(customFood("Soup", 300.0, size = 2.0, unit = ServingUnit.CUP))
        val entryId = db.diaryDao().insertEntry(diaryRow("soup", soupId, "Soup", 2.0, 360.0, "1 cup", 0.0))

        val diary = DiaryRepositoryImpl(db, goals)
        val basket = InMemoryBasketRepository()
        val viewModel = FoodDetailViewModel(
            GetFoodDetailsUseCase(foods), CalculateNutritionForServingUseCase(),
            AddFoodToBasketUseCase(basket), GetBasketItemsUseCase(basket), UpdateBasketItemUseCase(basket),
            RemoveBasketItemUseCase(basket), CommitSingleBasketItemUseCase(basket, diary),
            UpdateDiaryEntryUseCase(diary), DeleteDiaryEntryUseCase(diary), GetDiaryEntryUseCase(diary)
        )
        viewModel.loadFood(foodId = soupId, entryId = entryId)
        assertTrue(viewModel.uiState.value.food!!.isSnapshot)
        assertEquals(360.0, viewModel.uiState.value.calculatedNutrition.calories, 0.001)

        viewModel.onQuantityChange("3")
        assertEquals(540.0, viewModel.uiState.value.calculatedNutrition.calories, 0.001)
    }

    // BUG-035
    @Test
    fun basketQuantity_acceptsDecimalsAndShowsThemExactly() {
        assertEquals(1.5, parseQuantityInput("1,5")!!, 0.0)
        assertEquals(0.25, parseQuantityInput(" 0.25 ")!!, 0.0)
        assertEquals("0.25", formatQuantityForInput(0.25))
        assertEquals("1", formatQuantityForInput(1.0))
        assertEquals("\u00D7 100 g", extractUnitDescription("100 g"))
    }

    // BUG-036
    @Test
    fun listSubtitle_alwaysNamesTheBasisOfTheShownCalories() {
        val catalog = Food(
            id = 1896880878330341L, uuid = "c", name = "Chapati", brand = "Aashirvaad",
            nutrition = Nutrition(202.0, 5.9, 35.7, 3.6),
            servings = listOf(Serving(id = 1, description = "100 g", unit = ServingUnit.GRAMS, gramWeight = 100.0, isDefault = true))
        )
        assertEquals("Aashirvaad \u2022 per 100 g", catalog.listSubtitle)

        val shake = customFood("Shake", 120.0, size = 1.0, unit = ServingUnit.SERVING).copy(id = 100_000_002L, brand = "MyBrand").toFood()
        assertEquals("MyBrand \u2022 per 1 serving", shake.listSubtitle)
        assertEquals("per 1 serving", shake.copy(brand = null).listSubtitle)
    }
}
