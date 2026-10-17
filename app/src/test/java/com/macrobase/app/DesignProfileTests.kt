package com.macrobase.app

import com.macrobase.app.domain.model.FitnessGoal
import com.macrobase.app.domain.model.Goal
import com.macrobase.app.feature.preferences.profileDisplayName
import com.macrobase.app.feature.preferences.profileStrategyLine
import org.junit.Assert.assertEquals
import org.junit.Test

/** The redesigned Profile screen (design phase 8): the card at the top. */
class DesignProfileTests {

    @Test
    fun theCardShowsTheSavedStrategyAndTarget() {
        assertEquals(
            "Cutting \u2022 2,100 kcal a day",
            profileStrategyLine(Goal(dailyCalorieGoal = 2099.6, fitnessGoal = FitnessGoal.CUTTING))
        )
        assertEquals("Maintaining \u2022 2,000 kcal a day", profileStrategyLine(Goal()))
    }

    @Test
    fun theCardShowsTheSavedName() {
        assertEquals("Asha Rao", profileDisplayName(" Asha ", "Rao"))
        assertEquals("Asha", profileDisplayName("Asha", ""))
        assertEquals("Rao", profileDisplayName("", "Rao"))
        // Before a name is saved
        assertEquals("Your profile", profileDisplayName("", " "))
    }
}
