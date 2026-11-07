package com.macrobase.app.feature.customfoods

import com.macrobase.app.core.designsystem.components.WriteFailedDialog
import com.macrobase.app.core.util.SafeWrites
import com.macrobase.app.core.designsystem.components.MbDelayedProgress
import com.macrobase.app.core.navigation.ReportContentScrolled
import com.macrobase.app.core.designsystem.components.rememberIsScrolled
import com.macrobase.app.core.designsystem.components.mbClickable
import com.macrobase.app.core.designsystem.components.mbSearchPillFrame
import com.macrobase.app.core.designsystem.components.MbScanButton
import com.macrobase.app.core.designsystem.components.MbFilledPlusButton
import com.macrobase.app.core.designsystem.components.MbTextField
import com.macrobase.app.core.designsystem.components.MbButtonKind
import com.macrobase.app.core.designsystem.components.MbConfirmDialog
import com.macrobase.app.core.designsystem.components.MbButton
import com.macrobase.app.core.designsystem.components.MbPanel
import com.macrobase.app.core.designsystem.components.MbRowTile
import com.macrobase.app.core.designsystem.components.MbIconButton
import com.macrobase.app.core.designsystem.components.MbIcon
import com.macrobase.app.core.designsystem.Ph
import androidx.compose.ui.unit.sp
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Fastfood
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import com.macrobase.app.core.navigation.LocalDockInset
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
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
import androidx.activity.compose.BackHandler
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.macrobase.app.core.designsystem.AppColors
import com.macrobase.app.core.designsystem.AppShapes
import com.macrobase.app.core.designsystem.AppSpacing
import com.macrobase.app.core.designsystem.AppTypography
import com.macrobase.app.core.designsystem.Dimensions
import com.macrobase.app.core.designsystem.components.EmptyStateCard
import com.macrobase.app.core.designsystem.components.PrimaryButton
import com.macrobase.app.domain.model.CustomFood
import com.macrobase.app.domain.model.Food
import com.macrobase.app.core.util.PendingDelete
import com.macrobase.app.core.util.hasThousandsSeparator
import com.macrobase.app.core.util.parseDecimalInput
import com.macrobase.app.feature.detail.formatQuantityForInput
import kotlin.math.roundToInt
import com.macrobase.app.domain.model.Nutrition
import com.macrobase.app.domain.model.ServingUnit
import com.macrobase.app.domain.model.scanner.NutritionLabelDraft
import com.macrobase.app.domain.repository.FoodRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID

data class CustomFoodsListUiState(
    val isLoading: Boolean = true,
    val allCustomFoods: List<Food> = emptyList(),
    val filteredFoods: List<Food> = emptyList(),
    val searchQuery: String = ""
)

