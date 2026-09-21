package com.beeregg2001.komorebi.data.util

import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime

/** Converts an API timestamp's instant to the Android TV device's local civil time. */
fun OffsetDateTime.toDeviceTime(): ZonedDateTime = atZoneSameInstant(ZoneId.systemDefault())

/** The local 4:00 television-day boundary containing this instant. */
fun OffsetDateTime.deviceTvDayStart(): OffsetDateTime {
    val localTime = toDeviceTime()
    val boundary = localTime.withHour(4).withMinute(0).withSecond(0).withNano(0)
    return (if (localTime.hour < 4) boundary.minusDays(1) else boundary).toOffsetDateTime()
}
