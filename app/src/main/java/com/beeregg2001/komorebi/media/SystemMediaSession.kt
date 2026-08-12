package com.beeregg2001.komorebi.media

import android.content.Context
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
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
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
    isLoading: Boolean = player == null,
    onPrevious: (() -> Unit)? = null,
    onNext: (() -> Unit)? = null,
    onSeekRelative: ((Long) -> Unit)? = null,
    onStop: (() -> Unit)? = null
) {
    val context = LocalContext.current.applicationContext
    val controller = LocalSystemMediaSessionController.current
    val epoch = LocalSystemMediaSessionEpoch.current
    val currentOnPrevious = rememberUpdatedState(onPrevious)
    val currentOnNext = rememberUpdatedState(onNext)
    val currentOnSeekRelative = rememberUpdatedState(onSeekRelative)
    val currentOnStop = rememberUpdatedState(onStop)
    val hasPrevious = onPrevious != null
    val hasNext = onNext != null
    val canSeekRelative = onSeekRelative != null
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
                setArtworkUri(artworkUrl.toArtworkUriOrNull())
                if (artworkData != null) {
                    setArtworkData(artworkData!!, MediaMetadata.PICTURE_TYPE_FRONT_COVER)
                }
            }
            .build()
    }

    DisposableEffect(controller, epoch, player, metadata, hasPrevious, hasNext, canSeekRelative, isLoading) {
        // A null player is an expected handoff/preparing state. The root-owned
        // controller intentionally retains its previous MediaSession instead
        // of replacing it with a short-lived platform session.
        val attachment = if (controller != null && epoch != null) controller.attach(
            epoch = epoch,
            player = player,
            metadata = metadata,
            isLoading = isLoading,
            onPrevious = { currentOnPrevious.value?.invoke() },
            onNext = { currentOnNext.value?.invoke() },
            onSeekRelative = { deltaMilliseconds -> currentOnSeekRelative.value?.invoke(deltaMilliseconds) },
            canSeekRelative = canSeekRelative,
            onStop = { currentOnStop.value?.invoke() },
            hasPrevious = hasPrevious,
            hasNext = hasNext,
        ) else null
        onDispose { controller?.detach(attachment) }
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

internal class SystemSessionPlayer(
    player: Player,
    private val metadata: MediaMetadata,
    private val forceLoading: Boolean,
    private val onPrevious: () -> Unit,
    private val onNext: () -> Unit,
    private val onSeekRelative: (Long) -> Unit,
    internal val canSeekRelative: Boolean,
    private val onStop: () -> Unit,
    private val hasPrevious: Boolean,
    private val hasNext: Boolean
) : ForwardingPlayer(player) {

    internal fun seekRelative(deltaMilliseconds: Long) = onSeekRelative(deltaMilliseconds)

    override fun getMediaMetadata(): MediaMetadata = metadata

    override fun getPlaybackState(): Int =
        if (forceLoading) Player.STATE_BUFFERING else super.getPlaybackState()

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
