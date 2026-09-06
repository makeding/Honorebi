package com.beeregg2001.komorebi.ui.player

import com.beeregg2001.komorebi.ui.subtitle.AribId3PrivPayloadRouter
import com.beeregg2001.komorebi.ui.subtitle.NativeCaptionCue
import com.beeregg2001.komorebi.ui.subtitle.NativeCaptionDecoder
import com.beeregg2001.komorebi.ui.subtitle.NativeCaptionLanguage
import com.beeregg2001.komorebi.util.mmts.B62SubtitleSample

/** Shared B24/B62 decoder selection. Caption and superimpose always retain separate decoders. */
internal object CaptionDecodeRouter {
    data class DecodeResult(
        val type: Int,
        val render: Boolean,
        val cues: List<NativeCaptionCue>,
        val languages: List<NativeCaptionLanguage>,
    )

    fun decodeB24(
        privateData: ByteArray,
        captionsEnabled: Boolean,
        captionDecoder: NativeCaptionDecoder,
        superimposeDecoder: NativeCaptionDecoder,
        ptsMs: Long,
    ): DecodeResult? = when (val route = AribId3PrivPayloadRouter.route(privateData, captionsEnabled)) {
        is AribId3PrivPayloadRouter.Route.Caption -> {
            val cue = captionDecoder.decode(privateData, ptsMs, renderCaptions = route.render)
            DecodeResult(
                type = NativeCaptionCue.TYPE_CAPTION,
                render = route.render,
                cues = listOfNotNull(cue),
                languages = captionDecoder.availableLanguages(),
            )
        }
        AribId3PrivPayloadRouter.Route.Superimpose -> DecodeResult(
            type = NativeCaptionCue.TYPE_SUPERIMPOSE,
            render = true,
            cues = listOfNotNull(superimposeDecoder.decode(privateData, ptsMs, renderCaptions = true)),
            languages = emptyList(),
        )
        AribId3PrivPayloadRouter.Route.Ignore -> null
    }

    fun decodeB62(
        sample: B62SubtitleSample,
        captionDecoder: NativeCaptionDecoder,
        superimposeDecoder: NativeCaptionDecoder,
        captionsEnabled: Boolean,
    ): DecodeResult? {
        val decoder = when (sample.type) {
            NativeCaptionDecoder.TYPE_CAPTION -> captionDecoder
            NativeCaptionDecoder.TYPE_SUPERIMPOSE -> superimposeDecoder
            else -> return null
        }
        val render = shouldRenderB62(sample.type, captionsEnabled) ?: return null
        // B62 streams do not expose B24-style management packets. Keep decoding hidden
        // captions so the decoder's language/state remains current; only suppress output.
        return DecodeResult(
            type = sample.type,
            render = render,
            cues = decoder.decodeB62(
                data = sample.data,
                ptsMs = sample.timeUs / 1_000L,
                operationMode = sample.operationMode,
                timingMode = sample.timingMode,
                referenceStartPtsMs = sample.referenceStartTimeUs?.div(1_000L),
                mpuSequenceNumber = sample.mpuSequenceNumber,
                resources = sample.resources,
                discontinuity = sample.discontinuity,
            ),
            languages = decoder.availableLanguages(),
        )
    }

    internal fun shouldRenderB62(type: Int, captionsEnabled: Boolean): Boolean? = when (type) {
        NativeCaptionDecoder.TYPE_CAPTION -> captionsEnabled
        NativeCaptionDecoder.TYPE_SUPERIMPOSE -> true
        else -> null
    }
}
