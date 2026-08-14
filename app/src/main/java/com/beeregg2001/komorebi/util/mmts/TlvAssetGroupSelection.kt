package com.beeregg2001.komorebi.util.mmts

internal fun isAudioLayerCompatible(
    videoSelectionLevels: Set<Int>,
    audioSelectionLevels: IntArray
): Boolean =
    videoSelectionLevels.isEmpty() ||
        audioSelectionLevels.isEmpty() ||
        audioSelectionLevels.any(videoSelectionLevels::contains)
