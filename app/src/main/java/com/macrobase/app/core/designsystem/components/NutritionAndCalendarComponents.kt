package com.macrobase.app.core.designsystem.components

import kotlin.math.roundToInt
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.macrobase.app.core.config.CalendarPerformanceConfig
import com.macrobase.app.core.designsystem.AppColors
import com.macrobase.app.core.designsystem.AppShapes
import com.macrobase.app.core.designsystem.AppSpacing
import com.macrobase.app.core.designsystem.AppTypography
import com.macrobase.app.core.designsystem.Dimensions
import com.macrobase.app.domain.model.CalendarDaySummary
import com.macrobase.app.domain.model.DiaryEntry
import com.macrobase.app.domain.model.Meal
import com.macrobase.app.domain.model.MealType

/**
 * 40dp Square Calendar Cell matching UI_UX_SPECIFICATION.md
 */
@Composable
fun CalendarDayCell(
    day: CalendarDaySummary,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val cellColor = CalendarPerformanceConfig.getColorForCategory(day.performanceCategory)

    Box(
        modifier = modifier
            .size(Dimensions.CalendarCellSize)
            .clip(AppShapes.CalendarCell)
            .background(cellColor)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "${day.date.dayOfMonth}",
            style = AppTypography.Header3,
            color = AppColors.TextPrimary
        )
    }
}

/**
 * 5-Step Calendar Adherence Legend
 */
@Composable
fun CalendarLegend(
    modifier: Modifier = Modifier
) {
    val colors = listOf(
        AppColors.CalendarEmpty,
        AppColors.CalendarGreenSubtle,
        AppColors.CalendarGreenOptimal,
        AppColors.CalendarRedWarning,
        AppColors.CalendarRedAlert
    )
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(AppSpacing.xs),
        verticalAlignment = Alignment.CenterVertically
    ) {
        colors.forEach { color ->
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(color)
            )
        }
    }
}

/**
 * Meal diary section header with (+) quick add and (i) meal summary info.
 */
@Composable
fun DailyMealSection(
    meal: Meal,
    onAddFoodClick: () -> Unit,
    onInfoClick: () -> Unit,
    onEntryClick: (DiaryEntry) -> Unit,
    modifier: Modifier = Modifier
) {
    val sectionCalories = meal.totalCalories.roundToInt()

    Column(modifier = modifier.fillMaxWidth()) {
        // Section Header Row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(Dimensions.SectionHeaderHeight)
                .background(AppColors.Surface)
                .padding(horizontal = AppSpacing.lg),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = meal.type.displayName,
                style = AppTypography.Header3,
                color = AppColors.TextPrimary,
                modifier = Modifier.weight(1f)
            )

            // (+) Quick add button with accessible touch target
            IconButton(
                onClick = onAddFoodClick,
                modifier = Modifier.size(36.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .clip(CircleShape)
                        .background(AppColors.CalorieText),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = "Add Food to ${meal.type.displayName}",
                        tint = AppColors.Background,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }

            IconButton(
                onClick = onInfoClick,
                modifier = Modifier.size(36.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Info,
                    contentDescription = "${meal.type.displayName} Info",
                    tint = AppColors.TextSecondary,
                    modifier = Modifier.size(18.dp)
                )
            }

            Text(
                text = "$sectionCalories",
                style = AppTypography.Header3,
                color = AppColors.TextPrimary,
                modifier = Modifier.padding(start = AppSpacing.xs)
            )
        }

        // Section Food Items
        if (meal.entries.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(36.dp)
                    .background(AppColors.Background)
                    .padding(horizontal = AppSpacing.lg),
                contentAlignment = Alignment.CenterStart
            ) {
                Text(
                    text = "No foods logged yet.",
                    style = AppTypography.Body2,
                    color = AppColors.TextMuted
                )
            }
        } else {
            meal.entries.forEach { entry ->
                FoodResultItem(
                    food = entry.food,
                    onClick = { onEntryClick(entry) }
                )
                HorizontalDivider(color = AppColors.Divider, thickness = 1.dp)
            }
        }
    }
}
