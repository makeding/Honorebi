package com.beeregg2001.komorebi.ui.video.player

import android.util.Log
import android.view.SurfaceView
import android.view.ViewGroup
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import com.beeregg2001.komorebi.data.model.Channel
import com.beeregg2001.komorebi.ui.live.B60MediaPlane
import com.beeregg2001.komorebi.ui.live.B60_INITIAL_MEDIA_PLANE
import com.beeregg2001.komorebi.ui.live.DataBroadcastingRemoteCommand
import com.beeregg2001.komorebi.ui.live.DataBroadcastingWebViewOverlay
import com.beeregg2001.komorebi.ui.live.b60MediaPlane
import com.beeregg2001.komorebi.util.mmts.B60DataBroadcastingStore

@Composable
internal fun BoxScope.RecordedMediaSurface(
    isDataBroadcastingActive: Boolean,
    dataBroadcastingStore: B60DataBroadcastingStore,
    dataBroadcastingChannel: Channel,
    exoPlayer: ExoPlayer,
    dataBroadcastingRemoteCommand: DataBroadcastingRemoteCommand?,
    onRemoteCommandConsumed: (Long) -> Unit,
    onMediaPlane: (B60MediaPlane?) -> Unit,
    onBlankModeChanged: (Boolean) -> Unit,
    closeDataBroadcasting: () -> Unit,
    dataBroadcastingMediaPlane: B60MediaPlane?,
    isDataBroadcastingBlank: Boolean,
    videoWidth: Int,
    videoHeight: Int,
    pixelWidthHeightRatio: Float,
    state: VideoPlayerState,
    renderedFrameGeneration: Int,
    mainFocusRequester: FocusRequester,
    isPiPMode: Boolean,
    isSubOverlayOpen: Boolean,
) {
    if (isDataBroadcastingActive) {
        DataBroadcastingWebViewOverlay(
            store = dataBroadcastingStore,
            channel = dataBroadcastingChannel,
            currentMediaTimeSeconds = { exoPlayer.currentPosition.coerceAtLeast(0L) / 1_000.0 },
            remoteCommand = dataBroadcastingRemoteCommand,
            onRemoteCommandConsumed = onRemoteCommandConsumed,
            onStatus = { status -> Log.d("VideoPlayerScreen", "Recorded B60: $status") },
            onMediaPlane = onMediaPlane,
            onBlankModeChanged = onBlankModeChanged,
            onApplicationExited = closeDataBroadcasting,
            modifier = Modifier.fillMaxSize().zIndex(1f)
        )
    }
    val videoSurfaceModifier = if (isDataBroadcastingActive) when {
        isDataBroadcastingBlank -> Modifier.fillMaxSize().zIndex(2f)
        dataBroadcastingMediaPlane == null -> Modifier.fillMaxSize()
            .b60MediaPlane(B60_INITIAL_MEDIA_PLANE).zIndex(2f)
        dataBroadcastingMediaPlane.visible && dataBroadcastingMediaPlane.width > 0f &&
            dataBroadcastingMediaPlane.height > 0f -> Modifier.fillMaxSize()
            .b60MediaPlane(dataBroadcastingMediaPlane).zIndex(2f)
        else -> Modifier.align(Alignment.TopEnd).padding(24.dp).fillMaxWidth(0.34f)
            .aspectRatio(16f / 9f).zIndex(2f)
            .border(1.dp, Color.White.copy(alpha = 0.45f), RoundedCornerShape(4.dp))
    } else Modifier.fillMaxSize()
    AndroidView(
        factory = { context -> AspectRatioFrameLayout(context).apply {
            keepScreenOn = true
            addView(SurfaceView(context).apply { layoutParams = ViewGroup.LayoutParams(-1, -1) })
        } },
        update = { view ->
            val surfaceView = view.getChildAt(0) as SurfaceView
            exoPlayer.setVideoSurfaceView(surfaceView)
            if (videoWidth > 0 && videoHeight > 0) {
                val ratio = (videoWidth.toFloat() * pixelWidthHeightRatio) / videoHeight.toFloat()
                view.setAspectRatio(ratio)
                val targetMode = if (ratio >= 1.7f) AspectRatioFrameLayout.RESIZE_MODE_FILL else AspectRatioFrameLayout.RESIZE_MODE_FIT
                if (view.resizeMode != targetMode) view.resizeMode = targetMode
            }
        },
        onRelease = { view ->
            exoPlayer.clearVideoSurfaceView(view.getChildAt(0) as SurfaceView)
            view.keepScreenOn = false
        },
        modifier = videoSurfaceModifier.graphicsLayer {
            if (state.lCropEnabled) {
                scaleX = state.lCropZoom / 100f; scaleY = state.lCropZoom / 100f
                translationX = size.width * (state.lCropX / 100f)
                translationY = size.height * (state.lCropY / 100f)
                transformOrigin = when (state.lCropOrigin) {
                    ZoomOrigin.TopLeft -> TransformOrigin(0f, 0f); ZoomOrigin.TopRight -> TransformOrigin(1f, 0f)
                    ZoomOrigin.BottomLeft -> TransformOrigin(0f, 1f); ZoomOrigin.BottomRight -> TransformOrigin(1f, 1f)
                }
            } else {
                scaleX = 1f; scaleY = 1f; translationX = 0f; translationY = 0f
                transformOrigin = TransformOrigin.Center
            }
        }.semantics { contentDescription = if (renderedFrameGeneration > 0) "再生映像:$renderedFrameGeneration" else "映像準備中" }
            .focusRequester(mainFocusRequester)
            .focusable(!isPiPMode && !isSubOverlayOpen && state.lCropMode == LCropMode.HIDDEN)
    )
}
