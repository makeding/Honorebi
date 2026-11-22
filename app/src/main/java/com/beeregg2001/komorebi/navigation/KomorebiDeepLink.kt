package com.beeregg2001.komorebi.navigation

import android.content.Intent
import java.io.ByteArrayOutputStream
import java.math.BigDecimal
import java.net.URI
import java.net.URISyntaxException
import java.nio.charset.StandardCharsets

/**
 * Pure-Kotlin URI parser and builder for Komorebi deep links.
 *
 * Supported routes:
 * - `komorebi://recording/{recordedProgramId}[?position={seconds}]`
 * - `komorebi://live/{displayChannelId}`
 */
object KomorebiDeepLink {
    const val SCHEME: String = "komorebi"
    const val HOST_RECORDING: String = "recording"
    const val HOST_LIVE: String = "live"

    private const val QUERY_POSITION: String = "position"
    private const val MAX_LIVE_CHANNEL_ID_LENGTH: Int = 128

    fun parse(intent: Intent?): PlaybackOpenRequest? {
        if (intent?.action != Intent.ACTION_VIEW) return null
        return parse(intent.dataString)
    }

    fun parse(raw: String?): PlaybackOpenRequest? {
        if (raw.isNullOrEmpty()) return null
        val uri = try {
            URI(raw)
        } catch (_: URISyntaxException) {
            return null
        }

        val scheme = uri.scheme ?: return null
        if (!scheme.equals(SCHEME, ignoreCase = true)) return null
        if (uri.userInfo != null || uri.port != -1) return null

        val host = uri.host?.lowercase() ?: return null
        val rawPath = uri.rawPath ?: return null
        if (!rawPath.startsWith("/")) return null
        val segments = rawPath.removePrefix("/").split("/")
        if (segments.size != 1 || segments[0].isEmpty()) return null
        val rawSegment = segments[0]

        return when (host) {
            HOST_RECORDING -> parseRecording(rawSegment, uri.rawQuery)
            HOST_LIVE -> parseLive(rawSegment)
            else -> null
        }
    }

    fun recordingUri(id: Int, positionSeconds: Double? = null): String {
        require(id > 0) { "recordedProgramId must be positive: $id" }
        if (positionSeconds == null) {
            return "$SCHEME://$HOST_RECORDING/$id"
        }
        require(positionSeconds.isFinite() && positionSeconds >= 0.0) {
            "positionSeconds must be finite and >= 0: $positionSeconds"
        }
        val formattedPosition = if (positionSeconds == 0.0) {
            "0"
        } else {
            BigDecimal.valueOf(positionSeconds).stripTrailingZeros().toPlainString()
        }
        return "$SCHEME://$HOST_RECORDING/$id?$QUERY_POSITION=$formattedPosition"
    }

    fun liveUri(displayChannelId: String): String {
        require(displayChannelId.isNotBlank() && displayChannelId.length <= MAX_LIVE_CHANNEL_ID_LENGTH) {
            "displayChannelId must be non-blank and <= $MAX_LIVE_CHANNEL_ID_LENGTH chars"
        }
        return "$SCHEME://$HOST_LIVE/${percentEncodeSegment(displayChannelId)}"
    }

    private fun parseRecording(rawId: String, rawQuery: String?): PlaybackOpenRequest.Recording? {
        if (!rawId.all { it in '0'..'9' }) return null
        val id = rawId.toIntOrNull()?.takeIf { it > 0 } ?: return null

        var positionSeconds: Double? = null
        if (!rawQuery.isNullOrEmpty()) {
            val pairs = rawQuery.split("&")
            for (pair in pairs) {
                if (pair.isEmpty()) continue
                val eqIndex = pair.indexOf('=')
                val rawKey = if (eqIndex >= 0) pair.substring(0, eqIndex) else pair
                val key = percentDecode(rawKey) ?: return null
                if (key != QUERY_POSITION) continue

                if (positionSeconds != null) return null
                if (eqIndex < 0) return null
                val rawValue = percentDecode(pair.substring(eqIndex + 1)) ?: return null
                if (rawValue.isEmpty() || rawValue.startsWith("-")) return null
                val parsed = rawValue.toDoubleOrNull() ?: return null
                if (!parsed.isFinite() || parsed < 0.0) return null
                positionSeconds = parsed
            }
        }

        return PlaybackOpenRequest.Recording(
            recordedProgramId = id,
            positionSeconds = positionSeconds,
            source = PlaybackOpenSource.DeepLink,
        )
    }

    private fun parseLive(rawChannelId: String): PlaybackOpenRequest.Live? {
        val decoded = percentDecode(rawChannelId) ?: return null
        if (decoded.isBlank() || decoded.length > MAX_LIVE_CHANNEL_ID_LENGTH) return null
        return PlaybackOpenRequest.Live(
            displayChannelId = decoded,
            source = PlaybackOpenSource.DeepLink,
        )
    }

    private fun percentEncodeSegment(value: String): String {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        val sb = StringBuilder(bytes.size)
        for (b in bytes) {
            val unsigned = b.toInt() and 0xFF
            if (isUnreserved(unsigned)) {
                sb.append(unsigned.toChar())
            } else {
                sb.append('%')
                sb.append(HEX_DIGITS[unsigned ushr 4])
                sb.append(HEX_DIGITS[unsigned and 0x0F])
            }
        }
        return sb.toString()
    }

    private fun percentDecode(raw: String): String? {
        if ('%' !in raw) return raw
        val out = ByteArrayOutputStream(raw.length)
        var i = 0
        while (i < raw.length) {
            val ch = raw[i]
            if (ch == '%') {
                if (i + 2 >= raw.length) return null
                val hi = hexValue(raw[i + 1])
                val lo = hexValue(raw[i + 2])
                if (hi < 0 || lo < 0) return null
                out.write((hi shl 4) or lo)
                i += 3
            } else {
                val utf8 = ch.toString().toByteArray(StandardCharsets.UTF_8)
                out.write(utf8)
                i += 1
            }
        }
        return String(out.toByteArray(), StandardCharsets.UTF_8)
    }

    private fun isUnreserved(code: Int): Boolean =
        (code in 'A'.code..'Z'.code) ||
            (code in 'a'.code..'z'.code) ||
            (code in '0'.code..'9'.code) ||
            code == '-'.code ||
            code == '.'.code ||
            code == '_'.code ||
            code == '~'.code

    private fun hexValue(ch: Char): Int = when (ch) {
        in '0'..'9' -> ch - '0'
        in 'A'..'F' -> ch - 'A' + 10
        in 'a'..'f' -> ch - 'a' + 10
        else -> -1
    }

    private const val HEX_DIGITS: String = "0123456789ABCDEF"
}
