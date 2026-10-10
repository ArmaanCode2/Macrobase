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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate

/** Ids of rows that cannot be logged: typed text that is not an amount, or a stored amount of 0 or less. */
private fun invalidIds(items: List<BasketItem>, typedInvalid: Set<String>): Set<String> =
    items.mapNotNullTo(HashSet()) { item ->
        item.id.takeIf { it in typedInvalid || !(item.quantity.isFinite() && item.quantity > 0.0) }
    }

enum class LoggingMode {
    MULTIPLE_FOODS,
    SINGLE_FOOD_RECIPE
}

class BasketViewModel(
    private val getBasketItemsUseCase: GetBasketItemsUseCase,
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

    /** Rows whose typed quantity text is not a loggable amount (BUG-012). */
    private val _typedInvalidIds = MutableStateFlow<Set<String>>(emptySet())

    /**
     * Rows that block "Log X Foods" until fixed: typed text that is not an amount, or a stored
     * amount of 0 or less (copied from an entry an older version logged, BUG-012).
     */
    val invalidQuantityIds: StateFlow<Set<String>> =
        combine(getBasketItemsUseCase(), _typedInvalidIds, ::invalidIds)
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    /** True while the basket is being logged; repeat taps on "Log X Foods" are ignored (BUG-011). */
    private val _isSubmitting = MutableStateFlow(false)
    val isSubmitting: StateFlow<Boolean> = _isSubmitting.asStateFlow()

    private val _submitError = MutableStateFlow<String?>(null)
    val submitError: StateFlow<String?> = _submitError.asStateFlow()

    init {
        viewModelScope.launch {
            items.collect { currentItems ->
                // Forget invalid-quantity marks for items that left the basket (logged elsewhere, removed)
                val ids = currentItems.mapTo(HashSet()) { it.id }
                _typedInvalidIds.update { invalid -> invalid.filterTo(HashSet()) { it in ids } }
                if (currentItems.isEmpty()) {
                    hasUserOverriddenDateMeal = false
                } else if (!hasUserOverriddenDateMeal) {
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
        val parsedQty = com.macrobase.app.feature.detail.parsePositiveQuantity(quantityText)
        if (parsedQty == null) {
            // Blank, zero, negative: keep the item's last valid amount and block logging until fixed
            _typedInvalidIds.update { it + itemId }
            return
        }
        _typedInvalidIds.update { it - itemId }

        val newNutrition = calculateNutritionForServingUseCase(item.food, item.serving, parsedQty)
        val updatedItem = item.copy(
            quantity = parsedQty,
            calculatedNutrition = newNutrition
        )
        updateBasketItemUseCase(updatedItem)
    }

    /**
     * The row's field was refilled from the item's stored amount (the row was recreated after
     * rotation, scrolling or another screen, or the amount changed elsewhere), so the text the
     * user typed is gone and so is its error.
     */
    fun onQuantityTextReset(itemId: String) {
        _typedInvalidIds.update { it - itemId }
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
        _typedInvalidIds.update { it - itemId }
        if (_revealedItemId.value == itemId) {
            _revealedItemId.value = null
        }
    }

    fun clearBasket() {
        hasUserOverriddenDateMeal = false
        clearBasketUseCase()
        _typedInvalidIds.value = emptySet()
        _revealedItemId.value = null
    }

    fun submitBasket(onSuccess: () -> Unit, onError: (String) -> Unit) {
        // Taps arrive on the main thread, so a second tap always sees the first one's flag
        if (_isSubmitting.value) return
        if (invalidIds(getBasketItemsUseCase().value, _typedInvalidIds.value).isNotEmpty()) {
            val message = "Fix the highlighted amounts before logging."
            _submitError.value = message
            onError(message)
            return
        }
        _isSubmitting.value = true
        _submitError.value = null
        viewModelScope.launch {
            val result = commitBasketUseCase()
            _isSubmitting.value = false
            result.onSuccess { logged ->
                // Nothing logged (basket already empty): no navigation, so no double back-press
                if (logged > 0) onSuccess()
            }.onFailure {
                // The diary insert is all-or-nothing, so on failure every item is still in the basket
                val message = "Couldn't log your basket. Your items are still here; please try again."
                _submitError.value = message
                onError(message)
            }
        }
    }
}
