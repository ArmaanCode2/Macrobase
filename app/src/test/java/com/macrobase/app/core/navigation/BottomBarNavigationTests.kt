package com.macrobase.app.core.navigation

import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.ComposeNavigator
import androidx.navigation.compose.composable
import androidx.navigation.createGraph
import androidx.navigation.navArgument
import com.macrobase.app.data.repository.basket.InMemoryBasketRepository
import com.macrobase.app.domain.model.DailyNutritionSummary
import com.macrobase.app.domain.model.DiaryEntry
import com.macrobase.app.domain.repository.DiaryRepository
import com.macrobase.app.domain.usecase.DeleteDiaryEntryUseCase
import com.macrobase.app.domain.usecase.GetDailyDiaryUseCase
import com.macrobase.app.domain.usecase.basket.AddFoodToBasketUseCase
import com.macrobase.app.feature.dashboard.HomeViewModel
import com.macrobase.app.testutil.MutableTestClock
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.LocalTime

/**
 * The Home tab, the drawer's Home item and a widget tap all go to today's dashboard from any
 * screen, with nothing left above it (1.2.4).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BottomBarNavigationTests {

    private val today = LocalDate.of(2026, 10, 13)

    // MacroBaseScaffold draws the bar from this list, Statistics as the raised middle button
    @Test
    fun theBarIsHomeCustomFoodsStatsRankSettingsAndEachTabOpensItsScreen() {
        assertEquals(
            listOf(Screen.Home, Screen.CustomFoods, Screen.Statistics, Screen.Rank, Screen.Preferences),
            Screen.bottomNavScreens
        )
        assertEquals(listOf("Home", "Custom Foods", "Stats", "Rank", "Settings"), Screen.bottomNavScreens.map(::bottomTabLabel))

        val navController = navController()
        Screen.bottomNavScreens.reversed().forEach { screen ->
            navController.navigateToTopLevel(screen, homeViewModel())
            assertEquals(screen.route, navController.currentDestination?.route)
        }
    }

    @Test
    fun homeFromStatsAfterAPastDayOpensTodayWithNothingAbove() {
        val homeViewModel = homeViewModel()
        val navController = navController()
        // As the calendar opens a past day
        navController.navigate(Screen.Home.dateRoute(today.minusDays(3))) {
            popUpTo(Screen.Home.route.substringBefore("?"))
            launchSingleTop = true
        }
        homeViewModel.onSelectDate(today.minusDays(3))
        navController.navigateToTopLevel(Screen.Statistics, homeViewModel)

        navController.navigateToTopLevel(Screen.Home, homeViewModel)

        assertOnlyHome(navController)
        assertEquals(today, homeViewModel.selectedDate.value)
    }

    // Each tab switch saves the screens it closes; none of them may come back on a Home tap
    @Test
    fun homeAfterSwitchingTabsOpensTheDashboardNotTheLastTab() {
        val navController = navController()
        navController.navigateToTopLevel(Screen.Statistics, homeViewModel())
        navController.navigateToTopLevel(Screen.Rank, homeViewModel())
        navController.navigateToTopLevel(Screen.Preferences, homeViewModel())
        navController.navigate(Screen.ImportExport.route)

        navController.navigateToTopLevel(Screen.Home, homeViewModel())
        assertOnlyHome(navController)

        // A second tap on Home, and Home after another tab, still land on the dashboard
        navController.navigateToTopLevel(Screen.Home, homeViewModel())
        assertOnlyHome(navController)
        navController.navigateToTopLevel(Screen.Rank, homeViewModel())
        navController.navigateToTopLevel(Screen.Home, homeViewModel())
        assertOnlyHome(navController)
    }

    // The widget tap takes the same path, from whatever screen the app was left on
    @Test
    fun homeFromADeepScreenClosesEveryScreenAboveTheDashboard() {
        val homeViewModel = homeViewModel()
        val navController = navController()
        navController.navigateToTopLevel(Screen.CustomFoods, homeViewModel)
        navController.navigate(Screen.EditCustomFood.createRoute(7L))
        navController.navigate(Screen.Search.createRoute())

        navController.navigateToTodayDashboard(homeViewModel)

        assertOnlyHome(navController)
        assertEquals(today, homeViewModel.selectedDate.value)
    }

    @Test
    fun homeOnTheDashboardItselfStaysOnOneDashboard() {
        val navController = navController()

        navController.navigateToTopLevel(Screen.Home, homeViewModel())

        assertOnlyHome(navController)
    }

    @Test
    fun customFoodsFromTheTabOpensTheListNotAHalfFilledForm() {
        val navController = navController()
        navController.navigateToTopLevel(Screen.CustomFoods, homeViewModel())
        navController.navigate(Screen.EditCustomFood.createRoute())
        navController.navigateToTopLevel(Screen.Statistics, homeViewModel())

        navController.navigateToTopLevel(Screen.CustomFoods, homeViewModel())

        assertCustomFoodsListOverHome(navController)
    }

    // The drawer opens Custom Foods the same way, after Home left the saved form behind
    @Test
    fun customFoodsFromTheDrawerAfterHomeOpensTheListNotAHalfFilledForm() {
        val navController = navController()
        navController.navigateToTopLevel(Screen.CustomFoods, homeViewModel())
        navController.navigate(Screen.EditCustomFood.createRoute())
        navController.navigateToTopLevel(Screen.Rank, homeViewModel())
        navController.navigateToTopLevel(Screen.Home, homeViewModel())

        navController.navigateToTopLevel(Screen.CustomFoods, homeViewModel())
        assertCustomFoodsListOverHome(navController)

        // Tapping Custom Foods while its form is open also goes back to the list
        navController.navigate(Screen.EditCustomFood.createRoute())
        navController.navigateToTopLevel(Screen.CustomFoods, homeViewModel())
        assertCustomFoodsListOverHome(navController)
    }

    private fun assertCustomFoodsListOverHome(navController: NavHostController) {
        assertEquals(Screen.CustomFoods.route, navController.currentDestination?.route)
        assertEquals(Screen.Home.route, navController.previousBackStackEntry?.destination?.route)
    }

    private fun assertOnlyHome(navController: NavHostController) {
        assertEquals(Screen.Home.route, navController.currentDestination?.route)
        assertNull("a screen is left under the dashboard", navController.previousBackStackEntry)
        // The day comes from the ViewModel; a day left in the route would be applied again
        assertNull(navController.currentBackStackEntry?.arguments?.getString("dateEpochDay"))
    }

    private fun navController(): NavHostController {
        val navController = NavHostController(RuntimeEnvironment.getApplication())
        navController.navigatorProvider.addNavigator(ComposeNavigator())
        navController.graph = navController.createGraph(startDestination = Screen.Home.route) {
            composable(
                Screen.Home.route,
                arguments = listOf(navArgument("dateEpochDay") { type = NavType.StringType; nullable = true; defaultValue = null })
            ) {}
            composable(
                Screen.Search.route,
                arguments = listOf(
                    navArgument("mealType") { type = NavType.StringType; nullable = true; defaultValue = null },
                    navArgument("dateEpochDay") { type = NavType.StringType; nullable = true; defaultValue = null }
                )
            ) {}
            composable(Screen.Statistics.route) {}
            composable(Screen.Rank.route) {}
            composable(Screen.Preferences.route) {}
            composable(Screen.ImportExport.route) {}
            composable(Screen.CustomFoods.route) {}
            composable(
                Screen.EditCustomFood.route,
                arguments = listOf(navArgument("foodId") { type = NavType.LongType; defaultValue = 0L })
            ) {}
        }
        return navController
    }

    private fun homeViewModel(): HomeViewModel {
        val diary = EmptyDiaryRepository()
        return HomeViewModel(
            getDailyDiaryUseCase = GetDailyDiaryUseCase(diary),
            deleteDiaryEntryUseCase = DeleteDiaryEntryUseCase(diary),
            addFoodToBasketUseCase = AddFoodToBasketUseCase(InMemoryBasketRepository()),
            clock = MutableTestClock(today, LocalTime.NOON)
        )
    }

    private class EmptyDiaryRepository : DiaryRepository {
        override suspend fun addEntries(entries: List<DiaryEntry>) {}
        override suspend fun addEntry(entry: DiaryEntry): Long = 0L
        override suspend fun getDiaryForDate(date: LocalDate) = DailyNutritionSummary(date = date)
        override fun observeDiaryForDate(date: LocalDate) = flowOf(DailyNutritionSummary(date = date))
        override suspend fun getEntryById(entryId: Long): DiaryEntry? = null
        override suspend fun updateEntry(entry: DiaryEntry) {}
        override suspend fun deleteEntry(entryId: Long) {}
        override suspend fun getMonthlyAdherence(year: Int, month: Int) = emptyList<com.macrobase.app.domain.model.CalendarDaySummary>()
        override fun observeMonthlyAdherence(year: Int, month: Int) = flowOf(emptyList<com.macrobase.app.domain.model.CalendarDaySummary>())
        override fun observeNutritionAggregations(startDate: LocalDate, endDate: LocalDate) = flowOf(emptyList<com.macrobase.app.domain.repository.DailyMacroAggregation>())
        override suspend fun getNutritionAggregations(startDate: LocalDate, endDate: LocalDate) = emptyList<com.macrobase.app.domain.repository.DailyMacroAggregation>()
    }
}
