package com.beeregg2001.komorebi.data.watchnext

import com.beeregg2001.komorebi.data.model.RecordedProgram
import com.beeregg2001.komorebi.ui.video.player.policy.normalizeQuickSeriesKey
import com.beeregg2001.komorebi.ui.video.player.policy.parsePrimaryEpisodeNumber
import com.beeregg2001.komorebi.ui.video.player.policy.selectPreferredEpisodeRecording
import com.beeregg2001.komorebi.util.TitleNormalizer

object NextEpisodeResolver {

    fun seriesDisplayTitle(program: RecordedProgram): String =
        program.seriesName?.trim().orEmpty().ifBlank {
            if (program.title.isBlank()) "" else TitleNormalizer.extractDisplayTitle(program.title)
        }

    fun seriesKey(program: RecordedProgram): String =
        program.seriesId?.let { "series:$it" } ?: normalizeQuickSeriesKey(seriesDisplayTitle(program))

    fun isSeriesEpisode(program: RecordedProgram): Boolean =
        program.seriesId != null ||
            parsePrimaryEpisodeNumber(program.episodeNumber)?.isFinite() == true ||
            program.isEpisodic == true

    fun isSameSeries(a: RecordedProgram, b: RecordedProgram): Boolean {
        if (a.seriesId != null && b.seriesId != null) {
            return a.seriesId == b.seriesId
        }
        val keyA = normalizeQuickSeriesKey(seriesDisplayTitle(a))
        val keyB = normalizeQuickSeriesKey(seriesDisplayTitle(b))
        return keyA.isNotBlank() && keyA == keyB
    }

    fun resolveNext(
        source: RecordedProgram,
        candidates: List<RecordedProgram>,
        history: Map<Int, WatchNextHistoryInput>,
        exclusionActive: Boolean,
        isExcluded: (RecordedProgram) -> Boolean,
    ): RecordedProgram? {
        val pool = candidates
            .distinctBy { it.id }
            .filter { candidate ->
                candidate.id > 0 &&
                    candidate.id != source.id &&
                    !candidate.isRecording &&
                    !candidate.recordedVideo.status.equals("Recording", ignoreCase = true) &&
                    !(exclusionActive && candidate.channel == null) &&
                    !isExcluded(candidate) &&
                    candidate.title.isNotBlank() &&
                    isSameSeries(source, candidate)
            }

        if (pool.isEmpty()) return null

        val sourceEpisode = parsePrimaryEpisodeNumber(source.episodeNumber)?.takeIf { it.isFinite() }
        val ordered = if (sourceEpisode != null) {
            pool
                .mapNotNull { candidate ->
                    val episode = parsePrimaryEpisodeNumber(candidate.episodeNumber)
                        ?.takeIf { it.isFinite() && it > sourceEpisode }
                        ?: return@mapNotNull null
                    episode to candidate
                }
                .groupBy({ it.first }, { it.second })
                .toSortedMap()
                .values
                .mapNotNull { group ->
                    selectPreferredEpisodeRecording(group, source.channel?.id)
                }
        } else {
            val sourceStartMillis = WatchNextPlanner.parseTimestampMillis(source.startTime) ?: return null
            pool
                .mapNotNull { candidate ->
                    val startMillis = WatchNextPlanner.parseTimestampMillis(candidate.startTime)
                        ?.takeIf { it > sourceStartMillis }
                        ?: return@mapNotNull null
                    startMillis to candidate
                }
                .sortedWith(
                    compareBy<Pair<Long, RecordedProgram>> { it.first }
                        .thenBy { it.second.id }
                )
                .map { it.second }
        }

        for (candidate in ordered) {
            val historyEntry = history[candidate.id]
            val historyPos = historyEntry?.positionSeconds?.takeIf { it.isFinite() }
            val programPos = candidate.playbackPosition.takeIf { it.isFinite() }
            val watchPosition = when {
                historyPos != null && programPos != null -> maxOf(historyPos, programPos)
                historyPos != null -> historyPos
                programPos != null -> programPos
                else -> return null
            }

            if (watchPosition <= WatchProgressPolicy.MIN_PROGRESS_SECONDS) {
                return candidate
            }

            val effectiveDuration = historyEntry?.durationSeconds?.takeIf { it.isFinite() && it > 0.0 }
                ?: WatchProgressPolicy.effectiveDurationSeconds(candidate)
            if (WatchProgressPolicy.isCompleted(watchPosition, effectiveDuration)) {
                continue
            }

            return null
        }

        return null
    }
}
