package com.beeregg2001.komorebi.ui.main

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Looper
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
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNetwork
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class NetworkLifecycleTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun pauseKeepsConnectedNetworkAndWakeGraceAbsorbsBriefLinkBringUp() {
        val manager = RuntimeEnvironment.getApplication().getSystemService(
            Context.CONNECTIVITY_SERVICE,
        ) as ConnectivityManager
        val shadow = shadowOf(manager)
        shadow.clearAllNetworks()
        val network = ShadowNetwork.newInstance(123)
        val ethernetCaps = NetworkCapabilities().apply {
            shadowOf(this).addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
        }
        shadow.addNetwork(network, null)
        shadow.setNetworkCapabilities(network, ethernetCaps)
        val awake = mutableStateOf(true)
        val generation = mutableStateOf(0)
        var physicalRestores = 0
        lateinit var status: State<NetworkConnectionStatus>
        compose.setContent {
            status = rememberNetworkConnectionStatus(
                awake = awake.value,
                wakeGeneration = generation.value,
                onPhysicalNetworkRestored = { physicalRestores++ },
            )
        }
        val connected = NetworkConnectionStatus(NetworkTransport.ETHERNET, true)
        compose.runOnIdle { assertEquals(connected, status.value); awake.value = false }
        compose.runOnIdle { assertEquals(connected, status.value); awake.value = true; generation.value++ }
        compose.runOnIdle { assertEquals(connected, status.value); awake.value = false }

        // Wake while NIC is still bringing up: stays connected during the 5s wake grace window.
        compose.runOnIdle { shadow.clearAllNetworks(); awake.value = true; generation.value++ }
        compose.runOnIdle { assertEquals(connected, status.value) }
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(WAKE_DISCONNECT_GRACE_MS - 500L))
        compose.runOnIdle { assertEquals(connected, status.value) }

        // After wake grace expires with still no network, honestly reports Disconnected.
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1_000L))
        compose.runOnIdle { assertEquals(NetworkConnectionStatus.Disconnected, status.value); awake.value = false }

        // Waking while already Disconnected stays Disconnected immediately (no fake Connected).
        compose.runOnIdle { awake.value = true; generation.value++ }
        compose.runOnIdle { assertEquals(NetworkConnectionStatus.Disconnected, status.value); awake.value = false }

        // Network returns on next wake -> immediately Connected.
        compose.runOnIdle {
            shadow.addNetwork(network, null)
            shadow.setNetworkCapabilities(network, ethernetCaps)
            awake.value = true
            generation.value++
        }
        compose.runOnIdle { assertEquals(connected, status.value) }
        assertEquals(0, physicalRestores)
    }

    @Test
    fun networkReturningDuringWakeGraceStaysConnectedAndNotifiesRestored() {
        val manager = RuntimeEnvironment.getApplication().getSystemService(
            Context.CONNECTIVITY_SERVICE,
        ) as ConnectivityManager
        val shadow = shadowOf(manager)
        shadow.clearAllNetworks()
        val network = ShadowNetwork.newInstance(456)
        val wifiCaps = NetworkCapabilities().apply {
            shadowOf(this).addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
        }
        shadow.addNetwork(network, null)
        shadow.setNetworkCapabilities(network, wifiCaps)
        val awake = mutableStateOf(true)
        val generation = mutableStateOf(0)
        var physicalRestores = 0
        lateinit var status: State<NetworkConnectionStatus>
        compose.setContent {
            status = rememberNetworkConnectionStatus(
                awake = awake.value,
                wakeGeneration = generation.value,
                onPhysicalNetworkRestored = { physicalRestores++ },
            )
        }
        val connected = NetworkConnectionStatus(NetworkTransport.WIFI, true)
        compose.runOnIdle { assertEquals(connected, status.value); awake.value = false }

        // Wake with empty networks -> enters wake grace window without flashing Disconnected.
        compose.runOnIdle { shadow.clearAllNetworks(); awake.value = true; generation.value++ }
        compose.runOnIdle { assertEquals(connected, status.value) }

        // Wi-Fi finishes associating 1.5s later -> callback fires before grace expires.
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1_500L))
        compose.runOnIdle {
            shadow.addNetwork(network, null)
            shadow.setNetworkCapabilities(network, wifiCaps)
            shadow.networkCallbacks.forEach { it.onAvailable(network) }
        }
        shadowOf(Looper.getMainLooper()).idle()
        compose.runOnIdle {
            assertEquals(connected, status.value)
            assertEquals(1, physicalRestores)
        }

        // Advancing past the original grace deadline must NOT downgrade to Disconnected.
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(WAKE_DISCONNECT_GRACE_MS))
        compose.runOnIdle { assertEquals(connected, status.value) }
    }
}
