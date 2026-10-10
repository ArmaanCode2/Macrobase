package com.macrobase.app.feature.detail

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
    private val getDiaryEntryUseCase: com.macrobase.app.domain.usecase.GetDiaryEntryUseCase
) : ViewModel() {

    private val _uiState = MutableStateFlow(FoodDetailUiState())
    val uiState: StateFlow<FoodDetailUiState> = _uiState.asStateFlow()

    private var editingEntryId: Long? = null
    private var editingBasketItemId: String? = null

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
            
            val targetDate = basketItem?.date ?: (date ?: LocalDate.now())
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
        _uiState.update { it.copy(targetDate = date) }
        saveCurrentStateToBasket()
    }

    fun logFood(onSuccess: () -> Unit) {
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

    BackHandler(onBack = onNavigateBack)

    LaunchedEffect(foodId, entryId, basketItemId) {
        viewModel.loadFood(foodId, mealType, date, entryId, basketItemId)
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
                .background(AppColors.Background)
        ) {
            // Top Header Bar matching reference
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .background(AppColors.Primary)
                    .padding(horizontal = AppSpacing.xs),
                verticalAlignment = Alignment.CenterVertically
            ) {
                androidx.compose.material3.IconButton(onClick = onNavigateBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = AppColors.TextPrimary
                    )
                }
                Spacer(modifier = Modifier.width(AppSpacing.xs))
                Text(
                    text = "Food Detail",
                    style = AppTypography.Header2.copy(color = AppColors.TextPrimary)
                )
            }
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
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = "Error",
                        tint = AppColors.AlertRed,
                        modifier = Modifier.size(48.dp)
                    )
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

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(AppColors.Background)
    ) {
        // Top Header Bar matching reference
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .background(AppColors.Primary)
                .padding(horizontal = AppSpacing.xs),
            verticalAlignment = Alignment.CenterVertically
        ) {
            androidx.compose.material3.IconButton(onClick = onNavigateBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = AppColors.TextPrimary
                )
            }
            Spacer(modifier = Modifier.width(AppSpacing.xs))
            Text(
                text = if (viewModel.isEditingDiary()) "Edit Food" else "Food Detail",
                style = AppTypography.Header2.copy(color = AppColors.TextPrimary)
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
        ) {
        // Food Header Section
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = AppSpacing.sm, vertical = AppSpacing.sm),
            verticalAlignment = Alignment.Top
        ) {
            // Icon
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .border(1.dp, AppColors.Divider, RoundedCornerShape(4.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.Fastfood, contentDescription = null, tint = AppColors.TextPrimary, modifier = Modifier.size(24.dp))
            }
            
            Spacer(modifier = Modifier.width(AppSpacing.sm))
            
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Qty Input
                    androidx.compose.foundation.text.BasicTextField(
                        value = uiState.quantityInputText,
                        onValueChange = { viewModel.onQuantityChange(it) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        textStyle = AppTypography.Body1.copy(color = AppColors.TextPrimary, textAlign = androidx.compose.ui.text.style.TextAlign.Center),
                        singleLine = true,
                        decorationBox = { innerTextField ->
                            Box(
                                modifier = Modifier
                                    .size(width = 48.dp, height = 36.dp)
                                    .border(1.dp, if (uiState.quantityError != null) AppColors.AlertRed else AppColors.Divider, RoundedCornerShape(2.dp))
                                    .padding(vertical = 6.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                innerTextField()
                            }
                        }
                    )
                    Spacer(modifier = Modifier.width(AppSpacing.sm))
                    
                    // Serving Dropdown
                    var dropdownExpanded by remember { mutableStateOf(false) }
                    Box(modifier = Modifier.weight(1f).clickable { dropdownExpanded = true }) {
                        Text(
                            text = uiState.selectedServing?.let { food.servingLabel(it) } ?: "100 g",
                            style = AppTypography.Body1,
                            color = AppColors.TextPrimary
                        )
                        DropdownMenu(
                            expanded = dropdownExpanded,
                            onDismissRequest = { dropdownExpanded = false },
                            modifier = Modifier.background(AppColors.SurfaceAlt)
                        ) {
                            uiState.availableServings.forEach { serving ->
                                DropdownMenuItem(
                                    text = { Text(food.servingLabel(serving), color = AppColors.TextPrimary) },
                                    onClick = {
                                        viewModel.onServingSelected(serving)
                                        dropdownExpanded = false
                                    }
                                )
                            }
                        }
                    }
                    
                    // Right Info
                    Icon(androidx.compose.material.icons.Icons.Default.Info, contentDescription = "Info", tint = AppColors.TextSecondary, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(AppSpacing.sm))
                    Column(horizontalAlignment = Alignment.End) {
                        Text(String.format("%.0f", uiState.calculatedNutrition.calories), style = AppTypography.Header3, color = AppColors.CalorieText)
                        Text("cal", style = AppTypography.Caption, color = AppColors.TextSecondary)
                    }
                }
                
                uiState.quantityError?.let { error ->
                    Text(error, style = AppTypography.Caption, color = AppColors.AlertRed)
                }
                Spacer(modifier = Modifier.height(4.dp))
                // Food Name
                Text(food.name, style = AppTypography.Body1, color = AppColors.TextPrimary)
            }
        }
        
        Spacer(modifier = Modifier.height(AppSpacing.md))
        HorizontalDivider(color = AppColors.Divider, thickness = 1.dp)

        // Calorie Section
        Row(
            modifier = Modifier.fillMaxWidth().background(AppColors.Surface).padding(horizontal = AppSpacing.sm, vertical = AppSpacing.sm),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Total Calories", style = AppTypography.Header3, color = AppColors.TextPrimary)
            Text(String.format("%.0f", uiState.calculatedNutrition.calories), style = AppTypography.Header3, color = AppColors.CalorieText)
        }
        HorizontalDivider(color = AppColors.Divider, thickness = 1.dp)
        
        // Macro Row
        Row(
            modifier = Modifier.fillMaxWidth().background(AppColors.Surface).padding(vertical = AppSpacing.xs),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            MacroValue(label = "Protein", value = uiState.calculatedNutrition.proteinGrams, color = AppColors.MacroProtein)
            Box(modifier = Modifier.size(width = 1.dp, height = 16.dp).background(AppColors.Divider))
            MacroValue(label = "Carb", value = uiState.calculatedNutrition.carbsGrams, color = AppColors.MacroCarbs)
            Box(modifier = Modifier.size(width = 1.dp, height = 16.dp).background(AppColors.Divider))
            MacroValue(label = "Fat", value = uiState.calculatedNutrition.fatGrams, color = AppColors.MacroFat)
        }
        HorizontalDivider(color = AppColors.Divider, thickness = 1.dp)

        Spacer(modifier = Modifier.height(AppSpacing.md))

        // When Section
        var isEditingWhen by remember { mutableStateOf(true) }
        Row(
            modifier = Modifier.fillMaxWidth().clickable { isEditingWhen = !isEditingWhen }.padding(horizontal = AppSpacing.sm, vertical = AppSpacing.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            val formatter = java.time.format.DateTimeFormatter.ofPattern("EEE, MM/dd/yyyy")
            val today = LocalDate.now()
            val yesterday = today.minusDays(1)
            val dateLabel = when (uiState.targetDate) {
                today -> "Today"
                yesterday -> "Yesterday"
                else -> uiState.targetDate.format(formatter)
            }
            Text("When: ${dateLabel}, ${uiState.targetMealType.displayName}", style = AppTypography.Header3, color = AppColors.TextPrimary)
            Icon(Icons.Default.Edit, contentDescription = "Edit When", tint = AppColors.TextPrimary, modifier = Modifier.size(20.dp))
        }

        if (isEditingWhen) {
            Column(
                modifier = Modifier.fillMaxWidth().border(1.dp, AppColors.Divider)
            ) {
                val meals = MealType.entries
                Row(modifier = Modifier.fillMaxWidth()) {
                    MealShortcutButton(meals[0], uiState.targetMealType, Modifier.weight(1f)) { viewModel.onMealTypeSelected(it) }
                    Box(modifier = Modifier.size(width = 1.dp, height = 48.dp).background(AppColors.Divider))
                    MealShortcutButton(meals[1], uiState.targetMealType, Modifier.weight(1f)) { viewModel.onMealTypeSelected(it) }
                    Box(modifier = Modifier.size(width = 1.dp, height = 48.dp).background(AppColors.Divider))
                    MealShortcutButton(meals[2], uiState.targetMealType, Modifier.weight(1f)) { viewModel.onMealTypeSelected(it) }
                }
                HorizontalDivider(color = AppColors.Divider, thickness = 1.dp)
                Row(modifier = Modifier.fillMaxWidth()) {
                    MealShortcutButton(meals[3], uiState.targetMealType, Modifier.weight(1f)) { viewModel.onMealTypeSelected(it) }
                    Box(modifier = Modifier.size(width = 1.dp, height = 48.dp).background(AppColors.Divider))
                    MealShortcutButton(meals[4], uiState.targetMealType, Modifier.weight(1f)) { viewModel.onMealTypeSelected(it) }
                    Box(modifier = Modifier.size(width = 1.dp, height = 48.dp).background(AppColors.Divider))
                    MealShortcutButton(meals[5], uiState.targetMealType, Modifier.weight(1f)) { viewModel.onMealTypeSelected(it) }
                }
            }

            Spacer(modifier = Modifier.height(AppSpacing.md))

            Row(modifier = Modifier.fillMaxWidth().border(1.dp, AppColors.Divider)) {
                val today = LocalDate.now()
                val yesterday = today.minusDays(1)
                
                DateShortcutButton("Yesterday", yesterday, uiState.targetDate, Modifier.weight(1f)) { viewModel.onDateSelected(yesterday) }
                Box(modifier = Modifier.size(width = 1.dp, height = 48.dp).background(AppColors.Divider))
                DateShortcutButton("Today", today, uiState.targetDate, Modifier.weight(1f)) { viewModel.onDateSelected(today) }
                Box(modifier = Modifier.size(width = 1.dp, height = 48.dp).background(AppColors.Divider))
                
                var showDatePicker by remember { mutableStateOf(false) }
                val isCustomDate = uiState.targetDate != today && uiState.targetDate != yesterday
                
                TextButton(
                    onClick = { showDatePicker = true },
                    modifier = Modifier.weight(1f).height(48.dp),
                    shape = RoundedCornerShape(0.dp),
                    colors = ButtonDefaults.textButtonColors(containerColor = if (isCustomDate) AppColors.SurfaceAlt else AppColors.Surface)
                ) {
                    Text("Choose a day", style = AppTypography.Body2, color = if (isCustomDate) AppColors.Primary else AppColors.TextPrimary)
                }
                
                if (showDatePicker) {
                    val initialMillis = uiState.targetDate.atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli()
                    @OptIn(ExperimentalMaterial3Api::class)
                    val datePickerState = rememberDatePickerState(initialSelectedDateMillis = initialMillis)
                    
                    @OptIn(ExperimentalMaterial3Api::class)
                    DatePickerDialog(
                        onDismissRequest = { showDatePicker = false },
                        confirmButton = {
                            TextButton(onClick = {
                                datePickerState.selectedDateMillis?.let { millis ->
                                    val selectedDate = java.time.Instant.ofEpochMilli(millis).atZone(java.time.ZoneOffset.UTC).toLocalDate()
                                    viewModel.onDateSelected(selectedDate)
                                }
                                showDatePicker = false
                            }) { Text("Confirm", color = AppColors.Primary) }
                        },
                        dismissButton = {
                            TextButton(onClick = { showDatePicker = false }) { Text("Cancel", color = AppColors.Primary) }
                        },
                        colors = DatePickerDefaults.colors(containerColor = AppColors.Surface)
                    ) {
                        DatePicker(
                            state = datePickerState,
                            colors = DatePickerDefaults.colors(
                                titleContentColor = AppColors.TextPrimary,
                                headlineContentColor = AppColors.TextPrimary,
                                weekdayContentColor = AppColors.TextSecondary,
                                subheadContentColor = AppColors.TextSecondary,
                                yearContentColor = AppColors.TextPrimary,
                                currentYearContentColor = AppColors.Primary,
                                selectedYearContentColor = AppColors.TextPrimary,
                                selectedYearContainerColor = AppColors.Primary,
                                dayContentColor = AppColors.TextPrimary,
                                disabledDayContentColor = AppColors.TextDisabled,
                                selectedDayContentColor = AppColors.TextPrimary,
                                selectedDayContainerColor = AppColors.Primary,
                                todayDateBorderColor = AppColors.Primary,
                                todayContentColor = AppColors.Primary
                            )
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(AppSpacing.md))
        HorizontalDivider(color = AppColors.Divider, thickness = 1.dp)

        // Photo Row (Mocked / Unavailable but visually matched)
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = AppSpacing.sm, vertical = AppSpacing.sm),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(androidx.compose.material.icons.Icons.Default.CameraAlt, contentDescription = null, tint = AppColors.TextPrimary, modifier = Modifier.size(20.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("Photo", style = AppTypography.Body1, color = AppColors.TextPrimary)
                Icon(androidx.compose.material.icons.Icons.Default.ChevronRight, contentDescription = null, tint = AppColors.TextPrimary, modifier = Modifier.size(20.dp))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Add Photo:", style = AppTypography.Body2, color = AppColors.TextSecondary)
                Spacer(modifier = Modifier.width(AppSpacing.xs))
                Icon(androidx.compose.material.icons.Icons.Default.CameraAlt, contentDescription = null, tint = AppColors.TextPrimary, modifier = Modifier.size(20.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Icon(androidx.compose.material.icons.Icons.Default.Image, contentDescription = null, tint = AppColors.TextPrimary, modifier = Modifier.size(20.dp))
            }
        }
        
        HorizontalDivider(color = AppColors.Divider, thickness = 1.dp)
        Spacer(modifier = Modifier.height(AppSpacing.xs))

        if (viewModel.isEditingDiary()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = AppSpacing.sm, vertical = AppSpacing.xs),
                horizontalArrangement = Arrangement.spacedBy(AppSpacing.sm)
            ) {
                // Copy Button
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(44.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(AppColors.SurfaceAlt)
                        .border(1.dp, AppColors.Divider, RoundedCornerShape(4.dp))
                        .clickable(enabled = !uiState.isSaving && uiState.quantityError == null) {
                            viewModel.copyEntry { copiedFoodId, copiedMealType, copiedDateEpochDay, newBasketItemId ->
                                viewModel.loadFood(
                                    foodId = copiedFoodId,
                                    mealType = copiedMealType,
                                    date = LocalDate.ofEpochDay(copiedDateEpochDay),
                                    entryId = 0L,
                                    basketItemId = newBasketItemId
                                )
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.ContentCopy,
                            contentDescription = "Copy",
                            tint = AppColors.TextPrimary,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Copy",
                            style = AppTypography.Body1.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Medium, color = AppColors.TextPrimary)
                        )
                    }
                }

                // Delete Button
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(44.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(AppColors.SurfaceAlt)
                        .border(1.dp, AppColors.Divider, RoundedCornerShape(4.dp))
                        .clickable(enabled = !uiState.isSaving) {
                            viewModel.deleteEntry { onNavigateBack() }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = androidx.compose.material.icons.Icons.Default.Delete,
                            contentDescription = "Delete",
                            tint = AppColors.TextPrimary,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Delete",
                            style = AppTypography.Body1.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Medium, color = AppColors.TextPrimary)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(AppSpacing.xs))
            HorizontalDivider(color = AppColors.Divider, thickness = 1.dp)
        }

        // Primary Action Button
        val logLabel = if (viewModel.isEditingDiary()) "SAVE ENTRY" else "Log 1 Food"
        val canSave = !uiState.isSaving && uiState.quantityError == null
        uiState.actionError?.let { error ->
            Text(
                error,
                style = AppTypography.Body2,
                color = AppColors.AlertRed,
                modifier = Modifier.fillMaxWidth().padding(horizontal = AppSpacing.sm, vertical = AppSpacing.xs)
            )
        }
        Button(
            onClick = { viewModel.logFood { onNavigateBack() } },
            enabled = canSave,
            modifier = Modifier.fillMaxWidth().height(48.dp).padding(horizontal = AppSpacing.xs),
            shape = RoundedCornerShape(2.dp),
            colors = ButtonDefaults.buttonColors(containerColor = AppColors.Primary)
        ) {
            Text(logLabel, style = AppTypography.Header3, color = AppColors.TextPrimary)
        }

        if (!viewModel.isEditingDiary()) {
            Spacer(modifier = Modifier.height(AppSpacing.sm))
            Box(modifier = Modifier.fillMaxWidth().clickable(enabled = canSave) { viewModel.keepInBasket { onNavigateBack() } }, contentAlignment = Alignment.Center) {
                Text("Keep in Basket", style = AppTypography.Body1, color = AppColors.TextSecondary)
            }
        }

        Spacer(modifier = Modifier.height(AppSpacing.md))

        // Lower Links
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = AppSpacing.sm),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("Report a problem", style = AppTypography.Caption, color = AppColors.TextSecondary)
            Text("Report nutrition discrepancy", style = AppTypography.Caption, color = AppColors.TextSecondary)
        }
        
        Spacer(modifier = Modifier.height(AppSpacing.sm))
        
        if (viewModel.isEditingBasket()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = !uiState.isSaving) { viewModel.removeCurrentBasketItem { onNavigateBack() } }
                    .padding(vertical = AppSpacing.sm),
                contentAlignment = Alignment.Center
            ) {
                Text("Remove from Basket", style = AppTypography.Body1, color = Color(0xFFE53935))
            }
            Spacer(modifier = Modifier.height(AppSpacing.md))
        } else if (viewModel.isEditingDiary()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = !uiState.isSaving) { viewModel.deleteEntry { onNavigateBack() } }
                    .padding(vertical = AppSpacing.sm),
                contentAlignment = Alignment.Center
            ) {
                Text("Delete Entry", style = AppTypography.Body1, color = Color(0xFFE53935))
            }
            Spacer(modifier = Modifier.height(AppSpacing.md))
        }
        
        HorizontalDivider(color = AppColors.Divider, thickness = 1.dp)

        // Nutrition Facts
        Column(
            modifier = Modifier.fillMaxWidth().background(AppColors.Surface).padding(AppSpacing.sm)
        ) {
            Text("Nutrition Facts", style = AppTypography.Header2, color = AppColors.TextPrimary)
            Spacer(modifier = Modifier.height(AppSpacing.sm))
            
            NutritionRow("Calories", uiState.calculatedNutrition.calories, "kcal", true)
            HorizontalDivider(color = AppColors.Divider, thickness = 1.dp)
            NutritionRow("Total Fat", uiState.calculatedNutrition.fatGrams, "g", true)
            NutritionRow("Saturated Fat", uiState.calculatedNutrition.saturatedFatGrams, "g", false)
            NutritionRow("Trans Fat", uiState.calculatedNutrition.transFatGrams, "g", false)
            HorizontalDivider(color = AppColors.Divider, thickness = 1.dp)
            NutritionRow("Cholesterol", uiState.calculatedNutrition.cholesterolMg, "mg", true)
            HorizontalDivider(color = AppColors.Divider, thickness = 1.dp)
            NutritionRow("Sodium", uiState.calculatedNutrition.sodiumMg, "mg", true)
            HorizontalDivider(color = AppColors.Divider, thickness = 1.dp)
            NutritionRow("Total Carbohydrate", uiState.calculatedNutrition.carbsGrams, "g", true)
            NutritionRow("Dietary Fiber", uiState.calculatedNutrition.fiberGrams, "g", false)
            NutritionRow("Total Sugars", uiState.calculatedNutrition.sugarGrams, "g", false)
            HorizontalDivider(color = AppColors.Divider, thickness = 1.dp)
            NutritionRow("Protein", uiState.calculatedNutrition.proteinGrams, "g", true)
        }
        
        Spacer(modifier = Modifier.height(AppSpacing.xxl))
        }
    }
}

