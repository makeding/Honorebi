package com.beeregg2001.komorebi.ui.main

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.net.Inet4Address
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext

internal const val NORMAL_DISCONNECT_GRACE_MS = 2_000L
internal const val WAKE_DISCONNECT_GRACE_MS = 5_000L

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
    val isVpn: Boolean = false,
    val isRoutable: Boolean = true,
)

internal data class NetworkLinkSnapshot(
    val hasIpv4: Boolean,
    val dnsServers: Set<String>,
    val domains: String?,
)

/**
 * Returns true when an already-up network gains IPv4 addressing, new DNS servers, or search domains
 * (e.g., IPv4 DHCP finishing after IPv6 RA on wake, where local LAN DNS becomes available later).
 */
internal fun didLinkGainRoutingOrDns(
    previous: NetworkLinkSnapshot?,
    current: NetworkLinkSnapshot,
): Boolean {
    if (previous == null) return false
    return (!previous.hasIpv4 && current.hasIpv4) ||
        (current.dnsServers - previous.dnsServers).isNotEmpty() ||
        (previous.domains.isNullOrBlank() && !current.domains.isNullOrBlank())
}

internal fun networkSnapshot(
    hasWifi: Boolean = false,
    hasEthernet: Boolean = false,
    isDefault: Boolean = false,
    hasCellular: Boolean = false,
    hasVpn: Boolean = false,
    hasInternet: Boolean = true,
    isSuspended: Boolean = false,
): NetworkCapabilitySnapshot = NetworkCapabilitySnapshot(
    transport = when {
        hasWifi -> NetworkTransport.WIFI
        hasEthernet -> NetworkTransport.ETHERNET
        else -> NetworkTransport.OTHER
    },
    isDefault = isDefault,
    isVpn = hasVpn,
    isRoutable = !isSuspended && (hasWifi || hasEthernet || hasCellular || hasVpn || hasInternet),
)

internal fun disconnectGraceMillis(
    wasConnected: Boolean,
    isWakeTransition: Boolean,
): Long = when {
    !wasConnected -> 0L
    isWakeTransition -> WAKE_DISCONNECT_GRACE_MS
    else -> NORMAL_DISCONNECT_GRACE_MS
}

internal fun resolveNetworkConnectionStatus(
    networks: List<NetworkCapabilitySnapshot>,
): NetworkConnectionStatus {
    val routable = networks.filter { it.isRoutable }
    if (routable.isEmpty()) return NetworkConnectionStatus.Disconnected

    val defaultNetwork = routable.firstOrNull { it.isDefault }
    val transport = if (defaultNetwork != null) {
        if (defaultNetwork.isVpn && defaultNetwork.transport == NetworkTransport.OTHER) {
            // Preserve the physical Ethernet/Wi-Fi icon when a VPN network is active.
            val physical = routable.filterNot { it.isVpn }
            physical.firstOrNull { it.transport == NetworkTransport.ETHERNET }?.transport
                ?: physical.firstOrNull { it.transport == NetworkTransport.WIFI }?.transport
                ?: defaultNetwork.transport
        } else {
            defaultNetwork.transport
        }
    } else {
        val selected = routable.firstOrNull { !it.isVpn && it.transport == NetworkTransport.ETHERNET }
            ?: routable.firstOrNull { !it.isVpn && it.transport == NetworkTransport.WIFI }
            ?: routable.firstOrNull { !it.isVpn }
            ?: routable.first()
        selected.transport
    }

    return NetworkConnectionStatus(transport = transport, isAvailable = true)
}

@Composable
fun rememberNetworkAvailableState(): State<Boolean> {
    val connectionStatus = rememberNetworkConnectionStatus()
    return remember { derivedStateOf { connectionStatus.value.isAvailable } }
}

@Composable
fun rememberNetworkConnectionStatus(): State<NetworkConnectionStatus> {
    val wakeState = rememberAppWakeState()
    return rememberNetworkConnectionStatus(wakeState.isAwake, wakeState.generation)
}

