package com.beeregg2001.komorebi.util.mmts

import com.beeregg2001.komorebi.NativeLib
import java.io.Closeable

/**
 * libaribtlv の demux API と tlvdemux の playback helper を Komorebi の Extractor
 * スレッドから利用するための薄い JNI ラッパー。callback は push()/flush() を呼び出した
 * 同じスレッド上で同期的に実行される。
 */
class NativeTlvDemuxer(
    callback: Callback,
    preferredVideoPacketId: Int?,
    buildRecordingIndex: Boolean,
    exposeAllVideoTracks: Boolean
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
            timescale: Long,
            assetGroupIdentifications: IntArray,
            assetGroupSelectionLevels: IntArray,
            audioChannelLayout: Int,
            audioSampleRate: Int,
            audioMainComponent: Boolean,
            subtitleType: Int,
            subtitleOperationMode: Int,
            subtitleTimingMode: Int
        )

        fun onAccessUnit(
            trackId: Long,
            codec: Int,
            data: ByteArray,
            dataLength: Int,
            ptsValue: Long,
            ptsTimescale: Long,
            dtsValue: Long,
            dtsTimescale: Long,
            inputOffset: Long,
            mpuSequenceNumber: Long,
            subtitleReferenceStartPtsValue: Long,
            subtitleReferenceStartPtsTimescale: Long,
            subtitleResourceIndices: IntArray,
            subtitleResourceTypes: IntArray,
            subtitleResourceData: Array<ByteArray>,
            randomAccess: Boolean,
            discontinuity: Boolean
        )

        fun onBroadcastClock(
            mediaTimeValue: Long,
            mediaTimeTimescale: Long,
            broadcastTimeValue: Long,
            broadcastTimeTimescale: Long,
            inputOffset: Long,
            discontinuity: Boolean
        )

        fun onPlaybackDamage(
            trackId: Long,
            startTimeUs: Long,
            endTimeUs: Long,
            recoveryTimeUs: Long,
            startInputOffset: Long,
            endInputOffset: Long,
            recoveryInputOffset: Long,
            recoveryRestartOffset: Long,
            severity: Int,
            action: Int
        )

        fun onEventInfo(
            contextId: Long,
            tableId: Int,
            currentNext: Boolean,
            sectionNumber: Int,
            serviceId: Int,
            tlvStreamId: Int,
            originalNetworkId: Int,
            eventId: Int,
            startTimeUnixMilliseconds: Long,
            durationSeconds: Long,
            runningStatus: Int,
            freeCaMode: Boolean,
            language: String,
            title: String,
            description: String
        )

        fun onLayoutConfiguration(contextId: Long, backgroundColorRgb: Int)

        fun onApplicationState(
            contextId: Long,
            applicationType: Int,
            organizationId: Int,
            applicationId: Long,
            controlCode: Int,
            applicationPriority: Int,
            entryPath: String,
            transportUrls: Array<String>,
            collectionState: Int,
            resourceCount: Long,
            entryReady: Boolean
        )

        fun onApplicationResource(
            contextId: Long,
            path: String,
            contentType: String,
            data: ByteArray,
            version: Int
        )

        fun onApplicationResourcesReset()

        fun onError(code: Int, inputOffset: Long, recoverable: Boolean, message: String)
    }

    private val nativeLib = NativeLib()
    private var handle = nativeLib.openTlvDemuxer(
        callback,
        preferredVideoPacketId ?: -1,
        buildRecordingIndex,
        exposeAllVideoTracks
    )

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

    fun reposition(inputOffset: Long) {
        if (handle != 0L) nativeLib.repositionTlvDemuxer(handle, inputOffset)
    }

    fun getSeekPoints(targetUs: Long): LongArray {
        if (handle == 0L) return longArrayOf(-1L, -1L, -1L, -1L)
        return nativeLib.getTlvSeekPoints(handle, targetUs)
    }

    override fun close() {
        if (handle == 0L) return
        nativeLib.closeTlvDemuxer(handle)
        handle = 0L
    }

    companion object {
        const val PLAYBACK_DAMAGE_WARNING = 0
        const val PLAYBACK_DAMAGE_SEVERE = 1
        const val PLAYBACK_RECOVERY_NONE = 0
        const val PLAYBACK_RECOVERY_SEEK = 1
        const val PLAYBACK_RECOVERY_WAIT_FOR_RECOVERY = 2
    }
}
