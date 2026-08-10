package com.beeregg2001.komorebi.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LauncherAppRepositoryTest {

    @Test
    fun firstLauncherResourceId_usesFirstNonZeroCandidate() {
        assertEquals(42, firstLauncherResourceId(0, 42, 99))
        assertEquals(0, firstLauncherResourceId(0, 0))
    }

    @Test
    fun launcherResourceUri_buildsCoilCompatibleAndroidResourceModel() {
        assertEquals(
            "android.resource://tv.example.app/123",
            launcherResourceUri("tv.example.app", 123),
        )
        assertNull(launcherResourceUri("tv.example.app", 0))
        assertNull(launcherResourceUri("", 123))
    }
}
