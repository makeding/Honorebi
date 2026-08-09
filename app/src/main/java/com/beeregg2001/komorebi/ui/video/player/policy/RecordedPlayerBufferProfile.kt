package com.beeregg2001.komorebi.ui.video.player.policy

/**
 * Load-control values for a recorded-player source. Raw MMT takes precedence
 * over chase playback, matching the extractor/source selection order.
 */
data class RecordedPlayerBufferProfile(
    val targetBufferBytes: Int,
    val minBufferMs: Int,
    val maxBufferMs: Int,
    val bufferForPlaybackMs: Int,
    val bufferForPlaybackAfterRebufferMs: Int,
) {
    companion object {
        fun select(
            isRawMmtsPlayback: Boolean,
            isRecordingChasePlayback: Boolean,
        ): RecordedPlayerBufferProfile = when {
            isRawMmtsPlayback -> RawMmts
            isRecordingChasePlayback -> Chase
            else -> Recorded
        }

        val Recorded = RecordedPlayerBufferProfile(
            targetBufferBytes = 64 * 1024 * 1024,
            minBufferMs = 30_000,
            maxBufferMs = 90_000,
            bufferForPlaybackMs = 4_000,
            bufferForPlaybackAfterRebufferMs = 8_000,
        )

        val RawMmts = RecordedPlayerBufferProfile(
            targetBufferBytes = 32 * 1024 * 1024,
            minBufferMs = 5_000,
            maxBufferMs = 10_000,
            bufferForPlaybackMs = 1_000,
            bufferForPlaybackAfterRebufferMs = 2_000,
        )

        val Chase = RecordedPlayerBufferProfile(
            targetBufferBytes = 48 * 1024 * 1024,
            minBufferMs = 15_000,
            maxBufferMs = 45_000,
            bufferForPlaybackMs = 8_000,
            bufferForPlaybackAfterRebufferMs = 15_000,
        )
    }
}
