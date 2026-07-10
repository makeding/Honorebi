package com.beeregg2001.komorebi.ui.main

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

enum class NetworkTransport {
    WIFI,
    ETHERNET,
    OTHER,
    DISCONNECTED
}

data class NetworkConnectionStatus(
    val transport: NetworkTransport,
    val isAvailable: Boolean
) {
    companion object {
        val Disconnected = NetworkConnectionStatus(NetworkTransport.DISCONNECTED, false)
    }
}

@Composable
fun rememberNetworkAvailableState(): State<Boolean> {
    val connectionStatus = rememberNetworkConnectionStatus()
    return remember { derivedStateOf { connectionStatus.value.isAvailable } }
}

@Composable
fun rememberNetworkConnectionStatus(): State<NetworkConnectionStatus> {
    val context = LocalContext.current.applicationContext
    val connectivityManager = remember(context) {
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    }
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    val state = remember(connectivityManager) {
        mutableStateOf(connectivityManager.getConnectionStatus())
    }

    DisposableEffect(connectivityManager, mainHandler) {
        val updateState = {
            mainHandler.post {
                state.value = connectivityManager.getConnectionStatus()
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

private fun ConnectivityManager.getConnectionStatus(): NetworkConnectionStatus {
    val network = activeNetwork ?: return NetworkConnectionStatus.Disconnected
    val capabilities = getNetworkCapabilities(network)
        ?: return NetworkConnectionStatus.Disconnected

    val transport = when {
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> NetworkTransport.WIFI
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> NetworkTransport.ETHERNET
        else -> NetworkTransport.OTHER
    }

    return NetworkConnectionStatus(transport = transport, isAvailable = true)
}
