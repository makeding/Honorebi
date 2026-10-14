package com.beeregg2001.komorebi.ui.screensaver

import android.app.Application
import android.content.Intent
import android.os.PowerManager
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
@OptIn(ExperimentalCoroutinesApi::class)
class InAppScreensaverStateTest {
    private val effects = TestCoroutineScheduler()
    @get:Rule val compose = createComposeRule(effectContext = UnconfinedTestDispatcher(effects))
    private val context get() = RuntimeEnvironment.getApplication()
    private val owner = object : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry
    }
    private val enabled = mutableStateOf(true)
    private val hasImages = mutableStateOf(true)
    private val mounted = mutableStateOf(true)
    private lateinit var state: InAppScreensaverState

    private fun start(interactive: Boolean = true, resumed: Boolean = true) {
        compose.runOnIdle {
            shadowOf(context.getSystemService(PowerManager::class.java)).setIsInteractive(interactive)
            owner.registry.currentState = if (resumed) Lifecycle.State.RESUMED else Lifecycle.State.STARTED
        }
        compose.setContent {
            if (mounted.value) {
                CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                    state = rememberInAppScreensaverState(enabled.value, 1, hasImages.value)
                    if (state.isVisible) {
                        Dialog(onDismissRequest = state::dismiss) {
                            Box(Modifier.size(100.dp).testTag("saver"))
                        }
                    }
                }
            }
        }
    }

    private fun advance(millis: Long) {
        compose.runOnIdle {
            ShadowSystemClock.advanceBy(Duration.ofMillis(millis))
            effects.advanceTimeBy(millis)
            effects.runCurrent()
        }
        compose.waitForIdle()
    }

    private fun screen(interactive: Boolean) {
        compose.runOnIdle {
            shadowOf(context.getSystemService(PowerManager::class.java)).setIsInteractive(interactive)
            context.sendBroadcast(Intent(if (interactive) Intent.ACTION_SCREEN_ON else Intent.ACTION_SCREEN_OFF))
        }
        compose.waitForIdle()
    }

    private fun pause(stop: Boolean = false) {
        compose.runOnIdle {
            owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
            if (stop) owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        }
        compose.waitForIdle()
    }

    private fun resume() {
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }
        compose.waitForIdle()
    }

    private fun hidden() = compose.onNodeWithTag("saver").assertDoesNotExist()
    private fun shown() = compose.onNodeWithTag("saver").assertExists()

    @Test fun pendingDeadlineDoesNotCarryAcrossLongSleepAndStop() {
        start()
        advance(59_000)
        pause(stop = true)
        screen(false)
        advance(600_000)
        hidden()
        screen(true)
        hidden()
        resume()
        hidden()
        advance(59_000)
        hidden()
        advance(1_000)
        shown()
    }

    @Test fun visibleSaverIsDismissedOnPauseAndResumeWithoutStopStartsFresh() {
        start()
        advance(60_000)
        shown()
        pause()
        hidden()
        advance(600_000)
        resume()
        hidden()
        advance(59_000)
        hidden()
        advance(1_000)
        shown()
    }

    @Config(sdk = [34])
    @Test fun screenOffAloneDismissesVisibleSaverAndScreenOnRestartsIdle() {
        start()
        advance(60_000)
        shown()
        screen(false)
        hidden()
        advance(600_000)
        hidden()
        screen(true)
        hidden()
        advance(59_000)
        hidden()
        advance(1_000)
        shown()
    }

    @Test fun resumeWhileAsleepAndWakeWhileBackgroundedBothWaitForEligibility() {
        start(interactive = false, resumed = false)
        resume()
        advance(120_000)
        hidden()
        pause()
        screen(true)
        advance(120_000)
        hidden()
        resume()
        hidden()
        advance(59_000)
        hidden()
        advance(1_000)
        shown()
    }

    @Test fun playbackAndMissingImagesOrDisabledSettingStillSuppressSaverAfterWake() {
        val player = Any()
        try {
            start()
            compose.runOnIdle { ScreensaverInteractionTracker.setPlaybackActive(player, true) }
            pause()
            screen(false)
            advance(120_000)
            screen(true)
            resume()
            advance(120_000)
            hidden()
            compose.runOnIdle { ScreensaverInteractionTracker.setPlaybackActive(player, false) }
            advance(59_000)
            hidden()
            advance(1_000)
            shown()
            compose.runOnIdle { hasImages.value = false }
            advance(120_000)
            hidden()
            compose.runOnIdle { enabled.value = false; hasImages.value = true }
            advance(120_000)
            hidden()
        } finally {
            ScreensaverInteractionTracker.setPlaybackActive(player, false)
        }
    }

    @Test fun ordinaryDismissalRestartsTimerAndDisposalRetiresTimerAndReceiver() {
        start()
        advance(60_000)
        shown()
        compose.runOnIdle { state.dismiss() }
        hidden()
        advance(59_000)
        hidden()
        advance(1_000)
        shown()
        compose.runOnIdle { mounted.value = false }
        hidden()
        screen(false)
        screen(true)
        advance(120_000)
        hidden()
    }
}
