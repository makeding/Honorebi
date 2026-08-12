package com.beeregg2001.komorebi.data.model

data class KonomiUser(
    val id: Int,
    val name: String,
    val is_admin: Boolean = false,
    val pinned_channel_ids: List<String> = emptyList(),
)

data class UserAccessToken(
    @com.google.gson.annotations.SerializedName("access_token") val accessToken: String,
    @com.google.gson.annotations.SerializedName("token_type") val tokenType: String,
)

data class WatchedHistoryItem(
    @com.google.gson.annotations.SerializedName("video_id") val videoId: Int,
    @com.google.gson.annotations.SerializedName("last_playback_position") val playbackPosition: Double,
    @com.google.gson.annotations.SerializedName("created_at") val createdAt: Double,
    @com.google.gson.annotations.SerializedName("updated_at") val updatedAt: Double,
)

data class WatchedHistoryPayload(val items: List<WatchedHistoryItem>)

data class DeviceAuthCreateRequest(
    @com.google.gson.annotations.SerializedName("device_name") val deviceName: String,
)

data class DeviceAuthTokenRequest(
    @com.google.gson.annotations.SerializedName("device_code") val deviceCode: String,
)

data class DeviceAuthRequest(
    @com.google.gson.annotations.SerializedName("device_code") val deviceCode: String,
    @com.google.gson.annotations.SerializedName("user_code") val userCode: String,
    @com.google.gson.annotations.SerializedName("verification_url") val verificationUrl: String,
    @com.google.gson.annotations.SerializedName("expires_in") val expiresIn: Int,
    val interval: Int,
)

data class KonomiHistoryProgram(
    val program: KonomiProgram,
    val playback_position: Double,
    val last_watched_at: String,
    // ★追加: UI受け渡し用のメタデータ（APIにはないがDBから復元した際に保持する）
    val videoId: Int? = null,
    val tileColumns: Int = 1,
    val tileRows: Int = 1,
    val tileInterval: Double = 10.0,
    val tileWidth: Int = 320,
    val tileHeight: Int = 180
)

data class KonomiProgram(
    val id: String,
    val title: String,
    val description: String,
    val detail: Map<String, String>?, // ★追加: 番組詳細
    val start_time: String,
    val end_time: String,
    val channel_id: String,
)

data class HistoryUpdateRequest(
    val program_id: String,
    val playback_position: Double
)
