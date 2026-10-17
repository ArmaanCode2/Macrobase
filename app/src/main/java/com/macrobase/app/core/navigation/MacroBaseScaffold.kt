package com.macrobase.app.core.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FabPosition
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import org.koin.androidx.compose.koinViewModel
import androidx.compose.material3.rememberDrawerState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import com.macrobase.app.core.designsystem.AppColors
import com.macrobase.app.core.designsystem.AppSpacing
import com.macrobase.app.core.designsystem.AppTypography
import com.macrobase.app.core.designsystem.Dimensions
import kotlinx.coroutines.launch

import com.macrobase.app.feature.dashboard.HomeViewModel

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Assessment
import androidx.compose.material.icons.filled.RestaurantMenu
import androidx.compose.material.icons.filled.Settings

/**
 * Main Application Scaffold integrating Top Bar, Drawer, Bottom Nav, and Content.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MacroBaseScaffold(
    navController: NavHostController,
    homeViewModel: HomeViewModel? = null,
    modifier: Modifier = Modifier,
    content: @Composable (Modifier) -> Unit
) {
    val basketViewModel: com.macrobase.app.feature.basket.BasketViewModel = koinViewModel()
    val basketItems by basketViewModel.items.collectAsState()

    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    val isRootScreen = currentRoute?.substringBefore("?") == Screen.Home.route.substringBefore("?")
    val isScanner = currentRoute?.substringBefore("?") == Screen.NutritionLabelScanner.route.substringBefore("?")
    val isFoodDetail = currentRoute?.startsWith("food_detail") == true

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(
                drawerContainerColor = AppColors.Surface
            ) {
                AppDrawer(
                    currentRoute = currentRoute,
                    onNavigate = { screen ->
                        scope.launch { drawerState.close() }
                        navController.navigateToTopLevel(screen, homeViewModel)
                    }
                )
            }
        }
    ) {
        Scaffold(
            topBar = {
                if (isScanner || isFoodDetail) {
                    // Immersive Fullscreen Scanner and Food Detail handle their own top bar
                } else {
                    val isBasket = currentRoute?.substringBefore("?") == Screen.Basket.route.substringBefore("?")
                    
                    if (isRootScreen || isBasket) {
                        // Home Dashboard, Food Detail & Basket Custom Top Bar with Embedded Search Input Pill
                        androidx.compose.material3.TopAppBar(
                            title = {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(Dimensions.SearchInputHeight)
                                        .clip(androidx.compose.foundation.shape.RoundedCornerShape(2.dp))
                                        .background(AppColors.SurfaceAlt)
                                        .clickable { navController.navigate(Screen.Search.createRoute()) }
                                        .padding(horizontal = AppSpacing.sm),
                                    contentAlignment = Alignment.CenterStart
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(
                                            text = "Search foods to log",
                                            style = AppTypography.Body2,
                                            color = AppColors.TextPrimary
                                        )
                                        Icon(
                                            imageVector = androidx.compose.material.icons.Icons.Default.QrCodeScanner,
                                            contentDescription = "Scanner",
                                            tint = AppColors.TextPrimary,
                                            modifier = Modifier.size(Dimensions.IconSizeSmall)
                                        )
                                    }
                                }
                            },
                            navigationIcon = {
                                if (isRootScreen) {
                                    androidx.compose.material3.IconButton(onClick = { scope.launch { drawerState.open() } }) {
                                        Icon(
                                            imageVector = androidx.compose.material.icons.Icons.Default.Menu,
                                            contentDescription = "Navigation Menu",
                                            tint = AppColors.TextPrimary
                                        )
                                    }
                                } else {
                                    androidx.compose.material3.IconButton(onClick = { navController.popBackStack() }) {
                                        Icon(
                                            imageVector = androidx.compose.material.icons.Icons.AutoMirrored.Filled.ArrowBack,
                                            contentDescription = "Back",
                                            tint = AppColors.TextPrimary
                                        )
                                    }
                                }
                            },
                            actions = {
                                androidx.compose.material3.IconButton(onClick = { navController.navigate(Screen.Basket.route) }) {
                                    androidx.compose.material3.BadgedBox(
                                        badge = {
                                            if (basketItems.isNotEmpty()) {
                                                androidx.compose.material3.Badge(containerColor = androidx.compose.ui.graphics.Color(0xFFE53935)) {
                                                    Text(
                                                        text = basketItems.size.toString(),
                                                        color = AppColors.TextPrimary
                                                    )
                                                }
                                            }
                                        }
                                    ) {
                                        Icon(
                                            imageVector = androidx.compose.material.icons.Icons.Default.ShoppingCart,
                                            contentDescription = "Basket",
                                            tint = AppColors.TextPrimary
                                        )
                                    }
                                }
                            },
                            colors = androidx.compose.material3.TopAppBarDefaults.topAppBarColors(
                                containerColor = AppColors.Primary
                            )
                        )
                    } else {
                        // Standard Secondary Top Bar with Back Arrow
                        androidx.compose.material3.TopAppBar(
                            title = {
                                Text(
                                    text = getScreenTitle(currentRoute),
                                    style = AppTypography.Header1,
                                    color = AppColors.TextPrimary
                                )
                            },
                            navigationIcon = {
                                androidx.compose.material3.IconButton(onClick = { navController.popBackStack() }) {
                                    Icon(
                                        imageVector = androidx.compose.material.icons.Icons.AutoMirrored.Filled.ArrowBack,
                                        contentDescription = "Back",
                                        tint = AppColors.TextPrimary
                                    )
                                }
                            },
                            colors = androidx.compose.material3.TopAppBarDefaults.topAppBarColors(
                                containerColor = AppColors.Primary
                            )
                        )
                    }
                }
            },
            bottomBar = {
                if (!isScanner) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(64.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(Dimensions.BottomNavHeight)
                                .align(Alignment.BottomCenter)
                                .background(AppColors.Surface),
                            horizontalArrangement = Arrangement.SpaceEvenly,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // The bar is drawn from Screen.bottomNavScreens, in that order
                            Screen.bottomNavScreens.forEach { screen ->
                                if (screen == Screen.Statistics) {
                                    Spacer(modifier = Modifier.weight(1f)) // Space for the raised Stats button
                                } else {
                                    BottomNavTab(
                                        title = bottomTabLabel(screen),
                                        icon = screen.icon!!,
                                        selected = currentRoute?.substringBefore("?") == screen.route.substringBefore("?"),
                                        onClick = { navController.navigateToTopLevel(screen, homeViewModel) },
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                            }
                        }
                        
                        Box(
                            modifier = Modifier
                                .width(72.dp)
                                .fillMaxHeight()
                                .align(Alignment.TopCenter)
                                .background(AppColors.Primary, androidx.compose.foundation.shape.RoundedCornerShape(2.dp))
                                .clickable { navController.navigateToTopLevel(Screen.Statistics, homeViewModel) },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Screen.Statistics.icon!!, contentDescription = bottomTabLabel(Screen.Statistics), tint = AppColors.TextPrimary, modifier = Modifier.size(28.dp))
                        }
                    }
                }
            },
            floatingActionButton = { },
            floatingActionButtonPosition = androidx.compose.material3.FabPosition.End,
            containerColor = AppColors.Background
        ) { paddingValues ->
            content(Modifier.padding(paddingValues))
        }
    }
}

private fun getScreenTitle(route: String?): String {
    return when {
        route == null -> "MacroBase"
        route.startsWith("home") -> "Dashboard"
        route.startsWith("search") -> "Search Foods"
        route.startsWith("food_detail") -> "Food Details"
        route.startsWith("calendar") -> "Food Logging Calendar"
        route.startsWith("weight") -> "Weight Tracker"
        route.startsWith("water") -> "Water Tracker"
        route.startsWith("statistics") -> "Statistics"
        route.startsWith("custom_foods") -> "Custom Foods"
        route.startsWith("edit_custom_food") -> "Edit Custom Food"
        route.startsWith("recipes") -> "My Recipes"
        route.startsWith("edit_recipe") -> "Edit Recipe"
        route.startsWith("daily_goals") -> "Edit Daily Goals"
        route.startsWith("rank") -> "Rank"
        route.startsWith("preferences") -> "Preferences"
        route.startsWith("import_export") -> "Data & Backup"
        else -> "MacroBase"
    }
}

@Composable
fun BottomNavTab(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxHeight()
            // With no text under the icon, screen readers get the tab role and its selected state
            .selectable(selected = selected, onClick = onClick, role = Role.Tab),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        val color = if (selected) AppColors.Primary else AppColors.TextSecondary
        // Icon only; the title stays as the screen reader label
        Icon(icon, contentDescription = title, tint = color, modifier = Modifier.size(24.dp))
    }
}

/** The screen reader label of a bottom bar tab. The bar shows icons only. */
internal fun bottomTabLabel(screen: Screen): String = when (screen) {
    Screen.Home -> "Home"
    Screen.Statistics -> "Stats"
    Screen.Preferences -> "Settings"
    else -> screen.title
}

