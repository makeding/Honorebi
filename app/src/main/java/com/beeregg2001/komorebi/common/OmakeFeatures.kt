package com.beeregg2001.komorebi.common

import androidx.compose.runtime.staticCompositionLocalOf
import com.beeregg2001.komorebi.data.model.Channel
import com.beeregg2001.komorebi.data.model.LiveRowState
import com.beeregg2001.komorebi.data.model.RecordedProgram
import java.time.OffsetDateTime
import java.time.ZonedDateTime
import kotlin.random.Random

val LocalLateNightWatchEnabled = staticCompositionLocalOf { false }
val LocalSpoilerBlurEnabled = staticCompositionLocalOf { false }

data class JikkyoFestivalAlert(
    val channel: Channel,
    val force: Int,
) {
    val bannerText: String
        get() = "【速報】${channel.name} で実況お祭り発生中（勢い $force/分）"
}

/**
 * 深夜2時〜4時台の視聴時に、時計横へ控えめな夜更かしツッコミを返す。
 */
fun lateNightWatchMessage(now: ZonedDateTime): String? = when (now.hour) {
    2 -> "深夜2時…あと1話だけ？"
    3 -> "深夜3時です。明日の自分、大丈夫？"
    4 -> "まもなく朝です…そろそろ寝よう"
    else -> null
}

private val QUOTED_SUBTITLE_REGEX = Regex("「[^」]{2,}」")
private val EPISODE_TRAILER_REGEX = Regex("""((?:第\s*[0-9０-９一二三四五六七八九十百]+\s*話|#[0-9０-９]+|EP\.?\s*[0-9０-９]+))\s+(.+)$""", RegexOption.IGNORE_CASE)

/**
 * 未視聴の録画タイトルからネタバレになりやすいサブタイトル部分を伏せ字にする。
 */
fun maskSpoilerTitle(title: String, enabled: Boolean, isWatched: Boolean): String {
    if (!enabled || isWatched || title.isBlank()) return title
    var masked = title
    var changed = false

    if (QUOTED_SUBTITLE_REGEX.containsMatchIn(masked)) {
        masked = QUOTED_SUBTITLE_REGEX.replace(masked, "「██████」")
        changed = true
    }

    if (!changed) {
        val epMatch = EPISODE_TRAILER_REGEX.find(masked)
        if (epMatch != null) {
            masked = masked.substring(0, epMatch.range.first) +
                "${epMatch.groupValues[1]}（ネタバレ防止中）"
        }
    }
    return masked
}

/**
 * CM緊急退避用：現在のチャンネル以外で最も実況勢いが高い視聴可能チャンネルを選ぶ。
 */
fun pickCmEscapeChannel(
    channels: List<Channel>,
    currentChannelId: String,
): Channel? = channels
    .asSequence()
    .filter { it.id != currentChannelId && !it.is_subchannel && it.isWatchable }
    .filter { (it.jikkyoForce ?: 0) > 0 }
    .maxByOrNull { it.jikkyoForce ?: 0 }

/**
 * 実況勢いがしきい値を超えたチャンネルを検出する（同一チャンネルはクールダウン中は再通知しない）。
 */
fun detectJikkyoFestival(
    rows: List<LiveRowState>,
    currentChannelId: String?,
    alertedAtMillis: Map<String, Long>,
    nowMillis: Long,
    threshold: Int = 500,
    cooldownMillis: Long = 10 * 60_000L,
): JikkyoFestivalAlert? {
    val hottest = rows.asSequence()
        .flatMap { it.channels.asSequence() }
        .filter { !it.channel.is_subchannel && it.channel.id != currentChannelId }
        .filter { (it.jikkyoForce ?: 0) >= threshold }
        .filter { state ->
            val last = alertedAtMillis[state.channel.id]
            last == null || (nowMillis - last) >= cooldownMillis
        }
        .maxByOrNull { it.jikkyoForce ?: 0 }
        ?: return null
    return JikkyoFestivalAlert(
        channel = hottest.channel,
        force = hottest.jikkyoForce ?: return null,
    )
}

