package com.beeregg2001.komorebi.ui.player.recorded

import android.util.Log
import com.beeregg2001.komorebi.common.UrlBuilder
import com.beeregg2001.komorebi.data.SettingsRepository
import com.beeregg2001.komorebi.data.model.StreamQuality
import com.beeregg2001.komorebi.data.repository.RecordProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

data class RecordedPlaybackSource(
    val url: String,
    /** True only for the KonomiTV transcoding endpoint, never for recording chase itself. */
    val usesTranscodingLiveWindow: Boolean,
)

/** Resolves the recorded stream boundary without giving UI code provider or setting ownership. */
@Singleton
class RecordedPlaybackSourceResolver @Inject constructor(
    private val recordProvider: RecordProvider,
    private val settingsRepository: SettingsRepository,
) {
    suspend fun resolve(
        videoId: Int,
        quality: String,
        sessionId: String,
        offsetSeconds: Double = 0.0,
        isRecording: Boolean = false,
    ): RecordedPlaybackSource = try {
        when (quality) {
            StreamQuality.RAW_MMTS_PRIMARY_VALUE -> RecordedPlaybackSource(
                url = UrlBuilder.getVideoRawMmtsUrl(
                    settingsRepository.konomiIp.first(), settingsRepository.konomiPort.first(), videoId,
                ),
                usesTranscodingLiveWindow = false,
            )
            StreamQuality.ORIGINAL_MPEG_TS_VALUE -> RecordedPlaybackSource(
                url = UrlBuilder.getVideoOriginalDownloadUrl(
                    settingsRepository.konomiIp.first(), settingsRepository.konomiPort.first(), videoId,
                ),
                usesTranscodingLiveWindow = false,
            )
            else -> recordProvider.getRecordStreamUrl(
                videoId, quality, sessionId, offsetSeconds, isRecording,
            ).let { url ->
                RecordedPlaybackSource(
                    url = url,
                    usesTranscodingLiveWindow = url.contains("/api/xcode") && quality != "10",
                )
            }
        }
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        Log.e(TAG, "Failed to resolve recorded stream URL", error)
        RecordedPlaybackSource(url = "", usesTranscodingLiveWindow = false)
    }

    private companion object { const val TAG = "RecordedPlaybackSource" }
}
