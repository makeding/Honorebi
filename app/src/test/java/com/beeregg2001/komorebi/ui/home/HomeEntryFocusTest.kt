package com.beeregg2001.komorebi.ui.home

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [28])
class HomeEntryFocusTest {
    @get:Rule val compose = createComposeRule()

    @Test fun backCanReturnToSameTabRepeatedlyAndContentIsDistinct() {
        val memory = FocusMemory()
        val tab = FocusRequester()
        val content = FocusRequester()
        compose.setContent {
            HomeEntryFocusEffect(memory, { tab }, { content })
            Row {
                Box(Modifier.size(48.dp).testTag("tab").focusRequester(tab).focusable())
                Box(Modifier.size(48.dp).testTag("content").focusRequester(content).focusable())
            }
        }
        repeat(2) {
            compose.onNodeWithTag("content").requestFocus().assertIsFocused()
            compose.runOnIdle { memory.remember(0) }
            compose.onNodeWithTag("tab").assertIsFocused()
            compose.runOnIdle { assertNull(memory.request) }
        }
        compose.runOnIdle { memory.requestContent(0) }
        compose.onNodeWithTag("content").assertIsFocused()
        compose.runOnIdle { assertNull(memory.request) }
    }
}
