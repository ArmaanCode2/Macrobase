package com.macrobase.app.core.designsystem

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.dp

/**
 * Centralized Design System Shapes matching UI_UX_SPECIFICATION.md
 */
object AppShapes {
    val Card = RoundedCornerShape(2.dp)
    val CardSmall = RoundedCornerShape(0.dp)
    val CalendarCell = RoundedCornerShape(2.dp)
    val InputBox = RoundedCornerShape(2.dp)
    val Button = RoundedCornerShape(4.dp)
    val PillButton = RoundedCornerShape(12.dp)
    val Dialog = RoundedCornerShape(4.dp)
    val BottomSheet = RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp)
}

val LocalAppShapes = staticCompositionLocalOf { AppShapes }
