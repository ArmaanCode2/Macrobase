package com.macrobase.app.feature.detail

import com.macrobase.app.feature.basket.basketDateLabel
import com.macrobase.app.core.designsystem.components.MealAndDayPicker
import com.macrobase.app.core.designsystem.components.isScrolled
import com.macrobase.app.core.designsystem.components.MbHeader
import com.macrobase.app.core.designsystem.components.MbDangerGhostButton
import com.macrobase.app.core.designsystem.components.MbButtonKind
import com.macrobase.app.core.designsystem.components.MbButton
import com.macrobase.app.core.designsystem.components.MbChip
import com.macrobase.app.core.designsystem.components.MbPanel
import com.macrobase.app.core.designsystem.components.MbIconCircle
import com.macrobase.app.domain.model.FoodSource
import com.macrobase.app.domain.model.Food
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ColumnScope
import com.macrobase.app.core.designsystem.components.mbClickable
import com.macrobase.app.core.designsystem.components.mealLabel
import com.macrobase.app.core.designsystem.components.MbIconButton
import com.macrobase.app.core.designsystem.components.MbIcon
import com.macrobase.app.core.designsystem.Ph
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.PaddingValues
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.map
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.DatePickerDefaults
import java.time.ZoneOffset
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.macrobase.app.core.designsystem.AppColors
import com.macrobase.app.core.util.parseDecimalInput
import com.macrobase.app.core.designsystem.AppShapes
import com.macrobase.app.core.designsystem.AppSpacing
import com.macrobase.app.core.designsystem.AppTypography
import com.macrobase.app.core.designsystem.Dimensions
import com.macrobase.app.core.designsystem.components.PrimaryButton
import com.macrobase.app.domain.model.DiaryEntry
import com.macrobase.app.domain.model.MacroCalorieSplit
import com.macrobase.app.domain.model.MealType
import com.macrobase.app.domain.model.Nutrition
import com.macrobase.app.domain.model.Serving
import com.macrobase.app.domain.model.state.FoodDetailUiState
import com.macrobase.app.domain.usecase.CalculateNutritionForServingUseCase
import com.macrobase.app.domain.usecase.DeleteDiaryEntryUseCase
import com.macrobase.app.domain.usecase.GetFoodDetailsUseCase
import com.macrobase.app.domain.usecase.UpdateDiaryEntryUseCase
import com.macrobase.app.domain.usecase.basket.AddFoodToBasketUseCase
import com.macrobase.app.domain.usecase.basket.GetBasketItemsUseCase
import com.macrobase.app.domain.usecase.basket.UpdateBasketItemUseCase
import com.macrobase.app.domain.usecase.basket.RemoveBasketItemUseCase
import com.macrobase.app.domain.usecase.basket.CommitSingleBasketItemUseCase
import com.macrobase.app.domain.usecase.basket.BasketItemMissingException
import com.macrobase.app.domain.model.basket.BasketItem
import androidx.activity.compose.BackHandler
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.UUID
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.filled.Fastfood
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Warning
import androidx.compose.ui.text.style.TextAlign
import java.util.Locale
import kotlin.math.roundToInt

