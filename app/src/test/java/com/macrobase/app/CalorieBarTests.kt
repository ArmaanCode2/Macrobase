package com.macrobase.app

import androidx.compose.ui.graphics.colorspace.ColorSpaces
import com.macrobase.app.core.config.CalendarPerformanceConfig
import com.macrobase.app.core.designsystem.AppBrushes
import com.macrobase.app.core.designsystem.AppColors
import com.macrobase.app.feature.dashboard.calorieBarFill
import com.macrobase.app.feature.dashboard.calorieBarStops
import com.macrobase.app.feature.dashboard.calorieOverShare
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.hypot

/**
 * Home's calorie bar turned fully red at 1 kcal over the goal. It now turns from its green toward red
 * step by step, through amber, and is fully red at the calendar's "Well over" line (120% of the goal).
 */
class CalorieBarTests {

    @Test
    fun theBarKeepsItsGreenUpToTheGoal() {
        assertEquals(0f, calorieOverShare(1500.0, 2000.0), 0f)
        assertEquals(0f, calorieOverShare(2000.0, 2000.0), 0f)
        assertEquals(AppBrushes.BrandGradientStops, calorieBarStops(0f))
    }

    @Test
    fun tenCaloriesOverIsOnlyATouchOfRed() {
        val share = calorieOverShare(2010.0, 2000.0)

        assertTrue(share > 0f)
        assertEquals(0.025f, share, 0.001f)
    }

    @Test
    fun theRedGrowsWithTheExcessAndIsFullAtTheCalendarsWellOverLine() {
        assertEquals(0.5f, calorieOverShare(2200.0, 2000.0), 0.001f)
        assertEquals(1f, calorieOverShare(2000.0 * CalendarPerformanceConfig.OVER_BUDGET_RATIO, 2000.0), 0.001f)
        assertEquals(1f, calorieOverShare(5000.0, 2000.0), 0f)
        assertTrue(calorieBarStops(1f).all { it.second == AppColors.ProgressOver })
    }

    // A straight blend of green and red greys out halfway (khaki at 110% of the goal); through amber it stays bright
    @Test
    fun theColoursBetweenStayVivid() {
        assertTrue(calorieBarStops(0.5f).all { it.second == AppColors.Warning })
        for (step in 0..20) {
            calorieBarStops(step / 20f).forEach { (at, color) ->
                val lab = color.convert(ColorSpaces.Oklab)
                val chroma = hypot(lab.green, lab.blue)
                assertTrue("stop $at at ${step * 5}% is greyed: chroma $chroma", chroma >= 0.135f)
            }
        }
    }

    @Test
    fun halfwayEveryColourSitsBetweenItsGreenAndRed() {
        val half = calorieBarStops(0.5f)

        AppBrushes.BrandGradientStops.zip(half).forEach { (green, mixed) ->
            assertEquals(green.first, mixed.first, 0f)
            assertTrue("$mixed", mixed.second.red > green.second.red && mixed.second.red <= AppColors.ProgressOver.red)
            assertTrue("$mixed", mixed.second.green < green.second.green && mixed.second.green >= AppColors.ProgressOver.green)
        }
    }

    // As before, food logged against no goal shows the bar full and red
    @Test
    fun withNoGoalAnyFoodIsOver() {
        assertEquals(1f, calorieOverShare(500.0, 0.0), 0f)
        assertEquals(0f, calorieOverShare(0.0, 0.0), 0f)
        assertEquals(1f, calorieBarFill(500.0, 0.0), 0f)
        assertEquals(0f, calorieBarFill(0.0, 0.0), 0f)
    }

    @Test
    fun theBarFillsToTheGoalAndStaysFull() {
        assertEquals(0.75f, calorieBarFill(1500.0, 2000.0), 0.0001f)
        assertEquals(1f, calorieBarFill(2010.0, 2000.0), 0f)
    }

    @Test
    fun theBarDrawsTheBlendInsteadOfASolidRed() {
        val path = "src/main/java/com/macrobase/app/feature/dashboard/HomeScreen.kt"
        val home = listOf(File(path), File("app/$path")).first { it.isFile }.readText().replace("\r\n", "\n")
        val bar = home.substring(home.indexOf("private fun CalorieBar(")).substringBefore("\n}\n")

        assertTrue(bar.contains("calorieBarStops(calorieOverShare(eaten, goal))"))
        assertTrue(bar.contains(".background(brush)"))
        assertFalse(bar.contains("AppColors.ProgressOver"))
        assertFalse(bar.contains("AppBrushes.BrandGradient)"))
    }
}
