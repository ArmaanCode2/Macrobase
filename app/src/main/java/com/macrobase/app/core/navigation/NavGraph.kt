package com.macrobase.app.core.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.macrobase.app.domain.model.MealType
import com.macrobase.app.feature.calendar.CalendarScreen
import com.macrobase.app.feature.calendar.CalendarViewModel
import com.macrobase.app.feature.customfoods.CustomFoodsScreen
import com.macrobase.app.feature.customfoods.CustomFoodsViewModel
import com.macrobase.app.feature.customfoods.EditCustomFoodScreen
import com.macrobase.app.feature.dashboard.HomeScreen
import com.macrobase.app.feature.dashboard.HomeViewModel
import com.macrobase.app.feature.detail.FoodDetailScreen
import com.macrobase.app.feature.detail.FoodDetailViewModel
import com.macrobase.app.feature.goals.DailyGoalsScreen
import com.macrobase.app.feature.goals.DailyGoalsViewModel
import com.macrobase.app.feature.importexport.ImportExportScreen
import com.macrobase.app.feature.importexport.ImportExportViewModel
import com.macrobase.app.feature.preferences.PreferencesScreen
import com.macrobase.app.feature.preferences.PreferencesViewModel
import com.macrobase.app.feature.recipes.EditRecipeScreen
import com.macrobase.app.feature.recipes.RecipesScreen
import com.macrobase.app.feature.recipes.RecipesViewModel
import com.macrobase.app.feature.scanner.NutritionLabelScannerScreen
import com.macrobase.app.feature.scanner.NutritionLabelScannerViewModel
import com.macrobase.app.feature.search.SearchScreen
import com.macrobase.app.feature.search.SearchViewModel
import com.macrobase.app.feature.statistics.StatisticsScreen
import com.macrobase.app.feature.statistics.StatisticsViewModel
import com.macrobase.app.feature.water.WaterScreen
import com.macrobase.app.feature.water.WaterViewModel
import com.macrobase.app.feature.weight.WeightScreen
import com.macrobase.app.feature.weight.WeightViewModel
import org.koin.androidx.compose.koinViewModel
import java.time.LocalDate

