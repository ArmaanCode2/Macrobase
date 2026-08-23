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
import com.macrobase.app.domain.usecase.basket.CommitSingleBasketItemUseCase
import com.macrobase.app.domain.model.basket.BasketItem
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

class FoodDetailViewModel(
    private val getFoodDetailsUseCase: GetFoodDetailsUseCase,
    private val calculateNutritionForServingUseCase: CalculateNutritionForServingUseCase,
    private val addFoodToBasketUseCase: AddFoodToBasketUseCase,
    private val getBasketItemsUseCase: GetBasketItemsUseCase,
    private val updateBasketItemUseCase: UpdateBasketItemUseCase,
    private val commitSingleBasketItemUseCase: CommitSingleBasketItemUseCase,
    private val updateDiaryEntryUseCase: UpdateDiaryEntryUseCase,
    private val deleteDiaryEntryUseCase: DeleteDiaryEntryUseCase,
    private val getDiaryEntryUseCase: com.macrobase.app.domain.usecase.GetDiaryEntryUseCase
) : ViewModel() {

    private val _uiState = MutableStateFlow(FoodDetailUiState())
    val uiState: StateFlow<FoodDetailUiState> = _uiState.asStateFlow()

    private var editingEntryId: Long? = null
    private var editingBasketItemId: String? = null

    fun loadFood(foodId: Long, mealType: MealType? = null, date: LocalDate? = null, entryId: Long? = null, basketItemId: String? = null) {
        editingEntryId = if (entryId != null && entryId > 0L) entryId else null
        editingBasketItemId = basketItemId
        
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }

            // 1. Check if editing an existing diary entry (EDIT MODE)
            if (editingEntryId != null && editingEntryId!! > 0L) {
                val diaryEntry = getDiaryEntryUseCase(editingEntryId!!)
                if (diaryEntry != null) {
                    val catalogFood = getFoodDetailsUseCase(diaryEntry.food.id) ?: getFoodDetailsUseCase(foodId)
                    val food = catalogFood ?: diaryEntry.food
                    val catalogServings = catalogFood?.servings ?: emptyList()

                    // Match existing serving against catalog servings
                    val matchedServing = catalogServings.firstOrNull {
                        it.description.equals(diaryEntry.serving.description, ignoreCase = true)
                    } ?: if (diaryEntry.serving.gramWeight > 0.0) {
                        catalogServings.firstOrNull {
                            it.gramWeight > 0.0 && kotlin.math.abs(it.gramWeight - diaryEntry.serving.gramWeight) < 0.001
                        }
                    } else null

                    val selectedServing = matchedServing ?: diaryEntry.serving
                    val availableServings = if (catalogServings.any { it.description.equals(selectedServing.description, ignoreCase = true) }) {
                        catalogServings
                    } else if (catalogServings.isNotEmpty()) {
                        listOf(selectedServing) + catalogServings
                    } else {
                        listOf(selectedServing)
                    }

                    val initialQty = diaryEntry.quantity
                    val initialQtyText = if (initialQty == initialQty.toLong().toDouble()) initialQty.toLong().toString() else initialQty.toString()
                    val calculated = diaryEntry.calculatedNutrition
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
                            errorMessage = null
                        )
                    }
                    return@launch
                }
            }

            // 2. Otherwise NEW FOOD or BASKET ITEM MODE
            val item = getFoodDetailsUseCase(foodId)

            if (item == null) {
                _uiState.update { it.copy(isLoading = false, errorMessage = "Food record not found") }
                return@launch
            }

            val servings = if (item.servings.isNotEmpty()) {
                item.servings
            } else {
                listOf(Serving(description = "100 g", gramWeight = 100.0, isDefault = true))
            }

            // If editing basket item, load its state
            val basketItem = editingBasketItemId?.let { id -> getBasketItemsUseCase().value.firstOrNull { it.id == id } }
            
            val defaultServing = basketItem?.serving ?: (servings.firstOrNull { it.isDefault } ?: servings.first())
            val initialQty = basketItem?.quantity ?: 1.0
            
            val calculated = calculateNutritionForServingUseCase(item, defaultServing, initialQty)
            val split = MacroCalorieSplit.fromNutrition(calculated)
            
            val targetDate = basketItem?.date ?: (date ?: LocalDate.now())
            val targetMealType = basketItem?.mealType ?: (mealType ?: MealType.BREAKFAST)

            if (editingBasketItemId == null && editingEntryId == null) {
                val newId = addFoodToBasketUseCase(
                    food = item,
                    serving = defaultServing,
                    quantity = initialQty,
                    calculatedNutrition = calculated,
                    date = targetDate,
                    mealType = targetMealType
                )
                editingBasketItemId = newId
            }

            _uiState.update {
                it.copy(
                    food = item,
                    availableServings = servings,
                    selectedServing = defaultServing,
                    enteredQuantity = initialQty,
                    quantityInputText = if (initialQty == initialQty.toLong().toDouble()) initialQty.toLong().toString() else initialQty.toString(),
                    calculatedNutrition = calculated,
                    macroCalorieSplit = split,
                    targetMealType = targetMealType,
                    targetDate = targetDate,
                    isLoading = false,
                    errorMessage = null
                )
            }
        }
    }

    private fun saveCurrentStateToBasket() {
        if (editingBasketItemId != null) {
            val state = _uiState.value
            val food = state.food ?: return
            val serving = state.selectedServing ?: return
            val existingItem = getBasketItemsUseCase().value.firstOrNull { it.id == editingBasketItemId }
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
    }

    fun onQuantityChange(newQuantityText: String) {
        val qty = newQuantityText.toDoubleOrNull() ?: 0.0
        val food = _uiState.value.food ?: return
        val serving = _uiState.value.selectedServing ?: return

        val calculated = calculateNutritionForServingUseCase(food, serving, qty)
        val split = MacroCalorieSplit.fromNutrition(calculated)

        _uiState.update {
            it.copy(
                enteredQuantity = qty,
                quantityInputText = newQuantityText,
                calculatedNutrition = calculated,
                macroCalorieSplit = split
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

        viewModelScope.launch {
            if (editingEntryId != null && editingEntryId!! > 0L) {
                val entry = DiaryEntry(
                    id = editingEntryId ?: 0L,
                    uuid = UUID.randomUUID().toString(),
                    date = state.targetDate,
                    mealType = state.targetMealType,
                    food = food,
                    serving = serving,
                    quantity = state.enteredQuantity,
                    calculatedNutrition = state.calculatedNutrition,
                    loggedAt = Instant.now()
                )
                updateDiaryEntryUseCase(entry)
            } else if (editingBasketItemId != null) {
                saveCurrentStateToBasket()
                commitSingleBasketItemUseCase(editingBasketItemId!!)
            }

            _uiState.update { it.copy(isSavedSuccess = true) }
            onSuccess()
        }
    }
    
    fun keepInBasket(onSuccess: () -> Unit) {
        saveCurrentStateToBasket()
        _uiState.update { it.copy(isSavedSuccess = true) }
        onSuccess()
    }

    fun deleteEntry(onSuccess: () -> Unit) {
        val id = editingEntryId ?: return
        viewModelScope.launch {
            deleteDiaryEntryUseCase(id)
            onSuccess()
        }
    }

    fun copyEntry(onCopied: (foodId: Long, mealType: MealType, dateEpochDay: Long, basketItemId: String) -> Unit) {
        val state = _uiState.value
        val food = state.food ?: return
        val serving = state.selectedServing ?: return

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

    fun isEditingDiary(): Boolean = editingEntryId != null && editingEntryId!! > 0L
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

    LaunchedEffect(foodId, entryId, basketItemId) {
        viewModel.loadFood(foodId, mealType, date, entryId, basketItemId)
    }

    if (uiState.isLoading) {
        Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = AppColors.Primary)
        }
        return
    }

    val food = uiState.food ?: return

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
        // Swipe to delete hint (mock)
        Box(
            modifier = Modifier.fillMaxWidth().background(AppColors.Surface).padding(vertical = 4.dp),
            contentAlignment = Alignment.Center
        ) {
            Text("swipe left to delete", style = AppTypography.Caption, color = AppColors.TextSecondary)
        }
        
        HorizontalDivider(color = AppColors.Divider, thickness = 1.dp)

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
                                    .border(1.dp, AppColors.Divider, RoundedCornerShape(2.dp))
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
                            text = uiState.selectedServing?.description ?: "100 g",
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
                                    text = { Text(serving.description, color = AppColors.TextPrimary) },
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
                        .clickable {
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
                        .clickable {
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
        Button(
            onClick = { viewModel.logFood { onNavigateBack() } },
            modifier = Modifier.fillMaxWidth().height(48.dp).padding(horizontal = AppSpacing.xs),
            shape = RoundedCornerShape(2.dp),
            colors = ButtonDefaults.buttonColors(containerColor = AppColors.Primary)
        ) {
            Text(logLabel, style = AppTypography.Header3, color = AppColors.TextPrimary)
        }

        if (!viewModel.isEditingDiary()) {
            Spacer(modifier = Modifier.height(AppSpacing.sm))
            Box(modifier = Modifier.fillMaxWidth().clickable { viewModel.keepInBasket { onNavigateBack() } }, contentAlignment = Alignment.Center) {
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
        
        if (!viewModel.isEditingDiary()) {
            Box(modifier = Modifier.fillMaxWidth().clickable { viewModel.deleteEntry { onNavigateBack() } }, contentAlignment = Alignment.Center) {
                Text("Clear Basket", style = AppTypography.Body1, color = Color(0xFFE53935))
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
