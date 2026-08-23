package com.macrobase.app.core.designsystem.components

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Fastfood
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.macrobase.app.core.designsystem.AppColors
import com.macrobase.app.core.designsystem.AppShapes
import com.macrobase.app.core.designsystem.AppSpacing
import com.macrobase.app.core.designsystem.AppTypography
import com.macrobase.app.core.designsystem.Dimensions
import com.macrobase.app.domain.model.Food

/**
 * Standard Full Width Hero Button matching UI_UX_SPECIFICATION.md
 */
@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    backgroundColor: Color = AppColors.Primary
) {
    Button(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .height(Dimensions.ButtonHeight),
        enabled = enabled,
        shape = AppShapes.Button,
        colors = ButtonDefaults.buttonColors(
            containerColor = backgroundColor,
            disabledContainerColor = AppColors.TextDisabled
        )
    ) {
        Text(
            text = text,
            style = AppTypography.Button,
            color = if (enabled) AppColors.TextPrimary else AppColors.TextSecondary
        )
    }
}

/**
 * Standard Empty State Card matching UI_UX_SPECIFICATION.md
 */
@Composable
fun EmptyStateCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    actionButtonText: String? = null,
    onActionClick: (() -> Unit)? = null
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(AppSpacing.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = AppColors.TextSecondary,
            modifier = Modifier.size(48.dp)
        )
        Text(
            text = title,
            style = AppTypography.Header2,
            modifier = Modifier.padding(top = AppSpacing.md)
        )
        Text(
            text = subtitle,
            style = AppTypography.Body2,
            color = AppColors.TextSecondary,
            modifier = Modifier.padding(top = AppSpacing.xs)
        )
        if (actionButtonText != null && onActionClick != null) {
            PrimaryButton(
                text = actionButtonText,
                onClick = onActionClick,
                modifier = Modifier.padding(top = AppSpacing.lg)
            )
        }
    }
}

/**
 * 3-Column Macro Progress Indicator Strip (P, C, F)
 */
@Composable
fun MacroProgressStrip(
    proteinGrams: Double,
    carbsGrams: Double,
    fatGrams: Double,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(Dimensions.MacroStripHeight)
            .background(AppColors.Background),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Protein
        Row(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = "${proteinGrams.toInt()}g ", style = AppTypography.Header3, color = AppColors.MacroProtein)
            Text(text = "Protein", style = AppTypography.Body2, color = AppColors.TextSecondary)
        }
        Box(modifier = Modifier.size(width = 1.dp, height = 20.dp).background(AppColors.Divider))
        // Carbs
        Row(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = "${carbsGrams.toInt()}g ", style = AppTypography.Header3, color = AppColors.MacroCarbs)
            Text(text = "Carb", style = AppTypography.Body2, color = AppColors.TextSecondary)
        }
        Box(modifier = Modifier.size(width = 1.dp, height = 20.dp).background(AppColors.Divider))
        // Fat
        Row(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = "${fatGrams.toInt()}g ", style = AppTypography.Header3, color = AppColors.MacroFat)
            Text(text = "Fat", style = AppTypography.Body2, color = AppColors.TextSecondary)
        }
    }
}

/**
 * 64dp Standard Food Result Row in Search / Recents List
 */
@Composable
fun FoodResultItem(
    food: Food,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(Dimensions.FoodRowHeight)
            .background(AppColors.Background)
            .clickable(onClick = onClick)
            .padding(horizontal = AppSpacing.lg),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Thumbnail Icon Container
        Box(
            modifier = Modifier
                .size(Dimensions.FoodThumbnailSize)
                .clip(AppShapes.CardSmall)
                .background(AppColors.Background),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.Fastfood,
                contentDescription = null,
                tint = AppColors.TextSecondary,
                modifier = Modifier.size(16.dp)
            )
        }

        // Title and Subtitle
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = AppSpacing.md)
        ) {
            Text(
                text = food.name,
                style = AppTypography.Body2,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            val sub = food.brand ?: food.defaultServing?.description ?: food.servingBasis
            Text(
                text = sub,
                style = AppTypography.Caption,
                color = AppColors.TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        // Calories & Chevron
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.End
        ) {
            Text(
                text = "${food.nutrition.calories.toInt()}",
                style = AppTypography.Body1,
                color = AppColors.CalorieText
            )
            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = null,
                tint = AppColors.TextSecondary,
                modifier = Modifier.padding(start = AppSpacing.sm).size(Dimensions.IconSizeSmall)
            )
        }
    }
}
