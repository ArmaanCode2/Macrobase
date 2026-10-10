package com.macrobase.app

import com.macrobase.app.data.repository.basket.BasketItemsJson
import com.macrobase.app.data.repository.basket.InMemoryBasketRepository
import com.macrobase.app.data.repository.basket.PersistentBasketRepository
import com.macrobase.app.domain.model.CustomFood
import com.macrobase.app.domain.model.DiaryEntry
import com.macrobase.app.domain.model.Food
import com.macrobase.app.domain.model.FoodSource
import com.macrobase.app.domain.model.MealType
import com.macrobase.app.domain.model.Nutrition
import com.macrobase.app.domain.model.Recipe
import com.macrobase.app.domain.model.Serving
import com.macrobase.app.domain.model.ServingUnit
import com.macrobase.app.domain.model.basket.BasketItem
import com.macrobase.app.domain.repository.DiaryRepository
import com.macrobase.app.domain.repository.FoodRepository
import com.macrobase.app.domain.repository.RecipeRepository
import com.macrobase.app.domain.repository.basket.BasketRepository
import com.macrobase.app.domain.usecase.CalculateNutritionForServingUseCase
import com.macrobase.app.domain.usecase.DeleteDiaryEntryUseCase
import com.macrobase.app.domain.usecase.GetDiaryEntryUseCase
import com.macrobase.app.domain.usecase.GetFoodDetailsUseCase
import com.macrobase.app.domain.usecase.UpdateDiaryEntryUseCase
import com.macrobase.app.domain.usecase.basket.AddFoodToBasketUseCase
import com.macrobase.app.domain.usecase.basket.BasketItemMissingException
import com.macrobase.app.domain.usecase.basket.ClearBasketUseCase
import com.macrobase.app.domain.usecase.basket.CommitBasketUseCase
import com.macrobase.app.domain.usecase.basket.CommitSingleBasketItemUseCase
import com.macrobase.app.domain.usecase.basket.GetBasketItemsUseCase
import com.macrobase.app.domain.usecase.basket.InvalidBasketQuantityException
import com.macrobase.app.domain.usecase.basket.RemoveBasketItemUseCase
import com.macrobase.app.domain.usecase.basket.UpdateAllBasketItemsUseCase
import com.macrobase.app.domain.usecase.basket.UpdateBasketItemUseCase
import com.macrobase.app.feature.basket.BasketViewModel
import com.macrobase.app.feature.detail.FoodDetailViewModel
import com.macrobase.app.feature.detail.QUANTITY_INPUT_ERROR
import com.macrobase.app.feature.detail.parsePositiveQuantity
import com.macrobase.app.feature.recipes.RecipesViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.Instant
import java.time.LocalDate

