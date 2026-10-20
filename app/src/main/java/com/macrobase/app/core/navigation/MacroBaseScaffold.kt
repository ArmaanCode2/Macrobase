package com.macrobase.app.core.navigation

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.material3.Scaffold
import com.macrobase.app.core.designsystem.AppTypography
import androidx.compose.material3.Text
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import com.macrobase.app.core.designsystem.AppColors
import com.macrobase.app.core.designsystem.Dimensions
import com.macrobase.app.core.designsystem.Ph
import com.macrobase.app.core.designsystem.components.MbAura
import com.macrobase.app.core.designsystem.components.MbBasketButton
import com.macrobase.app.core.designsystem.components.MbFab
import com.macrobase.app.core.designsystem.components.MbHeader
import com.macrobase.app.core.designsystem.components.MbViewSwitch
import com.macrobase.app.feature.dashboard.HomeViewModel
import com.macrobase.app.feature.foods.FOODS_VIEW_KEY
import com.macrobase.app.feature.foods.FOODS_VIEW_RECIPES
import org.koin.androidx.compose.koinViewModel

/** The route without its arguments, such as "home" for "home?dateEpochDay={dateEpochDay}". */
private fun String.routeBase(): String = substringBefore("?").substringBefore("/")

/** The tab roots that Back leaves for Home while keeping them to come back to. */
private val TabsKeptOnBack = setOf(Screen.Statistics, Screen.Rank, Screen.Preferences).map { it.route.routeBase() }.toSet()

/** Whether [route] is one of the five tab roots, whatever its arguments. */
internal fun isTabRootRoute(route: String?): Boolean =
    route != null && Screen.bottomNavScreens.any { it.route.routeBase() == route.routeBase() }

/**
 * The app's frame: the aura behind the top of every screen except the scanner, a pinned header,
 * and on the five tab roots the bottom dock (view switch and "+" button above the bar). Pushed
 * screens and the scanner have no dock.
 */
