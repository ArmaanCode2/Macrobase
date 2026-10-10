package com.macrobase.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.navigation.compose.rememberNavController
import android.content.Intent
import androidx.compose.runtime.LaunchedEffect
import com.macrobase.app.core.designsystem.MacroBaseTheme
import com.macrobase.app.core.navigation.MacroBaseScaffold
import com.macrobase.app.core.navigation.NavGraph
import com.macrobase.app.core.navigation.Screen
import com.macrobase.app.feature.dashboard.HomeViewModel
import com.macrobase.app.feature.widget.MacroBaseWidgetProvider
import org.koin.androidx.compose.koinViewModel

import kotlinx.coroutines.flow.MutableSharedFlow

class MainActivity : ComponentActivity() {
    private val widgetActionFlow = MutableSharedFlow<String?>(extraBufferCapacity = 1)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        if (intent?.action == MacroBaseWidgetProvider.ACTION_WIDGET_LOG_FOOD) {
            widgetActionFlow.tryEmit(intent?.action)
        }

        setContent {
            MacroBaseTheme {
                val navController = rememberNavController()
                val homeViewModel: HomeViewModel = koinViewModel()

                LaunchedEffect(Unit) {
                    widgetActionFlow.collect { action ->
                        if (action == MacroBaseWidgetProvider.ACTION_WIDGET_LOG_FOOD) {
                            navController.navigate(Screen.Search.createRoute(null, null))
                        }
                    }
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

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.action == MacroBaseWidgetProvider.ACTION_WIDGET_LOG_FOOD) {
            widgetActionFlow.tryEmit(intent.action)
        }
    }
}
