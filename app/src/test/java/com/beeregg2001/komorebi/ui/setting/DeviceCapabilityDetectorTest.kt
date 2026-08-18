package com.beeregg2001.komorebi.ui.setting

import android.view.Display
import org.junit.Assert.assertEquals
import org.junit.Test

class DeviceCapabilityDetectorTest {
    @Test
    fun labelsKnownHdrTypesOnceAndIgnoresUnknownTypes() {
        assertEquals(
            listOf("HLG", "HDR10", "HDR10+", "Dolby Vision"),
            DeviceCapabilityDetector.hdrTypeLabels(
                intArrayOf(
                    Display.HdrCapabilities.HDR_TYPE_HLG,
                    Display.HdrCapabilities.HDR_TYPE_HDR10,
                    Display.HdrCapabilities.HDR_TYPE_HDR10_PLUS,
                    Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION,
                    Display.HdrCapabilities.HDR_TYPE_HLG,
                    Int.MAX_VALUE
                )
            )
        )
    }
}