fun formatQuantityForInput(value: Double, tolerance: Double = 0.0001): String {
    if (!value.isFinite()) return "0"
    val rounded = kotlin.math.round(value)
    return if (kotlin.math.abs(value - rounded) < tolerance) {
        rounded.toLong().toString()
    } else {
        // Plain decimal without float noise or exponent: 0.1 + 0.2 shows "0.3", 1e7 shows "10000000"
        java.math.BigDecimal(value).setScale(6, java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
    }
}

/** Quantity typed by the user; accepts a comma decimal separator ("1,5") as many keyboards insert. */
fun parseQuantityInput(text: String): Double? = parseDecimalInput(text)

/**
 * A quantity that may be logged (BUG-012): "1,5" and "1.5" are accepted; blank, zero,
 * negative and non-finite input is rejected so it can never log 0 or negative calories.
 */
fun parsePositiveQuantity(text: String): Double? =
    parseQuantityInput(text)?.takeIf { it.isFinite() && it > 0.0 }

/** Shown next to a quantity field whose text [parsePositiveQuantity] rejects. */
const val QUANTITY_INPUT_ERROR = "Enter an amount above 0"

class FoodDetailViewModel(
    private val getFoodDetailsUseCase: GetFoodDetailsUseCase,
    private val calculateNutritionForServingUseCase: CalculateNutritionForServingUseCase,
    private val addFoodToBasketUseCase: AddFoodToBasketUseCase,
    private val getBasketItemsUseCase: GetBasketItemsUseCase,
    private val updateBasketItemUseCase: UpdateBasketItemUseCase,
    private val removeBasketItemUseCase: RemoveBasketItemUseCase,
    private val commitSingleBasketItemUseCase: CommitSingleBasketItemUseCase,
    private val updateDiaryEntryUseCase: UpdateDiaryEntryUseCase,
    private val deleteDiaryEntryUseCase: DeleteDiaryEntryUseCase,
    private val getDiaryEntryUseCase: com.macrobase.app.domain.usecase.GetDiaryEntryUseCase,
    private val clock: java.time.Clock = com.macrobase.app.core.util.DeviceClock,
    private val getGoalsUseCase: com.macrobase.app.domain.usecase.GetGoalsUseCase? = null
) : ViewModel() {

    private val _uiState = MutableStateFlow(FoodDetailUiState())
    val uiState: StateFlow<FoodDetailUiState> = _uiState.asStateFlow()

    /** The daily calorie target, for "% of your daily target"; 0 when it is not known. */
    val dailyCalorieGoal: StateFlow<Double> = (
        getGoalsUseCase?.invoke()?.map { it.dailyCalorieGoal } ?: kotlinx.coroutines.flow.flowOf(0.0)
    ).stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(5000), 0.0)

    private var editingEntryId: Long? = null
    private var editingBasketItemId: String? = null

    /** Opened without a day, so it logs to today, whatever day that is when it is logged (BUG-040). */
    private var dateFollowsToday = false

    // The diary entry this screen loaded, to notice a newer save made elsewhere
    private var loadedEntry: DiaryEntry? = null

    /**
     * True from the first tap of Log/Save/Keep/Delete/Remove until it fails; after success the
     * screen closes. Taps arrive on the main thread, so a second tap always sees it (BUG-011).
     */
    private var actionInFlight = false

    private fun startAction(requiresValidQuantity: Boolean = true): Boolean {
        if (actionInFlight) return false
        if (requiresValidQuantity && _uiState.value.quantityError != null) return false
        actionInFlight = true
        _uiState.update { it.copy(isSaving = true, actionError = null) }
        return true
    }

    private fun actionFailed(message: String) {
        actionInFlight = false
        _uiState.update { it.copy(isSaving = false, actionError = message) }
    }

    /** The arguments the screen was opened with (BUG-038). */
    private data class OpenRequest(
        val foodId: Long,
        val mealType: MealType?,
        val date: LocalDate?,
        val entryId: Long?,
        val basketItemId: String?
    )

    private var openedWith: OpenRequest? = null

    /**
     * Loads what the screen was opened for, once. A screen recreated by rotation asks again with
     * the same arguments and keeps what the user typed, even after Copy switched it to another
     * item (BUG-038). [loadFood] always reloads.
     */
    fun openFood(foodId: Long, mealType: MealType? = null, date: LocalDate? = null, entryId: Long? = null, basketItemId: String? = null) {
        val request = OpenRequest(foodId, mealType, date, entryId, basketItemId)
        if (request == openedWith) {
            reloadIfEntryChanged(request)
            return
        }
        openedWith = request
        loadFood(foodId, mealType, date, entryId, basketItemId)
    }

    /**
     * A screen shown again keeps what the user typed, unless its diary entry was saved elsewhere
     * meanwhile: then it shows the saved entry, so Save cannot overwrite it with an older one.
     */
    private fun reloadIfEntryChanged(request: OpenRequest) {
        val entryId = editingEntryId ?: return
        val loaded = loadedEntry ?: return
        viewModelScope.launch {
            if (getDiaryEntryUseCase(entryId) != loaded) {
                loadFood(request.foodId, request.mealType, request.date, request.entryId, request.basketItemId)
            }
        }
    }

    /** The screen is in the foreground again (BUG-040). */
    fun onResumed() {
        refreshImplicitDate()
    }

    /** A food opened without a day logs to today, so after midnight that is the new day (BUG-040). */
    private fun refreshImplicitDate() {
        if (!dateFollowsToday) return
        val today = LocalDate.now(clock)
        if (_uiState.value.targetDate != today) _uiState.update { it.copy(targetDate = today) }
    }

    fun loadFood(foodId: Long, mealType: MealType? = null, date: LocalDate? = null, entryId: Long? = null, basketItemId: String? = null) {
        editingEntryId = if (entryId != null && entryId > 0L) entryId else null
        editingBasketItemId = basketItemId

        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null, quantityError = null, actionError = null) }

            // 1. Check if editing an existing diary entry (EDIT MODE)
            val currentEditingEntryId = editingEntryId
            if (currentEditingEntryId != null && currentEditingEntryId > 0L) {
                val diaryEntry = getDiaryEntryUseCase(currentEditingEntryId)
                if (diaryEntry != null) {
                    dateFollowsToday = false
                    loadedEntry = diaryEntry
                    // Trust a looked-up food only if it is the one this entry logged; otherwise
                    // keep the entry's own snapshot so Save never rewrites it with another food.
                    val catalogFood = getFoodDetailsUseCase(diaryEntry.food.id)
                        ?.takeIf { it.isSameFoodAs(diaryEntry.food) && it.canScale(it.resolveLoggedServing(diaryEntry.serving)) }
                    val food = catalogFood ?: diaryEntry.food
                    val catalogServings = catalogFood?.servings ?: emptyList()

                    // Match existing serving against catalog servings
                    val selectedServing = catalogFood?.resolveLoggedServing(diaryEntry.serving) ?: diaryEntry.serving
                    val availableServings = if (selectedServing in catalogServings) {
                        catalogServings
                    } else if (catalogServings.isNotEmpty()) {
                        listOf(selectedServing) + catalogServings
                    } else {
                        listOf(selectedServing)
                    }

                    val initialQty = diaryEntry.quantity
                    val initialQtyText = formatQuantityForInput(initialQty)
                    val rawCalculated = diaryEntry.calculatedNutrition
                    val catalogNutrition = catalogFood?.nutrition
                    // Only null means "not recorded"; a logged 0.0 is a real zero and stays (BUG-037)
                    fun missing(logged: Double?, catalog: Double?) = logged == null && (catalog ?: 0.0) > 0.0
                    val hasMissingSecondaryNutrients = catalogNutrition != null && (
                        missing(rawCalculated.fiberGrams, catalogNutrition.fiberGrams) ||
                        missing(rawCalculated.sugarGrams, catalogNutrition.sugarGrams) ||
                        missing(rawCalculated.sodiumMg, catalogNutrition.sodiumMg) ||
                        missing(rawCalculated.saturatedFatGrams, catalogNutrition.saturatedFatGrams) ||
                        missing(rawCalculated.transFatGrams, catalogNutrition.transFatGrams) ||
                        missing(rawCalculated.cholesterolMg, catalogNutrition.cholesterolMg)
                    )
                    // Fill in only the secondary nutrients older versions did not record; calories
                    // and macros stay the logged snapshot even if the food changed since (AGENTS.md 1.4).
                    // Each nutrient follows the same rule as the check above, never a catalog 0.0.
                    val calculated = if (hasMissingSecondaryNutrients) {
                        val current = calculateNutritionForServingUseCase(food, selectedServing, initialQty)
                        fun fill(logged: Double?, catalog: Double?, recalculated: Double?) =
                            if (missing(logged, catalog)) recalculated else logged
                        rawCalculated.copy(
                            fiberGrams = fill(rawCalculated.fiberGrams, catalogNutrition?.fiberGrams, current.fiberGrams),
                            sugarGrams = fill(rawCalculated.sugarGrams, catalogNutrition?.sugarGrams, current.sugarGrams),
                            sodiumMg = fill(rawCalculated.sodiumMg, catalogNutrition?.sodiumMg, current.sodiumMg),
                            saturatedFatGrams = fill(rawCalculated.saturatedFatGrams, catalogNutrition?.saturatedFatGrams, current.saturatedFatGrams),
                            transFatGrams = fill(rawCalculated.transFatGrams, catalogNutrition?.transFatGrams, current.transFatGrams),
                            cholesterolMg = fill(rawCalculated.cholesterolMg, catalogNutrition?.cholesterolMg, current.cholesterolMg)
                        )
                    } else {
                        rawCalculated
                    }
                    val split = MacroCalorieSplit.fromNutrition(calculated)
                    val targetDate = diaryEntry.date
                    val targetMealType = diaryEntry.mealType

                    _uiState.update {
                        it.copy(
                            food = food,
                            availableServings = availableServings,
                            selectedServing = selectedServing,
                            enteredQuantity = initialQty,
                            quantityInputText = initialQtyText,
                            calculatedNutrition = calculated,
                            macroCalorieSplit = split,
                            targetMealType = targetMealType,
                            targetDate = targetDate,
                            isLoading = false,
                            errorMessage = null,
                            // An entry an older version logged with 0 or less: Save waits for a real amount
                            quantityError = loadedQuantityError(initialQty)
                        )
                    }
                    return@launch
                } else {
                    // Nothing to keep, so a recreated screen tries again
                    openedWith = null
                    _uiState.update { it.copy(food = null, isLoading = false, errorMessage = "Diary entry not found") }
                    return@launch
                }
            }

            // 2. Otherwise NEW FOOD or BASKET ITEM MODE
            // If editing basket item, load its state
            val basketItem = editingBasketItemId?.let { id -> getBasketItemsUseCase().value.firstOrNull { it.id == id } }

            // A basket item keeps the food it was added with (possibly a diary snapshot, e.g. a
            // copied recipe entry). Use a looked-up food only if it is that same food.
            val lookedUp = getFoodDetailsUseCase(foodId)
            val item = if (basketItem != null) {
                lookedUp?.takeIf { it.isSameFoodAs(basketItem.food) && it.canScale(basketItem.serving) } ?: basketItem.food
            } else {
                lookedUp
            }

            if (item == null) {
                openedWith = null
                _uiState.update { it.copy(food = null, isLoading = false, errorMessage = "Food record not found") }
                return@launch
            }

            val servings = if (item.servings.isNotEmpty()) {
                item.servings
            } else {
                listOf(Serving(description = "100 g", gramWeight = 100.0, isDefault = true))
            }

            val defaultServing = basketItem?.serving ?: (servings.firstOrNull { it.isDefault } ?: servings.first())
            val initialQty = basketItem?.quantity ?: 1.0
            
            val calculated = calculateNutritionForServingUseCase(item, defaultServing, initialQty)
            val split = MacroCalorieSplit.fromNutrition(calculated)
            
            // Opened without a day (Track, the widget, a dashboard on today): it logs to today
            dateFollowsToday = basketItem == null && date == null
            loadedEntry = null
            val targetDate = basketItem?.date ?: (date ?: LocalDate.now(clock))
            val targetMealType = basketItem?.mealType ?: (mealType ?: MealType.BREAKFAST)

            _uiState.update {
                it.copy(
                    food = item,
                    availableServings = servings,
                    selectedServing = defaultServing,
                    enteredQuantity = initialQty,
                    quantityInputText = formatQuantityForInput(initialQty),
                    calculatedNutrition = calculated,
                    macroCalorieSplit = split,
                    targetMealType = targetMealType,
                    targetDate = targetDate,
                    isLoading = false,
                    errorMessage = null,
                    quantityError = loadedQuantityError(initialQty)
                )
            }
        }
    }

    private fun loadedQuantityError(quantity: Double): String? =
        if (quantity.isFinite() && quantity > 0.0) null else QUANTITY_INPUT_ERROR

    private fun saveCurrentStateToBasket() {
        val currentId = editingBasketItemId ?: return
        val state = _uiState.value
        val food = state.food ?: return
        val serving = state.selectedServing ?: return
        val existingItem = getBasketItemsUseCase().value.firstOrNull { it.id == currentId }
        if (existingItem != null) {
            val updatedItem = existingItem.copy(
                quantity = state.enteredQuantity,
                serving = serving,
                calculatedNutrition = state.calculatedNutrition,
                date = state.targetDate,
                mealType = state.targetMealType
            )
            updateBasketItemUseCase(updatedItem)
        }
    }

    fun onQuantityChange(newQuantityText: String) {
        val food = _uiState.value.food ?: return
        val serving = _uiState.value.selectedServing ?: return
        val qty = parsePositiveQuantity(newQuantityText)
        if (qty == null) {
            // Keep the last valid amount and its numbers; Log/Save stay disabled until it is fixed
            _uiState.update { it.copy(quantityInputText = newQuantityText, quantityError = QUANTITY_INPUT_ERROR) }
            return
        }

        val calculated = calculateNutritionForServingUseCase(food, serving, qty)
        val split = MacroCalorieSplit.fromNutrition(calculated)

        _uiState.update {
            it.copy(
                enteredQuantity = qty,
                quantityInputText = newQuantityText,
                calculatedNutrition = calculated,
                macroCalorieSplit = split,
                quantityError = null
            )
        }
        saveCurrentStateToBasket()
    }

    fun onServingSelected(serving: Serving) {
        val food = _uiState.value.food ?: return
        val qty = _uiState.value.enteredQuantity

        val calculated = calculateNutritionForServingUseCase(food, serving, qty)
        val split = MacroCalorieSplit.fromNutrition(calculated)

        _uiState.update {
            it.copy(
                selectedServing = serving,
                calculatedNutrition = calculated,
                macroCalorieSplit = split
            )
        }
        saveCurrentStateToBasket()
    }

    fun onMealTypeSelected(mealType: MealType) {
        _uiState.update { it.copy(targetMealType = mealType) }
        saveCurrentStateToBasket()
    }
    
    fun onDateSelected(date: LocalDate) {
        dateFollowsToday = false
        _uiState.update { it.copy(targetDate = date) }
        saveCurrentStateToBasket()
    }

    fun logFood(onSuccess: () -> Unit) {
        refreshImplicitDate()
        val state = _uiState.value
        val food = state.food ?: return
        val serving = state.selectedServing ?: return
        if (!startAction()) return

        viewModelScope.launch {
            val currentEditingEntryId = editingEntryId
            val currentBasketItemId = editingBasketItemId
            val result: Result<Unit> = if (currentEditingEntryId != null && currentEditingEntryId > 0L) {
                runCatching {
                    val existingEntry = getDiaryEntryUseCase(currentEditingEntryId)
                    val entry = DiaryEntry(
                        id = currentEditingEntryId,
                        uuid = existingEntry?.uuid ?: UUID.randomUUID().toString(),
                        date = state.targetDate,
                        mealType = state.targetMealType,
                        food = food,
                        serving = serving,
                        quantity = state.enteredQuantity,
                        calculatedNutrition = state.calculatedNutrition,
                        loggedAt = existingEntry?.loggedAt ?: Instant.now()
                    )
                    updateDiaryEntryUseCase(entry)
                }
            } else if (currentBasketItemId != null) {
                saveCurrentStateToBasket()
                commitSingleBasketItemUseCase(currentBasketItemId)
            } else {
                // New logs pass through the basket (AGENTS.md section 5), then commit at once
                val newBasketItemId = addFoodToBasketUseCase(
                    food = food,
                    serving = serving,
                    quantity = state.enteredQuantity,
                    calculatedNutrition = state.calculatedNutrition,
                    date = state.targetDate,
                    mealType = state.targetMealType
                )
                commitSingleBasketItemUseCase(newBasketItemId).onFailure {
                    // Not logged: take the staged copy back out, so a retry cannot leave a duplicate behind
                    removeBasketItemUseCase(newBasketItemId)
                }
            }

            result.onSuccess {
                _uiState.update { it.copy(isSavedSuccess = true) }
                onSuccess()
            }.onFailure { error ->
                actionFailed(
                    when {
                        error is BasketItemMissingException -> error.message ?: "Nothing was logged."
                        currentEditingEntryId != null -> "Couldn't save this entry. Please try again."
                        currentBasketItemId != null -> "Couldn't log this food. It is still in your basket."
                        else -> "Couldn't log this food. Please try again."
                    }
                )
            }
        }
    }
    
    fun keepInBasket(onSuccess: () -> Unit) {
        refreshImplicitDate()
        val state = _uiState.value
        val food = state.food ?: return
        val serving = state.selectedServing ?: return
        if (!startAction()) return
        val currentBasketItemId = editingBasketItemId
        if (currentBasketItemId == null) {
            addFoodToBasketUseCase(
                food = food,
                serving = serving,
                quantity = state.enteredQuantity,
                calculatedNutrition = state.calculatedNutrition,
                date = state.targetDate,
                mealType = state.targetMealType
            )
        } else {
            if (getBasketItemsUseCase().value.none { it.id == currentBasketItemId }) {
                actionFailed(BasketItemMissingException().message ?: "This item is no longer in the basket.")
                return
            }
            saveCurrentStateToBasket()
        }
        _uiState.update { it.copy(isSavedSuccess = true) }
        onSuccess()
    }

    fun removeCurrentBasketItem(onSuccess: () -> Unit) {
        val itemId = editingBasketItemId ?: return
        if (!startAction(requiresValidQuantity = false)) return
        removeBasketItemUseCase(itemId)
        onSuccess()
    }

    fun deleteEntry(onSuccess: () -> Unit) {
        val id = editingEntryId ?: return
        if (!startAction(requiresValidQuantity = false)) return
        viewModelScope.launch {
            runCatching { deleteDiaryEntryUseCase(id) }
                .onSuccess { onSuccess() }
                .onFailure { actionFailed("Couldn't delete this entry. Please try again.") }
        }
    }

    fun copyEntry(onCopied: (foodId: Long, mealType: MealType, dateEpochDay: Long, basketItemId: String) -> Unit) {
        val state = _uiState.value
        val food = state.food ?: return
        val serving = state.selectedServing ?: return
        // Only from a diary entry, and once: onCopied reloads this screen for the new basket item,
        // which leaves diary-edit mode, so a second tap does nothing (BUG-011)
        if (!isEditingDiary() || actionInFlight || state.quantityError != null) return

        val newBasketItemId = addFoodToBasketUseCase(
            food = food,
            serving = serving,
            quantity = state.enteredQuantity,
            calculatedNutrition = state.calculatedNutrition,
            date = state.targetDate,
            mealType = state.targetMealType
        )
        onCopied(food.id, state.targetMealType, state.targetDate.toEpochDay(), newBasketItemId)
    }

    fun isEditingDiary(): Boolean {
        val currentId = editingEntryId
        return currentId != null && currentId > 0L
    }
    fun isEditingBasket(): Boolean = editingBasketItemId != null
}

