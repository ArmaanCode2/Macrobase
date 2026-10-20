package com.macrobase.app.core.navigation

import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NamedNavArgument
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.Box
import androidx.compose.animation.AnimatedContentScope
import com.macrobase.app.core.designsystem.components.MbMotion
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.runtime.remember
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.fadeOut
import androidx.compose.animation.fadeIn
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.snap
import androidx.compose.animation.AnimatedContentTransitionScope
import com.macrobase.app.feature.foods.FOODS_VIEW_RECIPES
import com.macrobase.app.feature.foods.FOODS_VIEW_CUSTOM
import com.macrobase.app.feature.foods.FOODS_VIEW_KEY
import com.macrobase.app.feature.foods.FoodsScreen
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavBackStackEntry
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

/**
 * Pops [entry] only while it is the screen on top. A Back tap during a save, or a save finishing
 * after Back was tapped, would otherwise pop the screen underneath as well (BUG-011).
 */
private fun NavHostController.popIfCurrent(entry: NavBackStackEntry) {
    if (currentBackStackEntry?.id == entry.id) popBackStack()
}

/**
 * A destination whose screen ignores touches while it is not the current one. The screen being left
 * is hidden at once but stays composed through the next screen's 220-280 ms entrance, and after Back
 * or the Home tab it is the one on top, so a quick tap would otherwise land on it.
 */
private fun NavGraphBuilder.screen(
    navController: NavHostController,
    route: String,
    arguments: List<NamedNavArgument> = emptyList(),
    content: @Composable AnimatedContentScope.(NavBackStackEntry) -> Unit
) {
    composable(route = route, arguments = arguments) { entry ->
        val animatedScope = this
        val current by navController.currentBackStackEntryAsState()
        Box(propagateMinConstraints = true) {
            animatedScope.content(entry)
            if (current != null && current?.id != entry.id) {
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .pointerInput(Unit) {
                            awaitPointerEventScope {
                                while (true) awaitPointerEvent().changes.forEach { it.consume() }
                            }
                        }
                )
            }
        }
    }
}

/** Whether a navigation goes from one tab root to another: the bottom bar, Home's avatar, the "+" sheet. */
private fun AnimatedContentTransitionScope<NavBackStackEntry>.isTabSwitch(): Boolean =
    isTabRootRoute(initialState.destination.route) && isTabRootRoute(targetState.destination.route)

