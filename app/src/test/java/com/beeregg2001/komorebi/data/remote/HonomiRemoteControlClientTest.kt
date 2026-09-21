package com.beeregg2001.komorebi.data.remote

import com.beeregg2001.komorebi.data.model.CmSkipMode
import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HonomiRemoteControlClientTest {
    private val gson = Gson()

    @Test
    fun parsesRemoteCommands() {
        assertEquals(
            HonomiRemoteCommand.OpenLive("gr011"),
            parseHonomiRemoteCommand(
                gson,
                """{"type":"Command","command":{"type":"OpenLive","display_channel_id":"gr011"}}""",
            ),
        )
        assertEquals(
            HonomiRemoteCommand.OpenRecording(42, 12.5),
            parseHonomiRemoteCommand(
                gson,
                """{"type":"Command","command":{"type":"OpenRecording","recorded_program_id":42,"position_seconds":12.5}}""",
            ),
        )
        assertEquals(
            HonomiRemoteCommand.Pause,
            parseHonomiRemoteCommand(gson, """{"type":"Command","command":{"type":"Pause"}}"""),
        )
        assertEquals(
            HonomiRemoteCommand.VolumeUp,
            parseHonomiRemoteCommand(gson, """{"type":"Command","command":{"type":"VolumeUp"}}"""),
        )
        assertEquals(
            HonomiRemoteCommand.VolumeDown,
            parseHonomiRemoteCommand(gson, """{"type":"Command","command":{"type":"VolumeDown"}}"""),
        )
        assertEquals(
            HonomiRemoteCommand.VolumeMute,
            parseHonomiRemoteCommand(gson, """{"type":"Command","command":{"type":"VolumeMute"}}"""),
        )
        assertNull(parseHonomiRemoteCommand(gson, """{"type":"Unknown"}"""))
    }

    @Test
    fun parsesSeekAndChapterCommands() {
        assertEquals(
            HonomiRemoteCommand.SeekTo(930.25),
            parseHonomiRemoteCommand(
                gson,
                """{"type":"Command","command":{"type":"SeekTo","position_seconds":930.25}}""",
            ),
        )
        assertEquals(
            HonomiRemoteCommand.SkipChapter(HonomiRemoteSkipDirection.NEXT),
            parseHonomiRemoteCommand(
                gson,
                """{"type":"Command","command":{"type":"SkipChapter","direction":"Next"}}""",
            ),
        )
        assertEquals(
            HonomiRemoteCommand.SkipChapter(HonomiRemoteSkipDirection.PREVIOUS),
            parseHonomiRemoteCommand(
                gson,
                """{"type":"Command","command":{"type":"SkipChapter","direction":"Previous"}}""",
            ),
        )
        assertEquals(
            HonomiRemoteCommand.SkipCM,
            parseHonomiRemoteCommand(gson, """{"type":"Command","command":{"type":"SkipCM"}}"""),
        )
        assertEquals(
            HonomiRemoteCommand.SetCMSkipMode(CmSkipMode.AUTO),
            parseHonomiRemoteCommand(
                gson,
                """{"type":"Command","command":{"type":"SetCMSkipMode","mode":"Auto"}}""",
            ),
        )
        assertEquals(
            HonomiRemoteCommand.SetCMSkipMode(CmSkipMode.OFF),
            parseHonomiRemoteCommand(
                gson,
                """{"type":"Command","command":{"type":"SetCMSkipMode","mode":"Off"}}""",
            ),
        )
    }

    /** 壊れた値で意図しないシークや設定変更を起こさないよう、未知の列挙値はコマンドごと捨てる。 */
    @Test
    fun dropsCommandsWithUnknownEnumValues() {
        assertNull(
            parseHonomiRemoteCommand(
                gson,
                """{"type":"Command","command":{"type":"SkipChapter","direction":"Sideways"}}""",
            ),
        )
        assertNull(
            parseHonomiRemoteCommand(
                gson,
                """{"type":"Command","command":{"type":"SetCMSkipMode","mode":"Sometimes"}}""",
            ),
        )
    }

    /** 負の position_seconds はサーバー側の検証を通らないが、受信側でも 0 に丸めて安全側へ倒す。 */
    @Test
    fun clampsNegativeSeekTargetsToZero() {
        assertEquals(
            HonomiRemoteCommand.SeekTo(0.0),
            parseHonomiRemoteCommand(
                gson,
                """{"type":"Command","command":{"type":"SeekTo","position_seconds":-5}}""",
            ),
        )
    }

    @Test
    fun separatesStateRequestsFromRemoteCommands() {
        assertEquals(
            HonomiRemoteServerEvent.RequestState,
            parseHonomiRemoteServerEvent(gson, """{"type":"RequestState"}"""),
        )
        assertEquals(
            HonomiRemoteServerEvent.Command(HonomiRemoteCommand.Pause),
            parseHonomiRemoteServerEvent(
                gson,
                """{"type":"Command","command":{"type":"Pause"}}""",
            ),
        )
        assertNull(parseHonomiRemoteServerEvent(gson, """{"type":"Unknown"}"""))
    }
}
