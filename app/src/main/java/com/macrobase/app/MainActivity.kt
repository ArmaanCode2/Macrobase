package com.macrobase.app

import org.koin.android.ext.android.get
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.CancellationException
import com.macrobase.app.domain.repository.rank.RankRepository
import androidx.lifecycle.lifecycleScope
import android.util.Log
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.navigation.compose.rememberNavController
import android.content.Intent
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.macrobase.app.core.designsystem.MacroBaseTheme
import com.macrobase.app.core.navigation.MacroBaseScaffold
import com.macrobase.app.core.navigation.NavGraph
import com.macrobase.app.core.navigation.navigateToTodayDashboard
import com.macrobase.app.feature.dashboard.HomeViewModel
import com.macrobase.app.feature.widget.MacroBaseWidgetProvider
import org.koin.androidx.compose.koinViewModel

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow

/**
 * Whether a launch should open today's dashboard for a widget tap. A recreated activity (rotation,
 * or a restore after the process was killed) still carries the intent that started it, and that
 * tap was already handled. So does a task reopened from Recents, which Android relaunches with the
 * intent that first started it (BUG-030). Widgets drawn before 1.2.4 send the old action.
 */
internal fun shouldOpenTodayFromWidget(action: String?, isRecreated: Boolean, launchedFromHistory: Boolean): Boolean =
    (action == MacroBaseWidgetProvider.ACTION_WIDGET_OPEN_TODAY || action == MacroBaseWidgetProvider.LEGACY_ACTION_WIDGET_LOG_FOOD) &&
        !isRecreated && !launchedFromHistory

private const val RANK_WARM_UP_DELAY_MS = 3_000L

class MainActivity : ComponentActivity() {
    // Buffered, so a tap that arrives before the UI collects is kept until it does (BUG-030)
    private val openTodayRequests = Channel<Unit>(Channel.BUFFERED)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        handleWidgetIntent(intent, isRecreated = savedInstanceState != null)

        // A few seconds after the screen opens, the rank starts in the background: the saved
        // profile when nothing changed, and from then on each change once it settles, so the Rank
        // tab opens on a profile that is ready. Only here, not when the widget wakes the app.
        lifecycleScope.launch {
            delay(RANK_WARM_UP_DELAY_MS)
            try {
                get<RankRepository>().observeRankProfile().first()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("MainActivity", "Rank warm-up skipped: ${e.message}")
            }
        }

        setContent {
            MacroBaseTheme {
                val navController = rememberNavController()
                val homeViewModel: HomeViewModel = koinViewModel()

                LaunchedEffect(Unit) {
                    // Navigating before the NavHost has set its graph would throw
                    navController.currentBackStackEntryFlow.first()
                    openTodayRequests.receiveAsFlow().collect {
                        navController.navigateToTodayDashboard(homeViewModel)
                    }
                }

                // A dashboard left on today follows the calendar to the new day (BUG-040)
                LifecycleResumeEffect(homeViewModel) {
                    homeViewModel.onAppResumed()
                    onPauseOrDispose { }
                }

                MacroBaseScaffold(
                    navController = navController,
                    homeViewModel = homeViewModel
                ) { modifier ->
                    NavGraph(
                        navController = navController,
                        homeViewModel = homeViewModel,
                        modifier = modifier
                    )
                }
            }
        }
    }

    // Opening the app redraws the widget. Some phones hold back the midnight refresh and the
    // 30-minute update while the app is closed, and only logging food redrew it before, so a
    // widget could show yesterday's totals well into the next day.
    override fun onStart() {
        super.onStart()
        MacroBaseWidgetProvider.updateAllWidgets(this)
    }

    // The activity is singleTop, so a widget tap while it is running arrives here (BUG-030)
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleWidgetIntent(intent, isRecreated = false)
    }

    private fun handleWidgetIntent(intent: Intent?, isRecreated: Boolean) {
        if (intent == null) return
        val launchedFromHistory = (intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) != 0
        if (!shouldOpenTodayFromWidget(intent.action, isRecreated, launchedFromHistory)) return
        openTodayRequests.trySend(Unit)
        // Handled once: a later recreation of this activity must not leave its screen again
        intent.action = null
    }
}
