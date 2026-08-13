package com.beeregg2001.komorebi.data

import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsRepositoryStartupTabTest {

    @Test
    fun retiredBaseballStartupTabFallsBackToHome() {
        assertEquals("ホーム", normalizeStartupTab("プロ野球"))
    }

    @Test
    fun supportedAndUnsetStartupTabsRemainStable() {
        assertEquals("ライブ", normalizeStartupTab("ライブ"))
        assertEquals("ホーム", normalizeStartupTab(null))
    }

    @Test
    fun legacyCmSkipOnIsReadAsAuto() {
        assertEquals("AUTO", normalizeCmSkipMode("ON"))
        assertEquals("MANUAL", normalizeCmSkipMode("MANUAL"))
        assertEquals("OFF", normalizeCmSkipMode(null))
    }
}
