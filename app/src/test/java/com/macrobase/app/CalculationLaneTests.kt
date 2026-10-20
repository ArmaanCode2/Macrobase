package com.macrobase.app

import com.macrobase.app.core.util.settleAfterFirst
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/** The background lane: a burst of changes costs one recalculation, and the first value is never held back. */
@OptIn(ExperimentalCoroutinesApi::class)
class CalculationLaneTests {

    @Test
    fun theFirstValuePassesAtOnceAndABurstSettlesIntoOne() = runTest {
        val changes = MutableSharedFlow<Int>(extraBufferCapacity = 16)
        val seen = mutableListOf<Int>()
        val job = launch { changes.settleAfterFirst(400).collect { seen += it } }
        runCurrent()

        changes.emit(1)
        runCurrent()
        // Opening a screen shows its figures straight away
        assertEquals(listOf(1), seen)

        // Five quick changes, 100 ms apart
        for (value in 2..6) {
            changes.emit(value)
            advanceTimeBy(100)
        }
        assertEquals(listOf(1), seen)
        // Once they settle, only the last is worked on
        advanceTimeBy(401)
        assertEquals(listOf(1, 6), seen)

        // A later single change is worked on once it settles
        changes.emit(7)
        advanceTimeBy(401)
        assertEquals(listOf(1, 6, 7), seen)
        job.cancel()
    }
}
