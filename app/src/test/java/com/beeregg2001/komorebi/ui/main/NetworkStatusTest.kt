package com.beeregg2001.komorebi.ui.main

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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

    @Test
    fun linkPropertiesGainDetectsLateIpv4DhcpAndLanDns() {
        val ipv6Only = NetworkLinkSnapshot(
            hasIpv4 = false,
            dnsServers = setOf("240b:250:a422:f504:9a03:9bff:fe64:c830"),
            domains = null,
        )
        val ipv6WithIpv4AndLanDns = NetworkLinkSnapshot(
            hasIpv4 = true,
            dnsServers = setOf("240b:250:a422:f504:9a03:9bff:fe64:c830", "172.24.0.254"),
            domains = "lan",
        )
        assertFalse(didLinkGainRoutingOrDns(null, ipv6Only))
        assertTrue(didLinkGainRoutingOrDns(ipv6Only, ipv6WithIpv4AndLanDns))
        assertTrue(
            didLinkGainRoutingOrDns(
                ipv6Only,
                ipv6Only.copy(dnsServers = ipv6Only.dnsServers + "172.24.0.254"),
            ),
        )
        assertTrue(didLinkGainRoutingOrDns(ipv6Only, ipv6Only.copy(domains = "lan")))
        assertFalse(didLinkGainRoutingOrDns(ipv6WithIpv4AndLanDns, ipv6WithIpv4AndLanDns))
    }
}
