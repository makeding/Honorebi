package com.beeregg2001.komorebi.ui.home

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

/** Restore the latest destination after returning from an external system/app screen. */
@Composable
internal fun HomeResumeFocusEffect(onRestore: () -> Unit) {
    val owner = LocalLifecycleOwner.current
    val restore = rememberUpdatedState(onRestore)
    DisposableEffect(owner) {
        var wasPaused = false
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> {
                    wasPaused = true
                }
                Lifecycle.Event.ON_RESUME -> if (wasPaused) {
                    wasPaused = false
                    restore.value()
                }
                else -> Unit
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose {
            owner.lifecycle.removeObserver(observer)
        }
    }
}
