package com.beeregg2001.komorebi.common

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.util.Log
import androidx.media3.common.util.UnstableApi
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

object UrlBuilder {

    /**
     * ホストとポートからベースURLを組み立てる。
     *
     * スキーム付きの入力 (例: https://example.com) はそのまま維持する。
     * URL側にポートが明示されている場合は設定欄のポートより優先し、
     * ポートがないスキーム付きURLは http/https の標準ポートを使用する。
     */
    fun formatBaseUrl(ip: String, port: String, defaultProtocol: String): String {
        val cleanIp = ip.trim().removeSuffix("/")
        val normalized = if (
            cleanIp.startsWith("http://", ignoreCase = true) ||
            cleanIp.startsWith("https://", ignoreCase = true)
        ) {
            cleanIp
        } else {
            "$defaultProtocol://$cleanIp"
        }

        val parsed = normalized.toHttpUrlOrNull() ?: return "$normalized:$port"
        val hasExplicitScheme = cleanIp.startsWith("http://", ignoreCase = true) ||
            cleanIp.startsWith("https://", ignoreCase = true)
        val hasExplicitPort = Regex("^https?://(?:\\[[^]]+\\]|[^/:]+):\\d+(?:/|$)", RegexOption.IGNORE_CASE)
            .containsMatchIn(normalized)
        if (hasExplicitPort) return parsed.toString().removeSuffix("/")

        val configuredPort = if (hasExplicitScheme) {
            if (parsed.scheme.equals("https", ignoreCase = true)) 443 else 80
        } else {
            port.toIntOrNull()?.takeIf { it in 1..65535 } ?: return parsed.toString()
        }
        return parsed.newBuilder().port(configuredPort).build().toString().removeSuffix("/")
    }

    /** EDCBのTCP直接接続用に、入力からホスト名/IPだけを取り出す。 */
    fun extractBareHost(ip: String): String {
        val cleanIp = ip.trim().removeSuffix("/")
        val normalized = if (
            cleanIp.startsWith("http://", ignoreCase = true) ||
            cleanIp.startsWith("https://", ignoreCase = true)
        ) cleanIp else "http://$cleanIp"
        return normalized.toHttpUrlOrNull()?.host
            ?: cleanIp.replace(Regex("^https?://", RegexOption.IGNORE_CASE), "")
                .substringBefore("/").substringBefore(":")
    }

    /**
     * Mirakurun形式のStreamID
     */
    @OptIn(UnstableApi::class)
    fun buildMirakurunStreamId(networkId: Long, serviceId: Long): String {
        val mirakurunId: Long = (networkId * 100000) + serviceId
        return mirakurunId.toString()
    }

    // --- ロゴ関連 ---
    @OptIn(UnstableApi::class)
    fun getMirakurunLogoUrl(ip: String, port: String, networkId: Long, serviceId: Long): String {
        val baseUrl = formatBaseUrl(ip, port, "http")
        val streamId = buildMirakurunStreamId(networkId, serviceId)
        return "$baseUrl/api/services/$streamId/logo"
    }

    fun getKonomiTvLogoUrl(ip: String, port: String, displayChannelId: String): String {
        val baseUrl = formatBaseUrl(ip, port, "https")
        return "$baseUrl/api/channels/$displayChannelId/logo"
    }

    // --- サムネイル関連 ---
    // ★修正: backendTypeを受け取り、システムごとに正しいパスを生成する
    fun getThumbnailUrl(backendType: String, ip: String, port: String, videoId: String): String {
        val baseUrl = formatBaseUrl(ip, port, "http") // サムネイルは基本的にhttpフォールバックで安全に組む
        return when (backendType) {
            // ★修正: TODOを削除し、EMWUIの標準サムネイルAPIパスを設定
            "EDCB" -> "$baseUrl/api/Thumbnail?id=$videoId"
            "EPGSTATION" -> "$baseUrl/api/thumbnails/$videoId" // EPGStationの標準サムネイルAPI
            else -> { // KonomiTV (デフォルト)
                val secureBaseUrl = formatBaseUrl(ip, port, "https")
                "$secureBaseUrl/api/videos/$videoId/thumbnail"
            }
        }
    }

