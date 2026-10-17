package com.macrobase.app.core.designsystem.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.macrobase.app.core.designsystem.AppColors
import com.macrobase.app.core.designsystem.AppShapes
import com.macrobase.app.core.designsystem.AppTypography
import com.macrobase.app.core.designsystem.Ph
import kotlinx.coroutines.launch

/** A 1 px line along a rounded top edge only, drawn over the content: the sheet's Edge-2 border. */
fun Modifier.topEdgeBorder(color: Color, radius: Dp): Modifier = drawWithContent {
    drawContent()
    val stroke = 1.dp.toPx()
    val r = radius.toPx()
    val half = stroke / 2f
    val path = Path().apply {
        moveTo(half, r)
        arcTo(Rect(half, half, half + 2 * r, half + 2 * r), 180f, 90f, false)
        lineTo(size.width - r - half, half)
        arcTo(Rect(size.width - 2 * r - half, half, size.width - half, half + 2 * r), 270f, 90f, false)
    }
    drawPath(path, color, style = Stroke(width = stroke))
}

/**
 * C10 Bottom sheet: #0A0D0B with 28 dp top corners and an Edge-2 top line, a grab handle, the title
 * at the top left with a close "X" at the top right, the content, then an optional footer row of
 * actions. [onDismiss] runs after the sheet has slid away. [onClose] replaces what the close button
 * does, for a sheet with steps that goes back a step instead.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MbSheet(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    title: String? = null,
    sheetState: SheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    scrollable: Boolean = true,
    onClose: (() -> Unit)? = null,
    footer: (@Composable RowScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val scope = rememberCoroutineScope()
    val close: () -> Unit = {
        scope.launch { sheetState.hide() }.invokeOnCompletion { if (!sheetState.isVisible) onDismiss() }
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        modifier = modifier.topEdgeBorder(AppColors.Edge2, 28.dp),
        sheetState = sheetState,
        shape = AppShapes.BottomSheet,
        containerColor = AppColors.SheetFill,
        contentColor = AppColors.TextPrimary,
        tonalElevation = 0.dp,
        scrimColor = AppColors.Scrim,
        dragHandle = {
            Box(
                modifier = Modifier
                    .padding(top = 10.dp, bottom = 2.dp)
                    .size(width = 40.dp, height = 4.dp)
                    .background(AppColors.DividerStrong, RoundedCornerShape(2.dp))
            )
        }
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier
                    .weight(1f, fill = false)
                    .then(if (scrollable) Modifier.verticalScroll(rememberScrollState()) else Modifier)
                    .padding(horizontal = 16.dp)
            ) {
                if (title != null) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = title,
                            style = AppTypography.Header1,
                            color = AppColors.TextPrimary,
                            modifier = Modifier.weight(1f).semantics { heading() }
                        )
                        MbIconButton(icon = Ph.X.light, contentDescription = "Close", onClick = onClose ?: close, tint = AppColors.TextSecondary)
                    }
                }
                content()
            }
            if (footer != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    content = footer
                )
            } else {
                Box(modifier = Modifier.padding(bottom = 16.dp))
            }
        }
    }
}
