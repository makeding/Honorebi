package com.beeregg2001.komorebi.ui.main

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
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

internal data class NetworkCapabilitySnapshot(
    val transport: NetworkTransport,
    val isDefault: Boolean,
)

internal fun networkSnapshot(
    hasWifi: Boolean = false,
    hasEthernet: Boolean = false,
    isDefault: Boolean = false,
): NetworkCapabilitySnapshot = NetworkCapabilitySnapshot(
    transport = when {
        hasWifi -> NetworkTransport.WIFI
        hasEthernet -> NetworkTransport.ETHERNET
        else -> NetworkTransport.OTHER
    },
    isDefault = isDefault,
)

internal fun resolveNetworkConnectionStatus(
    networks: List<NetworkCapabilitySnapshot>,
): NetworkConnectionStatus {
    val selected = networks.firstOrNull { it.isDefault }
        ?: networks.firstOrNull { it.transport == NetworkTransport.ETHERNET }
        ?: networks.firstOrNull { it.transport == NetworkTransport.WIFI }
        ?: networks.firstOrNull()
        ?: return NetworkConnectionStatus.Disconnected

    return NetworkConnectionStatus(transport = selected.transport, isAvailable = true)
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
        mutableStateOf(connectivityManager.getDefaultConnectionStatus())
    }

    DisposableEffect(connectivityManager, mainHandler) {
        val knownTransports = linkedMapOf<Network, NetworkTransport>()

        fun publishState() {
            val defaultNetwork = connectivityManager.activeNetwork
            state.value = resolveNetworkConnectionStatus(
                knownTransports.map { (network, transport) ->
                    NetworkCapabilitySnapshot(
                        transport = transport,
                        isDefault = network == defaultNetwork,
                    )
                },
            )
        }

        fun updateNetwork(network: Network, capabilities: NetworkCapabilities) {
            mainHandler.post {
                knownTransports[network] = capabilities.toNetworkTransport()
                publishState()
            }
        }

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                mainHandler.post {
                    connectivityManager.getNetworkCapabilities(network)?.let { capabilities ->
                        knownTransports[network] = capabilities.toNetworkTransport()
                    }
                    publishState()
                }
            }

            override fun onLost(network: Network) {
                mainHandler.post {
                    knownTransports.remove(network)
                    publishState()
                }
            }

            override fun onUnavailable() {
                mainHandler.post(::publishState)
            }

            override fun onCapabilitiesChanged(
                network: Network,
                networkCapabilities: NetworkCapabilities,
            ) {
                updateNetwork(network, networkCapabilities)
            }
        }

        connectivityManager.activeNetwork?.let { network ->
            connectivityManager.getNetworkCapabilities(network)?.let { capabilities ->
                knownTransports[network] = capabilities.toNetworkTransport()
            }
        }
        publishState()

        val request = NetworkRequest.Builder().build()
        runCatching { connectivityManager.registerNetworkCallback(request, callback) }

        onDispose {
            runCatching { connectivityManager.unregisterNetworkCallback(callback) }
        }
    }

    return state
}

private fun ConnectivityManager.getDefaultConnectionStatus(): NetworkConnectionStatus {
    val defaultNetwork = activeNetwork ?: return NetworkConnectionStatus.Disconnected
    val capabilities = getNetworkCapabilities(defaultNetwork)
        ?: return NetworkConnectionStatus.Disconnected

    return NetworkConnectionStatus(
        transport = capabilities.toNetworkTransport(),
        isAvailable = true,
    )
}

private fun NetworkCapabilities.toNetworkTransport(): NetworkTransport = networkSnapshot(
    hasWifi = hasTransport(NetworkCapabilities.TRANSPORT_WIFI),
    hasEthernet = hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET),
).transport
