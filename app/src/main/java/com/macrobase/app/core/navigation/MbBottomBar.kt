package com.macrobase.app.core.navigation

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.ui.unit.IntOffset
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.macrobase.app.core.designsystem.AppColors
import com.macrobase.app.core.designsystem.AppTypography
import com.macrobase.app.core.designsystem.Dimensions
import com.macrobase.app.core.designsystem.Ph
import com.macrobase.app.core.designsystem.components.DockCapsule
import com.macrobase.app.core.designsystem.components.MbIcon
import com.macrobase.app.core.designsystem.components.MbMotion
import com.macrobase.app.core.designsystem.components.mbClickable
import com.macrobase.app.core.designsystem.components.mbDockPill
import com.macrobase.app.core.designsystem.components.rememberReducedMotion

/** A tab of the bottom bar. */
data class BarTab(val screen: Screen, val label: String, val icon: Ph)

private val TabGap = 2.dp

/**
 * C6 Bottom bar: a full-width pill. The active tab is 98 dp and shows its icon and label; the others
 * share the rest and show the icon only. A capsule slides behind the active tab and its label fades
 * in. [selectedIndex] is -1 when no tab is on screen.
 */
@Composable
fun MbBottomBar(
    tabs: List<BarTab>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val reducedMotion = rememberReducedMotion()
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(Dimensions.DockPiece)
            .mbDockPill()
            .padding(6.dp)
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val others = (tabs.size - 1).coerceAtLeast(1)
            val inactiveWidth = (maxWidth - Dimensions.NavActiveWidth - TabGap * others) / others
            val active = selectedIndex.coerceAtLeast(0)
            val capsuleX = animateDpAsState(
                targetValue = (inactiveWidth + TabGap) * active,
                animationSpec = if (reducedMotion) snap() else tween(MbMotion.CapsuleMs, easing = MbMotion.Ease),
                label = "barCapsule"
            )
            if (selectedIndex >= 0) {
                // Read while placing, so the slide moves the capsule without recomposing the bar
                DockCapsule(
                    modifier = Modifier
                        .width(Dimensions.NavActiveWidth)
                        .offset { IntOffset(capsuleX.value.roundToPx(), 0) }
                )
            }
            Row(
                modifier = Modifier.fillMaxSize().selectableGroup(),
                horizontalArrangement = Arrangement.spacedBy(TabGap)
            ) {
                tabs.forEachIndexed { index, tab ->
                    val selected = index == selectedIndex
                    BarTabItem(
                        tab = tab,
                        selected = selected,
                        reducedMotion = reducedMotion,
                        onClick = { onSelect(index) },
                        modifier = Modifier.width(if (selected) Dimensions.NavActiveWidth else inactiveWidth)
                    )
                }
            }
        }
    }
}

@Composable
private fun BarTabItem(tab: BarTab, selected: Boolean, reducedMotion: Boolean, onClick: () -> Unit, modifier: Modifier) {
    val color = if (selected) AppColors.NavActive else AppColors.NavInactive
    Row(
        modifier = modifier
            .height(Dimensions.NavItem)
            .mbClickable(role = Role.Tab, pressedScale = 0.94f, onClick = onClick)
            .clearAndSetSemantics {
                contentDescription = tab.label
                role = Role.Tab
                this.selected = selected
            },
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically
    ) {
        MbIcon(icon = if (selected) tab.icon.regular else tab.icon.light, contentDescription = null, size = 22.dp, tint = color)
        if (selected) {
            // The label fades in over 300 ms, moving 4 dp from the left
            val reveal = remember { Animatable(if (reducedMotion) 1f else 0f) }
            LaunchedEffect(Unit) { if (!reducedMotion) reveal.animateTo(1f, tween(MbMotion.LabelMs, easing = MbMotion.Ease)) }
            Text(
                text = tab.label,
                style = AppTypography.Chip.copy(fontSize = 14.sp),
                color = color,
                maxLines = 1,
                modifier = Modifier.graphicsLayer {
                    alpha = reveal.value
                    translationX = (1f - reveal.value) * (-4).dp.toPx()
                }
            )
        }
    }
}