/** "Built-in", "Custom" or "Recipe": where a food comes from. */
internal fun foodSourceLabel(food: Food): String = when {
    food.source == FoodSource.RECIPE -> "Recipe"
    food.isUserOwned || food.source == FoodSource.CUSTOM_USER -> "Custom"
    else -> "Built-in"
}

/** The amount after a stepper tap: [delta] (0.5) added, never below 0, rounded to 2 decimals. */
internal fun steppedQuantity(current: Double, delta: Double): Double =
    (kotlin.math.round((current + delta) * 100.0) / 100.0).coerceAtLeast(0.0)

/** Total grams of [quantity] of [serving], or null when the serving has no weight. */
internal fun servingGrams(serving: Serving?, quantity: Double): Double? {
    if (serving == null || serving.gramWeight <= 0.0 || serving.quantity <= 0.0) return null
    return serving.gramWeight / serving.quantity * quantity
}

/** "150 g total", "37.5 g total". */
internal fun gramsTotalLabel(grams: Double): String {
    val rounded = kotlin.math.round(grams * 10.0) / 10.0
    val text = if (rounded % 1.0 == 0.0) String.format(Locale.US, "%.0f", rounded) else String.format(Locale.US, "%.1f", rounded)
    return "$text g total"
}

/** Whole-percent share of the daily calorie target, or null when there is no target. */
internal fun percentOfDailyTarget(calories: Double, dailyGoal: Double): Int? =
    if (dailyGoal > 0.0) ((calories / dailyGoal) * 100.0).roundToInt() else null

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FoodDetailScreen(
    foodId: Long,
    mealType: MealType?,
    date: LocalDate?,
    entryId: Long?,
    basketItemId: String?,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val viewModel: FoodDetailViewModel = org.koin.androidx.compose.koinViewModel()
    val uiState by viewModel.uiState.collectAsState()
    val dailyCalorieGoal by viewModel.dailyCalorieGoal.collectAsState()

    BackHandler(onBack = onNavigateBack)

    LaunchedEffect(foodId, entryId, basketItemId) {
        viewModel.openFood(foodId, mealType, date, entryId, basketItemId)
    }

    // A food opened without a day moves to the new day after midnight (BUG-040)
    androidx.lifecycle.compose.LifecycleResumeEffect(viewModel) {
        viewModel.onResumed()
        onPauseOrDispose { }
    }

    if (uiState.isLoading) {
        Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = AppColors.Primary)
        }
        return
    }

    val food = uiState.food
    if (food == null) {
        Column(
            modifier = modifier
                .fillMaxSize()
        ) {
            MbHeader(title = "Food detail", onBack = onNavigateBack, applyStatusBarInset = false)
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(AppSpacing.xl),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    MbIcon(icon = Ph.WarningCircle.light, contentDescription = "Error", size = 48.dp, tint = AppColors.Error)
                    Spacer(modifier = Modifier.height(AppSpacing.md))
                    Text(
                        text = uiState.errorMessage ?: "Food item not found",
                        style = AppTypography.Header3,
                        color = AppColors.TextPrimary,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(AppSpacing.lg))
                    PrimaryButton(
                        text = "Back to Search",
                        onClick = onNavigateBack
                    )
                }
            }
        }
        return
    }

    val isDiaryEdit = viewModel.isEditingDiary()
    val isBasketEdit = viewModel.isEditingBasket()
    val canSave = !uiState.isSaving && uiState.quantityError == null
    val nutrition = uiState.calculatedNutrition
    val today = LocalDate.now()
    val scrollState = rememberScrollState()

    Column(
        modifier = modifier
            .fillMaxSize()
    ) {
        MbHeader(
            title = if (isDiaryEdit) "Edit entry" else "Food detail",
            onBack = onNavigateBack,
            scrolled = scrollState.isScrolled,
            applyStatusBarInset = false
        )

        Box(modifier = Modifier.weight(1f)) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(scrollState)
                    .padding(horizontal = AppSpacing.screenMargin)
            ) {
                // Hero: the food, its calories and macros
                Row(
                    modifier = Modifier.padding(top = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    MbIconCircle(icon = Ph.BowlFood.light, size = 56.dp, iconSize = 26.dp)
                    Column(modifier = Modifier.weight(1f)) {
                        Text(text = food.name, style = AppTypography.Header1.copy(fontSize = 20.sp, lineHeight = 26.sp), color = AppColors.TextPrimary)
                        Text(text = foodSourceLabel(food), style = AppTypography.Body2, color = AppColors.TextSecondary)
                    }
                }
                Row(modifier = Modifier.padding(top = 18.dp)) {
                    Text(
                        text = "${nutrition.calories.roundToInt()}",
                        style = AppTypography.Hero,
                        color = AppColors.TextPrimary,
                        modifier = Modifier.alignByBaseline()
                    )
                    Text(
                        text = "kcal",
                        style = AppTypography.HeroUnit,
                        modifier = Modifier
                            .alignByBaseline()
                            .padding(start = 8.dp)
                    )
                }
                val percent = percentOfDailyTarget(nutrition.calories, dailyCalorieGoal)
                if (percent != null && uiState.quantityError == null) {
                    Text(
                        text = "$percent% of your daily target",
                        style = AppTypography.Body2.copy(fontSize = 13.sp),
                        color = AppColors.TextSecondary,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
                MacroTiles(
                    protein = nutrition.proteinGrams,
                    carbs = nutrition.carbsGrams,
                    fat = nutrition.fatGrams,
                    modifier = Modifier.padding(top = 16.dp)
                )

                // Amount: the stepper with a typed amount, then the serving
                DetailSection("Amount") {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        QuantityStepper(
                            text = uiState.quantityInputText,
                            isError = uiState.quantityError != null,
                            onTextChange = { viewModel.onQuantityChange(it) },
                            onStep = { delta ->
                                val current = parseQuantityInput(uiState.quantityInputText) ?: 0.0
                                viewModel.onQuantityChange(formatQuantityForInput(steppedQuantity(current, delta)))
                            }
                        )
                        if (uiState.quantityError == null) {
                            servingGrams(uiState.selectedServing, uiState.enteredQuantity)?.let { grams ->
                                Text(text = gramsTotalLabel(grams), style = AppTypography.Body2.copy(fontSize = 13.sp), color = AppColors.TextSecondary)
                            }
                        }
                    }
                    uiState.quantityError?.let { error ->
                        Text(error, style = AppTypography.Body2.copy(fontSize = 13.sp), color = AppColors.Error, modifier = Modifier.padding(top = 6.dp))
                    }
                    ServingChoice(
                        food = food,
                        servings = uiState.availableServings,
                        selected = uiState.selectedServing,
                        onSelect = { viewModel.onServingSelected(it) },
                        modifier = Modifier.padding(top = 12.dp)
                    )
                }

                // When: the meal and day it logs to
                DetailSection("When") {
                    MealAndDayPicker(
                        selectedMeal = uiState.targetMealType,
                        selectedDate = uiState.targetDate,
                        today = today,
                        onMealSelected = { viewModel.onMealTypeSelected(it) },
                        onDateSelected = { viewModel.onDateSelected(it) },
                        header = "Log to ${mealLabel(uiState.targetMealType)}, ${basketDateLabel(uiState.targetDate, today)}"
                    )
                }

                DetailSection("Nutrition facts") {
                    NutritionFactsPanel(nutrition)
                    Text(
                        text = "A dash means the food doesn't state that value.",
                        style = AppTypography.Body2.copy(fontSize = 12.5.sp),
                        color = AppColors.TextMuted,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }

                // Room for the pinned action bar
                Spacer(modifier = Modifier.height(112.dp))
            }

            // The action bar, pinned at the bottom over a short fade
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(Brush.verticalGradient(0f to Color.Transparent, 0.18f to AppColors.Background, 1f to AppColors.Background))
                    .padding(start = AppSpacing.screenMargin, end = AppSpacing.screenMargin, top = 16.dp, bottom = 12.dp)
            ) {
                uiState.actionError?.let { error ->
                    Text(
                        error,
                        style = AppTypography.Body2,
                        color = AppColors.Error,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 8.dp)
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    when {
                        isDiaryEdit -> {
                            MbDangerGhostButton(
                                onClick = { viewModel.deleteEntry { onNavigateBack() } },
                                contentDescription = "Delete entry",
                                enabled = !uiState.isSaving
                            )
                            MbButton(
                                text = "Copy",
                                kind = MbButtonKind.Secondary,
                                icon = Ph.Copy.light,
                                enabled = canSave,
                                onClick = {
                                    viewModel.copyEntry { copiedFoodId, copiedMealType, copiedDateEpochDay, newBasketItemId ->
                                        viewModel.loadFood(
                                            foodId = copiedFoodId,
                                            mealType = copiedMealType,
                                            date = LocalDate.ofEpochDay(copiedDateEpochDay),
                                            entryId = 0L,
                                            basketItemId = newBasketItemId
                                        )
                                    }
                                }
                            )
                            MbButton(
                                text = "Save changes",
                                onClick = { viewModel.logFood { onNavigateBack() } },
                                enabled = canSave,
                                modifier = Modifier.weight(1f)
                            )
                        }
                        else -> {
                            if (isBasketEdit) {
                                MbDangerGhostButton(
                                    onClick = { viewModel.removeCurrentBasketItem { onNavigateBack() } },
                                    contentDescription = "Remove from basket",
                                    enabled = !uiState.isSaving
                                )
                            }
                            MbButton(
                                text = "Keep in basket",
                                kind = MbButtonKind.Secondary,
                                enabled = canSave,
                                onClick = { viewModel.keepInBasket { onNavigateBack() } }
                            )
                            MbButton(
                                text = "Log 1 food",
                                onClick = { viewModel.logFood { onNavigateBack() } },
                                enabled = canSave,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }
        }
    }
}

/** A titled block of Food Detail ("Amount", "When", "Nutrition facts"). */
@Composable
private fun DetailSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().padding(top = AppSpacing.sectionGap)) {
        Text(
            text = title,
            style = AppTypography.Header2,
            color = AppColors.TextPrimary,
            modifier = Modifier
                .padding(bottom = 10.dp)
                .semantics { heading() }
        )
        content()
    }
}

/** Protein, carbs and fat as three small tiles. */
@Composable
internal fun MacroTiles(protein: Double, carbs: Double, fat: Double, modifier: Modifier = Modifier) {
    Row(modifier = modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        MacroTile("Protein", protein, AppColors.MacroProtein, Modifier.weight(1f))
        MacroTile("Carbs", carbs, AppColors.MacroCarbs, Modifier.weight(1f))
        MacroTile("Fat", fat, AppColors.MacroFat, Modifier.weight(1f))
    }
}

@Composable
private fun MacroTile(label: String, grams: Double, color: Color, modifier: Modifier) {
    MbPanel(
        modifier = modifier,
        shape = AppShapes.CardSmall,
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(modifier = Modifier.size(7.dp).clip(CircleShape).background(color))
            Text(text = label, style = AppTypography.Caption, color = AppColors.TextSecondary)
        }
        Row(modifier = Modifier.padding(top = 4.dp)) {
            Text(
                text = String.format(Locale.US, "%.1f", grams),
                style = AppTypography.Header2,
                color = AppColors.TextPrimary,
                modifier = Modifier.alignByBaseline()
            )
            Text(
                text = " g",
                style = AppTypography.Caption,
                color = AppColors.TextSecondary,
                modifier = Modifier.alignByBaseline()
            )
        }
    }
}

