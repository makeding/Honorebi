package com.beeregg2001.komorebi.data.model

/**
 * UI表示に特化した、計算済みのチャンネル状態。
 */
data class UiChannelState(
    val channel: Channel,
    val displayChannelId: String,
    val name: String,
    val programTitle: String,
    val progress: Float,
    val hasProgram: Boolean,
    val jikkyoForce: Int? = null // ★勢い情報を追加
)

data class LiveRowState(
    val genreId: String,
    val genreLabel: String,
    val channels: List<UiChannelState>
)

// --- 以下、既存のモデル定義 ---
data class EpgChannelResponse(
    val channels: List<EpgChannelWrapper>,
    @com.google.gson.annotations.SerializedName("source_errors") val sourceErrors: Map<String, String?> = emptyMap(),
)
data class EpgChannelWrapper(val channel: EpgChannel, val programs: List<EpgProgram>)
data class EpgChannel(
    val id: String, val display_channel_id: String, val network_id: Int? = null, val service_id: Int? = null,
    val transport_stream_id: Int? = null, val remocon_id: Int? = null, val channel_number: String = "",
    val type: String, val name: String, val jikkyo_force: Int? = null, val is_subchannel: Boolean = false,
    val is_radiochannel: Boolean = false, val is_watchable: Boolean = false,
    val source: String = "Broadcast", val capabilities: ChannelCapabilities = ChannelCapabilities(),
)
data class EpgProgram(
    val id: String, val channel_id: String, val network_id: Int? = null, val service_id: Int? = null,
    val event_id: Int? = null, val title: String, val description: String, val extended: String? = null,
    val detail: Map<String, String>?, val start_time: String, val end_time: String,
    val duration: Int, val is_free: Boolean, val genres: List<EpgGenre>?, val video_type: String?,
    val audio_type: String?, val audio_sampling_rate: String?, val source: String = "Broadcast"
)
data class EpgGenre(val major: String, val middle: String)
