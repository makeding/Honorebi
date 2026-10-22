package com.beeregg2001.komorebi.ui.main

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowNetwork
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class NetworkLifecycleTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun pauseKeepsConnectedNetworkAndWakeReadsActualLossAndRecovery() {
        val manager = RuntimeEnvironment.getApplication().getSystemService(
            Context.CONNECTIVITY_SERVICE,
        ) as ConnectivityManager
        val shadow = shadowOf(manager)
        shadow.clearAllNetworks()
        val network = ShadowNetwork.newInstance(123)
        shadow.addNetwork(network, null)
        shadow.setNetworkCapabilities(network, NetworkCapabilities().apply {
            shadowOf(this).addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
        })
        val awake = mutableStateOf(true)
        val generation = mutableStateOf(0)
        lateinit var status: State<NetworkConnectionStatus>
        compose.setContent {
            status = rememberNetworkConnectionStatus(awake.value, generation.value)
        }
        val connected = NetworkConnectionStatus(NetworkTransport.ETHERNET, true)
        compose.runOnIdle { assertEquals(connected, status.value); awake.value = false }
        compose.runOnIdle { assertEquals(connected, status.value); awake.value = true; generation.value++ }
        compose.runOnIdle { assertEquals(connected, status.value); awake.value = false }
        compose.runOnIdle { shadow.clearAllNetworks(); awake.value = true; generation.value++ }
        compose.runOnIdle { assertEquals(NetworkConnectionStatus.Disconnected, status.value); awake.value = false }
        compose.runOnIdle {
            shadow.addNetwork(network, null)
            shadow.setNetworkCapabilities(network, NetworkCapabilities().apply {
                shadowOf(this).addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
            })
            awake.value = true
            generation.value++
        }
        compose.runOnIdle { assertEquals(connected, status.value) }
    }
}
