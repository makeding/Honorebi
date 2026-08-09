package com.beeregg2001.komorebi.ui.video.player.policy

import com.beeregg2001.komorebi.data.model.ArchivedComment
import com.beeregg2001.komorebi.data.model.RecordedChannel
import com.beeregg2001.komorebi.data.model.RecordedProgram
import java.text.Normalizer
import kotlin.math.ceil
import kotlin.math.roundToInt

const val NEXT_EPISODE_COUNTDOWN_WINDOW_MS = 15_000L

private const val ATX_NEXT_EPISODE_TRIGGER_MS = 26 * 60 * 1000L
private const val THIRTY_MINUTE_RECORDING_MIN_MS = 27 * 60 * 1000L
private const val THIRTY_MINUTE_RECORDING_MAX_MS = 36 * 60 * 1000L
private const val COMMENT_CLIMAX_WINDOW_START_MS = 25 * 60 * 1000L
private const val COMMENT_CLIMAX_WINDOW_END_MS = 30 * 60 * 1000L
private const val COMMENT_DENSITY_BUCKET_MS = 10_000L
private const val COMMENT_CLIMAX_LEAD_MS = 10_000L
private const val MIN_PROGRAM_COMMENTS_FOR_CLIMAX = 80
private const val MIN_CLIMAX_COMMENTS = 20
private const val MIN_DENSE_BUCKET_COMMENTS = 5
private const val MIN_CLIMAX_WINDOW_COMMENT_RATIO = 0.08f
private const val MIN_CLIMAX_PEAK_TO_BASELINE_RATIO = 2.0f
private const val MIN_C_PART_SIGNAL_COMMENTS = 3
private const val C_PART_SIGNAL_CLUSTER_WINDOW_MS = 30_000L
private const val LATE_C_PART_WINDOW_START_MS = 28 * 60 * 1000L + 30_000L
private const val MIN_LATE_C_PART_COMMENTS = 8
private const val MIN_LATE_C_PART_PEAK_RATIO = 1.6f

fun normalizeQuickSeriesKey(value: String): String =
    value
        .trim()
        .replace(Regex("[\\s　]+"), "")
        .lowercase()

fun isNextEpisodeLandingEligible(program: RecordedProgram): Boolean =
    program.genres.orEmpty().any { genre ->
        val label = "${genre.major} ${genre.middle}".lowercase()
        label.contains("アニメ") ||
                label.contains("anime") ||
                label.contains("特撮") ||
                label.contains("tokusatsu") ||
                label.contains("ドラマ") ||
                label.contains("drama")
    }

fun calculateNextEpisodeCountdownStartMs(
    program: RecordedProgram,
    comments: List<ArchivedComment>,
    totalDurationMs: Long
): Long {
    val fallbackStartMs =
        (totalDurationMs - NEXT_EPISODE_COUNTDOWN_WINDOW_MS).coerceAtLeast(0L)
    if (totalDurationMs <= NEXT_EPISODE_COUNTDOWN_WINDOW_MS) return fallbackStartMs

    if (hasCPartSignal(comments, totalDurationMs)) return fallbackStartMs

    if (
        isAtxChannel(program.channel) &&
        isApproximatelyThirtyMinuteRecording(program, totalDurationMs) &&
        ATX_NEXT_EPISODE_TRIGGER_MS < totalDurationMs
    ) return ATX_NEXT_EPISODE_TRIGGER_MS

    return calculateCommentClimaxCountdownStartMs(comments, totalDurationMs)
        ?.takeIf { it < totalDurationMs }
        ?.coerceIn(0L, totalDurationMs)
        ?: fallbackStartMs
}

fun isAtxChannel(channel: RecordedChannel?): Boolean {
    if (channel == null) return false
    val textSignals = listOf(channel.id, channel.displayChannelId, channel.name, channel.channelNumber)
    return channel.serviceId == 333 || textSignals.any { value ->
        value.contains("AT-X", ignoreCase = true) ||
                value.contains("CS333", ignoreCase = true) ||
                value == "333" ||
                value.endsWith("333")
    }
}

fun isApproximatelyThirtyMinuteRecording(program: RecordedProgram, totalDurationMs: Long): Boolean =
    listOf(
        totalDurationMs,
        (program.duration * 1000.0).toLong(),
        (program.recordedVideo.duration * 1000.0).toLong()
    ).any { it in THIRTY_MINUTE_RECORDING_MIN_MS..THIRTY_MINUTE_RECORDING_MAX_MS }

fun hasCPartSignal(comments: List<ArchivedComment>, totalDurationMs: Long): Boolean {
    val windowStartMs = COMMENT_CLIMAX_WINDOW_START_MS
    val windowEndMs = minOf(COMMENT_CLIMAX_WINDOW_END_MS, totalDurationMs)
    if (windowEndMs <= windowStartMs) return false

    val cPartTimes = comments.asSequence().filter { isCPartComment(it.text) }
        .map { (it.time * 1000.0).toLong() }.filter { it in windowStartMs until windowEndMs }
        .sorted().toList()
    if (hasCluster(cPartTimes, MIN_C_PART_SIGNAL_COMMENTS, C_PART_SIGNAL_CLUSTER_WINDOW_MS)) return true

    val lateWindowStartMs = maxOf(LATE_C_PART_WINDOW_START_MS, windowStartMs)
    if (windowEndMs <= lateWindowStartMs) return false
    val lateComments = comments.asSequence().map { (it.time * 1000.0).toLong() }
        .filter { it in lateWindowStartMs until windowEndMs }.toList()
    if (lateComments.size < MIN_LATE_C_PART_COMMENTS) return false

    val lateBucketCount = ceil((windowEndMs - lateWindowStartMs).toDouble() / COMMENT_DENSITY_BUCKET_MS)
        .toInt().coerceAtLeast(1)
    val buckets = IntArray(lateBucketCount)
    lateComments.forEach { commentMs ->
        val index = ((commentMs - lateWindowStartMs) / COMMENT_DENSITY_BUCKET_MS).toInt()
            .coerceIn(0, lateBucketCount - 1)
        buckets[index] += 1
    }
    val peak = buckets.maxOrNull() ?: return false
    if (peak < MIN_LATE_C_PART_COMMENTS) return false
    val baseline = buckets.filter { it > 0 }.average().takeIf { !it.isNaN() } ?: return false
    return peak >= (baseline * MIN_LATE_C_PART_PEAK_RATIO).roundToInt()
}

