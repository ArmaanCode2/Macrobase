package com.macrobase.app.feature.basket

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.macrobase.app.domain.model.MealType
import com.macrobase.app.domain.model.Serving
import com.macrobase.app.domain.model.basket.BasketItem
import com.macrobase.app.domain.usecase.CalculateNutritionForServingUseCase
import com.macrobase.app.domain.usecase.basket.ClearBasketUseCase
import com.macrobase.app.domain.usecase.basket.CommitBasketUseCase
import com.macrobase.app.domain.usecase.basket.GetBasketItemsUseCase
import com.macrobase.app.domain.usecase.basket.RemoveBasketItemUseCase
import com.macrobase.app.domain.usecase.basket.UpdateAllBasketItemsUseCase
import com.macrobase.app.domain.usecase.basket.UpdateBasketItemUseCase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate

enum class LoggingMode {
    MULTIPLE_FOODS,
    SINGLE_FOOD_RECIPE
}

class BasketViewModel(
    getBasketItemsUseCase: GetBasketItemsUseCase,
    private val removeBasketItemUseCase: RemoveBasketItemUseCase,
    private val updateBasketItemUseCase: UpdateBasketItemUseCase,
    private val updateAllBasketItemsUseCase: UpdateAllBasketItemsUseCase,
    private val clearBasketUseCase: ClearBasketUseCase,
    private val commitBasketUseCase: CommitBasketUseCase,
    private val calculateNutritionForServingUseCase: CalculateNutritionForServingUseCase
) : ViewModel() {

    val items: StateFlow<List<BasketItem>> = getBasketItemsUseCase().stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    private val _commonDate = MutableStateFlow(LocalDate.now())
    val commonDate: StateFlow<LocalDate> = _commonDate.asStateFlow()

    private val _commonMealType = MutableStateFlow(MealType.BREAKFAST)
    val commonMealType: StateFlow<MealType> = _commonMealType.asStateFlow()

    private val _loggingMode = MutableStateFlow(LoggingMode.MULTIPLE_FOODS)
    val loggingMode: StateFlow<LoggingMode> = _loggingMode.asStateFlow()

    private val _revealedItemId = MutableStateFlow<String?>(null)
    val revealedItemId: StateFlow<String?> = _revealedItemId.asStateFlow()

    private var hasUserOverriddenDateMeal = false

    init {
        viewModelScope.launch {
            items.collect { currentItems ->
                if (currentItems.isNotEmpty() && !hasUserOverriddenDateMeal) {
                    val first = currentItems.first()
                    _commonDate.value = first.date
                    _commonMealType.value = first.mealType
                }
            }
        }
    }

    fun onQuantityChange(itemId: String, quantityText: String) {
        val currentItems = items.value
        val item = currentItems.firstOrNull { it.id == itemId } ?: return
        val parsedQty = quantityText.toDoubleOrNull() ?: return
        if (parsedQty < 0.0) return

        val newNutrition = calculateNutritionForServingUseCase(item.food, item.serving, parsedQty)
        val updatedItem = item.copy(
            quantity = parsedQty,
            calculatedNutrition = newNutrition
        )
        updateBasketItemUseCase(updatedItem)
    }

    fun onServingChange(itemId: String, newServing: Serving) {
        val currentItems = items.value
        val item = currentItems.firstOrNull { it.id == itemId } ?: return

        val newNutrition = calculateNutritionForServingUseCase(item.food, newServing, item.quantity)
        val updatedItem = item.copy(
            serving = newServing,
            calculatedNutrition = newNutrition
        )
        updateBasketItemUseCase(updatedItem)
    }

    fun setCommonMealType(mealType: MealType) {
        hasUserOverriddenDateMeal = true
        _commonMealType.value = mealType
        updateAllBasketItemsUseCase(mealType)
    }

    fun setCommonDate(date: LocalDate) {
        hasUserOverriddenDateMeal = true
        _commonDate.value = date
        updateAllBasketItemsUseCase(date)
    }

    fun setLoggingMode(mode: LoggingMode) {
        _loggingMode.value = mode
    }

    fun setRevealedItem(itemId: String?) {
        _revealedItemId.value = itemId
    }

    fun removeItem(itemId: String) {
        removeBasketItemUseCase(itemId)
        if (_revealedItemId.value == itemId) {
            _revealedItemId.value = null
        }
    }

    fun clearBasket() {
        clearBasketUseCase()
        _revealedItemId.value = null
    }

    fun submitBasket(onSuccess: () -> Unit, onError: (String) -> Unit) {
        viewModelScope.launch {
            val result = commitBasketUseCase()
            if (result.isSuccess) {
                onSuccess()
            } else {
                onError(result.exceptionOrNull()?.message ?: "Failed to log basket items")
            }
        }
    }
}
