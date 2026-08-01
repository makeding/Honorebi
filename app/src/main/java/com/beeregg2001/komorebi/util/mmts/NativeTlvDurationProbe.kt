package com.beeregg2001.komorebi.util.mmts

import com.beeregg2001.komorebi.NativeLib
import java.io.Closeable

data class TlvDurationProbeRange(
    val requestId: Long,
    val offset: Long,
    val length: Long
)

data class TlvDurationProbeResult(
    val state: Int,
    val failure: Int,
    val durationUs: Long,
    val transferredBytes: Long
)

/** Thin JNI wrapper around tlvdemux::DurationProbe. */
class NativeTlvDurationProbe(
    sourceSize: Long,
    preferredVideoPacketId: Int?
) : Closeable {
    private val nativeLib = NativeLib()
    private var handle = nativeLib.openTlvDurationProbe(
        sourceSize,
        preferredVideoPacketId ?: -1
    )

    init {
        check(handle != 0L) { "Could not start TLV duration probe" }
    }

    fun nextRange(): TlvDurationProbeRange? {
        if (handle == 0L) return null
        val values = nativeLib.getTlvDurationProbeNextRange(handle)
        if (values.size < 3 || values[0] < 0L || values[1] < 0L || values[2] <= 0L) {
            return null
        }
        return TlvDurationProbeRange(values[0], values[1], values[2])
    }

    fun pushRange(
        requestId: Long,
        absoluteOffset: Long,
        data: ByteArray,
        length: Int,
        endOfRange: Boolean
    ): Boolean {
        if (handle == 0L) return false
        return nativeLib.pushTlvDurationProbeRange(
            handle,
            requestId,
            absoluteOffset,
            data,
            length,
            endOfRange
        )
    }

    fun result(): TlvDurationProbeResult {
        if (handle == 0L) return TlvDurationProbeResult(STATE_FAILED, -1, -1L, 0L)
        val values = nativeLib.getTlvDurationProbeResult(handle)
        if (values.size < 4) return TlvDurationProbeResult(STATE_FAILED, -1, -1L, 0L)
        return TlvDurationProbeResult(
            state = values[0].toInt(),
            failure = values[1].toInt(),
            durationUs = values[2],
            transferredBytes = values[3]
        )
    }

    override fun close() {
        if (handle == 0L) return
        nativeLib.closeTlvDurationProbe(handle)
        handle = 0L
    }

    companion object {
        const val STATE_NEED_RANGE = 1
        const val STATE_COMPLETE = 2
        const val STATE_UNKNOWN = 3
        const val STATE_FAILED = 4
        const val STATE_CANCELLED = 5
    }
}
