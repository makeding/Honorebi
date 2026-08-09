package com.beeregg2001.komorebi.ui.video.player.policy

/** Guards proportional byte seeking, which is invalid for EDCB relay streams. */
object RecordedByteSeekPolicy {
    fun shouldWrapTsSeekMap(
        isEdcbDirect: Boolean,
        isOriginalMpegTsPlayback: Boolean,
        durationUs: Long,
    ): Boolean = !isEdcbDirect && isOriginalMpegTsPlayback && durationUs > 0L

    fun isEstimatedByteSeekable(
        sourceLengthBytes: Long,
        durationUs: Long,
    ): Boolean = sourceLengthBytes > 0L && durationUs > 0L
}
