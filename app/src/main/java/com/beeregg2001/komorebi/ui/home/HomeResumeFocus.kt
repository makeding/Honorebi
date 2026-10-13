package com.beeregg2001.komorebi.ui.home

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Restore the latest destination after returning from an external system/app screen. */
@Composable
internal fun HomeResumeFocusEffect(onRestore: () -> Unit) {
    val owner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val restore = rememberUpdatedState(onRestore)
    DisposableEffect(owner) {
        var wasPaused = false
        var pendingRestore: Job? = null
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> {
                    wasPaused = true
                    pendingRestore?.cancel()
                }
                Lifecycle.Event.ON_RESUME -> if (wasPaused) {
                    wasPaused = false
                    pendingRestore = scope.launch {
                        delay(250)
                        restore.value()
                    }
                }
                else -> Unit
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose {
            pendingRestore?.cancel()
            owner.lifecycle.removeObserver(observer)
        }
    }
}
