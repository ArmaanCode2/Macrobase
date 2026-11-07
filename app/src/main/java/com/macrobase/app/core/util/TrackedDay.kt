package com.macrobase.app.core.util

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.Clock
import java.time.LocalDate

/**
 * The day a tracker screen is on. While that is the current day it follows the calendar, so a screen
 * left open overnight logs to the new day; a day the user picked stays (BUG-006). Home does the same
 * for the dashboard (BUG-040).
 */
class TrackedDay(private val clock: Clock) {
    private val _selected = MutableStateFlow(LocalDate.now(clock))
    val selected: StateFlow<LocalDate> = _selected.asStateFlow()

    private val _today = MutableStateFlow(LocalDate.now(clock))

    /** The current day as of the last [refresh], for what depends on it, such as a date range. */
    val today: StateFlow<LocalDate> = _today.asStateFlow()

    private var followsToday = true

    fun select(date: LocalDate) {
        _selected.value = date
        followsToday = date == LocalDate.now(clock)
    }

    fun shift(days: Long) = select(_selected.value.plusDays(days))

    /** For when the screen is shown again, and before a write: nothing fires when midnight passes. */
    fun refresh() {
        val now = LocalDate.now(clock)
        _today.value = now
        // A day the user picked that has since become today follows the calendar from now on
        if (_selected.value == now) followsToday = true
        if (followsToday) _selected.value = now
    }
}