class CustomFoodsViewModel(
    private val foodRepository: FoodRepository,
    private val savedState: SavedStateHandle = SavedStateHandle()
) : ViewModel() {

    /** The edit screen's form; see [CustomFoodForm]. */
    val form: StateFlow<CustomFoodForm> = savedState.getStateFlow(FORM_KEY, CustomFoodForm())

    private val _existingFood = MutableStateFlow<CustomFood?>(null)
    /** The stored food the form edits; null for a new one, and until it has loaded. */
    val existingFood: StateFlow<CustomFood?> = _existingFood.asStateFlow()

    fun updateForm(change: (CustomFoodForm) -> CustomFoodForm) {
        savedState[FORM_KEY] = change(form.value)
    }

    /** Loads the food to edit, once. A form brought back after the app was closed keeps what was typed. */
    fun openForEdit(foodId: Long) {
        if (foodId <= 0L || _existingFood.value != null) return
        viewModelScope.launch {
            val found = foodRepository.getCustomFood(foodId) ?: return@launch
            _existingFood.value = found
            if (form.value == CustomFoodForm()) savedState[FORM_KEY] = CustomFoodForm.from(found)
        }
    }

    /** True when leaving now would lose something the user entered. */
    fun hasUnsavedChanges(): Boolean = form.value != (_existingFood.value?.let { CustomFoodForm.from(it) } ?: CustomFoodForm())

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _scannedDraft = MutableStateFlow<NutritionLabelDraft?>(null)
    val scannedDraft: StateFlow<NutritionLabelDraft?> = _scannedDraft.asStateFlow()

    val customFoods: StateFlow<List<Food>> = foodRepository.observeCustomFoods()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val uiState: StateFlow<CustomFoodsListUiState> = combine(
        foodRepository.observeCustomFoods(),
        _searchQuery
    ) { foods, query ->
        val trimmed = query.trim().lowercase()
        val filtered = if (trimmed.isEmpty()) {
            foods
        } else {
            foods.filter {
                it.name.lowercase().contains(trimmed) || (it.brand?.lowercase()?.contains(trimmed) == true)
            }
        }
        CustomFoodsListUiState(
            isLoading = false,
            allCustomFoods = foods,
            filteredFoods = filtered,
            searchQuery = query
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = CustomFoodsListUiState(isLoading = true)
    )

    /** Fills the form. The draft stays for the review banner until the form is saved or it is dismissed. */
    fun setScannedDraft(draft: NutritionLabelDraft) {
        _scannedDraft.value = draft
        updateForm { it.withDraft(draft) }
    }

    fun clearScannedDraft() {
        _scannedDraft.value = null
    }

    fun onSearchQueryChange(query: String) {
        _searchQuery.value = query
    }

    fun saveCustomFood(
        id: Long = 0L,
        uuid: String = UUID.randomUUID().toString(),
        name: String,
        brand: String? = null,
        servingSize: Double,
        servingUnit: ServingUnit,
        customUnitName: String? = null,
        calories: Double,
        protein: Double,
        carbs: Double,
        fat: Double,
        saturatedFat: Double? = null,
        fiber: Double? = null,
        sugar: Double? = null,
        sodium: Double? = null,
        potassium: Double? = null,
        calcium: Double? = null,
        iron: Double? = null,
        onComplete: () -> Unit
    ) {
        // A second Save tap must not create the food twice (each call gets a new uuid, BUG-011)
        if (saveInFlight) return
        saveInFlight = true
        // A failed save keeps the form and allows a retry, with a message (BUG-022)
        writes.launch(onSaved = onComplete, onFailed = { saveInFlight = false }) {
            val customFood = CustomFood(
                id = id,
                uuid = uuid,
                name = name.trim(),
                brand = brand?.trim()?.ifBlank { null },
                servingSize = servingSize,
                servingUnit = servingUnit,
                customUnitName = if (servingUnit == ServingUnit.CUSTOM) customUnitName?.trim()?.ifBlank { null } else null,
                nutritionPerServing = Nutrition(
                    calories = calories,
                    proteinGrams = protein,
                    carbsGrams = carbs,
                    fatGrams = fat,
                    saturatedFatGrams = saturatedFat,
                    fiberGrams = fiber,
                    sugarGrams = sugar,
                    sodiumMg = sodium,
                    potassiumMg = potassium,
                    calciumMg = calcium,
                    ironMg = iron
                )
            )
            foodRepository.saveCustomFood(customFood)
        }
    }

    private var saveInFlight = false

    /** Saves and deletes, and the message of the last one that failed (BUG-022). */
    val writes = SafeWrites(viewModelScope)

    /** The food a delete button asked about, by row id. */
    val pendingDelete = PendingDelete<Long>()

    fun confirmDelete(onComplete: () -> Unit = {}) {
        pendingDelete.confirm()?.let { deleteCustomFood(it, onComplete) }
    }

    fun deleteCustomFood(id: Long, onComplete: () -> Unit) {
        writes.launch(onSaved = onComplete) { foodRepository.deleteCustomFood(id) }
    }

    private companion object {
        const val FORM_KEY = "custom_food_form"
    }
}

@Composable
fun CustomFoodsScreen(
    viewModel: CustomFoodsViewModel,
    onCreateClick: () -> Unit,
    onEditClick: (Long) -> Unit,
    onFoodClick: (Long) -> Unit,
    modifier: Modifier = Modifier,
    onScanClick: () -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsState()
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    val listState = rememberLazyListState()
    ReportContentScrolled(listState.rememberIsScrolled())
    ConfirmDeleteCustomFood(viewModel)
    WriteFailedDialog(viewModel.writes)

    Column(
        modifier = modifier
            .fillMaxSize()
            .clickable(
                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                indication = null
            ) { focusManager.clearFocus() }
    ) {
        // Search, with Create and Scan inside the bar
        CustomFoodsSearchBar(
            query = uiState.searchQuery,
            onQueryChange = { viewModel.onSearchQueryChange(it) },
            onClear = {
                viewModel.onSearchQueryChange("")
                focusManager.clearFocus()
            },
            onSearchAction = { focusManager.clearFocus() },
            onCreate = {
                focusManager.clearFocus()
                onCreateClick()
            },
            onScan = {
                focusManager.clearFocus()
                onScanClick()
            },
            modifier = Modifier.padding(horizontal = AppSpacing.screenMargin, vertical = 8.dp)
        )

        if (uiState.isLoading) {
            // A spinner only if the list takes a while, so a quick load never flashes one
            MbDelayedProgress(modifier = Modifier.fillMaxSize())
        } else if (uiState.allCustomFoods.isEmpty()) {
            EmptyStateCard(
                icon = Icons.Default.Fastfood,
                title = "No Custom Foods",
                subtitle = "Create and save your custom ingredients or foods to easily search and log them anytime.",
                actionButtonText = "+ Create Custom Food",
                onActionClick = onCreateClick,
                modifier = Modifier.padding(top = AppSpacing.xl)
            )
        } else if (uiState.filteredFoods.isEmpty()) {
            EmptyStateCard(
                icon = Icons.Default.Search,
                title = "No Matches Found",
                subtitle = "No custom foods match '${uiState.searchQuery}'.",
                modifier = Modifier.padding(top = AppSpacing.xl)
            )
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = AppSpacing.screenMargin,
                    end = AppSpacing.screenMargin,
                    top = 4.dp,
                    bottom = LocalDockInset.current + AppSpacing.md
                ),
                verticalArrangement = Arrangement.spacedBy(AppSpacing.rowGap)
            ) {
                item(key = "count") {
                    Text(
                        text = "Custom foods (${uiState.filteredFoods.size})",
                        style = AppTypography.Caption,
                        color = AppColors.TextMuted,
                        modifier = Modifier.padding(bottom = 2.dp)
                    )
                }
                items(uiState.filteredFoods, key = { it.id }) { item ->
                    CustomFoodRow(
                        food = item,
                        onClick = { onFoodClick(item.id) },
                        onEdit = { onEditClick(item.id) },
                        onDelete = { viewModel.pendingDelete.ask(item.id, item.name) }
                    )
                }
            }
        }
    }
}

