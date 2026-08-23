package com.macrobase.app.data.repository.basket

import com.macrobase.app.domain.model.MealType
import com.macrobase.app.domain.model.basket.BasketItem
import com.macrobase.app.domain.repository.basket.BasketRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class InMemoryBasketRepository @Inject constructor() : BasketRepository {
    private val _items = MutableStateFlow<List<BasketItem>>(emptyList())
    override val items: StateFlow<List<BasketItem>> = _items.asStateFlow()

    override fun addItem(item: BasketItem) {
        _items.update { current ->
            current + item
        }
    }

    override fun removeItem(itemId: String) {
        _items.update { current ->
            current.filter { it.id != itemId }
        }
    }

    override fun updateItem(item: BasketItem) {
        _items.update { current ->
            current.map { if (it.id == item.id) item else it }
        }
    }

    override fun updateAllMeals(mealType: MealType) {
        _items.update { current ->
            current.map { it.copy(mealType = mealType) }
        }
    }

    override fun updateAllDates(date: LocalDate) {
        _items.update { current ->
            current.map { it.copy(date = date) }
        }
    }

    override fun updateAllDateAndMeal(date: LocalDate, mealType: MealType) {
        _items.update { current ->
            current.map { it.copy(date = date, mealType = mealType) }
        }
    }

    override fun clearBasket() {
        _items.value = emptyList()
    }
}
