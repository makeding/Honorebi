package com.beeregg2001.komorebi.ui.live

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TifLiveOsdTest {
    @Test
    fun japaneseTunerChannelsUseLiveOsd() {
        for (type in listOf("GR", "BS", "CS")) {
            assertTrue(usesLiveOsd("tif:channel:1", type))
        }
        assertFalse(usesLiveOsd("tif:channel:1", "TV"))
        assertFalse(usesLiveOsd("tif:input:1", "INPUT"))
    }
}
