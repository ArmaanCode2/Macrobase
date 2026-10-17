package com.macrobase.app.core.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** The views a tab root shows in the dock's view switch. [owner] is the screen that declared them. */
class DockViews(
    val owner: Any,
    val items: List<String>,
    val selectedIndex: Int,
    val onSelect: (Int) -> Unit
)

/**
 * What the screen on top tells the scaffold: whether its content has scrolled under the header (the
 * header then gets its band), and the views for the dock's switch. The screen owns its state; the
 * scaffold only shows it and forwards taps. Each value remembers its owner, so the screen leaving
 * during a transition never clears what the arriving screen set.
 */
@Stable
class ScaffoldChrome {
    private var scrolledOwner by mutableStateOf<Any?>(null)

    val contentScrolled: Boolean get() = scrolledOwner != null

    var dockViews by mutableStateOf<DockViews?>(null)
        internal set

    internal fun setScrolled(owner: Any, scrolled: Boolean) {
        if (scrolled) scrolledOwner = owner else if (scrolledOwner === owner) scrolledOwner = null
    }

    internal fun clear(owner: Any) {
        if (scrolledOwner === owner) scrolledOwner = null
        if (dockViews?.owner === owner) dockViews = null
    }
}

val LocalScaffoldChrome = staticCompositionLocalOf { ScaffoldChrome() }

/**
 * Space a tab root leaves at the end of its scrolling content so the last item clears the bottom
 * dock (168 dp plus the navigation-bar inset). Zero on pushed screens, which have no dock.
 */
val LocalDockInset = compositionLocalOf<Dp> { 0.dp }

/** Tells the scaffold whether this screen's content has scrolled under the pinned header. */
@Composable
fun ReportContentScrolled(scrolled: Boolean) {
    val chrome = LocalScaffoldChrome.current
    val owner = remember { Any() }
    SideEffect { chrome.setScrolled(owner, scrolled) }
    DisposableEffect(chrome) { onDispose { chrome.clear(owner) } }
}

/** A tab root's views for the dock's switch, such as Custom foods / Recipes. */
@Composable
fun DeclareDockViews(items: List<String>, selectedIndex: Int, onSelect: (Int) -> Unit) {
    val chrome = LocalScaffoldChrome.current
    val owner = remember { Any() }
    val latestOnSelect by rememberUpdatedState(onSelect)
    val forward = remember { { index: Int -> latestOnSelect(index) } }
    SideEffect {
        val current = chrome.dockViews
        if (current?.owner !== owner || current.items != items || current.selectedIndex != selectedIndex) {
            chrome.dockViews = DockViews(owner, items, selectedIndex, forward)
        }
    }
    DisposableEffect(chrome) { onDispose { chrome.clear(owner) } }
}
