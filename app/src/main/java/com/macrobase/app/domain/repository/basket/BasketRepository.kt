package com.macrobase.app.domain.repository.basket

import com.macrobase.app.domain.model.MealType
import com.macrobase.app.domain.model.basket.BasketItem
import kotlinx.coroutines.flow.StateFlow
import java.time.LocalDate

interface BasketRepository {
    val items: StateFlow<List<BasketItem>>
    
    fun addItem(item: BasketItem)
    fun removeItem(itemId: String)
    fun updateItem(item: BasketItem)
    fun updateAllMeals(mealType: MealType)
    fun updateAllDates(date: LocalDate)
    fun updateAllDateAndMeal(date: LocalDate, mealType: MealType)
    fun clearBasket()
}
