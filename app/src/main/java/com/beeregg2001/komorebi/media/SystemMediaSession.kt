package com.beeregg2001.komorebi.media

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaMetadata as PlatformMediaMetadata
import android.media.session.MediaSession as PlatformMediaSession
import android.media.session.PlaybackState
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaSession
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.beeregg2001.komorebi.MainActivity
import com.beeregg2001.komorebi.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/**
 * Publishes the player that is currently visible to Android's system media controls.
 *
 * Playback continues to be owned by the existing screen/ViewModel. This layer only
 * advertises its state and forwards commands, so releasing the session never releases
 * the underlying ExoPlayer.
 */
@Composable
fun SystemMediaSession(
    player: Player?,
    title: String,
    subtitle: String? = null,
    artworkUrl: String? = null,
    mediaType: Int = MediaMetadata.MEDIA_TYPE_VIDEO,
    onPrevious: (() -> Unit)? = null,
    onNext: (() -> Unit)? = null,
    onStop: (() -> Unit)? = null
) {
    val context = LocalContext.current.applicationContext
    val currentOnPrevious = rememberUpdatedState(onPrevious)
    val currentOnNext = rememberUpdatedState(onNext)
    val currentOnStop = rememberUpdatedState(onStop)
    val hasPrevious = onPrevious != null
    val hasNext = onNext != null
    val artworkDataState = androidx.compose.runtime.remember(artworkUrl) {
        androidx.compose.runtime.mutableStateOf<ByteArray?>(null)
    }
    androidx.compose.runtime.LaunchedEffect(artworkUrl) {
        artworkDataState.value = loadArtworkData(context, artworkUrl)
    }
    val artworkData = artworkDataState.value
    val metadata = remember(title, subtitle, artworkUrl, artworkData, mediaType) {
        MediaMetadata.Builder()
            .setTitle(title)
            .setDisplayTitle(title)
            .setSubtitle(subtitle?.takeIf { it.isNotBlank() })
            .setArtist(subtitle?.takeIf { it.isNotBlank() })
            .setMediaType(mediaType)
            .setIsPlayable(true)
            .apply {
                if (artworkData != null) {
                    setArtworkData(artworkData!!, MediaMetadata.PICTURE_TYPE_FRONT_COVER)
                } else {
                    setArtworkUri(artworkUrl.toArtworkUriOrNull())
                }
            }
            .build()
    }

    DisposableEffect(player, metadata, hasPrevious, hasNext) {
        if (player == null) {
            return@DisposableEffect onDispose { }
        }

        val attachment = SystemMediaSessionRegistry.attach(
            context = context,
            player = player,
            metadata = metadata,
            onPrevious = { currentOnPrevious.value?.invoke() },
            onNext = { currentOnNext.value?.invoke() },
            onStop = { currentOnStop.value?.invoke() },
            hasPrevious = hasPrevious,
            hasNext = hasNext
        )
        onDispose { SystemMediaSessionRegistry.detach(attachment) }
    }
}

/**
 * Keeps media buttons useful while no playback screen is open. Both skip directions
 * intentionally start the default live channel; navigation becomes relative only
 * after live playback has started.
 */
@Composable
fun IdleSystemMediaSession(
    enabled: Boolean,
    onStartDefaultChannel: () -> Unit
) {
    val context = LocalContext.current.applicationContext
    val currentOnStart = rememberUpdatedState(onStartDefaultChannel)
    val appArtwork = remember(context) {
        ContextCompat.getDrawable(context, R.mipmap.ic_launcher)
            ?.toBitmap(width = 256, height = 256, config = Bitmap.Config.ARGB_8888)
    }

    DisposableEffect(context, enabled) {
        if (!enabled) {
            return@DisposableEffect onDispose { }
        }

        val session = PlatformMediaSession(context, "HonorebiIdle").apply {
            setCallback(object : PlatformMediaSession.Callback() {
                override fun onPlay() = currentOnStart.value.invoke()

                override fun onSkipToPrevious() = currentOnStart.value.invoke()

                override fun onSkipToNext() = currentOnStart.value.invoke()
            })
            setPlaybackState(
                PlaybackState.Builder()
                    .setActions(
                        PlaybackState.ACTION_PLAY or
                            PlaybackState.ACTION_SKIP_TO_PREVIOUS or
                            PlaybackState.ACTION_SKIP_TO_NEXT
                    )
                    .setState(PlaybackState.STATE_STOPPED, 0L, 0f)
                    .build()
            )
            setMetadata(
                PlatformMediaMetadata.Builder()
                    .putString(PlatformMediaMetadata.METADATA_KEY_TITLE, "Honorebi")
                    .putString(PlatformMediaMetadata.METADATA_KEY_DISPLAY_TITLE, "Honorebi")
                    .apply {
                        if (appArtwork != null) {
                            putBitmap(PlatformMediaMetadata.METADATA_KEY_ART, appArtwork)
                            putBitmap(PlatformMediaMetadata.METADATA_KEY_ALBUM_ART, appArtwork)
                        }
                    }
                    .build()
            )
            isActive = true
        }
        onDispose { session.release() }
    }
}

