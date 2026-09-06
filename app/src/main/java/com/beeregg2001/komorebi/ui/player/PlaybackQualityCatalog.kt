package com.beeregg2001.komorebi.ui.player

import android.util.Log
import com.beeregg2001.komorebi.data.SettingsRepository
import com.beeregg2001.komorebi.data.model.RecordedProgram
import com.beeregg2001.komorebi.data.model.StreamQuality
import com.beeregg2001.komorebi.data.model.StreamSource
import com.beeregg2001.komorebi.data.repository.RecordProvider
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The one owner of the persisted EDCB quality catalogue.
 *
 * Consumers still choose their source-specific and program-specific entries here, so a cached
 * EDCB list can never accidentally expose an HLS quality for direct, MMTS, or TS playback.
 */
@Singleton
class PlaybackQualityCatalog @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val recordProvider: RecordProvider,
    private val gson: Gson,
) {
    suspend fun live(source: StreamSource, isEdcbDirect: Boolean): List<StreamQuality> = when (source) {
        StreamSource.EDCB -> if (isEdcbDirect) directQuality() else edcbQualities(
            selectedQuality = settingsRepository.liveQuality.first(),
        )
        StreamSource.KONOMITV -> StreamQuality.DEFAULT_QUALITIES
        StreamSource.MIRAKURUN -> directQuality()
    }

    suspend fun recorded(program: RecordedProgram?): List<StreamQuality> {
        return when (settingsRepository.backendType.first()) {
            "EDCB" -> if (settingsRepository.edcbRecordPlayMethod.first() == "DIRECT") {
                directQuality()
            } else {
                edcbQualities(settingsRepository.videoQuality.first())
            }
            "KONOMITV" -> when {
                program?.requiresRawMmtsPlayback == true -> listOf(StreamQuality.recordedRawMmts())
                program?.recordedVideo?.containerFormat.equals("MPEG-TS", ignoreCase = true) &&
                    program?.recordedVideo?.videoCodec.equals("MPEG-2", ignoreCase = true) ->
                    listOf(StreamQuality.originalMpegTsHardwareDi()) + StreamQuality.DEFAULT_QUALITIES
                else -> StreamQuality.DEFAULT_QUALITIES
            }
            else -> directQuality()
        }
    }

    /** Explicit settings refresh. The catalogue remains the only cache writer. */
    suspend fun refreshEdcb(): List<StreamQuality> = runCatching {
        recordProvider.getStreamQualities()
    }.onFailure { Log.w(TAG, "Failed to refresh EDCB stream qualities", it) }
        .getOrDefault(emptyList())
        .also { qualities ->
            if (qualities.isNotEmpty()) {
                settingsRepository.saveString(
                    SettingsRepository.AVAILABLE_STREAM_QUALITIES,
                    gson.toJson(qualities),
                )
            }
        }

    /** Used by Settings while it observes DataStore; this intentionally never performs I/O. */
    fun settings(
        backend: String,
        cachedJson: String,
        selectedLiveQuality: String,
        selectedVideoQuality: String,
        dynamicQualities: List<StreamQuality>?,
    ): List<StreamQuality> {
        if (backend != "EDCB") return StreamQuality.DEFAULT_QUALITIES
        return dynamicQualities?.takeIf { it.isNotEmpty() }
            ?: parse(cachedJson).takeIf { it.isNotEmpty() }
            ?: selectedFallbacks(selectedLiveQuality, selectedVideoQuality)
    }

    private suspend fun edcbQualities(selectedQuality: String): List<StreamQuality> {
        parse(settingsRepository.availableStreamQualities.first())
            .takeIf { it.isNotEmpty() }
            ?.let { return it }

        return refreshEdcb()
            .takeIf { it.isNotEmpty() }
            ?: selectedFallbacks(selectedQuality, "").firstOrNull()
            ?.let(::listOf)
            ?: StreamQuality.DEFAULT_QUALITIES
    }

    private fun parse(json: String): List<StreamQuality> = runCatching {
        gson.fromJson<List<StreamQuality>>(json, STREAM_QUALITY_LIST_TYPE).orEmpty()
    }.getOrDefault(emptyList())

    private fun selectedFallbacks(first: String, second: String): List<StreamQuality> =
        listOf(first, second)
            .filter { it.isNotBlank() }
            .distinct()
            .map { StreamQuality(label = "設定値 ($it)", value = it, isRawTs = false) }
            .ifEmpty { StreamQuality.DEFAULT_QUALITIES }

    private fun directQuality() = listOf(
        StreamQuality(label = "オリジナル (Direct)", value = "direct", isRawTs = true),
    )

    private companion object {
        const val TAG = "PlaybackQualityCatalog"
        val STREAM_QUALITY_LIST_TYPE = object : TypeToken<List<StreamQuality>>() {}.type
    }
}
