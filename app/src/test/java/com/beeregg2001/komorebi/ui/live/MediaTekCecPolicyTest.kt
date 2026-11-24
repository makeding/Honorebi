package com.beeregg2001.komorebi.ui.live

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaTekCecPolicyTest {
    @Test fun onlyMediaTekCecChildrenUsePassthroughKeys() {
        assertTrue(usesMediaTekCec("com.mediatek.tis", true, "HDMI1"))
        assertFalse(usesMediaTekCec("com.mediatek.tis", true, null))
        assertFalse(usesMediaTekCec("com.mediatek.tis", false, "HDMI1"))
        assertFalse(usesMediaTekCec("other.vendor", true, "HDMI1"))
    }
}