private fun String?.toArtworkUriOrNull(): Uri? =
    this?.trim()?.takeIf { it.isNotEmpty() }?.let(Uri::parse)

private suspend fun loadArtworkData(context: Context, artworkUrl: String?): ByteArray? {
    val url = artworkUrl?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val result = context.imageLoader.execute(
        ImageRequest.Builder(context)
            .data(url)
            .size(320, 320)
            .allowHardware(false)
            .build()
    ) as? SuccessResult ?: return null
    return withContext(Dispatchers.Default) {
        val bitmap = result.drawable.toBitmap(config = Bitmap.Config.ARGB_8888)
        ByteArrayOutputStream().use { output ->
            if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) return@withContext null
            output.toByteArray()
        }
    }
}

private object SystemMediaSessionRegistry {
    private data class ActiveSession(val attachment: Any, val session: MediaSession)

    private var activeSession: ActiveSession? = null

    @Synchronized
    fun attach(
        context: Context,
        player: Player,
        metadata: MediaMetadata,
        onPrevious: () -> Unit,
        onNext: () -> Unit,
        onStop: () -> Unit,
        hasPrevious: Boolean,
        hasNext: Boolean
    ): Any {
        activeSession?.session?.release()

        val attachment = Any()
        val sessionPlayer = SystemSessionPlayer(
            player = player,
            metadata = metadata,
            onPrevious = onPrevious,
            onNext = onNext,
            onStop = onStop,
            hasPrevious = hasPrevious,
            hasNext = hasNext
        )
        val sessionActivity = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val session = MediaSession.Builder(context, sessionPlayer)
            .setSessionActivity(sessionActivity)
            .build()
        activeSession = ActiveSession(attachment, session)
        return attachment
    }

    @Synchronized
    fun detach(attachment: Any) {
        val active = activeSession ?: return
        if (active.attachment !== attachment) return
        active.session.release()
        activeSession = null
    }
}

private class SystemSessionPlayer(
    player: Player,
    private val metadata: MediaMetadata,
    private val onPrevious: () -> Unit,
    private val onNext: () -> Unit,
    private val onStop: () -> Unit,
    private val hasPrevious: Boolean,
    private val hasNext: Boolean
) : ForwardingPlayer(player) {

    override fun getMediaMetadata(): MediaMetadata = metadata

    override fun getAvailableCommands(): Player.Commands =
        super.getAvailableCommands().buildUpon()
            .addIf(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM, hasPrevious)
            .addIf(Player.COMMAND_SEEK_TO_PREVIOUS, hasPrevious)
            .addIf(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM, hasNext)
            .addIf(Player.COMMAND_SEEK_TO_NEXT, hasNext)
            .build()

    override fun isCommandAvailable(command: Int): Boolean = when (command) {
        Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
        Player.COMMAND_SEEK_TO_PREVIOUS -> hasPrevious

        Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
        Player.COMMAND_SEEK_TO_NEXT -> hasNext
        else -> super.isCommandAvailable(command)
    }

    override fun hasPreviousMediaItem(): Boolean = hasPrevious

    override fun hasNextMediaItem(): Boolean = hasNext

    override fun seekToPreviousMediaItem() = onPrevious()

    override fun seekToPrevious() = onPrevious()

    override fun seekToNextMediaItem() = onNext()

    override fun seekToNext() = onNext()

    override fun stop() {
        super.stop()
        onStop()
    }
}
