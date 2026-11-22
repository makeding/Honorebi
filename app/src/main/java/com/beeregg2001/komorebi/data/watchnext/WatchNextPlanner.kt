package com.beeregg2001.komorebi.data.watchnext

import com.beeregg2001.komorebi.data.model.RecordedProgram
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

enum class WatchNextKind {
    CONTINUE,
    NEW,
}

data class WatchNextHistoryInput(
    val id: Int,
    val title: String,
    val durationSeconds: Double,
    val positionSeconds: Double,
    val watchedAtMillis: Long,
)

data class WatchNextCandidate(
    val recordedProgramId: Int,
    val kind: WatchNextKind,
    val title: String,
    val description: String?,
    val durationMillis: Long?,
    val positionMillis: Long?,
    val engagementMillis: Long,
    val thumbnailUrls: List<String>,
) {
    val internalId: String
        get() = "${WatchNextPlanner.INTERNAL_ID_PREFIX}$recordedProgramId"
}

object WatchNextPlanner {
    const val INTERNAL_ID_PREFIX = "rec:"
    const val MAX_CONTINUE_ITEMS = 6
    const val MAX_NEW_ITEMS = 4
    const val MAX_TOTAL_ITEMS = 10
    const val NEW_WINDOW_MILLIS = 14L * 24L * 60L * 60L * 1000L
    const val CONTINUE_NEW_GAP_MILLIS = 1_000L

    private val JST_ZONE: ZoneId = ZoneId.of("Asia/Tokyo")
    private val DESCRIPTION_DATE_FORMATTER: DateTimeFormatter =
        DateTimeFormatter.ofPattern("M/d(E) HH:mm", Locale.JAPAN)

