package com.beeregg2001.komorebi.media

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaSession
import com.beeregg2001.komorebi.MainActivity
import com.beeregg2001.komorebi.data.model.CmSkipMode
import com.beeregg2001.komorebi.data.remote.HonomiRemoteChapter
import com.beeregg2001.komorebi.data.remote.HonomiRemoteSkipDirection

/**
 * Pure ownership arbitration for the root-owned system media session.
 *
 * A newer child attachment always wins.  In particular, a disposed A screen
 * cannot detach the B screen which replaced it during an episode handoff.
 */
internal class SystemMediaSessionOwnership {
    private var activeEpoch: Long? = null
    private var activeAttachmentId: Long? = null
    private var nextAttachmentId = 0L

    fun open(epoch: Long): Boolean {
        if (activeEpoch == epoch) return false
        activeEpoch = epoch
        activeAttachmentId = null
        return true
    }

    fun acquire(epoch: Long): SystemMediaSessionAttachment? {
        if (activeEpoch != epoch) return null
        val attachment = SystemMediaSessionAttachment(epoch, ++nextAttachmentId)
        activeAttachmentId = attachment.id
        return attachment
    }

    fun isCurrent(attachment: SystemMediaSessionAttachment): Boolean =
        activeEpoch == attachment.epoch && activeAttachmentId == attachment.id

    fun detach(attachment: SystemMediaSessionAttachment): Boolean {
        if (!isCurrent(attachment)) return false
        activeAttachmentId = null
        return true
    }

    fun close(epoch: Long): Boolean {
        if (activeEpoch != epoch) return false
        activeEpoch = null
        activeAttachmentId = null
        return true
    }
}

internal class SystemMediaSessionAttachment internal constructor(
    val epoch: Long,
    internal val id: Long,
)

/**
 * Owns exactly one Media3 [MediaSession] for a root playback-session epoch.
 *
 * Android's MediaSession API is main-looper bound.  This controller is called
 * from Compose effects, and explicitly checks that contract so a future caller
 * cannot accidentally mutate the session from an IO callback.
 */
