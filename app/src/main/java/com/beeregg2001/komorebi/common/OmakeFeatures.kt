package com.beeregg2001.komorebi.common

import androidx.compose.runtime.staticCompositionLocalOf
import com.beeregg2001.komorebi.data.model.Channel
import com.beeregg2001.komorebi.data.model.LiveRowState
import java.time.ZonedDateTime

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
