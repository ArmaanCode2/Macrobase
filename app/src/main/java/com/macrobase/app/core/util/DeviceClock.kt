package com.macrobase.app.core.util

import java.time.Clock
import java.time.Instant
import java.time.ZoneId

/**
 * The device clock, in whatever time zone the device is set to right now. Unlike
 * Clock.systemDefaultZone(), the zone is read on every call, so a time zone change while the app
 * runs is seen the same way LocalDate.now() sees it. Classes take a [Clock] so tests can fix "now".
 */
object DeviceClock : Clock() {
    override fun getZone(): ZoneId = ZoneId.systemDefault()

    override fun withZone(zone: ZoneId): Clock = system(zone)

    override fun instant(): Instant = Instant.now()
}