/**
 * P2 phase 1: BUG-011 (double taps log twice), BUG-012 (blank/zero/negative/comma amounts)
 * and BUG-015 (basket lost on app close; recipe "Log to Diary" never logged).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class P2PhaseOneLoggingTests {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val testDispatcher = StandardTestDispatcher()
    private val day = LocalDate.of(2026, 10, 5)

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // ---------------------------------------------------------------------------------------
    // BUG-015: the basket survives the app being closed
    // ---------------------------------------------------------------------------------------

    @Test
    fun basketSurvivesRestart_withEveryFieldOfEveryKindOfFood() = runTest(testDispatcher) {
        val file = File(tempFolder.root, "basket.json")
        val first = PersistentBasketRepository(file, backgroundScope)
        val items = listOf(catalogItem(), customItem(), snapshotItem(), recipeItem())
        items.forEach { first.addItem(it) }
        runCurrent()

        val reopened = PersistentBasketRepository(file, backgroundScope)

        assertEquals(items, reopened.items.value)
        // Missing nutrients stay missing and zeros stay zeros (AGENTS.md section 2.4)
        val restored = reopened.items.value.first().calculatedNutrition
        assertEquals(0.0, restored.fiberGrams!!, 0.0)
        assertNull(restored.sugarGrams)
    }

    @Test
    fun removedUpdatedAndClearedItemsStayThatWayAfterRestart() = runTest(testDispatcher) {
        val file = File(tempFolder.root, "basket.json")
        val first = PersistentBasketRepository(file, backgroundScope)
        val keep = catalogItem()
        val drop = customItem()
        first.addItem(keep)
        first.addItem(drop)
        runCurrent()
        first.removeItem(drop.id)
        first.updateItem(keep.copy(quantity = 3.0))
        runCurrent()

        assertEquals(listOf(keep.copy(quantity = 3.0)), PersistentBasketRepository(file, backgroundScope).items.value)

        first.clearBasket()
        runCurrent()
        assertTrue(PersistentBasketRepository(file, backgroundScope).items.value.isEmpty())
    }

    @Test
    fun changeMadeBeforeTheSaverStartsIsStillSaved() = runTest(testDispatcher) {
        val file = File(tempFolder.root, "basket.json")
        val repo = PersistentBasketRepository(file, backgroundScope)
        val item = catalogItem()
        repo.addItem(item) // the save collector has not run yet
        runCurrent()

        assertEquals(listOf(item), PersistentBasketRepository(file, backgroundScope).items.value)
    }

    @Test
    fun missingFileGivesEmptyBasketAndIsNotCreatedUntilSomethingChanges() = runTest(testDispatcher) {
        val file = File(tempFolder.root, "basket.json")
        val repo = PersistentBasketRepository(file, backgroundScope)
        runCurrent()

        assertTrue(repo.items.value.isEmpty())
        assertFalse(file.exists())
    }

    @Test
    fun corruptFileGivesEmptyBasketAndIsReplacedOnNextChange() = runTest(testDispatcher) {
        val file = File(tempFolder.root, "basket.json")
        file.writeText("{\"version\":1,\"items\":[{\"id\":", Charsets.UTF_8)

        val repo = PersistentBasketRepository(file, backgroundScope)
        assertTrue(repo.items.value.isEmpty())

        val item = catalogItem()
        repo.addItem(item)
        runCurrent()
        assertEquals(listOf(item), PersistentBasketRepository(file, backgroundScope).items.value)
    }

    @Test
    fun oneDamagedItemIsSkippedAndTheRestAreKept() {
        val good = catalogItem()
        val bad = customItem()
        val json = BasketItemsJson.encode(listOf(good, bad))
            .replace("\"name\":\"${bad.food.name}\"", "\"name\":12")

        assertEquals(listOf(good), BasketItemsJson.decode(json))
    }

    @Test
    fun unknownMealNameKeepsTheItemUnderBreakfast() {
        val item = customItem()
        val json = BasketItemsJson.encode(listOf(item))
            .replace("\"mealType\":\"${item.mealType.name}\"", "\"mealType\":\"BRUNCH\"")

        assertEquals(listOf(item.copy(mealType = MealType.BREAKFAST)), BasketItemsJson.decode(json))
    }

    @Test
    fun restoredItemLogsExactlyWhatWasStaged() = runTest(testDispatcher) {
        val file = File(tempFolder.root, "basket.json")
        val staged = catalogItem()
        PersistentBasketRepository(file, backgroundScope).addItem(staged)
        runCurrent()

        val reopened = PersistentBasketRepository(file, backgroundScope)
        val diary = RecordingDiaryRepository()
        assertEquals(1, CommitBasketUseCase(reopened, diary)().getOrThrow())
        runCurrent()

        val entry = diary.entries.single()
        assertEquals(staged.calculatedNutrition, entry.calculatedNutrition)
        assertEquals(staged.serving, entry.serving)
        assertEquals(staged.quantity, entry.quantity, 0.0)
        assertEquals(staged.food.id, entry.food.id)
        assertTrue(PersistentBasketRepository(file, backgroundScope).items.value.isEmpty())
    }

    // ---------------------------------------------------------------------------------------
    // BUG-011: one commit at a time; nothing logged twice
    // ---------------------------------------------------------------------------------------

    @Test
    fun overlappingLogAllCommitsLogEachItemOnce() = runTest(testDispatcher) {
        val basket = InMemoryBasketRepository(listOf(catalogItem(), customItem(), recipeItem()))
        val diary = RecordingDiaryRepository().apply { insertDelayMs = 100 }
        val commit = CommitBasketUseCase(basket, diary)

        val a = async { commit() }
        val b = async { commit() }
        advanceUntilIdle()

        assertEquals(3, diary.entries.size)
        assertEquals(setOf(3, 0), setOf(a.await().getOrThrow(), b.await().getOrThrow()))
        assertTrue(basket.items.value.isEmpty())
    }

    @Test
    fun overlappingSingleCommitsOfOneItemLogItOnce() = runTest(testDispatcher) {
        val item = catalogItem()
        val basket = InMemoryBasketRepository(listOf(item))
        val diary = RecordingDiaryRepository().apply { insertDelayMs = 100 }
        val commit = CommitSingleBasketItemUseCase(basket, diary)

        val a = async { commit(item.id) }
        val b = async { commit(item.id) }
        advanceUntilIdle()

        assertEquals(1, diary.entries.size)
        assertTrue(a.await().isSuccess)
        assertTrue(b.await().exceptionOrNull() is BasketItemMissingException)
    }

    @Test
    fun itemAddedWhileLogAllRunsStaysInTheBasket() = runTest(testDispatcher) {
        val basket = InMemoryBasketRepository(listOf(catalogItem(), customItem()))
        val diary = RecordingDiaryRepository().apply { insertDelayMs = 100 }

        val commit = async { CommitBasketUseCase(basket, diary)() }
        runCurrent() // the commit is now waiting on the diary insert
        val late = recipeItem()
        basket.addItem(late)
        advanceUntilIdle()

        assertEquals(2, commit.await().getOrThrow())
        assertEquals(2, diary.entries.size)
        assertEquals(listOf(late), basket.items.value)
    }

    @Test
    fun failedLogAllKeepsEveryItemInTheBasket() = runTest(testDispatcher) {
        val items = listOf(catalogItem(), customItem())
        val basket = InMemoryBasketRepository(items)
        val diary = RecordingDiaryRepository().apply { failNextInserts = 1 }

        assertTrue(CommitBasketUseCase(basket, diary)().isFailure)
        assertEquals(items, basket.items.value)
        assertTrue(diary.entries.isEmpty())
    }

    @Test
    fun doubleTapOnFoodDetailLogLogsOnce() = runTest(testDispatcher) {
        val env = FoodDetailEnv()
        env.viewModel.loadFood(OATS_ID, MealType.LUNCH, day)
        advanceUntilIdle()

        var closed = 0
        env.viewModel.logFood { closed++ }
        env.viewModel.logFood { closed++ }
        advanceUntilIdle()

        assertEquals(1, env.diary.entries.size)
        assertEquals(1, closed)
        assertTrue(env.basket.items.value.isEmpty())
    }

    @Test
    fun doubleTapOnKeepInBasketAddsOnce() = runTest(testDispatcher) {
        val env = FoodDetailEnv()
        env.viewModel.loadFood(OATS_ID, MealType.LUNCH, day)
        advanceUntilIdle()

        var closed = 0
        env.viewModel.keepInBasket { closed++ }
        env.viewModel.keepInBasket { closed++ }

        assertEquals(1, env.basket.items.value.size)
        assertEquals(1, closed)
    }

    @Test
    fun failedFoodDetailLogLeavesNoCopyBehindAndRetryLogsOnce() = runTest(testDispatcher) {
        val env = FoodDetailEnv()
        env.diary.failNextInserts = 1
        env.viewModel.loadFood(OATS_ID, MealType.LUNCH, day)
        advanceUntilIdle()

        var closed = 0
        env.viewModel.logFood { closed++ }
        advanceUntilIdle()
        val failed = env.viewModel.uiState.value
        assertEquals(0, closed)
        assertNotNull(failed.actionError)
        assertFalse(failed.isSaving)
        assertTrue(env.basket.items.value.isEmpty())

        env.viewModel.logFood { closed++ }
        advanceUntilIdle()
        assertEquals(1, closed)
        assertEquals(1, env.diary.entries.size)
        assertTrue(env.basket.items.value.isEmpty())
    }

    @Test
    fun loggingABasketItemRemovedElsewhereReportsItInsteadOfClosing() = runTest(testDispatcher) {
        val env = FoodDetailEnv()
        val item = catalogItem()
        env.basket.addItem(item)
        env.viewModel.loadFood(OATS_ID, basketItemId = item.id)
        advanceUntilIdle()
        env.basket.removeItem(item.id) // logged or removed from another screen

        var closed = false
        env.viewModel.logFood { closed = true }
        advanceUntilIdle()

        assertFalse(closed)
        assertNotNull(env.viewModel.uiState.value.actionError)
        assertFalse(env.viewModel.uiState.value.isSaving)
        assertTrue(env.diary.entries.isEmpty())
    }

    @Test
    fun failedLogOfABasketItemKeepsItInTheBasket() = runTest(testDispatcher) {
        val env = FoodDetailEnv()
        env.diary.failNextInserts = 1
        val item = catalogItem()
        env.basket.addItem(item)
        env.viewModel.loadFood(OATS_ID, basketItemId = item.id)
        advanceUntilIdle()

        env.viewModel.logFood { }
        advanceUntilIdle()

        assertEquals(listOf(item.id), env.basket.items.value.map { it.id })
        assertEquals("Couldn't log this food. It is still in your basket.", env.viewModel.uiState.value.actionError)
    }

    @Test
    fun doubleTapOnBasketLogAllLogsOnce() = runTest(testDispatcher) {
        val env = BasketEnv()
        env.diary.insertDelayMs = 100
        env.basket.addItem(catalogItem())
        env.basket.addItem(customItem())
        advanceUntilIdle()

        var closed = 0
        env.viewModel.submitBasket(onSuccess = { closed++ }, onError = { })
        env.viewModel.submitBasket(onSuccess = { closed++ }, onError = { })
        assertTrue(env.viewModel.isSubmitting.value)
        advanceUntilIdle()

        assertEquals(2, env.diary.entries.size)
        assertEquals(1, closed)
        assertFalse(env.viewModel.isSubmitting.value)
    }

    @Test
    fun failedBasketLogKeepsItemsAndShowsTheError() = runTest(testDispatcher) {
        val env = BasketEnv()
        env.diary.failNextInserts = 1
        env.basket.addItem(catalogItem())
        advanceUntilIdle()

        var closed = false
        var error: String? = null
        env.viewModel.submitBasket(onSuccess = { closed = true }, onError = { error = it })
        advanceUntilIdle()

        assertFalse(closed)
        assertNotNull(error)
        assertEquals(error, env.viewModel.submitError.value)
        assertEquals(1, env.basket.items.value.size)
        assertFalse(env.viewModel.isSubmitting.value)
    }

    // ---------------------------------------------------------------------------------------
    // BUG-012: blank, zero, negative and comma amounts
    // ---------------------------------------------------------------------------------------

    @Test
    fun positiveQuantityParsing() {
        assertEquals(1.5, parsePositiveQuantity("1,5")!!, 0.0)
        assertEquals(1.5, parsePositiveQuantity("1.5")!!, 0.0)
        assertEquals(2.0, parsePositiveQuantity(" 2 ")!!, 0.0)
        assertEquals(0.25, parsePositiveQuantity(".25")!!, 0.0)
        for (bad in listOf("", " ", "0", "0.0", "-1", "-0,5", "abc", ".", "1..2", "NaN", "Infinity", "-Infinity")) {
            assertNull("\"$bad\" must be rejected", parsePositiveQuantity(bad))
        }
    }

    @Test
    fun invalidFoodDetailAmountBlocksLoggingAndKeepsTheLastValidAmount() = runTest(testDispatcher) {
        val env = FoodDetailEnv()
        env.viewModel.loadFood(OATS_ID, MealType.LUNCH, day)
        advanceUntilIdle()
        env.viewModel.onQuantityChange("2")
        val valid = env.viewModel.uiState.value

        for (bad in listOf("", "0", "-1", "abc", ".")) {
            env.viewModel.onQuantityChange(bad)
            val state = env.viewModel.uiState.value
            assertEquals(QUANTITY_INPUT_ERROR, state.quantityError)
            assertEquals(bad, state.quantityInputText)
            assertEquals(2.0, state.enteredQuantity, 0.0)
            assertEquals(valid.calculatedNutrition, state.calculatedNutrition)

            var closed = false
            env.viewModel.logFood { closed = true }
            env.viewModel.keepInBasket { closed = true }
            advanceUntilIdle()
            assertFalse(closed)
            assertFalse(env.viewModel.uiState.value.isSaving)
        }
        assertTrue(env.diary.entries.isEmpty())
        assertTrue(env.basket.items.value.isEmpty())

        env.viewModel.onQuantityChange("1,5")
        assertNull(env.viewModel.uiState.value.quantityError)
        assertEquals(1.5, env.viewModel.uiState.value.enteredQuantity, 0.0)
        env.viewModel.logFood { }
        advanceUntilIdle()
        assertEquals(1.5, env.diary.entries.single().quantity, 0.0)
        assertEquals(389.0 * 1.5, env.diary.entries.single().calculatedNutrition.calories, 1e-9)
    }

    @Test
    fun invalidBasketRowAmountBlocksLogAllUntilFixed() = runTest(testDispatcher) {
        val env = BasketEnv()
        val item = catalogItem()
        env.basket.addItem(item)
        advanceUntilIdle()

        env.viewModel.onQuantityChange(item.id, "0")
        advanceUntilIdle()
        assertEquals(setOf(item.id), env.viewModel.invalidQuantityIds.value)
        assertEquals(item.quantity, env.basket.items.value.single().quantity, 0.0)

        var error: String? = null
        var closed = false
        env.viewModel.submitBasket(onSuccess = { closed = true }, onError = { error = it })
        advanceUntilIdle()
        assertFalse(closed)
        assertNotNull(error)
        assertTrue(env.diary.entries.isEmpty())

        env.viewModel.onQuantityChange(item.id, "2,5")
        advanceUntilIdle()
        assertTrue(env.viewModel.invalidQuantityIds.value.isEmpty())
        env.viewModel.submitBasket(onSuccess = { closed = true }, onError = { })
        advanceUntilIdle()
        assertTrue(closed)
        assertEquals(2.5, env.diary.entries.single().quantity, 0.0)
    }

    @Test
    fun removingAnInvalidBasketRowUnblocksLogAll() = runTest(testDispatcher) {
        val env = BasketEnv()
        val bad = catalogItem()
        val good = customItem()
        env.basket.addItem(bad)
        env.basket.addItem(good)
        advanceUntilIdle()

        env.viewModel.onQuantityChange(bad.id, "")
        env.viewModel.removeItem(bad.id)
        advanceUntilIdle()
        assertTrue(env.viewModel.invalidQuantityIds.value.isEmpty())

        var closed = false
        env.viewModel.submitBasket(onSuccess = { closed = true }, onError = { })
        advanceUntilIdle()
        assertTrue(closed)
        assertEquals(listOf(good.food.id), env.diary.entries.map { it.food.id })
    }

    // ---------------------------------------------------------------------------------------
    // BUG-015: recipe "Log to Diary" logs; "Add to Basket" stages
    // ---------------------------------------------------------------------------------------

    @Test
    fun recipeLogToDiaryLogsRightAway() = runTest(testDispatcher) {
        val env = RecipesEnv()
        var done = false
        env.viewModel.logRecipeToDiary(dal, MealType.DINNER, day, 1.5, onComplete = { done = true })
        advanceUntilIdle()

        assertTrue(done)
        assertTrue(env.basket.items.value.isEmpty())
        val entry = env.diary.entries.single()
        assertEquals(Recipe.FOOD_ID_OFFSET + dal.id, entry.food.id)
        assertEquals(FoodSource.RECIPE, entry.food.source)
        assertEquals(1.5, entry.quantity, 0.0)
        assertEquals(375.0, entry.calculatedNutrition.calories, 1e-9)
        assertEquals(MealType.DINNER, entry.mealType)
        assertEquals(day, entry.date)
    }

    @Test
    fun recipeAddToBasketOnlyStages() = runTest(testDispatcher) {
        val env = RecipesEnv()
        var done = false
        env.viewModel.addRecipeToBasket(dal, MealType.LUNCH, day, 2.0) { done = true }
        advanceUntilIdle()

        assertTrue(done)
        assertTrue(env.diary.entries.isEmpty())
        val item = env.basket.items.value.single()
        assertEquals(2.0, item.quantity, 0.0)
        assertEquals(500.0, item.calculatedNutrition.calories, 1e-9)
    }

    @Test
    fun recipeWithInvalidServingsIsNeitherLoggedNorStaged() = runTest(testDispatcher) {
        val env = RecipesEnv()
        for (bad in listOf(0.0, -2.0, Double.NaN, Double.POSITIVE_INFINITY)) {
            var error: String? = null
            var done = false
            env.viewModel.logRecipeToDiary(dal, MealType.LUNCH, day, bad, onComplete = { done = true }, onError = { error = it })
            env.viewModel.addRecipeToBasket(dal, MealType.LUNCH, day, bad) { done = true }
            advanceUntilIdle()
            assertEquals(QUANTITY_INPUT_ERROR, error)
            assertFalse(done)
        }
        assertTrue(env.basket.items.value.isEmpty())
        assertTrue(env.diary.entries.isEmpty())
    }

    @Test
    fun failedRecipeLogLeavesNoCopyBehindAndRetryLogsOnce() = runTest(testDispatcher) {
        val env = RecipesEnv()
        env.diary.failNextInserts = 1
        var error: String? = null
        var done = 0
        env.viewModel.logRecipeToDiary(dal, MealType.LUNCH, day, 1.0, onComplete = { done++ }, onError = { error = it })
        advanceUntilIdle()
        assertNotNull(error)
        assertEquals(0, done)
        assertTrue(env.basket.items.value.isEmpty())

        env.viewModel.logRecipeToDiary(dal, MealType.LUNCH, day, 1.0, onComplete = { done++ }, onError = { error = it })
        advanceUntilIdle()
        assertEquals(1, done)
        assertEquals(1, env.diary.entries.size)
        assertTrue(env.basket.items.value.isEmpty())
    }

    @Test
    fun recreatedBasketRowDropsTheErrorForTextThatIsGone() = runTest(testDispatcher) {
        val env = BasketEnv()
        val item = catalogItem()
        env.basket.addItem(item)
        advanceUntilIdle()

        env.viewModel.onQuantityChange(item.id, "")
        advanceUntilIdle()
        assertEquals(setOf(item.id), env.viewModel.invalidQuantityIds.value)

        // The row came back showing the stored amount (rotation, scrolling, another screen)
        env.viewModel.onQuantityTextReset(item.id)
        advanceUntilIdle()
        assertTrue(env.viewModel.invalidQuantityIds.value.isEmpty())
    }

    @Test
    fun basketItemWithStoredAmountOfZeroBlocksLogAll() = runTest(testDispatcher) {
        // e.g. copied from an entry an older version logged with quantity 0
        val env = BasketEnv()
        val bad = catalogItem().copy(quantity = 0.0)
        env.basket.addItem(bad)
        env.basket.addItem(customItem())
        advanceUntilIdle()

        assertEquals(setOf(bad.id), env.viewModel.invalidQuantityIds.value)
        env.viewModel.onQuantityTextReset(bad.id) // the field shows "0"; still not loggable
        advanceUntilIdle()
        assertEquals(setOf(bad.id), env.viewModel.invalidQuantityIds.value)

        var error: String? = null
        env.viewModel.submitBasket(onSuccess = { }, onError = { error = it })
        advanceUntilIdle()
        assertNotNull(error)
        assertTrue(env.diary.entries.isEmpty())

        env.viewModel.onQuantityChange(bad.id, "1")
        advanceUntilIdle()
        assertTrue(env.viewModel.invalidQuantityIds.value.isEmpty())
    }

    @Test
    fun commitRefusesItemsWithAmountOfZeroOrLess() = runTest(testDispatcher) {
        for (bad in listOf(0.0, -1.0, Double.NaN)) {
            val item = catalogItem().copy(quantity = bad)
            val basket = InMemoryBasketRepository(listOf(item, customItem()))
            val diary = RecordingDiaryRepository()

            assertTrue(CommitBasketUseCase(basket, diary)().exceptionOrNull() is InvalidBasketQuantityException)
            assertTrue(CommitSingleBasketItemUseCase(basket, diary)(item.id).exceptionOrNull() is InvalidBasketQuantityException)
            assertTrue(diary.entries.isEmpty())
            assertEquals(2, basket.items.value.size)
        }
    }

    @Test
    fun diaryEntryUsesTheBasketItemIdAsItsUuid() = runTest(testDispatcher) {
        val item = catalogItem()
        val diary = RecordingDiaryRepository()
        CommitSingleBasketItemUseCase(InMemoryBasketRepository(listOf(item)), diary)(item.id).getOrThrow()

        assertEquals(item.id, diary.entries.single().uuid)
    }

    @Test
    fun openingAnEntryLoggedWithZeroBlocksSaveButAllowsDelete() = runTest(testDispatcher) {
        val env = FoodDetailEnv()
        val legacyId = env.diary.addEntry(
            DiaryEntry(
                uuid = "legacy-zero",
                date = day,
                mealType = MealType.LUNCH,
                food = oats,
                serving = oats.servings[0],
                quantity = 0.0,
                calculatedNutrition = Nutrition.ZERO
            )
        )
        env.viewModel.loadFood(OATS_ID, entryId = legacyId)
        advanceUntilIdle()
        assertEquals(QUANTITY_INPUT_ERROR, env.viewModel.uiState.value.quantityError)

        var closed = false
        env.viewModel.logFood { closed = true }
        advanceUntilIdle()
        assertFalse(closed)

        env.viewModel.deleteEntry { closed = true }
        advanceUntilIdle()
        assertTrue(closed)
        assertTrue(env.diary.entries.isEmpty())
    }

    @Test
    fun openingABasketItemWithAmountOfZeroBlocksLogAndKeep() = runTest(testDispatcher) {
        val env = FoodDetailEnv()
        val item = catalogItem().copy(quantity = 0.0)
        env.basket.addItem(item)
        env.viewModel.loadFood(OATS_ID, basketItemId = item.id)
        advanceUntilIdle()

        assertEquals(QUANTITY_INPUT_ERROR, env.viewModel.uiState.value.quantityError)
        var closed = false
        env.viewModel.logFood { closed = true }
        env.viewModel.keepInBasket { closed = true }
        advanceUntilIdle()
        assertFalse(closed)
        assertTrue(env.diary.entries.isEmpty())
    }

    @Test
    fun doubleTapOnRecipeSaveCreatesOneRecipe() = runTest(testDispatcher) {
        val recipes = EmptyRecipeRepository()
        val env = RecipesEnv(recipes)
        var closed = 0
        env.viewModel.saveRecipe(dal.copy(id = 0)) { closed++ }
        env.viewModel.saveRecipe(dal.copy(id = 0)) { closed++ }
        advanceUntilIdle()

        assertEquals(1, recipes.created)
        assertEquals(1, closed)
    }

    // ---------------------------------------------------------------------------------------
    // Fixtures
    // ---------------------------------------------------------------------------------------

    private val dal = Recipe(
        id = 3,
        uuid = "recipe-dal",
        name = "Dal",
        servingsProduced = 2,
        savedNutritionPerServing = Nutrition(calories = 250.0, proteinGrams = 12.0, carbsGrams = 30.0, fatGrams = 8.0)
    )

    private val oats = Food(
        id = OATS_ID,
        uuid = "oats",
        name = "Rolled Oats",
        brand = "Mom's \"Best\" \\ Oats\nLine 2",
        category = "Cereals",
        barcode = "8901234567890",
        nutrition = Nutrition(
            calories = 389.0,
            proteinGrams = 16.9,
            carbsGrams = 66.3,
            fatGrams = 6.9,
            fiberGrams = 0.0,
            sugarGrams = null,
            sodiumMg = 2.0,
            ironMg = 4.7
        ),
        servings = listOf(
            Serving(id = 11, description = "100 g", unit = ServingUnit.GRAMS, quantity = 1.0, gramWeight = 100.0, isDefault = true),
            Serving(id = 12, description = "1/2 cup", unit = ServingUnit.CUP, quantity = 0.5, gramWeight = 40.0, sequence = 1)
        )
    )

    private fun catalogItem(): BasketItem {
        val serving = oats.servings[1]
        return item(oats, serving, 0.1 + 0.2, oats.nutritionFor(serving, 0.1 + 0.2), MealType.BREAKFAST)
    }

    private fun customItem(): BasketItem {
        val food = Food(
            id = 100_000_000L + 7,
            uuid = "custom-7",
            source = FoodSource.CUSTOM_USER,
            name = "Protein Bar",
            isUserOwned = true,
            servingBasis = "1 bar",
            nutrition = Nutrition(calories = 210.0, proteinGrams = 20.0, carbsGrams = 22.0, fatGrams = 7.0, fiberGrams = 3.0),
            servings = listOf(
                Serving(id = 1, description = "1 bar", unit = ServingUnit.CUSTOM, customUnitName = "bar", quantity = 1.0, gramWeight = 60.0, isDefault = true),
                Serving(id = 2, description = "1 g", unit = ServingUnit.GRAMS, quantity = 1.0, gramWeight = 1.0, sequence = 1)
            )
        )
        return item(food, food.servings[0], 2.0, food.nutrition.scale(2.0), MealType.SNACK)
    }

    private fun snapshotItem(): BasketItem {
        val serving = Serving(description = "1 bowl", unit = ServingUnit.BOWL)
        val food = Food(
            id = 5_000_000_000_000_001L,
            uuid = "snap",
            name = "Khichdi",
            isSnapshot = true,
            nutrition = Nutrition(calories = 300.0, proteinGrams = 9.0, carbsGrams = 50.0, fatGrams = 6.0),
            servings = emptyList()
        )
        return item(food, serving, 1.0, food.nutrition, MealType.DINNER)
    }

    private fun recipeItem(): BasketItem {
        val food = dal.toFood()
        val serving = food.servings.first().copy(gramWeight = 0.0)
        return item(food, serving, 1.0, dal.nutritionPerServing, MealType.LUNCH)
    }

    private fun item(food: Food, serving: Serving, quantity: Double, nutrition: Nutrition, meal: MealType) =
        BasketItem.create(food, serving, quantity, nutrition, day, meal)
            // The file keeps millisecond precision, like Instant values stored elsewhere in the app
            .copy(createdAt = Instant.ofEpochMilli(1_790_000_000_123L))

    private inner class FoodDetailEnv {
        val basket = InMemoryBasketRepository()
        val diary = RecordingDiaryRepository()
        val viewModel = FoodDetailViewModel(
            GetFoodDetailsUseCase(SingleFoodRepository(oats)),
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

    private inner class BasketEnv {
        val basket = InMemoryBasketRepository()
        val diary = RecordingDiaryRepository()
        val viewModel = BasketViewModel(
            getBasketItemsUseCase = GetBasketItemsUseCase(basket),
            removeBasketItemUseCase = RemoveBasketItemUseCase(basket),
            updateBasketItemUseCase = UpdateBasketItemUseCase(basket),
            updateAllBasketItemsUseCase = UpdateAllBasketItemsUseCase(basket),
            clearBasketUseCase = ClearBasketUseCase(basket),
            commitBasketUseCase = CommitBasketUseCase(basket, diary),
            calculateNutritionForServingUseCase = CalculateNutritionForServingUseCase()
        )
    }

    private inner class RecipesEnv(recipes: RecipeRepository = EmptyRecipeRepository()) {
        val basket: BasketRepository = InMemoryBasketRepository()
        val diary = RecordingDiaryRepository()
        val viewModel = RecipesViewModel(
            recipes,
            SingleFoodRepository(oats),
            AddFoodToBasketUseCase(basket),
            CommitSingleBasketItemUseCase(basket, diary),
            RemoveBasketItemUseCase(basket)
        )
    }

    private class RecordingDiaryRepository : DiaryRepository {
        val entries = mutableListOf<DiaryEntry>()
        var insertDelayMs = 0L
        var failNextInserts = 0

        override suspend fun addEntries(entries: List<DiaryEntry>) {
            if (insertDelayMs > 0) delay(insertDelayMs)
            if (failNextInserts > 0) {
                failNextInserts--
                throw IllegalStateException("database is full")
            }
            entries.forEach { this.entries.add(it.copy(id = this.entries.size + 1L)) }
        }

        override suspend fun addEntry(entry: DiaryEntry): Long {
            addEntries(listOf(entry))
            return entries.last().id
        }

        override suspend fun getDiaryForDate(date: LocalDate) = com.macrobase.app.domain.model.DailyNutritionSummary(date = date)
        override fun observeDiaryForDate(date: LocalDate) = flowOf(com.macrobase.app.domain.model.DailyNutritionSummary(date = date))
        override suspend fun getEntryById(entryId: Long) = entries.firstOrNull { it.id == entryId }
        override suspend fun updateEntry(entry: DiaryEntry) {}
        override suspend fun deleteEntry(entryId: Long) {
            entries.removeAll { it.id == entryId }
        }
        override suspend fun getMonthlyAdherence(year: Int, month: Int) = emptyList<com.macrobase.app.domain.model.CalendarDaySummary>()
        override fun observeMonthlyAdherence(year: Int, month: Int) = flowOf(emptyList<com.macrobase.app.domain.model.CalendarDaySummary>())
        override fun observeNutritionAggregations(startDate: LocalDate, endDate: LocalDate) = flowOf(emptyList<com.macrobase.app.domain.repository.DailyMacroAggregation>())
        override suspend fun getNutritionAggregations(startDate: LocalDate, endDate: LocalDate) = emptyList<com.macrobase.app.domain.repository.DailyMacroAggregation>()
    }

    private class SingleFoodRepository(private val food: Food) : FoodRepository {
        override suspend fun searchFoods(query: String, limit: Int) = listOf(food)
        override suspend fun getFoodById(id: Long) = food.takeIf { it.id == id }
        override suspend fun getFoodByBarcode(barcode: String): Food? = null
        override suspend fun getFoodServings(foodId: Long) = food.servings
        override suspend fun getRecentFoods(limit: Int) = emptyList<Food>()
        override suspend fun getFavoriteFoods() = emptyList<Food>()
        override fun observeCustomFoods() = flowOf(emptyList<Food>())
        override suspend fun getCustomFoods() = emptyList<Food>()
        override suspend fun saveCustomFood(food: CustomFood) = 0L
        override suspend fun deleteCustomFood(id: Long) {}
    }

    private class EmptyRecipeRepository : RecipeRepository {
        var created = 0
        override suspend fun createRecipe(recipe: Recipe): Long {
            created++
            return created.toLong()
        }
        override suspend fun updateRecipe(recipe: Recipe) {}
        override suspend fun deleteRecipe(recipeId: Long) {}
        override fun observeRecipes() = flowOf(emptyList<Recipe>())
        override suspend fun getRecipes() = emptyList<Recipe>()
        override suspend fun getRecipeById(recipeId: Long): Recipe? = null
    }

    private companion object {
        const val OATS_ID = 1_234_567_890_123_456L
    }
}
