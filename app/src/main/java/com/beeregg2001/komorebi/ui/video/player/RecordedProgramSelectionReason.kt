package com.beeregg2001.komorebi.ui.video.player

/**
 * Intent behind a recorded-program change requested from the video UI.
 *
 * This intentionally belongs to the player UI rather than MainRoot: the
 * playback host maps it to its session transition contract.
 */
enum class RecordedProgramSelectionReason {
    NextEpisode,
    PreviousEpisode,
    QuickSelect,
}
