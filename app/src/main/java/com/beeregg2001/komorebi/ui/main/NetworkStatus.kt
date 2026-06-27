package com.beeregg2001.komorebi.ui.main

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

@Composable
fun rememberNetworkAvailableState(): State<Boolean> {
    val context = LocalContext.current.applicationContext
    val connectivityManager = remember(context) {
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    }
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    val state = remember(connectivityManager) {
        mutableStateOf(connectivityManager.isNetworkAvailable())
    }

    DisposableEffect(connectivityManager, mainHandler) {
        val updateState = {
            mainHandler.post {
                state.value = connectivityManager.isNetworkAvailable()
            }
        }

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                updateState()
            }

            override fun onLost(network: Network) {
                updateState()
            }

            override fun onUnavailable() {
                updateState()
            }
        }

        runCatching { connectivityManager.registerDefaultNetworkCallback(callback) }
        updateState()

        onDispose {
            runCatching { connectivityManager.unregisterNetworkCallback(callback) }
        }
    }

    return state
}

private fun ConnectivityManager.isNetworkAvailable(): Boolean {
    val network = activeNetwork ?: return false
    return getNetworkCapabilities(network) != null
}
