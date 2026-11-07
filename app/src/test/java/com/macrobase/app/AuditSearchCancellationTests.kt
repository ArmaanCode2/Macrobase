package com.macrobase.app

import com.macrobase.app.core.config.DatabaseConfig
import com.macrobase.app.domain.model.CustomFood
import com.macrobase.app.domain.model.Food
import com.macrobase.app.domain.model.Serving
import com.macrobase.app.domain.model.state.SearchContentState
import com.macrobase.app.domain.repository.FoodRepository
import com.macrobase.app.domain.usecase.SearchFoodsUseCase
import com.macrobase.app.feature.search.SearchViewModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * BUG-024: a keystroke cancels the running query, and the ViewModel took that cancellation for a
 * failed search, so typing at a normal speed flashed "Search Error" with the coroutine's own text.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SearchCancellationTests {

    private val dispatcher = StandardTestDispatcher()

    /** Holds "ri" for good, as a query still running on a slow phone, and answers anything else. */
    private open class FakeFoods : FoodRepository {
        override suspend fun searchFoods(query: String, limit: Int): List<Food> {
            if (query == "ri") CompletableDeferred<Unit>().await()
            return emptyList()
        }
        override suspend fun getFoodById(id: Long): Food? = null
        override suspend fun getFoodByBarcode(barcode: String): Food? = null
        override suspend fun getFoodServings(foodId: Long) = emptyList<Serving>()
        override suspend fun getRecentFoods(limit: Int) = emptyList<Food>()
        override suspend fun getFavoriteFoods() = emptyList<Food>()
        override fun observeCustomFoods() = flowOf(emptyList<Food>())
        override suspend fun getCustomFoods() = emptyList<Food>()
        override suspend fun saveCustomFood(food: CustomFood) = 0L
        override suspend fun deleteCustomFood(id: Long) {}
    }

    private fun search(foods: FoodRepository) = SearchViewModel(SearchFoodsUseCase(foods), foods)

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun aNewerKeystrokeNeverShowsAnError() = runTest(dispatcher) {
        val vm = search(FakeFoods())
        val seen = mutableListOf<SearchContentState>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect { seen += it.contentState } }
        advanceUntilIdle()

        vm.onQueryChange("ri")
        advanceTimeBy(DatabaseConfig.SEARCH_DEBOUNCE_MILLIS + 1) // The query is running
        vm.onQueryChange("ric")
        advanceUntilIdle()

        assertFalse("seen: $seen", seen.contains(SearchContentState.ERROR))
        assertNull(vm.uiState.value.errorMessage)
        assertEquals("ric", vm.uiState.value.searchQuery)
    }

    @Test
    fun aFailedSearchShowsAFixedMessageNotTheException() = runTest(dispatcher) {
        val vm = search(object : FakeFoods() {
            override suspend fun searchFoods(query: String, limit: Int): List<Food> =
                throw IllegalStateException("no such column: normalized_name (code 1 SQLITE_ERROR)")
        })
        advanceUntilIdle()

        vm.onQueryChange("rice")
        advanceUntilIdle()

        assertEquals(SearchContentState.ERROR, vm.uiState.value.contentState)
        assertEquals("Failed to perform search.", vm.uiState.value.errorMessage)
    }

    // The custom and recent food loads share the ViewModel, and only stop when it is cleared
    @Test
    fun everyCatchInTheScreenLetsCancellationThrough() {
        val screen = listOf(
            File("src/main/java/com/macrobase/app/feature/search/SearchScreen.kt"),
            File("app/src/main/java/com/macrobase/app/feature/search/SearchScreen.kt")
        ).first { it.isFile }.readText()

        assertEquals(
            Regex("""catch \(\w+: Exception\)""").findAll(screen).count(),
            Regex("""catch \(e: CancellationException\) \{\s+throw e""").findAll(screen).count()
        )
    }
}
