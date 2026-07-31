package com.beeregg2001.komorebi.data.model

import com.google.gson.annotations.SerializedName

data class RecordedApiResponse(
    val total: Int,
    @SerializedName("recorded_programs") val recordedPrograms: List<RecordedProgram>
)

data class SeriesApiResponse(
    val total: Int,
    @SerializedName("series_list") val seriesList: List<SeriesProgram>
)

data class SeriesProgram(
    val id: Int,
    val title: String,
    val description: String = "",
    val genres: List<EpgGenre>? = null,
    @SerializedName("broadcast_periods") val broadcastPeriods: List<SeriesBroadcastPeriod> = emptyList()
)

data class SeriesBroadcastPeriod(
    val channel: RecordedChannel? = null,
    @SerializedName("recorded_programs") val recordedPrograms: List<RecordedProgram> = emptyList()
)

data class RecordedProgram(
    val id: Int,
    val title: String,
    @SerializedName(value = "series_name", alternate = ["seriesName", "series_title"])
    val seriesName: String? = null,
    val isEpisodic: Boolean? = false,
    val description: String,
    val detail: Map<String, String>? = null,
    @SerializedName("start_time") val startTime: String,
    @SerializedName("end_time") val endTime: String,
    val duration: Double,
    @SerializedName("recording_start_margin") val recordingStartMargin: Double = 0.0,
    @SerializedName("recording_end_margin") val recordingEndMargin: Double = 0.0,
    @SerializedName("is_partially_recorded") val isPartiallyRecorded: Boolean,
    val channel: RecordedChannel? = null,
    @SerializedName("recorded_video") val recordedVideo: RecordedVideo,
    val genres: List<EpgGenre>? = null,
    val isRecording: Boolean = false,
    val playbackPosition: Double = 0.0,
    // ★ ここから下の2行を追加するだけです ★
    val directThumbnailUrl: String? = null,
    val apiThumbnailUrl: String? = null
) {
    /**
     * BS4K/BS8K はライブ・録画ともに変換ストリームへ落とさず、Raw MMT/TLV で再生する。
     * 録画一覧のチャンネル情報が欠ける場合に備えて、コンテナ形式も判定に使う。
     */
    val requiresRawMmtsPlayback: Boolean
        get() = channel?.type.equals("BS4K", ignoreCase = true) ||
            recordedVideo.containerFormat.equals("MMT/TLV", ignoreCase = true)
}

// CM区間（チャプター）のデータモデル
data class CmSection(
    @SerializedName("start_time") val startTime: Double,
    @SerializedName("end_time") val endTime: Double
)

data class RecordedChannel(
    val id: String,
    @SerializedName("network_id") val networkId: Int? = null,
    @SerializedName("service_id") val serviceId: Int? = null,
    @SerializedName("display_channel_id") val displayChannelId: String,
    val type: String,
    val name: String,
    @SerializedName("channel_number") val channelNumber: String
)

data class RecordedVideo(
    val id: Int,
    val status: String,
    @SerializedName("file_path") val filePath: String,
    @SerializedName("recording_start_time") val recordingStartTime: String? = null,
    @SerializedName("recording_end_time") val recordingEndTime: String? = null,
    val duration: Double,
    @SerializedName("container_format") val containerFormat: String,
    @SerializedName("video_codec") val videoCodec: String,
    @SerializedName("audio_codec") val audioCodec: String,
    @SerializedName("has_key_frames") val hasKeyFrames: Boolean? = true,
    @SerializedName("thumbnail_info") val thumbnailInfo: ThumbnailInfo? = null,
    @SerializedName("cm_sections") val cmSections: List<CmSection>? = null
)

data class ThumbnailInfo(
    val version: Int,
    val tile: TileInfo?
)

data class TileInfo(
    @SerializedName("image_width") val imageWidth: Int,
    @SerializedName("image_height") val imageHeight: Int,
    @SerializedName("tile_width") val tileWidth: Int,
    @SerializedName("tile_height") val tileHeight: Int,
    @SerializedName("column_count") val columnCount: Int,
    @SerializedName("row_count") val rowCount: Int,
    @SerializedName("interval_sec") val intervalSec: Double,
    @SerializedName("total_tiles") val totalTiles: Int
)
