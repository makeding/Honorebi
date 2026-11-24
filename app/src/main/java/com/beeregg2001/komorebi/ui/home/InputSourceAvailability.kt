package com.beeregg2001.komorebi.ui.home

import android.media.tv.TvInputManager
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.beeregg2001.komorebi.ui.main.remoteInputs

internal fun showInputSourceAction(hasInputs: Boolean, hasSystemPicker: Boolean): Boolean =
    hasInputs || hasSystemPicker

@Composable
internal fun hasVisibleTvInputs(enabled: Boolean, hideEmpty: Boolean, showTuners: Boolean): Boolean {
    val context = LocalContext.current
    var generation by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { generation++ }
    DisposableEffect(context) {
        val manager = context.getSystemService(TvInputManager::class.java)
        val callback = object : TvInputManager.TvInputCallback() {
            override fun onInputAdded(inputId: String) { generation++ }
            override fun onInputRemoved(inputId: String) { generation++ }
            override fun onInputUpdated(inputId: String) { generation++ }
            override fun onInputStateChanged(inputId: String, state: Int) { generation++ }
        }
        manager?.registerCallback(callback, Handler(Looper.getMainLooper()))
        onDispose { manager?.unregisterCallback(callback) }
    }
    val available by produceState(false, enabled, hideEmpty, showTuners, generation) {
        value = enabled && remoteInputs(context, hideEmpty, showTuners, null).json.size() > 0
    }
    return available
}
