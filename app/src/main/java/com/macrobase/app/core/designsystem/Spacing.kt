package com.macrobase.app.core.designsystem

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Strict base grid spacing system matching UI_UX_SPECIFICATION.md
 */
object AppSpacing {
    val xxs: Dp = 1.dp   // Micro dividers, inner dot borders
    val xs: Dp = 2.dp    // Inner badge padding, tight element grouping
    val sm: Dp = 4.dp    // Item padding, icon-to-text spacing, grid gaps
    val md: Dp = 8.dp    // Row internal padding, sub-card spacing
    val lg: Dp = 12.dp   // Standard screen margins, card padding
    val xl: Dp = 16.dp   // Section spacing, bottom action bar separation
    val xxl: Dp = 24.dp  // Top hero module separation
}

val LocalAppSpacing = staticCompositionLocalOf { AppSpacing }
