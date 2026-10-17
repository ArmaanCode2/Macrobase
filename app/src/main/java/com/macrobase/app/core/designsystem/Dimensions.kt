package com.macrobase.app.core.designsystem

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Standard Component Sizes and Touch Targets
 */
object Dimensions {
    // App Bars and Headers
    val TopAppBarHeight: Dp = 48.dp
    val DateRibbonHeight: Dp = 36.dp
    val SectionHeaderHeight: Dp = 32.dp
    val MacroStripHeight: Dp = 32.dp
    val BottomNavHeight: Dp = 48.dp
    val DrawerWidth: Dp = 280.dp

    // Interactive & List Components
    val FoodRowHeight: Dp = 52.dp
    val FoodRowMinHeight: Dp = 48.dp
    val FormRowHeight: Dp = 40.dp
    val ButtonHeight: Dp = 40.dp
    val ButtonHeightSmall: Dp = 28.dp
    val SearchInputHeight: Dp = 32.dp

    // Calendar
    val CalendarCellSize: Dp = 36.dp
    val CalendarCellSpacing: Dp = 4.dp
    val HeatmapCellSize: Dp = 10.dp
    val HeatmapCellSpacing: Dp = 2.dp

    // Icons & Targets
    val IconSizeSmall: Dp = 16.dp
    val IconSizeMedium: Dp = 20.dp
    val IconSizeLarge: Dp = 24.dp
    val MinTouchTarget: Dp = 44.dp
    val FoodThumbnailSize: Dp = 32.dp
    val CalorieProgressHeight: Dp = 3.dp
    val WaterProgressHeight: Dp = 6.dp
    val DonutChartSize: Dp = 100.dp

    // Bottom dock (designs/design.txt section 4)
    val DockGap: Dp = 16.dp              // Dock to screen edges, bar to "+" button
    val DockPiece: Dp = 60.dp            // Height of the bar and the view switch
    val NavItem: Dp = 48.dp              // Height of a tab inside the bar
    val NavActiveWidth: Dp = 98.dp       // The active tab, icon plus label
    val Fab: Dp = 60.dp
    val ViewSwitchItem: Dp = 108.dp      // Item width in a scrolling view switch
    val ViewSwitchFade: Dp = 26.dp       // Edge fade of a scrolling view switch
    val ContentBottomPadding: Dp = 168.dp // Scroll content clears the dock (plus the nav-bar inset)
    val DockFade: Dp = 120.dp            // Fade behind the dock
    val DockFadeWithSwitch: Dp = 196.dp

    // Mentor components
    val IconCircle: Dp = 36.dp
    val IconCircleLarge: Dp = 44.dp
    val HeaderButton: Dp = 46.dp
    val MacroRing: Dp = 78.dp
    val MacroRingStroke: Dp = 5.dp
    val ButtonHeightMentor: Dp = 48.dp
}
