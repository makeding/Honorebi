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
    NEXT,
}

data class WatchNextNextInput(
    val sourceWatchedAtMillis: Long,
    val program: RecordedProgram,
)

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
    const val MAX_NEXT_ITEMS = 3
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
        nextEpisodes: List<WatchNextNextInput> = emptyList(),
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
        val maxNextAllowed = minOf(MAX_NEXT_ITEMS, MAX_TOTAL_ITEMS - continueCandidates.size).coerceAtLeast(0)
        val nextCandidates = nextEpisodes
            .sortedWith(
                compareByDescending<WatchNextNextInput> { it.sourceWatchedAtMillis }
                    .thenByDescending { it.program.id }
            )
            .filter { input ->
                val program = input.program
                isEligibleUnwatchedCandidate(
                    program = program,
                    occupiedIds = continueIds,
                    historyById = historyById,
                    exclusionActive = exclusionActive,
                    isExcluded = isExcluded,
                )
            }
            .distinctBy { it.program.id }
            .take(maxNextAllowed)
            .mapIndexed { index, input ->
                val program = input.program
                val durationMillis = WatchProgressPolicy.effectiveDurationSeconds(program)
                    ?.let { (it * 1000.0).toLong() }
                    ?.takeIf { it > 0L }
                WatchNextCandidate(
                    recordedProgramId = program.id,
                    kind = WatchNextKind.NEXT,
                    title = program.title,
                    description = buildDescription(program),
                    durationMillis = durationMillis,
                    positionMillis = null,
                    engagementMillis = input.sourceWatchedAtMillis.coerceAtMost(nowMillis) - index,
                    thumbnailUrls = extractThumbnailUrls(program),
                )
            }

        val occupiedIds = HashSet<Int>(continueIds.size + nextCandidates.size).apply {
            addAll(continueIds)
            nextCandidates.mapTo(this) { it.recordedProgramId }
        }
        val minPriorEngagement = (continueCandidates + nextCandidates).minOfOrNull { it.engagementMillis }
        val cutoffMillis = nowMillis - NEW_WINDOW_MILLIS
        val maxNewAllowed = minOf(
            MAX_NEW_ITEMS,
            MAX_TOTAL_ITEMS - continueCandidates.size - nextCandidates.size,
        ).coerceAtLeast(0)

        val eligibleNew = recordings
            .mapNotNull { program ->
                if (!isEligibleUnwatchedCandidate(
                        program = program,
                        occupiedIds = occupiedIds,
                        historyById = historyById,
                        exclusionActive = exclusionActive,
                        isExcluded = isExcluded,
                    )
                ) {
                    return@mapNotNull null
                }

                val endMillis = parseTimestampMillis(program.endTime) ?: return@mapNotNull null
                if (endMillis < cutoffMillis) return@mapNotNull null

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
            val baseEngagement = if (minPriorEngagement != null) {
                minOf(clampedEnd, minPriorEngagement - CONTINUE_NEW_GAP_MILLIS)
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

        return (continueCandidates + nextCandidates + newCandidates).take(MAX_TOTAL_ITEMS)
    }

    private fun isEligibleUnwatchedCandidate(
        program: RecordedProgram,
        occupiedIds: Set<Int>,
        historyById: Map<Int, WatchNextHistoryInput>,
        exclusionActive: Boolean,
        isExcluded: (RecordedProgram) -> Boolean,
    ): Boolean {
        if (program.id <= 0 || program.id in occupiedIds) return false
        if (program.isRecording || program.recordedVideo.status.equals("Recording", ignoreCase = true)) {
            return false
        }
        if (exclusionActive && program.channel == null) return false
        if (isExcluded(program)) return false
        if (program.title.isBlank()) return false

        val programDuration = WatchProgressPolicy.effectiveDurationSeconds(program)
        val programCompleted = WatchProgressPolicy.isCompleted(program.playbackPosition, programDuration)
        if (!program.playbackPosition.isFinite() ||
            program.playbackPosition > WatchProgressPolicy.MIN_PROGRESS_SECONDS ||
            programCompleted
        ) {
            return false
        }

        val historyEntry = historyById[program.id]
        if (historyEntry != null) {
            val historyDuration = historyEntry.durationSeconds.takeIf { it.isFinite() && it > 0.0 }
                ?: programDuration
            val historyCompleted = WatchProgressPolicy.isCompleted(historyEntry.positionSeconds, historyDuration)
            if (!historyEntry.positionSeconds.isFinite() ||
                historyEntry.positionSeconds > WatchProgressPolicy.MIN_PROGRESS_SECONDS ||
                historyCompleted
            ) {
                return false
            }
        }
        return true
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
