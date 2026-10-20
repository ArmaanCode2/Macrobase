package com.macrobase.app.core.navigation

import androidx.compose.ui.geometry.Offset
import com.macrobase.app.core.designsystem.components.glowTranslation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

/** The shell's motion: what the dock and header show during a navigation, and the aura's layers. */
class ShellMotionTests {

    @Test
    fun aLeavingScreensViewSwitchStopsShowingAtOnce() {
        val chrome = ScaffoldChrome()
        val views = DockViews(owner = Any(), items = listOf("Custom foods", "Recipes"), selectedIndex = 0, onSelect = {}, screen = "foods-entry")
        chrome.setDockViews(views)
        assertSame(views, chrome.dockViewsFor("foods-entry"))
        // Home is the current screen while Foods is still on its way out
        assertNull(chrome.dockViewsFor("home-entry"))
    }

    @Test
    fun aCopiedEntryKeepsItsViewSwitch() {
        // Tapping a tab again or restoring it gives a new entry object with the same id
        val chrome = ScaffoldChrome()
        chrome.setDockViews(DockViews(owner = Any(), items = listOf("A", "B"), selectedIndex = 0, onSelect = {}, screen = "stats-entry"))
        val sameIdNewString = String(charArrayOf('s', 't', 'a', 't', 's', '-', 'e', 'n', 't', 'r', 'y'))
        assertNotNull(chrome.dockViewsFor(sameIdNewString))
    }

    @Test
    fun aLeavingScreenNeverOverwritesOrClearsTheArrivingScreensViews() {
        val chrome = ScaffoldChrome()
        val stats = Any()
        val foods = Any()
        val foodsViews = DockViews(owner = foods, items = listOf("Custom foods", "Recipes"), selectedIndex = 1, onSelect = {}, screen = "foods-entry")
        chrome.setDockViews(foodsViews)
        // Stats recomposes during its exit, after Foods declared its views
        chrome.setDockViews(DockViews(owner = stats, items = listOf("Calendar", "Weight"), selectedIndex = 0, onSelect = {}, screen = "stats-entry"))
        assertSame(foodsViews, chrome.dockViewsFor("foods-entry"))
        // Stats leaves composition
        chrome.clear(stats)
        assertSame(foodsViews, chrome.dockViewsFor("foods-entry"))
        chrome.clear(foods)
        assertNull(chrome.dockViewsFor("foods-entry"))
    }

    @Test
    fun viewsDeclaredOutsideNavigationAlwaysShow() {
        val chrome = ScaffoldChrome()
        chrome.setDockViews(DockViews(owner = Any(), items = listOf("A", "B"), selectedIndex = 1, onSelect = {}))
        assertNotNull(chrome.dockViewsFor("any-entry"))
        assertNotNull(chrome.dockViewsFor(null))
    }

    @Test
    fun theHeaderBandFollowsTheCurrentScreen() {
        val chrome = ScaffoldChrome()
        val stats = Any()
        val rank = Any()
        assertFalse(chrome.isScrolled("stats-entry"))
        chrome.setScrolled(stats, "stats-entry", scrolled = true)
        assertTrue(chrome.isScrolled("stats-entry"))
        // After switching to Rank, Stats' scrolled list no longer bands Rank's header
        assertFalse(chrome.isScrolled("rank-entry"))
        // Rank scrolls; Stats, still leaving, scrolling back to the top does not unband it
        chrome.setScrolled(rank, "rank-entry", scrolled = true)
        chrome.setScrolled(stats, "stats-entry", scrolled = false)
        assertTrue(chrome.isScrolled("rank-entry"))
        chrome.clear(rank)
        assertFalse(chrome.isScrolled("rank-entry"))
    }

    @Test
    fun theFiveTabRootsAreRecognised() {
        listOf(Screen.Home.route, "home", Screen.CustomFoods.route, Screen.Statistics.route, Screen.Rank.route, Screen.Preferences.route)
            .forEach { assertTrue(it, isTabRootRoute(it)) }
        listOf(Screen.Weight.route, Screen.Water.route, Screen.Basket.route, Screen.Recipes.route, Screen.FoodDetail.route, null)
            .forEach { assertFalse(it.toString(), isTabRootRoute(it)) }
    }

    @Test
    fun theAuraLayersMoveTheGlowExactlyAsTheOldPerFrameDrawing() {
        // Layer A on a 393 dp wide screen at 2.75x: centre, radii and orbit in pixels
        val density = 2.75f
        val width = 393f * density
        val center = Offset(width * 0.04f, 0f)
        val radiusX = width * 0.9f
        val radiusY = 340f * density
        val k = radiusY / radiusX
        val orbitX = 34f * density
        val orbitY = 21f * density
        for (angle in listOf(0f, 37f, 90f, 181f, 270f, 333f)) {
            for ((stretchX, stretchY) in listOf(1f to 0.72f, 1.04f to 1f, 1.08f to 1.32f)) {
                val radians = Math.toRadians(angle.toDouble())
                val ox = orbitX * cos(radians).toFloat()
                val oy = orbitY * sin(radians).toFloat()
                val shift = glowTranslation(orbitX, orbitY, angle, stretchX, stretchY)
                for (theta in listOf(0.0, 0.7, 2.1, 3.9, 5.5)) {
                    val p = Offset(center.x + radiusX * cos(theta).toFloat(), center.y + radiusX * sin(theta).toFloat())
                    // Before: scale(stretchX, stretchY * k) about the centre, then translate(ox, oy / k)
                    val before = Offset(
                        center.x + stretchX * (p.x - center.x + ox),
                        center.y + stretchY * k * (p.y - center.y + oy / k)
                    )
                    // Now: drawn at rest as an ellipse, then the layer scales about the centre and translates
                    val rest = Offset(p.x, center.y + k * (p.y - center.y))
                    val now = Offset(
                        center.x + stretchX * (rest.x - center.x) + shift.x,
                        center.y + stretchY * (rest.y - center.y) + shift.y
                    )
                    assertEquals(before.x, now.x, 0.01f)
                    assertEquals(before.y, now.y, 0.01f)
                }
            }
        }
    }
}
