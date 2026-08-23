package com.macrobase.app.core.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Assessment
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Fastfood
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.ImportExport
import androidx.compose.material.icons.filled.Opacity
import androidx.compose.material.icons.filled.RestaurantMenu
import androidx.compose.material.icons.filled.Scale
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.ui.graphics.vector.ImageVector

import java.time.LocalDate

/**
 * Type-safe Screen destinations matching UI_UX_SPECIFICATION.md
 */
sealed class Screen(
    val route: String,
    val title: String,
    val icon: ImageVector? = null,
    val isBottomNavTab: Boolean = false,
    val isDrawerItem: Boolean = false
) {
    data object Home : Screen("home?dateEpochDay={dateEpochDay}", "Dashboard", Icons.Default.Home, isBottomNavTab = true, isDrawerItem = true) {
        fun createRoute(dateEpochDay: Long? = null) = if (dateEpochDay != null) "home?dateEpochDay=$dateEpochDay" else "home"
        fun todayRoute(): String = "home"
        fun dateRoute(date: LocalDate): String = "home?dateEpochDay=${date.toEpochDay()}"
    }

    data object Calendar : Screen("calendar", "Food Logging Calendar", Icons.Default.CalendarMonth, isBottomNavTab = true)
    
    data object Search : Screen("search?mealType={mealType}&dateEpochDay={dateEpochDay}", "Search Foods", Icons.Default.Search) {
        fun createRoute(mealType: String? = null, dateEpochDay: Long? = null) = buildString {
            append("search")
            val params = mutableListOf<String>()
            if (mealType != null) params.add("mealType=$mealType")
            if (dateEpochDay != null) params.add("dateEpochDay=$dateEpochDay")
            if (params.isNotEmpty()) {
                append("?")
                append(params.joinToString("&"))
            }
        }
    }

    data object FoodDetail : Screen("food_detail/{foodId}?mealType={mealType}&dateEpochDay={dateEpochDay}&entryId={entryId}&basketItemId={basketItemId}", "Food Detail") {
        fun createRoute(foodId: Long, mealType: String? = null, dateEpochDay: Long? = null, entryId: Long? = null, basketItemId: String? = null) = buildString {
            append("food_detail/$foodId")
            val params = mutableListOf<String>()
            if (mealType != null) params.add("mealType=$mealType")
            if (dateEpochDay != null) params.add("dateEpochDay=$dateEpochDay")
            if (entryId != null) params.add("entryId=$entryId")
            if (basketItemId != null) params.add("basketItemId=$basketItemId")
            if (params.isNotEmpty()) {
                append("?")
                append(params.joinToString("&"))
            }
        }
    }

    data object Weight : Screen("weight?dateEpochDay={dateEpochDay}", "Weight Tracker", Icons.Default.Scale, isDrawerItem = true) {
        fun createRoute(dateEpochDay: Long? = null) = if (dateEpochDay != null) "weight?dateEpochDay=$dateEpochDay" else "weight"
    }
    data object Water : Screen("water?dateEpochDay={dateEpochDay}", "Water Tracker", Icons.Default.Opacity, isDrawerItem = true) {
        fun createRoute(dateEpochDay: Long? = null) = if (dateEpochDay != null) "water?dateEpochDay=$dateEpochDay" else "water"
    }
    data object Statistics : Screen("statistics", "Statistics", Icons.Default.Assessment, isBottomNavTab = true)
    data object CustomFoods : Screen("custom_foods", "Custom Foods", Icons.Default.Fastfood, isDrawerItem = true)
    data object EditCustomFood : Screen("edit_custom_food?foodId={foodId}", "Edit Custom Food") {
        fun createRoute(foodId: Long? = null) = if (foodId != null) "edit_custom_food?foodId=$foodId" else "edit_custom_food"
    }
    data object NutritionLabelScanner : Screen("nutrition_label_scanner", "Scan Nutrition Label")
    data object Recipes : Screen("recipes", "My Recipes", Icons.Default.RestaurantMenu, isDrawerItem = true)
    data object EditRecipe : Screen("edit_recipe?recipeId={recipeId}", "Edit Recipe") {
        fun createRoute(recipeId: Long? = null) = if (recipeId != null) "edit_recipe?recipeId=$recipeId" else "edit_recipe"
    }
    data object DailyGoals : Screen("daily_goals", "Daily Goals", Icons.Default.Flag, isDrawerItem = true)
    data object Preferences : Screen("preferences", "Preferences", Icons.Default.Settings, isBottomNavTab = true, isDrawerItem = true)
    data object ImportExport : Screen("import_export", "Data & Backup", Icons.Default.ImportExport, isDrawerItem = true)

    data object Basket : Screen("basket", "Basket")

    companion object {
        val bottomNavScreens: List<Screen>
            get() = listOf(Home, Calendar, Statistics, Preferences)

        val drawerScreens: List<Screen>
            get() = listOf(Home, DailyGoals, CustomFoods, Recipes, Weight, Water, ImportExport, Preferences)
    }
}
