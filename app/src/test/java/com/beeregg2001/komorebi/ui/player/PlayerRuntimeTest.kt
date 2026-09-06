package com.beeregg2001.komorebi.ui.player

import android.os.Looper
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer
import java.lang.reflect.Proxy
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class PlayerRuntimeTest {
    private class PlayerProbe {
        lateinit var listener: Player.Listener
        val calls = mutableListOf<String>()
        var playbackState = Player.STATE_IDLE
        var failStop = false
        val player = Proxy.newProxyInstance(
            ExoPlayer::class.java.classLoader, arrayOf(ExoPlayer::class.java)
        ) { proxy, method, args ->
            when (method.name) {
                "getApplicationLooper" -> Looper.getMainLooper()
                "getPlaybackState" -> playbackState
                "isPlaying", "isLoading" -> false
                "getVideoSize" -> VideoSize.UNKNOWN
                "getCurrentTracks" -> Tracks.EMPTY
                "getPlayerError" -> null
                "addListener" -> { listener = args!![0] as Player.Listener; calls.add(method.name); null }
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.get(0)
                "toString" -> "PlayerProbe"
                else -> {
                    calls.add(method.name)
                    if (method.name == "stop" && failStop) error("stop failed")
                    null
                }
            }
        } as ExoPlayer
    }

    @Test fun removedListenerAndReleasedSlotRejectLateEvents() {
        val probe = PlayerProbe()
        val runtime = PlayerRuntime(probe.player)
        var events = 0
        val remove = runtime.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) { events++ }
        })
        probe.playbackState = Player.STATE_READY
        probe.listener.onPlaybackStateChanged(Player.STATE_READY)
        assertEquals(Player.STATE_READY, runtime.state.value.playbackState)
        assertEquals(1, events)
        remove()
        probe.listener.onPlaybackStateChanged(Player.STATE_READY)
        assertEquals(1, events)
        runtime.release()
        runtime.release()
        probe.listener.onPlaybackStateChanged(Player.STATE_READY)
        assertEquals(PlayerRuntimeState(), runtime.state.value)
        assertEquals(1, probe.calls.count { it == "addListener" })
        assertEquals(1, probe.calls.count { it == "release" })
        assertEquals(listOf("removeListener", "stop", "clearMediaItems", "release"), probe.calls.takeLast(4))
    }

    @Test fun releaseStillReleasesResourcesAndClearsStateWhenStopFails() {
        val probe = PlayerProbe()
        val runtime = PlayerRuntime(probe.player)
        probe.playbackState = Player.STATE_READY
        probe.listener.onPlaybackStateChanged(Player.STATE_READY)
        probe.failStop = true
        assertTrue(runCatching { runtime.release() }.isFailure)
        assertEquals(1, probe.calls.count { it == "release" })
        assertEquals(PlayerRuntimeState(), runtime.state.value)
        assertTrue(runCatching { runtime.play() }.isFailure)
    }

    @Test fun dualSlotsPublishAndReleaseIndependently() {
        val main = PlayerProbe()
        val dual = PlayerProbe()
        val mainRuntime = PlayerRuntime(main.player)
        val dualRuntime = PlayerRuntime(dual.player)
        mainRuntime.release()
        dual.playbackState = Player.STATE_READY
        dual.listener.onPlaybackStateChanged(Player.STATE_READY)
        assertEquals(PlayerRuntimeState(), mainRuntime.state.value)
        assertEquals(Player.STATE_READY, dualRuntime.state.value.playbackState)
        assertFalse(dual.calls.contains("release"))
        dualRuntime.release()
    }
}
