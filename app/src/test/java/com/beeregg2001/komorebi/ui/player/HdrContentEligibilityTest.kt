@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package com.beeregg2001.komorebi.ui.player

import androidx.media3.common.C
import androidx.media3.common.ColorInfo
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.TrackGroup
import androidx.media3.common.Tracks
import org.junit.Assert.*
import org.junit.Test

@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(application = android.app.Application::class, sdk = [28])
class HdrContentEligibilityTest {
    private fun format(width: Int, height: Int, transfer: Int) = Format.Builder()
        .setSampleMimeType(MimeTypes.VIDEO_H265).setWidth(width).setHeight(height)
        .setColorInfo(ColorInfo.Builder().setColorTransfer(transfer).build()).build()

    @Test fun onlyUhdHlgIsEligible() {
        assertTrue(isHlgToneMappingContent(format(3840, 2160, C.COLOR_TRANSFER_HLG)))
        for (transfer in listOf(C.COLOR_TRANSFER_HLG, C.COLOR_TRANSFER_SDR)) {
            assertFalse(isHlgToneMappingContent(format(1920, 1080, transfer)))
        }
        for (transfer in listOf(C.COLOR_TRANSFER_SDR, C.COLOR_TRANSFER_ST2084, Format.NO_VALUE)) {
            assertFalse(isHlgToneMappingContent(format(3840, 2160, transfer)))
        }
        assertFalse(isHlgToneMappingContent(Format.Builder().build()))
    }

    @Test fun unselectedHlgDoesNotMakeSelectedSdrEligible() {
        val group = Tracks.Group(
            TrackGroup(format(3840, 2160, C.COLOR_TRANSFER_HLG), format(1920, 1080, C.COLOR_TRANSFER_SDR)),
            false, intArrayOf(C.FORMAT_HANDLED, C.FORMAT_HANDLED), booleanArrayOf(false, true)
        )
        assertEquals(false, selectedHlgToneMappingContent(Tracks(listOf(group))))
        assertNull(selectedHlgToneMappingContent(Tracks.EMPTY))
    }
}
