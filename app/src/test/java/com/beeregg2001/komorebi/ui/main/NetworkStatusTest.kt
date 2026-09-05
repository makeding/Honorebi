package com.beeregg2001.komorebi.ui.main

import org.junit.Assert.assertEquals
import org.junit.Test

class NetworkStatusTest {
    @Test
    fun networkButtonKeepsOneStableWidthAcrossStatuses() {
        assertEquals(132, NETWORK_STATUS_BUTTON_WIDTH_DP)
    }

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
}
