@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.beeregg2001.komorebi.ui.video.player

import android.os.SystemClock
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.beeregg2001.komorebi.ui.video.player.policy.RecordedTsRecoveryPolicy
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.CancellationException

internal fun PlaybackException.isTsDamageError(): Boolean = errorCode in setOf(
    PlaybackException.ERROR_CODE_DECODING_FAILED,
    PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
)

@Composable
internal fun RecordedTsRecovery(
    player: ExoPlayer,
    enabled: Boolean,
    networkAvailable: Boolean,
    fence: RecordedPlaybackFence,
    policy: RecordedTsRecoveryPolicy,
    bytesRead: AtomicLong,
    resolver: RecordedSeekResolver,
    onSeek: () -> Unit,
    onFailure: (String) -> Unit,
) {
    val online = rememberUpdatedState(networkAvailable)
    val currentOnSeek = rememberUpdatedState(onSeek)
    val currentOnFailure = rememberUpdatedState(onFailure)
    LaunchedEffect(networkAvailable, policy) {
        if (!networkAvailable) policy.cancelPending()
    }
    DisposableEffect(player, policy) {
        val listener = object : Player.Listener {
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                if (!playWhenReady) policy.cancelPending()
            }
        }
        player.addListener(listener)
        onDispose { policy.cancelPending(); player.removeListener(listener) }
    }
    LaunchedEffect(player, enabled, policy, fence.identity) {
        if (!enabled) return@LaunchedEffect
        while (isActive && fence.accepts()) {
            delay(1_000L)
            if (!online.value || !player.playWhenReady || player.mediaItemCount == 0 ||
                player.playbackState == Player.STATE_ENDED ||
                (player.currentTracks.isEmpty && !policy.pendingError)) {
                policy.clearObservation()
                continue
            }
            val stalled = policy.observe(
                SystemClock.elapsedRealtime(), player.currentPosition, player.bufferedPosition,
                bytesRead.get(), player.playbackState == Player.STATE_BUFFERING,
                player.isLoading, player.isPlaying,
            )
            if (!policy.pendingError && !stalled) continue
            policy.pendingError = false
            val serial = policy.seekSerial
            val failedPosition = player.currentPosition
            var resolvedTarget: Long? = null
            while (resolvedTarget == null) {
                val target = policy.nextTarget(failedPosition, player.duration) ?: break
                val resolved = try {
                    resolver.resolveForRecovery(target)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    Log.w("RecordedTsRecovery", "Recovery keyframe lookup failed", error)
                    null
                }
                if (!fence.accepts() || serial != policy.seekSerial || !online.value ||
                    !player.playWhenReady || (!policy.pendingError && player.isPlaying &&
                        player.currentPosition > failedPosition + 500L)) break
                // The index may repeatedly return the last GOP preceding a damaged region.
                if (resolved != null && resolved > failedPosition + 500L) resolvedTarget = resolved
                else Log.w("RecordedTsRecovery", "Skipping old keyframe: target_ms=$target resolved_ms=$resolved failed_ms=$failedPosition")
            }
            if (!fence.accepts() || serial != policy.seekSerial || !online.value ||
                !player.playWhenReady || (player.isPlaying && player.currentPosition > failedPosition + 500L)) continue
            if (resolvedTarget == null) {
                player.pause()
                player.stop()
                currentOnFailure.value("${policy.failureReason}（TS_RECOVERY_EXHAUSTED）。先へシークするか画質を変更してください。")
                continue
            }
            Log.w("RecordedTsRecovery", "Reopening damaged TS: position_ms=$failedPosition target_ms=$resolvedTarget bytes_read=${bytesRead.get()} loading=${player.isLoading} buffered_ms=${player.bufferedPosition}")
            currentOnSeek.value()
            // Release the damaged extractor/codec queues; an in-buffer seek can retain them.
            player.stop()
            player.seekTo(resolvedTarget)
            player.prepare()
        }
    }
}
