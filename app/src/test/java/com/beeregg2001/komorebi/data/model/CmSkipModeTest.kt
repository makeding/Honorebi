package com.beeregg2001.komorebi.data.model

import org.junit.Assert.assertEquals
import org.junit.Test

class CmSkipModeTest {
    @Test
    fun legacyOnIsNormalizedToAuto() {
        assertEquals(CmSkipMode.AUTO, CmSkipMode.fromPreference("ON"))
    }

    @Test
    fun supportedModesAndUnknownValuesNormalizeSafely() {
        assertEquals(CmSkipMode.OFF, CmSkipMode.fromPreference(null))
        assertEquals(CmSkipMode.OFF, CmSkipMode.fromPreference("UNKNOWN"))
        assertEquals(CmSkipMode.MANUAL, CmSkipMode.fromPreference("MANUAL"))
        assertEquals(CmSkipMode.AUTO, CmSkipMode.fromPreference("AUTO"))
    }

    @Test
    fun modeCycleVisitsAllThreeModes() {
        assertEquals(CmSkipMode.MANUAL, CmSkipMode.OFF.next())
        assertEquals(CmSkipMode.AUTO, CmSkipMode.MANUAL.next())
        assertEquals(CmSkipMode.OFF, CmSkipMode.AUTO.next())
    }
}
