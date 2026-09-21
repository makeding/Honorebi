package com.beeregg2001.komorebi.media

import com.beeregg2001.komorebi.data.model.CmSkipMode
import com.beeregg2001.komorebi.data.remote.HonomiRemoteCommand
import com.beeregg2001.komorebi.data.remote.HonomiRemoteSkipDirection
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

    /** 進捗バー・チャプター・CM スキップも、相対シークと同じ「録画再生が整っている」条件で揃える。 */
    @Test
    fun progressBarAndChapterCommandsFollowTheSeekGate() {
        val seekCommands = listOf(
            HonomiRemoteCommand.SeekTo(930.0),
            HonomiRemoteCommand.SkipChapter(HonomiRemoteSkipDirection.NEXT),
            HonomiRemoteCommand.SkipChapter(HonomiRemoteSkipDirection.PREVIOUS),
            HonomiRemoteCommand.SkipCM,
        )
        seekCommands.forEach { command ->
            assertTrue(shouldDispatchRemoteTransport(command, "Recorded", false))
            assertFalse(shouldDispatchRemoteTransport(command, "Live", false))
            assertFalse(shouldDispatchRemoteTransport(command, "Idle", false))
            assertFalse(shouldDispatchRemoteTransport(command, "Recorded", true))
        }
    }

    @Test
    fun opensAreOwnedByTheRootScreen() {
        assertFalse(shouldDispatchRemoteTransport(HonomiRemoteCommand.OpenLive("gr011"), "Live", false))
        assertFalse(shouldDispatchRemoteTransport(HonomiRemoteCommand.OpenRecording(42, 0.0), "Recorded", false))
    }

    /** CM スキップ設定はアプリ全体の設定なので、再生セッション側ではなく MainRootScreen が受ける。 */
    @Test
    fun cmSkipModeChangesAreOwnedByTheRootScreen() {
        assertFalse(shouldDispatchRemoteTransport(HonomiRemoteCommand.SetCMSkipMode(CmSkipMode.AUTO), "Recorded", false))
        assertFalse(shouldDispatchRemoteTransport(HonomiRemoteCommand.SetCMSkipMode(CmSkipMode.OFF), "Idle", false))
    }

    @Test
    fun volumeControlsAreAlwaysAvailable() {
        assertTrue(shouldDispatchRemoteTransport(HonomiRemoteCommand.VolumeUp, "Idle", false))
        assertTrue(shouldDispatchRemoteTransport(HonomiRemoteCommand.VolumeDown, "Live", true))
        assertTrue(shouldDispatchRemoteTransport(HonomiRemoteCommand.VolumeMute, "Recorded", true))
    }
}
