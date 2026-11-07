package com.macrobase.app

import androidx.core.view.WindowCompat
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * BUG-021: the app is dark only, but the system bars followed the phone's theme. With the phone in
 * Light mode the status icons went dark on the black app, and three-button navigation got a pale bar.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "notnight")
class DarkSystemBarsTests {

    private val activity by lazy { Robolectric.buildActivity(MainActivity::class.java).create().get() }

    @Test
    fun aPhoneInLightModeKeepsLightIconsOnBothBars() {
        val bars = WindowCompat.getInsetsController(activity.window, activity.window.decorView)

        assertFalse("status bar icons are dark", bars.isAppearanceLightStatusBars)
        assertFalse("navigation bar icons are dark", bars.isAppearanceLightNavigationBars)
    }

    @Test
    fun threeButtonNavigationGetsNoPaleBar() {
        assertFalse(activity.window.isNavigationBarContrastEnforced)
    }
}
