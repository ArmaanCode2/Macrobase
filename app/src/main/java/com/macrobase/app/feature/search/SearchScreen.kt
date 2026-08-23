package com.macrobase.app.feature.search

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.macrobase.app.core.config.DatabaseConfig
import com.macrobase.app.core.designsystem.AppColors
import com.macrobase.app.core.designsystem.AppSpacing
import com.macrobase.app.core.designsystem.AppTypography
import com.macrobase.app.core.designsystem.Dimensions
import com.macrobase.app.core.designsystem.components.EmptyStateCard
import com.macrobase.app.core.designsystem.components.FoodResultItem
import com.macrobase.app.domain.model.MealType
import com.macrobase.app.domain.model.state.FoodSearchUiState
import com.macrobase.app.domain.repository.FoodRepository
import com.macrobase.app.domain.usecase.SearchFoodsUseCase
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate

class SearchViewModel(
    private val searchFoodsUseCase: SearchFoodsUseCase,
    private val foodRepository: FoodRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(FoodSearchUiState())
    val uiState: StateFlow<FoodSearchUiState> = _uiState.asStateFlow()

    private var targetDate: LocalDate = LocalDate.now()
    private var searchJob: Job? = null

    init {
        observeCustomAndRecentFoods()
    }

    fun setContext(mealType: MealType, date: LocalDate) {
        targetDate = date
        _uiState.update { it.copy(targetMealType = mealType) }
    }

    fun onQueryChange(query: String) {
        _uiState.update { it.copy(searchQuery = query) }
        searchJob?.cancel()

        val trimmed = query.trim()
        if (trimmed.isEmpty()) {
            _uiState.update { it.copy(searchResults = emptyList(), isLoading = false, errorMessage = null) }
            return
        }

        searchJob = viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            delay(DatabaseConfig.SEARCH_DEBOUNCE_MILLIS) // 250ms debounce per UI spec

            try {
                val results = searchFoodsUseCase(trimmed, limit = 50)
                _uiState.update {
                    it.copy(
                        searchResults = results,
                        isLoading = false,
                        errorMessage = null
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        searchResults = emptyList(),
                        isLoading = false,
                        errorMessage = e.message ?: "Search failed"
                    )
                }
            }
        }
    }

    fun onClearQuery() {
        onQueryChange("")
    }

    private fun observeCustomAndRecentFoods() {
        viewModelScope.launch {
            try {
                foodRepository.observeCustomFoods().collect { customList ->
                    _uiState.update {
                        it.copy(
                            customFoods = customList,
                            isInitialLoading = false
                        )
                    }
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(isInitialLoading = false) }
            }
        }

        viewModelScope.launch {
            try {
                val recents = foodRepository.getRecentFoods(limit = 10)
                _uiState.update { it.copy(recentFoods = recents) }
            } catch (ignored: Exception) {}
        }
    }
}

