package com.beeregg2001.komorebi.ui.video.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

/** Mount when data arrives; retain the renderer across transient empty updates within this session. */
@Composable
internal fun rememberRecordedCommentLayerReady(session: Any, hasComments: Boolean): Boolean {
    var initialized by remember(session) { mutableStateOf(false) }
    if (hasComments) SideEffect { initialized = true }
    return hasComments || initialized
}
