package com.macrobase.app.core.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.NavBackStackEntry

/**
 * The views a tab root shows in the dock's view switch. [owner] is the screen that declared them and
 * [screen] the id of its back stack entry (null outside navigation).
 */
class DockViews(
    val owner: Any,
    val items: List<String>,
    val selectedIndex: Int,
    val onSelect: (Int) -> Unit,
    val screen: String? = null
)

/**
 * What the screens tell the scaffold: whether their content has scrolled under the header (the header
 * then gets its band), and the views for the dock's switch. The screen owns its state; the scaffold
 * only shows it and forwards taps.
 *
 * Each screen writes only its own slot, so a screen still on its way out can neither overwrite nor
 * clear what the arriving screen declared. Each slot carries its screen's back stack entry id, and
 * the scaffold shows only the current entry's, so a leaving screen's switch and band go the moment
 * the navigation starts. Ids, not entry objects: navigation copies an entry when a tab is tapped
 * again or a screen is restored.
 */
@Stable
class ScaffoldChrome {
    private val dockSlots = mutableStateMapOf<Any, DockViews>()

    /** The screens scrolled under the header, each with its entry id; a screen is here only while scrolled. */
    private val scrolledSlots = mutableStateMapOf<Any, String?>()

    /** Whether the screen of back stack entry [current] has scrolled under the header. */
    fun isScrolled(current: String?): Boolean = scrolledSlots.values.any { it.isShownFor(current) }

    /** The dock views of the screen of back stack entry [current]. */
    fun dockViewsFor(current: String?): DockViews? = dockSlots.values.firstOrNull { it.screen.isShownFor(current) }

    internal fun setDockViews(views: DockViews) {
        val now = dockSlots[views.owner]
        if (now == null || now.items != views.items || now.selectedIndex != views.selectedIndex || now.screen != views.screen) {
            dockSlots[views.owner] = views
        }
    }

    internal fun setScrolled(owner: Any, screen: String?, scrolled: Boolean) {
        if (!scrolled) {
            scrolledSlots.remove(owner)
        } else if (!scrolledSlots.containsKey(owner) || scrolledSlots[owner] != screen) {
            scrolledSlots[owner] = screen
        }
    }

    internal fun clear(owner: Any) {
        dockSlots.remove(owner)
        scrolledSlots.remove(owner)
    }
}

/** A value set from back stack entry [this] shows while that entry is the current one; outside navigation, always. */
private fun String?.isShownFor(current: String?): Boolean = this == null || this == current

val LocalScaffoldChrome = staticCompositionLocalOf { ScaffoldChrome() }

/**
 * Space a tab root leaves at the end of its scrolling content so the last item clears the bottom
 * dock (168 dp plus the navigation-bar inset). Zero on pushed screens, which have no dock.
 */
val LocalDockInset = compositionLocalOf<Dp> { 0.dp }

/** The back stack entry id of the screen this is called from, or null outside navigation. */
@Composable
private fun currentScreenId(): String? = (LocalLifecycleOwner.current as? NavBackStackEntry)?.id

/** Tells the scaffold whether this screen's content has scrolled under the pinned header. */
@Composable
fun ReportContentScrolled(scrolled: Boolean) {
    val chrome = LocalScaffoldChrome.current
    val owner = remember { Any() }
    val screen = currentScreenId()
    SideEffect { chrome.setScrolled(owner, screen, scrolled) }
    DisposableEffect(chrome) { onDispose { chrome.clear(owner) } }
}

/** A tab root's views for the dock's switch, such as Custom foods / Recipes. */
@Composable
fun DeclareDockViews(items: List<String>, selectedIndex: Int, onSelect: (Int) -> Unit) {
    val chrome = LocalScaffoldChrome.current
    val owner = remember { Any() }
    val screen = currentScreenId()
    val latestOnSelect by rememberUpdatedState(onSelect)
    val forward = remember { { index: Int -> latestOnSelect(index) } }
    SideEffect { chrome.setDockViews(DockViews(owner, items, selectedIndex, forward, screen)) }
    DisposableEffect(chrome) { onDispose { chrome.clear(owner) } }
}
