package com.beeregg2001.komorebi.ui.setting

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalWindowInfo
import com.beeregg2001.komorebi.common.safeRequestFocusWithRetry
import com.beeregg2001.komorebi.util.UpdateState

/** Return ownership from the root update overlay and from the external installer window. */
@Composable
internal fun AppInfoFocusReturnEffect(
    updateState: UpdateState,
    checkRequester: FocusRequester,
    updateRequested: Boolean,
    lastFocusedRow: FocusRequester?,
    isWindowFocused: Boolean = LocalWindowInfo.current.isWindowFocused,
) {
    var promptReturnPending by remember { mutableStateOf(false) }
    var windowReturnPending by remember { mutableStateOf(false) }

    LaunchedEffect(updateState, updateRequested, lastFocusedRow, isWindowFocused) {
        if (!isWindowFocused) {
            if (lastFocusedRow != null) windowReturnPending = true
            return@LaunchedEffect
        }
        if (updateState is UpdateState.UpdateAvailable) {
            if (updateRequested) promptReturnPending = true
            return@LaunchedEffect
        }
        if (!promptReturnPending && !windowReturnPending) return@LaunchedEffect

        // The overlay must leave composition before requesting its former owner's focus.
        withFrameNanos { }
        val target = if (promptReturnPending) checkRequester else lastFocusedRow
        if (target?.safeRequestFocusWithRetry("AppInfo_UpdateReturn") == true) {
            promptReturnPending = false
            windowReturnPending = false
        }
    }
}
