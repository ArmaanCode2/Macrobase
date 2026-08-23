package com.macrobase.app.feature.recipes

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.RestaurantMenu
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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
import com.macrobase.app.core.designsystem.components.FoodResultItem
import com.macrobase.app.core.designsystem.components.PrimaryButton
import com.macrobase.app.domain.model.DiaryEntry
import com.macrobase.app.domain.model.Food
import com.macrobase.app.domain.model.MealType
import com.macrobase.app.domain.model.Recipe
import com.macrobase.app.domain.model.RecipeIngredient
import com.macrobase.app.domain.model.Serving
import com.macrobase.app.domain.repository.FoodRepository
import com.macrobase.app.domain.repository.RecipeRepository
import com.macrobase.app.domain.usecase.LogFoodUseCase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

import androidx.compose.material3.CircularProgressIndicator
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

data class RecipesListUiState(
    val isLoading: Boolean = true,
    val recipes: List<Recipe> = emptyList()
)

class RecipesViewModel(
    private val recipeRepository: RecipeRepository,
    private val foodRepository: com.macrobase.app.domain.repository.FoodRepository,
    private val addFoodToBasketUseCase: com.macrobase.app.domain.usecase.basket.AddFoodToBasketUseCase
) : ViewModel() {

    val uiState: StateFlow<RecipesListUiState> = recipeRepository.observeRecipes().map { list ->
        RecipesListUiState(
            isLoading = false,
            recipes = list
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = RecipesListUiState(isLoading = true)
    )

    val recipes: StateFlow<List<Recipe>> = recipeRepository.observeRecipes()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun saveRecipe(recipe: Recipe, onComplete: () -> Unit) {
        viewModelScope.launch {
            if (recipe.id > 0) {
                recipeRepository.updateRecipe(recipe)
            } else {
                recipeRepository.createRecipe(recipe)
            }
            onComplete()
        }
    }

    fun deleteRecipe(recipeId: Long, onComplete: () -> Unit) {
        viewModelScope.launch {
            recipeRepository.deleteRecipe(recipeId)
            onComplete()
        }
    }

    suspend fun searchFoodsForIngredient(query: String): List<Food> {
        return foodRepository.searchFoods(query, limit = 20)
    }

    fun logRecipeToDiary(recipe: Recipe, mealType: MealType, date: LocalDate, servingsToLog: Double = 1.0, onComplete: () -> Unit) {
        viewModelScope.launch {
            val perServing = recipe.nutritionPerServing
            val scaledNutrition = perServing.scale(servingsToLog)

            addFoodToBasketUseCase(
                food = recipe.toFood(),
                serving = Serving(description = "Serving", gramWeight = 0.0, isDefault = true),
                quantity = servingsToLog,
                calculatedNutrition = scaledNutrition,
                date = date,
                mealType = mealType
            )
            onComplete()
        }
    }
}

@Composable
fun RecipesScreen(
    viewModel: RecipesViewModel,
    onCreateClick: () -> Unit,
    onEditClick: (Long) -> Unit,
    onLogSuccess: () -> Unit,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsState()
    var recipeToLog by remember { mutableStateOf<Recipe?>(null) }

    Scaffold(
        floatingActionButton = {
            FloatingActionButton(
                onClick = onCreateClick,
                containerColor = AppColors.Primary,
                contentColor = AppColors.TextPrimary
            ) {
                Icon(Icons.Default.Add, contentDescription = "Create Recipe")
            }
        },
        containerColor = AppColors.Background
    ) { padding ->
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(padding)
                .background(AppColors.Background)
        ) {
            if (uiState.isLoading) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(color = AppColors.Primary)
                }
            } else if (uiState.recipes.isEmpty()) {
                EmptyStateCard(
                    icon = Icons.Default.RestaurantMenu,
                    title = "My Recipes",
                    subtitle = "Build multi-ingredient recipes with automatic per-serving nutrition calculation.",
                    actionButtonText = "+ Create New Recipe",
                    onActionClick = onCreateClick,
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
                        text = "SAVED RECIPES (${uiState.recipes.size})",
                        style = AppTypography.Caption,
                        color = AppColors.TextSecondary
                    )
                }

                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(uiState.recipes, key = { it.id }) { recipe ->
                        RecipeCard(
                            recipe = recipe,
                            onLogClick = { recipeToLog = recipe },
                            onEditClick = { onEditClick(recipe.id) },
                            onDeleteClick = { viewModel.deleteRecipe(recipe.id) {} }
                        )
                        HorizontalDivider(color = AppColors.Divider, thickness = 1.dp)
                    }
                }
            }
        }
    }

    // Log Recipe Modal Dialog
    recipeToLog?.let { recipe ->
        var selectedMeal by remember { mutableStateOf(MealType.LUNCH) }
        var servingsCount by remember { mutableStateOf("1") }

        AlertDialog(
            onDismissRequest = { recipeToLog = null },
            title = { Text("Log Recipe: ${recipe.name}", style = AppTypography.Header2) },
            text = {
                Column {
                    Text("Choose meal slot and servings to log:", style = AppTypography.Body2, color = AppColors.TextSecondary)
                    Spacer(modifier = Modifier.height(AppSpacing.sm))

                    var mealExpanded by remember { mutableStateOf(false) }
                    Box(modifier = Modifier.fillMaxWidth()) {
                        OutlinedTextField(
                            value = selectedMeal.displayName,
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("Meal") },
                            trailingIcon = { Icon(Icons.Default.ArrowDropDown, contentDescription = null) },
                            modifier = Modifier.fillMaxWidth().clickable { mealExpanded = true }
                        )
                        DropdownMenu(expanded = mealExpanded, onDismissRequest = { mealExpanded = false }) {
                            MealType.entries.forEach { m ->
                                DropdownMenuItem(
                                    text = { Text(m.displayName) },
                                    onClick = {
                                        selectedMeal = m
                                        mealExpanded = false
                                    }
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(AppSpacing.sm))

                    OutlinedTextField(
                        value = servingsCount,
                        onValueChange = { servingsCount = it },
                        label = { Text("Number of Servings") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val qty = servingsCount.toDoubleOrNull() ?: 1.0
                        viewModel.logRecipeToDiary(
                            recipe = recipe,
                            mealType = selectedMeal,
                            date = LocalDate.now(),
                            servingsToLog = qty,
                            onComplete = {
                                recipeToLog = null
                                onLogSuccess()
                            }
                        )
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = AppColors.Primary)
                ) {
                    Text("Log to Diary")
                }
            },
            dismissButton = {
                TextButton(onClick = { recipeToLog = null }) {
                    Text("Cancel", color = AppColors.TextSecondary)
                }
            },
            containerColor = AppColors.SurfaceAlt
        )
    }
}

@Composable
fun RecipeCard(
    recipe: Recipe,
    onLogClick: () -> Unit,
    onEditClick: () -> Unit,
    onDeleteClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val perServing = recipe.nutritionPerServing

    Card(
        shape = AppShapes.Card,
        colors = CardDefaults.cardColors(containerColor = AppColors.Surface),
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = AppSpacing.md, vertical = AppSpacing.sm)
    ) {
        Column(modifier = Modifier.padding(AppSpacing.md)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = recipe.name,
                        style = AppTypography.Header2,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "${recipe.servingsProduced} servings • ${recipe.ingredients.size} ingredients",
                        style = AppTypography.Caption,
                        color = AppColors.TextSecondary
                    )
                }

                Text(
                    text = "${perServing.calories.toInt()} cal/srv",
                    style = AppTypography.ValueMd,
                    color = AppColors.CalorieText
                )
            }

            Spacer(modifier = Modifier.height(AppSpacing.sm))

            // Macro summary line
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(AppSpacing.md)
            ) {
                Text(text = "P: ${String.format("%.1f", perServing.proteinGrams)}g", style = AppTypography.Body2, color = AppColors.MacroProtein)
                Text(text = "C: ${String.format("%.1f", perServing.carbsGrams)}g", style = AppTypography.Body2, color = AppColors.MacroCarbs)
                Text(text = "F: ${String.format("%.1f", perServing.fatGrams)}g", style = AppTypography.Body2, color = AppColors.MacroFat)
            }

            Spacer(modifier = Modifier.height(AppSpacing.md))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onEditClick, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Default.Edit, contentDescription = "Edit Recipe", tint = AppColors.TextSecondary, modifier = Modifier.size(18.dp))
                }
                IconButton(onClick = onDeleteClick, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Default.Delete, contentDescription = "Delete Recipe", tint = AppColors.TextSecondary, modifier = Modifier.size(18.dp))
                }
                Spacer(modifier = Modifier.size(AppSpacing.sm))
                Button(
                    onClick = onLogClick,
                    shape = AppShapes.PillButton,
                    colors = ButtonDefaults.buttonColors(containerColor = AppColors.Primary),
                    modifier = Modifier.height(Dimensions.ButtonHeightSmall)
                ) {
                    Text("(+) Log Recipe", style = AppTypography.Caption, color = AppColors.TextPrimary)
                }
            }
        }
    }
}

