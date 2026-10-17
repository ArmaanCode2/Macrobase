package com.macrobase.app.core.designsystem.components

import android.provider.Settings
import androidx.annotation.DrawableRes
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
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

/** Scales the element down while it is pressed: the design's press feedback on every control. */
private fun Modifier.pressScale(interactionSource: MutableInteractionSource, enabled: Boolean, pressedScale: Float): Modifier =
    composed {
        val pressed by interactionSource.collectIsPressedAsState()
        val scale by animateFloatAsState(
            targetValue = if (pressed && enabled) pressedScale else 1f,
            animationSpec = tween(MbMotion.PressMs, easing = MbMotion.Ease),
            label = "press"
        )
        graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
    }

/** A click target that shrinks a little while pressed, with no ripple. */
fun Modifier.mbClickable(
    enabled: Boolean = true,
    role: Role? = Role.Button,
    pressedScale: Float = 0.96f,
    onClickLabel: String? = null,
    onClick: () -> Unit
): Modifier = composed {
    val interactionSource = remember { MutableInteractionSource() }
    pressScale(interactionSource, enabled, pressedScale)
        .clickable(
            interactionSource = interactionSource,
            indication = null,
            enabled = enabled,
            role = role,
            onClickLabel = onClickLabel,
            onClick = onClick
        )
}

/** Like [mbClickable], for an item that is selected or not (chips, tabs, the When table). */
fun Modifier.mbSelectable(
    selected: Boolean,
    enabled: Boolean = true,
    role: Role? = Role.RadioButton,
    pressedScale: Float = 0.96f,
    onClick: () -> Unit
): Modifier = composed {
    val interactionSource = remember { MutableInteractionSource() }
    pressScale(interactionSource, enabled, pressedScale)
        .selectable(
            selected = selected,
            interactionSource = interactionSource,
            indication = null,
            enabled = enabled,
            role = role,
            onClick = onClick
        )
}

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
