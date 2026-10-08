package com.beeregg2001.komorebi.util

import org.junit.Assert.assertEquals
import org.junit.Test

class UpdateApkSelectorTest {
    private val urls = mapOf("arm64-v8a" to "arm64.apk", "armeabi-v7a" to "arm32.apk")

    @Test fun prefersFirstSupportedAbi() {
        assertEquals("arm64.apk", select(listOf("arm64-v8a", "armeabi-v7a")))
        assertEquals("arm32.apk", select(listOf("armeabi-v7a", "arm64-v8a")))
    }

    @Test fun selectsArm32For32BitAndroid() {
        assertEquals("arm32.apk", select(listOf("armeabi-v7a", "armeabi")))
    }

    @Test fun skipsUnavailableOrBlankAssets() {
        assertEquals("arm32.apk", select(listOf("arm64-v8a", "armeabi-v7a"), urls - "arm64-v8a"))
        assertEquals("arm32.apk", select(listOf("arm64-v8a", "armeabi-v7a"), urls + ("arm64-v8a" to " ")))
    }

    @Test fun fallsBackToUniversalForUnknownEmptyAndLegacyManifests() {
        assertEquals("universal.apk", select(listOf("x86_64")))
        assertEquals("universal.apk", select(emptyList()))
        assertEquals("universal.apk", select(listOf("arm64-v8a"), emptyMap()))
        assertEquals("universal.apk", select(listOf("arm64-v8a"), mapOf("arm64-v8a" to "")))
    }

    private fun select(abis: List<String>, assets: Map<String, String> = urls) =
        UpdateApkSelector.select(abis, assets, "universal.apk")
}
