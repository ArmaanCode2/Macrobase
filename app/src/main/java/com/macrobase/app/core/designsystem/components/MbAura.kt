package com.macrobase.app.core.designsystem.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.sin

/** One glow of the aura, in the design's terms (designs/design.txt section 7). */
private class AuraLayer(
    val centerXFraction: Float,
    val centerY: Dp,
    val radiusXFraction: Float,
    val radiusY: Dp,
    val stops: Array<Pair<Float, Color>>,
    val orbitX: Dp,
    val orbitY: Dp
)

private val Transparent = Color(0x00060706)

private val LayerA = AuraLayer(
    centerXFraction = 0.04f, centerY = 0.dp, radiusXFraction = 0.9f, radiusY = 340.dp,
    stops = arrayOf(0f to Color(0x8C228236), 0.38f to Color(0x4010461C), 0.70f to Transparent),
    orbitX = 34.dp, orbitY = 21.dp
)

private val LayerB = AuraLayer(
    centerXFraction = 0.38f, centerY = (-20).dp, radiusXFraction = 0.6f, radiusY = 270.dp,
    stops = arrayOf(0f to Color(0x42228236), 0.40f to Color(0x1C10461C), 0.72f to Transparent),
    orbitX = 64.dp, orbitY = 32.dp
)

/** How far down the aura can reach once stretched, so its box is tall enough. */
private val AuraHeight = 480.dp

/**
 * The moving green glow at the top left behind every screen except the scanner. Two radial
 * gradients drift on slow elliptical orbits and stretch on their own rhythm; only the draw phase
 * reads the animation, so nothing recomposes per frame. With animations off it stays still and the
 * fainter second layer is hidden.
 */
@Composable
fun MbAura(modifier: Modifier = Modifier) {
    if (rememberReducedMotion()) {
        Canvas(modifier = modifier.fillMaxWidth().height(AuraHeight)) {
            drawAuraLayer(LayerA, angleDegrees = null, scaleX = 1f, scaleY = 1f, alpha = 1f)
        }
        return
    }
    val transition = rememberInfiniteTransition(label = "aura")
    val orbitA = transition.loop(14_400, "auraOrbitA")
    val stretchA = transition.breathe(6_400, "auraStretchA")
    val orbitB = transition.loop(22_400, "auraOrbitB")
    val stretchB = transition.breathe(4_400, "auraStretchB")
    val fadeB = transition.breathe(5_600, "auraFadeB")

    Canvas(modifier = modifier.fillMaxWidth().height(AuraHeight)) {
        val a = stretchA.value
        drawAuraLayer(LayerA, orbitA.value, scaleX = lerp(1f, 1.08f, a), scaleY = lerp(0.72f, 1.32f, a), alpha = 1f)
        val b = stretchB.value
        // Layer B turns the other way
        drawAuraLayer(LayerB, -orbitB.value, scaleX = lerp(0.96f, 1.05f, b), scaleY = lerp(0.80f, 1.45f, b), alpha = lerp(0.55f, 1f, fadeB.value))
    }
}

@Composable
private fun androidx.compose.animation.core.InfiniteTransition.loop(durationMs: Int, label: String): State<Float> =
    animateFloat(0f, 360f, infiniteRepeatable(tween(durationMs, easing = LinearEasing), RepeatMode.Restart), label)

@Composable
private fun androidx.compose.animation.core.InfiniteTransition.breathe(durationMs: Int, label: String): State<Float> =
    animateFloat(0f, 1f, infiniteRepeatable(tween(durationMs, easing = MbMotion.Sine), RepeatMode.Reverse), label)

private fun lerp(from: Float, to: Float, fraction: Float): Float = from + (to - from) * fraction

/**
 * Draws one elliptical glow. The orbit moves its centre round an ellipse (no move when
 * [angleDegrees] is null); the stretch scales the glow and its orbit about the resting centre.
 */
private fun DrawScope.drawAuraLayer(layer: AuraLayer, angleDegrees: Float?, scaleX: Float, scaleY: Float, alpha: Float) {
    val center = Offset(size.width * layer.centerXFraction, layer.centerY.toPx())
    val radiusX = size.width * layer.radiusXFraction
    val radiusY = layer.radiusY.toPx()
    val orbit = angleDegrees?.let {
        val radians = Math.toRadians(it.toDouble())
        Offset(layer.orbitX.toPx() * cos(radians).toFloat(), layer.orbitY.toPx() * sin(radians).toFloat())
    } ?: Offset.Zero
    withTransform({
        scale(scaleX, scaleY * radiusY / radiusX, pivot = center)
        translate(orbit.x, orbit.y * radiusX / radiusY)
    }) {
        drawCircle(
            brush = Brush.radialGradient(*layer.stops, center = center, radius = radiusX),
            radius = radiusX,
            center = center,
            alpha = alpha
        )
    }
}
