package com.macrobase.app

import androidx.room.Room
import com.macrobase.app.data.database.UserDatabase
import com.macrobase.app.data.repository.DiaryRepositoryImpl
import com.macrobase.app.data.repository.basket.InMemoryBasketRepository
import com.macrobase.app.domain.model.Food
import com.macrobase.app.domain.model.FoodSource
import com.macrobase.app.domain.model.Goal
import com.macrobase.app.domain.model.MealType
import com.macrobase.app.domain.model.Nutrition
import com.macrobase.app.domain.model.Serving
import com.macrobase.app.domain.model.basket.BasketItem
import com.macrobase.app.domain.repository.GoalsRepository
import com.macrobase.app.domain.usecase.basket.CommitBasketUseCase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.LocalDate

/** BUG-011 / BUG-015 against the real diary table. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class P2PhaseOneRoomTests {

    private lateinit var db: UserDatabase
    private val day = LocalDate.of(2026, 10, 5)

    private val goals = object : GoalsRepository {
        private val state = MutableStateFlow(Goal())
        override suspend fun getGoals() = state.value
        override fun observeGoals() = state
        override suspend fun updateGoals(goals: Goal) { state.value = goals }
    }

    private val bar = Food(
        id = 100_000_000L + 3,
        uuid = "bar",
        source = FoodSource.CUSTOM_USER,
        name = "Protein Bar",
        isUserOwned = true,
        nutrition = Nutrition(calories = 210.0, proteinGrams = 20.0, carbsGrams = 22.0, fatGrams = 7.0),
        servings = listOf(Serving(id = 1, description = "1 bar", gramWeight = 60.0, isDefault = true))
    )

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), UserDatabase::class.java)
            .allowMainThreadQueries()
            .setQueryExecutor { it.run() }
            .setTransactionExecutor { it.run() }
            .build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun barItem() = BasketItem.create(bar, bar.servings[0], 2.0, bar.nutrition.scale(2.0), day, MealType.LUNCH)

    @Test
    fun loggedItemThatComesBackIsReplacedNotDuplicated() = runBlocking {
        val diary = DiaryRepositoryImpl(db, goals)
        val item = barItem()
        val basket = InMemoryBasketRepository(listOf(item))
        assertEquals(1, CommitBasketUseCase(basket, diary)().getOrThrow())

        // The app died before the basket file recorded the removal, so the item is back
        basket.addItem(item)
        assertEquals(1, CommitBasketUseCase(basket, diary)().getOrThrow())

        val entries = diary.getDiaryForDate(day).meals.flatMap { it.entries }
        assertEquals(1, entries.size)
        assertEquals(item.id, entries.single().uuid)
        assertEquals(420.0, entries.single().calculatedNutrition.calories, 1e-9)
    }

    @Test
    fun theSameFoodLoggedTwiceOnPurposeGivesTwoEntries() = runBlocking {
        val diary = DiaryRepositoryImpl(db, goals)
        val basket = InMemoryBasketRepository(listOf(barItem(), barItem()))
        assertEquals(2, CommitBasketUseCase(basket, diary)().getOrThrow())

        assertEquals(2, diary.getDiaryForDate(day).meals.flatMap { it.entries }.size)
    }
}