@Composable
fun NavGraph(
    navController: NavHostController,
    homeViewModel: HomeViewModel = koinViewModel(),
    modifier: Modifier = Modifier
) {
    // The demo's screen motion (designs/app/shell.css): a tab fades in rising 6 dp over 220 ms, a
    // pushed screen fades in from 28 dp to the right over 280 ms, and going back from 20 dp to the
    // left over 240 ms. The screen being left is hidden at once, as in the demo, so two screens are
    // never drawn over each other (the library's default is a 700 ms cross-fade of both).
    val density = LocalDensity.current
    val tabEnter = remember(density) {
        val rise = with(density) { 6.dp.roundToPx() }
        fadeIn(tween(220, easing = MbMotion.Ease)) + slideInVertically(tween(220, easing = MbMotion.Ease)) { rise }
    }
    val pushEnter = remember(density) {
        val shift = with(density) { 28.dp.roundToPx() }
        fadeIn(tween(280, easing = MbMotion.Ease)) + slideInHorizontally(tween(280, easing = MbMotion.Ease)) { shift }
    }
    val popEnter = remember(density) {
        val shift = with(density) { 20.dp.roundToPx() }
        fadeIn(tween(240, easing = MbMotion.Ease)) + slideInHorizontally(tween(240, easing = MbMotion.Ease)) { -shift }
    }
    // The camera preview is a SurfaceView, which cannot follow a slide on older Android versions
    val scannerEnter = remember { fadeIn(tween(280, easing = MbMotion.Ease)) }
    val leave = remember { fadeOut(snap()) }

    NavHost(
        navController = navController,
        startDestination = Screen.Home.route,
        modifier = modifier,
        enterTransition = {
            when {
                isTabSwitch() -> tabEnter
                targetState.destination.route == Screen.NutritionLabelScanner.route -> scannerEnter
                else -> pushEnter
            }
        },
        exitTransition = { leave },
        popEnterTransition = { if (isTabSwitch()) tabEnter else popEnter },
        popExitTransition = { leave }
    ) {
        // Home Dashboard
        screen(
            navController = navController,
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

            // A day passed in the route is applied once. Coming back to Home (Back, rotation) must
            // not undo a later day change or the move to a new day after midnight (BUG-040)
            var appliedDateArg by rememberSaveable { mutableStateOf<Long?>(null) }
            LaunchedEffect(dateEpochDayArg) {
                if (dateEpochDayArg != null && dateEpochDayArg != appliedDateArg) {
                    appliedDateArg = dateEpochDayArg
                    homeViewModel.onSelectDate(LocalDate.ofEpochDay(dateEpochDayArg))
                }
            }

            val basketViewModel: com.macrobase.app.feature.basket.BasketViewModel = koinViewModel()
            val basketItems by basketViewModel.items.collectAsState()

            HomeScreen(
                viewModel = homeViewModel,
                basketCount = basketItems.size,
                onOpenSearch = { navController.navigate(Screen.Search.createRoute()) },
                onScanLabel = {
                    // The scan fills a new custom food form: the form opens first, so the result lands in it
                    navController.navigate(Screen.EditCustomFood.createRoute())
                    navController.navigate(Screen.NutritionLabelScanner.route)
                },
                onOpenBasket = { navController.navigate(Screen.Basket.route) },
                onOpenProfile = { navController.navigateToTopLevel(Screen.Preferences, homeViewModel) },
                onLogWeight = { dateEpochDay ->
                    navController.navigate(Screen.Weight.createRoute(dateEpochDay, openLog = true))
                },
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
        screen(
            navController = navController,
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
                },
                onNavigateBack = { navController.popIfCurrent(backStackEntry) }
            )
        }

        // Food Detail & Log
        screen(
            navController = navController,
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
                onNavigateBack = { navController.popIfCurrent(backStackEntry) }
            )
        }

        // Food Logging Calendar
        screen(navController, Screen.Calendar.route) {
            val viewModel: CalendarViewModel = koinViewModel()
            LaunchedEffect(viewModel) { viewModel.onShown() }
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
        screen(
            navController = navController,
            route = Screen.Weight.route,
            arguments = listOf(
                navArgument("dateEpochDay") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
                navArgument("openLog") {
                    type = NavType.BoolType
                    defaultValue = false
                }
            )
        ) { backStackEntry ->
            val dateEpochDayArg = backStackEntry.arguments?.getString("dateEpochDay")?.toLongOrNull()
            val openLog = backStackEntry.arguments?.getBoolean("openLog") ?: false
            val viewModel: WeightViewModel = koinViewModel()
            androidx.compose.runtime.LaunchedEffect(dateEpochDayArg) {
                if (dateEpochDayArg != null) {
                    viewModel.onSelectDate(LocalDate.ofEpochDay(dateEpochDayArg))
                }
            }
            WeightScreen(
                viewModel = viewModel,
                onNavigateBack = { navController.popBackStack() },
                openLogOnStart = openLog
            )
        }

        // Water Tracker
        screen(
            navController = navController,
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
        screen(navController, Screen.Statistics.route) {
            val viewModel: StatisticsViewModel = koinViewModel()
            val calendarViewModel: CalendarViewModel = koinViewModel()
            StatisticsScreen(
                viewModel = viewModel,
                calendarViewModel = calendarViewModel,
                onDateClick = { selectedDate ->
                    homeViewModel.onSelectDate(selectedDate)
                    navController.navigate(Screen.Home.dateRoute(selectedDate)) {
                        // Stats is kept, so it comes back as it was
                        popUpTo(Screen.Home.route.substringBefore("?")) {
                            saveState = true
                        }
                        launchSingleTop = true
                    }
                }
            )
        }

        // Foods tab: custom foods and recipes, switched in the dock
        screen(navController, Screen.CustomFoods.route) { backStackEntry ->
            FoodsDestination(navController, backStackEntry, initialView = FOODS_VIEW_CUSTOM, showSwitchInContent = false)
        }

        // Edit / Create Custom Food
        screen(
            navController = navController,
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
                onSaveSuccess = { navController.popIfCurrent(backStackEntry) }
            )
        }

        // Nutrition Label Scanner (CameraX + ML Kit OCR)
        screen(navController, Screen.NutritionLabelScanner.route) {
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

        // Recipes opened as a pushed screen: the Foods views with their switch at the top
        screen(navController, Screen.Recipes.route) { backStackEntry ->
            FoodsDestination(navController, backStackEntry, initialView = FOODS_VIEW_RECIPES, showSwitchInContent = true)
        }

        // Edit / Create Recipe
        screen(
            navController = navController,
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
                onSaveSuccess = { navController.popIfCurrent(backStackEntry) }
            )
        }

        // Daily Goals (Forwarded to PreferencesScreen for backward compatibility)
        screen(navController, Screen.DailyGoals.route) {
            val viewModel: PreferencesViewModel = koinViewModel()
            PreferencesScreen(
                viewModel = viewModel,
                onNavigateToImportExport = { navController.navigate(Screen.ImportExport.route) },
                onOpenWeight = { navController.navigate(Screen.Weight.createRoute()) },
                onOpenWater = { navController.navigate(Screen.Water.createRoute()) },
                onOpenRank = { navController.navigateToTopLevel(Screen.Rank, homeViewModel) }
            )
        }

        // Preferences
        screen(navController, Screen.Preferences.route) {
            val viewModel: PreferencesViewModel = koinViewModel()
            PreferencesScreen(
                viewModel = viewModel,
                onNavigateToImportExport = { navController.navigate(Screen.ImportExport.route) },
                onOpenWeight = { navController.navigate(Screen.Weight.createRoute()) },
                onOpenWater = { navController.navigate(Screen.Water.createRoute()) },
                onOpenRank = { navController.navigateToTopLevel(Screen.Rank, homeViewModel) }
            )
        }

        // Import & Export
        screen(navController, Screen.ImportExport.route) {
            val viewModel: ImportExportViewModel = koinViewModel()
            ImportExportScreen(viewModel = viewModel)
        }

        // Basket
        screen(navController, Screen.Basket.route) { backStackEntry ->
            val viewModel: com.macrobase.app.feature.basket.BasketViewModel = koinViewModel()
            com.macrobase.app.feature.basket.BasketScreen(
                viewModel = viewModel,
                onNavigateBack = { navController.popIfCurrent(backStackEntry) },
                onNavigateToSearch = { navController.navigate(Screen.Search.createRoute()) },
                onNavigateToDashboard = { navController.popBackStack(Screen.Home.route, inclusive = false) },
                onEditItem = { basketItemId, foodId ->
                    navController.navigate(Screen.FoodDetail.createRoute(foodId = foodId, basketItemId = basketItemId))
                }
            )
        }

        // Rank
        screen(navController, Screen.Rank.route) {
            val viewModel: com.macrobase.app.feature.rank.RankViewModel = koinViewModel()
            com.macrobase.app.feature.rank.RankScreen(viewModel = viewModel)
        }
    }
}

