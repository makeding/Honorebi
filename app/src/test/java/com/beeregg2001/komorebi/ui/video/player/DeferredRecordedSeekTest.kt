@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.beeregg2001.komorebi.ui.video.player

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.Player
import androidx.media3.common.FlagSet
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.lang.reflect.Proxy

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [28])
class DeferredRecordedSeekTest {
    @get:Rule val compose = createComposeRule()

    @Test fun notReadyConflatesAndBecomingSeekableCommitsOnlyOnce() {
        val queue = DeferredRecordedSeek()
        val fake = SeekablePlayer()
        val commits = mutableListOf<Long>()
        compose.setContent { AwaitRecordedSeek(queue, fake.player, "source", { true }, commits::add) }
        compose.runOnIdle {
            queue.request(10_000L)
            fake.event()
            assertEquals(10_000L, queue.targetMs)
            queue.request(40_000L)
            fake.seekable = true
            fake.event()
            fake.event()
            assertEquals(listOf(40_000L), commits)
            assertNull(queue.targetMs)
        }
    }

    @Test fun changingSourceAndLeavingCompositionDiscardPendingTargets() {
        val queue = DeferredRecordedSeek()
        val fake = SeekablePlayer()
        val identity = mutableStateOf("old")
        val visible = mutableStateOf(true)
        val commits = mutableListOf<Long>()
        compose.setContent {
            if (visible.value) AwaitRecordedSeek(queue, fake.player, identity.value, { true }, commits::add)
        }
        compose.runOnIdle { queue.request(90_000L); identity.value = "new" }
        compose.runOnIdle { assertNull(queue.targetMs); queue.request(25_000L); visible.value = false }
        compose.runOnIdle {
            fake.seekable = true
            fake.event()
            assertTrue(commits.isEmpty())
            assertTrue(fake.listeners.isEmpty())
            assertNull(queue.targetMs)
        }
    }

    @Test fun pauseAndStalePlaybackNeverCommitAfterReadiness() {
        val queue = DeferredRecordedSeek()
        val fake = SeekablePlayer()
        val commits = mutableListOf<Long>()
        var current = true
        val owner = object : LifecycleOwner {
            val registry = LifecycleRegistry.createUnsafe(this)
            override val lifecycle: Lifecycle = registry
        }
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }
        compose.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                AwaitRecordedSeek(queue, fake.player, "source", { current }, commits::add)
            }
        }
        compose.runOnIdle {
            queue.request(10_000L)
            owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
            fake.seekable = true
            fake.event()
            assertNull(queue.targetMs)
            owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
            queue.request(20_000L)
            current = false
            fake.event()
            assertTrue(commits.isEmpty())
            assertNull(queue.targetMs)
        }
    }

    private class SeekablePlayer {
        var seekable = false
        val listeners = mutableListOf<Player.Listener>()
        val player = Proxy.newProxyInstance(Player::class.java.classLoader, arrayOf(Player::class.java)) { proxy, method, args ->
            when (method.name) {
                "isCurrentMediaItemSeekable" -> seekable
                "addListener" -> { listeners.add(args!![0] as Player.Listener); null }
                "removeListener" -> { listeners.remove(args!![0] as Player.Listener); null }
                "equals" -> proxy === args?.get(0)
                "hashCode" -> System.identityHashCode(proxy)
                "toString" -> "SeekablePlayer"
                else -> error("Unexpected Player access: ${method.name}")
            }
        } as Player
        fun event() {
            val events = Player.Events(FlagSet.Builder().add(Player.EVENT_TIMELINE_CHANGED).build())
            listeners.toList().forEach { it.onEvents(player, events) }
        }
    }
}
