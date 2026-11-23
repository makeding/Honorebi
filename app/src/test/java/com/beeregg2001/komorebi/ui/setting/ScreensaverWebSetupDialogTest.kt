package com.beeregg2001.komorebi.ui.setting

import android.app.Application
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.beeregg2001.komorebi.setup.ConnectionSetupSession
import com.beeregg2001.komorebi.setup.ConnectionSetupUiState
import com.beeregg2001.komorebi.ui.theme.KomorebiTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28], qualifiers = "w1280dp-h720dp-land")
class ScreensaverWebSetupDialogTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun displaysScreensaverUrlUpdatesLiveCountAndInvokesLifecycleCallbacks() {
        val session = ConnectionSetupSession(token = "abc123token", source = "SCREENSAVER")
        val state = mutableStateOf(ConnectionSetupUiState(session = session))
        val count = mutableIntStateOf(2)
        val shown = mutableStateOf(true)
        var starts = 0
        var stops = 0
        var dismissed = 0

        compose.setContent {
            KomorebiTheme {
                if (shown.value) {
                    ScreensaverWebSetupDialog(
                        serverIp = "192.168.1.42",
                        state = state.value,
                        savedImageCount = count.intValue,
                        onStart = { starts++ },
                        onStop = { stops++ },
                        onDismiss = {
                            dismissed++
                            shown.value = false
                        },
                    )
                }
            }
        }

        compose.mainClock.advanceTimeBy(300)
        compose.waitForIdle()

        assertEquals(1, starts)
        assertEquals(0, stops)
        compose.onNodeWithText("スマホから画像を管理").assertIsDisplayed()
        compose.onNodeWithText("http://192.168.1.42:8081/screensaver/#abc123token").assertIsDisplayed()
        compose.onNodeWithText("現在の登録画像: 2 枚（スマホで追加・削除すると自動で反映されます）").assertIsDisplayed()

        // Reactive live count update
        count.intValue = 5
        compose.waitForIdle()
        compose.onNodeWithText("現在の登録画像: 5 枚（スマホで追加・削除すると自動で反映されます）").assertIsDisplayed()

        // Close button is focused and dismiss triggers onStop via DisposableEffect
        compose.onNodeWithText("閉じる").assertIsFocused().performKeyInput {
            keyDown(Key.DirectionCenter)
            keyUp(Key.DirectionCenter)
        }
        compose.waitForIdle()

        assertEquals(1, dismissed)
        assertEquals(1, stops)
    }

    @Test
    fun errorStateShowsMessageAndRetryAction() {
        val state = mutableStateOf(
            ConnectionSetupUiState(error = "画像管理ページを開けませんでした。ネットワーク接続を確認して再試行してください。"),
        )
        var starts = 0

        compose.setContent {
            KomorebiTheme {
                ScreensaverWebSetupDialog(
                    serverIp = "192.168.1.42",
                    state = state.value,
                    savedImageCount = 0,
                    onStart = { starts++ },
                    onStop = {},
                    onDismiss = {},
                )
            }
        }

        compose.waitForIdle()
        assertEquals(1, starts)
        compose.onNodeWithText("画像管理ページを開けませんでした。ネットワーク接続を確認して再試行してください。").assertIsDisplayed()
        compose.onNodeWithText("再試行").requestFocus().performKeyInput {
            keyDown(Key.DirectionCenter)
            keyUp(Key.DirectionCenter)
        }
        compose.waitForIdle()
        assertEquals(2, starts)
    }
}
