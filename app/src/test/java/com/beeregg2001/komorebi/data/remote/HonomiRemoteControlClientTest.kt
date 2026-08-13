package com.beeregg2001.komorebi.data.remote

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
        assertNull(parseHonomiRemoteCommand(gson, """{"type":"Unknown"}"""))
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
