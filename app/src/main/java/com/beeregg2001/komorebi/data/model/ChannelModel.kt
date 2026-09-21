package com.beeregg2001.komorebi.data.model

import com.google.gson.annotations.SerializedName

// ルートオブジェクト
data class ChannelApiResponse(
    @SerializedName("GR") val terrestrial: List<Channel>? = null,
    @SerializedName("BS") val bs: List<Channel>? = null,
    @SerializedName("CS") val cs: List<Channel>? = null,
    @SerializedName("SKY") val sky: List<Channel>? = null,
    @SerializedName("BS4K") val bs4k: List<Channel>? = null,
    @SerializedName("IPTV") val iptv: List<Channel>? = null,
    // Kept in memory only.  /api/channels is a channel-array object; source
    // errors travel in X-Channel-Source-Errors so older strict clients keep parsing it.
    val sourceErrors: Map<String, String?> = emptyMap()
)

data class ChannelCapabilities(
    @SerializedName("live_stream") val liveStream: Boolean = false,
    @SerializedName("live_stream_session") val liveStreamSession: Boolean = false,
    @SerializedName("data_broadcasting") val dataBroadcasting: Boolean = false,
    val recording: Boolean = false,
    @SerializedName("remote_playback") val remotePlayback: Boolean = false,
)

data class LiveStreamSessionRequest(@SerializedName("channel_id") val channelId: String)

data class LiveStreamSessionResponse(
    val id: String,
    @SerializedName("stream_url") val streamUrl: String,
    @SerializedName("stream_type") val streamType: String,
)

// チャンネル情報
data class Channel(
    val id: String,
    @SerializedName("display_channel_id") val displayChannelId: String,
    val name: String,
    @SerializedName("channel_number") val channelNumber: String,
    @SerializedName("network_id") val networkId: Long? = null,
    @SerializedName("service_id") val serviceId: Long? = null,
    // ★追加: TSIDをパースするためのプロパティ
    @SerializedName("transport_stream_id") val transportStreamId: Long? = null,
    val type: String,
    @SerializedName("is_watchable") val isWatchable: Boolean,
    @SerializedName("is_subchannel") val is_subchannel: Boolean = false,
    @SerializedName("is_display") val isDisplay: Boolean,
    @SerializedName("program_present") val programPresent: Program?,
    @SerializedName("program_following") val programFollowing: Program?,
    @SerializedName("remocon_id") val remocon_Id: Int? = null,
    @SerializedName("jikkyo_force") val jikkyoForce: Int? = null,
    val source: String = "Broadcast",
    val capabilities: ChannelCapabilities = ChannelCapabilities(),
    @SerializedName("viewer_count") val viewerCount: Int? = null,
) {
    // ★追加: どのバックエンドでも変わらない全国共通の識別子
    val uniqueId: String get() =
        if (networkId != null && serviceId != null) "${networkId}_${serviceId}" else id
    val isJellyfin: Boolean get() = source.equals("Jellyfin", ignoreCase = true)
    fun supportsLiveStreamSession(): Boolean = capabilities.liveStreamSession
}

// 番組情報
data class Program(
    val id: String,
    val title: String,
    val description: String,
    val detail: Map<String, String>?, // "番組内容", "出演者" などの可変項目
    @SerializedName("start_time") val startTime: String,
    @SerializedName("end_time") val endTime: String,
    val duration: Int,
    val genres: List<Genre>?,
    @SerializedName("video_resolution") val videoResolution: String? = null,
    val source: String = "Broadcast",
)

// ジャンル情報
data class Genre(
    val major: String,
    val middle: String
)

enum class AudioMode { MAIN, SUB }
enum class SubMenuCategory { AUDIO, VIDEO, SUBTITLE, QUALITY, COMMENT, RECORD } // ★RECORD追加
enum class StreamSource { MIRAKURUN, KONOMITV, EDCB }

// ★追加: サーバー設定をポリモーフィズムで扱うためのSealed Class
sealed class BackendConfig {
    abstract val ip: String
    abstract val port: String
    val isValid: Boolean get() = ip.isNotBlank() && port.isNotBlank()

    data class KonomiTv(override val ip: String, override val port: String) : BackendConfig()
    data class Mirakurun(override val ip: String, override val port: String) : BackendConfig()
    data class Edcb(override val ip: String, override val port: String) : BackendConfig()
}

object LivePlayerConstants {
    const val TAG_SUBTITLE = "SubtitleDebug"
    const val SUBTITLE_SYNC_OFFSET_MS = -500L
}
