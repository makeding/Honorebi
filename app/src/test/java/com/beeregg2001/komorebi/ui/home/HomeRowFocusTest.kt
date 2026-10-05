package com.beeregg2001.komorebi.ui.home

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import com.beeregg2001.komorebi.common.safeRequestFocusWithRetry
import com.beeregg2001.komorebi.ui.home.components.restoredRowRequest
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [28])
class HomeRowFocusTest {
    @get:Rule val compose = createComposeRule()

    @Test fun offscreenItemRestoresRepeatedlyAndMissingItemFallsBack() {
        val memory = FocusMemory()
        val ids = (0..30).map(Int::toString)
        compose.setContent {
            val state = rememberLazyListState()
            val restore = restoredRowRequest(ids, state, memory, "history")
            LazyRow(state = state, modifier = Modifier.size(200.dp, 60.dp)) {
                items(ids, key = { it }) { id ->
                    val requester = remember { FocusRequester() }
                    LaunchedEffect(restore?.id) {
                        if (restore?.itemId == id && requester.safeRequestFocusWithRetry()) {
                            memory.consume(restore.id)
                        }
                    }
                    Box(Modifier.size(60.dp).testTag(id).focusRequester(requester).focusable())
                }
            }
        }
        for (id in listOf("20", "0", "20", "deleted")) {
            compose.runOnIdle {
                memory.remember(0, "history", id)
                memory.prepare(memory.requestId!!)
            }
            compose.onNodeWithTag(if (id == "deleted") "0" else id).assertIsFocused()
            compose.runOnIdle { assertNull(memory.request) }
        }
    }
}
