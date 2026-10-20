package com.macrobase.app.core.designsystem.components

import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.DisposableEffect
import android.view.accessibility.AccessibilityManager
import kotlinx.coroutines.launch
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.animation.core.Animatable
import android.provider.Settings
import androidx.annotation.DrawableRes
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable

import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** The design's motion values (designs/design.txt section 7). */
object MbMotion {
    /** "ease": the default for slides, fills and snaps. */
    val Ease = CubicBezierEasing(0.16f, 1f, 0.3f, 1f)

    /** "sine": the aura's breathing. */
    val Sine = CubicBezierEasing(0.37f, 0f, 0.63f, 1f)

    const val PressMs = 140
    const val CapsuleMs = 340
    const val LabelMs = 300
    const val RingMs = 700
    const val SwipeMs = 260
}

/**
 * True when the system animator duration scale is 0 (Developer options or accessibility "Remove
 * animations"). Moving pieces then show their final state only.
 */
@Composable
fun rememberReducedMotion(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
}

/**
 * True while any accessibility service (TalkBack, Switch Access, Voice Access) is on. Follows the
 * setting as it changes, so a screen can build what only such a service needs just while one is on.
 */
@Composable
fun rememberAccessibilityOn(): Boolean {
    val context = LocalContext.current
    val manager = remember(context) { context.getSystemService(AccessibilityManager::class.java) }
    val enabled = remember(manager) { mutableStateOf(manager?.isEnabled == true) }
    DisposableEffect(manager) {
        val listener = AccessibilityManager.AccessibilityStateChangeListener { enabled.value = it }
        manager?.addAccessibilityStateChangeListener(listener)
        onDispose { manager?.removeAccessibilityStateChangeListener(listener) }
    }
    return enabled.value
}

/**
 * The design's press feedback on every control: it scales down to [pressedScale] while pressed and
 * back when let go, over [MbMotion.PressMs]. An indication node, so a screen full of controls sets
 * up nothing for it (no state, coroutine or layer) until one of them is actually pressed.
 */
private class PressScaleIndication(private val pressedScale: Float) : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource): DelegatableNode = PressScaleNode(interactionSource, pressedScale)

    override fun equals(other: Any?): Boolean = other is PressScaleIndication && other.pressedScale == pressedScale

    override fun hashCode(): Int = pressedScale.hashCode()
}

private class PressScaleNode(
    private val interactionSource: InteractionSource,
    private val pressedScale: Float
) : Modifier.Node(), DrawModifierNode {
    private val scale = Animatable(1f)

    override fun onAttach() {
        coroutineScope.launch {
            val presses = mutableListOf<PressInteraction.Press>()
            interactionSource.interactions.collect { interaction ->
                when (interaction) {
                    is PressInteraction.Press -> presses += interaction
                    is PressInteraction.Release -> presses -= interaction.press
                    is PressInteraction.Cancel -> presses -= interaction.press
                }
                val target = if (presses.isEmpty()) 1f else pressedScale
                launch { scale.animateTo(target, tween(MbMotion.PressMs, easing = MbMotion.Ease)) }
            }
        }
    }

    // Scaled about the centre while drawing, as a layer's scale would be; layout and touch stay put
    override fun ContentDrawScope.draw() {
        val factor = scale.value
        if (factor == 1f) drawContent() else scale(factor) { this@draw.drawContent() }
    }
}

/** A click target that shrinks a little while pressed, with no ripple. */
fun Modifier.mbClickable(
    enabled: Boolean = true,
    role: Role? = Role.Button,
    pressedScale: Float = 0.96f,
    onClickLabel: String? = null,
    onClick: () -> Unit
): Modifier = clickable(
    interactionSource = null,
    indication = PressScaleIndication(pressedScale),
    enabled = enabled,
    onClickLabel = onClickLabel,
    role = role,
    onClick = onClick
)

/** Like [mbClickable], for an item that is selected or not (chips, tabs, the When table). */
fun Modifier.mbSelectable(
    selected: Boolean,
    enabled: Boolean = true,
    role: Role? = Role.RadioButton,
    pressedScale: Float = 0.96f,
    onClick: () -> Unit
): Modifier = selectable(
    selected = selected,
    interactionSource = null,
    indication = PressScaleIndication(pressedScale),
    enabled = enabled,
    role = role,
    onClick = onClick
)

/** The 1 px white highlight inside the top edge of filled buttons. Draw it after the fill. */
fun Modifier.innerTopHighlight(color: Color = Color(0x29FFFFFF)): Modifier = drawBehind {
    drawRect(color = color, size = Size(size.width, 1.dp.toPx()))
}

/** A Phosphor icon from res/drawable, tinted. */
@Composable
fun MbIcon(
    @DrawableRes icon: Int,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    size: Dp = 20.dp,
    tint: Color = LocalContentColor.current
) {
    Icon(
        painter = painterResource(id = icon),
        contentDescription = contentDescription,
        tint = tint,
        modifier = modifier.size(size)
    )
}