/**
 * The Foods views on [backStackEntry]: the chosen view lives in its saved state, so the "+" sheet's
 * "Log a recipe" can open the tab on Recipes.
 */
@Composable
private fun FoodsDestination(
    navController: NavHostController,
    backStackEntry: NavBackStackEntry,
    initialView: Int,
    showSwitchInContent: Boolean
) {
    val customFoodsViewModel: CustomFoodsViewModel = koinViewModel()
    val recipesViewModel: RecipesViewModel = koinViewModel()
    val selectedView by backStackEntry.savedStateHandle.getStateFlow(FOODS_VIEW_KEY, initialView).collectAsState()
    FoodsScreen(
        selectedView = selectedView,
        onSelectView = { backStackEntry.savedStateHandle[FOODS_VIEW_KEY] = it },
        showSwitchInContent = showSwitchInContent,
        customFoods = {
            CustomFoodsScreen(
                viewModel = customFoodsViewModel,
                onCreateClick = { navController.navigate(Screen.EditCustomFood.createRoute()) },
                onEditClick = { foodId -> navController.navigate(Screen.EditCustomFood.createRoute(foodId)) },
                onFoodClick = { foodId -> navController.navigate(Screen.FoodDetail.createRoute(foodId)) },
                onScanClick = {
                    // The scan fills a new custom food form: the form opens first, so the result lands in it
                    navController.navigate(Screen.EditCustomFood.createRoute())
                    navController.navigate(Screen.NutritionLabelScanner.route)
                }
            )
        },
        recipes = {
            RecipesScreen(
                viewModel = recipesViewModel,
                onCreateClick = { navController.navigate(Screen.EditRecipe.createRoute()) },
                onEditClick = { recipeId -> navController.navigate(Screen.EditRecipe.createRoute(recipeId)) },
                // Back to the existing dashboard rather than stacking a second one on top
                onLogSuccess = {
                    if (!navController.popBackStack(Screen.Home.route, inclusive = false)) {
                        navController.navigate(Screen.Home.todayRoute())
                    }
                }
            )
        }
    )
}
