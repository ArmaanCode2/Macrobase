package com.macrobase.app.feature.foods

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.macrobase.app.core.designsystem.AppSpacing
import com.macrobase.app.core.designsystem.components.MbViewSwitch
import com.macrobase.app.core.navigation.DeclareDockViews

/** The saved-state key of the Foods tab's view: 0 Custom foods, 1 Recipes. */
const val FOODS_VIEW_KEY = "foods_view"
const val FOODS_VIEW_CUSTOM = 0
const val FOODS_VIEW_RECIPES = 1

private val FoodsViews = listOf("Custom foods", "Recipes")

/**
 * The Foods tab: custom foods or recipes, picked with the dock's view switch. Each view starts at
 * the top of its list when shown. Opened as a pushed screen (no dock), the same switch sits at the
 * top of the content instead.
 */
@Composable
fun FoodsScreen(
    selectedView: Int,
    onSelectView: (Int) -> Unit,
    modifier: Modifier = Modifier,
    showSwitchInContent: Boolean = false,
    customFoods: @Composable () -> Unit,
    recipes: @Composable () -> Unit
) {
    if (!showSwitchInContent) {
        DeclareDockViews(items = FoodsViews, selectedIndex = selectedView, onSelect = onSelectView)
    }
    Column(modifier = modifier.fillMaxSize()) {
        if (showSwitchInContent) {
            MbViewSwitch(
                items = FoodsViews,
                selectedIndex = selectedView,
                onSelect = onSelectView,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = AppSpacing.screenMargin, vertical = 8.dp)
            )
        }
        Box(modifier = Modifier.fillMaxSize()) {
            if (selectedView == FOODS_VIEW_RECIPES) recipes() else customFoods()
        }
    }
}
