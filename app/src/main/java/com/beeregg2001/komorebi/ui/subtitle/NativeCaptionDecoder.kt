package com.beeregg2001.komorebi.ui.subtitle

import android.util.Log
import com.beeregg2001.komorebi.NativeLib

class NativeCaptionDecoder(
    private val nativeLib: NativeLib = NativeLib()
) : AutoCloseable {
    private var handle: Long = nativeLib.openCaptionDecoder()

    fun decode(data: ByteArray, ptsMs: Long): NativeCaptionCue? {
        val activeHandle = handle
        if (activeHandle == 0L) return null
        return try {
            nativeLib.decodeCaption(activeHandle, data, ptsMs)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to decode ARIB caption", e)
            null
        }
    }

    fun flush() {
        val activeHandle = handle
        if (activeHandle != 0L) nativeLib.flushCaptionDecoder(activeHandle)
    }

    override fun close() {
        val activeHandle = handle
        handle = 0L
        if (activeHandle != 0L) nativeLib.closeCaptionDecoder(activeHandle)
    }

    companion object {
        private const val TAG = "NativeCaptionDecoder"
    }
}
