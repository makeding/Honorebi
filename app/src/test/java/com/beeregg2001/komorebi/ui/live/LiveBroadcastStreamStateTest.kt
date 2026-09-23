package com.beeregg2001.komorebi.ui.live

import org.junit.Assert.assertEquals
import org.junit.Test

class LiveBroadcastStreamStateTest {
    @Test fun serverRestartRequiresFreshMediaEvenWithoutPlayerError() {
        val state = LiveBroadcastStreamState()
        assertEquals(LiveBroadcastStreamAction.PAUSE, state.onStatus("Restart", false))
        assertEquals(LiveBroadcastStreamAction.PAUSE, state.onStatus("Standby", false))
        assertEquals(LiveBroadcastStreamAction.REOPEN_AFTER_RESTART, state.onStatus("ONAir", false))
        assertEquals(LiveBroadcastStreamAction.PLAY, state.onStatus("ONAir", false))
    }
    @Test fun duplicateOnAirDoesNotRebuildButEndedOrErroredMediaDoes() {
        val state = LiveBroadcastStreamState()
        repeat(3) { assertEquals(LiveBroadcastStreamAction.PLAY, state.onStatus("ONAir", false)) }
        assertEquals(LiveBroadcastStreamAction.RECOVER_INVALID_MEDIA, state.onStatus("ONAir", true))
    }
    @Test fun restartIsSlotAndGenerationLocal() {
        val main = LiveBroadcastStreamState()
        val dual = LiveBroadcastStreamState()
        main.onStatus("Restart", false)
        assertEquals(LiveBroadcastStreamAction.PLAY, dual.onStatus("ONAir", false))
        assertEquals(LiveBroadcastStreamAction.REOPEN_AFTER_RESTART, main.onStatus("ONAir", false))
        assertEquals(LiveBroadcastStreamAction.PLAY, LiveBroadcastStreamState().onStatus("ONAir", false))
    }

    @Test fun establishedOfflineRequestsOneRecoveryButInitialOfflineDoesNot() {
        val state = LiveBroadcastStreamState()
        assertEquals(LiveBroadcastStreamAction.PAUSE, state.onStatus("Offline", false))
        assertEquals(LiveBroadcastStreamAction.PAUSE, state.onStatus("Standby", false))
        assertEquals(LiveBroadcastStreamAction.PLAY, state.onStatus("ONAir", false))
        assertEquals(LiveBroadcastStreamAction.RECOVER_OFFLINE, state.onStatus("Offline", false))
        assertEquals(LiveBroadcastStreamAction.PAUSE, state.onStatus("Offline", false))
        assertEquals(LiveBroadcastStreamAction.PAUSE, state.onStatus("Standby", false))
        assertEquals(LiveBroadcastStreamAction.PLAY, state.onStatus("ONAir", false))
        assertEquals(LiveBroadcastStreamAction.RECOVER_OFFLINE, state.onStatus("Offline", false))
    }

    @Test fun offlineRecoveryDoesNotAffectTheOtherSlotOrNewGeneration() {
        val main = LiveBroadcastStreamState()
        val dual = LiveBroadcastStreamState()
        main.onStatus("ONAir", false)
        dual.onStatus("ONAir", false)
        assertEquals(LiveBroadcastStreamAction.RECOVER_OFFLINE, main.onStatus("Offline", false))
        assertEquals(LiveBroadcastStreamAction.PLAY, dual.onStatus("ONAir", false))
        assertEquals(LiveBroadcastStreamAction.PAUSE, LiveBroadcastStreamState().onStatus("Offline", false))
    }
}
