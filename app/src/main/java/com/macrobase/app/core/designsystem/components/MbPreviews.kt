package com.macrobase.app.core.designsystem.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.macrobase.app.core.designsystem.AppColors
import com.macrobase.app.core.designsystem.AppTypography
import com.macrobase.app.core.designsystem.MacroBaseTheme
import com.macrobase.app.core.designsystem.Ph
import com.macrobase.app.domain.model.MealType
import java.time.LocalDate

@Composable
private fun PreviewCanvas(content: @Composable () -> Unit) {
    MacroBaseTheme {
        Box(modifier = Modifier.background(AppColors.Background).padding(16.dp)) { content() }
    }
}

@Preview(widthDp = 390)
@Composable
private fun MbPanelPreview() = PreviewCanvas {
    MbPanel(modifier = Modifier.fillMaxWidth()) {
        Text("776", style = AppTypography.Hero)
        Text("/ 2,000 kcal", style = AppTypography.HeroUnit)
    }
}

@Preview(widthDp = 390)
@Composable
private fun MbRowTilePreview() = PreviewCanvas {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        MbRowTile(title = "Vegetable poha", subtitle = "1 bowl (150 g)", icon = Ph.BowlFood.light, value = "272", onClick = {}, opaque = true)
        MbRowTile(title = "Data & backup", icon = Ph.Database.light, onClick = {})
    }
}

@Preview(widthDp = 390)
@Composable
private fun MbButtonPreview() = PreviewCanvas {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        MbButton(text = "Log 1 food", onClick = {}, modifier = Modifier.fillMaxWidth())
        MbButton(text = "Keep in basket", onClick = {}, kind = MbButtonKind.Secondary, modifier = Modifier.fillMaxWidth())
        MbButton(text = "Delete", onClick = {}, kind = MbButtonKind.Danger)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            MbDangerGhostButton(onClick = {}, contentDescription = "Delete")
            MbIconButton(icon = Ph.Basket.light, contentDescription = "Basket", onClick = {}, outlined = true)
            MbFab(onClick = {})
        }
    }
}

@Preview(widthDp = 390)
@Composable
private fun MbChipPreview() = PreviewCanvas {
    var selected by remember { mutableIntStateOf(0) }
    MbChipRow {
        listOf("Last 30 days", "Last 3 months", "1 year").forEachIndexed { index, label ->
            MbChip(text = label, selected = index == selected, onClick = { selected = index })
        }
    }
}

@Preview(widthDp = 390)
@Composable
private fun MbTextFieldPreview() = PreviewCanvas {
    var text by remember { mutableStateOf("72.4") }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        MbTextField(value = text, onValueChange = { text = it }, label = "Current weight", suffix = "kg", helpText = "20-500 kg")
        MbTextField(value = "", onValueChange = {}, label = "Name", placeholder = "Food name", errorText = "Enter a name")
    }
}

@Preview(widthDp = 390)
@Composable
private fun MbHeaderPreview() = PreviewCanvas {
    Column {
        MbHeader(title = "Food detail", onBack = {}, applyStatusBarInset = false)
        MbHeader(title = "Edit entry", onBack = {}, scrolled = true, applyStatusBarInset = false) {
            MbIconButton(icon = Ph.Info.light, contentDescription = "Info", onClick = {}, outlined = true, size = 46.dp)
        }
    }
}

@Preview(widthDp = 390)
@Composable
private fun MbMacroRingPreview() = PreviewCanvas {
    Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
        MbMacroRing(label = "Protein", eatenGrams = 27.0, targetGrams = 125.0, color = AppColors.MacroProtein)
        MbMacroRing(label = "Carbs", eatenGrams = 104.0, targetGrams = 250.0, color = AppColors.MacroCarbs)
        MbMacroRing(label = "Fat", eatenGrams = 0.0, targetGrams = 56.0, color = AppColors.MacroFat)
    }
}

@Preview(widthDp = 390)
@Composable
private fun MbViewSwitchPreview() = PreviewCanvas {
    var two by remember { mutableIntStateOf(0) }
    var five by remember { mutableIntStateOf(1) }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        MbViewSwitch(items = listOf("Custom foods", "Recipes"), selectedIndex = two, onSelect = { two = it }, modifier = Modifier.fillMaxWidth())
        MbViewSwitch(
            items = listOf("Calendar", "Weight", "Consistency", "Averages", "Rank history"),
            selectedIndex = five,
            onSelect = { five = it },
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Preview(widthDp = 390, heightDp = 420)
@Composable
private fun MbAuraPreview() = MacroBaseTheme {
    Box(modifier = Modifier.background(AppColors.Background)) { MbAura() }
}

@Preview(widthDp = 390)
@Composable
private fun MealAndDayPickerPreview() = PreviewCanvas {
    val today = LocalDate.of(2026, 10, 17)
    MealAndDayPicker(
        selectedMeal = MealType.BREAKFAST,
        selectedDate = today,
        today = today,
        onMealSelected = {},
        onDateSelected = {},
        header = "Log to Breakfast, Today"
    )
}
