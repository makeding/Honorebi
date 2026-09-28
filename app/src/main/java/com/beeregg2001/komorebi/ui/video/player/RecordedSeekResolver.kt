package com.beeregg2001.komorebi.ui.video.player

import android.util.Log
import com.beeregg2001.komorebi.data.model.SeekPositionResponse
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.withTimeoutOrNull

/** サーバー側で解決済みのシーク先 (再生時刻と録画ファイル内のバイト位置)。 */
data class RecordedSeekResolution(val timeUs: Long, val position: Long)

/**
 * 録画ファイル直接再生 (オリジナル画質の MPEG-TS) のシーク先バイト位置を HonomiTV の seek-position API で解決する。
 *
 * 従来はファイルサイズ比例の推定位置へシークしていたが、可変ビットレートの録画では目標時刻より
 * 数十秒〜数分手前に着地することがあり、目標時刻に達するまでのデコードで再生再開が大きく遅れていた。
 * 解決結果は [resolutionRef] に保持し、ExoPlayer の SeekMap.getSeekPoints から参照する。
 */
internal class RecordedSeekResolver(
    private val videoId: Int,
    private val enabled: Boolean,
    private val resolvePosition: suspend (videoId: Int, timeSeconds: Double) -> SeekPositionResponse?,
) {
    val resolutionRef = AtomicReference<RecordedSeekResolution?>(null)

    /**
     * 目標時刻を解決し、ExoPlayer の seekTo / setMediaItem に渡す時刻 (ms) を返す。
     * 解決できなければ [resolutionRef] をクリアして目標時刻をそのまま返す (従来の比例シークにフォールバック)。
     */
    suspend fun resolve(targetMs: Long): Long {
        if (!enabled || videoId == 0 || targetMs <= 0L) {
            resolutionRef.set(null)
            return targetMs
        }
        val response = withTimeoutOrNull(RESOLVE_TIMEOUT_MS) { resolvePosition(videoId, targetMs / 1000.0) }
        if (response == null || response.position < 0L) {
            resolutionRef.set(null)
            Log.w(TAG, "Seek position not resolved, falling back to proportional seek. [video=$videoId, target_ms=$targetMs]")
            return targetMs
        }
        val resolvedTimeUs = (response.time * 1_000_000.0).toLong().coerceAtLeast(0L)
        resolutionRef.set(RecordedSeekResolution(resolvedTimeUs, response.position))
        Log.i(
            TAG,
            "Resolved seek position. [video=$videoId, target_ms=$targetMs, resolved_ms=${resolvedTimeUs / 1000L}, position=${response.position}]"
        )
        return resolvedTimeUs / 1000L
    }

    companion object {
        private const val TAG = "RecordedSeekResolver"
        private const val RESOLVE_TIMEOUT_MS = 2_000L

        /** getSeekPoints に渡された時刻と解決済み時刻の差がこの範囲内なら解決済み位置を使う。 */
        const val MATCH_TOLERANCE_US = 1_500_000L
    }
}
