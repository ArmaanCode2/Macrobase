package com.macrobase.app

import com.macrobase.app.domain.model.CustomFood
import com.macrobase.app.domain.model.Food
import com.macrobase.app.domain.model.FoodSource
import com.macrobase.app.domain.model.Nutrition
import com.macrobase.app.domain.model.Serving
import com.macrobase.app.domain.model.state.SearchContentState
import com.macrobase.app.domain.repository.FoodRepository
import com.macrobase.app.domain.usecase.SearchFoodsUseCase
import com.macrobase.app.feature.search.SearchViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * BUG-025: "No Foods Found", then "+ Create Custom Food" and save: Search came back to the same
 * query and still said nothing was found.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SearchRefreshTests {

    private val dispatcher = StandardTestDispatcher()

    /** Custom foods only, matched by name, as the catalog would also return them. */
    private class CustomOnly : FoodRepository {
        val custom = MutableStateFlow(emptyList<Food>())
        override suspend fun searchFoods(query: String, limit: Int) = custom.value.filter { it.name.contains(query, ignoreCase = true) }
        override suspend fun getFoodById(id: Long): Food? = null
        override suspend fun getFoodByBarcode(barcode: String): Food? = null
        override suspend fun getFoodServings(foodId: Long) = emptyList<Serving>()
        override suspend fun getRecentFoods(limit: Int) = emptyList<Food>()
        override suspend fun getFavoriteFoods() = emptyList<Food>()
        override fun observeCustomFoods() = custom
        override suspend fun getCustomFoods() = custom.value
        override suspend fun saveCustomFood(food: CustomFood) = 0L
        override suspend fun deleteCustomFood(id: Long) {}
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun aCustomFoodMadeFromNoResultsShowsInThoseResults() = runTest(dispatcher) {
        val repo = CustomOnly()
        val vm = SearchViewModel(SearchFoodsUseCase(repo), repo)
        vm.onQueryChange("xyz")
        advanceUntilIdle()
        assertEquals(SearchContentState.NO_SEARCH_RESULTS, vm.uiState.value.contentState)

        repo.custom.value = listOf(
            Food(
                id = 100_000_001L,
                uuid = "xyz",
                source = FoodSource.CUSTOM_USER,
                name = "Xyz bar",
                isUserOwned = true,
                nutrition = Nutrition(calories = 200.0, proteinGrams = 10.0, carbsGrams = 20.0, fatGrams = 8.0)
            )
        )
        advanceUntilIdle()

        assertEquals(SearchContentState.SEARCH_RESULTS, vm.uiState.value.contentState)
        assertEquals(listOf("Xyz bar"), vm.uiState.value.searchResults.map { it.name })
    }
}