@Composable
fun SearchScreen(
    viewModel: SearchViewModel,
    mealType: MealType? = null,
    dateEpochDay: Long? = null,
    onFoodClick: (Long, MealType, Long) -> Unit,
    onCreateCustomFood: () -> Unit,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsState()
    val date = if (dateEpochDay != null) LocalDate.ofEpochDay(dateEpochDay) else LocalDate.now()

    LaunchedEffect(mealType, dateEpochDay) {
        if (mealType != null) {
            viewModel.setContext(mealType, date)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(AppColors.Background)
    ) {
        // 1. Embedded Search Field
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(AppColors.Surface)
                .padding(horizontal = AppSpacing.md, vertical = AppSpacing.sm)
        ) {
            TextField(
                value = uiState.searchQuery,
                onValueChange = { viewModel.onQueryChange(it) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .clip(RoundedCornerShape(4.dp)),
                placeholder = {
                    Text(
                        text = "Search foods for ${uiState.targetMealType.displayName}...",
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
                    if (uiState.isLoading) {
                        CircularProgressIndicator(
                            color = AppColors.Primary,
                            strokeWidth = 2.dp,
                            modifier = Modifier.padding(12.dp).fillMaxSize(0.5f)
                        )
                    } else if (uiState.searchQuery.isNotEmpty()) {
                        IconButton(onClick = { viewModel.onClearQuery() }) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Clear",
                                tint = AppColors.TextSecondary
                            )
                        }
                    }
                },
                singleLine = true,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    imeAction = androidx.compose.ui.text.input.ImeAction.Search
                ),
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

        // 2. Results / Empty / No-Results States driven by explicit SearchContentState
        when (uiState.contentState) {
            com.macrobase.app.domain.model.state.SearchContentState.INITIAL_LOADING -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(color = AppColors.Primary, strokeWidth = 2.dp)
                }
            }
            com.macrobase.app.domain.model.state.SearchContentState.EMPTY_QUERY_WITH_CONTENT -> {
                if (uiState.customFoods.isNotEmpty() || uiState.recentFoods.isNotEmpty()) {
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        if (uiState.customFoods.isNotEmpty()) {
                            item(key = "header_custom_foods") {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(Dimensions.SectionHeaderHeight)
                                        .background(AppColors.SurfaceAlt)
                                        .padding(horizontal = AppSpacing.lg),
                                    contentAlignment = Alignment.CenterStart
                                ) {
                                    Text(
                                        text = "CUSTOM FOODS",
                                        style = AppTypography.Caption,
                                        color = AppColors.TextSecondary
                                    )
                                }
                            }
                            items(uiState.customFoods, key = { "custom_${it.id}" }) { item ->
                                FoodResultItem(
                                    food = item,
                                    onClick = { onFoodClick(item.id, uiState.targetMealType, date.toEpochDay()) }
                                )
                                HorizontalDivider(color = AppColors.Divider, thickness = 1.dp)
                            }
                        }

                        val recentOnly = uiState.recentFoods.filter { recent ->
                            uiState.customFoods.none { it.id == recent.id }
                        }
                        if (recentOnly.isNotEmpty()) {
                            item(key = "header_recent_foods") {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(Dimensions.SectionHeaderHeight)
                                        .background(AppColors.SurfaceAlt)
                                        .padding(horizontal = AppSpacing.lg),
                                    contentAlignment = Alignment.CenterStart
                                ) {
                                    Text(
                                        text = "RECENTLY LOGGED",
                                        style = AppTypography.Caption,
                                        color = AppColors.TextSecondary
                                    )
                                }
                            }
                            items(recentOnly, key = { "recent_${it.id}" }) { item ->
                                FoodResultItem(
                                    food = item,
                                    onClick = { onFoodClick(item.id, uiState.targetMealType, date.toEpochDay()) }
                                )
                                HorizontalDivider(color = AppColors.Divider, thickness = 1.dp)
                            }
                        }
                    }
                } else {
                    // Intentional empty state when user has no custom foods yet
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(Dimensions.SectionHeaderHeight)
                            .background(AppColors.SurfaceAlt)
                            .padding(horizontal = AppSpacing.lg),
                        contentAlignment = Alignment.CenterStart
                    ) {
                        Text(
                            text = "CUSTOM FOODS",
                            style = AppTypography.Caption,
                            color = AppColors.TextSecondary
                        )
                    }
                    EmptyStateCard(
                        icon = Icons.Default.Search,
                        title = "No Custom Foods",
                        subtitle = "You haven't created any custom foods yet. Type above to search the offline database or create a new custom food.",
                        actionButtonText = "+ Create Custom Food",
                        onActionClick = onCreateCustomFood,
                        modifier = Modifier.padding(top = AppSpacing.xl)
                    )
                }
            }
            com.macrobase.app.domain.model.state.SearchContentState.SEARCHING,
            com.macrobase.app.domain.model.state.SearchContentState.SEARCH_RESULTS -> {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(Dimensions.SectionHeaderHeight)
                        .background(AppColors.SurfaceAlt)
                        .padding(horizontal = AppSpacing.lg),
                    contentAlignment = Alignment.CenterStart
                ) {
                    Text(
                        text = "SEARCH RESULTS (${uiState.searchResults.size})",
                        style = AppTypography.Caption,
                        color = AppColors.TextSecondary
                    )
                }
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(uiState.searchResults, key = { it.id }) { item ->
                        FoodResultItem(
                            food = item,
                            onClick = { onFoodClick(item.id, uiState.targetMealType, date.toEpochDay()) }
                        )
                        HorizontalDivider(color = AppColors.Divider, thickness = 1.dp)
                    }
                }
            }
            com.macrobase.app.domain.model.state.SearchContentState.NO_SEARCH_RESULTS -> {
                EmptyStateCard(
                    icon = Icons.Default.Search,
                    title = "No Foods Found",
                    subtitle = "We couldn't find matching items in the offline database.",
                    actionButtonText = "+ Create Custom Food",
                    onActionClick = onCreateCustomFood,
                    modifier = Modifier.padding(top = AppSpacing.xl)
                )
            }
            com.macrobase.app.domain.model.state.SearchContentState.ERROR -> {
                EmptyStateCard(
                    icon = Icons.Default.Search,
                    title = "Search Error",
                    subtitle = uiState.errorMessage ?: "Failed to perform search.",
                    actionButtonText = "+ Create Custom Food",
                    onActionClick = onCreateCustomFood,
                    modifier = Modifier.padding(top = AppSpacing.xl)
                )
            }
        }
    }
}