private fun hasCluster(sortedTimesMs: List<Long>, minCount: Int, clusterWindowMs: Long): Boolean {
    if (sortedTimesMs.size < minCount) return false
    var startIndex = 0
    for (endIndex in sortedTimesMs.indices) {
        while (sortedTimesMs[endIndex] - sortedTimesMs[startIndex] > clusterWindowMs) startIndex += 1
        if (endIndex - startIndex + 1 >= minCount) return true
    }
    return false
}

fun isCPartComment(text: String): Boolean {
    val normalized = Normalizer.normalize(text.trim(), Normalizer.Form.NFKC).lowercase()
        .replace(Regex("[\\s　]+"), "")
    if (normalized.isBlank()) return false
    val stripped = normalized.trim { char ->
        char == '!' || char == '?' || char == '！' || char == '？' || char == '。' || char == '.' ||
                char == ',' || char == '、' || char == '-' || char == '_' || char == '~' || char == '〜'
    }
    if (stripped == "c") return true
    return stripped.contains("cpart") || stripped.contains("partc") || stripped.contains("cパート") ||
            stripped.contains("パートc") || stripped.contains("cぱーと") || stripped.contains("ぱーとc")
}

fun calculateCommentClimaxCountdownStartMs(comments: List<ArchivedComment>, totalDurationMs: Long): Long? {
    val windowStartMs = COMMENT_CLIMAX_WINDOW_START_MS
    val windowEndMs = minOf(COMMENT_CLIMAX_WINDOW_END_MS, totalDurationMs)
    if (windowEndMs <= windowStartMs) return null
    val totalCommentCount = comments.count { (it.time * 1000.0).toLong() in 0 until totalDurationMs }
    if (totalCommentCount < MIN_PROGRAM_COMMENTS_FOR_CLIMAX) return null
    val commentsInWindow = comments.asSequence().map { (it.time * 1000.0).toLong() }
        .filter { it in windowStartMs until windowEndMs }.toList()
    val minWindowComments = maxOf(MIN_CLIMAX_COMMENTS, (totalCommentCount * MIN_CLIMAX_WINDOW_COMMENT_RATIO).roundToInt())
    if (commentsInWindow.size < minWindowComments) return null

    val bucketCount = ceil((windowEndMs - windowStartMs).toDouble() / COMMENT_DENSITY_BUCKET_MS)
        .toInt().coerceAtLeast(1)
    val buckets = IntArray(bucketCount)
    commentsInWindow.forEach { commentMs ->
        val index = ((commentMs - windowStartMs) / COMMENT_DENSITY_BUCKET_MS).toInt().coerceIn(0, bucketCount - 1)
        buckets[index] += 1
    }
    val maxBucket = buckets.maxOrNull() ?: return null
    if (maxBucket < MIN_DENSE_BUCKET_COMMENTS) return null
    val nonEmptyBuckets = buckets.filter { it > 0 }.sorted()
    val averageNonEmptyBucket = nonEmptyBuckets.average().takeIf { !it.isNaN() } ?: 0.0
    val medianNonEmptyBucket = nonEmptyBuckets.takeIf { it.isNotEmpty() }?.let { it[it.size / 2].toDouble() } ?: 0.0
    val relativeBaseline = maxOf(averageNonEmptyBucket, medianNonEmptyBucket)
    if (relativeBaseline > 0.0 && maxBucket < (relativeBaseline * MIN_CLIMAX_PEAK_TO_BASELINE_RATIO).roundToInt()) return null
    val denseThreshold = maxOf(MIN_DENSE_BUCKET_COMMENTS, (maxBucket * 0.45).roundToInt(), (relativeBaseline * 1.5).roundToInt())
    val sparseThreshold = maxOf(1, (maxBucket * 0.25).roundToInt(), (relativeBaseline * 0.7).roundToInt())
    val peakIndex = buckets.indices.filter { buckets[it] >= denseThreshold }
        .maxWithOrNull(compareBy<Int> { buckets[it] }.thenBy { it }) ?: return null
    var regionEnd = peakIndex
    while (regionEnd + 1 < buckets.size) {
        if (buckets[regionEnd + 1] < sparseThreshold) break
        regionEnd += 1
    }
    val climaxRegionEndMs = minOf(windowStartMs + ((regionEnd + 1) * COMMENT_DENSITY_BUCKET_MS), windowEndMs)
    return (climaxRegionEndMs - COMMENT_CLIMAX_LEAD_MS).coerceAtLeast(0L)
}