/** Asks before deleting what [CustomFoodsViewModel.pendingDelete] holds; [onDeleted] runs once it is gone. */
@Composable
private fun ConfirmDeleteCustomFood(viewModel: CustomFoodsViewModel, onDeleted: () -> Unit = {}) {
    val request by viewModel.pendingDelete.request.collectAsState()
    request?.let {
        MbConfirmDialog(
            title = "Delete ${it.label}?",
            text = "This cannot be undone. Entries already in your diary stay as they are.",
            confirmText = "Delete",
            onConfirm = { viewModel.confirmDelete(onDeleted) },
            onDismiss = { viewModel.pendingDelete.cancel() }
        )
    }
}

/**
 * C14 The custom foods search: a real input with the name-or-brand filter, its clear button, a filled
 * "+" that creates a custom food and an outlined scan button.
 */
@Composable
private fun CustomFoodsSearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    onClear: () -> Unit,
    onSearchAction: () -> Unit,
    onCreate: () -> Unit,
    onScan: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .mbSearchPillFrame(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        MbIcon(
            icon = Ph.MagnifyingGlass.light,
            contentDescription = null,
            size = 18.dp,
            tint = AppColors.TextMuted,
            modifier = Modifier.padding(start = 10.dp)
        )
        BasicTextField(
            value = query,
            onValueChange = onQueryChange,
            singleLine = true,
            textStyle = AppTypography.Body1.copy(color = AppColors.TextPrimary),
            cursorBrush = SolidColor(AppColors.Primary),
            keyboardOptions = KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Search),
            keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = { onSearchAction() }),
            modifier = Modifier
                .weight(1f)
                .padding(start = 4.dp)
                .semantics { contentDescription = "Search custom foods" },
            decorationBox = { innerTextField ->
                Box(modifier = Modifier.heightIn(min = 42.dp), contentAlignment = Alignment.CenterStart) {
                    if (query.isEmpty()) {
                        Text(
                            text = "Search custom foods\u2026",
                            style = AppTypography.Body1.copy(fontSize = 14.5.sp, fontWeight = FontWeight.Light),
                            color = AppColors.TextMuted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    innerTextField()
                }
            }
        )
        if (query.isNotEmpty()) {
            MbIconButton(icon = Ph.X.light, contentDescription = "Clear", onClick = onClear, size = 36.dp, iconSize = 16.dp)
        }
        MbFilledPlusButton(onClick = onCreate, contentDescription = "Create custom food")
        MbScanButton(onClick = onScan, contentDescription = "Scan a label")
    }
}

