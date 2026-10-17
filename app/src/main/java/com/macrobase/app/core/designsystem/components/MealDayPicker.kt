package com.macrobase.app.core.designsystem.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDefaults
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.macrobase.app.core.designsystem.AppColors
import com.macrobase.app.core.designsystem.AppShapes
import com.macrobase.app.core.designsystem.AppTypography
import com.macrobase.app.domain.model.MealType
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * A meal's name in sentence case for display ("AM snack", "Late snack"). [MealType.displayName]
 * stays upper case because stored data and tests use it.
 */
fun mealLabel(meal: MealType): String = when (meal) {
    MealType.BREAKFAST -> "Breakfast"
    MealType.LUNCH -> "Lunch"
    MealType.DINNER -> "Dinner"
    MealType.AM_SNACK -> "AM snack"
    MealType.PM_SNACK -> "PM snack"
    MealType.SNACK -> "Late snack"
}

/** The meal buttons, 2 rows of 3, in the order the basket shows them. */
private val MealGrid = listOf(
    listOf(MealType.BREAKFAST, MealType.LUNCH, MealType.DINNER),
    listOf(MealType.AM_SNACK, MealType.PM_SNACK, MealType.SNACK)
)

/**
 * C11 "When" table: the 6 meal buttons and the Yesterday / Today / Choose a day row, 3 x 3 equal
 * cells. A null [selectedMeal] or [selectedDate] leaves that row with nothing selected (a basket
 * whose items differ). An optional [header] ("Log to Breakfast, Today") sits above the grid.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MealAndDayPicker(
    selectedMeal: MealType?,
    selectedDate: LocalDate?,
    today: LocalDate,
    onMealSelected: (MealType) -> Unit,
    onDateSelected: (LocalDate) -> Unit,
    modifier: Modifier = Modifier,
    header: String? = null
) {
    var showDatePicker by remember { mutableStateOf(false) }

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (header != null) {
            Text(
                text = header,
                style = AppTypography.Body2.copy(fontSize = 13.sp),
                color = AppColors.TextSecondary,
                modifier = Modifier.padding(bottom = 4.dp)
            )
        }
        MealGrid.forEach { rowMeals ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .selectableGroup(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                rowMeals.forEach { meal ->
                    PickerButton(
                        text = mealLabel(meal),
                        isSelected = meal == selectedMeal,
                        onClick = { onMealSelected(meal) }
                    )
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 6.dp)
                .selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            val isYesterday = selectedDate == today.minusDays(1)
            val isToday = selectedDate == today
            val isOther = selectedDate != null && !isYesterday && !isToday

            PickerButton(text = "Yesterday", isSelected = isYesterday, onClick = { onDateSelected(today.minusDays(1)) })
            PickerButton(text = "Today", isSelected = isToday, onClick = { onDateSelected(today) })
            PickerButton(
                text = selectedDate?.takeIf { isOther }?.format(DateTimeFormatter.ofPattern("MM/dd")) ?: "Choose a day",
                isSelected = isOther,
                onClick = { showDatePicker = true }
            )
        }
    }

    if (showDatePicker) {
        MbDatePickerDialog(
            initialDate = selectedDate ?: today,
            onDateSelected = onDateSelected,
            onDismiss = { showDatePicker = false }
        )
    }
}

/**
 * The app's date picker dialog in the design's colours. Any day can be picked, as before;
 * [onDateSelected] gets the chosen day and the dialog closes.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MbDatePickerDialog(
    initialDate: LocalDate,
    onDateSelected: (LocalDate) -> Unit,
    onDismiss: () -> Unit
) {
    val initialMillis = initialDate.atStartOfDay(ZoneId.of("UTC")).toInstant().toEpochMilli()
    val datePickerState = rememberDatePickerState(initialSelectedDateMillis = initialMillis)
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                datePickerState.selectedDateMillis?.let { millis ->
                    onDateSelected(Instant.ofEpochMilli(millis).atZone(ZoneId.of("UTC")).toLocalDate())
                }
                onDismiss()
            }) { Text("OK", color = AppColors.Primary) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel", color = AppColors.Primary) }
        },
        shape = AppShapes.Dialog,
        colors = DatePickerDefaults.colors(containerColor = AppColors.Surface)
    ) {
        DatePicker(
            state = datePickerState,
            colors = DatePickerDefaults.colors(
                containerColor = AppColors.Surface,
                titleContentColor = AppColors.TextPrimary,
                headlineContentColor = AppColors.TextPrimary,
                weekdayContentColor = AppColors.TextSecondary,
                subheadContentColor = AppColors.TextPrimary,
                yearContentColor = AppColors.TextPrimary,
                currentYearContentColor = AppColors.Primary,
                selectedYearContentColor = AppColors.OnPrimary,
                selectedYearContainerColor = AppColors.Primary,
                dayContentColor = AppColors.TextPrimary,
                selectedDayContentColor = AppColors.OnPrimary,
                selectedDayContainerColor = AppColors.Primary,
                todayDateBorderColor = AppColors.Primary,
                todayContentColor = AppColors.Primary
            )
        )
    }
}

/**
 * One 46 dp cell of the table, in chip colours: outlined, or green-tinted when selected. The label
 * stays on one line, so a narrow screen or large text shortens it rather than cutting a second line.
 */
@Composable
private fun RowScope.PickerButton(text: String, isSelected: Boolean, onClick: () -> Unit) {
    val shape = AppShapes.InputBox
    Box(
        modifier = Modifier
            .weight(1f)
            .height(46.dp)
            .mbSelectable(selected = isSelected, role = Role.RadioButton, pressedScale = 0.97f, onClick = onClick)
            .clip(shape)
            .background(if (isSelected) AppColors.ChipOnFill else Color.Transparent)
            .border(1.dp, if (isSelected) AppColors.ChipOnBorder else AppColors.ChipBorder, shape)
            .padding(horizontal = 4.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            style = AppTypography.Chip.copy(fontSize = 14.sp),
            color = if (isSelected) AppColors.ChipOnInk else AppColors.ChipInk,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
