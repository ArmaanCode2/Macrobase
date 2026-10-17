package com.macrobase.app.core.designsystem.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.macrobase.app.core.designsystem.AppBrushes
import com.macrobase.app.core.designsystem.AppColors
import com.macrobase.app.core.designsystem.AppShapes
import com.macrobase.app.core.designsystem.AppSpacing
import com.macrobase.app.core.designsystem.AppTypography
import com.macrobase.app.core.designsystem.Dimensions
import com.macrobase.app.core.designsystem.Ph

/** The 12.5 sp light secondary line under a row title. */
internal val RowSubtitleStyle = AppTypography.Body2.copy(fontSize = 12.5.sp, lineHeight = 17.sp, fontWeight = FontWeight.Light)

/**
 * C1 Panel: an outlined, faintly tinted container with large corners. Depth comes from the 1 px edge
 * and the green tint at its top left, never from elevation.
 */
@Composable
fun MbPanel(
    modifier: Modifier = Modifier,
    shape: Shape = AppShapes.Card,
    contentPadding: PaddingValues = PaddingValues(AppSpacing.panelPadding),
    verticalArrangement: Arrangement.Vertical = Arrangement.Top,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = modifier
            .clip(shape)
            .background(AppColors.PanelFill)
            .background(AppBrushes.PanelTint)
            .border(1.dp, AppColors.Edge, shape)
            .padding(contentPadding),
        verticalArrangement = verticalArrangement,
        content = content
    )
}

/** C16 Icon circle: the round thumbnail in front of rows and tracker cards. */
@Composable
fun MbIconCircle(
    @DrawableRes icon: Int,
    modifier: Modifier = Modifier,
    size: Dp = Dimensions.IconCircle,
    iconSize: Dp = 18.dp,
    tint: Color = AppColors.IconCircleInk,
    contentDescription: String? = null
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(AppColors.IconCircleFill)
            .border(1.dp, AppColors.Edge2, CircleShape),
        contentAlignment = Alignment.Center
    ) {
        MbIcon(icon = icon, contentDescription = contentDescription, size = iconSize, tint = tint)
    }
}

/**
 * C2 Row tile: icon circle, title with an optional secondary line, then the value and a chevron.
 * [opaque] rows hide what is behind them (rows that slide over swipe actions).
 */
@Composable
fun MbRowTile(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    @DrawableRes icon: Int? = Ph.ForkKnife.light,
    value: String? = null,
    onClick: (() -> Unit)? = null,
    showChevron: Boolean = onClick != null,
    opaque: Boolean = false,
    shape: Shape = AppShapes.Row,
    subtitleMaxLines: Int = 1,
    trailing: (@Composable RowScope.() -> Unit)? = null
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 58.dp)
            .then(if (onClick != null) Modifier.mbClickable(pressedScale = 0.985f, onClick = onClick) else Modifier)
            .clip(shape)
            .background(if (opaque) AppColors.RowTile else AppColors.RowFill)
            .border(1.dp, AppColors.Edge, shape)
            .padding(start = 10.dp, end = 12.dp, top = 9.dp, bottom = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (icon != null) {
            MbIconCircle(icon = icon, size = 34.dp, iconSize = 17.dp)
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = title,
                style = AppTypography.Header3,
                color = AppColors.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (!subtitle.isNullOrEmpty()) {
                Text(
                    text = subtitle,
                    style = RowSubtitleStyle,
                    color = AppColors.TextSecondary,
                    maxLines = subtitleMaxLines,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        if (value != null) {
            Text(text = value, style = AppTypography.Body1, color = AppColors.TextPrimary, maxLines = 1)
        }
        trailing?.invoke(this)
        if (showChevron) {
            MbIcon(icon = Ph.CaretRight.light, contentDescription = null, size = 15.dp, tint = AppColors.TextMuted)
        }
    }
}
