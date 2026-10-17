package com.macrobase.app.feature.search

import com.macrobase.app.core.designsystem.components.rememberIsScrolled
import com.macrobase.app.core.designsystem.components.mbSearchPillFrame
import androidx.compose.animation.core.tween
import androidx.compose.animation.animateColorAsState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.lazy.rememberLazyListState
import com.macrobase.app.core.designsystem.components.mbClickable
import com.macrobase.app.core.designsystem.components.mealLabel
import com.macrobase.app.core.designsystem.components.MbIconButton
import com.macrobase.app.core.designsystem.components.MbIcon
import com.macrobase.app.core.designsystem.AppShapes
import com.macrobase.app.core.designsystem.Ph
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.PaddingValues
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
    /** The day is null when Search was opened without one: the food then logs to today (BUG-040). */
    onFoodClick: (Long, MealType, Long?) -> Unit,
    onCreateCustomFood: () -> Unit,
    modifier: Modifier = Modifier,
    onNavigateBack: () -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsState()
    val date = if (dateEpochDay != null) LocalDate.ofEpochDay(dateEpochDay) else LocalDate.now()
    val listState = rememberLazyListState()
    val scrolled = listState.rememberIsScrolled()

    LaunchedEffect(mealType, dateEpochDay) {
        if (mealType != null) {
            viewModel.setContext(mealType, date)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
    ) {
        // 1. The header: back, then the search field with its clear button
        SearchHeader(
            query = uiState.searchQuery,
            placeholder = "Search foods for ${mealLabel(uiState.targetMealType)}\u2026",
            isLoading = uiState.isLoading,
            scrolled = scrolled,
            onQueryChange = { viewModel.onQueryChange(it) },
            onClear = { viewModel.onClearQuery() },
            onBack = onNavigateBack
        )

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
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(start = AppSpacing.screenMargin, end = AppSpacing.screenMargin, bottom = AppSpacing.xxl),
                        verticalArrangement = Arrangement.spacedBy(AppSpacing.rowGap)
                    ) {
                        if (uiState.customFoods.isNotEmpty()) {
                            item(key = "header_custom_foods") { SearchSectionHeading("Custom foods") }
                            items(uiState.customFoods, key = { "custom_${it.id}" }) { item ->
                                FoodResultItem(
                                    food = item,
                                    onClick = { onFoodClick(item.id, uiState.targetMealType, dateEpochDay) }
                                )
                            }
                        }

                        val recentOnly = uiState.recentFoods.filter { recent ->
                            uiState.customFoods.none { it.id == recent.id }
                        }
                        if (recentOnly.isNotEmpty()) {
                            item(key = "header_recent_foods") { SearchSectionHeading("Recently logged") }
                            items(recentOnly, key = { "recent_${it.id}" }) { item ->
                                FoodResultItem(
                                    food = item,
                                    onClick = { onFoodClick(item.id, uiState.targetMealType, dateEpochDay) }
                                )
                            }
                        }
                    }
                } else {
                    // Intentional empty state when user has no custom foods yet
                    Column(modifier = Modifier.padding(horizontal = AppSpacing.screenMargin)) {
                        SearchSectionHeading("Custom foods")
                        EmptyStateCard(
                            icon = Icons.Default.Search,
                            title = "No Custom Foods",
                            subtitle = "You haven't created any custom foods yet. Type above to search the offline database or create a new custom food.",
                            actionButtonText = "+ Create Custom Food",
                            onActionClick = onCreateCustomFood,
                            modifier = Modifier.padding(top = AppSpacing.md)
                        )
                    }
                }
            }
            com.macrobase.app.domain.model.state.SearchContentState.SEARCHING,
            com.macrobase.app.domain.model.state.SearchContentState.SEARCH_RESULTS -> {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = AppSpacing.screenMargin, end = AppSpacing.screenMargin, bottom = AppSpacing.xxl),
                    verticalArrangement = Arrangement.spacedBy(AppSpacing.rowGap)
                ) {
                    item(key = "header_results") { SearchSectionHeading("Search results (${uiState.searchResults.size})") }
                    items(uiState.searchResults, key = { it.id }) { item ->
                        FoodResultItem(
                            food = item,
                            onClick = { onFoodClick(item.id, uiState.targetMealType, dateEpochDay) }
                        )
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

/** A section title above a list of foods. */
@Composable
private fun SearchSectionHeading(text: String) {
    Text(
        text = text,
        style = AppTypography.Header2,
        color = AppColors.TextPrimary,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = AppSpacing.lg, bottom = 2.dp)
            .semantics { heading() }
    )
}

/**
 * Search's pinned header: the back circle, then the search field as a pill with a loading spinner
 * or a clear button. It gets the header band once the list scrolls under it.
 */
@Composable
private fun SearchHeader(
    query: String,
    placeholder: String,
    isLoading: Boolean,
    scrolled: Boolean,
    onQueryChange: (String) -> Unit,
    onClear: () -> Unit,
    onBack: () -> Unit
) {
    val band by animateColorAsState(
        targetValue = if (scrolled) AppColors.HeaderBand else Color.Transparent,
        animationSpec = tween(200),
        label = "searchBand"
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(band)
            .padding(start = 10.dp, end = AppSpacing.screenMargin, top = 6.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        MbIconButton(
            icon = Ph.ArrowLeft.light,
            contentDescription = "Back",
            onClick = onBack,
            size = Dimensions.HeaderButton,
            outlined = true
        )
        Row(
            modifier = Modifier
                .weight(1f)
                .mbSearchPillFrame(),
            verticalAlignment = Alignment.CenterVertically
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
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 10.dp)
                    .semantics { contentDescription = "Search foods" },
                decorationBox = { innerTextField ->
                    Box(modifier = Modifier.heightIn(min = 38.dp), contentAlignment = Alignment.CenterStart) {
                        if (query.isEmpty()) {
                            Text(
                                text = placeholder,
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
            if (isLoading) {
                CircularProgressIndicator(
                    color = AppColors.Primary,
                    strokeWidth = 2.dp,
                    modifier = Modifier
                        .padding(end = 10.dp)
                        .size(20.dp)
                )
            } else if (query.isNotEmpty()) {
                MbIconButton(
                    icon = Ph.X.light,
                    contentDescription = "Clear",
                    onClick = onClear,
                    size = 38.dp,
                    iconSize = 16.dp
                )
            }
        }
    }
}
