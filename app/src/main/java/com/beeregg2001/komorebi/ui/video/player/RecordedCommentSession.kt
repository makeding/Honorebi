package com.beeregg2001.komorebi.ui.video.player

import android.util.Log
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.SnapshotStateList
import com.beeregg2001.komorebi.data.jikkyo.JikkyoClient
import com.beeregg2001.komorebi.data.model.ArchivedComment
import com.beeregg2001.komorebi.data.model.RecordedProgram
import com.beeregg2001.komorebi.viewmodel.VideoPlayerViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedSendChannelException

/** Every successful response is a snapshot, not a promise that upstream has archived the tail. */
internal suspend fun refreshRecordedComments(
    keepRefreshing: () -> Boolean,
    accepts: () -> Boolean,
    fetch: suspend () -> Result<List<ArchivedComment>>,
    merge: (List<ArchivedComment>) -> Unit,
    onFailure: (Throwable) -> Unit,
    waitForRefresh: suspend () -> Unit = { delay(30_000L) },
) {
    while (currentCoroutineContext().isActive && accepts()) {
        val result = fetch()
        currentCoroutineContext().ensureActive()
        if (!accepts()) return
        result.fold(merge, onFailure)
        if (result.isSuccess && !keepRefreshing()) return
        waitForRefresh()
    }
}

@Composable
internal fun rememberRecordedComments(
    program: RecordedProgram,
    fence: RecordedPlaybackFence,
    viewModel: VideoPlayerViewModel,
    onShowToast: (String) -> Unit,
): SnapshotStateList<ArchivedComment> {
    val videoId = program.recordedVideo.id
    val comments = remember(videoId, fence.identity) { mutableStateListOf<ArchivedComment>() }
    val keys = remember(comments) { HashSet<String>() }
    val currentProgram by rememberUpdatedState(program)
    val currentToast by rememberUpdatedState(onShowToast)
    // Recording -> Recorded must not abandon comments still waiting for archive collection.
    var wasChase by remember(videoId, fence.identity) {
        mutableStateOf(program.isRecording || program.recordedVideo.status == "Recording")
    }
    SideEffect {
        if (program.isRecording || program.recordedVideo.status == "Recording") wasChase = true
    }
    LaunchedEffect(videoId, fence.identity, wasChase) {
        var reportedFailure = false
        refreshRecordedComments(
            keepRefreshing = { wasChase },
            accepts = fence::accepts,
            fetch = { viewModel.fetchRecordedComments(videoId) },
            merge = { incoming ->
                reportedFailure = false
                appendUniqueArchivedComments(comments, keys, incoming)
                Log.i("RecordedComments", "Archive merged: video=$videoId received=${incoming.size} total=${comments.size}")
            },
            onFailure = { error ->
                Log.w("RecordedComments", "Archive request failed: video=$videoId", error)
                if (!reportedFailure) {
                    currentToast((error.message ?: "実況コメントを取得できませんでした。接続を確認してください。") + " 自動で再試行します。")
                    reportedFailure = true
                }
            },
        )
    }
    val recordingStart = program.recordingStartUnixOrNull()
    LaunchedEffect(videoId, fence.identity, wasChase, recordingStart, program.channel?.id) {
        if (!wasChase || recordingStart == null) return@LaunchedEffect
        var watchUrl: String? = null
        while (isActive && fence.accepts() && watchUrl == null) {
            watchUrl = viewModel.getChaseJikkyoWatchSessionUrl(currentProgram)
            if (watchUrl == null) delay(30_000L)
        }
        if (!fence.accepts() || watchUrl == null) return@LaunchedEffect
        val pending = Channel<ArchivedComment>(256)
        val client = JikkyoClient(watchUrl)
        val consumer = launch {
            for (first in pending) {
                val batch = arrayListOf(first)
                delay(250L)
                while (batch.size < 256) {
                    batch.add(pending.tryReceive().getOrNull() ?: break)
                }
                if (!fence.accepts()) return@launch
                appendUniqueArchivedComments(comments, keys, batch)
                Log.i("RecordedComments", "WS merged: video=$videoId received=${batch.size} total=${comments.size}")
            }
        }
        try {
            client.startChase(recordingStart.toDouble()) { raw ->
                if (fence.accepts()) {
                    parseChaseWsArchivedComment(raw, recordingStart)?.let {
                        try { pending.send(it) } catch (_: ClosedSendChannelException) { }
                    }
                }
            }
            awaitCancellation()
        } finally {
            // Release a socket callback blocked by backpressure before cancelling its connection.
            pending.cancel()
            client.stop()
            consumer.cancel()
        }
    }
    return comments
}
