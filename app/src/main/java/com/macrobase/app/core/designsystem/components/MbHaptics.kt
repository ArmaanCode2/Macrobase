package com.macrobase.app.core.designsystem.components

import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalView

/**
 * The app's light ticks. They play through the screen's view, so they follow the phone's own
 * touch-vibration setting (off there, off here) and need no permission.
 */
class MbHaptics internal constructor(private val view: View) {

    /** A different tab or view was switched to. */
    fun switched() {
        view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
    }

    /** A swiped row reached the point where letting go opens it. */
    fun reachedOpen() {
        val tick = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            HapticFeedbackConstants.GESTURE_THRESHOLD_ACTIVATE
        } else {
            HapticFeedbackConstants.CLOCK_TICK
        }
        view.performHapticFeedback(tick)
    }
}

@Composable
fun rememberMbHaptics(): MbHaptics {
    val view = LocalView.current
    return remember(view) { MbHaptics(view) }
}
