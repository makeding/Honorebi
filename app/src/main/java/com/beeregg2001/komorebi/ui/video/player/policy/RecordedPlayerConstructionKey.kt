package com.beeregg2001.komorebi.ui.video.player.policy

/**
 * Immutable inputs which change ExoPlayer's renderer, source, extractor, or
 * buffer construction. Metadata such as title and reported duration is
 * deliberately absent: hydrating it must not release a playing instance.
 */
data class RecordedPlayerConstructionKey(
    val programId: Int?,
    val isRecordingChasePlayback: Boolean,
    val isRawMmtsPlayback: Boolean,
    val isOriginalMpegTsPlayback: Boolean,
    val isEdcbDirect: Boolean,
    val tsreadexServiceId: Int,
    val chaseProgramWindowDurationUs: Long,
    val enableHdrToSdrToneMapping: Boolean,
)
