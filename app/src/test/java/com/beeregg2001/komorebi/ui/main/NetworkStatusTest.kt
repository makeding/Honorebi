package com.beeregg2001.komorebi.ui.main

import org.junit.Assert.assertEquals
import org.junit.Test

class NetworkStatusTest {
    @Test
    fun ethernetIsAvailableWhenItIsTheOnlyReportedNetwork() {
        assertEquals(
            NetworkConnectionStatus(NetworkTransport.ETHERNET, true),
            resolveNetworkConnectionStatus(
                listOf(networkSnapshot(hasEthernet = true)),
            ),
        )
    }

    @Test
    fun ethernetIsUsedWhenTheDefaultNetworkIsTemporarilyMissing() {
        assertEquals(
            NetworkConnectionStatus(NetworkTransport.ETHERNET, true),
            resolveNetworkConnectionStatus(
                listOf(
                    networkSnapshot(hasEthernet = true, isDefault = false),
                    networkSnapshot(hasWifi = true, isDefault = false),
                ),
            ),
        )
    }

    @Test
    fun defaultNetworkWinsWhenMultipleTransportsAreConnected() {
        assertEquals(
            NetworkConnectionStatus(NetworkTransport.WIFI, true),
            resolveNetworkConnectionStatus(
                listOf(
                    networkSnapshot(hasEthernet = true, isDefault = false),
                    networkSnapshot(hasWifi = true, isDefault = true),
                ),
            ),
        )
    }

    @Test
    fun capabilityChangeReclassifiesWifiAsEthernet() {
        val wifi = resolveNetworkConnectionStatus(
            listOf(networkSnapshot(hasWifi = true, isDefault = true)),
        )
        val ethernet = resolveNetworkConnectionStatus(
            listOf(networkSnapshot(hasEthernet = true, isDefault = true)),
        )

        assertEquals(NetworkTransport.WIFI, wifi.transport)
        assertEquals(NetworkTransport.ETHERNET, ethernet.transport)
    }

    @Test
    fun noReportedNetworkIsDisconnected() {
        assertEquals(
            NetworkConnectionStatus.Disconnected,
            resolveNetworkConnectionStatus(emptyList()),
        )
    }

    @Test
    fun nonRoutableAndSuspendedNetworksAreIgnored() {
        assertEquals(
            NetworkConnectionStatus.Disconnected,
            resolveNetworkConnectionStatus(
                listOf(
                    networkSnapshot(hasInternet = false),
                    networkSnapshot(hasWifi = true, isSuspended = true),
                ),
            ),
        )
    }

    @Test
    fun vpnDefaultPreservesUnderlyingPhysicalTransportIcon() {
        assertEquals(
            NetworkConnectionStatus(NetworkTransport.ETHERNET, true),
            resolveNetworkConnectionStatus(
                listOf(
                    networkSnapshot(hasVpn = true, isDefault = true),
                    networkSnapshot(hasEthernet = true, isDefault = false),
                ),
            ),
        )
        assertEquals(
            NetworkConnectionStatus(NetworkTransport.OTHER, true),
            resolveNetworkConnectionStatus(
                listOf(networkSnapshot(hasVpn = true, isDefault = true)),
            ),
        )
    }

    @Test
    fun disconnectGraceWindowMatchesWakeAndRuntimeTransitions() {
        assertEquals(0L, disconnectGraceMillis(wasConnected = false, isWakeTransition = false))
        assertEquals(0L, disconnectGraceMillis(wasConnected = false, isWakeTransition = true))
        assertEquals(NORMAL_DISCONNECT_GRACE_MS, disconnectGraceMillis(wasConnected = true, isWakeTransition = false))
        assertEquals(WAKE_DISCONNECT_GRACE_MS, disconnectGraceMillis(wasConnected = true, isWakeTransition = true))
    }
}
