package com.beeregg2001.komorebi

import com.beeregg2001.komorebi.ui.subtitle.NativeCaptionCue
import com.beeregg2001.komorebi.util.mmts.NativeTlvDemuxer
import java.nio.ByteBuffer

class NativeLib {
    companion object {
        init {
            System.loadLibrary("komorebi-native")
        }
    }

    // フィルタインスタンスの生成（引数を配列で渡す）
    external fun openFilter(args: Array<String>): Long

    // Direct ByteBuffer を使った高速処理
    external fun processDataBuffer(
        handle: Long,
        inputBuffer: ByteBuffer,
        inputLength: Int,
        outputBuffer: ByteBuffer
    ): Int

    // インスタンスの破棄
    external fun closeFilter(handle: Long)

    external fun pushDataBuffer(handle: Long, inputBuffer: ByteBuffer, inputLength: Int)
    external fun popDataBuffer(handle: Long, outputBuffer: ByteBuffer, maxLen: Int): Int

    external fun openCaptionDecoder(captionType: Int): Long
    external fun decodeCaption(handle: Long, data: ByteArray, ptsMs: Long): NativeCaptionCue?
    external fun decodeB62Captions(
        handle: Long,
        data: ByteArray,
        ptsMs: Long,
        operationMode: Int,
        timingMode: Int,
        referenceStartPtsMs: Long,
        resourceScopeId: Long,
        resourceIndices: IntArray,
        resourceTypes: IntArray,
        resourceData: Array<ByteArray>,
        discontinuity: Boolean
    ): Array<NativeCaptionCue>
    external fun getCaptionLanguageCodes(handle: Long): IntArray
    external fun switchCaptionLanguage(handle: Long, languageId: Int)
    external fun flushCaptionDecoder(handle: Long)
    external fun closeCaptionDecoder(handle: Long)

    external fun openTlvDemuxer(
        callback: NativeTlvDemuxer.Callback,
        preferredVideoPacketId: Int,
        buildRecordingIndex: Boolean,
        exposeAllVideoTracks: Boolean
    ): Long
    external fun pushTlvData(handle: Long, data: ByteArray, length: Int)
    external fun flushTlvDemuxer(handle: Long)
    external fun resetTlvDemuxer(handle: Long)
    external fun repositionTlvDemuxer(handle: Long, inputOffset: Long)
    external fun getTlvSeekPoints(handle: Long, targetUs: Long): LongArray
    external fun closeTlvDemuxer(handle: Long)

    external fun openTlvDurationProbe(sourceSize: Long, preferredVideoPacketId: Int): Long
    external fun getTlvDurationProbeNextRange(handle: Long): LongArray
    external fun pushTlvDurationProbeRange(
        handle: Long,
        requestId: Long,
        absoluteOffset: Long,
        data: ByteArray,
        length: Int,
        endOfRange: Boolean
    ): Boolean
    external fun getTlvDurationProbeResult(handle: Long): LongArray
    external fun closeTlvDurationProbe(handle: Long)
}