/** The pill stepper: minus, the typed amount, plus. A step is 0.5. */
@Composable
internal fun QuantityStepper(
    text: String,
    isError: Boolean,
    onTextChange: (String) -> Unit,
    onStep: (Double) -> Unit,
    modifier: Modifier = Modifier
) {
    val shape = CircleShape
    Row(
        modifier = modifier
            .clip(shape)
            .background(AppColors.FieldFill)
            .border(1.dp, if (isError) AppColors.Error else AppColors.FieldBorder, shape)
            .padding(2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        MbIconButton(
            icon = Ph.Minus.light,
            contentDescription = "Decrease amount",
            onClick = { onStep(-0.5) },
            tint = AppColors.TextPrimary
        )
        BasicTextField(
            value = text,
            onValueChange = onTextChange,
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            textStyle = AppTypography.Header1.copy(color = AppColors.TextPrimary, textAlign = TextAlign.Center),
            cursorBrush = SolidColor(AppColors.Primary),
            modifier = Modifier
                .width(60.dp)
                .semantics { contentDescription = "Amount" }
        )
        MbIconButton(
            icon = Ph.Plus.light,
            contentDescription = "Increase amount",
            onClick = { onStep(0.5) },
            tint = AppColors.TextPrimary
        )
    }
}

/** The serving as chips when there are up to 6, otherwise a dropdown. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ServingChoice(
    food: Food,
    servings: List<Serving>,
    selected: Serving?,
    onSelect: (Serving) -> Unit,
    modifier: Modifier = Modifier
) {
    if (servings.size <= 6) {
        FlowRow(
            modifier = modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            servings.forEach { serving ->
                MbChip(text = food.servingLabel(serving), selected = serving == selected, onClick = { onSelect(serving) })
            }
        }
    } else {
        var expanded by remember { mutableStateOf(false) }
        Box(modifier = modifier) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .clip(AppShapes.InputBox)
                    .background(AppColors.FieldFill)
                    .border(1.dp, AppColors.FieldBorder, AppShapes.InputBox)
                    .mbClickable(pressedScale = 0.98f, onClickLabel = "Choose a serving") { expanded = true }
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = selected?.let { food.servingLabel(it) } ?: "100 g",
                    style = AppTypography.Body1,
                    color = AppColors.TextPrimary,
                    modifier = Modifier.weight(1f)
                )
                MbIcon(icon = Ph.CaretDown.light, contentDescription = null, size = 16.dp, tint = AppColors.TextSecondary)
            }
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                modifier = Modifier.background(AppColors.SheetFill)
            ) {
                servings.forEach { serving ->
                    DropdownMenuItem(
                        text = { Text(food.servingLabel(serving), color = AppColors.TextPrimary) },
                        onClick = {
                            onSelect(serving)
                            expanded = false
                        }
                    )
                }
            }
        }
    }
}

/** The full nutrition list in a panel with hairline rows; a value the food doesn't state shows "-". */
@Composable
private fun NutritionFactsPanel(nutrition: Nutrition) {
    MbPanel(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp)
    ) {
        NutritionRow("Calories", nutrition.calories, "kcal", true)
        FactsDivider()
        NutritionRow("Total Fat", nutrition.fatGrams, "g", true)
        NutritionRow("Saturated Fat", nutrition.saturatedFatGrams, "g", false)
        NutritionRow("Trans Fat", nutrition.transFatGrams, "g", false)
        FactsDivider()
        NutritionRow("Cholesterol", nutrition.cholesterolMg, "mg", true)
        FactsDivider()
        NutritionRow("Sodium", nutrition.sodiumMg, "mg", true)
        FactsDivider()
        NutritionRow("Total Carbohydrate", nutrition.carbsGrams, "g", true)
        NutritionRow("Dietary Fiber", nutrition.fiberGrams, "g", false)
        NutritionRow("Total Sugars", nutrition.sugarGrams, "g", false)
        FactsDivider()
        NutritionRow("Protein", nutrition.proteinGrams, "g", true)
    }
}

@Composable
private fun FactsDivider() {
    HorizontalDivider(color = AppColors.Divider, thickness = 1.dp)
}

@Composable
private fun NutritionRow(label: String, value: Double?, unit: String, isMain: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 9.dp).padding(start = if (isMain) 0.dp else AppSpacing.md),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = if (isMain) AppTypography.Body1 else AppTypography.Body2, color = if (isMain) AppColors.TextPrimary else AppColors.TextSecondary)
        val valueStr = if (value != null) String.format(Locale.US, "%.1f %s", value, unit) else "-"
        Text(valueStr, style = AppTypography.Body1, color = AppColors.TextPrimary)
    }
}
