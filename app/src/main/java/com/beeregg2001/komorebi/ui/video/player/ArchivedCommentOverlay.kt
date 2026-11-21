package com.beeregg2001.komorebi.ui.video.player

import android.util.Log
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.beeregg2001.komorebi.data.model.ArchivedComment
import com.beeregg2001.komorebi.ui.player.DanmakuEngineMode
import com.beeregg2001.komorebi.ui.player.DanmakuItem
import com.beeregg2001.komorebi.ui.player.DanmakuOverlay
import com.beeregg2001.komorebi.ui.player.jikkyoDanmaku
import com.beeregg2001.komorebi.ui.player.jikkyoDanmakuType
import com.beeregg2001.komorebi.ui.player.DanmakuViewHandle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

private const val TAG = "ArchivedCommentOverlay"

// A Surface can be recreated without replacing its Android View.
private class PreparedCommentSurface(val handle: DanmakuViewHandle)

@Composable
fun ArchivedCommentOverlay(
    modifier: Modifier = Modifier,
    comments: SnapshotStateList<ArchivedComment>,
    currentPositionProvider: () -> Long,
    isPlaying: Boolean,
    isCommentEnabled: Boolean,
    commentSpeed: Float,
    commentFontSizeScale: Float,
    commentOpacity: Float,
    commentMaxLines: Int,
    commentEngine: DanmakuEngineMode = DanmakuEngineMode.DFM_HARDWARE,
    useSoftwareRendering: Boolean = false,
    recordedPlaybackFence: RecordedPlaybackFence,
) {
    val danmakuViewRef = remember { mutableStateOf<DanmakuViewHandle?>(null) }
    val preparedDanmakuViewRef = remember { mutableStateOf<PreparedCommentSurface?>(null) }

    // UIスレッドでしか取得できない画面密度(density)を事前に計算しておく
    val context = LocalContext.current
    val density = remember(context) { context.resources.displayMetrics.density }
    val colorCache = remember { HashMap<String, Int>() }

    val latestPositionProvider by rememberUpdatedState(currentPositionProvider)
    val latestFontScale by rememberUpdatedState(commentFontSizeScale)
    val snapshots = remember(comments, recordedPlaybackFence.identity) { ArchivedCommentSnapshots(comments) }
    val schedule = remember(
        snapshots,
        recordedPlaybackFence.identity,
        preparedDanmakuViewRef.value,
    ) { ArchivedCommentSchedule() }
    LaunchedEffect(snapshots, recordedPlaybackFence.identity) { snapshots.observe(recordedPlaybackFence) }

    // Chase playback may populate/merge the list after this coroutine starts.
    // Read the latest immutable snapshot; never copy the whole list on a timer.
    LaunchedEffect(
        snapshots,
        preparedDanmakuViewRef.value,
        isPlaying,
        isCommentEnabled,
        recordedPlaybackFence.identity,
    ) {
        val surface = preparedDanmakuViewRef.value ?: return@LaunchedEffect
        val view = surface.handle
        if (!shouldPollArchivedComments(
                isPlaying = isPlaying,
                isCommentEnabled = isCommentEnabled,
                fenceActive = recordedPlaybackFence.accepts(),
                hasPreparedView = true,
            )
        ) {
            return@LaunchedEffect
        }
        while (isActive) {
            if (!recordedPlaybackFence.accepts() || preparedDanmakuViewRef.value !== surface) {
                return@LaunchedEffect
            }
            val currentSec = latestPositionProvider() / 1000.0 // ExoPlayer access stays on Main.
            val commentsSnapshot = snapshots.current
            val fontScale = latestFontScale
            withContext(Dispatchers.Default) {
                view.ifActive { activeView ->
                    if (!recordedPlaybackFence.accepts() || !activeView.isPrepared ||
                        preparedDanmakuViewRef.value !== surface
                    ) return@ifActive
                    val batch = schedule.next(commentsSnapshot, currentSec)
                    if (batch.reset) {
                        if (!recordedPlaybackFence.accepts()) return@ifActive
                        activeView.clearAll()
                        Log.i(TAG, "Comment overlay seek reset. [current_sec=$currentSec, comments=${commentsSnapshot.size}]")
                    }
                    batch.comments.forEach { comment ->
                        if (recordedPlaybackFence.accepts()) {
                            val showTimeMs = activeView.currentTimeMs + ((comment.time - currentSec) * 1000).toLong()
                            activeView.addDanmaku(createDanmakuItem(comment, fontScale, density, colorCache, showTimeMs))
                        }
                    }
                }
            }
            delay(500)
        }
    }

    DanmakuOverlay(
        modifier = modifier,
        useSoftwareRendering = useSoftwareRendering,
        engineMode = commentEngine,
        speed = commentSpeed,
        opacity = commentOpacity,
        maxLines = commentMaxLines,
        sessionKey = recordedPlaybackFence.identity,
        running = isPlaying && isCommentEnabled && recordedPlaybackFence.accepts(),
        onViewCreated = { handle ->
            if (recordedPlaybackFence.accepts()) {
                danmakuViewRef.value = handle
                preparedDanmakuViewRef.value = null
            }
        },
        onViewPrepared = { handle ->
            if (recordedPlaybackFence.accepts() && danmakuViewRef.value === handle) {
                handle.ifActive {
                    if (preparedDanmakuViewRef.value?.handle === handle) it.clearAll()
                    preparedDanmakuViewRef.value = PreparedCommentSurface(handle)
                }
            }
        },
        onViewReleased = { handle ->
            if (preparedDanmakuViewRef.value?.handle?.view === handle.view) preparedDanmakuViewRef.value = null
            if (danmakuViewRef.value?.view === handle.view) danmakuViewRef.value = null
        },
    )
}

/**
 * コメントのインスタンスを作成する
 * View の release と直列化したバックグラウンド処理から呼び、事前計算した density を使う。
 */
private fun createDanmakuItem(
    comment: ArchivedComment,
    fontSizeScale: Float,
    density: Float,
    colorCache: MutableMap<String, Int>,
    showTimeMs: Long,
): DanmakuItem = jikkyoDanmaku(
    comment.text, comment.color, comment.size, jikkyoDanmakuType(comment.type),
    32f * fontSizeScale * density, showTimeMs, colorCache,
)