@Composable
internal fun rememberNetworkConnectionStatus(
    @Suppress("UNUSED_PARAMETER") awake: Boolean,
    wakeGeneration: Int,
    onPhysicalNetworkRestored: () -> Unit = {},
): State<NetworkConnectionStatus> {
    val context = LocalContext.current.applicationContext
    val connectivityManager = remember(context) {
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    }
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    val state = remember(connectivityManager) {
        mutableStateOf(connectivityManager.getDefaultConnectionStatus())
    }
    val notifyPhysicalRestored by rememberUpdatedState(onPhysicalNetworkRestored)
    var lastWakeGeneration by remember(connectivityManager) { mutableIntStateOf(wakeGeneration) }
    var physicallyDisconnectedDuringGrace by remember(connectivityManager) { mutableStateOf(false) }

    // Key only on wakeGeneration (not awake) so ON_PAUSE / settings overlays never churn
    // the NetworkCallback, while actual sleep->wake transitions still rebuild the OS snapshot
    // and re-register in case deep sleep dropped callbacks.
    DisposableEffect(connectivityManager, mainHandler, wakeGeneration) {
        val isWakeTransition = wakeGeneration != lastWakeGeneration
        lastWakeGeneration = wakeGeneration
        var disposed = false
        val knownCapabilities = linkedMapOf<Network, NetworkCapabilities>()
        val knownLinks = linkedMapOf<Network, NetworkLinkSnapshot>()
        var pendingDisconnect: Runnable? = null

        fun cancelPendingDisconnect() {
            pendingDisconnect?.let(mainHandler::removeCallbacks)
            pendingDisconnect = null
        }

        fun publishState(allowGrace: Boolean, useWakeGrace: Boolean = false) {
            if (disposed) return
            val defaultNetwork = connectivityManager.activeNetwork
            val resolved = resolveNetworkConnectionStatus(
                knownCapabilities.map { (network, capabilities) ->
                    capabilities.toNetworkSnapshot(isDefault = network == defaultNetwork)
                },
            )
            if (resolved.isAvailable) {
                cancelPendingDisconnect()
                val restoredDuringGrace = physicallyDisconnectedDuringGrace && state.value.isAvailable
                physicallyDisconnectedDuringGrace = false
                state.value = resolved
                if (restoredDuringGrace) {
                    notifyPhysicalRestored()
                }
                return
            }

            val graceMs = if (allowGrace) {
                disconnectGraceMillis(
                    wasConnected = state.value.isAvailable,
                    isWakeTransition = useWakeGrace,
                )
            } else {
                0L
            }
            if (graceMs <= 0L) {
                cancelPendingDisconnect()
                physicallyDisconnectedDuringGrace = false
                state.value = NetworkConnectionStatus.Disconnected
            } else {
                physicallyDisconnectedDuringGrace = true
                if (pendingDisconnect == null) {
                    val task = Runnable {
                        pendingDisconnect = null
                        publishState(allowGrace = false)
                    }
                    pendingDisconnect = task
                    mainHandler.postDelayed(task, graceMs)
                }
            }
        }

        fun updateNetwork(network: Network, capabilities: NetworkCapabilities) {
            mainHandler.post {
                if (disposed) return@post
                knownCapabilities[network] = capabilities
                publishState(allowGrace = true)
            }
        }

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                mainHandler.post {
                    if (disposed) return@post
                    connectivityManager.getNetworkCapabilities(network)?.let { capabilities ->
                        knownCapabilities[network] = capabilities
                    }
                    if (network !in knownLinks) {
                        connectivityManager.getLinkProperties(network)?.let { props ->
                            knownLinks[network] = props.toLinkSnapshot()
                        }
                    }
                    publishState(allowGrace = true)
                }
            }

            override fun onLost(network: Network) {
                mainHandler.post {
                    if (disposed) return@post
                    knownCapabilities.remove(network)
                    knownLinks.remove(network)
                    publishState(allowGrace = true)
                }
            }

            override fun onUnavailable() {
                mainHandler.post { publishState(allowGrace = true) }
            }

            override fun onCapabilitiesChanged(
                network: Network,
                networkCapabilities: NetworkCapabilities,
            ) {
                updateNetwork(network, networkCapabilities)
            }

            override fun onLinkPropertiesChanged(
                network: Network,
                linkProperties: LinkProperties,
            ) {
                mainHandler.post {
                    if (disposed) return@post
                    val next = linkProperties.toLinkSnapshot()
                    val previous = knownLinks.put(network, next)
                    if (didLinkGainRoutingOrDns(previous, next) && state.value.isAvailable) {
                        notifyPhysicalRestored()
                    }
                }
            }
        }

        // Rebuild from both activeNetwork and allNetworks (allNetworks is deprecated on API 31+).
        connectivityManager.activeNetwork?.let { network ->
            connectivityManager.getNetworkCapabilities(network)?.let { capabilities ->
                knownCapabilities[network] = capabilities
            }
            connectivityManager.getLinkProperties(network)?.let { props ->
                knownLinks[network] = props.toLinkSnapshot()
            }
        }
        @Suppress("DEPRECATION")
        connectivityManager.allNetworks.forEach { network ->
            connectivityManager.getNetworkCapabilities(network)?.let { capabilities ->
                knownCapabilities[network] = capabilities
            }
            connectivityManager.getLinkProperties(network)?.let { props ->
                knownLinks[network] = props.toLinkSnapshot()
            }
        }
        publishState(allowGrace = isWakeTransition, useWakeGrace = isWakeTransition)

        val request = NetworkRequest.Builder()
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()
        runCatching { connectivityManager.registerNetworkCallback(request, callback) }

        onDispose {
            disposed = true
            cancelPendingDisconnect()
            runCatching { connectivityManager.unregisterNetworkCallback(callback) }
        }
    }

    return state
}

private fun ConnectivityManager.getDefaultConnectionStatus(): NetworkConnectionStatus {
    val defaultNetwork = activeNetwork ?: return NetworkConnectionStatus.Disconnected
    val capabilities = getNetworkCapabilities(defaultNetwork)
        ?: return NetworkConnectionStatus.Disconnected

    return resolveNetworkConnectionStatus(
        listOf(capabilities.toNetworkSnapshot(isDefault = true)),
    )
}

private fun NetworkCapabilities.toNetworkSnapshot(isDefault: Boolean): NetworkCapabilitySnapshot {
    val hasWifi = hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    val hasEthernet = hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    val hasCellular = hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
    val hasVpn = hasTransport(NetworkCapabilities.TRANSPORT_VPN)
    val hasInternet = hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    val isSuspended = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
        hasInternet &&
        !hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_SUSPENDED)
    return networkSnapshot(
        hasWifi = hasWifi,
        hasEthernet = hasEthernet,
        isDefault = isDefault,
        hasCellular = hasCellular,
        hasVpn = hasVpn,
        hasInternet = hasInternet,
        isSuspended = isSuspended,
    )
}

private fun LinkProperties.toLinkSnapshot(): NetworkLinkSnapshot = NetworkLinkSnapshot(
    hasIpv4 = linkAddresses.any { it.address is Inet4Address },
    dnsServers = dnsServers.mapNotNull { it.hostAddress }.toSet(),
    domains = domains?.trim()?.takeIf { it.isNotEmpty() },
)
