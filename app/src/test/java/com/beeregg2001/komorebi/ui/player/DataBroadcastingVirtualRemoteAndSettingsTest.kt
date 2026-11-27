package com.beeregg2001.komorebi.ui.player

import android.view.KeyEvent as NativeKeyEvent
import androidx.compose.ui.input.key.KeyEvent
import com.beeregg2001.komorebi.data.DataBroadcastingPrefectures
import com.beeregg2001.komorebi.data.DataBroadcastingSettings
import com.beeregg2001.komorebi.data.DataBroadcastingVirtualRemoteMode
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DataBroadcastingVirtualRemoteAndSettingsTest {

    @Test
    fun normalizeZipcodeAcceptsHyphenFullWidthAndBlank() {
        assertEquals("", DataBroadcastingSettings.normalizeZipcode(""))
        assertEquals("", DataBroadcastingSettings.normalizeZipcode("   "))
        assertEquals("1000001", DataBroadcastingSettings.normalizeZipcode("100-0001"))
        assertEquals("1000001", DataBroadcastingSettings.normalizeZipcode("1000001"))
        assertEquals("1000001", DataBroadcastingSettings.normalizeZipcode("１００－０００１"))
        assertNull(DataBroadcastingSettings.normalizeZipcode("100-001"))
        assertNull(DataBroadcastingSettings.normalizeZipcode("100-000a"))
    }

    @Test
    fun buildReceiverInfoJsonMapsPrefectureAndRegionCode() {
        val tokyo = DataBroadcastingPrefectures.findByCode(14)!!
        val settings = DataBroadcastingSettings(
            allowExternalNetwork = true,
            zipcode = "1000001",
            prefectureCode = tokyo.code,
        )
        val json = JSONObject(buildReceiverInfoJson(settings))
        assertEquals("1000001", json.getString("zipcode"))
        assertEquals(14, json.getInt("prefecture"))
        assertEquals(0b101010101100, json.getInt("regioncode"))

        val emptyJson = JSONObject(buildReceiverInfoJson(DataBroadcastingSettings()))
        assertTrue(emptyJson.isNull("zipcode"))
        assertTrue(emptyJson.isNull("prefecture"))
        assertTrue(emptyJson.isNull("regioncode"))
    }

    @Test
    fun autoModeOpensVirtualBarOnlyWhenHardwareKeysAreMissing() {
        val missingState = DataBroadcastingInputState(hasMissingHardwareKeys = { true })
        missingState.onDataBroadcastingActivated(DataBroadcastingVirtualRemoteMode.AUTO)
        assertTrue(missingState.isVirtualRemoteVisible)
        assertFalse(missingState.isVirtualRemoteFocused)
        assertEquals(DataBroadcastingVirtualKey.Blue, missingState.selectedVirtualKey)

        val fullRemoteState = DataBroadcastingInputState(hasMissingHardwareKeys = { false })
        fullRemoteState.onDataBroadcastingActivated(DataBroadcastingVirtualRemoteMode.AUTO)
        assertFalse(fullRemoteState.isVirtualRemoteVisible)

        fullRemoteState.onDataBroadcastingActivated(DataBroadcastingVirtualRemoteMode.ALWAYS)
        assertTrue(fullRemoteState.isVirtualRemoteVisible)
        assertFalse(fullRemoteState.isVirtualRemoteFocused)

        val manualState = DataBroadcastingInputState(hasMissingHardwareKeys = { true })
        manualState.onDataBroadcastingActivated(DataBroadcastingVirtualRemoteMode.MANUAL)
        assertFalse(manualState.isVirtualRemoteVisible)
    }

    @Test
    fun virtualBarPassivePassThroughAndMenuFocusFlow() = runTest {
        val state = DataBroadcastingInputState(hasMissingHardwareKeys = { true })
        state.onDataBroadcastingActivated(DataBroadcastingVirtualRemoteMode.ALWAYS)

        val colorEvents = mutableListOf<DataBroadcastingColorKey>()
        val remoteEvents = mutableListOf<String>()
        var backCalls = 0
        var blankCalls = 0

        fun send(action: Int, keyCode: Int): Boolean = state.handleKeyEvent(
            keyEvent = KeyEvent(NativeKeyEvent(action, keyCode)),
            scope = this,
            onBack = { backCalls += 1 },
            onBlank = { blankCalls += 1 },
            onColorKey = { colorEvents += it },
            onRemoteKey = { remoteEvents += it },
        )

        // Passive docked state: D-pad + OK go straight to the broadcast page
        assertTrue(state.isVirtualRemoteVisible)
        assertFalse(state.isVirtualRemoteFocused)
        assertTrue(send(NativeKeyEvent.ACTION_DOWN, NativeKeyEvent.KEYCODE_DPAD_LEFT))
        assertTrue(send(NativeKeyEvent.ACTION_DOWN, NativeKeyEvent.KEYCODE_DPAD_RIGHT))
        assertTrue(send(NativeKeyEvent.ACTION_DOWN, NativeKeyEvent.KEYCODE_DPAD_DOWN))
        assertTrue(send(NativeKeyEvent.ACTION_DOWN, NativeKeyEvent.KEYCODE_DPAD_CENTER))
        assertTrue(send(NativeKeyEvent.ACTION_UP, NativeKeyEvent.KEYCODE_DPAD_CENTER))
        assertEquals(listOf("left", "right", "down", "enter"), remoteEvents)

        // Menu key focuses the bar
        assertTrue(send(NativeKeyEvent.ACTION_DOWN, NativeKeyEvent.KEYCODE_MENU))
        assertTrue(send(NativeKeyEvent.ACTION_UP, NativeKeyEvent.KEYCODE_MENU))
        assertTrue(state.isVirtualRemoteFocused)

        // Move right from Blue -> Red and press Enter: fires Red and returns focus to page
        assertTrue(send(NativeKeyEvent.ACTION_DOWN, NativeKeyEvent.KEYCODE_DPAD_RIGHT))
        assertEquals(DataBroadcastingVirtualKey.Red, state.selectedVirtualKey)
        assertTrue(send(NativeKeyEvent.ACTION_DOWN, NativeKeyEvent.KEYCODE_DPAD_CENTER))
        assertEquals(listOf(DataBroadcastingColorKey.Red), colorEvents)
        assertFalse(state.isVirtualRemoteFocused)
        // Stays passively visible in ALWAYS mode
        assertTrue(state.isVirtualRemoteVisible)

        // Re-focus bar and dispatch digit key: stays focused for consecutive digits
        state.openVirtualRemote(DataBroadcastingVirtualKey.Digit7)
        assertTrue(state.isVirtualRemoteFocused)
        state.dispatchVirtualKey(
            key = DataBroadcastingVirtualKey.Digit7,
            onBack = { backCalls += 1 },
            onBlank = { blankCalls += 1 },
            onColorKey = { colorEvents += it },
            onRemoteKey = { remoteEvents += it },
        )
        assertEquals(listOf("left", "right", "down", "enter", "7"), remoteEvents)
        assertTrue(state.isVirtualRemoteFocused)

        // DPAD_UP returns focus to page without firing broadcast back
        assertTrue(send(NativeKeyEvent.ACTION_DOWN, NativeKeyEvent.KEYCODE_DPAD_UP))
        assertFalse(state.isVirtualRemoteFocused)
        assertTrue(state.isVirtualRemoteVisible)
        assertEquals(0, backCalls)

        // Data virtual key blanks broadcast and hides bar
        state.openVirtualRemote(DataBroadcastingVirtualKey.Data)
        state.dispatchVirtualKey(
            key = DataBroadcastingVirtualKey.Data,
            onBack = { backCalls += 1 },
            onBlank = { blankCalls += 1 },
            onColorKey = { colorEvents += it },
            onRemoteKey = { remoteEvents += it },
        )
        assertEquals(1, blankCalls)
        assertFalse(state.isVirtualRemoteVisible)
        assertFalse(state.isVirtualRemoteFocused)
    }

    @Test
    fun hardwareColorKeySuppressesFutureAutoPopup() = runTest {
        val state = DataBroadcastingInputState(hasMissingHardwareKeys = { true })
        state.onDataBroadcastingActivated(DataBroadcastingVirtualRemoteMode.AUTO)
        assertTrue(state.isVirtualRemoteVisible)

        val colors = mutableListOf<DataBroadcastingColorKey>()
        state.handleKeyEvent(
            keyEvent = KeyEvent(NativeKeyEvent(NativeKeyEvent.ACTION_UP, NativeKeyEvent.KEYCODE_PROG_GREEN)),
            scope = this,
            onBack = {},
            onBlank = {},
            onColorKey = { colors += it },
            onRemoteKey = {},
        )
        assertEquals(listOf(DataBroadcastingColorKey.Green), colors)
        assertFalse(state.isVirtualRemoteVisible)

        // Next activation in AUTO mode remembers hardware key was observed
        state.onDataBroadcastingActivated(DataBroadcastingVirtualRemoteMode.AUTO)
        assertFalse(state.isVirtualRemoteVisible)
    }

    @Test
    fun buildB24StreamUrlFormatsNormalAndStandaloneEndpoints() {
        assertEquals(
            "https://my.local.konomi.tv:7000/api/streams/live/gr011/1080p-60fps/psi-archived-data",
            buildB24StreamUrl(
                konomiBaseUrl = "https://my.local.konomi.tv:7000/",
                displayChannelId = "gr011",
                apiQuality = "1080p-60fps",
            ),
        )
        assertEquals(
            "https://my.local.konomi.tv:7000/api/streams/live/bs211/data-broadcasting",
            buildB24StreamUrl(
                konomiBaseUrl = "https://my.local.konomi.tv:7000",
                displayChannelId = "bs211",
                apiQuality = "1080p",
                isStandaloneDataChannel = true,
            ),
        )
    }
}
