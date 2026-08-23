package com.macrobase.app.domain.usecase.basket

import com.macrobase.app.domain.model.DiaryEntry
import com.macrobase.app.domain.model.Food
import com.macrobase.app.domain.model.MealType
import com.macrobase.app.domain.model.Nutrition
import com.macrobase.app.domain.model.Serving
import com.macrobase.app.domain.model.basket.BasketItem
import com.macrobase.app.domain.repository.DiaryRepository
import com.macrobase.app.domain.repository.basket.BasketRepository
import kotlinx.coroutines.flow.StateFlow
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

class CommitSingleBasketItemUseCase(
    private val basketRepository: BasketRepository,
    private val diaryRepository: DiaryRepository
) {
    suspend operator fun invoke(itemId: String): Result<Unit> = runCatching {
        val items = basketRepository.items.value
        val item = items.firstOrNull { it.id == itemId } ?: return@runCatching
        
        val entry = DiaryEntry(
            id = 0L,
            uuid = java.util.UUID.randomUUID().toString(),
            date = item.date,
            mealType = item.mealType,
            food = item.food.copy(
                name = item.foodNameSnapshot,
                brand = item.brandSnapshot
            ),
            serving = item.serving,
            quantity = item.quantity,
            calculatedNutrition = item.calculatedNutrition,
            loggedAt = Instant.now()
        )
        
        diaryRepository.addEntries(listOf(entry))
        basketRepository.removeItem(itemId)
    }
}

class CommitBasketUseCase(
    private val basketRepository: BasketRepository,
    private val diaryRepository: DiaryRepository
) {
    suspend operator fun invoke(): Result<Unit> = runCatching {
        val items = basketRepository.items.value
        if (items.isEmpty()) return@runCatching

        // Transactionally insert all entries.
        // Currently DiaryRepository might only have insertDiaryEntry(entry: DiaryEntry)
        // We will loop through them. If there's an issue, it might not be a real SQL transaction
        // unless we modify DiaryRepository to have an insertAll transaction.
        // For now, let's map them to DiaryEntry and insert them.
        // Wait, DiaryRepository handles Room transactions if we provide a method. Let's add insertDiaryEntries to DiaryRepository.
        val entries = items.map { item ->
            DiaryEntry(
                id = 0L,
                uuid = java.util.UUID.randomUUID().toString(),
                date = item.date,
                mealType = item.mealType,
                food = item.food.copy(
                    name = item.foodNameSnapshot,
                    brand = item.brandSnapshot
                ),
                serving = item.serving,
                quantity = item.quantity,
                calculatedNutrition = item.calculatedNutrition,
                loggedAt = Instant.now()
            )
        }
        
        diaryRepository.addEntries(entries)
        basketRepository.clearBasket()
    }
}
