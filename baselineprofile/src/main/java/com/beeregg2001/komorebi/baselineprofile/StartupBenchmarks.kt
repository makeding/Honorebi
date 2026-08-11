package com.beeregg2001.komorebi.baselineprofile

import androidx.benchmark.macro.BaselineProfileMode
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Startup baselines deliberately distinguish Android's first frame from the app's stable Home
 * surface. The app reports fully drawn separately; the explicit Home wait below is not used as
 * a substitute for that signal.
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class StartupBenchmarks {

    @get:Rule
    val rule = MacrobenchmarkRule()

    @Test
    fun coldStartFirstFrameWithoutProfile() = firstFrame(CompilationMode.None())

    @Test
    fun coldStartFirstFrameWithBaselineProfile() =
        firstFrame(CompilationMode.Partial(BaselineProfileMode.Require))

    @Test
    fun coldStartStableHome() {
        rule.measureRepeated(
            packageName = targetAppId(),
            metrics = listOf(StartupTimingMetric(), FrameTimingMetric()),
            compilationMode = CompilationMode.Partial(BaselineProfileMode.Require),
            startupMode = StartupMode.COLD,
            iterations = 10,
            setupBlock = { pressHome() },
            measureBlock = {
                startActivityAndWait()
                // This is a stable-Home readiness condition, not a reportFullyDrawn wait.
                check(device.wait(Until.hasObject(By.text("ホーム")), HOME_TIMEOUT_MS)) {
                    "Home tab was not visible within ${HOME_TIMEOUT_MS}ms"
                }
                device.waitForIdle()
            }
        )
    }

    private fun firstFrame(compilationMode: CompilationMode) {
        rule.measureRepeated(
            packageName = targetAppId(),
            metrics = listOf(StartupTimingMetric()),
            compilationMode = compilationMode,
            startupMode = StartupMode.COLD,
            iterations = 10,
            setupBlock = { pressHome() },
            // Android's first rendered frame only; no data/Home readiness wait is added.
            measureBlock = { startActivityAndWait() }
        )
    }

    private fun targetAppId(): String = requireNotNull(
        InstrumentationRegistry.getArguments().getString("targetAppId")
    ) { "targetAppId not passed as instrumentation runner arg" }

    private companion object {
        const val HOME_TIMEOUT_MS = 10_000L
    }
}
