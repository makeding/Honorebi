package com.beeregg2001.komorebi.ui.video.player

import com.beeregg2001.komorebi.data.model.ArchivedComment
import com.beeregg2001.komorebi.data.model.Channel
import com.beeregg2001.komorebi.data.model.Program
import com.beeregg2001.komorebi.data.model.RecordedProgram
import java.time.OffsetDateTime
import org.json.JSONObject

internal fun RecordedProgram.toDataBroadcastingChannel(): Channel {
    val recordedChannel = channel
    return Channel(
        id = recordedChannel?.id ?: "recorded-$id",
        displayChannelId = recordedChannel?.displayChannelId.orEmpty(),
        name = recordedChannel?.name ?: title,
        channelNumber = recordedChannel?.channelNumber.orEmpty(),
        networkId = recordedChannel?.networkId?.toLong() ?: 0L,
        serviceId = recordedChannel?.serviceId?.toLong() ?: 0L,
        transportStreamId = 0L,
        type = recordedChannel?.type ?: "BS4K",
        isWatchable = true,
        isDisplay = true,
        programPresent = Program(
            id = id.toString(), title = title, description = description, detail = detail,
            startTime = startTime, endTime = endTime,
            duration = recordedVideo.duration.toInt().coerceAtLeast(0), genres = null,
            videoResolution = null
        ),
        programFollowing = null, remocon_Id = 0
    )
}

internal fun ArchivedComment.stableCommentKey(): String =
    "$time:$author:$type:$size:$color:$text"

internal fun appendUniqueArchivedComments(
    target: MutableList<ArchivedComment>, knownKeys: MutableSet<String>, candidates: List<ArchivedComment>
) {
    if (candidates.isEmpty()) return
    val newComments = candidates.asSequence().filter { knownKeys.add(it.stableCommentKey()) }
        .sortedBy { it.time }.toList()
    if (newComments.isEmpty()) return
    if (target.isEmpty() || target.last().time <= newComments.first().time) target.addAll(newComments)
    else {
        val mergedComments = (target + newComments).sortedBy { it.time }
        target.clear()
        target.addAll(mergedComments)
    }
}

internal fun RecordedProgram.chaseElapsedDurationMs(nowMillis: Long = System.currentTimeMillis()): Long =
    runCatching {
        val startMs = OffsetDateTime.parse(startTime).toInstant().toEpochMilli()
        val endMs = OffsetDateTime.parse(endTime).toInstant().toEpochMilli()
        (nowMillis.coerceAtMost(endMs) - startMs).coerceAtLeast(0L)
    }.getOrDefault((duration * 1000.0).toLong().coerceAtLeast(0L))

internal fun RecordedProgram.programStartUnixOrNull(): Long? =
    runCatching { OffsetDateTime.parse(startTime).toEpochSecond() }.getOrNull()

internal fun parseChaseWsArchivedComment(jsonText: String, programStartUnix: Long): ArchivedComment? =
    runCatching {
        val chat = JSONObject(jsonText).optJSONObject("chat") ?: return null
        val content = chat.optString("content", "")
        if (content.isBlank() || chat.optString("deleted") == "1") return null
        if (content.startsWith("/") && content.matches(Regex("^/[a-z][a-z0-9_-]*(?:\\s|$).*")) &&
            chat.optString("premium") == "3") return null
        var color = "#FFEAEA"; var position = "right"; var size = "medium"
        chat.optString("mail", "").replace("184", "").split(" ").forEach { command ->
            getCommentColor(command)?.let { color = it }
            getCommentPosition(command)?.let { position = it }
            getCommentSize(command)?.let { size = it }
        }
        val chatDate = chat.optString("date").toDoubleOrNull() ?: return null
        val chatDateUsec = chat.optString("date_usec", "0").toDoubleOrNull() ?: return null
        val commentTime = (chatDate - programStartUnix) + (chatDateUsec / 1_000_000.0)
        if (commentTime < -5.0) return null
        ArchivedComment(commentTime.coerceAtLeast(0.0), content, color, chat.optString("user_id", ""), position, size)
    }.getOrNull()

private fun getCommentColor(command: String): String? = when (command) {
    "red" -> "#F02840"; "pink" -> "#FF8080"; "orange" -> "#FFC000"; "yellow" -> "#FFFF00"
    "green" -> "#00FF00"; "cyan" -> "#00FFFF"; "blue" -> "#0000FF"; "purple" -> "#C000FF"
    "black" -> "#000000"; "niconicowhite", "white2" -> "#CCCC99"; "truered", "red2" -> "#CC0033"
    "passionorange", "orange2" -> "#FF6600"; "madyellow", "yellow2" -> "#999900"
    "elementalgreen" -> "#00CC66"; "marineblue", "blue2" -> "#33FFFC"; "nobleviolet", "purple2" -> "#6633CC"
    else -> null
}

private fun getCommentPosition(command: String): String? = when (command) {
    "ue" -> "top"; "naka" -> "right"; "shita" -> "bottom"; else -> null
}

private fun getCommentSize(command: String): String? = when (command) {
    "big" -> "big"; "medium" -> "medium"; "small" -> "small"; else -> null
}
