package com.macrobase.app

import com.macrobase.app.feature.weight.WeightTimeInterval
import com.macrobase.app.feature.weight.weightIntervalChipLabel
import org.junit.Assert.assertEquals
import org.junit.Test

/** Design phase 8 (Water, Weight, Data & backup): display helpers of the restyled trackers. */
class DesignTrackersTests {

    @Test
    fun weightIntervalChipsShowEveryOptionInSentenceCase() {
        assertEquals(
            listOf("Last 30 days", "Last 3 months", "Last 6 months", "1 year", "All time", "Custom"),
            WeightTimeInterval.entries.map(::weightIntervalChipLabel)
        )
    }

    @Test
    fun theIntervalLabelsThemselvesAreUnchanged() {
        assertEquals("1 Year", WeightTimeInterval.ONE_YEAR.label)
        assertEquals("All Time", WeightTimeInterval.ALL_TIME.label)
        assertEquals("Custom", WeightTimeInterval.CUSTOM.label)
    }
}