/**
 * Opens a bottom bar tab or a drawer item. Home is always today's dashboard, and Custom Foods is
 * always its list.
 */
fun NavHostController.navigateToTopLevel(screen: Screen, homeViewModel: HomeViewModel?) {
    when (screen) {
        Screen.Home -> navigateToTodayDashboard(homeViewModel)
        // Never restored, so a half-filled food form saved by a tab switch never comes back
        Screen.CustomFoods -> navigateToTab(Screen.CustomFoods.route, restoreState = false)
        else -> navigateToTab(screen.route.substringBefore("?"))
    }
}

/**
 * Today's dashboard, with every screen above it closed. The Home tab, the drawer's Home item and a
 * widget tap all come here, so they behave the same from any screen.
 *
 * Nothing is saved or restored here. A tab's `popUpTo(home) { saveState = true }` also files the
 * screens it closed under Home itself, so `restoreState` on Home brought those screens back
 * instead of the dashboard.
 */
fun NavHostController.navigateToTodayDashboard(homeViewModel: HomeViewModel?) {
    homeViewModel?.resetToToday()
    navigate(Screen.Home.todayRoute()) {
        popUpTo(Screen.Home.route.substringBefore("?"))
        launchSingleTop = true
    }
}

/**
 * A tab above the dashboard. The screens open above Home are saved under the tab they belong to;
 * with [restoreState] this tab's saved screens come back, otherwise it opens fresh.
 */
fun NavHostController.navigateToTab(route: String, restoreState: Boolean = true) {
    navigate(route) {
        popUpTo(Screen.Home.route.substringBefore("?")) { saveState = true }
        launchSingleTop = true
        this.restoreState = restoreState
    }
}
