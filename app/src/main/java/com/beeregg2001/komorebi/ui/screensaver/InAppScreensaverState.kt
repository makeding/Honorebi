package com.beeregg2001.komorebi.ui.screensaver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.PowerManager
import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.delay

@Stable
internal class InAppScreensaverState {
    var isVisible by mutableStateOf(false)
        internal set

    fun dismiss() {
        isVisible = false
        ScreensaverInteractionTracker.onUserInteraction()
    }
}

/** Idle belongs to a resumed, interactive session, never to time spent asleep. */
@Composable
internal fun rememberInAppScreensaverState(
    enabled: Boolean,
    timeoutMinutes: Int,
    hasImages: Boolean,
): InAppScreensaverState {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val state = remember { InAppScreensaverState() }
    var eligible by remember(context, lifecycleOwner) { mutableStateOf(false) }
    val lastInteraction by ScreensaverInteractionTracker.lastInteractionMillis.collectAsState()
    val playbackActive by ScreensaverInteractionTracker.playbackActive.collectAsState()

    DisposableEffect(context, lifecycleOwner) {
        val powerManager = context.getSystemService(PowerManager::class.java)
        var resumed = lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        var interactive = powerManager.isInteractive

        fun updateEligibility() {
            val next = resumed && interactive
            if (next && !eligible) ScreensaverInteractionTracker.onUserInteraction()
            eligible = next
            // Dismiss synchronously, before a stale timer or the next frame can show it.
            if (!next) state.isVisible = false
        }

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    Intent.ACTION_SCREEN_OFF -> interactive = false
                    Intent.ACTION_SCREEN_ON -> interactive = true
                    else -> return
                }
                updateEligibility()
            }
        }
        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    resumed = true
                    interactive = powerManager.isInteractive
                }
                Lifecycle.Event.ON_PAUSE, Lifecycle.Event.ON_STOP, Lifecycle.Event.ON_DESTROY -> resumed = false
                else -> return@LifecycleEventObserver
            }
            updateEligibility()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        updateEligibility()
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            context.unregisterReceiver(receiver)
            eligible = false
            state.isVisible = false
        }
    }

    LaunchedEffect(enabled, timeoutMinutes, hasImages, eligible, playbackActive, lastInteraction) {
        state.isVisible = false
        if (!enabled || !hasImages || !eligible || playbackActive) return@LaunchedEffect
        val deadline = lastInteraction + timeoutMinutes.coerceAtLeast(1) * 60_000L
        val remaining = deadline - SystemClock.elapsedRealtime()
        if (remaining > 0) delay(remaining)
        // Lifecycle/input can change before recomposition cancels this old effect.
        if (eligible &&
            !ScreensaverInteractionTracker.playbackActive.value &&
            ScreensaverInteractionTracker.lastInteractionMillis.value == lastInteraction &&
            SystemClock.elapsedRealtime() >= deadline
        ) state.isVisible = true
    }
    return state
}
