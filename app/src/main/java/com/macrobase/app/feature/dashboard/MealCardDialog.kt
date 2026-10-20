package com.macrobase.app.feature.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.macrobase.app.core.designsystem.AppColors
import com.macrobase.app.core.designsystem.AppShapes
import com.macrobase.app.core.designsystem.AppTypography
import com.macrobase.app.core.designsystem.Ph
import com.macrobase.app.core.designsystem.components.MbButton
import com.macrobase.app.core.designsystem.components.MbButtonKind
import com.macrobase.app.core.designsystem.components.MbDangerGhostButton
import com.macrobase.app.core.designsystem.components.MbPanel
import com.macrobase.app.core.designsystem.components.MbSheet
import com.macrobase.app.core.designsystem.components.MealAndDayPicker
import com.macrobase.app.core.designsystem.components.mealLabel
import com.macrobase.app.domain.model.Meal
import com.macrobase.app.domain.model.MealType
import com.macrobase.app.feature.basket.basketDateLabel
import java.time.LocalDate
import java.util.Locale
import kotlin.math.roundToInt

/** What the meal card shows: the totals with the meal's actions, the move picker, or the delete check. */
internal enum class MealCardMode { INFO, MOVE, DELETE }

/** "1 food" or "3 foods". */
internal fun foodCountLabel(count: Int): String = if (count == 1) "1 food" else "$count foods"

/** "3 foods \u2022 640 kcal \u2022 Today": the meal a move or delete applies to. */
internal fun mealSummaryLine(meal: Meal, day: LocalDate, today: LocalDate): String =
    "${foodCountLabel(meal.entries.size)} \u2022 ${meal.totalCalories.roundToInt()} kcal \u2022 ${basketDateLabel(day, today)}"

/** A move to the meal's own day and meal changes nothing, so Save waits for another choice. */
internal fun isMealMoveChange(fromDate: LocalDate, fromMeal: MealType, toDate: LocalDate, toMeal: MealType): Boolean =
    toDate != fromDate || toMeal != fromMeal

