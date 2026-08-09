package com.beeregg2001.komorebi.ui.live

import com.beeregg2001.komorebi.data.model.Channel
import com.beeregg2001.komorebi.data.model.StreamQuality
import com.beeregg2001.komorebi.data.model.StreamSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class LivePlayerDerivedStateTest {
    @Test
    fun navigationUsesDisplayChannelInstancesAndWrapsAtBothEnds() {
        val first = channel(id = "1", networkId = 1, serviceId = 1)
        val second = channel(id = "2", networkId = 1, serviceId = 2)
        val staleSecond = channel(id = "2", networkId = 1, serviceId = 2)

        val navigation = deriveLiveChannelNavigation(
            selectedChannel = staleSecond,
            groupedChannels = linkedMapOf("GR" to listOf(first, second)),
            lastWatchedChannels = listOf(staleSecond)
        )

        assertSame(second, navigation.currentChannel)
        assertEquals(listOf(second), navigation.recentChannels)
        assertSame(first, navigation.previousChannel)
        assertSame(first, navigation.nextChannel)
    }

    @Test
    fun navigationDoesNotOfferPreviousOrNextForOneOrUnknownChannel() {
        val only = channel(id = "1")

        assertNull(
            deriveLiveChannelNavigation(only, mapOf("GR" to listOf(only)), emptyList()).previousChannel
        )
        assertNull(
            deriveLiveChannelNavigation(channel(id = "missing"), emptyMap(), emptyList()).nextChannel
        )
    }

    @Test
    fun bs4kAlwaysUsesRawMmtsQualitiesWhileOtherChannelsUseBackendQualities() {
        val backendQualities = listOf(StreamQuality("HD", "hd"))

        assertEquals(backendQualities, effectiveLiveQualities(backendQualities, channel(type = "GR")))
        assertTrue(effectiveLiveQualities(backendQualities, channel(type = "BS4K")).all { it.isRawMmts })
    }

    @Test
    fun loadingPresentationPreservesSourceSpecificRulesAndMessages() {
        val konomi = liveLoadingPresentation(
            streamSource = StreamSource.KONOMITV,
            isEdcbDirect = false,
            sseStatus = "Offline",
            sseDetail = "再接続中",
            playerError = null,
            isBuffering = false,
            hasRenderedFirstFrame = true,
            isPlaybackReady = false,
            statusLoadingText = "読み込み中"
        )
        assertTrue(konomi.isVisible)
        assertEquals("再接続中", konomi.message)

        val directEdcb = liveLoadingPresentation(
            streamSource = StreamSource.EDCB,
            isEdcbDirect = true,
            sseStatus = "Standby",
            sseDetail = "待機中",
            playerError = null,
            isBuffering = false,
            hasRenderedFirstFrame = true,
            isPlaybackReady = false,
            statusLoadingText = "読み込み中"
        )
        assertFalse(directEdcb.isVisible)

        val decoding = liveLoadingPresentation(
            streamSource = StreamSource.MIRAKURUN,
            isEdcbDirect = false,
            sseStatus = "Standby",
            sseDetail = "",
            playerError = null,
            isBuffering = false,
            hasRenderedFirstFrame = false,
            isPlaybackReady = true,
            statusLoadingText = "読み込み中"
        )
        assertTrue(decoding.isVisible)
        assertEquals("映像をデコード中...", decoding.message)
    }

    @Test
    fun mediaSessionLoadingIncludesSwitchFirstFrameAndBuffering() {
        assertTrue(isLiveMediaSessionLoading("new", "old", true, false))
        assertTrue(isLiveMediaSessionLoading("same", "same", false, false))
        assertTrue(isLiveMediaSessionLoading("same", "same", true, true))
        assertFalse(isLiveMediaSessionLoading("same", "same", true, false))
    }

    private fun channel(
        id: String = "channel",
        networkId: Long = 1L,
        serviceId: Long = 1L,
        type: String = "GR"
    ) = Channel(
        id = id,
        displayChannelId = id,
        name = id,
        channelNumber = id,
        networkId = networkId,
        serviceId = serviceId,
        type = type,
        isWatchable = true,
        isDisplay = true,
        programPresent = null,
        programFollowing = null,
        remocon_Id = 1
    )
}
