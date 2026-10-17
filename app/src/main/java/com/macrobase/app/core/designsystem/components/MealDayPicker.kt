package com.macrobase.app.core.designsystem.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.macrobase.app.core.designsystem.AppColors
import com.macrobase.app.core.designsystem.AppSpacing
import com.macrobase.app.core.designsystem.AppTypography
import com.macrobase.app.domain.model.MealType
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** The meal buttons, 2 rows of 3, in the order the basket shows them. */
private val MealGrid = listOf(
    listOf(MealType.BREAKFAST, MealType.LUNCH, MealType.DINNER),
    listOf(MealType.AM_SNACK, MealType.PM_SNACK, MealType.SNACK)
)

/**
 * The 6 meal buttons and the Yesterday / Today / Choose a day row, as the basket shows them under
 * "When:". A null [selectedMeal] or [selectedDate] leaves that row with nothing selected (a basket
 * whose items differ).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MealAndDayPicker(
    selectedMeal: MealType?,
    selectedDate: LocalDate?,
    today: LocalDate,
    onMealSelected: (MealType) -> Unit,
    onDateSelected: (LocalDate) -> Unit,
    modifier: Modifier = Modifier
) {
    var showDatePicker by remember { mutableStateOf(false) }

    Column(modifier = modifier.fillMaxWidth()) {
        MealGrid.forEach { rowMeals ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                rowMeals.forEach { meal ->
                    PickerButton(
                        text = meal.displayName,
                        isSelected = meal == selectedMeal,
                        onClick = { onMealSelected(meal) }
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(AppSpacing.xs))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
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
        val initialMillis = (selectedDate ?: today).atStartOfDay(ZoneId.of("UTC")).toInstant().toEpochMilli()
        val datePickerState = rememberDatePickerState(initialSelectedDateMillis = initialMillis)
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    datePickerState.selectedDateMillis?.let { millis ->
                        onDateSelected(Instant.ofEpochMilli(millis).atZone(ZoneId.of("UTC")).toLocalDate())
                    }
                    showDatePicker = false
                }) { Text("OK", color = AppColors.Primary) }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) { Text("Cancel", color = AppColors.Primary) }
            },
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
                    selectedYearContentColor = AppColors.TextPrimary,
                    selectedYearContainerColor = AppColors.Primary,
                    dayContentColor = AppColors.TextPrimary,
                    selectedDayContentColor = AppColors.TextPrimary,
                    selectedDayContainerColor = AppColors.Primary,
                    todayDateBorderColor = AppColors.Primary,
                    todayContentColor = AppColors.Primary
                )
            )
        }
    }
}

/**
 * One square button of the picker: lighter, outlined and bold when selected. The label stays on
 * one line, so a narrow screen or large text shortens it rather than cutting a second line.
 */
@Composable
private fun RowScope.PickerButton(text: String, isSelected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .weight(1f)
            .height(38.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(if (isSelected) AppColors.SurfaceAlt else AppColors.Surface)
            .border(1.dp, if (isSelected) AppColors.TextSecondary else AppColors.Divider, RoundedCornerShape(2.dp))
            .selectable(selected = isSelected, role = Role.RadioButton, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            style = AppTypography.Body2.copy(
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                color = if (isSelected) AppColors.TextPrimary else AppColors.TextSecondary
            ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
