package com.beeregg2001.komorebi.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HdrToneMappingTest {
    @Test
    fun parsesPackedLutLayout() {
        val data = IntArray(3 + 8 * 4).apply {
            this[0] = 2
            this[1] = 8
            this[2] = 4
        }

        assertEquals(HlgSdrLutLayout(2, 8, 4), parseHlgSdrLutLayout(data))
    }

    @Test
    fun findsARejectedRequestThroughWrappedCauses() {
        val rejection = HdrToneMappingRejectedException("GL extension unavailable")
        assertTrue(HdrToneMapping.rejectionCause(IllegalStateException(rejection)) === rejection)
    }

    @Test
    fun findsMedia3LutFailureByStableCode() {
        val rejection = IllegalStateException("$ERROR_CODE: only HLG input is supported")
        assertTrue(HdrToneMapping.rejectionCause(rejection) === rejection)
    }
}
