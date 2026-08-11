package com.beeregg2001.komorebi.baselineprofile

import android.os.SystemClock
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * TV interaction baselines for an already discoverable Raw MMTS recording.
 *
 * Invoke with existing UI data only, for example:
 * -e rawMmtsTitle "BS4K test recording"
 *
 * The player exposes an accessibility marker only after Media3 reports the first rendered frame.
 * No media URL, intent extra, or test-only entry point is injected by these tests.
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class RawMmtsBenchmarks {

    @get:Rule
    val rule = MacrobenchmarkRule()

    @Test
    fun rawMmtsFirstFrame() = measureRawMmts { config ->
        startActivityAndWait()
        openConfiguredRecording(device, config)
        waitForDecodedFrame(device)
    }

    @Test
    fun rawMmtsRemoteSeek() = measureRawMmts { config ->
        startActivityAndWait()
        openConfiguredRecording(device, config)
        val renderedFrame = waitForDecodedFrame(device)
        // The player handles left/right only after the seek bar gains D-pad focus.
        device.pressDPadUp()
        repeat(18) { device.pressDPadRight() }
        waitForDecodedFrame(device, after = renderedFrame)
    }

    @Test
    fun rawMmtsSceneSearchDpad() = measureRawMmts { config ->
        startActivityAndWait()
        openConfiguredRecording(device, config)
        val renderedFrame = waitForDecodedFrame(device)
        device.pressDPadCenter()
        val thumbnailButton = device.wait(Until.findObject(By.desc("サムネイル")), UI_TIMEOUT_MS)
        checkNotNull(thumbnailButton) { "Scene-search button was not visible" }
        thumbnailButton.click()
        check(device.wait(Until.hasObject(By.textContains("間隔")), UI_TIMEOUT_MS)) {
            "Scene-search interval selector was not visible"
        }
        device.pressDPadDown()
        device.pressDPadUp()
        device.pressDPadRight()
        device.pressDPadCenter()
        waitForDecodedFrame(device, after = renderedFrame)
    }

    private fun measureRawMmts(action: MacrobenchmarkScope.(RawMmtsConfig) -> Unit) {
        val config = RawMmtsConfig.fromInstrumentationArgs()
        assumeTrue(config.skipReason, config.isConfigured)
        rule.measureRepeated(
            packageName = targetAppId(),
            metrics = listOf(FrameTimingMetric()),
            compilationMode = CompilationMode.Partial(),
            startupMode = StartupMode.COLD,
            iterations = 5,
            setupBlock = { pressHome() },
            measureBlock = { action(config) }
        )
    }

    private fun openConfiguredRecording(device: UiDevice, config: RawMmtsConfig) {
        val recording = device.wait(Until.findObject(By.text(config.title)), UI_TIMEOUT_MS)
        checkNotNull(recording) {
            "Configured Raw MMTS title '${config.title}' was not discoverable in the launched UI"
        }
        recording.click()
    }

    private fun waitForDecodedFrame(device: UiDevice, after: String? = null): String {
        val deadline = SystemClock.uptimeMillis() + PLAYBACK_TIMEOUT_MS
        while (SystemClock.uptimeMillis() < deadline) {
            val description = device.findObject(By.descStartsWith(RENDERED_VIDEO_DESCRIPTION))
                ?.contentDescription
            if (description != null && description != after) return description
            SystemClock.sleep(FRAME_POLL_INTERVAL_MS)
        }
        error("Media3 did not report a new rendered video frame")
    }

    private fun targetAppId(): String = requireNotNull(
        InstrumentationRegistry.getArguments().getString("targetAppId")
    ) { "targetAppId not passed as instrumentation runner arg" }

    private data class RawMmtsConfig(val title: String) {
        val isConfigured get() = title.isNotBlank()
        val skipReason get() =
            "Skipping Raw MMTS benchmarks: pass -e rawMmtsTitle for an existing UI recording"

        companion object {
            fun fromInstrumentationArgs() = RawMmtsConfig(
                title = InstrumentationRegistry.getArguments().getString("rawMmtsTitle").orEmpty(),
            )
        }
    }

    private companion object {
        const val UI_TIMEOUT_MS = 10_000L
        const val PLAYBACK_TIMEOUT_MS = 30_000L
        const val FRAME_POLL_INTERVAL_MS = 25L
        const val RENDERED_VIDEO_DESCRIPTION = "再生映像"
    }
}
