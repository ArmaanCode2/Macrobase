package com.macrobase.app.core.designsystem

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.dp

/**
 * Centralized Design System Shapes (designs/design.txt section 4). Buttons, chips, switches, the
 * bar, the capsule, the "+" button and avatars are fully round.
 */
object AppShapes {
    val Card = RoundedCornerShape(20.dp)
    val CardSmall = RoundedCornerShape(16.dp)
    val Row = RoundedCornerShape(18.dp)
    val CalendarCell = RoundedCornerShape(10.dp)
    val InputBox = RoundedCornerShape(14.dp)
    val Button = RoundedCornerShape(percent = 50)
    val PillButton = RoundedCornerShape(percent = 50)
    val Dialog = RoundedCornerShape(28.dp)
    val BottomSheet = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
}

val LocalAppShapes = staticCompositionLocalOf { AppShapes }
