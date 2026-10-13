package com.beeregg2001.komorebi.ui.video

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.beeregg2001.komorebi.ui.components.DialogActionButton
import com.beeregg2001.komorebi.ui.components.DialogActionRole
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.beeregg2001.komorebi.data.api.interceptor.BackendApiException
import com.beeregg2001.komorebi.ui.theme.KomorebiTheme
import java.io.IOException
import java.net.SocketTimeoutException

/** Fixed feedback space keeps retained content stationary during refresh/retry. */
val LibraryFeedbackHeight = 112.dp

fun libraryFailureMessage(error: Throwable): String = when (error) {
    is SocketTimeoutException, is kotlinx.coroutines.TimeoutCancellationException ->
        "応答がありません。再試行してください。（TIMEOUT）"
    is BackendApiException -> "${error.reason}（${error.safeCode} / HTTP ${error.status}）"
    is retrofit2.HttpException -> "サーバーが要求を処理できませんでした。（HTTP ${error.code()}）"
    is jcifs.smb.SmbAuthException -> "認証できません。接続設定のユーザー名とパスワードを確認してください。（SMB_AUTH）"
    is jcifs.smb.SmbException -> "共有フォルダーにアクセスできません。接続先とアクセス権を確認してください。（SMB ${error.ntStatus.toUInt().toString(16).uppercase()}）"
    is java.io.FileNotFoundException -> "指定されたフォルダーが見つかりません。接続先を確認してください。（NOT_FOUND）"
    is IOException -> "接続できません。ネットワークと接続先を確認して再試行してください。（NETWORK）"
    else -> "データを読み取れませんでした。再試行してください。（READ_FAILED）"
}

@Composable
internal fun LibraryFeedback(
    hasContent: Boolean,
    loading: Boolean,
    error: String?,
    failureTitle: String,
    emptyTitle: String,
    retryRequester: FocusRequester,
    onRetry: () -> Unit,
    onRecovery: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = KomorebiTheme.colors
    var retainedError by remember { mutableStateOf(error) }
    var retryFocused by remember { mutableStateOf(false) }
    var submitted by remember { mutableStateOf(false) }
    val pending = loading || submitted
    val shownError = error ?: retainedError.takeIf { pending || retryFocused }
    val currentRecovery by rememberUpdatedState(onRecovery)
    LaunchedEffect(submitted) {
        if (submitted) { withFrameNanos { }; submitted = false }
    }
    LaunchedEffect(error, loading, retryFocused) {
        if (error != null) retainedError = error
        else if (!loading) {
            if (retainedError != null && retryFocused) currentRecovery()
            else retainedError = null
        }
    }
    // Keep the retry node until focus restoration has run on successful recovery.
    val keepRetry = shownError != null || (retainedError != null && retryFocused)
    Box(modifier, contentAlignment = if (hasContent) Alignment.BottomCenter else Alignment.Center) {
        if (!hasContent || shownError != null || loading || keepRetry) {
            Row(
                Modifier.fillMaxWidth().height(LibraryFeedbackHeight).padding(horizontal = 16.dp, vertical = 8.dp)
                    .testTag("library-feedback"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                    Text(
                        when {
                            shownError != null -> failureTitle
                            loading -> "読み込み中…"
                            !hasContent -> emptyTitle
                            else -> ""
                        }, color = colors.textPrimary, style = MaterialTheme.typography.titleMedium,
                    )
                    if (shownError != null) Text(shownError, color = colors.textSecondary, style = MaterialTheme.typography.bodyMedium)
                }
                if (keepRetry) {
                    DialogActionButton(
                        role = DialogActionRole.Primary,
                        onClick = { if (!pending && error != null) { submitted = true; onRetry() } },
                        modifier = Modifier.width(132.dp).focusRequester(retryRequester)
                            .onFocusChanged { retryFocused = it.isFocused }
                            .semantics { stateDescription = if (pending) "読み込み中" else if (error == null) "読み込み完了" else "再試行できます" }
                            .testTag("library-retry"),
                    ) { Text(if (pending) "再試行中…" else if (error == null) "完了" else "再試行") }
                } else {
                    Spacer(Modifier.width(132.dp))
                }
            }
        }
    }
}
