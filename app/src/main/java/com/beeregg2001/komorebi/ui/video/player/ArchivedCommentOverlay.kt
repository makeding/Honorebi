package com.beeregg2001.komorebi.ui.video.player

import android.util.Log
import android.view.View
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.beeregg2001.komorebi.data.model.ArchivedComment
import com.beeregg2001.komorebi.ui.live.LiveCommentOverlay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import master.flame.danmaku.controller.IDanmakuView
import master.flame.danmaku.danmaku.model.BaseDanmaku
import android.graphics.Color as AndroidColor
import kotlin.math.abs

private const val TAG = "ArchivedCommentOverlay"

@Composable
fun ArchivedCommentOverlay(
    modifier: Modifier = Modifier,
    comments: List<ArchivedComment>,
    currentPositionProvider: () -> Long,
    isPlaying: Boolean,
    isCommentEnabled: Boolean,
    commentSpeed: Float,
    commentFontSizeScale: Float,
    commentOpacity: Float,
    commentMaxLines: Int,
    useSoftwareRendering: Boolean = false
) {
    val danmakuViewRef = remember { mutableStateOf<IDanmakuView?>(null) }

    // UIスレッドでしか取得できない画面密度(density)を事前に計算しておく
    val context = LocalContext.current
    val density = remember(context) { context.resources.displayMetrics.density }
    val colorCache = remember { HashMap<String, Int>() }

    LaunchedEffect(isPlaying, isCommentEnabled) {
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

    // コメント同期・描画予約ロジック
    LaunchedEffect(Unit) {

        var currentIndex = 0
        var lastPlayerSec = withContext(Dispatchers.Main) { currentPositionProvider() / 1000.0 }
        val lookAheadSec = 2.0 // 2秒先まで先読みして描画予約する

        while (isActive) {
            if (latestIsPlaying && latestIsCommentEnabled) {
                // ★ 修正1: ExoPlayerへのアクセス(currentPositionProvider)は必ずメインスレッドで行う
                val currentSec =
                    withContext(Dispatchers.Main) { currentPositionProvider() / 1000.0 }
                val commentsSnapshot = comments.toList()

                // ★ 修正2: 重い検索処理やインスタンス化はバックグラウンドスレッドで行う
                withContext(Dispatchers.Default) {
                    // シーク検知: 現在位置と最後に処理した時間が1.5秒以上乖離している場合
                    if (abs(currentSec - lastPlayerSec) > 1.5) {
                        danmakuViewRef.value?.removeAllDanmakus(true)
                        currentIndex = commentsSnapshot.findFirstIndexAtOrAfter(currentSec)
                        Log.i(
                            TAG,
                            "Comment overlay seek reset. [current_sec=$currentSec, index=$currentIndex, " +
                                "comments=${commentsSnapshot.size}]"
                        )
                    }

                    danmakuViewRef.value?.let { view ->
                        if (view.isPrepared) {
                            val targetTimeSec = currentSec + lookAheadSec
                            val danmakusToAdd = mutableListOf<BaseDanmaku>()
                            var eligibleCommentCount = 0

                            if (currentIndex > commentsSnapshot.size) {
                                currentIndex = commentsSnapshot.findFirstIndexAtOrAfter(currentSec)
                            }
                            while (currentIndex < commentsSnapshot.size) {
                                val comment = commentsSnapshot[currentIndex]
                                if (comment.time > targetTimeSec) break // 2秒以上先ならループを抜ける

                                // シーク直後の過去すぎるコメントを捨てる
                                if (comment.time >= currentSec - 0.5) {
                                    eligibleCommentCount++
                                    val d =
                                        createDanmaku(
                                            view,
                                            comment,
                                            commentFontSizeScale,
                                            density,
                                            colorCache
                                        )
                                    if (d != null) {
                                        // 現在時刻との差分を計算し、DanmakuViewの内部時計で正確な表示時刻を予約
                                        val futureMs = ((comment.time - currentSec) * 1000).toLong()
                                        d.setTime(view.currentTime + futureMs)
                                        danmakusToAdd.add(d)
                                    }
                                }
                                currentIndex++
                            }

                            // コメントの追加(addDanmaku)は内部的にスレッドセーフなのでバックグラウンドから呼んでもOK
                            danmakusToAdd.forEach { view.addDanmaku(it) }
                            if (eligibleCommentCount > 0) {
                                Log.i(
                                    TAG,
                                    "Comment overlay scheduled. [current_sec=$currentSec, target_sec=$targetTimeSec, " +
                                        "eligible=$eligibleCommentCount, added=${danmakusToAdd.size}, index=$currentIndex, " +
                                        "total=${commentsSnapshot.size}, first=${commentsSnapshot.first().time}, " +
                                        "last=${commentsSnapshot.last().time}]"
                                )
                            }
                        }
                    }
                }
                lastPlayerSec = currentSec
            }
            delay(500)
        }
    }

    LiveCommentOverlay(
        modifier = modifier,
        useSoftwareRendering = useSoftwareRendering,
        speed = commentSpeed,
        opacity = commentOpacity,
        maxLines = commentMaxLines,
        onViewCreated = { view ->
            danmakuViewRef.value = view
            if (!isPlaying || !isCommentEnabled) view.pause()
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

private fun List<ArchivedComment>.findFirstIndexAtOrAfter(timeSec: Double): Int {
    var low = 0
    var high = size
    while (low < high) {
        val mid = (low + high) ushr 1
        if (this[mid].time < timeSec) {
            low = mid + 1
        } else {
            high = mid
        }
    }
    return low
}