internal class SystemMediaSessionController(context: Context) {
    private val appContext = context.applicationContext
    private val ownership = SystemMediaSessionOwnership()
    private var session: MediaSession? = null
    private var playbackStateChangedListener: (() -> Unit)? = null
    // 現在前面にある再生画面が提供する、システムのメディアボタンでは表現できない操作 (絶対シーク・チャプター送り・CM スキップ) と、
    // それらを HonomiTV の進捗バーに描くためのチャプター情報を取り出すプロバイダ。
    // スナップショットではなくプロバイダを保持するのは、追いかけ再生の durationOverrideMs のように
    // 毎フレーム変わる値があり、値が変わるたびに MediaSession を attach し直すのを避けるため。
    // 画面切り替え中は古い画面のものが残りうるが、その間の配信は shouldDispatchRemoteTransport() の
    // isPlaybackSwitching ガードで止まるため、既存の seekRelative と同じ安全性になる。
    private var remoteCapabilitiesProvider: (() -> RemotePlaybackCapabilities?)? = null
    private val playerListener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            playbackStateChangedListener?.invoke()
        }
    }

    private fun remoteCapabilities(): RemotePlaybackCapabilities? = remoteCapabilitiesProvider?.invoke()

    fun openEpoch(epoch: Long) {
        requireMainLooper()
        if (ownership.open(epoch)) {
            releaseSession()
        }
    }

    fun attach(
        epoch: Long,
        player: Player?,
        metadata: MediaMetadata,
        isLoading: Boolean,
        onPrevious: () -> Unit,
        onNext: () -> Unit,
        onSeekRelative: (Long) -> Unit,
        canSeekRelative: Boolean,
        onStop: () -> Unit,
        hasPrevious: Boolean,
        hasNext: Boolean,
        remoteCapabilitiesProvider: (() -> RemotePlaybackCapabilities?)? = null,
    ): SystemMediaSessionAttachment? {
        requireMainLooper()
        val attachment = ownership.acquire(epoch) ?: return null
        // 準備中 (player == null) でも、進捗バーへ流すチャプターや長さは今の画面のものが正しいので先に取り込む。
        this.remoteCapabilitiesProvider = remoteCapabilitiesProvider

        // There is deliberately no temporary platform MediaSession while the
        // replacement player is preparing.  Keeping the previous Media3
        // session alive preserves the Cast route/controller across A -> B.
        if (player != null) {
            check(player.applicationLooper == Looper.getMainLooper()) {
                "System media-session players must use the main application looper"
            }
            val sessionPlayer = SystemSessionPlayer(
                player = player,
                metadata = metadata,
                forceLoading = isLoading,
                onPrevious = onPrevious,
                onNext = onNext,
                onSeekRelative = onSeekRelative,
                canSeekRelative = canSeekRelative,
                onStop = onStop,
                hasPrevious = hasPrevious,
                hasNext = hasNext,
            )
            val activeSession = session
            if (activeSession == null) {
                session = MediaSession.Builder(appContext, sessionPlayer)
                    .setSessionActivity(sessionActivity())
                    .build()
                sessionPlayer.addListener(playerListener)
            } else {
                activeSession.player.removeListener(playerListener)
                activeSession.setPlayer(sessionPlayer)
                sessionPlayer.addListener(playerListener)
            }
            playbackStateChangedListener?.invoke()
        }
        return attachment
    }

    fun detach(attachment: SystemMediaSessionAttachment?) {
        requireMainLooper()
        if (attachment != null) ownership.detach(attachment)
        // Child disposal must never release the root session.  It may happen
        // after its replacement already acquired ownership.
    }

    fun closeEpoch(epoch: Long) {
        requireMainLooper()
        if (ownership.close(epoch)) releaseSession()
    }

    fun close() {
        requireMainLooper()
        releaseSession()
    }

    fun play() {
        requireMainLooper()
        session?.player?.play()
    }

    fun pause() {
        requireMainLooper()
        session?.player?.pause()
    }

    fun stop() {
        requireMainLooper()
        session?.player?.stop()
    }

    fun seekRelative(deltaMilliseconds: Long) {
        requireMainLooper()
        val player = session?.player as? SystemSessionPlayer ?: return
        if (!player.canSeekRelative) return
        player.seekRelative(deltaMilliseconds)
    }

    /**
     * 進捗バーの操作による絶対シーク。
     *
     * 往復遅延のあいだに再生位置が進んでも狙った位置に着くよう、受信した位置をそのまま再生画面へ渡す。
     * 範囲のクランプは録画の実際の長さを知っている再生画面側の performSeek() が行う。
     */
    fun seekTo(positionMilliseconds: Long) {
        requireMainLooper()
        remoteCapabilities()?.seekTo?.invoke(positionMilliseconds)
    }

    fun skipChapter(direction: HonomiRemoteSkipDirection) {
        requireMainLooper()
        remoteCapabilities()?.skipChapter?.invoke(direction)
    }

    fun skipCM() {
        requireMainLooper()
        remoteCapabilities()?.skipCM?.invoke()
    }

    fun playbackState(): RemotePlaybackState? {
        requireMainLooper()
        val player = session?.player ?: return null
        val capabilities = remoteCapabilities()
        val position = player.currentPosition.takeIf { it >= 0 }
        // 追いかけ再生では ExoPlayer の duration が録画中の実尺を表さないため、再生画面が算出した長さを優先する。
        val duration = capabilities?.durationOverrideMs?.takeIf { it > 0 }
            ?: player.duration.takeIf { it != C.TIME_UNSET && it >= 0 }
        return RemotePlaybackState(
            title = player.mediaMetadata.title?.toString(),
            subtitle = player.mediaMetadata.subtitle?.toString(),
            artworkUrl = player.mediaMetadata.artworkUri?.toString(),
            isPlaying = player.isPlaying,
            isBuffering = player.playbackState == Player.STATE_BUFFERING,
            positionSeconds = position?.div(1_000.0),
            durationSeconds = duration?.div(1_000.0),
            canSeek = (player as? SystemSessionPlayer)?.canSeekRelative == true && player.isCurrentMediaItemSeekable,
            playbackRate = player.playbackParameters.speed.toDouble(),
            isChasePlayback = capabilities?.isChasePlayback == true,
            chapters = capabilities?.chapters ?: emptyList(),
            cmSkipMode = capabilities?.cmSkipMode,
        )
    }

    fun setPlaybackStateChangedListener(listener: (() -> Unit)?) {
        requireMainLooper()
        playbackStateChangedListener = listener
    }

    private fun sessionActivity(): PendingIntent = PendingIntent.getActivity(
        appContext,
        0,
        Intent(appContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun releaseSession() {
        session?.player?.removeListener(playerListener)
        session?.release()
        session = null
        remoteCapabilitiesProvider = null
        playbackStateChangedListener?.invoke()
    }

    private fun requireMainLooper() {
        check(Looper.myLooper() == Looper.getMainLooper()) {
            "SystemMediaSessionController must be used on the main looper"
        }
    }
}

/**
 * 再生画面が HonomiTV のリモコンへ公開する、メディアボタンでは表現できない操作とその材料。
 *
 * コールバックが null の操作は、その画面では単に実行されない (ライブ視聴など)。
 */
// public な SystemMediaSession() の引数として各再生画面から渡されるため、この型も public にする。
data class RemotePlaybackCapabilities(
    val seekTo: ((Long) -> Unit)? = null,
    val skipChapter: ((HonomiRemoteSkipDirection) -> Unit)? = null,
    val skipCM: (() -> Unit)? = null,
    // 追いかけ再生では ExoPlayer の duration が使えないため、再生画面が算出した長さを渡す
    val durationOverrideMs: Long? = null,
    val isChasePlayback: Boolean = false,
    val chapters: List<HonomiRemoteChapter> = emptyList(),
    val cmSkipMode: CmSkipMode? = null,
)

internal data class RemotePlaybackState(
    val title: String?,
    val subtitle: String?,
    val artworkUrl: String?,
    val isPlaying: Boolean,
    val isBuffering: Boolean,
    val positionSeconds: Double?,
    val durationSeconds: Double?,
    val canSeek: Boolean,
    val playbackRate: Double,
    val isChasePlayback: Boolean,
    val chapters: List<HonomiRemoteChapter>,
    val cmSkipMode: CmSkipMode?,
)
