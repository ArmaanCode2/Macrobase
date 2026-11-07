package com.macrobase.app.core.designsystem.components

import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.TextUnit
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

/** The ring's number sizes, largest first: at large font scales the numbers shrink to fit (BUG-030). */
internal val MACRO_RING_FONT_SIZES: List<TextUnit> = (14 downTo 7).map { it.sp }

/** The first of [sizes] at which [widthAt] fits in [available], or the last when none does. */
internal fun fittingFontSize(sizes: List<TextUnit>, available: Float, widthAt: (TextUnit) -> Float): TextUnit =
    sizes.firstOrNull { widthAt(it) <= available } ?: sizes.last()

/** How full a macro ring is: eaten over target, full at or over target, empty when nothing is eaten. */
internal fun macroRingProgress(eaten: Double, target: Double): Float = when {
    eaten <= 0.0 -> 0f
    target <= 0.0 -> 1f
    else -> (eaten / target).toFloat().coerceIn(0f, 1f)
}

/**
 * C12 Macro ring: a 78 dp ring with a 5 dp stroke. The arc in the macro colour starts at 12 o'clock
 * and runs clockwise; inside is "eaten/target" in whole grams with no unit, the label below. It fills
 * from empty when it first appears ([fillIn] false starts it at its value) and from the old value when
 * the numbers change.
 */
@Composable
fun MbMacroRing(
    label: String,
    eatenGrams: Double,
    targetGrams: Double,
    color: Color,
    modifier: Modifier = Modifier,
    fillIn: Boolean = true
) {
    val eaten = eatenGrams.roundToInt()
    val target = targetGrams.roundToInt()
    val progress = macroRingProgress(eatenGrams, targetGrams)
    val reducedMotion = rememberReducedMotion()
    val sweep = remember { Animatable(if (reducedMotion || !fillIn) progress else 0f) }
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
            // The ring has a fixed size, so at large font scales the numbers shrink instead of being cut off (BUG-030)
            val text = "$eaten/$target"
            val style = AppTypography.Header3.copy(fontSize = 14.sp, letterSpacing = (-0.01).em)
            val measurer = rememberTextMeasurer()
            val density = LocalDensity.current
            val available = with(density) { (Dimensions.MacroRing - Dimensions.MacroRingStroke * 2 - 4.dp).toPx() }
            val fontSize = remember(text, density) {
                fittingFontSize(MACRO_RING_FONT_SIZES, available) { size ->
                    measurer.measure(text, style.copy(fontSize = size), maxLines = 1, softWrap = false).size.width.toFloat()
                }
            }
            Text(
                text = text,
                style = style.copy(fontSize = fontSize),
                color = AppColors.TextPrimary,
                maxLines = 1,
                softWrap = false,
                // Past the smallest size the cut shows, never a silent part of the number
                overflow = TextOverflow.Ellipsis
            )
        }
        Text(text = label, style = AppTypography.Caption, color = AppColors.TextSecondary)
    }
}
