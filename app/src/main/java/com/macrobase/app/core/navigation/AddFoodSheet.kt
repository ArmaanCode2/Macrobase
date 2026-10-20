package com.macrobase.app.core.navigation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.unit.dp
import com.macrobase.app.core.designsystem.Ph
import com.macrobase.app.core.designsystem.components.MbChip
import com.macrobase.app.core.designsystem.components.MbChipRow
import com.macrobase.app.core.designsystem.components.MbRowTile
import com.macrobase.app.core.designsystem.components.MbSheet
import com.macrobase.app.core.designsystem.components.mealLabel
import com.macrobase.app.domain.model.MealType
import com.macrobase.app.feature.dashboard.HomeViewModel
import kotlinx.coroutines.launch

/** "Copy breakfast from yesterday", keeping capitals that start a name ("AM snack"). */
internal fun copyFromYesterdayLabel(meal: MealType): String {
    val label = mealLabel(meal)
    val inSentence = if (label.length > 1 && label[1].isUpperCase()) label else label.replaceFirstChar { it.lowercase() }
    return "Copy $inSentence from yesterday"
}

/**
 * C7 The "+" button's sheet: a meal chip row (starting on the meal for the time of day) that sets
 * the meal for the actions below it. Every action closes the sheet first. Copying yesterday's meal
 * fills the basket and opens it; with nothing logged for that meal yesterday the row is disabled.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddFoodSheet(
    homeViewModel: HomeViewModel,
    onDismiss: () -> Unit,
    onSearch: (MealType) -> Unit,
    onScanLabel: () -> Unit,
    onCreateCustomFood: () -> Unit,
    onLogRecipe: () -> Unit,
    onOpenBasket: () -> Unit,
    onLogWater: () -> Unit,
    onLogWeight: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    var meal by rememberSaveable { mutableStateOf(homeViewModel.currentMeal()) }

    val yesterday = remember { homeViewModel.today().minusDays(1) }
    val yesterdayDiary by remember(yesterday) { homeViewModel.summaryFor(yesterday) }.collectAsState(initial = null)
    val yesterdayMealIsEmpty = yesterdayDiary?.meals?.firstOrNull { it.type == meal }?.entries.isNullOrEmpty()

    // Slide the sheet away, then act
    fun go(action: () -> Unit) {
        scope.launch { sheetState.hide() }.invokeOnCompletion {
            onDismiss()
            action()
        }
    }

    MbSheet(onDismiss = onDismiss, title = "Add food", sheetState = sheetState) {
        MbChipRow(modifier = Modifier.padding(top = 8.dp)) {
            MealType.entries.sortedBy { it.order }.forEach { option ->
                MbChip(text = mealLabel(option), selected = option == meal, onClick = { meal = option })
            }
        }
        Column(modifier = Modifier.padding(top = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            MbRowTile(title = "Search foods", icon = Ph.MagnifyingGlass.light, onClick = { go { onSearch(meal) } })
            MbRowTile(title = "Scan nutrition label", icon = Ph.Scan.light, onClick = { go(onScanLabel) })
            MbRowTile(title = "Create custom food", icon = Ph.NotePencil.light, onClick = { go(onCreateCustomFood) })
            MbRowTile(title = "Log a recipe", icon = Ph.CookingPot.light, onClick = { go(onLogRecipe) })
            MbRowTile(
                title = copyFromYesterdayLabel(meal),
                subtitle = if (yesterdayMealIsEmpty) "Nothing logged for ${mealLabel(meal)} yesterday" else null,
                icon = Ph.Copy.light,
                onClick = if (yesterdayMealIsEmpty) null else {
                    {
                        val target = meal
                        go { homeViewModel.copyMealFrom(yesterday, target, toMeal = target, onDone = {}); onOpenBasket() }
                    }
                },
                showChevron = !yesterdayMealIsEmpty,
                modifier = if (yesterdayMealIsEmpty) Modifier.alpha(0.5f) else Modifier
            )
            MbRowTile(title = "Log water", icon = Ph.Drop.light, onClick = { go(onLogWater) })
            MbRowTile(title = "Log weight", icon = Ph.Scales.light, onClick = { go(onLogWeight) })
        }
    }
}
