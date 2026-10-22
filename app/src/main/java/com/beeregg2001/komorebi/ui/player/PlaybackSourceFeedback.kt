package com.beeregg2001.komorebi.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.Text
import com.beeregg2001.komorebi.common.UserFacingFailure
import com.beeregg2001.komorebi.common.safeRequestFocus

sealed interface PlaybackSourceRequest {
    data object Ready : PlaybackSourceRequest
    data class Pending(val previousFailure: UserFacingFailure? = null) : PlaybackSourceRequest
    data class Failed(val failure: UserFacingFailure) : PlaybackSourceRequest
}

/** Passive loading lane, or a stable interactive failure/retry panel. */
@Composable
fun PlaybackSourceFeedback(
    request: PlaybackSourceRequest,
    modifier: Modifier = Modifier,
    loadingText: String = "再生を読み込んでいます…",
    failureText: String = "再生を開始できませんでした。",
    onRetry: () -> Unit,
    onBack: (() -> Unit)? = null,
) {
    if (request == PlaybackSourceRequest.Ready) return
    val pending = request is PlaybackSourceRequest.Pending
    val failure = when (request) {
        is PlaybackSourceRequest.Failed -> request.failure
        is PlaybackSourceRequest.Pending -> request.previousFailure
        PlaybackSourceRequest.Ready -> null
    }
    if (pending && failure == null) {
        PlaybackLoadingNotice(message = loadingText, modifier = modifier)
        return
    }
    val retryFocus = remember { FocusRequester() }
    val hasFailure = failure != null
    LaunchedEffect(hasFailure) {
        if (hasFailure) retryFocus.safeRequestFocus("PlaybackSource")
    }
    Column(
        modifier.width(640.dp).height(272.dp)
            .background(Color.Black.copy(alpha = 0.9f), RoundedCornerShape(12.dp))
            .padding(24.dp).testTag("playback-source-feedback"),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.fillMaxWidth().height(40.dp), contentAlignment = Alignment.Center) {
            if (pending) CircularProgressIndicator(Modifier.size(24.dp), color = Color.White)
        }
        Text(if (pending) loadingText else failureText, color = Color.White)
        Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
            if (failure != null) Text("${failure.message}（${failure.code}）", color = Color.White)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Button(
                onClick = { if (!pending && failure != null) onRetry() },
                enabled = failure != null,
                modifier = Modifier.focusRequester(retryFocus).width(144.dp)
                    .testTag("playback-source-retry").semantics {
                        if (pending || failure == null) disabled()
                        stateDescription = if (pending) "読み込み中" else "再試行できます"
                    },
            ) { Text("再試行") }
            if (onBack != null) {
                Button(
                    onClick = onBack,
                    modifier = Modifier.width(144.dp)
                        .testTag("playback-source-back"),
                ) { Text("戻る") }
            }
        }
    }
}