@Composable
fun CustomFoodRow(
    food: Food,
    onClick: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    MbRowTile(
        title = food.name,
        subtitle = food.listSubtitle,
        icon = Ph.ForkKnife.light,
        value = "${food.nutrition.calories.roundToInt()}",
        onClick = onClick,
        showChevron = false,
        modifier = modifier
    ) {
        MbIconButton(icon = Ph.PencilSimple.light, contentDescription = "Edit Custom Food", onClick = onEdit, size = 40.dp, iconSize = 18.dp)
        MbIconButton(icon = Ph.Trash.light, contentDescription = "Delete Custom Food", onClick = onDelete, size = 40.dp, iconSize = 18.dp)
    }
}

/** Protein, carbohydrates and fat have no "not stated", so a blank one saves as 0 and the form says so (BUG-013). */
internal fun blankMacroHelp(text: String): String? = if (text.isBlank()) "Left blank, this saves as 0 g" else null

/** A number typed into the custom food form: its value, or why it was refused. Blank is neither. */
internal data class FormNumber(val value: Double?, val error: String?)

/**
 * Reads one number field of the custom food form. "1,5" reads as 1.5, as comma keyboards type it.
 * NaN, infinity and negative values are refused, since a stored NaN would break every later
 * backup. A [largeAmount] field refuses "2,000", which means 2000 there, rather than save 2, unless
 * the app wrote that text itself ([writtenByApp]): "4.125" from a scan is 4.125 (BUG-036).
 */
internal fun readFormNumber(text: String, largeAmount: Boolean = false, writtenByApp: Boolean = false): FormNumber {
    val trimmed = text.trim()
    if (trimmed.isEmpty()) return FormNumber(null, null)
    // A leading 0 is never a thousands group: "0.125" is an eighth
    if (largeAmount && !writtenByApp && !trimmed.startsWith("0") && hasThousandsSeparator(trimmed)) {
        return FormNumber(null, "Leave out the thousands separator")
    }
    val number = parseDecimalInput(trimmed) ?: return FormNumber(null, "Enter a number")
    if (number < 0.0) return FormNumber(null, "Cannot be negative")
    return FormNumber(number, null)
}

