package com.macrobase.app.core.designsystem.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.macrobase.app.core.designsystem.AppColors
import com.macrobase.app.core.designsystem.AppTypography
import com.macrobase.app.core.designsystem.Dimensions
import com.macrobase.app.core.designsystem.Ph

/**
 * C9 Screen header: a 46 dp back circle, the centred 18 sp title and an optional right action. It is
 * meant to sit outside the scrolling content (so it stays pinned) and gets a solid band with a
 * bottom line once the content has [scrolled] under it.
 */
@Composable
fun MbHeader(
    title: String,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    scrolled: Boolean = false,
    applyStatusBarInset: Boolean = true,
    actions: @Composable RowScope.() -> Unit = {}
) {
    val band by animateColorAsState(
        targetValue = if (scrolled) AppColors.HeaderBand else Color.Transparent,
        animationSpec = tween(200),
        label = "headerBand"
    )
    val line by animateColorAsState(
        targetValue = if (scrolled) AppColors.HeaderBandLine else Color.Transparent,
        animationSpec = tween(200),
        label = "headerLine"
    )
    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(band)
            .drawBehind {
                val stroke = 1.dp.toPx()
                drawLine(line, Offset(0f, size.height - stroke / 2f), Offset(size.width, size.height - stroke / 2f), stroke)
            }
            .then(if (applyStatusBarInset) Modifier.windowInsetsPadding(WindowInsets.statusBars) else Modifier)
            .padding(start = 10.dp, end = 10.dp, top = 6.dp, bottom = 8.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(Dimensions.HeaderButton),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            if (onBack != null) {
                MbIconButton(
                    icon = Ph.ArrowLeft.light,
                    contentDescription = "Back",
                    onClick = onBack,
                    size = Dimensions.HeaderButton,
                    outlined = true
                )
            } else {
                Spacer(modifier = Modifier.size(Dimensions.HeaderButton))
            }
            Spacer(modifier = Modifier.weight(1f))
            actions()
        }
        Text(
            text = title,
            style = AppTypography.Header1,
            color = AppColors.TextPrimary,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .align(Alignment.Center)
                .padding(horizontal = 60.dp)
                .semantics { heading() }
        )
    }
}

/** True once a scrolling column has moved off its top, for [MbHeader]'s band. */
val ScrollState.isScrolled: Boolean get() = value > 0

/** True once a lazy list has moved off its top, for [MbHeader]'s band. */
@Composable
fun LazyListState.rememberIsScrolled(): Boolean {
    val scrolled by remember(this) { derivedStateOf { firstVisibleItemIndex > 0 || firstVisibleItemScrollOffset > 0 } }
    return scrolled
}
