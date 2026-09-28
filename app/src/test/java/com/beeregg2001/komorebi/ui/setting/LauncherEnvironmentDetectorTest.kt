package com.beeregg2001.komorebi.ui.setting

import org.junit.Assert.assertEquals
import org.junit.Test

class LauncherEnvironmentDetectorTest {
    @Test
    fun googleTvLauncherWinsOverLeanback() {
        assertEquals(
            HomeEnvironment.GOOGLE_TV,
            LauncherEnvironmentDetector.classify(
                hasGoogleTvLauncher = true,
                hasAndroidTvLauncher = false,
                hasChromecastShell = false,
                isLeanback = true,
                manufacturer = "Sony",
                model = "BRAVIA",
            )
        )
    }

    @Test
    fun chromecastShellIsGoogleTv() {
        assertEquals(
            HomeEnvironment.GOOGLE_TV,
            LauncherEnvironmentDetector.classify(
                hasGoogleTvLauncher = false,
                hasAndroidTvLauncher = false,
                hasChromecastShell = true,
                isLeanback = true,
                manufacturer = "Google",
                model = "Chromecast HD",
            )
        )
    }

    @Test
    fun leanbackLauncherIsAndroidTv() {
        assertEquals(
            HomeEnvironment.ANDROID_TV,
            LauncherEnvironmentDetector.classify(
                hasGoogleTvLauncher = false,
                hasAndroidTvLauncher = true,
                hasChromecastShell = false,
                isLeanback = true,
                manufacturer = "NVIDIA",
                model = "SHIELD Android TV",
            )
        )
    }

    @Test
    fun leanbackFeatureAloneIsAndroidTv() {
        assertEquals(
            HomeEnvironment.ANDROID_TV,
            LauncherEnvironmentDetector.classify(
                hasGoogleTvLauncher = false,
                hasAndroidTvLauncher = false,
                hasChromecastShell = false,
                isLeanback = true,
                manufacturer = "Sharp",
                model = "AQUOS",
            )
        )
    }

    @Test
    fun phonesAreUnknown() {
        assertEquals(
            HomeEnvironment.UNKNOWN,
            LauncherEnvironmentDetector.classify(
                hasGoogleTvLauncher = false,
                hasAndroidTvLauncher = false,
                hasChromecastShell = false,
                isLeanback = false,
                manufacturer = "Google",
                model = "Pixel 8",
            )
        )
    }
}
