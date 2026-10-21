package com.beeregg2001.komorebi.ui.player.recorded

import android.util.Log
import com.beeregg2001.komorebi.common.UrlBuilder
import com.beeregg2001.komorebi.common.UserFacingFailure
import com.beeregg2001.komorebi.common.userFacingFailure
import com.beeregg2001.komorebi.data.SettingsRepository
import com.beeregg2001.komorebi.data.model.StreamQuality
import com.beeregg2001.komorebi.data.repository.RecordProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

data class RecordedPlaybackSource(
    val url: String,
    /** True only for the KonomiTV transcoding endpoint, never for recording chase itself. */
    val usesTranscodingLiveWindow: Boolean,
    val failure: UserFacingFailure? = null,
)

/** Resolves the recorded stream boundary without giving UI code provider or setting ownership. */
@Singleton
class RecordedPlaybackSourceResolver @Inject constructor(
    private val recordProvider: RecordProvider,
    private val settingsRepository: SettingsRepository,
    private val nhkExclusionRepository: com.beeregg2001.komorebi.data.repository.NHKExclusionRepository,
) {
    suspend fun resolve(
        videoId: Int,
        quality: String,
        sessionId: String,
        offsetSeconds: Double = 0.0,
        isRecording: Boolean = false,
    ): RecordedPlaybackSource = try {
        nhkExclusionRepository.state.first { it.isLoaded }
        if (nhkExclusionRepository.state.value.isActive) {
            val program = recordProvider.getRecordedProgram(videoId).getOrThrow()
            if (nhkExclusionRepository.isExcluded(program)) {
                throw IllegalStateException("NHK_EXCLUDED")
            }
        }
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
            else -> withTimeout(15_000L) {
                recordProvider.getRecordStreamUrl(videoId, quality, sessionId, offsetSeconds, isRecording)
            }.let { url ->
                RecordedPlaybackSource(
                    url = url,
                    usesTranscodingLiveWindow = url.contains("/api/xcode") && quality != "10",
                    failure = if (url.isBlank()) userFacingFailure(null, "RECORDED_SOURCE") else null,
                )
            }
        }
    } catch (error: TimeoutCancellationException) {
        RecordedPlaybackSource(url = "", usesTranscodingLiveWindow = false,
            failure = userFacingFailure(error, "RECORDED_SOURCE"))
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        Log.e(TAG, "Failed to resolve recorded stream URL", error)
        RecordedPlaybackSource(url = "", usesTranscodingLiveWindow = false,
            failure = userFacingFailure(error, "RECORDED_SOURCE"))
    }

    private companion object { const val TAG = "RecordedPlaybackSource" }
}
