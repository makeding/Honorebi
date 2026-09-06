package com.beeregg2001.komorebi.ui.video.player

import com.beeregg2001.komorebi.data.model.RecordedProgram
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Formats static metadata already present on [RecordedProgram] for the player information panel.
 *
 * This deliberately has no Compose or Android dependency so the overlay can render it without
 * doing date parsing during composition, and malformed timestamps from a backend cannot break
 * playback controls.
 */
internal data class PlaybackProgramInfoRow(
    val label: String,
    val value: String
)

/** Row API used by the detailed program-information panel. */
internal object PlaybackProgramInfoFormatter {
    fun format(program: RecordedProgram, timeFormat: String): List<PlaybackProgramInfoRow> =
        formatPlaybackProgramMetaRows(program, timeFormat, ZoneId.systemDefault())
}

private fun formatPlaybackProgramMetaRows(
    program: RecordedProgram,
    timeFormat: String,
    zoneId: ZoneId
): List<PlaybackProgramInfoRow> = buildList {
    formatChannel(program)?.let { add(PlaybackProgramInfoRow("チャンネル", it)) }
    formatBroadcastTime(program.startTime, program.endTime, timeFormat, zoneId)
        ?.let { add(PlaybackProgramInfoRow("放送日時", it)) }
    val actualStart = program.recordedVideo.recordingStartTime.orEmpty()
    val actualEnd = program.recordedVideo.recordingEndTime.orEmpty()
    val startKnown = parseDateTime(actualStart, zoneId) != null
    val endKnown = parseDateTime(actualEnd, zoneId) != null
    val actual = when {
        startKnown && endKnown -> formatBroadcastTime(actualStart, actualEnd, timeFormat, zoneId)!!
        startKnown -> "${formatBroadcastTime(actualStart, "", timeFormat, zoneId)} - 終了時刻未記録"
        endKnown -> "開始時刻未記録 - ${formatBroadcastTime(actualEnd, "", timeFormat, zoneId)}"
        else -> "記録されていません"
    }
    add(PlaybackProgramInfoRow("実際の録画日時", actual))
    formatDuration(program.recordedVideo.duration.takeIf { it > 0.0 } ?: program.duration)
        ?.let { add(PlaybackProgramInfoRow("長さ", it)) }
    formatStatus(program)?.let { add(PlaybackProgramInfoRow("状態", it)) }
}

private fun formatChannel(program: RecordedProgram): String? {
    val channel = program.channel ?: return null
    return listOf(channel.name, channel.displayChannelId)
        .map(String::trim)
        .filter(String::isNotEmpty)
        .distinct()
        .joinToString(" ・ ")
        .ifEmpty { null }
}

internal fun formatBroadcastTime(
    startTime: String,
    endTime: String,
    timeFormat: String,
    zoneId: ZoneId = ZoneId.systemDefault()
): String? {
    val start = parseDateTime(startTime, zoneId) ?: return null
    val startPattern = if (timeFormat == "12H") "yyyy/MM/dd(E) a h:mm" else "yyyy/MM/dd(E) HH:mm"
    val formattedStart = DateTimeFormatter.ofPattern(startPattern, Locale.JAPANESE).format(start)
    val end = parseDateTime(endTime, zoneId) ?: return formattedStart
    val crossesDate = java.time.LocalDate.from(start) != java.time.LocalDate.from(end)
    val endPattern = (if (crossesDate) "yyyy/MM/dd(E) " else "") +
        (if (timeFormat == "12H") "a h:mm" else "HH:mm")
    return "$formattedStart - ${DateTimeFormatter.ofPattern(endPattern, Locale.JAPANESE).format(end)}"
}

private fun parseDateTime(value: String, zoneId: ZoneId): java.time.temporal.TemporalAccessor? =
    runCatching { ZonedDateTime.parse(value) }.getOrNull()
        ?: runCatching { OffsetDateTime.parse(value) }.getOrNull()
        ?: runCatching { LocalDateTime.parse(value) }.getOrNull()
        ?: runCatching { Instant.parse(value).atZone(zoneId) }.getOrNull()

private fun formatDuration(seconds: Double): String? {
    if (!seconds.isFinite() || seconds <= 0.0) return null
    val totalSeconds = seconds.toLong()
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val remainingSeconds = totalSeconds % 60
    return if (hours > 0) {
        "%d:%02d:%02d".format(Locale.ROOT, hours, minutes, remainingSeconds)
    } else {
        "%d:%02d".format(Locale.ROOT, minutes, remainingSeconds)
    }
}

private fun formatStatus(program: RecordedProgram): String? = buildList {
    if (program.isRecording || program.recordedVideo.status.equals("Recording", ignoreCase = true)) {
        add("録画中")
    }
    if (program.isPartiallyRecorded) add("部分録画")
}.joinToString(" ・ ").ifEmpty { null }