    fun plan(
        history: List<WatchNextHistoryInput>,
        recordings: List<RecordedProgram>,
        lookup: Map<Int, RecordedProgram>,
        exclusionActive: Boolean,
        isExcluded: (RecordedProgram) -> Boolean,
        nowMillis: Long,
    ): List<WatchNextCandidate> {
        val sortedHistory = history
            .sortedWith(
                compareByDescending<WatchNextHistoryInput> { it.watchedAtMillis }
                    .thenByDescending { it.id }
            )
            .distinctBy { it.id }
        val historyById = sortedHistory.associateBy { it.id }

        val continueCandidates = mutableListOf<WatchNextCandidate>()
        for (entry in sortedHistory) {
            if (continueCandidates.size >= MAX_CONTINUE_ITEMS) break
            if (entry.id <= 0) continue
            val program = lookup[entry.id]
            val effectiveDuration = entry.durationSeconds.takeIf { it.isFinite() && it > 0.0 }
                ?: program?.let { WatchProgressPolicy.effectiveDurationSeconds(it) }
            if (!WatchProgressPolicy.isInProgress(entry.positionSeconds, effectiveDuration)) {
                continue
            }
            if (exclusionActive && (program == null || program.channel == null || isExcluded(program))) {
                continue
            }
            if (program != null && isExcluded(program)) {
                continue
            }

            val durationMillis = effectiveDuration?.let { (it * 1000.0).toLong() }?.takeIf { it > 0L }
            val positionMillis = if (durationMillis != null) {
                (entry.positionSeconds * 1000.0).toLong().coerceIn(0L, durationMillis)
            } else {
                null
            }
            val title = entry.title.ifBlank { program?.title.orEmpty() }
            if (title.isBlank()) continue

            continueCandidates += WatchNextCandidate(
                recordedProgramId = entry.id,
                kind = WatchNextKind.CONTINUE,
                title = title,
                description = program?.let(::buildDescription),
                durationMillis = durationMillis,
                positionMillis = positionMillis,
                engagementMillis = entry.watchedAtMillis.coerceAtMost(nowMillis),
                thumbnailUrls = program?.let(::extractThumbnailUrls).orEmpty(),
            )
        }

        val continueIds = continueCandidates.mapTo(HashSet()) { it.recordedProgramId }
        val minContinueEngagement = continueCandidates.minOfOrNull { it.engagementMillis }
        val cutoffMillis = nowMillis - NEW_WINDOW_MILLIS
        val maxNewAllowed = minOf(MAX_NEW_ITEMS, MAX_TOTAL_ITEMS - continueCandidates.size).coerceAtLeast(0)

        val eligibleNew = recordings
            .mapNotNull { program ->
                if (program.id <= 0 || program.id in continueIds) return@mapNotNull null
                if (program.isRecording || program.recordedVideo.status.equals("Recording", ignoreCase = true)) {
                    return@mapNotNull null
                }
                if (exclusionActive && program.channel == null) return@mapNotNull null
                if (isExcluded(program)) return@mapNotNull null

                val programDuration = WatchProgressPolicy.effectiveDurationSeconds(program)
                val programCompleted = programDuration != null &&
                    program.playbackPosition >= programDuration * WatchProgressPolicy.COMPLETION_RATIO
                if (!program.playbackPosition.isFinite() ||
                    program.playbackPosition > WatchProgressPolicy.MIN_PROGRESS_SECONDS ||
                    programCompleted
                ) {
                    return@mapNotNull null
                }

                val historyEntry = historyById[program.id]
                if (historyEntry != null) {
                    val historyDuration = historyEntry.durationSeconds.takeIf { it.isFinite() && it > 0.0 }
                        ?: programDuration
                    val historyCompleted = historyDuration != null &&
                        historyEntry.positionSeconds >= historyDuration * WatchProgressPolicy.COMPLETION_RATIO
                    if (!historyEntry.positionSeconds.isFinite() ||
                        historyEntry.positionSeconds > WatchProgressPolicy.MIN_PROGRESS_SECONDS ||
                        historyCompleted
                    ) {
                        return@mapNotNull null
                    }
                }

                val endMillis = parseTimestampMillis(program.endTime) ?: return@mapNotNull null
                if (endMillis < cutoffMillis) return@mapNotNull null
                if (program.title.isBlank()) return@mapNotNull null

                program to endMillis
            }
            .sortedWith(
                compareByDescending<Pair<RecordedProgram, Long>> { it.second }
                    .thenByDescending { it.first.id }
            )
            .distinctBy { it.first.id }
            .take(maxNewAllowed)

        val newCandidates = eligibleNew.mapIndexed { index, (program, endMillis) ->
            val clampedEnd = endMillis.coerceAtMost(nowMillis)
            val baseEngagement = if (minContinueEngagement != null) {
                minOf(clampedEnd, minContinueEngagement - CONTINUE_NEW_GAP_MILLIS)
            } else {
                clampedEnd
            }
            val durationMillis = WatchProgressPolicy.effectiveDurationSeconds(program)
                ?.let { (it * 1000.0).toLong() }
                ?.takeIf { it > 0L }

            WatchNextCandidate(
                recordedProgramId = program.id,
                kind = WatchNextKind.NEW,
                title = program.title,
                description = buildDescription(program),
                durationMillis = durationMillis,
                positionMillis = null,
                engagementMillis = baseEngagement - index,
                thumbnailUrls = extractThumbnailUrls(program),
            )
        }

        return (continueCandidates + newCandidates).take(MAX_TOTAL_ITEMS)
    }

    internal fun parseTimestampMillis(raw: String?): Long? {
        if (raw.isNullOrBlank()) return null
        val trimmed = raw.trim()
        return runCatching { OffsetDateTime.parse(trimmed).toInstant().toEpochMilli() }
            .recoverCatching { Instant.parse(trimmed).toEpochMilli() }
            .getOrNull()
    }

    private fun buildDescription(program: RecordedProgram): String? {
        val channelName = program.channel?.name?.trim()?.takeIf { it.isNotEmpty() }
        val formattedStart = parseTimestampMillis(program.startTime)?.let { millis ->
            Instant.ofEpochMilli(millis)
                .atZone(JST_ZONE)
                .format(DESCRIPTION_DATE_FORMATTER)
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
}
