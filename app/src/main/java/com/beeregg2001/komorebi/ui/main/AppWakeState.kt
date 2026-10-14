package com.beeregg2001.komorebi.ui.main

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.PowerManager
import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

/** Shared boundary for idle, network snapshots, and backend recovery. */
@Stable
internal class AppWakeState {
    var isAwake by mutableStateOf(false)
        internal set
    var generation by mutableIntStateOf(0)
        internal set
    var sessionStartedAtMillis by mutableLongStateOf(0L)
        internal set
}

@Composable
internal fun rememberAppWakeState(onAwakeChanged: (Boolean) -> Unit = {}): AppWakeState {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val state = remember(context, lifecycleOwner) { AppWakeState() }
    val notifyAwakeChanged by rememberUpdatedState(onAwakeChanged)
    DisposableEffect(context, lifecycleOwner) {
        val powerManager = context.getSystemService(PowerManager::class.java)
        var resumed = lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        var interactive = powerManager.isInteractive
        var wasAwake = false

        fun update() {
            val next = resumed && interactive
            if (next && !state.isAwake) {
                if (wasAwake) state.generation++
                wasAwake = true
                state.sessionStartedAtMillis = SystemClock.elapsedRealtime()
            }
            if (next != state.isAwake) {
                state.isAwake = next
                notifyAwakeChanged(next)
            }
        }

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    Intent.ACTION_SCREEN_OFF -> interactive = false
                    Intent.ACTION_SCREEN_ON -> interactive = true
                    else -> return
                }
                update()
            }
        }
        ContextCompat.registerReceiver(context, receiver, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
        }, ContextCompat.RECEIVER_NOT_EXPORTED)
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    resumed = true
                    interactive = powerManager.isInteractive
                }
                Lifecycle.Event.ON_PAUSE, Lifecycle.Event.ON_STOP, Lifecycle.Event.ON_DESTROY -> resumed = false
                else -> return@LifecycleEventObserver
            }
            update()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        update()
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            context.unregisterReceiver(receiver)
            state.isAwake = false
            notifyAwakeChanged(false)
        }
    }
    return state
}
