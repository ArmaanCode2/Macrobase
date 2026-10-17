package com.macrobase.app.feature.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.macrobase.app.core.designsystem.AppColors
import com.macrobase.app.core.designsystem.AppShapes
import com.macrobase.app.core.designsystem.AppSpacing
import com.macrobase.app.core.designsystem.AppTypography
import com.macrobase.app.core.designsystem.components.MealAndDayPicker
import com.macrobase.app.domain.model.Meal
import com.macrobase.app.domain.model.MealType
import com.macrobase.app.feature.basket.basketDateLabel
import com.macrobase.app.feature.basket.basketWhenLabel
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
 * The card the meal's (i) opens: its calories and macros and, for a meal with food in it, Copy
 * (every food into the basket), Move (to another meal and day, with the basket's buttons) and
 * Delete (after one check). [day] is the day the meal was logged on.
 */
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
    val mealName = meal.type.displayName

    // Cancel and Back return to the totals and forget the move choice
    fun backToTotals() {
        mode = MealCardMode.INFO
        moveToMeal = meal.type
        moveToDate = day
    }

    AlertDialog(
        onDismissRequest = { if (mode == MealCardMode.INFO) onDismiss() else backToTotals() },
        // Wide enough for the basket's three buttons per row
        modifier = Modifier.padding(horizontal = AppSpacing.md),
        properties = DialogProperties(usePlatformDefaultWidth = false),
        title = {
            Text(
                text = when (mode) {
                    MealCardMode.INFO -> "$mealName Nutrition"
                    MealCardMode.MOVE -> "Move $mealName"
                    MealCardMode.DELETE -> "Delete $mealName?"
                },
                style = AppTypography.Header2
            )
        },
        text = {
            // Scrolls when the move picker is taller than the screen (landscape, large text)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                when (mode) {
                    MealCardMode.INFO -> {
                        MealTotals(meal)
                        if (meal.entries.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(AppSpacing.md))
                            HorizontalDivider(color = AppColors.Divider, thickness = 1.dp)
                            Spacer(modifier = Modifier.height(AppSpacing.md))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                MealActionButton(text = "Copy", color = AppColors.TextPrimary, onClick = onCopy)
                                MealActionButton(text = "Move", color = AppColors.TextPrimary, onClick = { mode = MealCardMode.MOVE })
                                MealActionButton(text = "Delete", color = AppColors.Error, onClick = { mode = MealCardMode.DELETE })
                            }
                            Spacer(modifier = Modifier.height(AppSpacing.sm))
                            Text(
                                text = "Copy adds these foods to your basket.",
                                style = AppTypography.Caption,
                                color = AppColors.TextSecondary
                            )
                        }
                    }
                    MealCardMode.MOVE -> {
                        Text(text = mealSummaryLine(meal, day, today), style = AppTypography.Body2, color = AppColors.TextSecondary)
                        Spacer(modifier = Modifier.height(AppSpacing.md))
                        // On the basket's dark background, so the buttons look as they do there
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(AppColors.Background)
                                .padding(AppSpacing.sm)
                        ) {
                            Text(
                                text = "To:  ${basketWhenLabel(moveToDate, moveToMeal, today)}",
                                style = AppTypography.Header3.copy(fontWeight = FontWeight.Bold),
                                color = AppColors.TextPrimary
                            )
                            Spacer(modifier = Modifier.height(AppSpacing.sm))
                            MealAndDayPicker(
                                selectedMeal = moveToMeal,
                                selectedDate = moveToDate,
                                today = today,
                                onMealSelected = { moveToMeal = it },
                                onDateSelected = { moveToDate = it }
                            )
                        }
                    }
                    MealCardMode.DELETE -> {
                        Text(text = mealSummaryLine(meal, day, today), style = AppTypography.Body2, color = AppColors.TextSecondary)
                        Spacer(modifier = Modifier.height(AppSpacing.sm))
                        Text(
                            text = "These foods will be removed from your diary. You can't undo this.",
                            style = AppTypography.Body1,
                            color = AppColors.TextPrimary
                        )
                    }
                }
            }
        },
        confirmButton = {
            when (mode) {
                MealCardMode.INFO -> TextButton(onClick = onDismiss) {
                    Text("Close", color = AppColors.Primary)
                }
                MealCardMode.MOVE -> Button(
                    onClick = { onMove(moveToDate, moveToMeal) },
                    enabled = isMealMoveChange(day, meal.type, moveToDate, moveToMeal),
                    shape = RoundedCornerShape(2.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = AppColors.Primary,
                        contentColor = AppColors.OnPrimary,
                        disabledContainerColor = AppColors.Surface,
                        disabledContentColor = AppColors.TextDisabled
                    )
                ) {
                    Text("Save", style = AppTypography.Header3.copy(color = Color.Unspecified))
                }
                MealCardMode.DELETE -> Button(
                    onClick = onDelete,
                    shape = RoundedCornerShape(2.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = AppColors.AlertRed, contentColor = AppColors.TextPrimary)
                ) {
                    Text("Delete", style = AppTypography.Header3.copy(color = Color.Unspecified))
                }
            }
        },
        dismissButton = if (mode == MealCardMode.INFO) {
            null
        } else {
            {
                TextButton(onClick = { backToTotals() }) {
                    Text("Cancel", color = AppColors.TextSecondary)
                }
            }
        },
        containerColor = AppColors.SurfaceAlt,
        shape = AppShapes.Dialog
    )
}

/** The meal's calories, then protein, carbs and fat with their colour dots. */
@Composable
private fun MealTotals(meal: Meal) {
    Text("Calories: ${meal.totalCalories.roundToInt()} kcal", style = AppTypography.Header3)
    Spacer(modifier = Modifier.height(AppSpacing.sm))
    MacroLine(label = "Protein", grams = meal.totalProtein, color = AppColors.MacroProtein)
    MacroLine(label = "Carbs", grams = meal.totalCarbs, color = AppColors.MacroCarbs)
    MacroLine(label = "Fat", grams = meal.totalFat, color = AppColors.MacroFat)
}

@Composable
private fun MacroLine(label: String, grams: Double, color: Color) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(vertical = AppSpacing.xs)
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(color)
        )
        Spacer(modifier = Modifier.width(AppSpacing.sm))
        Text(
            text = "$label: ${String.format(Locale.US, "%.1f", grams)}g",
            style = AppTypography.Body1,
            color = color
        )
    }
}

/**
 * One of Copy / Move / Delete, shaped like the basket's meal buttons and one shade lighter than
 * the card, as those are one shade lighter than the basket.
 */
@Composable
private fun RowScope.MealActionButton(text: String, color: Color, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .weight(1f)
            .height(38.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(AppColors.SurfaceInput)
            .border(1.dp, AppColors.Divider, RoundedCornerShape(2.dp))
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(text = text, style = AppTypography.Body2.copy(fontWeight = FontWeight.Medium, color = color))
    }
}
