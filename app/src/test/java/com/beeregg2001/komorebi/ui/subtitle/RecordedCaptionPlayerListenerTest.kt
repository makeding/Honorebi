package com.beeregg2001.komorebi.ui.subtitle

import androidx.media3.common.C
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import org.junit.Assert.assertEquals
import org.junit.Test

class RecordedCaptionPlayerListenerTest {
    @Test
    fun `pause speed and seek callbacks each wake caption scheduling`() {
        var invalidations = 0
        val listener = RecordedCaptionPlayerListener { invalidations++ }
        val position = Player.PositionInfo(
            null,
            0,
            null,
            0,
            0L,
            0L,
            C.INDEX_UNSET,
            C.INDEX_UNSET,
        )

        listener.onIsPlayingChanged(false)
        listener.onPlaybackParametersChanged(PlaybackParameters(2f))
        listener.onPositionDiscontinuity(position, position, Player.DISCONTINUITY_REASON_SEEK)

        assertEquals(3, invalidations)
    }
}
