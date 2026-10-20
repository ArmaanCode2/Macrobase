package com.macrobase.app

import androidx.compose.ui.graphics.Color
import com.macrobase.app.core.designsystem.AppColors
import com.macrobase.app.feature.dashboard.daySwipeStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.File

/** Version 1.2.6: the day swipe on Home's calories panel, and the pure black background. */
class HomeTweaksTests {

    @Test
    fun swipingTheCaloriesPanelStepsADayAsTheCalendarStepsAMonth() {
        val threshold = 48f
        // Finger moved left: the next day, as the calendar shows the next month
        assertEquals(1, daySwipeStep(-49f, threshold))
        // Finger moved right: the day before
        assertEquals(-1, daySwipeStep(49f, threshold))
        // Too short to count, either way
        assertEquals(0, daySwipeStep(-48f, threshold))
        assertEquals(0, daySwipeStep(48f, threshold))
        assertEquals(0, daySwipeStep(0f, threshold))
    }

    @Test
    fun theBackgroundIsPureBlackFromTheFirstFrame() {
        assertEquals(Color.Black, AppColors.Background)
        // The window and the system bars show before Compose draws, so they match it
        val themes = listOf(File("src/main/res/values/themes.xml"), File("app/src/main/res/values/themes.xml")).first { it.isFile }.readText()
        listOf("statusBarColor", "navigationBarColor", "windowBackground").forEach { item ->
            assertEquals(item, "#000000", Regex("""name="android:$item">([^<]+)<""").find(themes)?.groupValues?.get(1))
        }
        assertFalse(themes.contains("#060706"))
    }
}
