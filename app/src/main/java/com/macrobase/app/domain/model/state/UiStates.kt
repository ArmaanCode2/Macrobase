package com.macrobase.app.domain.model.state

import com.macrobase.app.domain.model.CalendarDaySummary
import com.macrobase.app.domain.model.ConsistencyStatistics
import com.macrobase.app.domain.model.DailyNutritionSummary
import com.macrobase.app.domain.model.Food
import com.macrobase.app.domain.model.Goal
import com.macrobase.app.domain.model.MacroAveragesStatistics
import com.macrobase.app.domain.model.MealType
import com.macrobase.app.domain.model.Nutrition
import com.macrobase.app.domain.model.Serving
import com.macrobase.app.domain.model.UserPreferences
import com.macrobase.app.domain.model.WeightEntry
import com.macrobase.app.domain.model.WeightStatistics
import java.time.LocalDate
import java.time.YearMonth

/**
 * UI State for Home Daily Dashboard.
 */
data class DailyDashboardUiState(
    val selectedDate: LocalDate = LocalDate.now(),
    val summary: DailyNutritionSummary = DailyNutritionSummary(date = LocalDate.now()),
    val goals: Goal = Goal(),
    val isLoading: Boolean = false,
    val errorMessage: String? = null
)

enum class SearchContentState {
    INITIAL_LOADING,
    EMPTY_QUERY_WITH_CONTENT,
    SEARCHING,
    SEARCH_RESULTS,
    NO_SEARCH_RESULTS,
    ERROR
}

/**
 * UI State for Food Search.
 */
data class FoodSearchUiState(
    val searchQuery: String = "",
    val targetMealType: MealType = MealType.BREAKFAST,
    val searchResults: List<Food> = emptyList(),
    val customFoods: List<Food> = emptyList(),
    val recentFoods: List<Food> = emptyList(),
    val favoriteFoods: List<Food> = emptyList(),
    val isInitialLoading: Boolean = true,
    val isLoading: Boolean = false,
    val errorMessage: String? = null
) {
    val contentState: SearchContentState
        get() = when {
            searchQuery.isBlank() -> {
                if (isInitialLoading) SearchContentState.INITIAL_LOADING
                else SearchContentState.EMPTY_QUERY_WITH_CONTENT
            }
            isLoading -> SearchContentState.SEARCHING
            errorMessage != null -> SearchContentState.ERROR
            searchResults.isNotEmpty() -> SearchContentState.SEARCH_RESULTS
            else -> SearchContentState.NO_SEARCH_RESULTS
        }
}

/**
 * UI State for Food Detail and Serving configuration.
 */
data class FoodDetailUiState(
    val food: Food? = null,
    val availableServings: List<Serving> = emptyList(),
    val selectedServing: Serving? = null,
    val enteredQuantity: Double = 1.0,
    val quantityInputText: String = "1",
    val calculatedNutrition: Nutrition = Nutrition.ZERO,
    val macroCalorieSplit: com.macrobase.app.domain.model.MacroCalorieSplit = com.macrobase.app.domain.model.MacroCalorieSplit.ZERO,
    val targetMealType: MealType = MealType.BREAKFAST,
    val targetDate: LocalDate = LocalDate.now(),
    val isLoading: Boolean = false,
    val isSavedSuccess: Boolean = false,
    val errorMessage: String? = null
)

/**
 * UI State for Food Logging Calendar.
 */
data class CalendarUiState(
    val selectedYearMonth: YearMonth = YearMonth.now(),
    val adherenceDays: List<CalendarDaySummary> = emptyList(),
    val selectedDaySummary: DailyNutritionSummary? = null,
    val isLoading: Boolean = false,
    val errorMessage: String? = null
)

/**
 * UI State for Weight Progress.
 */
data class WeightUiState(
    val history: List<WeightEntry> = emptyList(),
    val latestWeightKg: Double? = null,
    val targetWeightKg: Double? = null,
    val statistics: WeightStatistics? = null,
    val isLoading: Boolean = false
)

/**
 * UI State for Water Hydration Tracking.
 */
data class WaterTrackingUiState(
    val date: LocalDate = LocalDate.now(),
    val currentIntakeMl: Double = 0.0,
    val dailyGoalMl: Double = 2500.0,
    val isLoading: Boolean = false
) {
    val progressRatio: Float
        get() = if (dailyGoalMl > 0.0) (currentIntakeMl / dailyGoalMl).toFloat().coerceIn(0f, 1f) else 0f
}

/**
 * UI State for Analytics and Consistency Heatmap.
 */
data class StatisticsUiState(
    val selectedTab: Int = 0,
    val startDate: LocalDate = LocalDate.now().minusDays(30),
    val endDate: LocalDate = LocalDate.now(),
    val consistencyStats: ConsistencyStatistics? = null,
    val macroStats: MacroAveragesStatistics? = null,
    val weightStats: WeightStatistics? = null,
    val isLoading: Boolean = false
)