data class UnwatchedBacklogSummary(
    val count: Int,
    val totalSeconds: Long,
) {
    val label: String
        get() {
            val totalMinutes = (totalSeconds / 60L).coerceAtLeast(0L)
            val hours = totalMinutes / 60L
            val mins = totalMinutes % 60L
            val durationText = when {
                hours > 0 && mins > 0 -> "${hours}時間${mins}分"
                hours > 0 -> "${hours}時間"
                else -> "${mins}分"
            }
            return "未視聴 ${count}本 · 合計 $durationText"
        }
}

/**
 * 未視聴録画の本数と合計再生時間を集計する（積み録画プレッシャー表示用）。
 */
fun summarizeUnwatchedBacklog(
    programs: List<RecordedProgram>,
    watchedProgramIds: Set<Int> = emptySet(),
): UnwatchedBacklogSummary? {
    var count = 0
    var totalSeconds = 0L
    for (p in programs) {
        if (p.playbackPosition > 5.0 || p.id in watchedProgramIds) continue
        count++
        val dur = if (p.duration > 0.0) p.duration else p.recordedVideo.duration
        if (dur > 0.0) totalSeconds += dur.toLong()
    }
    if (count == 0) return null
    return UnwatchedBacklogSummary(count = count, totalSeconds = totalSeconds)
}

/**
 * 今期・前期（直近2クール）の放送開始境界（JST等の現在オフセット基準）を返す。
 */
fun recentTwoSeasonsCutoff(now: OffsetDateTime = OffsetDateTime.now()): OffsetDateTime {
    val currentQuarterStartMonth = ((now.monthValue - 1) / 3) * 3 + 1
    val currentQuarterStart = now
        .withMonth(currentQuarterStartMonth)
        .withDayOfMonth(1)
        .withHour(0)
        .withMinute(0)
        .withSecond(0)
        .withNano(0)
    return currentQuarterStart.minusMonths(3)
}

/**
 * 今期・前期のシリーズからランダムに1作品を選び、次に観るべきエピソード（未視聴の最古話、なければ最新話）を返す。
 */
fun pickRandomRecentSeasonSeriesProgram(
    groupedSeries: Map<String, List<RecordedProgram>>,
    now: OffsetDateTime = OffsetDateTime.now(),
    random: Random = Random.Default,
): Pair<String, RecordedProgram>? {
    val cutoff = recentTwoSeasonsCutoff(now)
    val eligible = groupedSeries.entries.mapNotNull { (seriesTitle, episodes) ->
        val playable = episodes.filter { ep ->
            ep.isRecording || ep.recordedVideo.status == "Recording" || (ep.recordedVideo.hasKeyFrames ?: true)
        }
        if (playable.isEmpty()) return@mapNotNull null
        val hasRecentEpisode = playable.any { ep ->
            val start = runCatching { OffsetDateTime.parse(ep.startTime) }.getOrNull()
            start != null && !start.isBefore(cutoff)
        }
        if (!hasRecentEpisode) return@mapNotNull null
        seriesTitle to playable
    }
    if (eligible.isEmpty()) return null
    val (seriesTitle, episodes) = eligible[random.nextInt(eligible.size)]
    val picked = episodes
        .filter { it.playbackPosition <= 5.0 }
        .minByOrNull { it.startTime }
        ?: episodes.maxByOrNull { it.startTime }
        ?: return null
    return seriesTitle to picked
}

private val KUSA_WORD_REGEX = Regex("(草|[wｗ]{2,}|ﾜﾛﾀ|ワロタ)", RegexOption.IGNORE_CASE)
private val KITA_WORD_REGEX = Regex("(ｷﾀ|キタ|きたー|来たー)")

/**
 * 実況コメント中の定番ワード（「草」「ｷﾀ━」）をカウントする。
 */
class JikkyoWordCounter {
    var kusaCount: Int = 0
        private set
    var kitaCount: Int = 0
        private set

    fun record(text: String) {
        if (text.isBlank()) return
        if (KUSA_WORD_REGEX.containsMatchIn(text)) kusaCount++
        if (KITA_WORD_REGEX.containsMatchIn(text)) kitaCount++
    }

    fun reset() {
        kusaCount = 0
        kitaCount = 0
    }

    fun formatSummary(): String? {
        val parts = buildList {
            if (kusaCount > 0) add("草 ${kusaCount}回")
            if (kitaCount > 0) add("ｷﾀ━ ${kitaCount}回")
        }
        return if (parts.isEmpty()) null else "実況戦績: ${parts.joinToString(" · ")}"
    }
}
