package com.beeregg2001.komorebi.data.previewchannels

import com.beeregg2001.komorebi.data.model.Channel
import com.beeregg2001.komorebi.data.model.Program
import com.beeregg2001.komorebi.data.model.RecordedProgram
import com.beeregg2001.komorebi.data.watchnext.PreviewArtworkKind
import com.beeregg2001.komorebi.data.watchnext.WatchNextPlanner
import com.beeregg2001.komorebi.data.watchnext.WatchProgressPolicy
import com.beeregg2001.komorebi.navigation.KomorebiDeepLink
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

enum class PreviewChannelKind(
    val internalProviderId: String,
    val displayName: String,
    val description: String,
) {
    LIVE(
        internalProviderId = "komorebi:preview:live",
        displayName = "放送中の番組（ライブ）",
        description = "現在放送中のテレビ番組をすぐに視聴できます",
    ),
    RECORDINGS(
        internalProviderId = "komorebi:preview:recordings",
        displayName = "新着録画",
        description = "最近録画された番組をホーム画面から直接再生します",
    ),
}

data class PreviewProgramCandidate(
    val internalId: String,
    val kind: PreviewChannelKind,
    val title: String,
    val description: String?,
    val startMillis: Long?,
    val endMillis: Long?,
    val durationMillis: Long?,
    val deepLink: String,
    val artworkKind: PreviewArtworkKind,
    val artworkKey: String,
    val artworkUrls: List<String>,
    val weight: Int,
    val sourceChannel: Channel? = null,
)

object PreviewChannelPlanner {
    const val MAX_LIVE_ITEMS = 12
    const val MAX_RECORDING_ITEMS = 12
    const val LIVE_INTERNAL_ID_PREFIX = "live:"
    const val RECORDING_INTERNAL_ID_PREFIX = "rec:"
    const val MIN_REFRESH_DELAY_MILLIS = 30_000L
    const val MAX_REFRESH_DELAY_MILLIS = 15L * 60L * 1000L
    const val BOUNDARY_BUFFER_MILLIS = 5_000L

    private val JST_ZONE: ZoneId = ZoneId.of("Asia/Tokyo")
    private val LIVE_TIME_FORMATTER: DateTimeFormatter =
        DateTimeFormatter.ofPattern("HH:mm", Locale.JAPAN)
    private val RECORDING_DATE_FORMATTER: DateTimeFormatter =
        DateTimeFormatter.ofPattern("M/d(E) HH:mm", Locale.JAPAN)
    private val HEX_CHARS = "0123456789abcdef".toCharArray()

    fun planLive(
        channels: List<Channel>,
        nowMillis: Long,
        isExcluded: (Channel) -> Boolean,
    ): List<PreviewProgramCandidate> {
        val seenDisplayIds = HashSet<String>()
        val selected = mutableListOf<LiveSelection>()

        for (channel in channels) {
            if (selected.size >= MAX_LIVE_ITEMS) break
            if (channel.id.startsWith("tif:")) continue
            if (!channel.isDisplay) continue
            if (channel.is_subchannel) continue

            val displayChannelId = channel.displayChannelId.trim()
            if (displayChannelId.isBlank()) continue
            if (isExcluded(channel)) continue

            val active = selectActiveProgram(channel, nowMillis) ?: continue
            val title = active.program.title.trim()
            if (title.isBlank()) continue

            val deepLink = runCatching { KomorebiDeepLink.liveUri(displayChannelId) }.getOrNull()
                ?: continue

            if (!seenDisplayIds.add(displayChannelId)) continue

            selected += LiveSelection(
                channel = channel,
                displayChannelId = displayChannelId,
                title = title,
                startMillis = active.startMillis,
                endMillis = active.endMillis,
                deepLink = deepLink,
            )
        }

        return selected.mapIndexed { index, item ->
            PreviewProgramCandidate(
                internalId = "$LIVE_INTERNAL_ID_PREFIX${item.displayChannelId}",
                kind = PreviewChannelKind.LIVE,
                title = item.title,
                description = buildLiveDescription(
                    channelName = item.channel.name,
                    startMillis = item.startMillis,
                    endMillis = item.endMillis,
                ),
                startMillis = item.startMillis,
                endMillis = item.endMillis,
                durationMillis = (item.endMillis - item.startMillis).takeIf { it > 0L },
                deepLink = item.deepLink,
                artworkKind = PreviewArtworkKind.LIVE,
                artworkKey = liveArtworkKey(item.displayChannelId),
                artworkUrls = emptyList(),
                weight = MAX_LIVE_ITEMS - index,
                sourceChannel = item.channel,
            )
        }
    }