/**
 * The sheet the meal's (i) opens: its calories and macros and, for a meal with food in it, Copy
 * (every food into the basket), Move (to another meal and day, with the basket's When table) and
 * Delete (after one check). [day] is the day the meal was logged on. While moving or deleting, the
 * sheet stays open until Cancel or the close button returns to the totals, so a stray tap never
 * loses the choice.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MealCardDialog(
    meal: Meal,
    day: LocalDate,
    today: LocalDate,
    onDismiss: () -> Unit,
    onCopy: () -> Unit,
    onMove: (LocalDate, MealType) -> Unit,
    onDelete: () -> Unit
) {
    var mode by remember(meal.type, day) { mutableStateOf(MealCardMode.INFO) }
    var moveToMeal by remember(meal.type, day) { mutableStateOf(meal.type) }
    var moveToDate by remember(meal.type, day) { mutableStateOf(day) }
    val mealName = mealLabel(meal.type)

    val currentMode by rememberUpdatedState(mode)
    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
        confirmValueChange = { value -> value != SheetValue.Hidden || currentMode == MealCardMode.INFO }
    )

    // Cancel returns to the totals and forgets the move choice
    fun backToTotals() {
        mode = MealCardMode.INFO
        moveToMeal = meal.type
        moveToDate = day
    }

    val footer: (@Composable RowScope.() -> Unit)? = when {
        mode == MealCardMode.MOVE -> { {
            MbButton(text = "Cancel", onClick = { backToTotals() }, kind = MbButtonKind.Secondary)
            MbButton(
                text = "Save",
                onClick = { onMove(moveToDate, moveToMeal) },
                enabled = isMealMoveChange(day, meal.type, moveToDate, moveToMeal),
                modifier = Modifier.weight(1f)
            )
        } }
        mode == MealCardMode.DELETE -> { {
            MbButton(text = "Cancel", onClick = { backToTotals() }, kind = MbButtonKind.Secondary)
            MbButton(text = "Delete", onClick = onDelete, kind = MbButtonKind.Danger, modifier = Modifier.weight(1f))
        } }
        meal.entries.isNotEmpty() -> { {
            MbButton(text = "Copy", onClick = onCopy, kind = MbButtonKind.Secondary, icon = Ph.Copy.light, modifier = Modifier.weight(1f))
            MbButton(
                text = "Move",
                onClick = { mode = MealCardMode.MOVE },
                kind = MbButtonKind.Secondary,
                icon = Ph.ArrowsLeftRight.light,
                modifier = Modifier.weight(1f)
            )
            MbDangerGhostButton(onClick = { mode = MealCardMode.DELETE }, contentDescription = "Delete")
        } }
        else -> null
    }

    MbSheet(
        onDismiss = onDismiss,
        title = when (mode) {
            MealCardMode.INFO -> "$mealName nutrition"
            MealCardMode.MOVE -> "Move $mealName"
            MealCardMode.DELETE -> "Delete $mealName?"
        },
        sheetState = sheetState,
        onClose = if (mode == MealCardMode.INFO) null else { { backToTotals() } },
        footer = footer
    ) {
        when (mode) {
            MealCardMode.INFO -> {
                Text(
                    text = "${foodCountLabel(meal.entries.size)} \u2022 ${basketDateLabel(day, today)}",
                    style = SheetNoteStyle,
                    color = AppColors.TextSecondary,
                    modifier = Modifier.padding(top = 4.dp, bottom = 12.dp)
                )
                MealTotals(meal)
                if (meal.entries.isNotEmpty()) {
                    Text(
                        text = "Copy adds these foods to your basket.",
                        style = SheetNoteStyle,
                        color = AppColors.TextSecondary,
                        modifier = Modifier.padding(top = 14.dp)
                    )
                }
            }
            MealCardMode.MOVE -> {
                Text(
                    text = mealSummaryLine(meal, day, today),
                    style = SheetNoteStyle,
                    color = AppColors.TextSecondary,
                    modifier = Modifier.padding(top = 4.dp, bottom = 12.dp)
                )
                MealAndDayPicker(
                    selectedMeal = moveToMeal,
                    selectedDate = moveToDate,
                    today = today,
                    onMealSelected = { moveToMeal = it },
                    onDateSelected = { moveToDate = it },
                    header = "To: ${basketDateLabel(moveToDate, today)}, ${mealLabel(moveToMeal)}"
                )
            }
            MealCardMode.DELETE -> {
                Text(
                    text = mealSummaryLine(meal, day, today),
                    style = SheetNoteStyle,
                    color = AppColors.TextSecondary,
                    modifier = Modifier.padding(top = 4.dp, bottom = 12.dp)
                )
                Text(
                    text = "These foods will be removed from your diary. You can't undo this.",
                    style = AppTypography.Body1,
                    color = AppColors.TextPrimary
                )
            }
        }
    }
}

private val SheetNoteStyle = AppTypography.Body2.copy(fontSize = 13.sp)

/** The meal's calories, protein, carbs and fat as four tiles. */
@Composable
private fun MealTotals(meal: Meal) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TotalTile(label = "Calories", value = "${meal.totalCalories.roundToInt()} kcal")
            TotalTile(label = "Protein", value = gramsLabel(meal.totalProtein))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TotalTile(label = "Carbs", value = gramsLabel(meal.totalCarbs))
            TotalTile(label = "Fat", value = gramsLabel(meal.totalFat))
        }
    }
}

private fun gramsLabel(grams: Double): String = "${String.format(Locale.US, "%.1f", grams)} g"

@Composable
private fun RowScope.TotalTile(label: String, value: String) {
    MbPanel(
        modifier = Modifier.weight(1f),
        shape = AppShapes.CardSmall,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Text(text = label, style = AppTypography.Caption, color = AppColors.TextSecondary)
        Text(text = value, style = AppTypography.Header2, color = AppColors.TextPrimary, modifier = Modifier.padding(top = 2.dp))
    }
}
