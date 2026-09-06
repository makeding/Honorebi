package com.beeregg2001.komorebi.ui.home

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import com.beeregg2001.komorebi.ui.main.NetworkConnectionStatus
import com.beeregg2001.komorebi.ui.main.NetworkTransport
import com.beeregg2001.komorebi.ui.theme.KomorebiTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class NetworkStatusButtonTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun everyNetworkStateUsesTheSameIconOnlySlotWithoutMovingNeighbors() {
        val statuses = listOf(
            NetworkConnectionStatus(NetworkTransport.WIFI, true) to "Wi-Fi 設定",
            NetworkConnectionStatus(NetworkTransport.ETHERNET, true) to "有線ネットワーク設定",
            NetworkConnectionStatus(NetworkTransport.OTHER, true) to "ネットワーク設定",
            NetworkConnectionStatus.Disconnected to "オフライン。Wi-Fi 設定",
        )

        val currentStatus = mutableStateOf(statuses.first().first)
        val focusRequester = FocusRequester()
        val leftFocusRequester = FocusRequester()
        val rightFocusRequester = FocusRequester()
        compose.setContent {
            KomorebiTheme {
                Row {
                    Box(Modifier.size(48.dp).testTag("left-neighbor"))
                    NetworkStatusButton(
                        status = currentStatus.value,
                        focusRequester = focusRequester,
                        leftFocusRequester = leftFocusRequester,
                        rightFocusRequester = rightFocusRequester,
                        canTakeFocus = true,
                        onClick = {},
                    )
                    Box(Modifier.size(48.dp).testTag("right-neighbor"))
                }
            }
        }

        val buttonBounds = mutableListOf<DpRect>()
        val rightNeighborBounds = mutableListOf<DpRect>()
        statuses.forEach { (status, description) ->
            compose.runOnIdle { currentStatus.value = status }
            compose.waitForIdle()

            compose.onNodeWithTag(NETWORK_STATUS_BUTTON_TEST_TAG)
                .assertWidthIsEqualTo(48.dp)
                .assertHeightIsEqualTo(48.dp)
            compose.onNodeWithContentDescription(description).assertExists()
            buttonBounds += compose.onNodeWithTag(NETWORK_STATUS_BUTTON_TEST_TAG)
                .getUnclippedBoundsInRoot()
            rightNeighborBounds += compose.onNodeWithTag("right-neighbor")
                .getUnclippedBoundsInRoot()
        }

        assertEquals(1, buttonBounds.distinct().size)
        assertEquals(1, rightNeighborBounds.distinct().size)
        listOf("Wi-Fi", "有線", "ネットワーク", "オフライン").forEach { label ->
            compose.onNodeWithText(label).assertDoesNotExist()
        }
    }
}