@Composable
fun EditCustomFoodScreen(
    foodId: Long = 0L,
    viewModel: CustomFoodsViewModel,
    onScanLabelClick: () -> Unit = {},
    onSaveSuccess: () -> Unit,
    modifier: Modifier = Modifier
) {
    val form by viewModel.form.collectAsState()
    val existingFood by viewModel.existingFood.collectAsState()
    val scannedDraft by viewModel.scannedDraft.collectAsState()
    var showDiscardPrompt by remember { mutableStateOf(false) }

    LaunchedEffect(foodId) { viewModel.openForEdit(foodId) }
    ConfirmDeleteCustomFood(viewModel, onDeleted = onSaveSuccess)
    WriteFailedDialog(viewModel.writes)

    BackHandler(enabled = viewModel.hasUnsavedChanges()) { showDiscardPrompt = true }
    if (showDiscardPrompt) {
        MbConfirmDialog(
            title = "Discard this food?",
            text = "What you entered here has not been saved.",
            confirmText = "Discard",
            dismissText = "Keep editing",
            onConfirm = {
                showDiscardPrompt = false
                onSaveSuccess()
            },
            onDismiss = { showDiscardPrompt = false }
        )
    }

    val isEditing = foodId > 0L && existingFood != null
    val servingSizeInput = form.read(CustomFoodForm::servingSize, largeAmount = true)
    val caloriesInput = form.read(CustomFoodForm::calories, largeAmount = true)
    val proteinInput = readFormNumber(form.protein)
    val carbsInput = readFormNumber(form.carbs)
    val fatInput = readFormNumber(form.fat)
    // Saturated fat is part of total fat; more of it is a typo or a misread label
    val saturatedFatInput = readFormNumber(form.saturatedFat).let { input ->
        val value = input.value
        if (value != null && value > (fatInput.value ?: 0.0)) FormNumber(null, "More than total fat") else input
    }
    val fiberInput = readFormNumber(form.fiber)
    val sugarInput = readFormNumber(form.sugar)
    val sodiumInput = form.read(CustomFoodForm::sodium, largeAmount = true)
    val potassiumInput = form.read(CustomFoodForm::potassium, largeAmount = true)
    val calciumInput = form.read(CustomFoodForm::calcium, largeAmount = true)
    val ironInput = form.read(CustomFoodForm::iron)
    val numberInputs = listOf(
        servingSizeInput, caloriesInput, proteinInput, carbsInput, fatInput,
        saturatedFatInput, fiberInput, sugarInput, sodiumInput, potassiumInput, calciumInput, ironInput
    )
    val isCustomUnitValid = form.servingUnit != ServingUnit.CUSTOM || form.customUnitName.isNotBlank()
    val isFormValid = form.name.isNotBlank() && caloriesInput.value != null && (servingSizeInput.value ?: 0.0) > 0.0 &&
        numberInputs.none { it.error != null } && isCustomUnitValid

    val scrollState = rememberScrollState()
    ReportContentScrolled(scrollState.rememberIsScrolled())

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(horizontal = AppSpacing.screenMargin, vertical = AppSpacing.sm)
    ) {
        Text(text = if (isEditing) "Edit Custom Food" else "Create Custom Food", style = AppTypography.Header1)
        Spacer(modifier = Modifier.height(AppSpacing.lg))

        // Optional on-device scanner option (for new foods)
        if (!isEditing) {
            MbButton(
                text = "Scan Nutrition Label",
                onClick = onScanLabelClick,
                kind = MbButtonKind.Secondary,
                icon = Ph.Scan.light,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(AppSpacing.lg))
        }

        // Detected values review banner
        val draft = scannedDraft
        if (draft != null && !isEditing) {
            MbPanel(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    MbIcon(icon = Ph.Scan.light, contentDescription = null, size = 20.dp, tint = AppColors.CalorieText)
                    Spacer(modifier = Modifier.width(AppSpacing.sm))
                    Text(
                        text = "Values detected (${draft.detectedBasis.displayName})",
                        style = AppTypography.Header3,
                        color = AppColors.CalorieText,
                        modifier = Modifier.weight(1f)
                    )
                    MbIconButton(
                        icon = Ph.X.light,
                        contentDescription = "Dismiss",
                        onClick = { viewModel.clearScannedDraft() },
                        size = 36.dp,
                        iconSize = 16.dp
                    )
                }
                Spacer(modifier = Modifier.height(AppSpacing.xs))
                Text(
                    text = "Values detected from nutrition label. Please review before creating.",
                    style = AppTypography.Body2,
                    color = AppColors.TextSecondary
                )
                if (!draft.normalizationNote.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(AppSpacing.xs))
                    Text(
                        text = draft.normalizationNote!!,
                        style = AppTypography.Caption,
                        color = AppColors.Primary
                    )
                }
                // A value no food can have, or calories that do not match the macros (BUG-042)
                if (draft.criticalWarnings.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(AppSpacing.xs))
                    draft.criticalWarnings.forEach { warning ->
                        Text(
                            text = "\u2022 $warning",
                            style = AppTypography.Caption,
                            color = AppColors.Error
                        )
                    }
                }
                if (draft.warnings.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(AppSpacing.xs))
                    draft.warnings.forEach { warning ->
                        Text(
                            text = "\u2022 $warning",
                            style = AppTypography.Caption,
                            color = AppColors.Warning
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(AppSpacing.md))
        }

        FormSection("Required information") {
            MbTextField(
                value = form.name,
                onValueChange = { text -> viewModel.updateForm { it.copy(name = text) } },
                label = "Food Name *",
                modifier = Modifier.fillMaxWidth()
            )
            MbTextField(
                value = form.brand,
                onValueChange = { text -> viewModel.updateForm { it.copy(brand = text) } },
                label = "Brand / Origin (Optional)",
                modifier = Modifier.fillMaxWidth()
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(AppSpacing.md)
            ) {
                MbTextField(
                    value = form.servingSize,
                    onValueChange = { text -> viewModel.updateForm { it.copy(servingSize = text) } },
                    label = "Serving Size *",
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    errorText = servingSizeInput.error,
                    modifier = Modifier.weight(1f)
                )
                ServingUnitPicker(
                    selected = form.servingUnit,
                    onSelect = { unit -> viewModel.updateForm { it.copy(servingUnit = unit) } },
                    modifier = Modifier.weight(1.5f)
                )
            }
            if (form.servingUnit == ServingUnit.CUSTOM) {
                MbTextField(
                    value = form.customUnitName,
                    onValueChange = { text -> viewModel.updateForm { it.copy(customUnitName = text) } },
                    label = "Custom Unit Name * (e.g. roti, slice, bowl, scoop)",
                    modifier = Modifier.fillMaxWidth()
                )
            }
            MbTextField(
                value = form.calories,
                onValueChange = { text -> viewModel.updateForm { it.copy(calories = text) } },
                label = "Calories (kcal) *",
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                errorText = caloriesInput.error,
                modifier = Modifier.fillMaxWidth()
            )
        }

        FormSection("Macronutrients (per serving)") {
            MbTextField(
                value = form.protein,
                onValueChange = { text -> viewModel.updateForm { it.copy(protein = text) } },
                label = "Protein (g)",
                helpText = blankMacroHelp(form.protein),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                errorText = proteinInput.error,
                modifier = Modifier.fillMaxWidth()
            )
            MbTextField(
                value = form.carbs,
                onValueChange = { text -> viewModel.updateForm { it.copy(carbs = text) } },
                label = "Carbohydrates (g)",
                helpText = blankMacroHelp(form.carbs),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                errorText = carbsInput.error,
                modifier = Modifier.fillMaxWidth()
            )
            MbTextField(
                value = form.fat,
                onValueChange = { text -> viewModel.updateForm { it.copy(fat = text) } },
                label = "Fat (g)",
                helpText = blankMacroHelp(form.fat),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                errorText = fatInput.error,
                modifier = Modifier.fillMaxWidth()
            )
        }

        FormSection("Additional nutrients (optional)") {
            Text(
                text = "Saturated fat, fiber, sugar and sodium count toward your rank's food quality. " +
                    "A blank field counts as unknown.",
                style = AppTypography.Body2.copy(fontSize = 13.sp),
                color = AppColors.TextSecondary
            )
            MbTextField(
                value = form.saturatedFat,
                onValueChange = { text -> viewModel.updateForm { it.copy(saturatedFat = text) } },
                label = "Saturated Fat (g)",
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                errorText = saturatedFatInput.error,
                modifier = Modifier.fillMaxWidth()
            )
            MbTextField(
                value = form.fiber,
                onValueChange = { text -> viewModel.updateForm { it.copy(fiber = text) } },
                label = "Dietary Fiber (g)",
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                errorText = fiberInput.error,
                modifier = Modifier.fillMaxWidth()
            )
            MbTextField(
                value = form.sugar,
                onValueChange = { text -> viewModel.updateForm { it.copy(sugar = text) } },
                label = "Total Sugars (g)",
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                errorText = sugarInput.error,
                modifier = Modifier.fillMaxWidth()
            )
            MbTextField(
                value = form.sodium,
                onValueChange = { text -> viewModel.updateForm { it.copy(sodium = text) } },
                label = "Sodium (mg)",
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                errorText = sodiumInput.error,
                modifier = Modifier.fillMaxWidth()
            )
            // A scanned label's minerals land here, where they can be checked (BUG-040)
            MbTextField(
                value = form.potassium,
                onValueChange = { text -> viewModel.updateForm { it.copy(potassium = text) } },
                label = "Potassium (mg)",
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                errorText = potassiumInput.error,
                modifier = Modifier.fillMaxWidth()
            )
            MbTextField(
                value = form.calcium,
                onValueChange = { text -> viewModel.updateForm { it.copy(calcium = text) } },
                label = "Calcium (mg)",
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                errorText = calciumInput.error,
                modifier = Modifier.fillMaxWidth()
            )
            MbTextField(
                value = form.iron,
                onValueChange = { text -> viewModel.updateForm { it.copy(iron = text) } },
                label = "Iron (mg)",
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                errorText = ironInput.error,
                modifier = Modifier.fillMaxWidth()
            )
        }

        Spacer(modifier = Modifier.height(AppSpacing.xl))

        PrimaryButton(
            text = if (isEditing) "Update Custom Food" else "Save Custom Food",
            enabled = isFormValid,
            onClick = {
                viewModel.saveCustomFood(
                    id = existingFood?.id ?: 0L,
                    uuid = existingFood?.uuid ?: UUID.randomUUID().toString(),
                    name = form.name,
                    brand = form.brand,
                    servingSize = servingSizeInput.value ?: 1.0,
                    servingUnit = form.servingUnit,
                    customUnitName = if (form.servingUnit == ServingUnit.CUSTOM) form.customUnitName else null,
                    calories = caloriesInput.value ?: 0.0,
                    protein = proteinInput.value ?: 0.0,
                    carbs = carbsInput.value ?: 0.0,
                    fat = fatInput.value ?: 0.0,
                    saturatedFat = saturatedFatInput.value,
                    fiber = fiberInput.value,
                    sugar = sugarInput.value,
                    sodium = sodiumInput.value,
                    potassium = potassiumInput.value,
                    calcium = calciumInput.value,
                    iron = ironInput.value,
                    onComplete = {
                        viewModel.clearScannedDraft()
                        onSaveSuccess()
                    }
                )
            }
        )

        if (isEditing) {
            Spacer(modifier = Modifier.height(AppSpacing.md))
            MbButton(
                text = "Delete Custom Food",
                onClick = { existingFood?.let { viewModel.pendingDelete.ask(it.id, it.name) } },
                kind = MbButtonKind.Danger,
                icon = Ph.Trash.light,
                modifier = Modifier.fillMaxWidth()
            )
        }

        Spacer(modifier = Modifier.height(AppSpacing.xxl))
    }
}

