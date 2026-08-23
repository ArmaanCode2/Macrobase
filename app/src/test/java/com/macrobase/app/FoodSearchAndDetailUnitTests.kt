package com.macrobase.app

import com.macrobase.app.domain.model.CustomFood
import com.macrobase.app.domain.model.Food
import com.macrobase.app.domain.model.MacroCalorieSplit
import com.macrobase.app.domain.model.Nutrition
import com.macrobase.app.domain.model.Serving
import com.macrobase.app.domain.model.ServingUnit
import com.macrobase.app.domain.repository.FoodRepository
import com.macrobase.app.domain.usecase.CalculateNutritionForServingUseCase
import com.macrobase.app.domain.usecase.GetFoodDetailsUseCase
import com.macrobase.app.feature.detail.FoodDetailViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.sql.Connection
import java.sql.DriverManager

@OptIn(ExperimentalCoroutinesApi::class)
class FoodSearchAndDetailUnitTests {

    private val testDispatcher = StandardTestDispatcher()
    private var connection: Connection? = null
    private val calculateNutritionUseCase = CalculateNutritionForServingUseCase()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)

        val candidates = listOf(
            File("src/main/assets/databases/built_in_foods.db"),
            File("app/src/main/assets/databases/built_in_foods.db"),
            File("built_in_foods.db"),
            File("../built_in_foods.db")
        )
        val assetDbFile = candidates.firstOrNull { it.exists() }
            ?: error("Cannot find built_in_foods.db in candidate locations")

        connection = DriverManager.getConnection("jdbc:sqlite:${assetDbFile.absolutePath}")
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        connection?.close()
    }

    @Test
    fun macroCalorieSplit_calculatesExactPercentages() {
        // 25g protein (100 kcal), 50g carbs (200 kcal), 11.111g fat (100 kcal) -> 400 kcal total
        val nutrition = Nutrition(
            calories = 400.0,
            proteinGrams = 25.0,
            carbsGrams = 50.0,
            fatGrams = 11.111111
        )

        val split = MacroCalorieSplit.fromNutrition(nutrition)

        assertEquals(100.0, split.proteinCalories, 0.01)
        assertEquals(200.0, split.carbCalories, 0.01)
        assertEquals(100.0, split.fatCalories, 0.01)
        assertEquals(400.0, split.totalMacroCalories, 0.01)

        assertEquals(25.0, split.proteinPercentage, 0.1)
        assertEquals(50.0, split.carbPercentage, 0.1)
        assertEquals(25.0, split.fatPercentage, 0.1)
    }

    @Test
    fun macroCalorieSplit_handlesZeroCaloriesSafelyWithoutNaN() {
        val zeroNutrition = Nutrition.ZERO
        val split = MacroCalorieSplit.fromNutrition(zeroNutrition)

        assertEquals(0.0, split.totalMacroCalories, 0.001)
        assertEquals(0.0, split.proteinPercentage, 0.001)
        assertEquals(0.0, split.carbPercentage, 0.001)
        assertEquals(0.0, split.fatPercentage, 0.001)

        assertFalse("Percentage must not be NaN", split.proteinPercentage.isNaN())
        assertFalse("Percentage must not be NaN", split.carbPercentage.isNaN())
        assertFalse("Percentage must not be NaN", split.fatPercentage.isNaN())
    }

    @Test
    fun macroCalorieSplit_handlesPureSingleMacroFoodSafely() {
        // Pure Protein (e.g. egg whites or isolate): 30g protein, 0g carb, 0g fat
        val pureProtein = Nutrition(calories = 120.0, proteinGrams = 30.0, carbsGrams = 0.0, fatGrams = 0.0)
        val pSplit = MacroCalorieSplit.fromNutrition(pureProtein)
        assertEquals(100.0, pSplit.proteinPercentage, 0.01)
        assertEquals(0.0, pSplit.carbPercentage, 0.01)
        assertEquals(0.0, pSplit.fatPercentage, 0.01)

        // Pure Fat (e.g. olive oil): 0g protein, 0g carb, 14g fat (126 kcal)
        val pureFat = Nutrition(calories = 126.0, proteinGrams = 0.0, carbsGrams = 0.0, fatGrams = 14.0)
        val fSplit = MacroCalorieSplit.fromNutrition(pureFat)
        assertEquals(0.0, fSplit.proteinPercentage, 0.01)
        assertEquals(0.0, fSplit.carbPercentage, 0.01)
        assertEquals(100.0, fSplit.fatPercentage, 0.01)
    }

    @Test
    fun foodDetailViewModel_recalculatesNutritionOnServingAndQuantityChanges() = runTest(testDispatcher) {
        val sampleFood = Food(
            id = 100,
            uuid = "sample-food",
            name = "Rolled Oats",
            nutrition = Nutrition(
                calories = 389.0,         // per 100g
                proteinGrams = 16.9,
                carbsGrams = 66.3,
                fatGrams = 6.9,
                fiberGrams = 10.6,
                sugarGrams = 0.0
            ),
            servings = listOf(
                Serving(id = 1, description = "100 g", unit = ServingUnit.GRAMS, quantity = 1.0, gramWeight = 100.0, isDefault = true),
                Serving(id = 2, description = "1 cup (81g)", unit = ServingUnit.CUP, quantity = 1.0, gramWeight = 81.0, isDefault = false),
                Serving(id = 3, description = "1/2 cup (40g)", unit = ServingUnit.CUP, quantity = 1.0, gramWeight = 40.0, isDefault = false)
            )
        )

        val fakeRepo = object : FoodRepository {
            override suspend fun searchFoods(query: String, limit: Int) = listOf(sampleFood)
            override suspend fun getFoodById(id: Long) = sampleFood
            override suspend fun getFoodByBarcode(barcode: String) = null
            override suspend fun getFoodServings(foodId: Long) = sampleFood.servings
            override fun observeCustomFoods() = kotlinx.coroutines.flow.flowOf(emptyList<Food>())
            override suspend fun getCustomFoods() = emptyList<Food>()
            override suspend fun saveCustomFood(customFood: CustomFood) = 1L
            override suspend fun deleteCustomFood(id: Long) {}
            override suspend fun getRecentFoods(limit: Int) = emptyList<Food>()
            override suspend fun getFavoriteFoods() = emptyList<Food>()
        }

        val fakeDiaryRepo = object : com.macrobase.app.domain.repository.DiaryRepository {
            override suspend fun getDiaryForDate(date: java.time.LocalDate) = com.macrobase.app.domain.model.DailyNutritionSummary(date = date)
            override fun observeDiaryForDate(date: java.time.LocalDate) = kotlinx.coroutines.flow.flowOf(com.macrobase.app.domain.model.DailyNutritionSummary(date = date))
            override suspend fun getEntryById(entryId: Long): com.macrobase.app.domain.model.DiaryEntry? = null
            override suspend fun addEntry(entry: com.macrobase.app.domain.model.DiaryEntry) = 1L
            override suspend fun addEntries(entries: List<com.macrobase.app.domain.model.DiaryEntry>) {}
            override suspend fun updateEntry(entry: com.macrobase.app.domain.model.DiaryEntry) {}
            override suspend fun deleteEntry(entryId: Long) {}
            override suspend fun getMonthlyAdherence(year: Int, month: Int) = emptyList<com.macrobase.app.domain.model.CalendarDaySummary>()
            override fun observeMonthlyAdherence(year: Int, month: Int) = kotlinx.coroutines.flow.flowOf(emptyList<com.macrobase.app.domain.model.CalendarDaySummary>())
            override fun observeNutritionAggregations(startDate: java.time.LocalDate, endDate: java.time.LocalDate) = kotlinx.coroutines.flow.flowOf(emptyList<com.macrobase.app.domain.repository.DailyMacroAggregation>())
            override suspend fun getNutritionAggregations(startDate: java.time.LocalDate, endDate: java.time.LocalDate) = emptyList<com.macrobase.app.domain.repository.DailyMacroAggregation>()
        }
        val fakeDetailUseCase = GetFoodDetailsUseCase(fakeRepo)
        val fakeBasketRepo = com.macrobase.app.data.repository.basket.InMemoryBasketRepository()
        val viewModel = FoodDetailViewModel(
            fakeDetailUseCase,
            calculateNutritionUseCase,
            com.macrobase.app.domain.usecase.basket.AddFoodToBasketUseCase(fakeBasketRepo),
            com.macrobase.app.domain.usecase.basket.GetBasketItemsUseCase(fakeBasketRepo),
            com.macrobase.app.domain.usecase.basket.UpdateBasketItemUseCase(fakeBasketRepo),
            com.macrobase.app.domain.usecase.basket.CommitSingleBasketItemUseCase(fakeBasketRepo, fakeDiaryRepo),
            com.macrobase.app.domain.usecase.UpdateDiaryEntryUseCase(fakeDiaryRepo),
            com.macrobase.app.domain.usecase.DeleteDiaryEntryUseCase(fakeDiaryRepo),
            com.macrobase.app.domain.usecase.GetDiaryEntryUseCase(fakeDiaryRepo)
        )

        // 1. Initial load
        viewModel.loadFood(100L)
        advanceUntilIdle()

        val state1 = viewModel.uiState.value
        assertEquals("Rolled Oats", state1.food?.name)
        assertEquals(3, state1.availableServings.size)
        assertEquals("100 g", state1.selectedServing?.description)
        assertEquals(389.0, state1.calculatedNutrition.calories, 0.1)

        // 2. Select 1/2 cup (40g -> 0.40x multiplier)
        val halfCupServing = state1.availableServings[2]
        viewModel.onServingSelected(halfCupServing)
        val state2 = viewModel.uiState.value
        assertEquals("1/2 cup (40g)", state2.selectedServing?.description)
        // 389 * 0.40 = 155.6 kcal
        assertEquals(155.6, state2.calculatedNutrition.calories, 0.1)
        assertEquals(6.76, state2.calculatedNutrition.proteinGrams, 0.01)

        // 3. Change quantity to 2.5 (2.5 * 40g = 100g -> 1.0x multiplier)
        viewModel.onQuantityChange("2.5")
        val state3 = viewModel.uiState.value
        assertEquals(2.5, state3.enteredQuantity, 0.001)
        assertEquals("2.5", state3.quantityInputText)
        assertEquals(389.0, state3.calculatedNutrition.calories, 0.1)
    }

    @Test
    fun builtInDatabase_fts5Queries_verifiesAllSpecifiedSampleFoods() {
        val conn = checkNotNull(connection)
        val foodsToTest = listOf(
            "apple",
            "banana",
            "milk",
            "rice",
            "wheat",
            "paneer",
            "lentils",
            "almonds",
            "egg",
            "fish"
        )

        for (query in foodsToTest) {
            val pstmt = conn.prepareStatement("""
                SELECT f.id, f.name, f.calories, f.protein, f.carbohydrates, f.fat, c.name as category_name
                FROM foods_fts fts
                JOIN foods f ON f.id = fts.food_id
                LEFT JOIN categories c ON f.category_id = c.id
                WHERE foods_fts MATCH ? AND f.is_active = 1
                ORDER BY rank
                LIMIT 5;
            """)
            pstmt.setString(1, "$query*")
            val rs = pstmt.executeQuery()

            var count = 0
            while (rs.next()) {
                count++
                val name = rs.getString("name")
                val cal = rs.getDouble("calories")
                assertTrue("Food name must not be empty", name.isNotBlank())
                assertTrue("Calories must be >= 0", cal >= 0.0)
            }

            assertTrue("Expected at least 1 match for query '$query'", count > 0)
            pstmt.close()
        }
    }

    @Test
    fun searchViewModel_initialState_loadsCustomFoodsForEmptyQuery() = runTest(testDispatcher) {
        val custom1 = Food(
            id = 100000001L,
            uuid = "custom-1",
            name = "Homemade Paneer",
            nutrition = Nutrition(calories = 265.0, proteinGrams = 18.0, carbsGrams = 3.0, fatGrams = 20.0),
            servings = listOf(Serving(description = "30 g", gramWeight = 30.0, isDefault = true))
        )
        val custom2 = Food(
            id = 100000002L,
            uuid = "custom-2",
            name = "Soy Granules",
            nutrition = Nutrition(calories = 345.0, proteinGrams = 52.0, carbsGrams = 33.0, fatGrams = 0.5),
            servings = listOf(Serving(description = "20 g", gramWeight = 20.0, isDefault = true))
        )

        val customFoodsFlow = kotlinx.coroutines.flow.MutableStateFlow(listOf(custom1, custom2))

        val fakeRepo = object : FoodRepository {
            override suspend fun searchFoods(query: String, limit: Int) = if (query == "paneer") listOf(custom1) else emptyList()
            override suspend fun getFoodById(id: Long) = if (id == custom1.id) custom1 else custom2
            override suspend fun getFoodByBarcode(barcode: String) = null
            override suspend fun getFoodServings(foodId: Long) = emptyList<Serving>()
            override suspend fun getRecentFoods(limit: Int) = emptyList<Food>()
            override suspend fun getFavoriteFoods() = emptyList<Food>()
            override fun observeCustomFoods() = customFoodsFlow
            override suspend fun getCustomFoods() = customFoodsFlow.value
            override suspend fun saveCustomFood(food: CustomFood) = 1L
            override suspend fun deleteCustomFood(id: Long) {}
        }

        val searchFoodsUseCase = com.macrobase.app.domain.usecase.SearchFoodsUseCase(fakeRepo)
        val viewModel = com.macrobase.app.feature.search.SearchViewModel(searchFoodsUseCase, fakeRepo)

        advanceUntilIdle()

        // 1. Initially blank query -> EMPTY_QUERY_WITH_CONTENT with 2 custom foods
        val state1 = viewModel.uiState.value
        assertEquals("", state1.searchQuery)
        assertEquals(com.macrobase.app.domain.model.state.SearchContentState.EMPTY_QUERY_WITH_CONTENT, state1.contentState)
        assertEquals(2, state1.customFoods.size)
        assertEquals("Homemade Paneer", state1.customFoods[0].name)
        assertEquals("Soy Granules", state1.customFoods[1].name)

        // 2. Type "paneer" -> SEARCH_RESULTS with matching item
        viewModel.onQueryChange("paneer")
        advanceUntilIdle()

        val state2 = viewModel.uiState.value
        assertEquals("paneer", state2.searchQuery)
        assertEquals(com.macrobase.app.domain.model.state.SearchContentState.SEARCH_RESULTS, state2.contentState)
        assertEquals(1, state2.searchResults.size)
        assertEquals("Homemade Paneer", state2.searchResults[0].name)

        // 3. Clear query -> Returns to EMPTY_QUERY_WITH_CONTENT with custom foods restored
        viewModel.onClearQuery()
        advanceUntilIdle()

        val state3 = viewModel.uiState.value
        assertEquals("", state3.searchQuery)
        assertEquals(com.macrobase.app.domain.model.state.SearchContentState.EMPTY_QUERY_WITH_CONTENT, state3.contentState)
        assertEquals(2, state3.customFoods.size)
        assertTrue(state3.searchResults.isEmpty())

        // 4. Type non-matching query "xyznonexistent" -> NO_SEARCH_RESULTS
        viewModel.onQueryChange("xyznonexistent")
        advanceUntilIdle()

        val state4 = viewModel.uiState.value
        assertEquals(com.macrobase.app.domain.model.state.SearchContentState.NO_SEARCH_RESULTS, state4.contentState)
        assertTrue(state4.searchResults.isEmpty())
    }

    @Test
    fun searchViewModel_whenNoCustomFoods_contentStateIsStillEmptyQueryWithContent() = runTest(testDispatcher) {
        val customFoodsFlow = kotlinx.coroutines.flow.MutableStateFlow<List<Food>>(emptyList())

        val fakeRepo = object : FoodRepository {
            override suspend fun searchFoods(query: String, limit: Int) = emptyList<Food>()
            override suspend fun getFoodById(id: Long) = null
            override suspend fun getFoodByBarcode(barcode: String) = null
            override suspend fun getFoodServings(foodId: Long) = emptyList<Serving>()
            override suspend fun getRecentFoods(limit: Int) = emptyList<Food>()
            override suspend fun getFavoriteFoods() = emptyList<Food>()
            override fun observeCustomFoods() = customFoodsFlow
            override suspend fun getCustomFoods() = emptyList<Food>()
            override suspend fun saveCustomFood(food: CustomFood) = 1L
            override suspend fun deleteCustomFood(id: Long) {}
        }

        val searchFoodsUseCase = com.macrobase.app.domain.usecase.SearchFoodsUseCase(fakeRepo)
        val viewModel = com.macrobase.app.feature.search.SearchViewModel(searchFoodsUseCase, fakeRepo)

        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals("", state.searchQuery)
        assertEquals(com.macrobase.app.domain.model.state.SearchContentState.EMPTY_QUERY_WITH_CONTENT, state.contentState)
        assertTrue(state.customFoods.isEmpty())
    }

    @Test
    fun editExistingLoggedFood_paneer30g_initializesFromSnapshotAndUpdatesCorrectly() = runTest(testDispatcher) {
        val paneerFood = Food(
            id = 201L,
            uuid = "food-paneer",
            name = "Paneer",
            nutrition = Nutrition(calories = 265.0, proteinGrams = 18.0, carbsGrams = 3.0, fatGrams = 20.0), // per 100g -> 2.65 kcal/g
            servings = listOf(
                Serving(id = 1, description = "1.0 g", unit = ServingUnit.GRAMS, quantity = 1.0, gramWeight = 1.0, isDefault = true),
                Serving(id = 2, description = "100 g", unit = ServingUnit.GRAMS, quantity = 1.0, gramWeight = 100.0, isDefault = false)
            )
        )

        // Historical logged entry: 30g Paneer in Breakfast
        val loggedServing = Serving(id = 1, description = "1.0 g", unit = ServingUnit.GRAMS, quantity = 1.0, gramWeight = 1.0)
        val initialNutrition = Nutrition(calories = 79.5, proteinGrams = 5.4, carbsGrams = 0.9, fatGrams = 6.0)
        val entryId = 501L
        var storedEntry: com.macrobase.app.domain.model.DiaryEntry? = com.macrobase.app.domain.model.DiaryEntry(
            id = entryId,
            uuid = "entry-501",
            food = paneerFood,
            mealType = com.macrobase.app.domain.model.MealType.BREAKFAST,
            date = java.time.LocalDate.of(2026, 8, 23),
            serving = loggedServing,
            quantity = 30.0,
            calculatedNutrition = initialNutrition
        )

        val fakeFoodRepo = object : FoodRepository {
            override suspend fun searchFoods(query: String, limit: Int) = listOf(paneerFood)
            override suspend fun getFoodById(id: Long) = paneerFood
            override suspend fun getFoodByBarcode(barcode: String) = null
            override suspend fun getFoodServings(foodId: Long) = paneerFood.servings
            override fun observeCustomFoods() = kotlinx.coroutines.flow.flowOf(emptyList<Food>())
            override suspend fun getCustomFoods() = emptyList<Food>()
            override suspend fun getRecentFoods(limit: Int) = emptyList<Food>()
            override suspend fun getFavoriteFoods() = emptyList<Food>()
            override suspend fun saveCustomFood(food: CustomFood) = 1L
            override suspend fun deleteCustomFood(id: Long) {}
        }

        val fakeDiaryRepo = object : com.macrobase.app.domain.repository.DiaryRepository {
            override suspend fun getDiaryForDate(date: java.time.LocalDate) = com.macrobase.app.domain.model.DailyNutritionSummary(date = date)
            override fun observeDiaryForDate(date: java.time.LocalDate) = kotlinx.coroutines.flow.flowOf(com.macrobase.app.domain.model.DailyNutritionSummary(date = date))
            override suspend fun getEntryById(entryId: Long): com.macrobase.app.domain.model.DiaryEntry? = storedEntry
            override suspend fun addEntry(entry: com.macrobase.app.domain.model.DiaryEntry): Long {
                storedEntry = entry
                return entry.id
            }
            override suspend fun addEntries(entries: List<com.macrobase.app.domain.model.DiaryEntry>) {}
            override suspend fun updateEntry(entry: com.macrobase.app.domain.model.DiaryEntry) {
                storedEntry = entry
            }
            override suspend fun deleteEntry(entryId: Long) {
                storedEntry = null
            }
            override suspend fun getMonthlyAdherence(year: Int, month: Int) = emptyList<com.macrobase.app.domain.model.CalendarDaySummary>()
            override fun observeMonthlyAdherence(year: Int, month: Int) = kotlinx.coroutines.flow.flowOf(emptyList<com.macrobase.app.domain.model.CalendarDaySummary>())
            override fun observeNutritionAggregations(startDate: java.time.LocalDate, endDate: java.time.LocalDate) = kotlinx.coroutines.flow.flowOf(emptyList<com.macrobase.app.domain.repository.DailyMacroAggregation>())
            override suspend fun getNutritionAggregations(startDate: java.time.LocalDate, endDate: java.time.LocalDate) = emptyList<com.macrobase.app.domain.repository.DailyMacroAggregation>()
        }

        val fakeBasketRepo = com.macrobase.app.data.repository.basket.InMemoryBasketRepository()
        val viewModel = FoodDetailViewModel(
            GetFoodDetailsUseCase(fakeFoodRepo),
            calculateNutritionUseCase,
            com.macrobase.app.domain.usecase.basket.AddFoodToBasketUseCase(fakeBasketRepo),
            com.macrobase.app.domain.usecase.basket.GetBasketItemsUseCase(fakeBasketRepo),
            com.macrobase.app.domain.usecase.basket.UpdateBasketItemUseCase(fakeBasketRepo),
            com.macrobase.app.domain.usecase.basket.CommitSingleBasketItemUseCase(fakeBasketRepo, fakeDiaryRepo),
            com.macrobase.app.domain.usecase.UpdateDiaryEntryUseCase(fakeDiaryRepo),
            com.macrobase.app.domain.usecase.DeleteDiaryEntryUseCase(fakeDiaryRepo),
            com.macrobase.app.domain.usecase.GetDiaryEntryUseCase(fakeDiaryRepo)
        )

        // 1. Open editor for existing logged entry
        viewModel.loadFood(foodId = 201L, entryId = entryId)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals("Paneer", state.food?.name)
        assertEquals(30.0, state.enteredQuantity, 0.001)
        assertEquals("30", state.quantityInputText)
        assertEquals("1.0 g", state.selectedServing?.description)
        assertEquals(79.5, state.calculatedNutrition.calories, 0.1)
        assertTrue(viewModel.isEditingDiary())

        // 2. Change quantity from 30 to 35
        viewModel.onQuantityChange("35")
        val stateUpdated = viewModel.uiState.value
        assertEquals(35.0, stateUpdated.enteredQuantity, 0.001)
        assertEquals("35", stateUpdated.quantityInputText)
        // 35 * 2.65 = 92.75 kcal
        assertEquals(92.75, stateUpdated.calculatedNutrition.calories, 0.1)

        // 3. Save entry
        var savedCallbackCalled = false
        viewModel.logFood { savedCallbackCalled = true }
        advanceUntilIdle()

        assertTrue(savedCallbackCalled)
        assertEquals(entryId, storedEntry?.id)
        assertEquals(35.0, storedEntry?.quantity ?: 0.0, 0.001)
        assertEquals(92.75, storedEntry?.calculatedNutrition?.calories ?: 0.0, 0.1)
    }

    @Test
    fun editExistingLoggedFood_bread4slices_initializesServingAndQuantityCorrectly() = runTest(testDispatcher) {
        val breadFood = Food(
            id = 202L,
            uuid = "food-bread",
            name = "Brown Bread",
            nutrition = Nutrition(calories = 264.0, proteinGrams = 12.0, carbsGrams = 48.0, fatGrams = 2.4), // per 100g
            servings = listOf(
                Serving(id = 1, description = "1 slice (25g)", unit = ServingUnit.SLICE, quantity = 1.0, gramWeight = 25.0, isDefault = true),
                Serving(id = 2, description = "100 g", unit = ServingUnit.GRAMS, quantity = 1.0, gramWeight = 100.0, isDefault = false)
            )
        )

        // Historical logged entry: 4 slices of Brown Bread (4 * 25g = 100g -> 264 kcal)
        val sliceServing = Serving(id = 1, description = "1 slice (25g)", unit = ServingUnit.SLICE, quantity = 1.0, gramWeight = 25.0)
        val initialNutrition = Nutrition(calories = 264.0, proteinGrams = 12.0, carbsGrams = 48.0, fatGrams = 2.4)
        val entryId = 502L
        val storedEntry = com.macrobase.app.domain.model.DiaryEntry(
            id = entryId,
            uuid = "entry-502",
            food = breadFood,
            mealType = com.macrobase.app.domain.model.MealType.BREAKFAST,
            date = java.time.LocalDate.of(2026, 8, 23),
            serving = sliceServing,
            quantity = 4.0,
            calculatedNutrition = initialNutrition
        )

        val fakeFoodRepo = object : FoodRepository {
            override suspend fun searchFoods(query: String, limit: Int) = listOf(breadFood)
            override suspend fun getFoodById(id: Long) = breadFood
            override suspend fun getFoodByBarcode(barcode: String) = null
            override suspend fun getFoodServings(foodId: Long) = breadFood.servings
            override fun observeCustomFoods() = kotlinx.coroutines.flow.flowOf(emptyList<Food>())
            override suspend fun getCustomFoods() = emptyList<Food>()
            override suspend fun getRecentFoods(limit: Int) = emptyList<Food>()
            override suspend fun getFavoriteFoods() = emptyList<Food>()
            override suspend fun saveCustomFood(food: CustomFood) = 1L
            override suspend fun deleteCustomFood(id: Long) {}
        }

        val fakeDiaryRepo = object : com.macrobase.app.domain.repository.DiaryRepository {
            override suspend fun getDiaryForDate(date: java.time.LocalDate) = com.macrobase.app.domain.model.DailyNutritionSummary(date = date)
            override fun observeDiaryForDate(date: java.time.LocalDate) = kotlinx.coroutines.flow.flowOf(com.macrobase.app.domain.model.DailyNutritionSummary(date = date))
            override suspend fun getEntryById(entryId: Long) = storedEntry
            override suspend fun addEntry(entry: com.macrobase.app.domain.model.DiaryEntry) = entry.id
            override suspend fun addEntries(entries: List<com.macrobase.app.domain.model.DiaryEntry>) {}
            override suspend fun updateEntry(entry: com.macrobase.app.domain.model.DiaryEntry) {}
            override suspend fun deleteEntry(entryId: Long) {}
            override suspend fun getMonthlyAdherence(year: Int, month: Int) = emptyList<com.macrobase.app.domain.model.CalendarDaySummary>()
            override fun observeMonthlyAdherence(year: Int, month: Int) = kotlinx.coroutines.flow.flowOf(emptyList<com.macrobase.app.domain.model.CalendarDaySummary>())
            override fun observeNutritionAggregations(startDate: java.time.LocalDate, endDate: java.time.LocalDate) = kotlinx.coroutines.flow.flowOf(emptyList<com.macrobase.app.domain.repository.DailyMacroAggregation>())
            override suspend fun getNutritionAggregations(startDate: java.time.LocalDate, endDate: java.time.LocalDate) = emptyList<com.macrobase.app.domain.repository.DailyMacroAggregation>()
        }

        val fakeBasketRepo = com.macrobase.app.data.repository.basket.InMemoryBasketRepository()
        val viewModel = FoodDetailViewModel(
            GetFoodDetailsUseCase(fakeFoodRepo),
            calculateNutritionUseCase,
            com.macrobase.app.domain.usecase.basket.AddFoodToBasketUseCase(fakeBasketRepo),
            com.macrobase.app.domain.usecase.basket.GetBasketItemsUseCase(fakeBasketRepo),
            com.macrobase.app.domain.usecase.basket.UpdateBasketItemUseCase(fakeBasketRepo),
            com.macrobase.app.domain.usecase.basket.CommitSingleBasketItemUseCase(fakeBasketRepo, fakeDiaryRepo),
            com.macrobase.app.domain.usecase.UpdateDiaryEntryUseCase(fakeDiaryRepo),
            com.macrobase.app.domain.usecase.DeleteDiaryEntryUseCase(fakeDiaryRepo),
            com.macrobase.app.domain.usecase.GetDiaryEntryUseCase(fakeDiaryRepo)
        )

        viewModel.loadFood(foodId = 202L, entryId = entryId)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals("Brown Bread", state.food?.name)
        assertEquals(4.0, state.enteredQuantity, 0.001)
        assertEquals("4", state.quantityInputText)
        assertEquals("1 slice (25g)", state.selectedServing?.description)
        assertEquals(264.0, state.calculatedNutrition.calories, 0.1)
    }

    @Test
    fun editHistoricalFood_whenDatabaseServingChanged_preservesHistoricalSnapshot() = runTest(testDispatcher) {
        val historicalServing = Serving(id = 999, description = "1 custom bowl (75g)", unit = ServingUnit.CUSTOM, quantity = 1.0, gramWeight = 75.0)
        val historicalNutrition = Nutrition(calories = 200.0, proteinGrams = 10.0, carbsGrams = 30.0, fatGrams = 4.0)
        val entryId = 503L
        val storedEntry = com.macrobase.app.domain.model.DiaryEntry(
            id = entryId,
            uuid = "entry-503",
            food = Food(id = 203L, uuid = "f-203", name = "Old Recipe", nutrition = historicalNutrition),
            mealType = com.macrobase.app.domain.model.MealType.LUNCH,
            date = java.time.LocalDate.of(2026, 8, 20),
            serving = historicalServing,
            quantity = 2.0,
            calculatedNutrition = historicalNutrition
        )

        // Database no longer contains this food
        val fakeFoodRepo = object : FoodRepository {
            override suspend fun searchFoods(query: String, limit: Int) = emptyList<Food>()
            override suspend fun getFoodById(id: Long) = null
            override suspend fun getFoodByBarcode(barcode: String) = null
            override suspend fun getFoodServings(foodId: Long) = emptyList<Serving>()
            override fun observeCustomFoods() = kotlinx.coroutines.flow.flowOf(emptyList<Food>())
            override suspend fun getCustomFoods() = emptyList<Food>()
            override suspend fun getRecentFoods(limit: Int) = emptyList<Food>()
            override suspend fun getFavoriteFoods() = emptyList<Food>()
            override suspend fun saveCustomFood(food: CustomFood) = 1L
            override suspend fun deleteCustomFood(id: Long) {}
        }

        val fakeDiaryRepo = object : com.macrobase.app.domain.repository.DiaryRepository {
            override suspend fun getDiaryForDate(date: java.time.LocalDate) = com.macrobase.app.domain.model.DailyNutritionSummary(date = date)
            override fun observeDiaryForDate(date: java.time.LocalDate) = kotlinx.coroutines.flow.flowOf(com.macrobase.app.domain.model.DailyNutritionSummary(date = date))
            override suspend fun getEntryById(entryId: Long) = storedEntry
            override suspend fun addEntry(entry: com.macrobase.app.domain.model.DiaryEntry) = entry.id
            override suspend fun addEntries(entries: List<com.macrobase.app.domain.model.DiaryEntry>) {}
            override suspend fun updateEntry(entry: com.macrobase.app.domain.model.DiaryEntry) {}
            override suspend fun deleteEntry(entryId: Long) {}
            override suspend fun getMonthlyAdherence(year: Int, month: Int) = emptyList<com.macrobase.app.domain.model.CalendarDaySummary>()
            override fun observeMonthlyAdherence(year: Int, month: Int) = kotlinx.coroutines.flow.flowOf(emptyList<com.macrobase.app.domain.model.CalendarDaySummary>())
            override fun observeNutritionAggregations(startDate: java.time.LocalDate, endDate: java.time.LocalDate) = kotlinx.coroutines.flow.flowOf(emptyList<com.macrobase.app.domain.repository.DailyMacroAggregation>())
            override suspend fun getNutritionAggregations(startDate: java.time.LocalDate, endDate: java.time.LocalDate) = emptyList<com.macrobase.app.domain.repository.DailyMacroAggregation>()
        }

        val fakeBasketRepo = com.macrobase.app.data.repository.basket.InMemoryBasketRepository()
        val viewModel = FoodDetailViewModel(
            GetFoodDetailsUseCase(fakeFoodRepo),
            calculateNutritionUseCase,
            com.macrobase.app.domain.usecase.basket.AddFoodToBasketUseCase(fakeBasketRepo),
            com.macrobase.app.domain.usecase.basket.GetBasketItemsUseCase(fakeBasketRepo),
            com.macrobase.app.domain.usecase.basket.UpdateBasketItemUseCase(fakeBasketRepo),
            com.macrobase.app.domain.usecase.basket.CommitSingleBasketItemUseCase(fakeBasketRepo, fakeDiaryRepo),
            com.macrobase.app.domain.usecase.UpdateDiaryEntryUseCase(fakeDiaryRepo),
            com.macrobase.app.domain.usecase.DeleteDiaryEntryUseCase(fakeDiaryRepo),
            com.macrobase.app.domain.usecase.GetDiaryEntryUseCase(fakeDiaryRepo)
        )

        viewModel.loadFood(foodId = 203L, entryId = entryId)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals("Old Recipe", state.food?.name)
        assertEquals(2.0, state.enteredQuantity, 0.001)
        assertEquals("2", state.quantityInputText)
        assertEquals("1 custom bowl (75g)", state.selectedServing?.description)
        assertEquals(200.0, state.calculatedNutrition.calories, 0.1)
    }

    @Test
    fun swipeReveal_doesNotDelete_andExplicitDeleteRemovesOnlyTargetEntry() = runTest(testDispatcher) {
        val breadNutrition = Nutrition(calories = 130.0, proteinGrams = 4.0, carbsGrams = 24.0, fatGrams = 1.0)
        val paneerNutrition = Nutrition(calories = 79.5, proteinGrams = 5.4, carbsGrams = 0.9, fatGrams = 6.0)

        val entry1 = com.macrobase.app.domain.model.DiaryEntry(
            id = 101L,
            uuid = "uuid-1",
            food = Food(id = 1L, uuid = "f-1", name = "Bread", nutrition = breadNutrition),
            mealType = com.macrobase.app.domain.model.MealType.BREAKFAST,
            date = java.time.LocalDate.of(2026, 8, 23),
            serving = Serving(description = "1 slice", gramWeight = 25.0),
            quantity = 2.0,
            calculatedNutrition = breadNutrition
        )
        val entry2 = com.macrobase.app.domain.model.DiaryEntry(
            id = 102L,
            uuid = "uuid-2",
            food = Food(id = 2L, uuid = "f-2", name = "Paneer", nutrition = paneerNutrition),
            mealType = com.macrobase.app.domain.model.MealType.BREAKFAST,
            date = java.time.LocalDate.of(2026, 8, 23),
            serving = Serving(description = "1.0 g", gramWeight = 1.0),
            quantity = 30.0,
            calculatedNutrition = paneerNutrition
        )

        val entries = mutableListOf(entry1, entry2)
        val fakeDiaryRepo = object : com.macrobase.app.domain.repository.DiaryRepository {
            override suspend fun getDiaryForDate(date: java.time.LocalDate) = com.macrobase.app.domain.model.DailyNutritionSummary(date = date)
            override fun observeDiaryForDate(date: java.time.LocalDate) = kotlinx.coroutines.flow.flowOf(com.macrobase.app.domain.model.DailyNutritionSummary(date = date))
            override suspend fun getEntryById(entryId: Long) = entries.firstOrNull { it.id == entryId }
            override suspend fun addEntry(entry: com.macrobase.app.domain.model.DiaryEntry) = entry.id
            override suspend fun addEntries(entriesList: List<com.macrobase.app.domain.model.DiaryEntry>) {}
            override suspend fun updateEntry(entry: com.macrobase.app.domain.model.DiaryEntry) {}
            override suspend fun deleteEntry(entryId: Long) {
                entries.removeAll { it.id == entryId }
            }
            override suspend fun getMonthlyAdherence(year: Int, month: Int) = emptyList<com.macrobase.app.domain.model.CalendarDaySummary>()
            override fun observeMonthlyAdherence(year: Int, month: Int) = kotlinx.coroutines.flow.flowOf(emptyList<com.macrobase.app.domain.model.CalendarDaySummary>())
            override fun observeNutritionAggregations(startDate: java.time.LocalDate, endDate: java.time.LocalDate) = kotlinx.coroutines.flow.flowOf(emptyList<com.macrobase.app.domain.repository.DailyMacroAggregation>())
            override suspend fun getNutritionAggregations(startDate: java.time.LocalDate, endDate: java.time.LocalDate) = emptyList<com.macrobase.app.domain.repository.DailyMacroAggregation>()
        }

        val fakeBasketRepo = com.macrobase.app.data.repository.basket.InMemoryBasketRepository()
        val addFoodToBasketUseCase = com.macrobase.app.domain.usecase.basket.AddFoodToBasketUseCase(fakeBasketRepo)
        val homeViewModel = com.macrobase.app.feature.dashboard.HomeViewModel(
            getDailyDiaryUseCase = com.macrobase.app.domain.usecase.GetDailyDiaryUseCase(fakeDiaryRepo),
            deleteDiaryEntryUseCase = com.macrobase.app.domain.usecase.DeleteDiaryEntryUseCase(fakeDiaryRepo),
            addFoodToBasketUseCase = addFoodToBasketUseCase
        )

        // 1. Swipe reveal Bread (entry1)
        homeViewModel.setRevealedEntry(101L)
        assertEquals(101L, homeViewModel.revealedEntryId.value)
        // Verify Bread was NOT deleted by swipe
        assertEquals(2, entries.size)

        // 2. Swipe another row (Paneer - entry2) -> closes Bread and reveals Paneer
        homeViewModel.setRevealedEntry(102L)
        assertEquals(102L, homeViewModel.revealedEntryId.value)
        assertEquals(2, entries.size)

        // 3. Explicit Delete Paneer (entry2)
        homeViewModel.deleteEntry(102L)
        advanceUntilIdle()

        // Verify Paneer is deleted, Bread remains intact, and revealed state resets
        assertNull(homeViewModel.revealedEntryId.value)
        assertEquals(1, entries.size)
        assertEquals(101L, entries.first().id)
        assertEquals("Bread", entries.first().food.name)
    }

    @Test
    fun copyEntryFromDashboard_createsBasketItem_doesNotModifyDiaryImmediately() = runTest(testDispatcher) {
        val paneerBaseNutrition = Nutrition(calories = 265.0, proteinGrams = 18.0, carbsGrams = 3.0, fatGrams = 20.0)
        val entry = com.macrobase.app.domain.model.DiaryEntry(
            id = 201L,
            uuid = "uuid-paneer",
            food = Food(id = 50L, uuid = "f-50", name = "Paneer", nutrition = paneerBaseNutrition),
            mealType = com.macrobase.app.domain.model.MealType.LUNCH,
            date = java.time.LocalDate.of(2026, 8, 23),
            serving = Serving(description = "1.0 g", gramWeight = 1.0),
            quantity = 30.0,
            calculatedNutrition = Nutrition(calories = 79.5, proteinGrams = 5.4, carbsGrams = 0.9, fatGrams = 6.0)
        )

        var diaryEntryCount = 1
        val fakeDiaryRepo = object : com.macrobase.app.domain.repository.DiaryRepository {
            override suspend fun getDiaryForDate(date: java.time.LocalDate) = com.macrobase.app.domain.model.DailyNutritionSummary(date = date)
            override fun observeDiaryForDate(date: java.time.LocalDate) = kotlinx.coroutines.flow.flowOf(com.macrobase.app.domain.model.DailyNutritionSummary(date = date))
            override suspend fun getEntryById(entryId: Long) = entry
            override suspend fun addEntry(entry: com.macrobase.app.domain.model.DiaryEntry): Long {
                diaryEntryCount++
                return entry.id
            }
            override suspend fun addEntries(entriesList: List<com.macrobase.app.domain.model.DiaryEntry>) {
                diaryEntryCount += entriesList.size
            }
            override suspend fun updateEntry(entry: com.macrobase.app.domain.model.DiaryEntry) {}
            override suspend fun deleteEntry(entryId: Long) {}
            override suspend fun getMonthlyAdherence(year: Int, month: Int) = emptyList<com.macrobase.app.domain.model.CalendarDaySummary>()
            override fun observeMonthlyAdherence(year: Int, month: Int) = kotlinx.coroutines.flow.flowOf(emptyList<com.macrobase.app.domain.model.CalendarDaySummary>())
            override fun observeNutritionAggregations(startDate: java.time.LocalDate, endDate: java.time.LocalDate) = kotlinx.coroutines.flow.flowOf(emptyList<com.macrobase.app.domain.repository.DailyMacroAggregation>())
            override suspend fun getNutritionAggregations(startDate: java.time.LocalDate, endDate: java.time.LocalDate) = emptyList<com.macrobase.app.domain.repository.DailyMacroAggregation>()
        }

        val fakeBasketRepo = com.macrobase.app.data.repository.basket.InMemoryBasketRepository()
        val addFoodToBasketUseCase = com.macrobase.app.domain.usecase.basket.AddFoodToBasketUseCase(fakeBasketRepo)
        val homeViewModel = com.macrobase.app.feature.dashboard.HomeViewModel(
            getDailyDiaryUseCase = com.macrobase.app.domain.usecase.GetDailyDiaryUseCase(fakeDiaryRepo),
            deleteDiaryEntryUseCase = com.macrobase.app.domain.usecase.DeleteDiaryEntryUseCase(fakeDiaryRepo),
            addFoodToBasketUseCase = addFoodToBasketUseCase
        )

        // Copy action from swipe
        homeViewModel.copyEntry(entry)
        advanceUntilIdle()

        // 1. Diary entries count did NOT increase immediately
        assertEquals(1, diaryEntryCount)

        // 2. Exactly one BasketItem was created with matching snapshot
        val basketItems = fakeBasketRepo.items.value
        assertEquals(1, basketItems.size)
        val item = basketItems.first()
        assertEquals("Paneer", item.foodNameSnapshot)
        assertEquals(30.0, item.quantity, 0.001)
        assertEquals("1.0 g", item.serving.description)
        assertEquals(com.macrobase.app.domain.model.MealType.LUNCH, item.mealType)
        assertEquals(java.time.LocalDate.of(2026, 8, 23), item.date)
        assertEquals(79.5, item.calculatedNutrition.calories, 0.1)
    }

    @Test
    fun copyEntryFromEditScreen_allowsEditsAndCommitsIndependentNewEntry() = runTest(testDispatcher) {
        val originalEntry = com.macrobase.app.domain.model.DiaryEntry(
            id = 301L,
            uuid = "uuid-original",
            food = Food(
                id = 80L,
                uuid = "f-80",
                name = "Paneer",
                nutrition = Nutrition(calories = 265.0, proteinGrams = 18.0, carbsGrams = 3.0, fatGrams = 20.0),
                servings = listOf(Serving(description = "1.0 g", gramWeight = 1.0, isDefault = true))
            ),
            mealType = com.macrobase.app.domain.model.MealType.BREAKFAST,
            date = java.time.LocalDate.of(2026, 8, 23),
            serving = Serving(description = "1.0 g", gramWeight = 1.0),
            quantity = 30.0,
            calculatedNutrition = Nutrition(calories = 79.5, proteinGrams = 5.4, carbsGrams = 0.9, fatGrams = 6.0)
        )

        val storedEntries = mutableListOf(originalEntry)
        val fakeDiaryRepo = object : com.macrobase.app.domain.repository.DiaryRepository {
            override suspend fun getDiaryForDate(date: java.time.LocalDate) = com.macrobase.app.domain.model.DailyNutritionSummary(date = date)
            override fun observeDiaryForDate(date: java.time.LocalDate) = kotlinx.coroutines.flow.flowOf(com.macrobase.app.domain.model.DailyNutritionSummary(date = date))
            override suspend fun getEntryById(entryId: Long) = storedEntries.firstOrNull { it.id == entryId }
            override suspend fun addEntry(entry: com.macrobase.app.domain.model.DiaryEntry): Long {
                val newId = (storedEntries.maxOfOrNull { it.id } ?: 0L) + 1L
                val persisted = entry.copy(id = newId)
                storedEntries.add(persisted)
                return newId
            }
            override suspend fun addEntries(entriesList: List<com.macrobase.app.domain.model.DiaryEntry>) {
                entriesList.forEach { addEntry(it) }
            }
            override suspend fun updateEntry(entry: com.macrobase.app.domain.model.DiaryEntry) {
                val index = storedEntries.indexOfFirst { it.id == entry.id }
                if (index >= 0) storedEntries[index] = entry
            }
            override suspend fun deleteEntry(entryId: Long) {
                storedEntries.removeAll { it.id == entryId }
            }
            override suspend fun getMonthlyAdherence(year: Int, month: Int) = emptyList<com.macrobase.app.domain.model.CalendarDaySummary>()
            override fun observeMonthlyAdherence(year: Int, month: Int) = kotlinx.coroutines.flow.flowOf(emptyList<com.macrobase.app.domain.model.CalendarDaySummary>())
            override fun observeNutritionAggregations(startDate: java.time.LocalDate, endDate: java.time.LocalDate) = kotlinx.coroutines.flow.flowOf(emptyList<com.macrobase.app.domain.repository.DailyMacroAggregation>())
            override suspend fun getNutritionAggregations(startDate: java.time.LocalDate, endDate: java.time.LocalDate) = emptyList<com.macrobase.app.domain.repository.DailyMacroAggregation>()
        }

        val fakeFoodRepo = object : FoodRepository {
            override suspend fun searchFoods(query: String, limit: Int) = listOf(originalEntry.food)
            override suspend fun getFoodById(id: Long) = originalEntry.food
            override suspend fun getFoodByBarcode(barcode: String) = null
            override suspend fun getFoodServings(foodId: Long) = originalEntry.food.servings
            override fun observeCustomFoods() = kotlinx.coroutines.flow.flowOf(emptyList<Food>())
            override suspend fun getCustomFoods() = emptyList<Food>()
            override suspend fun getRecentFoods(limit: Int) = emptyList<Food>()
            override suspend fun getFavoriteFoods() = emptyList<Food>()
            override suspend fun saveCustomFood(food: CustomFood) = 1L
            override suspend fun deleteCustomFood(id: Long) {}
        }

        val fakeBasketRepo = com.macrobase.app.data.repository.basket.InMemoryBasketRepository()
        val viewModel = FoodDetailViewModel(
            GetFoodDetailsUseCase(fakeFoodRepo),
            calculateNutritionUseCase,
            com.macrobase.app.domain.usecase.basket.AddFoodToBasketUseCase(fakeBasketRepo),
            com.macrobase.app.domain.usecase.basket.GetBasketItemsUseCase(fakeBasketRepo),
            com.macrobase.app.domain.usecase.basket.UpdateBasketItemUseCase(fakeBasketRepo),
            com.macrobase.app.domain.usecase.basket.CommitSingleBasketItemUseCase(fakeBasketRepo, fakeDiaryRepo),
            com.macrobase.app.domain.usecase.UpdateDiaryEntryUseCase(fakeDiaryRepo),
            com.macrobase.app.domain.usecase.DeleteDiaryEntryUseCase(fakeDiaryRepo),
            com.macrobase.app.domain.usecase.GetDiaryEntryUseCase(fakeDiaryRepo)
        )

        // 1. Open editor for existing diary entry 301L
        viewModel.loadFood(foodId = 80L, entryId = 301L)
        advanceUntilIdle()
        assertTrue(viewModel.isEditingDiary())

        // 2. User taps Copy
        var copiedBasketItemId: String? = null
        viewModel.copyEntry { _, _, _, basketItemId ->
            copiedBasketItemId = basketItemId
        }
        advanceUntilIdle()

        assertNotNull(copiedBasketItemId)
        assertEquals(1, storedEntries.size) // No new diary entry yet

        // 3. Screen reloads into BasketItem edit mode
        viewModel.loadFood(foodId = 80L, entryId = 0L, basketItemId = copiedBasketItemId)
        advanceUntilIdle()
        assertFalse(viewModel.isEditingDiary())
        assertTrue(viewModel.isEditingBasket())

        // 4. User changes quantity to 35 and meal to DINNER
        viewModel.onQuantityChange("35")
        viewModel.onMealTypeSelected(com.macrobase.app.domain.model.MealType.DINNER)
        advanceUntilIdle()

        // 5. User taps "Log Food"
        var loggedSuccess = false
        viewModel.logFood { loggedSuccess = true }
        advanceUntilIdle()

        assertTrue(loggedSuccess)
        // Verify two independent entries now exist in Diary
        assertEquals(2, storedEntries.size)

        // Original entry is unchanged
        val originalAfter = storedEntries.first { it.id == 301L }
        assertEquals(30.0, originalAfter.quantity, 0.001)
        assertEquals(com.macrobase.app.domain.model.MealType.BREAKFAST, originalAfter.mealType)

        // New copied entry has modified values
        val newEntry = storedEntries.first { it.id != 301L }
        assertEquals(35.0, newEntry.quantity, 0.001)
        assertEquals(com.macrobase.app.domain.model.MealType.DINNER, newEntry.mealType)
    }
}
