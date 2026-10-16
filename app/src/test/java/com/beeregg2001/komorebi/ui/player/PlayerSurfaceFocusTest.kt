package com.beeregg2001.komorebi.ui.player

import android.app.Application
import android.view.KeyEvent
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.activity.ComponentActivity
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class PlayerSurfaceFocusTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun surfaceFocusDeliversRemoteKeysToThePlayerScene() {
        val focus = FocusRequester()
        val received = mutableListOf<Int>()
        compose.setContent {
            Box(Modifier.onPreviewKeyEvent {
                if (it.nativeKeyEvent.action == KeyEvent.ACTION_DOWN) received += it.nativeKeyEvent.keyCode
                true
            }) {
                PlayerSurface(null, Modifier.size(160.dp, 90.dp).testTag("video")
                    .focusRequester(focus).focusable())
            }
            LaunchedEffect(Unit) { focus.requestFocus() }
        }
        compose.onNodeWithTag("video").assertIsFocused()
        compose.runOnIdle {
            for (code in listOf(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MENU)) {
                compose.activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
                compose.activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
            }
        }
        assertEquals(listOf(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MENU), received)
    }
}