    fun planRecordings(
        recordings: List<RecordedProgram>,
        nowMillis: Long,
        exclusionActive: Boolean,
        isExcluded: (RecordedProgram) -> Boolean,
    ): List<PreviewProgramCandidate> {
        val eligible = recordings
            .mapNotNull { program ->
                if (program.id <= 0) return@mapNotNull null
                if (program.isRecording || program.recordedVideo.status.equals("Recording", ignoreCase = true)) {
                    return@mapNotNull null
                }
                if (exclusionActive && program.channel == null) return@mapNotNull null
                if (isExcluded(program)) return@mapNotNull null
                if (program.title.isBlank()) return@mapNotNull null

                val startMillis = WatchNextPlanner.parseTimestampMillis(program.startTime)
                val endMillis = WatchNextPlanner.parseTimestampMillis(program.endTime)
                val sortEndMillis = endMillis ?: startMillis ?: Long.MIN_VALUE

                RecordingSelection(
                    program = program,
                    startMillis = startMillis,
                    endMillis = endMillis,
                    sortEndMillis = sortEndMillis,
                )
            }
            .sortedWith(
                compareByDescending<RecordingSelection> { it.sortEndMillis }
                    .thenByDescending { it.program.id }
            )
            .distinctBy { it.program.id }
            .take(MAX_RECORDING_ITEMS)

        return eligible.mapIndexed { index, item ->
            val program = item.program
            val durationMillis = WatchProgressPolicy.effectiveDurationSeconds(program)
                ?.let { (it * 1000.0).toLong() }
                ?.takeIf { it > 0L }

            PreviewProgramCandidate(
                internalId = "$RECORDING_INTERNAL_ID_PREFIX${program.id}",
                kind = PreviewChannelKind.RECORDINGS,
                title = program.title,
                description = buildRecordingDescription(program, item.startMillis),
                startMillis = item.startMillis,
                endMillis = item.endMillis,
                durationMillis = durationMillis,
                deepLink = KomorebiDeepLink.recordingUri(program.id),
                artworkKind = PreviewArtworkKind.RECORDING,
                artworkKey = program.id.toString(),
                artworkUrls = extractThumbnailUrls(program),
                weight = MAX_RECORDING_ITEMS - index,
                sourceChannel = null,
            )
        }
    }

    fun liveArtworkKey(displayChannelId: String): String {
        val digest = MessageDigest.getInstance("SHA-1")
            .digest(displayChannelId.toByteArray(StandardCharsets.UTF_8))
        val out = CharArray(digest.size * 2)
        for (i in digest.indices) {
            val v = digest[i].toInt() and 0xFF
            out[i * 2] = HEX_CHARS[v ushr 4]
            out[i * 2 + 1] = HEX_CHARS[v and 0x0F]
        }
        return String(out)
    }

    fun nextRefreshDelayMillis(
        live: List<PreviewProgramCandidate>,
        nowMillis: Long,
    ): Long {
        val minEndMillis = live.mapNotNull { it.endMillis }.minOrNull()
            ?: return MAX_REFRESH_DELAY_MILLIS
        return (minEndMillis - nowMillis + BOUNDARY_BUFFER_MILLIS)
            .coerceIn(MIN_REFRESH_DELAY_MILLIS, MAX_REFRESH_DELAY_MILLIS)
    }

    private fun selectActiveProgram(channel: Channel, nowMillis: Long): ActiveProgram? {
        activeProgramIfCurrent(channel.programPresent, nowMillis)?.let { return it }
        return activeProgramIfCurrent(channel.programFollowing, nowMillis)
    }

    private fun activeProgramIfCurrent(program: Program?, nowMillis: Long): ActiveProgram? {
        if (program == null) return null
        val startMillis = WatchNextPlanner.parseTimestampMillis(program.startTime) ?: return null
        val endMillis = WatchNextPlanner.parseTimestampMillis(program.endTime)
            ?: program.duration.takeIf { it > 0 }?.let { startMillis + it * 1000L }
            ?: return null
        if (endMillis <= startMillis) return null
        if (nowMillis !in startMillis until endMillis) return null
        return ActiveProgram(program, startMillis, endMillis)
    }

    private fun buildLiveDescription(
        channelName: String,
        startMillis: Long,
        endMillis: Long,
    ): String {
        val startText = Instant.ofEpochMilli(startMillis).atZone(JST_ZONE).format(LIVE_TIME_FORMATTER)
        val endText = Instant.ofEpochMilli(endMillis).atZone(JST_ZONE).format(LIVE_TIME_FORMATTER)
        val timeRange = "${startText}〜${endText}"
        val cleanName = channelName.trim()
        return if (cleanName.isNotEmpty()) "$cleanName · $timeRange" else timeRange
    }

    private fun buildRecordingDescription(
        program: RecordedProgram,
        startMillis: Long?,
    ): String? {
        val channelName = program.channel?.name?.trim()?.takeIf { it.isNotEmpty() }
        val formattedStart = startMillis?.let { millis ->
            Instant.ofEpochMilli(millis)
                .atZone(JST_ZONE)
                .format(RECORDING_DATE_FORMATTER)
        }
        return listOfNotNull(channelName, formattedStart)
            .joinToString(" · ")
            .takeIf { it.isNotEmpty() }
    }

    private fun extractThumbnailUrls(program: RecordedProgram): List<String> =
        listOfNotNull(program.directThumbnailUrl, program.apiThumbnailUrl)
            .map { it.trim() }
            .filter {
                it.startsWith("http://", ignoreCase = true) ||
                    it.startsWith("https://", ignoreCase = true)
            }
            .distinct()

    private data class ActiveProgram(
        val program: Program,
        val startMillis: Long,
        val endMillis: Long,
    )

    private data class LiveSelection(
        val channel: Channel,
        val displayChannelId: String,
        val title: String,
        val startMillis: Long,
        val endMillis: Long,
        val deepLink: String,
    )

    private data class RecordingSelection(
        val program: RecordedProgram,
        val startMillis: Long?,
        val endMillis: Long?,
        val sortEndMillis: Long,
    )
}
