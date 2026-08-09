package com.beeregg2001.komorebi.ui.video.player.policy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class RecordedPlayerConstructionKeyTest {
    private val baseline = RecordedPlayerConstructionKey(
        programId = 42,
        isRecordingChasePlayback = false,
        isRawMmtsPlayback = false,
        isOriginalMpegTsPlayback = false,
        isEdcbDirect = false,
        tsreadexServiceId = 101,
        chaseProgramWindowDurationUs = -1L,
        enableHdrToSdrToneMapping = false,
    )

    @Test
    fun metadataHydration_doesNotNeedAConstructionField() {
        // Reported duration/title/path are intentionally not constructor
        // inputs, so hydrating them cannot change equality or release player.
        assertEquals(baseline, baseline.copy())
    }

    @Test
    fun sourceAndExtractorChanges_requireNewPlayer() {
        assertNotEquals(baseline, baseline.copy(isRecordingChasePlayback = true))
        assertNotEquals(baseline, baseline.copy(isRawMmtsPlayback = true))
        assertNotEquals(baseline, baseline.copy(isOriginalMpegTsPlayback = true))
        assertNotEquals(baseline, baseline.copy(isEdcbDirect = true))
        assertNotEquals(baseline, baseline.copy(tsreadexServiceId = 102))
        assertNotEquals(baseline, baseline.copy(chaseProgramWindowDurationUs = 3_600_000_000L))
    }

    @Test
    fun rendererChange_requiresNewPlayer() {
        assertNotEquals(baseline, baseline.copy(enableHdrToSdrToneMapping = true))
    }
}