/** A titled group of the custom food form. */
@Composable
private fun FormSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = AppSpacing.lg),
        verticalArrangement = Arrangement.spacedBy(AppSpacing.md)
    ) {
        Text(
            text = title,
            style = AppTypography.Header2,
            color = AppColors.TextPrimary,
            modifier = Modifier.semantics { heading() }
        )
        content()
    }
}

/** The serving unit, a field that opens the list of all 14 units ("Custom..." included). */
@Composable
private fun ServingUnitPicker(selected: ServingUnit, onSelect: (ServingUnit) -> Unit, modifier: Modifier = Modifier) {
    var unitExpanded by remember { mutableStateOf(false) }
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(text = "Unit *", style = AppTypography.Body2.copy(fontSize = 13.sp), color = AppColors.TextSecondary)
        Box {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .clip(AppShapes.InputBox)
                    .background(AppColors.FieldFill)
                    .border(1.dp, AppColors.FieldBorder, AppShapes.InputBox)
                    .mbClickable(pressedScale = 0.98f, onClickLabel = "Select Unit") { unitExpanded = true }
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = selected.displayName,
                    style = AppTypography.Body1,
                    color = AppColors.TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                MbIcon(icon = Ph.CaretDown.light, contentDescription = null, size = 16.dp, tint = AppColors.TextSecondary)
            }
            DropdownMenu(
                expanded = unitExpanded,
                onDismissRequest = { unitExpanded = false },
                modifier = Modifier.background(AppColors.SheetFill)
            ) {
                ServingUnit.entries.forEach { u ->
                    DropdownMenuItem(
                        text = { Text(u.displayName, style = AppTypography.Body1, color = AppColors.TextPrimary) },
                        onClick = {
                            onSelect(u)
                            unitExpanded = false
                        }
                    )
                }
            }
        }
    }
}