@Composable
fun MacroBaseScaffold(
    navController: NavHostController,
    homeViewModel: HomeViewModel? = null,
    modifier: Modifier = Modifier,
    content: @Composable (Modifier) -> Unit
) {
    val basketViewModel: com.macrobase.app.feature.basket.BasketViewModel = koinViewModel()
    val basketItems by basketViewModel.items.collectAsState()

    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route
    val routeBase = currentRoute?.routeBase()

    val isScanner = routeBase == Screen.NutritionLabelScanner.route.routeBase()
    val isFoodDetail = routeBase == Screen.FoodDetail.route.routeBase()
    val tabIndex = Screen.bottomNavScreens.indexOfFirst { it.route.routeBase() == routeBase }
    val isTabRoot = tabIndex >= 0

    val chrome = remember { ScaffoldChrome() }
    var showAddSheet by rememberSaveable { mutableStateOf(false) }

    val density = LocalDensity.current
    val imeVisible = WindowInsets.ime.getBottom(density) > 0
    val navBarBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val dockInset = if (isTabRoot) Dimensions.ContentBottomPadding + navBarBottom else 0.dp

    val openScanner: () -> Unit = {
        // The scan fills a new custom food form: the form opens first, so the result lands in it
        navController.navigate(Screen.EditCustomFood.createRoute())
        navController.navigate(Screen.NutritionLabelScanner.route)
    }
    val openBasket: () -> Unit = { navController.navigate(Screen.Basket.route) }

    CompositionLocalProvider(
        LocalScaffoldChrome provides chrome,
        LocalDockInset provides dockInset
    ) {
        Box(modifier = modifier.fillMaxSize().background(AppColors.Background)) {
            if (!isScanner) MbAura()

            Scaffold(
                topBar = {
                    when {
                        // Home, Search (its field sits in the header), Rank (its info button), the scanner and Food Detail draw their own top
                        isScanner || isFoodDetail || routeBase == Screen.Home.route.routeBase() ||
                            routeBase == Screen.Search.route.routeBase() ||
                            routeBase == Screen.Rank.route.routeBase() -> Unit
                        isTabRoot -> MbHeader(
                            title = tabTitle(Screen.bottomNavScreens[tabIndex]),
                            scrolled = chrome.isScrolled(navBackStackEntry?.id)
                        ) {
                            if (Screen.bottomNavScreens[tabIndex] == Screen.CustomFoods) {
                                MbBasketButton(count = basketItems.size, onClick = openBasket)
                            }
                        }
                        else -> MbHeader(
                            title = getScreenTitle(currentRoute),
                            onBack = { navController.popBackStack() },
                            scrolled = chrome.isScrolled(navBackStackEntry?.id)
                        ) {
                            if (routeBase == Screen.Basket.route.routeBase() && basketItems.isNotEmpty()) {
                                Text(
                                    text = if (basketItems.size == 1) "1 item" else "${basketItems.size} items",
                                    style = AppTypography.Body2,
                                    color = AppColors.TextSecondary,
                                    modifier = Modifier.padding(end = 6.dp)
                                )
                            }
                        }
                    }
                },
                containerColor = Color.Transparent,
                contentColor = AppColors.TextPrimary,
                // Tab roots scroll under the dock, so they leave the bottom inset to LocalDockInset
                contentWindowInsets = if (isTabRoot) {
                    WindowInsets.systemBars.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)
                } else {
                    ScaffoldDefaults.contentWindowInsets
                }
            ) { paddingValues ->
                // Consumed, so a screen's own Scaffold does not add the system bars a second time
                content(Modifier.padding(paddingValues).consumeWindowInsets(paddingValues))
            }

            // Back from Stats, Rank or Profile keeps the tab, as the Home tab does. Composed after the
            // NavHost, so it is asked first; Home and Foods keep the NavHost's own Back.
            BackHandler(enabled = routeBase in TabsKeptOnBack) { navController.backToHomeKeepingTab() }

            if (isTabRoot && !imeVisible) {
                Dock(
                    tabIndex = tabIndex,
                    dockViews = chrome.dockViewsFor(navBackStackEntry?.id),
                    navBarBottom = navBarBottom,
                    showFab = homeViewModel != null,
                    onTab = { index -> navController.navigateToTopLevel(Screen.bottomNavScreens[index], homeViewModel) },
                    onFab = { showAddSheet = true }
                )
            }
        }

        if (showAddSheet && homeViewModel != null) {
            val onHome = routeBase == Screen.Home.route.routeBase()
            AddFoodSheet(
                homeViewModel = homeViewModel,
                onDismiss = { showAddSheet = false },
                onSearch = { meal ->
                    // On Home the day on screen is used, as Home's own add buttons do; elsewhere today
                    val date = if (onHome) homeViewModel.dateForNewLog() else null
                    navController.navigate(Screen.Search.createRoute(meal.name, date?.toEpochDay()))
                },
                onScanLabel = openScanner,
                onCreateCustomFood = { navController.navigate(Screen.EditCustomFood.createRoute()) },
                onLogRecipe = {
                    // The Foods tab, on its Recipes view
                    navController.navigateToTopLevel(Screen.CustomFoods, homeViewModel)
                    navController.currentBackStackEntry?.savedStateHandle?.set(FOODS_VIEW_KEY, FOODS_VIEW_RECIPES)
                },
                onOpenBasket = openBasket,
                onLogWater = { navController.navigate(Screen.Water.createRoute()) },
                onLogWeight = { navController.navigate(Screen.Weight.createRoute(openLog = true)) }
            )
        }
    }
}

/** The bottom dock: a fade, the view switch and "+" in one row, then the bar. */
@Composable
private fun BoxScope.Dock(
    tabIndex: Int,
    dockViews: DockViews?,
    navBarBottom: Dp,
    showFab: Boolean,
    onTab: (Int) -> Unit,
    onFab: () -> Unit
) {
    val fadeHeight = if (dockViews != null) Dimensions.DockFadeWithSwitch else Dimensions.DockFade
    Box(
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .fillMaxWidth()
            .height(fadeHeight + navBarBottom)
            .background(Brush.verticalGradient(0f to Color.Transparent, 0.62f to AppColors.Background, 1f to AppColors.Background))
    )
    Column(
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .fillMaxWidth()
            .padding(horizontal = Dimensions.DockGap)
            .padding(bottom = Dimensions.DockGap + navBarBottom),
        verticalArrangement = Arrangement.spacedBy(Dimensions.DockGap)
    ) {
        if (dockViews != null || showFab) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Dimensions.DockGap, Alignment.End),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (dockViews != null) {
                    MbViewSwitch(
                        items = dockViews.items,
                        selectedIndex = dockViews.selectedIndex,
                        onSelect = dockViews.onSelect,
                        modifier = Modifier.weight(1f)
                    )
                }
                if (showFab) MbFab(onClick = onFab, contentDescription = "Add food")
            }
        }
        MbBottomBar(
            tabs = Screen.bottomNavScreens.map { BarTab(it, bottomTabLabel(it), barIcon(it)) },
            selectedIndex = tabIndex,
            onSelect = onTab
        )
    }
}

private fun tabTitle(screen: Screen): String = when (screen) {
    Screen.CustomFoods -> "Foods"
    Screen.Statistics -> "Statistics"
    Screen.Rank -> "Rank"
    Screen.Preferences -> "Profile"
    else -> screen.title
}

