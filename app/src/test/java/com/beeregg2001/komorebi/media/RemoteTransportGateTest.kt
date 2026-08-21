package com.beeregg2001.komorebi.media

import com.beeregg2001.komorebi.data.remote.HonomiRemoteCommand
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteTransportGateTest {

    @Test
    fun switchingRejectsTransportExceptStop() {
        assertFalse(shouldDispatchRemoteTransport(HonomiRemoteCommand.Play, "Recorded", true))
        assertFalse(shouldDispatchRemoteTransport(HonomiRemoteCommand.Pause, "Recorded", true))
        assertFalse(shouldDispatchRemoteTransport(HonomiRemoteCommand.SeekRelative(15.0), "Recorded", true))
        assertTrue(shouldDispatchRemoteTransport(HonomiRemoteCommand.Stop, "Recorded", true))
    }

    @Test
    fun seekRequiresAReadyRecordedPlayback() {
        assertTrue(shouldDispatchRemoteTransport(HonomiRemoteCommand.SeekRelative(15.0), "Recorded", false))
        assertFalse(shouldDispatchRemoteTransport(HonomiRemoteCommand.SeekRelative(15.0), "Live", false))
    }

    @Test
    fun opensAreOwnedByTheRootScreen() {
        assertFalse(shouldDispatchRemoteTransport(HonomiRemoteCommand.OpenLive("gr011"), "Live", false))
        assertFalse(shouldDispatchRemoteTransport(HonomiRemoteCommand.OpenRecording(42, 0.0), "Recorded", false))
    }
}