@Composable
fun EditRecipeScreen(
    recipeId: Long = 0L,
    viewModel: RecipesViewModel,
    onSaveSuccess: () -> Unit,
    modifier: Modifier = Modifier
) {
    var name by remember { mutableStateOf("") }
    var servingsProduced by remember { mutableStateOf("4") }
    var ingredients by remember { mutableStateOf<List<RecipeIngredient>>(emptyList()) }
    var existingRecipe by remember { mutableStateOf<Recipe?>(null) }

    var showAddIngredientDialog by remember { mutableStateOf(false) }

    LaunchedEffect(recipeId) {
        if (recipeId > 0L) {
            val found = viewModel.recipes.value.firstOrNull { it.id == recipeId }
            if (found != null) {
                existingRecipe = found
                name = found.name
                servingsProduced = found.servingsProduced.toString()
                ingredients = found.ingredients
            }
        }
    }

    val servingsCount = (servingsProduced.toIntOrNull() ?: 1).coerceAtLeast(1)
    val tempRecipe = Recipe(
        id = existingRecipe?.id ?: 0L,
        uuid = existingRecipe?.uuid ?: UUID.randomUUID().toString(),
        name = name,
        servingsProduced = servingsCount,
        ingredients = ingredients
    )

    val totalNutr = tempRecipe.totalNutrition
    val perServingNutr = tempRecipe.nutritionPerServing
    val isFormValid = name.isNotBlank() && servingsCount > 0 && ingredients.isNotEmpty()

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(AppColors.Background)
            .padding(AppSpacing.lg)
            .verticalScroll(rememberScrollState())
    ) {
        Text(text = if (recipeId > 0L) "Edit Recipe" else "Create Recipe", style = AppTypography.Header1)
        Spacer(modifier = Modifier.height(AppSpacing.md))

        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text("Recipe Name *") },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(AppSpacing.sm))

        OutlinedTextField(
            value = servingsProduced,
            onValueChange = { servingsProduced = it },
            label = { Text("Servings Produced *") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(AppSpacing.md))

        // Running Live Nutrition Card
        Card(
            shape = AppShapes.Card,
            colors = CardDefaults.cardColors(containerColor = AppColors.Surface),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(AppSpacing.md)) {
                Text(text = "LIVE NUTRITION (PER SERVING)", style = AppTypography.Caption, color = AppColors.TextSecondary)
                Spacer(modifier = Modifier.height(AppSpacing.xs))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(text = "Calories", style = AppTypography.Header2)
                    Text(text = "${perServingNutr.calories.toInt()} kcal", style = AppTypography.ValueLg, color = AppColors.CalorieText)
                }
                Spacer(modifier = Modifier.height(AppSpacing.xs))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(text = "Protein: ${String.format("%.1f", perServingNutr.proteinGrams)}g", style = AppTypography.Body2, color = AppColors.MacroProtein)
                    Text(text = "Carbs: ${String.format("%.1f", perServingNutr.carbsGrams)}g", style = AppTypography.Body2, color = AppColors.MacroCarbs)
                    Text(text = "Fat: ${String.format("%.1f", perServingNutr.fatGrams)}g", style = AppTypography.Body2, color = AppColors.MacroFat)
                }
            }
        }

        Spacer(modifier = Modifier.height(AppSpacing.md))

        // Ingredients Section Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = "INGREDIENTS (${ingredients.size})", style = AppTypography.Header3)
            Button(
                onClick = { showAddIngredientDialog = true },
                shape = AppShapes.PillButton,
                colors = ButtonDefaults.buttonColors(containerColor = AppColors.Primary),
                modifier = Modifier.height(Dimensions.ButtonHeightSmall)
            ) {
                Text("+ Add Ingredient", style = AppTypography.Caption)
            }
        }

        Spacer(modifier = Modifier.height(AppSpacing.sm))

        if (ingredients.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(60.dp)
                    .background(AppColors.Surface, AppShapes.Card)
                    .padding(AppSpacing.md),
                contentAlignment = Alignment.Center
            ) {
                Text(text = "No ingredients added yet. Tap '+ Add Ingredient' above.", style = AppTypography.Body2, color = AppColors.TextSecondary)
            }
        } else {
            ingredients.forEachIndexed { index, ing ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = AppSpacing.xs)
                        .background(AppColors.Surface, AppShapes.Card)
                        .padding(AppSpacing.md),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(text = ing.food.name, style = AppTypography.Body1, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(text = "${String.format("%.1f", ing.quantity)} × ${ing.serving.description}", style = AppTypography.Caption, color = AppColors.TextSecondary)
                    }
                    Text(text = "${ing.nutrition.calories.toInt()} cal", style = AppTypography.Header3, color = AppColors.CalorieText)
                    IconButton(onClick = {
                        ingredients = ingredients.filterIndexed { i, _ -> i != index }
                    }) {
                        Icon(Icons.Default.Close, contentDescription = "Remove", tint = AppColors.TextSecondary)
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(AppSpacing.xl))

        PrimaryButton(
            text = if (recipeId > 0L) "Update Recipe" else "Save Recipe",
            enabled = isFormValid,
            onClick = {
                viewModel.saveRecipe(tempRecipe, onComplete = onSaveSuccess)
            }
        )
    }

    // Add Ingredient Dialog
    if (showAddIngredientDialog) {
        var query by remember { mutableStateOf("") }
        var searchResults by remember { mutableStateOf<List<Food>>(emptyList()) }
        var selectedFood by remember { mutableStateOf<Food?>(null) }
        var selectedServing by remember { mutableStateOf<Serving?>(null) }
        var qtyInput by remember { mutableStateOf("1") }

        AlertDialog(
            onDismissRequest = { showAddIngredientDialog = false },
            title = { Text(text = "Add Ingredient", style = AppTypography.Header2) },
            text = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    if (selectedFood == null) {
                        OutlinedTextField(
                            value = query,
                            onValueChange = {
                                query = it
                                if (it.isNotBlank()) {
                                    viewModel.viewModelScope.launch {
                                        searchResults = viewModel.searchFoodsForIngredient(it)
                                    }
                                }
                            },
                            label = { Text("Search ingredient...") },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(modifier = Modifier.height(AppSpacing.sm))

                        LazyColumn(modifier = Modifier.height(200.dp)) {
                            items(searchResults) { food ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            selectedFood = food
                                            selectedServing = food.defaultServing ?: Serving(description = "100 g", gramWeight = 100.0)
                                        }
                                        .padding(vertical = AppSpacing.xs),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(text = food.name, style = AppTypography.Body2, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                    Text(text = "${food.nutrition.calories.toInt()} cal", style = AppTypography.Caption, color = AppColors.CalorieText)
                                }
                                HorizontalDivider(color = AppColors.Divider)
                            }
                        }
                    } else {
                        Text(text = selectedFood!!.name, style = AppTypography.Header3)
                        Spacer(modifier = Modifier.height(AppSpacing.sm))

                        OutlinedTextField(
                            value = qtyInput,
                            onValueChange = { qtyInput = it },
                            label = { Text("Quantity") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(modifier = Modifier.height(AppSpacing.sm))

                        Text(text = "Serving: ${selectedServing?.description ?: "100 g"}", style = AppTypography.Body2)
                    }
                }
            },
            confirmButton = {
                if (selectedFood != null) {
                    Button(
                        onClick = {
                            val q = qtyInput.toDoubleOrNull() ?: 1.0
                            val ing = RecipeIngredient(
                                id = 0L,
                                food = selectedFood!!,
                                serving = selectedServing!!,
                                quantity = q
                            )
                            ingredients = ingredients + ing
                            showAddIngredientDialog = false
                        }
                    ) {
                        Text("Add to Recipe")
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddIngredientDialog = false }) {
                    Text("Cancel", color = AppColors.TextSecondary)
                }
            },
            containerColor = AppColors.SurfaceAlt
        )
    }
}
