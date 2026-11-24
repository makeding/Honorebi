package com.beeregg2001.komorebi.common

import com.beeregg2001.komorebi.data.model.Channel
import com.beeregg2001.komorebi.data.model.LiveRowState
import com.beeregg2001.komorebi.data.model.UiChannelState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class OmakeFeaturesTest {

    private fun testChannel(
        id: String,
        name: String,
        force: Int? = null,
        subchannel: Boolean = false,
        watchable: Boolean = true,
    ) = Channel(
        id = id,
        displayChannelId = id,
        name = name,
        channelNumber = "011",
        type = "GR",
        isWatchable = watchable,
        is_subchannel = subchannel,
        isDisplay = true,
        programPresent = null,
        programFollowing = null,
        jikkyoForce = force,
    )

    @Test
    fun lateNightWatchMessage_onlyTriggersBetween2And4Am() {
        val zone = ZoneId.of("Asia/Tokyo")
        assertNull(lateNightWatchMessage(ZonedDateTime.of(2026, 11, 24, 1, 59, 0, 0, zone)))
        assertNotNull(lateNightWatchMessage(ZonedDateTime.of(2026, 11, 24, 2, 0, 0, 0, zone)))
        assertNotNull(lateNightWatchMessage(ZonedDateTime.of(2026, 11, 24, 3, 30, 0, 0, zone)))
        assertNotNull(lateNightWatchMessage(ZonedDateTime.of(2026, 11, 24, 4, 59, 0, 0, zone)))
        assertNull(lateNightWatchMessage(ZonedDateTime.of(2026, 11, 24, 5, 0, 0, 0, zone)))
    }

    @Test
    fun maskSpoilerTitle_masksQuotedAndEpisodeSubtitlesForUnwatchedOnly() {
        assertEquals(
            "作品名 第12話「██████」",
            maskSpoilerTitle("作品名 第12話「主人公死す！」", enabled = true, isWatched = false),
        )
        assertEquals(
            "作品名 第12話（ネタバレ防止中）",
            maskSpoilerTitle("作品名 第12話 衝撃の最終回", enabled = true, isWatched = false),
        )
        assertEquals(
            "作品名 第12話「主人公死す！」",
            maskSpoilerTitle("作品名 第12話「主人公死す！」", enabled = true, isWatched = true),
        )
        assertEquals(
            "作品名 第12話「主人公死す！」",
            maskSpoilerTitle("作品名 第12話「主人公死す！」", enabled = false, isWatched = false),
        )
    }

    @Test
    fun pickCmEscapeChannel_picksHottestOtherWatchableMainChannel() {
        val current = testChannel("gr1", "現在ch", force = 900)
        val sub = testChannel("gr2s", "サブch", force = 800, subchannel = true)
        val hot = testChannel("gr3", "人気ch", force = 650)
        val cool = testChannel("gr4", "静かch", force = 120)

        val picked = pickCmEscapeChannel(listOf(current, sub, hot, cool), current.id)
        assertEquals("gr3", picked?.id)
    }

    @Test
    fun detectJikkyoFestival_respectsThresholdCurrentChannelAndCooldown() {
        val ch1 = testChannel("gr1", "祭りch", force = 720)
        val ch2 = testChannel("gr2", "通常ch", force = 200)
        val rows = listOf(
            LiveRowState(
                genreId = "GR",
                genreLabel = "地デジ",
                channels = listOf(
                    UiChannelState(
                        channel = ch1,
                        displayChannelId = ch1.displayChannelId,
                        name = ch1.name,
                        programTitle = "番組1",
                        progress = 0.5f,
                        hasProgram = true,
                        jikkyoForce = 720,
                    ),
                    UiChannelState(
                        channel = ch2,
                        displayChannelId = ch2.displayChannelId,
                        name = ch2.name,
                        programTitle = "番組2",
                        progress = 0.5f,
                        hasProgram = true,
                        jikkyoForce = 200,
                    ),
                ),
            ),
        )

        val alert = detectJikkyoFestival(rows, currentChannelId = null, alertedAtMillis = emptyMap(), nowMillis = 100_000L)
        assertEquals("gr1", alert?.channel?.id)
        assertEquals(720, alert?.force)

        // Already watching that channel -> no alert
        assertNull(detectJikkyoFestival(rows, currentChannelId = "gr1", alertedAtMillis = emptyMap(), nowMillis = 100_000L))

        // Within cooldown -> no alert
        assertNull(detectJikkyoFestival(rows, currentChannelId = null, alertedAtMillis = mapOf("gr1" to 90_000L), nowMillis = 100_000L))
    }
}
