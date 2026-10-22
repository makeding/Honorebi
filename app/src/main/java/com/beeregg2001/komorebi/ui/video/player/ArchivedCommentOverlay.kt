package com.beeregg2001.komorebi.ui.video.player

import android.util.Log
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.beeregg2001.komorebi.data.model.ArchivedComment
import com.beeregg2001.komorebi.ui.player.DanmakuOverlay
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import master.flame.danmaku.controller.IDanmakuView
import master.flame.danmaku.danmaku.model.BaseDanmaku
import android.graphics.Color as AndroidColor

private const val TAG = "ArchivedCommentOverlay"

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
    useSoftwareRendering: Boolean = false,
    recordedPlaybackFence: RecordedPlaybackFence,
) {
    val danmakuViewRef = remember { mutableStateOf<IDanmakuView?>(null) }
    val preparedDanmakuViewRef = remember { mutableStateOf<IDanmakuView?>(null) }

    // UIスレッドでしか取得できない画面密度(density)を事前に計算しておく
    val context = LocalContext.current
    val density = remember(context) { context.resources.displayMetrics.density }
    val colorCache = remember { HashMap<String, Int>() }

    val latestPositionProvider by rememberUpdatedState(currentPositionProvider)
    val latestFontScale by rememberUpdatedState(commentFontSizeScale)
    val snapshots = remember(comments, recordedPlaybackFence.identity) { ArchivedCommentSnapshots(comments) }
    val schedule = remember(snapshots, recordedPlaybackFence.identity) { ArchivedCommentSchedule() }
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
        if (!isPlaying || !isCommentEnabled || !recordedPlaybackFence.accepts()) {
            return@LaunchedEffect
        }
        val view = preparedDanmakuViewRef.value ?: return@LaunchedEffect
        while (isActive) {
            if (recordedPlaybackFence.accepts() && preparedDanmakuViewRef.value === view) {
                val currentSec = latestPositionProvider() / 1000.0 // ExoPlayer access stays on Main.
                val commentsSnapshot = snapshots.current
                val fontScale = latestFontScale
                val batch = schedule.next(commentsSnapshot, currentSec)
                if (batch.reset) {
                    if (!recordedPlaybackFence.accepts()) return@LaunchedEffect
                    view.removeAllDanmakus(true)
                    Log.i(TAG, "Comment overlay seek reset. [current_sec=$currentSec, comments=${commentsSnapshot.size}]")
                }
                batch.comments.forEach { comment ->
                    val danmaku = createDanmaku(view, comment, fontScale, density, colorCache)
                    if (danmaku != null && recordedPlaybackFence.accepts() && preparedDanmakuViewRef.value === view) {
                        danmaku.setTime(view.currentTime + ((comment.time - currentSec) * 1000).toLong())
                        view.addDanmaku(danmaku)
                    }
                }
            }
            delay(500)
        }
    }

    DanmakuOverlay(
        modifier = modifier,
        useSoftwareRendering = useSoftwareRendering,
        speed = commentSpeed,
        opacity = commentOpacity,
        maxLines = commentMaxLines,
        sessionKey = recordedPlaybackFence.identity,
        running = isPlaying && isCommentEnabled && recordedPlaybackFence.accepts(),
        onViewCreated = { view ->
            if (recordedPlaybackFence.accepts()) {
                danmakuViewRef.value = view
                preparedDanmakuViewRef.value = null
            }
        },
        onViewPrepared = { view ->
            if (recordedPlaybackFence.accepts() && danmakuViewRef.value === view) {
                preparedDanmakuViewRef.value = view
            }
        },
        onViewReleased = { view ->
            if (preparedDanmakuViewRef.value === view) preparedDanmakuViewRef.value = null
            if (danmakuViewRef.value === view) danmakuViewRef.value = null
        },
    )
}

/**
 * コメントのインスタンスを作成する
 * View の release と直列化するため UI スレッドから呼び、事前計算した density を使う。
 */
private fun createDanmaku(
    view: IDanmakuView,
    comment: ArchivedComment,
    fontSizeScale: Float,
    density: Float,
    colorCache: MutableMap<String, Int>
): BaseDanmaku? {
    val danmaku =
        view.config.mDanmakuFactory.createDanmaku(BaseDanmaku.TYPE_SCROLL_RL) ?: return null
    danmaku.text = comment.text
    danmaku.padding = 5

    // 引数で受け取った density を使って計算する
    danmaku.textSize = (32f * fontSizeScale) * density

    danmaku.textColor = colorCache.getOrPut(comment.color) {
        runCatching { AndroidColor.parseColor(comment.color) }
            .getOrDefault(AndroidColor.WHITE)
    }

    danmaku.textShadowColor = AndroidColor.BLACK
    return danmaku
}
