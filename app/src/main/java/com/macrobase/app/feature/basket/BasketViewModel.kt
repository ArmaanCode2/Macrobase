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
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate

/** Ids of rows that cannot be logged: typed text that is not an amount, or a stored amount of 0 or less. */
private fun invalidIds(items: List<BasketItem>, typedInvalid: Set<String>): Set<String> =
    items.mapNotNullTo(HashSet()) { item ->
        item.id.takeIf { it in typedInvalid || !(item.quantity.isFinite() && item.quantity > 0.0) }
    }

/** The value every item shares, or null when they differ or the basket is empty. */
private fun <T> sharedBy(items: List<BasketItem>, value: (BasketItem) -> T): T? =
    items.map(value).distinct().singleOrNull()

private fun isMixed(items: List<BasketItem>): Boolean =
    items.isNotEmpty() && (sharedBy(items) { it.date } == null || sharedBy(items) { it.mealType } == null)

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

    /**
     * The day every item will be logged under, or null when they differ (BUG-039). Each item is
     * logged under its own day and meal, so the header shows one only when they all share it.
     */
    val commonDate: StateFlow<LocalDate?> = getBasketItemsUseCase()
        .map { items -> sharedBy(items) { it.date } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, sharedBy(getBasketItemsUseCase().value) { it.date })

    /** The meal every item will be logged under, or null when they differ (BUG-039). */
    val commonMealType: StateFlow<MealType?> = getBasketItemsUseCase()
        .map { items -> sharedBy(items) { it.mealType } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, sharedBy(getBasketItemsUseCase().value) { it.mealType })

    /** True when the items differ in day or meal, so each row shows its own (BUG-039). */
    val isMixedDateOrMeal: StateFlow<Boolean> = getBasketItemsUseCase()
        .map(::isMixed)
        .stateIn(viewModelScope, SharingStarted.Eagerly, isMixed(getBasketItemsUseCase().value))

    private val _revealedItemId = MutableStateFlow<String?>(null)
    val revealedItemId: StateFlow<String?> = _revealedItemId.asStateFlow()

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

    /** Logs every item under [mealType]; the header follows from the items. */
    fun setCommonMealType(mealType: MealType) {
        updateAllBasketItemsUseCase(mealType)
    }

    /** Logs every item on [date]; the header follows from the items. */
    fun setCommonDate(date: LocalDate) {
        updateAllBasketItemsUseCase(date)
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
        clearBasketUseCase()
        _typedInvalidIds.value = emptySet()
        _revealedItemId.value = null
    }

    /** Errors also show through [submitError]; [onError] is for callers that want them directly. */
    fun submitBasket(onSuccess: () -> Unit, onError: (String) -> Unit = {}) {
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
