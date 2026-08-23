package com.macrobase.app.feature.customfoods

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
import androidx.compose.material3.CircularProgressIndicator
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
    private val foodRepository: FoodRepository
) : ViewModel() {

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

    fun setScannedDraft(draft: NutritionLabelDraft) {
        _scannedDraft.value = draft
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
        fiber: Double? = null,
        sugar: Double? = null,
        sodium: Double? = null,
        potassium: Double? = null,
        calcium: Double? = null,
        iron: Double? = null,
        onComplete: () -> Unit
    ) {
        viewModelScope.launch {
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
                    fiberGrams = fiber,
                    sugarGrams = sugar,
                    sodiumMg = sodium,
                    potassiumMg = potassium,
                    calciumMg = calcium,
                    ironMg = iron
                )
            )
            foodRepository.saveCustomFood(customFood)
            onComplete()
        }
    }

    fun deleteCustomFood(id: Long, onComplete: () -> Unit) {
        viewModelScope.launch {
            foodRepository.deleteCustomFood(id)
            onComplete()
        }
    }
}

@Composable
fun CustomFoodsScreen(
    viewModel: CustomFoodsViewModel,
    onCreateClick: () -> Unit,
    onEditClick: (Long) -> Unit,
    onFoodClick: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsState()
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current

    Scaffold(
        floatingActionButton = {
            FloatingActionButton(
                onClick = {
                    focusManager.clearFocus()
                    onCreateClick()
                },
                containerColor = AppColors.Primary,
                contentColor = AppColors.TextPrimary
            ) {
                Icon(Icons.Default.Add, contentDescription = "Create Custom Food")
            }
        },
        containerColor = AppColors.Background
    ) { padding ->
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(padding)
                .background(AppColors.Background)
                .clickable(
                    interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                    indication = null
                ) { focusManager.clearFocus() }
        ) {
            // Search field
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(AppColors.Surface)
                    .padding(horizontal = AppSpacing.md, vertical = AppSpacing.sm)
            ) {
                TextField(
                    value = uiState.searchQuery,
                    onValueChange = { viewModel.onSearchQueryChange(it) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                        .clip(RoundedCornerShape(4.dp)),
                    placeholder = {
                        Text(
                            text = "Search custom foods...",
                            style = AppTypography.Body1,
                            color = AppColors.TextSecondary
                        )
                    },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = "Search",
                            tint = AppColors.TextSecondary
                        )
                    },
                    trailingIcon = {
                        if (uiState.searchQuery.isNotEmpty()) {
                            IconButton(onClick = {
                                viewModel.onSearchQueryChange("")
                                focusManager.clearFocus()
                            }) {
                                Icon(Icons.Default.Close, contentDescription = "Clear", tint = AppColors.TextSecondary)
                            }
                        }
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Search),
                    keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = { focusManager.clearFocus() }),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = AppColors.SurfaceInput,
                        unfocusedContainerColor = AppColors.SurfaceInput,
                        focusedTextColor = AppColors.TextPrimary,
                        unfocusedTextColor = AppColors.TextPrimary,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent
                    )
                )
            }

            if (uiState.isLoading) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(color = AppColors.Primary)
                }
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
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(Dimensions.SectionHeaderHeight)
                        .background(AppColors.SurfaceAlt)
                        .padding(horizontal = AppSpacing.lg),
                    contentAlignment = Alignment.CenterStart
                ) {
                    Text(
                        text = "CUSTOM FOODS (${uiState.filteredFoods.size})",
                        style = AppTypography.Caption,
                        color = AppColors.TextSecondary
                    )
                }

                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(uiState.filteredFoods, key = { it.id }) { item ->
                        CustomFoodRow(
                            food = item,
                            onClick = { onFoodClick(item.id) },
                            onEdit = { onEditClick(item.id) },
                            onDelete = { viewModel.deleteCustomFood(item.id) {} }
                        )
                        HorizontalDivider(color = AppColors.Divider, thickness = 1.dp)
                    }
                }
            }
        }
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
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(Dimensions.FoodRowHeight)
            .background(AppColors.Background)
            .clickable(onClick = onClick)
            .padding(horizontal = AppSpacing.lg),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(Dimensions.FoodThumbnailSize)
                .clip(RoundedCornerShape(4.dp))
                .background(AppColors.Surface),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.Fastfood,
                contentDescription = null,
                tint = AppColors.Primary,
                modifier = Modifier.size(20.dp)
            )
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = AppSpacing.md)
        ) {
            Text(
                text = food.name,
                style = AppTypography.Body1,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            val sub = food.brand ?: food.defaultServing?.description ?: "1 serving"
            Text(
                text = sub,
                style = AppTypography.Body2,
                color = AppColors.TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = "${food.nutrition.calories.toInt()}",
                style = AppTypography.ValueMd,
                color = AppColors.CalorieText
            )
            Text(
                text = "Cal",
                style = AppTypography.Caption
            )
        }

        IconButton(onClick = onEdit, modifier = Modifier.padding(start = AppSpacing.xs).size(32.dp)) {
            Icon(Icons.Default.Edit, contentDescription = "Edit Custom Food", tint = AppColors.TextSecondary, modifier = Modifier.size(18.dp))
        }

        IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
            Icon(Icons.Default.Delete, contentDescription = "Delete Custom Food", tint = AppColors.TextSecondary, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
fun EditCustomFoodScreen(
    foodId: Long = 0L,
    viewModel: CustomFoodsViewModel,
    onScanLabelClick: () -> Unit = {},
    onSaveSuccess: () -> Unit,
    modifier: Modifier = Modifier
) {
    var existingFood by remember { mutableStateOf<Food?>(null) }
    var name by remember { mutableStateOf("") }
    var brand by remember { mutableStateOf("") }
    var servingSize by remember { mutableStateOf("1") }
    var selectedServingUnit by remember { mutableStateOf(ServingUnit.SERVING) }
    var customUnitName by remember { mutableStateOf("") }
    var calories by remember { mutableStateOf("") }
    var protein by remember { mutableStateOf("") }
    var carbs by remember { mutableStateOf("") }
    var fat by remember { mutableStateOf("") }
    var fiber by remember { mutableStateOf("") }
    var sugar by remember { mutableStateOf("") }
    var sodium by remember { mutableStateOf("") }

    val scannedDraft by viewModel.scannedDraft.collectAsState()

    LaunchedEffect(scannedDraft) {
        scannedDraft?.let { draft ->
            if (!draft.foodName.isNullOrBlank()) name = draft.foodName
            if (!draft.brand.isNullOrBlank()) brand = draft.brand
            draft.servingSize?.let { servingSize = if (it % 1.0 == 0.0) it.toInt().toString() else it.toString() }
            draft.servingUnit?.let { selectedServingUnit = it }
            draft.calories?.let { calories = if (it.value % 1.0 == 0.0) it.value.toInt().toString() else it.value.toString() }
            draft.protein?.let { protein = if (it.value % 1.0 == 0.0) it.value.toInt().toString() else it.value.toString() }
            draft.carbs?.let { carbs = if (it.value % 1.0 == 0.0) it.value.toInt().toString() else it.value.toString() }
            draft.fat?.let { fat = if (it.value % 1.0 == 0.0) it.value.toInt().toString() else it.value.toString() }
            draft.fiber?.let { fiber = if (it.value % 1.0 == 0.0) it.value.toInt().toString() else it.value.toString() }
            draft.sugar?.let { sugar = if (it.value % 1.0 == 0.0) it.value.toInt().toString() else it.value.toString() }
            draft.sodium?.let { sodium = if (it.value % 1.0 == 0.0) it.value.toInt().toString() else it.value.toString() }
            viewModel.clearScannedDraft()
        }
    }

    val customFoodsList by viewModel.customFoods.collectAsState()

    LaunchedEffect(foodId, customFoodsList) {
        if (foodId > 0L && existingFood == null) {
            val found = customFoodsList.firstOrNull { it.id == foodId }
            if (found != null) {
                existingFood = found
                name = found.name
                brand = found.brand ?: ""
                calories = found.nutrition.calories.toInt().toString()
                protein = found.nutrition.proteinGrams.toString()
                carbs = found.nutrition.carbsGrams.toString()
                fat = found.nutrition.fatGrams.toString()
                fiber = found.nutrition.fiberGrams?.toString() ?: ""
                sugar = found.nutrition.sugarGrams?.toString() ?: ""
                sodium = found.nutrition.sodiumMg?.toString() ?: ""
                found.defaultServing?.let {
                    servingSize = if (it.quantity % 1.0 == 0.0) it.quantity.toInt().toString() else it.quantity.toString()
                    selectedServingUnit = it.unit
                    customUnitName = it.customUnitName ?: ""
                }
            }
        }
    }

    val isEditing = foodId > 0L && existingFood != null
    val cVal = calories.toDoubleOrNull()
    val isCustomUnitValid = selectedServingUnit != ServingUnit.CUSTOM || customUnitName.isNotBlank()
    val isFormValid = name.isNotBlank() && (cVal != null && cVal >= 0.0) && (servingSize.toDoubleOrNull() ?: 0.0) > 0.0 && isCustomUnitValid

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(AppColors.Background)
            .padding(AppSpacing.lg)
            .verticalScroll(rememberScrollState())
    ) {
        Text(text = if (isEditing) "Edit Custom Food" else "Create Custom Food", style = AppTypography.Header1)
        Spacer(modifier = Modifier.height(AppSpacing.md))

        // Optional on-device scanner option (for new foods)
        if (!isEditing) {
            Button(
                onClick = onScanLabelClick,
                colors = ButtonDefaults.buttonColors(containerColor = AppColors.SurfaceAlt),
                shape = AppShapes.Button,
                modifier = Modifier.fillMaxWidth().height(Dimensions.ButtonHeight)
            ) {
                Icon(Icons.Default.CameraAlt, contentDescription = null, tint = AppColors.Primary)
                Spacer(modifier = Modifier.width(AppSpacing.sm))
                Text("Scan Nutrition Label", style = AppTypography.Button, color = AppColors.TextPrimary)
            }
            Spacer(modifier = Modifier.height(AppSpacing.md))
        }

        // Detected values review banner
        if (scannedDraft != null && !isEditing) {
            val draft = scannedDraft!!
            Surface(
                color = AppColors.SurfaceAlt,
                shape = AppShapes.Card,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(AppSpacing.md)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.QrCodeScanner,
                            contentDescription = null,
                            tint = AppColors.CalorieText,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(AppSpacing.xs))
                        Text(
                            text = "Values detected (${draft.detectedBasis.displayName})",
                            style = AppTypography.Header3,
                            color = AppColors.CalorieText
                        )
                    }
                    Spacer(modifier = Modifier.height(AppSpacing.xs))
                    Text(
                        text = "Values detected from nutrition label. Please review before creating.",
                        style = AppTypography.Body2,
                        color = AppColors.TextSecondary
                    )
                    if (draft.warnings.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(AppSpacing.xs))
                        draft.warnings.forEach { warning ->
                            Text(
                                text = "• $warning",
                                style = AppTypography.Caption,
                                color = AppColors.MacroCarbs
                            )
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(AppSpacing.md))
        }

        // Required Fields Section
        Text(text = "REQUIRED INFORMATION", style = AppTypography.Caption, color = AppColors.TextSecondary)
        Spacer(modifier = Modifier.height(AppSpacing.xs))

        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text("Food Name *") },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(AppSpacing.sm))

        OutlinedTextField(
            value = brand,
            onValueChange = { brand = it },
            label = { Text("Brand / Origin (Optional)") },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(AppSpacing.sm))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(AppSpacing.md)
        ) {
            OutlinedTextField(
                value = servingSize,
                onValueChange = { servingSize = it },
                label = { Text("Serving Size *") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.weight(1f)
            )

            var unitExpanded by remember { mutableStateOf(false) }
            Box(
                modifier = Modifier.weight(1.5f)
            ) {
                OutlinedTextField(
                    value = selectedServingUnit.displayName,
                    onValueChange = {},
                    readOnly = true,
                    enabled = false,
                    label = { Text("Unit *") },
                    trailingIcon = {
                        Icon(Icons.Default.ArrowDropDown, contentDescription = "Select Unit", tint = AppColors.TextSecondary)
                    },
                    colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                        disabledTextColor = AppColors.TextPrimary,
                        disabledBorderColor = AppColors.Divider,
                        disabledLabelColor = AppColors.TextSecondary,
                        disabledTrailingIconColor = AppColors.TextSecondary
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .clickable { unitExpanded = true }
                )

                DropdownMenu(
                    expanded = unitExpanded,
                    onDismissRequest = { unitExpanded = false },
                    modifier = Modifier.background(AppColors.SurfaceAlt)
                ) {
                    ServingUnit.entries.forEach { u ->
                        DropdownMenuItem(
                            text = { Text(u.displayName, style = AppTypography.Body1, color = AppColors.TextPrimary) },
                            onClick = {
                                selectedServingUnit = u
                                unitExpanded = false
                            }
                        )
                    }
                }
            }
        }

        if (selectedServingUnit == ServingUnit.CUSTOM) {
            Spacer(modifier = Modifier.height(AppSpacing.sm))
            OutlinedTextField(
                value = customUnitName,
                onValueChange = { customUnitName = it },
                label = { Text("Custom Unit Name * (e.g. roti, slice, bowl, scoop)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        }

        Spacer(modifier = Modifier.height(AppSpacing.sm))

        OutlinedTextField(
            value = calories,
            onValueChange = { calories = it },
            label = { Text("Calories (kcal) *") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(AppSpacing.md))
        Text(text = "MACRONUTRIENTS (PER SERVING)", style = AppTypography.Caption, color = AppColors.TextSecondary)
        Spacer(modifier = Modifier.height(AppSpacing.xs))

        OutlinedTextField(
            value = protein,
            onValueChange = { protein = it },
            label = { Text("Protein (g)") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(AppSpacing.sm))

        OutlinedTextField(
            value = carbs,
            onValueChange = { carbs = it },
            label = { Text("Carbohydrates (g)") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(AppSpacing.sm))

        OutlinedTextField(
            value = fat,
            onValueChange = { fat = it },
            label = { Text("Fat (g)") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(AppSpacing.md))
        Text(text = "ADDITIONAL NUTRIENTS (OPTIONAL)", style = AppTypography.Caption, color = AppColors.TextSecondary)
        Spacer(modifier = Modifier.height(AppSpacing.xs))

        OutlinedTextField(
            value = fiber,
            onValueChange = { fiber = it },
            label = { Text("Dietary Fiber (g)") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(AppSpacing.sm))

        OutlinedTextField(
            value = sugar,
            onValueChange = { sugar = it },
            label = { Text("Total Sugars (g)") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(AppSpacing.sm))

        OutlinedTextField(
            value = sodium,
            onValueChange = { sodium = it },
            label = { Text("Sodium (mg)") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(AppSpacing.xl))

        PrimaryButton(
            text = if (isEditing) "Update Custom Food" else "Save Custom Food",
            enabled = isFormValid,
            onClick = {
                viewModel.saveCustomFood(
                    id = existingFood?.id ?: 0L,
                    uuid = existingFood?.uuid ?: UUID.randomUUID().toString(),
                    name = name,
                    brand = brand,
                    servingSize = servingSize.toDoubleOrNull() ?: 1.0,
                    servingUnit = selectedServingUnit,
                    customUnitName = if (selectedServingUnit == ServingUnit.CUSTOM) customUnitName else null,
                    calories = cVal ?: 0.0,
                    protein = protein.toDoubleOrNull() ?: 0.0,
                    carbs = carbs.toDoubleOrNull() ?: 0.0,
                    fat = fat.toDoubleOrNull() ?: 0.0,
                    fiber = fiber.toDoubleOrNull(),
                    sugar = sugar.toDoubleOrNull(),
                    sodium = sodium.toDoubleOrNull(),
                    onComplete = {
                        viewModel.clearScannedDraft()
                        onSaveSuccess()
                    }
                )
            }
        )

        if (isEditing) {
            Spacer(modifier = Modifier.height(AppSpacing.sm))
            Button(
                onClick = {
                    viewModel.deleteCustomFood(existingFood!!.id, onComplete = onSaveSuccess)
                },
                modifier = Modifier.fillMaxWidth().height(Dimensions.ButtonHeight),
                shape = AppShapes.Button,
                colors = ButtonDefaults.buttonColors(containerColor = AppColors.ProgressOver)
            ) {
                Icon(Icons.Default.Delete, contentDescription = null, tint = AppColors.TextPrimary)
                Spacer(modifier = Modifier.size(AppSpacing.xs))
                Text(text = "Delete Custom Food", style = AppTypography.Button, color = AppColors.TextPrimary)
            }
        }
    }
}
