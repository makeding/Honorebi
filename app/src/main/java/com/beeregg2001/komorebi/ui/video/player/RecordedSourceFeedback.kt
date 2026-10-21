package com.beeregg2001.komorebi.ui.video.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.*
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

internal sealed interface RecordedSourceRequest {
    data object Ready : RecordedSourceRequest
    data class Pending(val previousFailure: UserFacingFailure? = null) : RecordedSourceRequest
    data class Failed(val failure: UserFacingFailure) : RecordedSourceRequest
}

/** Loading, failure and retry share bounds. Retrying keeps the same focused action. */
@Composable
internal fun RecordedSourceFeedback(
    request: RecordedSourceRequest,
    modifier: Modifier = Modifier,
    onRetry: () -> Unit,
) {
    if (request == RecordedSourceRequest.Ready) return
    val pending = request is RecordedSourceRequest.Pending
    val failure = when (request) {
        is RecordedSourceRequest.Failed -> request.failure
        is RecordedSourceRequest.Pending -> request.previousFailure
        else -> null
    }
    val focus = remember { FocusRequester() }
    // Focus is acquired once on first failure; a retry leaves this node attached and focused.
    val hasFailure = failure != null
    LaunchedEffect(hasFailure) { if (hasFailure) focus.safeRequestFocus("RecordedSource") }
    Column(
        modifier.width(640.dp).height(272.dp)
            .background(Color.Black.copy(alpha = 0.9f), RoundedCornerShape(12.dp))
            .padding(24.dp).testTag("recorded-source-feedback"),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.fillMaxWidth().height(40.dp), contentAlignment = Alignment.Center) {
            if (pending) CircularProgressIndicator(Modifier.size(24.dp), color = Color.White)
        }
        Text(if (pending) "再生を読み込んでいます…" else "再生を開始できませんでした。", color = Color.White)
        Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
            if (failure != null) Text("${failure.message}（${failure.code}）", color = Color.White)
        }
        Button(
            onClick = { if (!pending && failure != null) onRetry() },
            enabled = failure != null,
            modifier = Modifier.focusRequester(focus).width(144.dp)
                .testTag("recorded-source-retry").semantics {
                    if (pending || failure == null) disabled()
                    stateDescription = if (pending) "読み込み中" else "再試行できます"
                },
        ) { Text("再試行") }
    }
}
