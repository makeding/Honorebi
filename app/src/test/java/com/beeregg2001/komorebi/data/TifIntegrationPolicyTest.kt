package com.beeregg2001.komorebi.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TifIntegrationPolicyTest {

    @Test
    fun canDisableIsFalseOnlyForTifBackend() {
        assertFalse(TifIntegrationPolicy.canDisable("TIF"))
        assertTrue(TifIntegrationPolicy.canDisable("KONOMITV"))
        assertTrue(TifIntegrationPolicy.canDisable("EDCB"))
        assertTrue(TifIntegrationPolicy.canDisable("MIRAKURUN_ONLY"))
        assertTrue(TifIntegrationPolicy.canDisable("EPGSTATION"))
    }

    @Test
    fun isActiveTruthTable() {
        // Backend TIF is always active regardless of enabled/supported flags
        assertTrue(TifIntegrationPolicy.isActive("TIF", enabled = true, supported = true))
        assertTrue(TifIntegrationPolicy.isActive("TIF", enabled = false, supported = true))
        assertTrue(TifIntegrationPolicy.isActive("TIF", enabled = true, supported = false))
        assertTrue(TifIntegrationPolicy.isActive("TIF", enabled = false, supported = false))

        // Non-TIF backend requires both supported && enabled
        assertTrue(TifIntegrationPolicy.isActive("KONOMITV", enabled = true, supported = true))
        assertFalse(TifIntegrationPolicy.isActive("KONOMITV", enabled = false, supported = true))
        assertFalse(TifIntegrationPolicy.isActive("KONOMITV", enabled = true, supported = false))
        assertFalse(TifIntegrationPolicy.isActive("KONOMITV", enabled = false, supported = false))
    }
}
