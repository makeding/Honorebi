package com.beeregg2001.komorebi.ui.video.player

import android.util.Log
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.beeregg2001.komorebi.data.model.ArchivedComment
import com.beeregg2001.komorebi.ui.player.DanmakuOverlay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
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

    // UIスレッドでしか取得できない画面密度(density)を事前に計算しておく
    val context = LocalContext.current
    val density = remember(context) { context.resources.displayMetrics.density }
    val colorCache = remember { HashMap<String, Int>() }

    LaunchedEffect(isPlaying, isCommentEnabled, recordedPlaybackFence.identity) {
        if (!recordedPlaybackFence.accepts()) return@LaunchedEffect
        danmakuViewRef.value?.let { view ->
            if (view.isPrepared) {
                if (isPlaying && isCommentEnabled) {
                    view.resume()
                } else {
                    view.pause()
                }
            }
        }
    }

    val latestIsPlaying by rememberUpdatedState(isPlaying)
    val latestIsCommentEnabled by rememberUpdatedState(isCommentEnabled)

    val latestPositionProvider by rememberUpdatedState(currentPositionProvider)
    val latestFontScale by rememberUpdatedState(commentFontSizeScale)
    val snapshots = remember(comments, recordedPlaybackFence.identity) { ArchivedCommentSnapshots(comments) }
    LaunchedEffect(snapshots, recordedPlaybackFence.identity) { snapshots.observe(recordedPlaybackFence) }

    // Chase playback may populate/merge the list after this coroutine starts.
    // Read the latest immutable snapshot; never copy the whole list on a timer.
    LaunchedEffect(snapshots, recordedPlaybackFence.identity) {
        val schedule = ArchivedCommentSchedule()
        while (isActive) {
            val view = danmakuViewRef.value
            if (latestIsPlaying && latestIsCommentEnabled && recordedPlaybackFence.accepts() && view?.isPrepared == true) {
                val currentSec = latestPositionProvider() / 1000.0 // ExoPlayer access stays on Main.
                val commentsSnapshot = snapshots.current
                val fontScale = latestFontScale
                withContext(Dispatchers.Default) {
                    if (!recordedPlaybackFence.accepts()) return@withContext
                    val batch = schedule.next(commentsSnapshot, currentSec)
                    if (batch.reset) {
                        if (!recordedPlaybackFence.accepts()) return@withContext
                        view.removeAllDanmakus(true)
                        Log.i(TAG, "Comment overlay seek reset. [current_sec=$currentSec, comments=${commentsSnapshot.size}]")
                    }
                    batch.comments.forEach { comment ->
                        val danmaku = createDanmaku(view, comment, fontScale, density, colorCache)
                        if (danmaku != null && recordedPlaybackFence.accepts() && danmakuViewRef.value === view) {
                            danmaku.setTime(view.currentTime + ((comment.time - currentSec) * 1000).toLong())
                            view.addDanmaku(danmaku)
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
        speed = commentSpeed,
        opacity = commentOpacity,
        maxLines = commentMaxLines,
        sessionKey = recordedPlaybackFence.identity,
        onViewCreated = { view ->
            if (recordedPlaybackFence.accepts()) {
                danmakuViewRef.value = view
                if (!isPlaying || !isCommentEnabled) view.pause()
            }
        }
    )
}

/**
 * コメントのインスタンスを作成する
 * UIスレッド外から呼ばれるため、View(Context)への直接アクセスを避けて引数からdensityを受け取る
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
