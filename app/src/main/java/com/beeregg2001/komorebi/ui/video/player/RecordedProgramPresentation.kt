package com.beeregg2001.komorebi.ui.video.player

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.focusable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.Text
import androidx.tv.material3.MaterialTheme
import com.beeregg2001.komorebi.data.model.RecordedProgram
import com.beeregg2001.komorebi.ui.player.PlayerChannelLogo
import com.beeregg2001.komorebi.ui.player.PlayerProgramPanel
import com.beeregg2001.komorebi.viewmodel.VideoPlayerViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Request identity fences feedback as well as data when a recording is changed. */
data class ProgramDetailRequest(val programId: Int? = null, val loading: Boolean = false, val error: String? = null)

fun programDetailFailureMessage(error: Throwable): String = when (error) {
    is kotlinx.coroutines.TimeoutCancellationException, is java.net.SocketTimeoutException ->
        "サーバーからの応答がありません。再試行してください。（TIMEOUT）"
    is retrofit2.HttpException -> "サーバーが番組情報を返せませんでした。再試行してください。（HTTP ${error.code()}）"
    is java.io.IOException -> "サーバーに接続できません。接続を確認して再試行してください。（NETWORK）"
    else -> "番組情報を読み取れませんでした。再試行してください。（DETAIL_READ）"
}

internal data class RecordedProgramPresentation(
    val logoUrl: String = "",
    val cropLogo: Boolean = false,
    val request: ProgramDetailRequest = ProgramDetailRequest(),
    val retry: () -> Unit = {}
)

@Composable
internal fun rememberRecordedProgramPresentation(
    program: RecordedProgram,
    viewModel: VideoPlayerViewModel,
    backend: String,
): RecordedProgramPresentation {
    val request by viewModel.programDetailRequest.collectAsState()
    val logo by produceState("", program.channel, backend) {
        value = ""
        val channel = program.channel ?: return@produceState
        try { value = viewModel.recordedChannelLogo(channel) }
        catch (cancel: CancellationException) { throw cancel }
        catch (_: Exception) { /* Fixed logo slot displays the saved channel name. */ }
    }
    return RecordedProgramPresentation(logo, backend == "KONOMITV",
        request.takeIf { it.programId == program.id } ?: ProgramDetailRequest(),
        retry = { viewModel.fetchProgramDetail(program.id) })
}

@Composable
internal fun RecordedProgramStatus(program: RecordedProgram, timeFormat: String, presentation: RecordedProgramPresentation) {
    val schedule = remember(program.startTime, program.endTime, timeFormat) {
        formatBroadcastTime(program.startTime, program.endTime, timeFormat) ?: "放送日時は保存されていません"
    }
    Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.End) {
        Row(
            modifier = Modifier.height(48.dp)
                .testTag("recorded-channel-status")
                .background(Color.Black.copy(0.8f), RoundedCornerShape(8.dp))
                .border(1.dp, Color.White.copy(0.15f), RoundedCornerShape(8.dp))
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            PlayerChannelLogo(presentation.logoUrl, program.channel?.name.orEmpty(), presentation.cropLogo,
                Modifier.size(56.dp, 32.dp))
            Spacer(Modifier.width(16.dp))
            Text(
                text = program.channel?.name?.takeIf { it.isNotBlank() }
                    ?: "チャンネル情報なし",
                modifier = Modifier.testTag("recorded-channel-name"),
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
                fontWeight = FontWeight.Bold,
                maxLines = 1
            )
        }
        Text(schedule, color = Color.White, modifier = Modifier.width(380.dp).height(52.dp)
            .testTag("recorded-status-time").padding(top = 8.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.End, maxLines = 2)
    }
}

@Composable
internal fun ProgramInfoOverlay(
    program: RecordedProgram,
    timeFormat: String,
    presentation: RecordedProgramPresentation,
    onClose: () -> Unit
) {
    val requester = remember { FocusRequester() }
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    BackHandler { onClose() }
    LaunchedEffect(Unit) { requester.requestFocus() }
    val rows = remember(program, timeFormat) { PlaybackProgramInfoFormatter.format(program, timeFormat) }
    PlayerProgramPanel(
        channelName = program.channel?.name.orEmpty(), logoUrl = presentation.logoUrl,
        shouldCropLogo = presentation.cropLogo, title = program.title,
        description = program.description, detail = program.detail,
        metadata = rows.map { it.label to it.value }, scrollState = scroll,
        modifier = Modifier.focusRequester(requester).onPreviewKeyEvent {
            if (it.type != KeyEventType.KeyDown) false else when (it.key) {
                Key.DirectionUp, Key.DirectionDown -> {
                    val delta = if (it.key == Key.DirectionDown) 160 else -160
                    scope.launch { scroll.animateScrollTo((scroll.value + delta).coerceIn(0, scroll.maxValue)) }
                    true
                }
                Key.Back, Key.Escape -> { onClose(); true }
                else -> false
            }
        }.focusable(),
        feedback = {
            val request = presentation.request
            Text(when {
                request.loading -> "番組情報を読み込んでいます…"
                request.error != null -> "番組情報の取得に失敗しました。${request.error}"
                program.detail.isNullOrEmpty() -> "詳細情報なし  ·  上下キーでスクロール  ·  戻るで閉じる"
                else -> "上下キーでスクロール  ·  戻るで閉じる"
            }, color = Color.White.copy(0.8f), modifier = Modifier.weight(1f))
            Box(Modifier.width(120.dp)) {
                if (request.error != null && !request.loading) {
                    Button(onClick = presentation.retry, modifier = Modifier.width(120.dp)) {
                        Text("再試行")
                    }
                }
            }
        }
    )
}
