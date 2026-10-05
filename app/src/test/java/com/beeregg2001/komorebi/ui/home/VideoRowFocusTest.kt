package com.beeregg2001.komorebi.ui.home

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [28])
class VideoRowFocusTest {
    @get:Rule val compose = createComposeRule()

    @Test fun onAirDownStartsAtFirstCardThenRestoresRememberedChildAndRemovedChildFallsBack() {
        val banner = FocusRequester()
        var ids by mutableStateOf(listOf("first", "second", "third"))
        compose.setContent {
            val state = rememberLazyListState()
            val focus = rememberVideoRowFocus(ids, state)
            Column(Modifier.width(400.dp)) {
                // On Air's center lines up with the second video, reproducing the
                // spatial-search failure rather than placing it over the first.
                Box(Modifier.padding(start = 120.dp).size(100.dp, 50.dp)
                    .testTag("onair").focusRequester(banner).focusable())
                LazyRow(state = state, modifier = Modifier.width(400.dp).height(60.dp).then(focus.groupModifier())) {
                    itemsIndexed(ids, key = { _, id -> id }) { index, id ->
                        Box(Modifier.size(100.dp, 60.dp).testTag(id)
                            .then(focus.itemModifier(id))
                            .focusProperties { up = banner }.focusable())
                    }
                }
            }
        }
        compose.onNodeWithTag("onair").requestFocus().press(Key.DirectionDown)
        compose.onNodeWithTag("first").assertIsFocused().press(Key.DirectionRight)
        compose.onNodeWithTag("second").assertIsFocused().press(Key.DirectionUp)
        compose.onNodeWithTag("onair").assertIsFocused().press(Key.DirectionDown)
        compose.onNodeWithTag("second").assertIsFocused().press(Key.DirectionUp)
        // Stable identities survive a data refresh that changes row order.
        compose.runOnIdle { ids = listOf("third", "first", "second") }
        compose.onNodeWithTag("onair").press(Key.DirectionDown)
        compose.onNodeWithTag("second").assertIsFocused().press(Key.DirectionUp)
        // A direct playback return must bypass directional row memory.
        compose.onNodeWithTag("third").requestFocus().assertIsFocused().press(Key.DirectionUp)
        compose.onNodeWithTag("second").requestFocus().assertIsFocused().press(Key.DirectionUp)
        compose.runOnIdle { ids = listOf("first", "third") }
        compose.onNodeWithTag("onair").press(Key.DirectionDown)
        compose.onNodeWithTag("first").assertIsFocused()
    }

    private fun SemanticsNodeInteraction.press(key: Key): SemanticsNodeInteraction =
        performKeyInput { keyDown(key); keyUp(key) }
}
