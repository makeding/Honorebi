package com.beeregg2001.komorebi.common

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Display-only formatting for broadcast schedule times. It never changes stored timestamps. */
object ProgramTimeFormatter {
    const val TWELVE_HOUR = "12H"
    const val TWENTY_FOUR_HOUR = "24H"
    const val TWENTY_EIGHT_HOUR = "28H"

    private val twelveHourFormatter = DateTimeFormatter.ofPattern("a h:mm", Locale.JAPANESE)
    private val twentyFourHourFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.JAPANESE)

    data class DisplayDateTime(val date: LocalDate, val time: String)

    fun formatTime(time: LocalTime, format: String): String = when (format) {
        TWELVE_HOUR -> time.format(twelveHourFormatter)
        TWENTY_EIGHT_HOUR -> {
            val displayHour = time.hour + if (time.hour < 4) 24 else 0
            "%02d:%02d".format(Locale.ROOT, displayHour, time.minute)
        }
        else -> time.format(twentyFourHourFormatter)
    }

    fun displayDateTime(value: ZonedDateTime, format: String): DisplayDateTime {
        val isAfterMidnightInPreviousBroadcastDay =
            format == TWENTY_EIGHT_HOUR && value.hour < 4
        return DisplayDateTime(
            date = if (isAfterMidnightInPreviousBroadcastDay) value.toLocalDate().minusDays(1)
            else value.toLocalDate(),
            time = formatTime(value.toLocalTime(), format),
        )
    }

    fun formatDateTime(
        value: ZonedDateTime,
        format: String,
        datePattern: String,
        separator: String = " ",
    ): String {
        val display = displayDateTime(value, format)
        val date = display.date.format(DateTimeFormatter.ofPattern(datePattern, Locale.JAPANESE))
        return "$date$separator${display.time}"
    }

    fun formatRange(
        start: ZonedDateTime,
        end: ZonedDateTime?,
        format: String,
        datePattern: String,
        separator: String = " - ",
        includeEndDateOnCrossDate: Boolean = true,
    ): String {
        val startDisplay = displayDateTime(start, format)
        val startDate = startDisplay.date.format(DateTimeFormatter.ofPattern(datePattern, Locale.JAPANESE))
        val startText = "$startDate ${startDisplay.time}"
        if (end == null) return startText

        val endDisplay = displayDateTime(end, format)
        val endText = if (includeEndDateOnCrossDate && endDisplay.date != startDisplay.date) {
            val endDate = endDisplay.date.format(DateTimeFormatter.ofPattern(datePattern, Locale.JAPANESE))
            "$endDate ${endDisplay.time}"
        } else {
            endDisplay.time
        }
        return "$startText$separator$endText"
    }

    fun formatDateTime(
        value: String,
        format: String,
        datePattern: String,
        zoneId: ZoneId = ZoneId.systemDefault(),
    ): String? = parse(value, zoneId)?.let { formatDateTime(it, format, datePattern) }

    fun formatRange(
        start: String,
        end: String,
        format: String,
        datePattern: String,
        zoneId: ZoneId = ZoneId.systemDefault(),
        separator: String = " - ",
        includeEndDateOnCrossDate: Boolean = true,
    ): String? {
        val parsedStart = parse(start, zoneId) ?: return null
        val parsedEnd = end.takeIf(String::isNotBlank)?.let { parse(it, zoneId) }
        return formatRange(parsedStart, parsedEnd, format, datePattern, separator, includeEndDateOnCrossDate)
    }

    fun displayDate(date: LocalDate, time: LocalTime, format: String): LocalDate =
        if (format == TWENTY_EIGHT_HOUR && time.hour < 4) date.minusDays(1) else date

    private fun parse(value: String, zoneId: ZoneId): ZonedDateTime? =
        runCatching { ZonedDateTime.parse(value) }.getOrNull()
            ?: runCatching { OffsetDateTime.parse(value).atZoneSameInstant(zoneId) }.getOrNull()
            ?: runCatching { LocalDateTime.parse(value).atZone(zoneId) }.getOrNull()
            ?: runCatching { Instant.parse(value).atZone(zoneId) }.getOrNull()
}
