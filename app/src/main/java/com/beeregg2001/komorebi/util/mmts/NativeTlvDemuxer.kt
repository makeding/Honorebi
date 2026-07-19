package com.beeregg2001.komorebi.util.mmts

import com.beeregg2001.komorebi.NativeLib
import java.io.Closeable

/**
 * libtlvdemux.so の C++ API を Komorebi の Extractor スレッドから利用するための薄い JNI ラッパー。
 * callback は push()/flush() を呼び出した同じスレッド上で同期的に実行される。
 */
class NativeTlvDemuxer(
    callback: Callback
) : Closeable {

    interface Callback {
        fun onService(contextId: Long, packageId: ByteArray)

        fun onTrack(
            trackId: Long,
            contextId: Long,
            packetId: Int,
            kind: Int,
            codec: Int,
            language: String,
            componentTag: Int,
            timescale: Long
        )

        fun onAccessUnit(
            trackId: Long,
            codec: Int,
            data: ByteArray,
            ptsValue: Long,
            ptsTimescale: Long,
            dtsValue: Long,
            dtsTimescale: Long,
            inputOffset: Long,
            randomAccess: Boolean,
            discontinuity: Boolean
        )

        fun onError(code: Int, inputOffset: Long, recoverable: Boolean, message: String)
    }

    private val nativeLib = NativeLib()
    private var handle = nativeLib.openTlvDemuxer(callback)

    fun push(data: ByteArray, length: Int) {
        check(handle != 0L) { "Native TLV demuxer is closed" }
        nativeLib.pushTlvData(handle, data, length)
    }

    fun flush() {
        if (handle != 0L) nativeLib.flushTlvDemuxer(handle)
    }

    fun reset() {
        if (handle != 0L) nativeLib.resetTlvDemuxer(handle)
    }

    override fun close() {
        if (handle == 0L) return
        nativeLib.closeTlvDemuxer(handle)
        handle = 0L
    }
}
