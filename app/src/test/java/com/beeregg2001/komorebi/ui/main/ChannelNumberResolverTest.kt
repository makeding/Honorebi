package com.beeregg2001.komorebi.ui.main

import com.beeregg2001.komorebi.data.model.Channel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChannelNumberResolverTest {

    private fun channel(
        id: String,
        name: String = id,
        type: String = "GR",
        channelNumber: String = "",
        remoconId: Int? = null,
        serviceId: Long? = null,
        isSubchannel: Boolean = false,
        isDisplay: Boolean = true,
    ) = Channel(
        id = id,
        displayChannelId = id,
        name = name,
        channelNumber = channelNumber,
        serviceId = serviceId,
        type = type,
        isWatchable = true,
        is_subchannel = isSubchannel,
        isDisplay = isDisplay,
        programPresent = null,
        programFollowing = null,
        remocon_Id = remoconId,
    )

    @Test
    fun singleAndDoubleDigit_prefersCurrentTypeThenStandardBroadcastOrder() {
        val gr1 = channel(id = "gr011", type = "GR", channelNumber = "011", remoconId = 1)
        val bs1 = channel(id = "bs101", type = "BS", channelNumber = "101", remoconId = 1)
        val cs1 = channel(id = "cs055", type = "CS", channelNumber = "055", remoconId = 1)
        val tif1 = channel(id = "tif:tuner:1", type = "GR", channelNumber = "011", remoconId = 1)
        val all = listOf(tif1, cs1, bs1, gr1)

        assertEquals(gr1, ChannelNumberResolver.resolve("1", all, currentType = null))
        assertEquals(bs1, ChannelNumberResolver.resolve("1", all, currentType = "BS"))
        assertEquals(cs1, ChannelNumberResolver.resolve("1", all, currentType = "CS"))
        // Even when currentType matches TIF's type, broadcast channel ranks before TIF
        assertEquals(gr1, ChannelNumberResolver.resolve("1", all, currentType = "GR"))
        // TIF still resolves when no broadcast channel matches
        assertEquals(tif1, ChannelNumberResolver.resolve("1", listOf(tif1), currentType = null))
    }

    @Test
    fun singleDigit_prefersMainChannelBranchZeroAndDisplayed() {
        val sub = channel(id = "gr012", type = "GR", channelNumber = "012", remoconId = 1, isSubchannel = true)
        val branch1 = channel(id = "gr011-1", type = "GR", channelNumber = "011-1", remoconId = 1)
        val hiddenMain = channel(id = "gr011-hidden", type = "GR", channelNumber = "011-0", remoconId = 1, isDisplay = false)
        val displayedMain = channel(id = "gr011-0", type = "GR", channelNumber = "011-0", remoconId = 1, isDisplay = true)

        assertEquals(
            displayedMain,
            ChannelNumberResolver.resolve("1", listOf(sub, branch1, hiddenMain, displayedMain), currentType = "GR"),
        )
    }

    @Test
    fun singleAndDoubleDigit_fallsBackToChannelNumberWhenRemoconMissing() {
        val ch12 = channel(id = "cs12", type = "CS", channelNumber = "12", remoconId = 0)
        assertEquals(ch12, ChannelNumberResolver.resolve("12", listOf(ch12), currentType = null))
    }

    @Test
    fun zeroAlone_returnsNull() {
        val ch = channel(id = "gr011", type = "GR", channelNumber = "011", remoconId = 0, serviceId = 0)
        assertNull(ChannelNumberResolver.resolve("0", listOf(ch), currentType = "GR"))
        assertNull(ChannelNumberResolver.resolve("00", listOf(ch), currentType = "GR"))
        assertNull(ChannelNumberResolver.resolve("000", listOf(ch), currentType = "GR"))
    }

    @Test
    fun threeDigit_matchesZeroPaddedAndPrefixedNumbersAndBranchPriority() {
        val branch1 = channel(id = "gr011-1", type = "GR", channelNumber = "011-1", remoconId = 1)
        val branch0 = channel(id = "gr011-0", type = "GR", channelNumber = "011-0", remoconId = 1)
        val bsPrefixed = channel(id = "bs101", type = "BS", channelNumber = "BS101", remoconId = 1)
        val hiddenSub = channel(
            id = "gr012",
            type = "GR",
            channelNumber = "012",
            remoconId = 1,
            isSubchannel = true,
            isDisplay = false,
        )
        val all = listOf(branch1, branch0, bsPrefixed, hiddenSub)

        assertEquals(branch0, ChannelNumberResolver.resolve("011", all, currentType = null))
        assertEquals(bsPrefixed, ChannelNumberResolver.resolve("101", all, currentType = null))
        // Explicit 3-digit may tune a hidden sub-channel
        assertEquals(hiddenSub, ChannelNumberResolver.resolve("012", all, currentType = null))
    }

    @Test
    fun threeDigit_fallsBackToServiceId() {
        val ch = channel(id = "bs236", type = "BS", channelNumber = "ANIMAX", serviceId = 236L)
        assertEquals(ch, ChannelNumberResolver.resolve("236", listOf(ch), currentType = null))
    }

    @Test
    fun tifPassthroughInput_isAlwaysSkipped() {
        val hdmi1 = channel(id = "tif:input:hdmi1", type = "INPUT", channelNumber = "HDMI1", remoconId = 1, serviceId = 101L)
        assertNull(ChannelNumberResolver.resolve("1", listOf(hdmi1), currentType = null))
        assertNull(ChannelNumberResolver.resolve("001", listOf(hdmi1), currentType = null))
        assertNull(ChannelNumberResolver.resolve("101", listOf(hdmi1), currentType = null))
    }

    @Test
    fun invalidInputs_returnNull() {
        val ch = channel(id = "gr011", type = "GR", channelNumber = "011", remoconId = 1)
        assertNull(ChannelNumberResolver.resolve("", listOf(ch), currentType = null))
        assertNull(ChannelNumberResolver.resolve("   ", listOf(ch), currentType = null))
        assertNull(ChannelNumberResolver.resolve("0111", listOf(ch), currentType = null))
        assertNull(ChannelNumberResolver.resolve("1a", listOf(ch), currentType = null))
    }
}
