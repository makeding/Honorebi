package com.beeregg2001.komorebi.media

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Looper
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaSession
import com.beeregg2001.komorebi.MainActivity

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
        onStop: () -> Unit,
        hasPrevious: Boolean,
        hasNext: Boolean,
    ): SystemMediaSessionAttachment? {
        requireMainLooper()
        val attachment = ownership.acquire(epoch) ?: return null

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
                onStop = onStop,
                hasPrevious = hasPrevious,
                hasNext = hasNext,
            )
            val activeSession = session
            if (activeSession == null) {
                session = MediaSession.Builder(appContext, sessionPlayer)
                    .setSessionActivity(sessionActivity())
                    .build()
            } else {
                activeSession.setPlayer(sessionPlayer)
            }
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

    private fun sessionActivity(): PendingIntent = PendingIntent.getActivity(
        appContext,
        0,
        Intent(appContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun releaseSession() {
        session?.release()
        session = null
    }

    private fun requireMainLooper() {
        check(Looper.myLooper() == Looper.getMainLooper()) {
            "SystemMediaSessionController must be used on the main looper"
        }
    }
}
