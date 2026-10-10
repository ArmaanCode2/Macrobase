package com.macrobase.app.testutil

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset

/** A clock a test can move forward, for code that reads "today" (for example across midnight). */
class MutableTestClock(
    @Volatile private var now: Instant,
    private val zoneId: ZoneId = ZoneOffset.UTC
) : Clock() {

    constructor(date: LocalDate, time: LocalTime = LocalTime.NOON, zone: ZoneId = ZoneOffset.UTC) :
        this(date.atTime(time).atZone(zone).toInstant(), zone)

    fun advanceBy(duration: Duration) {
        now = now.plus(duration)
    }

    override fun getZone(): ZoneId = zoneId

    /** A fixed copy at the current instant; it does not move when this clock is advanced. */
    override fun withZone(zone: ZoneId): Clock = MutableTestClock(now, zone)

    override fun instant(): Instant = now
}
