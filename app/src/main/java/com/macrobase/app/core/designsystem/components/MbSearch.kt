package com.macrobase.app.core.designsystem.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.macrobase.app.core.designsystem.AppBrushes
import com.macrobase.app.core.designsystem.AppColors
import com.macrobase.app.core.designsystem.AppTypography
import com.macrobase.app.core.designsystem.Dimensions
import com.macrobase.app.core.designsystem.Ph

private val SearchPillBorder = Color(0x21FFFFFF)

/** The pill frame of a search bar with inline actions (C14). */
fun Modifier.mbSearchPillFrame(): Modifier = this
    .clip(CircleShape)
    .background(AppColors.SearchPillFill)
    .border(1.dp, SearchPillBorder, CircleShape)
    .padding(4.dp)

/**
 * C14 Search bar that opens search: a magnifier and [placeholder] that act as one button, then the
 * inline actions in [trailing] (the outlined scan button on Home).
 */
@Composable
fun MbSearchPill(
    placeholder: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    trailing: @Composable RowScope.() -> Unit = {}
) {
    Row(
        modifier = modifier.mbSearchPillFrame(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .defaultMinSize(minHeight = 42.dp)
                .mbClickable(pressedScale = 0.98f, onClickLabel = placeholder, onClick = onClick)
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            MbIcon(icon = Ph.MagnifyingGlass.light, contentDescription = null, size = 18.dp, tint = AppColors.TextMuted)
            Text(
                text = placeholder,
                style = AppTypography.Body1.copy(fontSize = 14.5.sp, fontWeight = FontWeight.Light),
                color = AppColors.TextMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        trailing()
    }
}

/** The outlined 42 dp scan button inside a search bar. */
@Composable
fun MbScanButton(onClick: () -> Unit, modifier: Modifier = Modifier, contentDescription: String = "Scan nutrition label") {
    Box(
        modifier = modifier
            .size(42.dp)
            .mbClickable(pressedScale = 0.92f, onClick = onClick)
            .clip(CircleShape)
            .background(AppColors.IconCircleFill)
            .border(1.dp, AppColors.ScanOutlineBorder, CircleShape),
        contentAlignment = Alignment.Center
    ) {
        MbIcon(icon = Ph.Scan.light, contentDescription = contentDescription, size = 19.dp, tint = Color(0xFFD8F7DC))
    }
}

/** The filled 42 dp "+" (primary gradient) inside a search bar. */
@Composable
fun MbFilledPlusButton(onClick: () -> Unit, contentDescription: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(42.dp)
            .mbClickable(pressedScale = 0.92f, onClick = onClick)
            .clip(CircleShape)
            .background(AppBrushes.PrimaryButtonGradient)
            .innerTopHighlight(),
        contentAlignment = Alignment.Center
    ) {
        MbIcon(icon = Ph.Plus.regular, contentDescription = contentDescription, size = 19.dp, tint = AppColors.OnPrimary)
    }
}

/** "Basket, 3 items": the basket button's label for screen readers. */
fun basketButtonLabel(count: Int): String = "Basket, $count ${if (count == 1) "item" else "items"}"

/** The outlined 46 dp basket button with its count badge. */
@Composable
fun MbBasketButton(count: Int, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(modifier = modifier) {
        MbIconButton(
            icon = Ph.Basket.light,
            contentDescription = basketButtonLabel(count),
            onClick = onClick,
            size = Dimensions.HeaderButton,
            outlined = true
        )
        if (count > 0) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = 2.dp, y = (-2).dp)
                    .defaultMinSize(minWidth = 18.dp, minHeight = 18.dp)
                    .clip(CircleShape)
                    .background(AppColors.Badge)
                    .padding(horizontal = 5.dp)
                    .clearAndSetSemantics { },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (count > 99) "99+" else count.toString(),
                    style = AppTypography.Caption.copy(fontSize = 11.sp, lineHeight = 13.sp),
                    color = AppColors.BadgeInk
                )
            }
        }
    }
}