/** The title of a pushed screen's header, in sentence case. */
private fun getScreenTitle(route: String?): String {
    return when {
        route == null -> "MacroBase"
        route.startsWith("home") -> "Dashboard"
        route.startsWith("search") -> "Search foods"
        route.startsWith("food_detail") -> "Food detail"
        route.startsWith("calendar") -> "Food logging calendar"
        route.startsWith("weight") -> "Weight"
        route.startsWith("water") -> "Water"
        route.startsWith("statistics") -> "Statistics"
        route.startsWith("custom_foods") -> "Foods"
        route.startsWith("edit_custom_food") -> "Custom food"
        route.startsWith("recipes") -> "Recipes"
        route.startsWith("edit_recipe") -> "Recipe"
        route.startsWith("daily_goals") -> "Profile"
        route.startsWith("rank") -> "Rank"
        route.startsWith("preferences") -> "Profile"
        route.startsWith("import_export") -> "Data & backup"
        route.startsWith("basket") -> "Basket"
        else -> "MacroBase"
    }
}

/** A bottom bar tab's label, shown on the active tab and read by screen readers. */
internal fun bottomTabLabel(screen: Screen): String = when (screen) {
    Screen.Home -> "Home"
    Screen.CustomFoods -> "Foods"
    Screen.Statistics -> "Stats"
    Screen.Rank -> "Rank"
    Screen.Preferences -> "Profile"
    else -> screen.title
}

private fun barIcon(screen: Screen): Ph = when (screen) {
    Screen.Home -> Ph.House
    Screen.CustomFoods -> Ph.ForkKnife
    Screen.Statistics -> Ph.ChartBar
    Screen.Rank -> Ph.Trophy
    else -> Ph.User
}

/**
 * Opens a bottom bar tab. Home is always today's dashboard, and the Foods tab always opens on its
 * list.
 */
fun NavHostController.navigateToTopLevel(screen: Screen, homeViewModel: HomeViewModel?) {
    when (screen) {
        // The tab being left is kept, as on a switch between tabs, so it comes back as it was
        Screen.Home -> navigateToTodayDashboard(homeViewModel, keepTabLeft = true)
        // Never restored, so a half-filled food form saved by a tab switch never comes back
        Screen.CustomFoods -> navigateToTab(Screen.CustomFoods.route, restoreState = false)
        else -> navigateToTab(screen.route.substringBefore("?"))
    }
}

/**
 * Today's dashboard, with every screen above it closed. The Home tab and a widget tap both come
 * here, so they behave the same from any screen.
 *
 * Home itself is never restored: a tab's `popUpTo(home) { saveState = true }` also files the
 * screens it closed under Home, so `restoreState` on Home brought those screens back instead of
 * the dashboard. With [keepTabLeft] (the Home tab) the tab being left is saved, so Stats, Rank or
 * Profile come back with their ViewModel, figures and scroll position instead of loading again.
 */
fun NavHostController.navigateToTodayDashboard(homeViewModel: HomeViewModel?, keepTabLeft: Boolean = false) {
    homeViewModel?.resetToToday()
    val save = keepTabLeft && savesTabLeft(openRoutes())
    navigate(Screen.Home.todayRoute()) {
        popUpTo(Screen.Home.route.substringBefore("?")) { saveState = save }
        launchSingleTop = true
    }
}

/**
 * Whether the screens open above Home are saved when they are left, to come back to. The Foods
 * tab is never restored (a half-filled food form must not come back), so its screens are never
 * saved either: a saved copy would only keep its ViewModels in memory.
 */
internal fun savesTabLeft(openRoutes: List<String?>): Boolean = openRoutes.none { it == Screen.CustomFoods.route }

private fun NavHostController.openRoutes(): List<String?> = currentBackStack.value.map { it.destination.route }

/**
 * Back from Stats, Rank or Profile: Home, as before, with the tab kept to come back to, like a tap
 * on the Home tab but without moving Home to today.
 */
fun NavHostController.backToHomeKeepingTab() {
    popBackStack(Screen.Home.route.substringBefore("?"), inclusive = false, saveState = savesTabLeft(openRoutes()))
}

/**
 * A tab above the dashboard. The screens open above Home are saved under the tab they belong to;
 * with [restoreState] this tab's saved screens come back, otherwise it opens fresh.
 */
fun NavHostController.navigateToTab(route: String, restoreState: Boolean = true) {
    val save = savesTabLeft(openRoutes())
    navigate(route) {
        popUpTo(Screen.Home.route.substringBefore("?")) { saveState = save }
        launchSingleTop = true
        this.restoreState = restoreState
    }
}
