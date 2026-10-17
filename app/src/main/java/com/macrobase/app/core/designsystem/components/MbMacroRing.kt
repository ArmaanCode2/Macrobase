package com.macrobase.app.core.designsystem.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.macrobase.app.core.designsystem.AppColors
import com.macrobase.app.core.designsystem.AppTypography
import com.macrobase.app.core.designsystem.Dimensions
import kotlin.math.roundToInt

/** How full a macro ring is: eaten over target, full at or over target, empty when nothing is eaten. */
internal fun macroRingProgress(eaten: Double, target: Double): Float = when {
    eaten <= 0.0 -> 0f
    target <= 0.0 -> 1f
    else -> (eaten / target).toFloat().coerceIn(0f, 1f)
}

/**
 * C12 Macro ring: a 78 dp ring with a 5 dp stroke. The arc in the macro colour starts at 12 o'clock
 * and runs clockwise; inside is "eaten/target" in whole grams with no unit, the label below. It fills
 * from empty when it first appears and from the old value when the numbers change.
 */
@Composable
fun MbMacroRing(
    label: String,
    eatenGrams: Double,
    targetGrams: Double,
    color: Color,
    modifier: Modifier = Modifier
) {
    val eaten = eatenGrams.roundToInt()
    val target = targetGrams.roundToInt()
    val progress = macroRingProgress(eatenGrams, targetGrams)
    val reducedMotion = rememberReducedMotion()
    val sweep = remember { Animatable(if (reducedMotion) progress else 0f) }
    LaunchedEffect(progress, reducedMotion) {
        if (reducedMotion) sweep.snapTo(progress) else sweep.animateTo(progress, tween(MbMotion.RingMs, easing = MbMotion.Ease))
    }

    Column(
        modifier = modifier.clearAndSetSemantics { contentDescription = "$label: $eaten of $target g" },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Box(modifier = Modifier.size(Dimensions.MacroRing), contentAlignment = Alignment.Center) {
            Canvas(modifier = Modifier.size(Dimensions.MacroRing)) {
                val stroke = Dimensions.MacroRingStroke.toPx()
                val inset = stroke / 2f
                val arcSize = Size(size.width - stroke, size.height - stroke)
                drawArc(
                    color = AppColors.BarTrack,
                    startAngle = 0f,
                    sweepAngle = 360f,
                    useCenter = false,
                    topLeft = Offset(inset, inset),
                    size = arcSize,
                    style = Stroke(width = stroke)
                )
                // Nothing eaten draws no arc at all: a round cap on a zero sweep would leave a dot
                if (eaten > 0 && sweep.value > 0f) {
                    drawArc(
                        color = color,
                        startAngle = -90f,
                        sweepAngle = 360f * sweep.value,
                        useCenter = false,
                        topLeft = Offset(inset, inset),
                        size = arcSize,
                        style = Stroke(width = stroke, cap = StrokeCap.Round)
                    )
                }
            }
            Text(
                text = "$eaten/$target",
                style = AppTypography.Header3.copy(fontSize = 14.sp, letterSpacing = (-0.01).em),
                color = AppColors.TextPrimary,
                maxLines = 1
            )
        }
        Text(text = label, style = AppTypography.Caption, color = AppColors.TextSecondary)
    }
}
