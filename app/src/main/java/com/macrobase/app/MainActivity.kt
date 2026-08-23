package com.macrobase.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.navigation.compose.rememberNavController
import com.macrobase.app.core.designsystem.MacroBaseTheme
import com.macrobase.app.core.navigation.MacroBaseScaffold
import com.macrobase.app.core.navigation.NavGraph
import com.macrobase.app.feature.dashboard.HomeViewModel
import org.koin.androidx.compose.koinViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            MacroBaseTheme {
                val navController = rememberNavController()
                val homeViewModel: HomeViewModel = koinViewModel()
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
}
