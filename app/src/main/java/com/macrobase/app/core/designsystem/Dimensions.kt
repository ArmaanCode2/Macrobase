package com.macrobase.app.core.designsystem

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Standard Component Sizes and Touch Targets matching UI_UX_SPECIFICATION.md
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
    val MinTouchTarget: Dp = 40.dp
    val FoodThumbnailSize: Dp = 32.dp
    val CalorieProgressHeight: Dp = 3.dp
    val WaterProgressHeight: Dp = 6.dp
    val DonutChartSize: Dp = 100.dp
}