@Composable
private fun MacroValue(label: String, value: Double, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(String.format("%.0fg", value), style = AppTypography.Body2, color = color)
        Spacer(modifier = Modifier.width(4.dp))
        Text(label, style = AppTypography.Caption, color = AppColors.TextSecondary)
    }
}

@Composable
private fun MealShortcutButton(
    meal: MealType,
    selected: MealType,
    modifier: Modifier = Modifier,
    isPlaceholder: Boolean = false,
    onClick: (MealType) -> Unit
) {
    if (isPlaceholder) {
        Box(modifier = modifier.height(48.dp).background(AppColors.Surface))
        return
    }
    val isSelected = meal == selected
    TextButton(
        onClick = { onClick(meal) },
        modifier = modifier.height(48.dp),
        shape = RoundedCornerShape(0.dp),
        colors = ButtonDefaults.textButtonColors(containerColor = if (isSelected) AppColors.SurfaceAlt else AppColors.Surface)
    ) {
        Text(meal.displayName, style = AppTypography.Body2, color = if (isSelected) AppColors.TextPrimary else AppColors.TextSecondary)
    }
}

@Composable
private fun DateShortcutButton(
    label: String,
    date: LocalDate,
    selected: LocalDate,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val isSelected = date == selected
    TextButton(
        onClick = onClick,
        modifier = modifier.height(48.dp),
        shape = RoundedCornerShape(0.dp),
        colors = ButtonDefaults.textButtonColors(containerColor = if (isSelected) AppColors.SurfaceAlt else AppColors.Surface)
    ) {
        Text(label, style = AppTypography.Body2, color = if (isSelected) AppColors.Primary else AppColors.TextSecondary)
    }
}

@Composable
private fun NutritionRow(label: String, value: Double?, unit: String, isMain: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = AppSpacing.xs, horizontal = if (isMain) 0.dp else AppSpacing.md),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = if (isMain) AppTypography.Body1 else AppTypography.Body2, color = AppColors.TextPrimary)
        val valueStr = if (value != null) String.format("%.1f %s", value, unit) else "-"
        Text(valueStr, style = AppTypography.Body1, color = AppColors.TextPrimary)
    }
}
