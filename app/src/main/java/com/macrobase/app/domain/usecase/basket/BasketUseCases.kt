package com.macrobase.app.domain.usecase.basket

import com.macrobase.app.domain.model.DiaryEntry
import com.macrobase.app.domain.model.Food
import com.macrobase.app.domain.model.MealType
import com.macrobase.app.domain.model.Nutrition
import com.macrobase.app.domain.model.Serving
import com.macrobase.app.domain.model.basket.BasketItem
import com.macrobase.app.domain.repository.DiaryRepository
import com.macrobase.app.domain.repository.basket.BasketRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.time.LocalDate

class AddFoodToBasketUseCase(
    private val basketRepository: BasketRepository
) {
    operator fun invoke(
        food: Food,
        serving: Serving,
        quantity: Double,
        calculatedNutrition: Nutrition,
        date: LocalDate,
        mealType: MealType
    ): String {
        val item = BasketItem.create(food, serving, quantity, calculatedNutrition, date, mealType)
        basketRepository.addItem(item)
        return item.id
    }
}

class GetBasketItemsUseCase(
    private val basketRepository: BasketRepository
) {
    operator fun invoke(): StateFlow<List<BasketItem>> = basketRepository.items
}

class RemoveBasketItemUseCase(
    private val basketRepository: BasketRepository
) {
    operator fun invoke(itemId: String) {
        basketRepository.removeItem(itemId)
    }
}

class UpdateBasketItemUseCase(
    private val basketRepository: BasketRepository
) {
    operator fun invoke(item: BasketItem) {
        basketRepository.updateItem(item)
    }
}

class UpdateAllBasketItemsUseCase(
    private val basketRepository: BasketRepository
) {
    operator fun invoke(mealType: MealType) {
        basketRepository.updateAllMeals(mealType)
    }

    operator fun invoke(date: LocalDate) {
        basketRepository.updateAllDates(date)
    }

    operator fun invoke(date: LocalDate, mealType: MealType) {
        basketRepository.updateAllDateAndMeal(date, mealType)
    }
}

class ClearBasketUseCase(
    private val basketRepository: BasketRepository
) {
    operator fun invoke() {
        basketRepository.clearBasket()
    }
}

/**
 * One basket commit at a time, app-wide (BUG-011). A second tap waits for the first commit and
 * then finds its items already gone, so nothing is logged twice.
 */
internal object BasketCommitLock {
    val mutex = Mutex()
}

/** The basket item to log no longer exists (removed elsewhere, or already logged). */
class BasketItemMissingException : IllegalStateException("This item is no longer in the basket, so nothing was logged.")

/** A basket item has an amount of 0 or less; nothing is logged until it is fixed (BUG-012). */
class InvalidBasketQuantityException : IllegalArgumentException("Enter an amount above 0 before logging.")

/** Like runCatching, but cancellation still cancels the caller instead of becoming a failure. */
private inline fun <T> commitCatching(block: () -> T): Result<T> = try {
    Result.success(block())
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    Result.failure(e)
}

/**
 * The diary entry for a basket item takes the item's id as its uuid. diary_entries.uuid is unique
 * and inserts REPLACE, so if a logged item ever comes back (the app died before the basket file
 * recorded its removal) logging it again replaces that entry instead of adding a duplicate.
 */
private fun BasketItem.toDiaryEntry(): DiaryEntry {
    if (!(quantity.isFinite() && quantity > 0.0)) throw InvalidBasketQuantityException()
    return DiaryEntry(
        id = 0L,
        uuid = id,
        date = date,
        mealType = mealType,
        food = food.copy(
            name = foodNameSnapshot,
            brand = brandSnapshot
        ),
        serving = serving,
        quantity = quantity,
        calculatedNutrition = calculatedNutrition,
        loggedAt = Instant.now()
    )
}

class CommitSingleBasketItemUseCase(
    private val basketRepository: BasketRepository,
    private val diaryRepository: DiaryRepository
) {
    suspend operator fun invoke(itemId: String): Result<Unit> = BasketCommitLock.mutex.withLock {
        commitCatching { commit(itemId) }
    }

    private suspend fun commit(itemId: String) {
        val items = basketRepository.items.value
        val item = items.firstOrNull { it.id == itemId } ?: throw BasketItemMissingException()

        diaryRepository.addEntries(listOf(item.toDiaryEntry()))
        basketRepository.removeItem(itemId)
    }
}

class CommitBasketUseCase(
    private val basketRepository: BasketRepository,
    private val diaryRepository: DiaryRepository
) {
    /** Logs every basket item in one insert; returns how many entries were logged. */
    suspend operator fun invoke(): Result<Int> = BasketCommitLock.mutex.withLock {
        commitCatching { commitAll() }
    }

    private suspend fun commitAll(): Int {
        val items = basketRepository.items.value
        if (items.isEmpty()) return 0

        val entries = items.map { it.toDiaryEntry() }

        // One Room insert for all entries; then remove exactly the committed items, so anything
        // added to the basket while this ran is kept
        diaryRepository.addEntries(entries)
        basketRepository.removeItems(items.map { it.id }.toSet())
        return items.size
    }
}
