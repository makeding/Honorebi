package com.beeregg2001.komorebi.ui.home

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.unit.dp
import com.beeregg2001.komorebi.data.model.LauncherApp
import com.beeregg2001.komorebi.ui.home.components.LauncherAppCard
import com.beeregg2001.komorebi.ui.theme.KomorebiTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class LauncherAppCardBackTest {
    @get:Rule val compose = createComposeRule()

    @Test fun editingBackOpensActionsWithoutLeakingEitherEventToRootBack() {
        var actionOpens = 0
        var rootBackEvents = 0

        compose.setContent {
            KomorebiTheme {
                val requester = remember { FocusRequester() }
                LaunchedEffect(Unit) { requester.requestFocus() }
                Box(
                    Modifier.onKeyEvent {
                        if (it.key == Key.Back) rootBackEvents++
                        false
                    }
                ) {
                    LauncherAppCard(
                        app = testApp,
                        onClick = {},
                        onManage = {},
                        onFocus = {},
                        isEditing = true,
                        onOpenActions = { actionOpens++ },
                        modifier = Modifier
                            .size(160.dp, 120.dp)
                            .testTag("app-card")
                            .focusRequester(requester),
                    )
                }
            }
        }

        compose.onNodeWithTag("app-card").assertIsFocused().performKeyInput {
            keyDown(Key.Back)
            keyUp(Key.Back)
        }

        assertEquals(1, actionOpens)
        assertEquals(0, rootBackEvents)
    }

    @Test fun nonEditingBackStillBubblesToTheNormalRootBackPath() {
        var rootBackEvents = 0

        compose.setContent {
            KomorebiTheme {
                val requester = remember { FocusRequester() }
                LaunchedEffect(Unit) { requester.requestFocus() }
                Box(
                    Modifier.onKeyEvent {
                        if (it.key == Key.Back) rootBackEvents++
                        false
                    }
                ) {
                    LauncherAppCard(
                        app = testApp,
                        onClick = {},
                        onManage = {},
                        onFocus = {},
                        modifier = Modifier
                            .size(160.dp, 120.dp)
                            .testTag("app-card")
                            .focusRequester(requester),
                    )
                }
            }
        }

        compose.onNodeWithTag("app-card").assertIsFocused().performKeyInput {
            keyDown(Key.Back)
            keyUp(Key.Back)
        }

        assertEquals(2, rootBackEvents)
    }

    private companion object {
        val testApp = LauncherApp(
            packageName = "test.app",
            activityName = "MainActivity",
            label = "Test app",
            icon = null,
            banner = null,
            artworkCacheVersion = "test",
        )
    }
}
