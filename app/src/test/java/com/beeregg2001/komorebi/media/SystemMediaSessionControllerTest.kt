package com.beeregg2001.komorebi.media

import android.app.Application
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class SystemMediaSessionControllerTest {
    @Test
    fun overlappingRootsWithSameEpoch_canAttachAndOldRootReleaseDoesNotAffectNewRoot() {
        val context = RuntimeEnvironment.getApplication()
        val oldRoot = SystemMediaSessionController(context)
        val newRoot = SystemMediaSessionController(context)
        val oldPlayer = ExoPlayer.Builder(context).build()
        val newPlayer = ExoPlayer.Builder(context).build()
        try {
            oldRoot.openEpoch(1)
            newRoot.openEpoch(1)
            val oldAttachment = attach(oldRoot, oldPlayer)
            attach(newRoot, newPlayer)
            val oldSession = session(oldRoot)
            val newSession = session(newRoot)
            assertNotEquals(oldSession.id, newSession.id)

            oldRoot.detach(oldAttachment)
            oldRoot.closeEpoch(1)
            assertNull(oldRoot.playbackState())
            assertSame(newSession, session(newRoot))
            assertNotNull(newRoot.playbackState())
            newRoot.play()
            assertTrue(newPlayer.playWhenReady)
        } finally {
            oldRoot.close()
            newRoot.close()
            oldPlayer.release()
            newPlayer.release()
        }
    }

    @Test
    fun preparingAndReplacingChild_preservesSessionAndRejectsLateDetach() {
        val context = RuntimeEnvironment.getApplication()
        val root = SystemMediaSessionController(context)
        val firstPlayer = ExoPlayer.Builder(context).build()
        val nextPlayer = ExoPlayer.Builder(context).build()
        try {
            root.openEpoch(1)
            val first = attach(root, firstPlayer)
            val originalSession = session(root)
            val preparing = attach(root, null)
            root.detach(first)
            assertSame(originalSession, session(root))
            attach(root, nextPlayer)
            root.detach(preparing)
            assertSame(originalSession, session(root))
            root.play()
            assertTrue(nextPlayer.playWhenReady)
            assertFalse(firstPlayer.playWhenReady)

            root.closeEpoch(1)
            assertNull(root.playbackState())
            // A later epoch reuses this root's ID after synchronous release.
            root.openEpoch(2)
            attach(root, nextPlayer, epoch = 2)
            assertEquals(originalSession.id, session(root).id)
        } finally {
            root.close()
            firstPlayer.release()
            nextPlayer.release()
        }
    }

    private fun attach(root: SystemMediaSessionController, player: Player?, epoch: Long = 1) =
        requireNotNull(root.attach(
            epoch = epoch,
            player = player,
            metadata = MediaMetadata.Builder().setTitle("Test").build(),
            isLoading = player == null,
            onPrevious = {}, onNext = {}, onSeekRelative = {},
            canSeekRelative = false, onStop = {}, hasPrevious = false, hasNext = false,
        ))

    private fun session(root: SystemMediaSessionController): MediaSession =
        SystemMediaSessionController::class.java.getDeclaredField("session")
            .apply { isAccessible = true }.get(root) as MediaSession
}
