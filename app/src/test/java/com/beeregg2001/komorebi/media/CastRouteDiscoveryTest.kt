package com.beeregg2001.komorebi.media

import android.app.Application
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [24, 28, 29, 30, 34])
@LooperMode(LooperMode.Mode.PAUSED)
class CastRouteDiscoveryTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun routeDiscoveryDoesNotBlockPlaybackContentOrTeardown() {
        val playing = mutableStateOf(true)
        compose.setContent {
            if (playing.value) CastRouteDiscovery()
            Box(Modifier.testTag("playback"))
        }
        compose.onNodeWithTag("playback").assertExists()
        compose.runOnIdle { playing.value = false }
        compose.onNodeWithTag("playback").assertExists()
        compose.runOnIdle { playing.value = true }
        compose.onNodeWithTag("playback").assertExists()
    }

    @Test
    fun callbackExecutor_dispatchesOnMainLooper() {
        var callbackLooper: Looper? = null

        mainLooperExecutor().execute {
            callbackLooper = Looper.myLooper()
        }

        assertNull(callbackLooper)
        shadowOf(Looper.getMainLooper()).idle()
        assertSame(Looper.getMainLooper(), callbackLooper)
    }
}
