package com.macrobase.app.core.designsystem

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Centralized Design System Colors matching UI_UX_SPECIFICATION.md
 */
object AppColors {
    // Core Brand Palette
    val Primary = Color(0xFF009639)         // Primary App Bars, Hero Action Buttons, Active Tab Fill
    val PrimaryDark = Color(0xFF007029)     // Pressed States, Header Contours
    val Background = Color(0xFF121212)      // Base Screen Canvas
    val Surface = Color(0xFF1E1E1E)         // Card Surfaces, Dialog Windows
    val SurfaceAlt = Color(0xFF242424)      // Date Headers, Sub-bars
    val SurfaceInput = Color(0xFF2C2C2C)    // Form Text Fields, Search Inputs
    val Divider = Color(0xFF333333)         // Row Separators, Section Borders

    // Typography Colors
    val TextPrimary = Color(0xFFFFFFFF)     // Screen Titles, Food Names
    val TextSecondary = Color(0xFFA0A0A0)   // Subtitles, Serving Units, Dates
    val TextDisabled = Color(0xFF555555)    // Disabled Action Items
    val TextMuted = Color(0xFF777777)       // Empty State Hints

    // Data Visualization & Accent Highlights
    val CalorieText = Color(0xFF00C853)     // Calorie Value Highlight Text
    val MacroProtein = Color(0xFF4A90E2)    // Protein Grams / Legend Slices (Blue)
    val MacroCarbs = Color(0xFFF5A623)      // Carbohydrates Grams / Slices (Gold/Amber)
    val MacroFat = Color(0xFFD0021B)        // Fat Grams / Legend Slices (Red)
    val ProgressOver = Color(0xFFE53935)    // Over-Calorie Limit Warning Bar
    val WaterCyan = Color(0xFF00BCD4)       // Water Progress Fill & Chevrons

    // Semantic Status & Feedback Colors
    val Success = Color(0xFF00C853)
    val SuccessSurface = Color(0xFF1B5E20)
    val Warning = Color(0xFFFFA000)
    val WarningSurface = Color(0xFF3E2723)
    val Error = Color(0xFFE53935)
    val ErrorSurface = Color(0xFFB71C1C)
    val AlertRed = Color(0xFFD32F2F)
    val AlertRedLight = Color(0xFFEF5350)

    // Calendar Heatmap Performance Colors
    val CalendarGreenOptimal = Color(0xFF2E7D32) // Green Day (0.85 - 1.05x Goal)
    val CalendarGreenLight = Color(0xFF4CAF50)   // Optimal Highlight
    val CalendarGreenSubtle = Color(0xFF1B5E20)  // Under Budget (< 0.85x Goal)
    val CalendarRedWarning = Color(0xFFC62828)   // Moderate Over (1.05 - 1.20x Goal)
    val CalendarRedAlert = Color(0xFFE53935)     // High Over Target (> 1.20x Goal)
    val CalendarEmpty = Color(0xFF1E1E1E)        // No Foods Logged (C = 0)
}

val LocalAppColors = staticCompositionLocalOf { AppColors }