    // --- ストリーミング関連 ---
    fun getMirakurunStreamUrl(ip: String, port: String, networkId: Long, serviceId: Long): String {
        val baseUrl = formatBaseUrl(ip, port, "http")
        val streamId = buildMirakurunStreamId(networkId, serviceId)
        return "$baseUrl/api/services/$streamId/stream"
    }

    fun getMirakurunRawMmtsStreamUrl(
        ip: String,
        port: String,
        networkId: Long,
        serviceId: Long
    ): String {
        return "${getMirakurunStreamUrl(ip, port, networkId, serviceId)}?decode=0"
    }

    fun getKonomiTvLiveStreamUrl(
        ip: String,
        port: String,
        displayChannelId: String,
        quality: String = "1080p-60fps"
    ): String {
        val baseUrl = formatBaseUrl(ip, port, "https")
        return "$baseUrl/api/streams/live/$displayChannelId/$quality/mpegts"
    }

    fun getKonomiTvLiveEventsUrl(
        ip: String,
        port: String,
        displayChannelId: String,
        quality: String = "1080p-60fps"
    ): String {
        val baseUrl = formatBaseUrl(ip, port, "https")
        return "$baseUrl/api/streams/live/$displayChannelId/$quality/events"
    }

    fun getKonomiTvWatchUrl(ip: String, port: String, displayChannelId: String): String {
        val baseUrl = formatBaseUrl(ip, port, "https")
        return "$baseUrl/tv/watch/${Uri.encode(displayChannelId)}"
    }

    fun getKonomiTvJikkyoWatchSessionUrl(ip: String, port: String, displayChannelId: String): String {
        val baseUrl = formatBaseUrl(ip, port, "https")
        return "$baseUrl/api/channels/$displayChannelId/jikkyo"
    }

    @OptIn(UnstableApi::class)
    fun getVideoPlaylistUrl(
        ip: String,
        port: String,
        videoId: Int,
        sessionId: String,
        quality: String = "1080p-60fps",
        isRecording: Boolean = false
    ): String {
        val baseUrl = formatBaseUrl(ip, port, "https")
        val recordingQuery = if (isRecording) "&recording=1" else ""
        return "$baseUrl/api/streams/video/$videoId/$quality/playlist?session_id=$sessionId$recordingQuery"
    }

    fun getVideoRawMmtsUrl(ip: String, port: String, videoId: Int): String {
        val baseUrl = formatBaseUrl(ip, port, "https")
        return "$baseUrl/api/streams/video/$videoId/raw-mmts/mpegts"
    }

    fun getVideoOriginalDownloadUrl(ip: String, port: String, videoId: Int): String {
        val baseUrl = formatBaseUrl(ip, port, "https")
        return "$baseUrl/api/videos/$videoId/download"
    }

    /**
     * シークバー用タイル画像取得 (KonomiTV API)
     * URL: /api/videos/{id}/thumbnail/tiled
     * パラメータなしで巨大なシート画像を取得する仕様
     */
    fun getTiledThumbnailUrl(ip: String, port: String, videoId: Int): String {
        val baseUrl = formatBaseUrl(ip, port, "https")
        return "$baseUrl/api/videos/$videoId/thumbnail/tiled"
    }

    // アーカイブ実況コメントAPIのURL
    fun getArchivedJikkyoUrl(ip: String, port:  String, videoId: Int): String {
        val baseUrl = formatBaseUrl(ip, port, "https")
        return "$baseUrl/api/videos/$videoId/jikkyo"
    }

    /**
     * EDCBの録画フォルダにある静的サムネイル (録画ファイル名.ts.jpg) を直接取得するURL
     */
    fun getEdcbDirectThumbnailUrl(ip: String, port: String, recFilePath: String): String {
        val baseUrl = formatBaseUrl(ip, port, "http")
        val relativePath = recFilePath
            .replace(Regex("^[a-zA-Z]:\\\\"), "")
            .replace("\\", "/")

        // 録画ファイルの末尾に .jpg を足すことで "hoge.ts.jpg" を指定
        val encodedPath = android.net.Uri.encode(relativePath, "/")
        return "$baseUrl/rec/$encodedPath.jpg"
    }
}
