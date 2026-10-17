package com.macrobase.app.core.designsystem

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.LinearGradientShader
import androidx.compose.ui.graphics.Shader
import androidx.compose.ui.graphics.ShaderBrush
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Centralized Design System Colors: the "Mentor" design (designs/design.txt section 2).
 * Dark only. Text and icons on Primary or on a green gradient use the dark ink [OnPrimary];
 * light text on #5BD86A is only about 1.6:1.
 */
object AppColors {
    // Core Brand Palette
    val Primary = Color(0xFF5BD86A)         // Active states, links, ticks, focus
    val PrimaryDark = Color(0xFF2DB04C)     // Pressed states, start of the button gradient
    val OnPrimary = Color(0xFF050A06)       // Ink on Primary and the button gradient
    val Background = Color(0xFF060706)      // Base Screen Canvas
    val Surface = Color(0xFF0A0D0B)         // Sheets and dialogs
    val SurfaceAlt = Color(0xFF0D120F)      // Pills, bars, date headers
    val SurfaceInput = Color(0xFF0D110E)    // Form Text Fields, Search Inputs
    val Divider = Color(0x12FFFFFF)         // Row Separators: white at 7%
    val DividerStrong = Color(0x29FFFFFF)   // White at 16%

    // Typography Colors
    val TextPrimary = Color(0xFFEDF2EE)     // Screen Titles, Food Names
    val TextSecondary = Color(0xFFA7B0A9)   // Subtitles, Serving Units, Dates
    val TextDisabled = Color(0xFF4A524C)    // Disabled Action Items
    val TextMuted = Color(0xFF838D85)       // Empty State Hints

    // Data Visualization & Accent Highlights
    val CalorieText = Color(0xFF84E48F)     // Calorie Value Highlight Text
    val MacroProtein = Color(0xFF5BD86A)
    val MacroCarbs = Color(0xFFD3F444)
    val MacroFat = Color(0xFF33CB9E)
    val ProgressOver = Color(0xFFFF6B5E)    // Over-Calorie Limit Warning
    val WaterCyan = Color(0xFF3FD0C9)       // Water Progress Fill

    // Semantic Status & Feedback Colors
    val Success = Color(0xFF6EE27C)
    val SuccessSurface = Color(0xFF14221A)
    val Warning = Color(0xFFFFA000)
    val WarningSurface = Color(0xFF2A2010)
    val Error = Color(0xFFFF6B5E)
    val ErrorSurface = Color(0xFFB5372D)
    val AlertRed = Color(0xFFB5372D)
    val AlertRedLight = Color(0xFFFF8A7E)

    // Calendar Heatmap Performance Colors
    val CalendarGreenOptimal = Color(0xFF3CB956) // On target (0.85 - 1.05x Goal)
    val CalendarGreenLight = Color(0xFF6EE27C)   // Optimal Highlight
    val CalendarGreenSubtle = Color(0xFF193A20)  // Under Budget (< 0.85x Goal)
    val CalendarRedWarning = Color(0xFF75731F)   // Moderate Over (1.05 - 1.20x Goal)
    val CalendarRedAlert = Color(0xFF7D3626)     // High Over Target (> 1.20x Goal)
    val CalendarEmpty = Color(0x0AFFFFFF)        // No Foods Logged (C = 0): white at 4%
    val CalendarOptimalInk = Color(0xFF04170A)   // Day number on CalendarGreenOptimal
    val CalendarUnderInk = Color(0xFFC9F5CF)     // Day number on CalendarGreenSubtle