@Composable
fun NavGraph(
    navController: NavHostController,
    homeViewModel: HomeViewModel = koinViewModel(),
    modifier: Modifier = Modifier
) {
    NavHost(
        navController = navController,
        startDestination = Screen.Home.route,
        modifier = modifier
    ) {
        // Home Dashboard
        composable(
            route = Screen.Home.route,
            arguments = listOf(
                navArgument("dateEpochDay") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                }
            )
        ) { backStackEntry ->
            val dateEpochDayArg = backStackEntry.arguments?.getString("dateEpochDay")?.toLongOrNull()

            LaunchedEffect(dateEpochDayArg) {
                if (dateEpochDayArg != null) {
                    homeViewModel.onSelectDate(LocalDate.ofEpochDay(dateEpochDayArg))
                }
            }

            HomeScreen(
                viewModel = homeViewModel,
                onNavigateToSearch = { mealType, dateEpochDay ->
                    navController.navigate(Screen.Search.createRoute(mealType.name, dateEpochDay))
                },
                onNavigateToDetail = { foodId, mealType, dateEpochDay, entryId ->
                    navController.navigate(Screen.FoodDetail.createRoute(foodId, mealType.name, dateEpochDay, entryId))
                },
                onNavigateToCalendar = {
                    navController.navigate(Screen.Calendar.route)
                },
                onNavigateToWater = { dateEpochDay ->
                    navController.navigate(Screen.Water.createRoute(dateEpochDay))
                },
                onNavigateToWeight = { dateEpochDay ->
                    navController.navigate(Screen.Weight.createRoute(dateEpochDay))
                }
            )
        }

        // Food Search
        composable(
            route = Screen.Search.route,
            arguments = listOf(
                navArgument("mealType") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
                navArgument("dateEpochDay") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                }
            )
        ) { backStackEntry ->
            val mealTypeArg = backStackEntry.arguments?.getString("mealType")
            val dateEpochDayArg = backStackEntry.arguments?.getString("dateEpochDay")?.toLongOrNull()
            val mealType = mealTypeArg?.let { runCatching { MealType.valueOf(it) }.getOrNull() }
            val viewModel: SearchViewModel = koinViewModel()
            SearchScreen(
                viewModel = viewModel,
                mealType = mealType,
                dateEpochDay = dateEpochDayArg,
                onFoodClick = { foodId, targetMealType, dateEp ->
                    navController.navigate(Screen.FoodDetail.createRoute(foodId, targetMealType.name, dateEp))
                },
                onCreateCustomFood = {
                    navController.navigate(Screen.EditCustomFood.createRoute())
                }
            )
        }

        // Food Detail & Log
        composable(
            route = Screen.FoodDetail.route,
            arguments = listOf(
                navArgument("foodId") { type = NavType.LongType },
                navArgument("mealType") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
                navArgument("dateEpochDay") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
                navArgument("entryId") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
                navArgument("basketItemId") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                }
            )
        ) { backStackEntry ->
            val foodId = backStackEntry.arguments?.getLong("foodId") ?: 0L
            val mealTypeArg = backStackEntry.arguments?.getString("mealType")
            val dateEpochDayArg = backStackEntry.arguments?.getString("dateEpochDay")?.toLongOrNull()
            val entryIdArg = backStackEntry.arguments?.getString("entryId")?.toLongOrNull()
            val basketItemIdArg = backStackEntry.arguments?.getString("basketItemId")

            val mealType = mealTypeArg?.let { runCatching { MealType.valueOf(it) }.getOrNull() }
            val targetDate = dateEpochDayArg?.let { LocalDate.ofEpochDay(it) }

            FoodDetailScreen(
                foodId = foodId,
                mealType = mealType,
                date = targetDate,
                entryId = entryIdArg,
                basketItemId = basketItemIdArg,
                onNavigateBack = {
                    if (basketItemIdArg != null) {
                        navController.popBackStack()
                    } else {
                        navController.popBackStack(Screen.Home.route, inclusive = false)
                    }
                }
            )
        }

        // Food Logging Calendar
        composable(Screen.Calendar.route) {
            val viewModel: CalendarViewModel = koinViewModel()
            CalendarScreen(
                viewModel = viewModel,
                onDateClick = { selectedDate ->
                    homeViewModel.onSelectDate(selectedDate)
                    navController.navigate(Screen.Home.dateRoute(selectedDate)) {
                        popUpTo(Screen.Home.route.substringBefore("?")) {
                            saveState = false
                        }
                        launchSingleTop = true
                    }
                }
            )
        }

        // Weight Tracker
        composable(
            route = Screen.Weight.route,
            arguments = listOf(
                navArgument("dateEpochDay") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                }
            )
        ) { backStackEntry ->
            val dateEpochDayArg = backStackEntry.arguments?.getString("dateEpochDay")?.toLongOrNull()
            val viewModel: WeightViewModel = koinViewModel()
            androidx.compose.runtime.LaunchedEffect(dateEpochDayArg) {
                if (dateEpochDayArg != null) {
                    viewModel.onSelectDate(LocalDate.ofEpochDay(dateEpochDayArg))
                }
            }
            WeightScreen(
                viewModel = viewModel,
                onNavigateBack = { navController.popBackStack() }
            )
        }

        // Water Tracker
        composable(
            route = Screen.Water.route,
            arguments = listOf(
                navArgument("dateEpochDay") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                }
            )
        ) { backStackEntry ->
            val dateEpochDayArg = backStackEntry.arguments?.getString("dateEpochDay")?.toLongOrNull()
            val viewModel: WaterViewModel = koinViewModel()
            androidx.compose.runtime.LaunchedEffect(dateEpochDayArg) {
                if (dateEpochDayArg != null) {
                    viewModel.onSelectDate(LocalDate.ofEpochDay(dateEpochDayArg))
                }
            }
            WaterScreen(
                viewModel = viewModel,
                onNavigateBack = { navController.popBackStack() }
            )
        }

        // Statistics
        composable(Screen.Statistics.route) {
            val viewModel: StatisticsViewModel = koinViewModel()
            val calendarViewModel: CalendarViewModel = koinViewModel()
            StatisticsScreen(
                viewModel = viewModel,
                calendarViewModel = calendarViewModel,
                onDateClick = { selectedDate ->
                    homeViewModel.onSelectDate(selectedDate)
                    navController.navigate(Screen.Home.dateRoute(selectedDate)) {
                        popUpTo(Screen.Home.route.substringBefore("?")) {
                            saveState = false
                        }
                        launchSingleTop = true
                    }
                }
            )
        }

        // Custom Foods
        composable(Screen.CustomFoods.route) {
            val viewModel: CustomFoodsViewModel = koinViewModel()
            CustomFoodsScreen(
                viewModel = viewModel,
                onCreateClick = { navController.navigate(Screen.EditCustomFood.createRoute()) },
                onEditClick = { foodId -> navController.navigate(Screen.EditCustomFood.createRoute(foodId)) },
                onFoodClick = { foodId -> navController.navigate(Screen.FoodDetail.createRoute(foodId)) }
            )
        }

        // Edit / Create Custom Food
        composable(
            route = Screen.EditCustomFood.route,
            arguments = listOf(navArgument("foodId") {
                type = NavType.LongType
                defaultValue = 0L
            })
        ) { backStackEntry ->
            val foodId = backStackEntry.arguments?.getLong("foodId") ?: 0L
            val viewModel: CustomFoodsViewModel = koinViewModel()

            // Observe scanned draft returned from Scanner
            val scannedDraftFlow = backStackEntry.savedStateHandle.getStateFlow<com.macrobase.app.domain.model.scanner.NutritionLabelDraft?>("scanned_nutrition_draft", null)
            val scannedDraft by scannedDraftFlow.collectAsState()
            androidx.compose.runtime.LaunchedEffect(scannedDraft) {
                scannedDraft?.let { draft ->
                    viewModel.setScannedDraft(draft)
                    backStackEntry.savedStateHandle.remove<com.macrobase.app.domain.model.scanner.NutritionLabelDraft>("scanned_nutrition_draft")
                }
            }

            EditCustomFoodScreen(
                foodId = foodId,
                viewModel = viewModel,
                onScanLabelClick = {
                    navController.navigate(Screen.NutritionLabelScanner.route)
                },
                onSaveSuccess = { navController.popBackStack() }
            )
        }

        // Nutrition Label Scanner (CameraX + ML Kit OCR)
        composable(Screen.NutritionLabelScanner.route) {
            val scannerViewModel: NutritionLabelScannerViewModel = koinViewModel()
            NutritionLabelScannerScreen(
                viewModel = scannerViewModel,
                onNavigateBack = { navController.popBackStack() },
                onDraftExtracted = { draft ->
                    navController.previousBackStackEntry?.savedStateHandle?.set("scanned_nutrition_draft", draft)
                    navController.popBackStack()
                }
            )
        }

        // Recipes List
        composable(Screen.Recipes.route) {
            val viewModel: RecipesViewModel = koinViewModel()
            RecipesScreen(
                viewModel = viewModel,
                onCreateClick = { navController.navigate(Screen.EditRecipe.createRoute()) },
                onEditClick = { recipeId -> navController.navigate(Screen.EditRecipe.createRoute(recipeId)) },
                onLogSuccess = { navController.navigate(Screen.Home.route) }
            )
        }

        // Edit / Create Recipe
        composable(
            route = Screen.EditRecipe.route,
            arguments = listOf(navArgument("recipeId") {
                type = NavType.LongType
                defaultValue = 0L
            })
        ) { backStackEntry ->
            val recipeId = backStackEntry.arguments?.getLong("recipeId") ?: 0L
            val viewModel: RecipesViewModel = koinViewModel()
            EditRecipeScreen(
                recipeId = recipeId,
                viewModel = viewModel,
                onSaveSuccess = { navController.popBackStack() }
            )
        }

        // Daily Goals
        composable(Screen.DailyGoals.route) {
            val viewModel: DailyGoalsViewModel = koinViewModel()
            DailyGoalsScreen(
                viewModel = viewModel,
                onSaveSuccess = { navController.popBackStack() }
            )
        }

        // Preferences
        composable(Screen.Preferences.route) {
            val viewModel: PreferencesViewModel = koinViewModel()
            PreferencesScreen(
                viewModel = viewModel,
                onNavigateToImportExport = { navController.navigate(Screen.ImportExport.route) }
            )
        }

        // Import & Export
        composable(Screen.ImportExport.route) {
            val viewModel: ImportExportViewModel = koinViewModel()
            ImportExportScreen(viewModel = viewModel)
        }

        // Basket
        composable(Screen.Basket.route) {
            val viewModel: com.macrobase.app.feature.basket.BasketViewModel = koinViewModel()
            com.macrobase.app.feature.basket.BasketScreen(
                viewModel = viewModel,
                onNavigateBack = { navController.popBackStack() },
                onNavigateToSearch = { navController.navigate(Screen.Search.createRoute()) },
                onNavigateToDashboard = { navController.popBackStack(Screen.Home.route, inclusive = false) },
                onEditItem = { basketItemId, foodId ->
                    navController.navigate(Screen.FoodDetail.createRoute(foodId = foodId, basketItemId = basketItemId))
                }
            )
        }
    }
}
