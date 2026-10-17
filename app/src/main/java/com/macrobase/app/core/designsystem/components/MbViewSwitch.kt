package com.macrobase.app.core.designsystem.components

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.snapping.SnapLayoutInfoProvider
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.macrobase.app.core.designsystem.AppColors
import com.macrobase.app.core.designsystem.AppTypography
import com.macrobase.app.core.designsystem.Dimensions
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

private val ItemGap = 2.dp
private val PillPadding = 6.dp

/** The bar and switch pill: #0D120F with a white 8 % inner line and a soft dark drop shadow. */
fun Modifier.mbDockPill(): Modifier = this
    .shadow(elevation = 14.dp, shape = CircleShape, ambientColor = Color.Black, spotColor = Color.Black)
    .clip(CircleShape)
    .background(AppColors.PillFill)
    .border(1.dp, AppColors.PillInnerLine, CircleShape)

/** The sliding capsule behind the active tab or view. */
@Composable
internal fun DockCapsule(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .height(Dimensions.NavItem)
            .clip(CircleShape)
            .background(AppColors.Capsule)
            .border(1.dp, AppColors.CapsuleBorder, CircleShape)
    )
}

/**
 * C8 View switch for the bottom dock, built like the bar. With 2 views each takes half; with 3 or
 * more every item is 108 dp, the track scrolls sideways inside the pill and snaps items to the
 * centre, and the active item scrolls to the centre when the view changes. The edge fades only on a
 * side that still has more items. The tab root owns [selectedIndex]; this only shows and forwards it.
 */
@Composable
fun MbViewSwitch(
    items: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val reducedMotion = rememberReducedMotion()
    Box(
        modifier = modifier
            .height(Dimensions.DockPiece)
            .mbDockPill()
            .padding(PillPadding)
    ) {
        if (items.size <= 2) {
            HalvesSwitch(items, selectedIndex, onSelect, reducedMotion)
        } else {
            ScrollingSwitch(items, selectedIndex, onSelect, reducedMotion)
        }
    }
}

@Composable
private fun HalvesSwitch(items: List<String>, selectedIndex: Int, onSelect: (Int) -> Unit, reducedMotion: Boolean) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val count = items.size.coerceAtLeast(1)
        val itemWidth = (maxWidth - ItemGap * (count - 1)) / count
        val capsuleX by animateDpAsState(
            targetValue = (itemWidth + ItemGap) * selectedIndex,
            animationSpec = if (reducedMotion) snap() else tween(MbMotion.CapsuleMs, easing = MbMotion.Ease),
            label = "switchCapsule"
        )
        DockCapsule(modifier = Modifier.width(itemWidth).offset(x = capsuleX))
        Row(
            modifier = Modifier.fillMaxSize().selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(ItemGap)
        ) {
            items.forEachIndexed { index, label ->
                SwitchItem(label, index == selectedIndex, { onSelect(index) }, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun ScrollingSwitch(items: List<String>, selectedIndex: Int, onSelect: (Int) -> Unit, reducedMotion: Boolean) {
    val scrollState = rememberScrollState()
    val density = LocalDensity.current
    val itemWidth = Dimensions.ViewSwitchItem
    val pitchPx = with(density) { (itemWidth + ItemGap).toPx() }
    val itemPx = with(density) { itemWidth.toPx() }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val viewportPx = constraints.maxWidth.toFloat()
        val snapProvider = remember(scrollState, viewportPx, items.size) {
            SwitchSnapProvider(scrollState, pitchPx, itemPx, viewportPx, items.size)
        }
        val fling = rememberSnapFlingBehavior(snapProvider)

        // The active item scrolls to the centre whenever the view changes
        LaunchedEffect(selectedIndex, viewportPx) {
            val target = centredScroll(selectedIndex, pitchPx, itemPx, viewportPx, scrollState.maxValue.toFloat()).roundToInt()
            if (reducedMotion) scrollState.scrollTo(target)
            else scrollState.animateScrollTo(target, tween(MbMotion.CapsuleMs, easing = MbMotion.Ease))
        }

        val capsuleX by animateDpAsState(
            targetValue = (itemWidth + ItemGap) * selectedIndex,
            animationSpec = if (reducedMotion) snap() else tween(MbMotion.CapsuleMs, easing = MbMotion.Ease),
            label = "switchCapsule"
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(CircleShape)
                .edgeFade(scrollState, Dimensions.ViewSwitchFade)
                .horizontalScroll(scrollState, flingBehavior = fling)
        ) {
            Box(modifier = Modifier.fillMaxHeight()) {
                DockCapsule(modifier = Modifier.width(itemWidth).offset(x = capsuleX))
                Row(
                    modifier = Modifier.fillMaxHeight().selectableGroup(),
                    horizontalArrangement = Arrangement.spacedBy(ItemGap)
                ) {
                    items.forEachIndexed { index, label ->
                        SwitchItem(label, index == selectedIndex, { onSelect(index) }, Modifier.width(itemWidth))
                    }
                }
            }
        }
    }
}

@Composable
private fun SwitchItem(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier) {
    Box(
        modifier = modifier
            .height(Dimensions.NavItem)
            .mbSelectable(selected = selected, role = Role.Tab, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            style = AppTypography.Chip.copy(fontSize = 14.sp),
            color = if (selected) AppColors.NavActive else AppColors.NavInactive,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 6.dp)
        )
    }
}

/**
 * Fades the content's edge into the pill's own fill, only on a side the track can still scroll
 * toward. The pill is a solid colour, so this matches a transparency mask without an offscreen layer.
 */
private fun Modifier.edgeFade(state: ScrollState, fade: Dp): Modifier = drawWithContent {
    drawContent()
    val width = fade.toPx()
    if (state.value > 0) {
        drawRect(
            brush = Brush.horizontalGradient(0f to AppColors.PillFill, 1f to Color.Transparent, startX = 0f, endX = width),
            size = Size(width, size.height)
        )
    }
    if (state.value < state.maxValue) {
        drawRect(
            brush = Brush.horizontalGradient(0f to Color.Transparent, 1f to AppColors.PillFill, startX = size.width - width, endX = size.width),
            topLeft = Offset(size.width - width, 0f),
            size = Size(width, size.height)
        )
    }
}

/** The scroll position that puts item [index] in the middle of the track, within its limits. */
internal fun centredScroll(index: Int, pitchPx: Float, itemPx: Float, viewportPx: Float, maxScroll: Float): Float =
    (index * pitchPx + itemPx / 2f - viewportPx / 2f).coerceIn(0f, maxScroll.coerceAtLeast(0f))

/** Snaps the scrolling switch so an item sits in the middle, in the direction of the fling. */
private class SwitchSnapProvider(
    private val state: ScrollState,
    private val pitchPx: Float,
    private val itemPx: Float,
    private val viewportPx: Float,
    private val count: Int
) : SnapLayoutInfoProvider {
    override fun calculateApproachOffset(velocity: Float, decayOffset: Float): Float = decayOffset

    override fun calculateSnapOffset(velocity: Float): Float {
        val exact = (state.value + viewportPx / 2f - itemPx / 2f) / pitchPx
        val index = when {
            velocity > 0f -> ceil(exact)
            velocity < 0f -> floor(exact)
            else -> exact.roundToInt().toFloat()
        }.toInt().coerceIn(0, (count - 1).coerceAtLeast(0))
        return centredScroll(index, pitchPx, itemPx, viewportPx, state.maxValue.toFloat()) - state.value
    }
}