    // Mentor surfaces and edges
    val Accent = Color(0xFF5BD86A)
    val AccentStrong = Color(0xFF84E48F)    // Rank name, emphasised values
    val Focus = Color(0xFF9DF0A7)
    val Edge = Color(0x1A96FFAA)            // Outline of panels and rows: rgba(150,255,170,0.10)
    val Edge2 = Color(0x3896FFAA)           // Icon circles, sheets: rgba(150,255,170,0.22)
    val PanelFill = Color(0x05FFFFFF)       // rgba(255,255,255,0.018) over the canvas
    val PanelTint = Color(0x0F6EE682)       // rgba(110,230,130,0.06), the top-left panel tint
    val RowFill = Color(0x04FFFFFF)         // rgba(255,255,255,0.015)
    val RowTile = Color(0xFF0B0F0C)         // Opaque row, for rows that must hide what is behind them
    val SheetFill = Color(0xFF0A0D0B)
    val FieldFill = Color(0x04FFFFFF)
    val FieldBorder = Color(0x1FFFFFFF)     // White at 12%
    val FieldRing = Color(0x295BD86A)       // rgba(91,216,106,0.16)
    val DangerFill = Color(0xFFB5372D)
    val OnDanger = Color(0xFFFFF8F6)
    val CopyFill = Color(0xFF7A4A12)
    val OnCopy = Color(0xFFFFF8F4)
    val PillFill = Color(0xFF0D120F)        // Bottom bar and view switch
    val PillInnerLine = Color(0x14FFFFFF)   // White at 8%
    val Capsule = Color(0xFF17261C)
    val CapsuleBorder = Color(0x475BD86A)   // rgba(91,216,106,0.28)
    val NavActive = Color(0xFF6FE07C)
    val NavInactive = Color(0xFF9AA39C)
    val ChipBorder = Color(0x21FFFFFF)      // White at 13%
    val ChipInk = Color(0xFFDFE6E0)
    val ChipOnFill = Color(0x215BD86A)      // rgba(91,216,106,0.13)
    val ChipOnBorder = Color(0x995BD86A)    // rgba(91,216,106,0.60)
    val ChipOnInk = Color(0xFFA6F2AE)
    val IconCircleFill = Color(0xFF0C140E)
    val IconCircleInk = Color(0xFFC9F5CF)
    val Badge = Color(0xFFD3F444)
    val BadgeInk = Color(0xFF0B1A06)
    val MaxRankBadge = Color(0xFFFFA000)
    val MaxRankInk = Color(0xFF1C1300)
    val ToastFill = Color(0xFF14221A)
    val ToastInk = Color(0xFFC9F5CF)
    val Scrim = Color(0xA8000000)           // rgba(0,0,0,0.66)
    val HeaderBand = Color(0xFF070907)      // Pinned header once content scrolls under it
    val HeaderBandLine = Color(0x0FFFFFFF)  // White at 6%
    val BarTrack = Color(0x12FFFFFF)        // White at 7%
    val ChartLine = Color(0xFF7FDC48)
    val ChartArea = Color(0x1A7FDC48)       // rgba(127,220,72,0.10)
    val ChartGrid = Color(0x29FFFFFF)       // White at 16%
    val FabInk = Color(0xFF06200C)
    val AvatarInk = Color(0xFFA6F2AE)
    val AvatarFill = Color(0xFF0B120D)
    val SearchPillFill = Color(0x40000000)  // rgba(0,0,0,0.25)
    val ScanOutlineBorder = Color(0x8C5BD86A) // rgba(91,216,106,0.55)
}

/** The design's gradients (design.txt section 2). */
object AppBrushes {
    /** Calorie bar and widget bar. */
    val BrandGradient: Brush = Brush.horizontalGradient(
        0f to Color(0xFF35CE4F), 0.52f to Color(0xFF7FDC48), 1f to Color(0xFFD3F444)
    )

    /** Primary buttons, with [AppColors.OnPrimary] ink. */
    val PrimaryButtonGradient: Brush = Brush.horizontalGradient(
        0f to Color(0xFF2DB04C), 0.55f to Color(0xFF62BF3F), 1f to Color(0xFF9CCB3A)
    )

    /** The "+" button, with [AppColors.FabInk] ink. */
    val FabGradient: Brush = cssLinearGradient(
        140f, 0f to Color(0xFFB6EF4A), 0.45f to Color(0xFF6FDC5A), 1f to Color(0xFF2FC6A0)
    )

    /** The faint green tint at the top left of each panel, drawn over [AppColors.PanelFill]. */
    val PanelTint: Brush = cssLinearGradient(
        160f, 0f to Color(0x0F6EE682), 0.42f to Color(0x03FFFFFF), 1f to Color(0x03FFFFFF)
    )

    /** The avatar ring: a sweep from 200 degrees. */
    val AvatarRing: Brush = Brush.sweepGradient(
        0f to Color(0xFF2FC6A0), 0.5f to Color(0xFFB6EF4A), 1f to Color(0xFF2FC6A0)
    )

    /**
     * A linear gradient at a CSS angle (0 = to top, 90 = to right, clockwise), sized like CSS: the
     * gradient line runs through the centre and is long enough for the corners to reach the end stops.
     */
    fun cssLinearGradient(angleDegrees: Float, vararg stops: Pair<Float, Color>): Brush = object : ShaderBrush() {
        override fun createShader(size: Size): Shader {
            val radians = Math.toRadians(angleDegrees.toDouble())
            val dx = sin(radians).toFloat()
            val dy = -cos(radians).toFloat()
            val half = (abs(size.width * dx) + abs(size.height * dy)) / 2f
            val center = Offset(size.width / 2f, size.height / 2f)
            return LinearGradientShader(
                from = Offset(center.x - dx * half, center.y - dy * half),
                to = Offset(center.x + dx * half, center.y + dy * half),
                colors = stops.map { it.second },
                colorStops = stops.map { it.first }
            )
        }
    }
}

val LocalAppColors = staticCompositionLocalOf { AppColors }
