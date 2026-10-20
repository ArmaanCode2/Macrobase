package com.macrobase.app.core.designsystem.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
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
 * gradients drift on slow elliptical orbits and stretch on their own rhythm. Each glow is drawn once
 * into its own layer and the animation only moves, scales and fades that layer (design.txt section
 * 7: transform and alpha only). No frame recomposes or re-records the screen's drawing; the GPU
 * still composites each frame. With animations off it stays still and the fainter second layer is
 * hidden.
 */
@Composable
fun MbAura(modifier: Modifier = Modifier) {
    val reducedMotion = rememberReducedMotion()
    Box(modifier = modifier.fillMaxWidth().height(AuraHeight)) {
        if (reducedMotion) {
            AuraGlow(LayerA) {}
            return@Box
        }
        val transition = rememberInfiniteTransition(label = "aura")
        val orbitA = transition.loop(14_400, "auraOrbitA")
        val stretchA = transition.breathe(6_400, "auraStretchA")
        val orbitB = transition.loop(22_400, "auraOrbitB")
        val stretchB = transition.breathe(4_400, "auraStretchB")
        val fadeB = transition.breathe(5_600, "auraFadeB")

        AuraGlow(LayerA) {
            val a = stretchA.value
            moveGlow(LayerA, orbitA.value, stretchX = lerp(1f, 1.08f, a), stretchY = lerp(0.72f, 1.32f, a))
        }
        AuraGlow(LayerB) {
            val b = stretchB.value
            // Layer B turns the other way
            moveGlow(LayerB, -orbitB.value, stretchX = lerp(0.96f, 1.05f, b), stretchY = lerp(0.80f, 1.45f, b))
            alpha = lerp(0.55f, 1f, fadeB.value)
        }
    }
}

@Composable
private fun androidx.compose.animation.core.InfiniteTransition.loop(durationMs: Int, label: String): State<Float> =
    animateFloat(0f, 360f, infiniteRepeatable(tween(durationMs, easing = LinearEasing), RepeatMode.Restart), label)

@Composable
private fun androidx.compose.animation.core.InfiniteTransition.breathe(durationMs: Int, label: String): State<Float> =
    animateFloat(0f, 1f, infiniteRepeatable(tween(durationMs, easing = MbMotion.Sine), RepeatMode.Reverse), label)

private fun lerp(from: Float, to: Float, fraction: Float): Float = from + (to - from) * fraction

/** One glow, drawn once at rest; [motion] sets its layer's transform and alpha on every frame. */
@Composable
private fun BoxScope.AuraGlow(layer: AuraLayer, motion: GraphicsLayerScope.() -> Unit) {
    Canvas(
        modifier = Modifier
            .matchParentSize()
            .graphicsLayer {
                // A single draw call, so the alpha applies to it directly, with no offscreen buffer
                compositingStrategy = CompositingStrategy.ModulateAlpha
                if (size.height > 0f) {
                    transformOrigin = TransformOrigin(layer.centerXFraction, layer.centerY.toPx() / size.height)
                }
                motion()
            }
    ) {
        drawRestingGlow(layer)
    }
}

/** The glow at rest: a radial gradient squashed to an ellipse of the layer's radii. */
private fun DrawScope.drawRestingGlow(layer: AuraLayer) {
    val center = Offset(size.width * layer.centerXFraction, layer.centerY.toPx())
    val radiusX = size.width * layer.radiusXFraction
    val radiusY = layer.radiusY.toPx()
    scale(scaleX = 1f, scaleY = radiusY / radiusX, pivot = center) {
        drawCircle(
            brush = Brush.radialGradient(*layer.stops, center = center, radius = radiusX),
            radius = radiusX,
            center = center
        )
    }
}

/**
 * Moves a glow's layer: the stretch scales it about its resting centre (the layer's transform
 * origin) and the orbit moves that centre round an ellipse, scaled by the stretch. This is the
 * transform the glow had when it was redrawn on every frame.
 */
private fun GraphicsLayerScope.moveGlow(layer: AuraLayer, angleDegrees: Float, stretchX: Float, stretchY: Float) {
    scaleX = stretchX
    scaleY = stretchY
    val shift = glowTranslation(layer.orbitX.toPx(), layer.orbitY.toPx(), angleDegrees, stretchX, stretchY)
    translationX = shift.x
    translationY = shift.y
}

/** Where the orbit puts a glow's centre at [angleDegrees], in pixels, after the layer's stretch. */
internal fun glowTranslation(orbitXPx: Float, orbitYPx: Float, angleDegrees: Float, stretchX: Float, stretchY: Float): Offset {
    val radians = Math.toRadians(angleDegrees.toDouble())
    return Offset(orbitXPx * cos(radians).toFloat() * stretchX, orbitYPx * sin(radians).toFloat() * stretchY)
}
