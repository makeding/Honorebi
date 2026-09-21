package com.beeregg2001.komorebi.ui.live

import org.junit.Assert.assertEquals
import org.junit.Test

class LiveBroadcastStreamStateTest {
    @Test fun serverRestartRequiresFreshMediaEvenWithoutPlayerError() {
        val state = LiveBroadcastStreamState()
        assertEquals(LiveBroadcastStreamAction.PAUSE, state.onStatus("Restart", false))
        assertEquals(LiveBroadcastStreamAction.PAUSE, state.onStatus("Standby", false))
        assertEquals(LiveBroadcastStreamAction.REOPEN, state.onStatus("ONAir", false))
        assertEquals(LiveBroadcastStreamAction.PLAY, state.onStatus("ONAir", false))
    }
    @Test fun duplicateOnAirDoesNotRebuildButEndedOrErroredMediaDoes() {
        val state = LiveBroadcastStreamState()
        repeat(3) { assertEquals(LiveBroadcastStreamAction.PLAY, state.onStatus("ONAir", false)) }
        assertEquals(LiveBroadcastStreamAction.REOPEN, state.onStatus("ONAir", true))
    }
    @Test fun restartIsSlotAndGenerationLocal() {
        val main = LiveBroadcastStreamState()
        val dual = LiveBroadcastStreamState()
        main.onStatus("Restart", false)
        assertEquals(LiveBroadcastStreamAction.PLAY, dual.onStatus("ONAir", false))
        assertEquals(LiveBroadcastStreamAction.REOPEN, main.onStatus("ONAir", false))
        assertEquals(LiveBroadcastStreamAction.PLAY, LiveBroadcastStreamState().onStatus("ONAir", false))
    }
}
