package com.beeregg2001.komorebi.ui.home

import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import com.beeregg2001.komorebi.common.safeRequestFocusWithRetry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** A row owns its stable child identity independently from banner/player focus. */
internal class VideoRowFocus(
    private val ids: () -> List<String>,
    private val state: LazyListState,
    private val scope: CoroutineScope,
) {
    private var lastFocusedId: String? = null
    private var entering = false
    private val requesters = mutableMapOf<String, FocusRequester>()

    fun groupModifier(): Modifier = Modifier.focusProperties {
        onEnter = entry@ {
            // Player/AI restore explicitly addresses a card; only directional
            // row entry should substitute the remembered child.
            if (requestedFocusDirection != FocusDirection.Down &&
                requestedFocusDirection != FocusDirection.Up) return@entry
            val currentIds = ids()
            if (!entering && currentIds.isNotEmpty()) {
                val index = currentIds.indexOf(lastFocusedId).takeIf { it >= 0 } ?: 0
                val requester = requester(currentIds[index])
                entering = true
                cancelFocusChange()
                scope.launch {
                    try {
                        state.scrollToItem(index)
                        withFrameNanos { }
                        requester.safeRequestFocusWithRetry("VideoRowEntry")
                    } finally {
                        entering = false
                    }
                }
            }
        }
    }.focusGroup()

    private fun requester(id: String) = requesters.getOrPut(id) { FocusRequester() }

    fun itemModifier(id: String): Modifier = Modifier
        .focusRequester(requester(id))
        .onFocusChanged { if (it.isFocused) lastFocusedId = id }
}

@Composable
internal fun rememberVideoRowFocus(ids: List<String>, state: LazyListState): VideoRowFocus {
    val currentIds = rememberUpdatedState(ids)
    val scope = rememberCoroutineScope()
    return remember(state, scope) { VideoRowFocus({ currentIds.value }, state, scope) }
}
