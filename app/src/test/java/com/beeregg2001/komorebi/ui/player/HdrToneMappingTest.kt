package com.beeregg2001.komorebi.ui.player

import android.media.MediaFormat
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HdrToneMappingTest {
    @Test
    fun acceptsOnlyTheRequestedSdrTransfer() {
        assertTrue(isHdrToneMappingRequestAccepted(MediaFormat.COLOR_TRANSFER_SDR_VIDEO))
        assertFalse(isHdrToneMappingRequestAccepted(null))
        assertFalse(isHdrToneMappingRequestAccepted(0))
        assertFalse(isHdrToneMappingRequestAccepted(MediaFormat.COLOR_TRANSFER_HLG))
    }

    @Test
    fun findsARejectedRequestThroughWrappedCauses() {
        val rejection = HdrToneMappingRejectedException("decoder", 0)
        assertTrue(HdrToneMapping.rejectionCause(IllegalStateException(rejection)) === rejection)
    }
}
