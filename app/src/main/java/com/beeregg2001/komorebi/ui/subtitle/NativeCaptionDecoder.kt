package com.beeregg2001.komorebi.ui.subtitle

import android.util.Log
import com.beeregg2001.komorebi.NativeLib
import com.beeregg2001.komorebi.util.mmts.B62SubtitleResource

class NativeCaptionDecoder(
    private val nativeLib: NativeLib = NativeLib(),
    private val captionType: Int = TYPE_CAPTION
) : AutoCloseable {
    private var handle: Long = nativeLib.openCaptionDecoder(captionType)
    private var languages: List<NativeCaptionLanguage> = emptyList()

    @Synchronized
    fun decode(
        data: ByteArray,
        ptsMs: Long,
        renderCaptions: Boolean = true
    ): NativeCaptionCue? {
        val activeHandle = handle
        if (activeHandle == 0L) return null
        // Keep feeding caption-management data while captions are hidden. It carries the
        // language table and is required both for showing the language switch and for making
        // a later SwitchLanguage call work. Caption statements are skipped to avoid rendering
        // bitmaps in the background.
        if (!renderCaptions && !AribCaptionData.isManagementPacket(data)) return null
        return try {
            val cue = nativeLib.decodeCaption(activeHandle, data, ptsMs)
            val detectedLanguages = nativeLib.getCaptionLanguageCodes(activeHandle)
                .mapIndexed { index, code ->
                    NativeCaptionLanguage(
                        id = index + 1,
                        iso6392Code = code.toIso6392Code()
                    )
                }
            if (detectedLanguages.isNotEmpty()) languages = detectedLanguages
            if (renderCaptions) cue else null
        } catch (e: Exception) {
            Log.e(TAG, "Failed to decode ARIB caption", e)
            null
        }
    }

    @Synchronized
    fun decodeB62(
        data: ByteArray,
        ptsMs: Long,
        operationMode: Int = 1,
        timingMode: Int = 3,
        referenceStartPtsMs: Long? = null,
        mpuSequenceNumber: Long? = null,
        resources: List<B62SubtitleResource> = emptyList(),
        discontinuity: Boolean = false
    ): List<NativeCaptionCue> {
        val activeHandle = handle
        if (activeHandle == 0L) return emptyList()
        return try {
            val decoded = nativeLib.decodeB62Captions(
                activeHandle,
                data,
                ptsMs,
                operationMode,
                timingMode,
                referenceStartPtsMs ?: Long.MIN_VALUE,
                mpuSequenceNumber?.plus(1L) ?: 0L,
                resources.map { it.index }.toIntArray(),
                resources.map { it.dataType }.toIntArray(),
                resources.map { it.data }.toTypedArray(),
                discontinuity
            ).toList()
            if (decoded.isEmpty() && !discontinuity) {
                return emptyList()
            }
            val timelineCommand = NativeCaptionCue(
                ptsMs = if (discontinuity) ptsMs else decoded.minOf { it.ptsMs },
                durationMs = 0L,
                clearScreen = true,
                planeWidth = 1,
                planeHeight = 1,
                images = emptyList(),
                timelineCommand = if (discontinuity) {
                    NativeCaptionCue.TIMELINE_COMMAND_RESET
                } else {
                    NativeCaptionCue.TIMELINE_COMMAND_REPLACE_FROM
                },
                type = captionType
            )
            val cues = listOf(timelineCommand) + decoded
            cues.also {
                if (decoded.isNotEmpty()) {
                    languages = listOf(NativeCaptionLanguage(id = 1, iso6392Code = "jpn"))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to decode ARIB STD-B62 caption", e)
            emptyList()
        }
    }

    @Synchronized
    fun availableLanguages(): List<NativeCaptionLanguage> = languages

    @Synchronized
    fun switchLanguage(languageId: Int) {
        val activeHandle = handle
        if (activeHandle == 0L || languageId !in 1..2) return
        nativeLib.switchCaptionLanguage(activeHandle, languageId)
    }

    @Synchronized
    fun flush() {
        val activeHandle = handle
        if (activeHandle != 0L) nativeLib.flushCaptionDecoder(activeHandle)
    }

    @Synchronized
    fun reset(languageId: Int = 1) {
        val activeHandle = handle
        if (activeHandle != 0L) nativeLib.closeCaptionDecoder(activeHandle)
        handle = nativeLib.openCaptionDecoder(captionType)
        languages = emptyList()
        if (handle != 0L && languageId != 1) {
            nativeLib.switchCaptionLanguage(handle, languageId)
        }
    }

    @Synchronized
    override fun close() {
        val activeHandle = handle
        handle = 0L
        languages = emptyList()
        if (activeHandle != 0L) nativeLib.closeCaptionDecoder(activeHandle)
    }

    private fun Int.toIso6392Code(): String = buildString(3) {
        append(((this@toIso6392Code shr 16) and 0xff).toChar())
        append(((this@toIso6392Code shr 8) and 0xff).toChar())
        append((this@toIso6392Code and 0xff).toChar())
    }

    companion object {
        const val TYPE_CAPTION = NativeCaptionCue.TYPE_CAPTION
        const val TYPE_SUPERIMPOSE = NativeCaptionCue.TYPE_SUPERIMPOSE

        private const val TAG = "NativeCaptionDecoder"
    }
}
