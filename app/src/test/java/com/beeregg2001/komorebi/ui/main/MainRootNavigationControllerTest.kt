package com.beeregg2001.komorebi.ui.main

import android.app.Application
import com.beeregg2001.komorebi.data.model.Channel
import com.beeregg2001.komorebi.data.model.EpgProgram
import com.beeregg2001.komorebi.data.model.RecordedProgram
import com.beeregg2001.komorebi.data.model.RecordedVideo
import com.beeregg2001.komorebi.data.model.ReserveChannel
import com.beeregg2001.komorebi.data.model.ReserveItem
import com.beeregg2001.komorebi.data.model.ReserveProgramDetail
import com.beeregg2001.komorebi.ui.player.PlaybackTarget
import com.beeregg2001.komorebi.ui.setting.SettingsCategory
import com.beeregg2001.komorebi.ui.setting.SettingsDestination
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class MainRootNavigationControllerTest {

    @Test
    fun resolveMainRootBackAction_enforcesExactPriorityOrderAcrossAll19Branches() {
        val state = MainRootState()
        val epg = epgProgram()
        val reserve = reserveItem()
        val recording = recording()
        val ch = channel()

        state.isAiConciergeOpen = true
        state.selectedConditionReserveItem = reserve
        state.editingNewProgram = epg
        state.editingReserveItem = reserve
        state.reserveToDelete = reserve
        state.selectedProgramForAutoReserve = recording
        state.showDeleteConfirmDialog = true
        state.playbackState.enterLive(ch)
        state.openSettings()
        state.epgSelectedProgram = epg
        state.selectedReserve = reserve
        state.isEpgJumpMenuOpen = true
        state.isSmbLibraryOpen = true
        state.isOnAirOpen = true
        state.isRecordListOpen = true
        state.isSeriesListOpen = true
        state.showConnectionErrorDialog = true
        state.isDataReady = true
        state.isUiReady = true

        // 1
        assertEquals(MainRootBackAction.CLOSE_AI_CONCIERGE, resolveMainRootBackAction(state))
        state.isAiConciergeOpen = false
        // 2
        assertEquals(MainRootBackAction.DISMISS_SELECTED_CONDITION_RESERVE, resolveMainRootBackAction(state))
        state.selectedConditionReserveItem = null
        // 3
        assertEquals(MainRootBackAction.DISMISS_EDITING_NEW_PROGRAM, resolveMainRootBackAction(state))
        state.editingNewProgram = null
        // 4
        assertEquals(MainRootBackAction.DISMISS_EDITING_RESERVE_ITEM, resolveMainRootBackAction(state))
        state.editingReserveItem = null
        // 5
        assertEquals(MainRootBackAction.DISMISS_RESERVE_TO_DELETE, resolveMainRootBackAction(state))
        state.reserveToDelete = null
        // 6
        assertEquals(MainRootBackAction.DISMISS_AUTO_RESERVE_PROGRAM, resolveMainRootBackAction(state))
        state.selectedProgramForAutoReserve = null
        // 7
        assertEquals(MainRootBackAction.DISMISS_DELETE_CONFIRM_DIALOG, resolveMainRootBackAction(state))
        state.showDeleteConfirmDialog = false
        // 8
        assertEquals(MainRootBackAction.HANDLE_PLAYBACK_BACK, resolveMainRootBackAction(state))
        state.playbackState.resetPlayback()
        // 9
        assertEquals(MainRootBackAction.CLOSE_SETTINGS, resolveMainRootBackAction(state))
        state.closeSettings()
        // 10
        assertEquals(MainRootBackAction.DISMISS_EPG_SELECTED_PROGRAM, resolveMainRootBackAction(state))
        state.epgSelectedProgram = null
        // 11
        assertEquals(MainRootBackAction.DISMISS_SELECTED_RESERVE, resolveMainRootBackAction(state))
        state.selectedReserve = null
        // 12
        assertEquals(MainRootBackAction.DISMISS_EPG_JUMP_MENU, resolveMainRootBackAction(state))
        state.isEpgJumpMenuOpen = false
        // 13
        assertEquals(MainRootBackAction.CLOSE_SMB_LIBRARY, resolveMainRootBackAction(state))
        state.isSmbLibraryOpen = false
        // 14
        assertEquals(MainRootBackAction.CLOSE_ON_AIR, resolveMainRootBackAction(state))
        state.isOnAirOpen = false
        // 15
        assertEquals(MainRootBackAction.CLOSE_RECORD_LIST, resolveMainRootBackAction(state))
        state.isRecordListOpen = false
        // 16
        assertEquals(MainRootBackAction.CLOSE_SERIES_LIST, resolveMainRootBackAction(state))
        state.isSeriesListOpen = false
        // 17
        assertEquals(MainRootBackAction.IGNORE, resolveMainRootBackAction(state))
        state.showConnectionErrorDialog = false
        // 18
        state.isDataReady = false
        state.isUiReady = true
        assertEquals(MainRootBackAction.IGNORE, resolveMainRootBackAction(state))
        state.isDataReady = true
        state.isUiReady = false
        assertEquals(MainRootBackAction.IGNORE, resolveMainRootBackAction(state))
        // 19
        state.isUiReady = true
        assertEquals(MainRootBackAction.TRIGGER_HOME_BACK, resolveMainRootBackAction(state))
    }

    @Test
    fun handleBackPress_debouncesWithin500msAndAllowsAt500ms() {
        val state = MainRootState().apply {
            isEpgJumpMenuOpen = true
            isSmbLibraryOpen = true
        }
        var now = 1_000L
        val controller = MainRootNavigationController(state = state, nowMillis = { now })

        val first = controller.handleBackPress(
            onCloseAiConcierge = {},
            onCloseSettings = {},
            onResetRecordSearch = {},
        )
        assertEquals(MainRootBackAction.DISMISS_EPG_JUMP_MENU, first)
        assertFalse(state.isEpgJumpMenuOpen)
        assertTrue(state.isSmbLibraryOpen)

        now = 1_499L
        val debounced = controller.handleBackPress(
            onCloseAiConcierge = {},
            onCloseSettings = {},
            onResetRecordSearch = {},
        )
        assertEquals(MainRootBackAction.IGNORE, debounced)
        assertTrue(state.isSmbLibraryOpen)

        now = 1_500L
        val second = controller.handleBackPress(
            onCloseAiConcierge = {},
            onCloseSettings = {},
            onResetRecordSearch = {},
        )
        assertEquals(MainRootBackAction.CLOSE_SMB_LIBRARY, second)
        assertFalse(state.isSmbLibraryOpen)
    }

    @Test
    fun handleBackPress_closeRecordListRestoresSeriesListWhenOpenedFromSeriesAndClearsSearch() {
        val state = MainRootState().apply {
            isRecordListOpen = true
            openedSeriesTitle = "Series"
        }
        var now = 1_000L
        var resetSearchCalls = 0
        val controller = MainRootNavigationController(state = state, nowMillis = { now })

        val action = controller.handleBackPress(
            onCloseAiConcierge = {},
            onCloseSettings = {},
            onResetRecordSearch = { resetSearchCalls++ },
        )

        assertEquals(MainRootBackAction.CLOSE_RECORD_LIST, action)
        assertFalse(state.isRecordListOpen)
        assertTrue(state.isSeriesListOpen)
        assertNull(state.openedSeriesTitle)
        assertEquals(1, resetSearchCalls)

        now = 1_500L
        val seriesAction = controller.handleBackPress(
            onCloseAiConcierge = {},
            onCloseSettings = {},
            onResetRecordSearch = { resetSearchCalls++ },
        )
        assertEquals(MainRootBackAction.CLOSE_SERIES_LIST, seriesAction)
        assertFalse(state.isSeriesListOpen)
        assertEquals(2, resetSearchCalls)
    }

    @Test
    fun handleBackPress_closeOnAirIncrementsOverlayReturnFocusTick() {
        val state = MainRootState().apply {
            isOnAirOpen = true
        }
        val initialTick = state.overlayReturnFocusTick
        val controller = MainRootNavigationController(state = state, nowMillis = { 1_000L })

        val action = controller.handleBackPress(
            onCloseAiConcierge = {},
            onCloseSettings = {},
            onResetRecordSearch = {},
        )

        assertEquals(MainRootBackAction.CLOSE_ON_AIR, action)
        assertFalse(state.isOnAirOpen)
        assertEquals(initialTick + 1, state.overlayReturnFocusTick)
    }

    @Test
    fun closeSettings_refreshesAndClearsErrorAndOfflineFlagsOnlyWhenNeeded() {
        val state = MainRootState().apply {
            isDataReady = true
            isOfflineMode = false
            showConnectionErrorDialog = false
            openSettings(SettingsDestination.Category(SettingsCategory.GENERAL))
        }
        val controller = MainRootNavigationController(state = state, initialSettingsDataRevision = 3)
        var refreshCalls = 0

        // Unchanged revision on healthy ready state: no refresh
        controller.onSettingsOpened(currentDataRevision = 3)
        controller.closeSettings(
            currentDataRevision = 3,
            isChannelError = false,
            onRefreshAllData = { refreshCalls++ },
        )
        assertFalse(state.isSettingsOpen)
        assertEquals(SettingsDestination.Default, state.settingsDestination)
        assertEquals(0, refreshCalls)

        // Changed revision: refreshes and clears error/offline flags
        state.openSettings()
        state.showConnectionErrorDialog = true
        state.isOfflineMode = true
        controller.onSettingsOpened(currentDataRevision = 3)
        controller.closeSettings(
            currentDataRevision = 4,
            isChannelError = false,
            onRefreshAllData = { refreshCalls++ },
        )
        assertFalse(state.isSettingsOpen)
        assertFalse(state.showConnectionErrorDialog)
        assertFalse(state.isOfflineMode)
        assertEquals(1, refreshCalls)

        // Offline mode even with unchanged revision: refreshes and clears offline flag
        state.openSettings()
        state.isOfflineMode = true
        controller.onSettingsOpened(currentDataRevision = 4)
        controller.closeSettings(
            currentDataRevision = 4,
            isChannelError = false,
            onRefreshAllData = { refreshCalls++ },
        )
        assertFalse(state.isOfflineMode)
        assertEquals(2, refreshCalls)

        // Channel error even with unchanged revision: refreshes and clears connection error dialog
        state.openSettings()
        state.showConnectionErrorDialog = true
        controller.onSettingsOpened(currentDataRevision = 4)
        controller.closeSettings(
            currentDataRevision = 4,
            isChannelError = true,
            onRefreshAllData = { refreshCalls++ },
        )
        assertFalse(state.showConnectionErrorDialog)
        assertEquals(3, refreshCalls)
    }

    @Test
    fun returnToLauncherHome_preservesCurrentTabFromActiveModuleAndResetsToHomeWhenIdle() {
        val state = MainRootState()
        val tabs = state.getVisibleTabs(hideAppsTab = false)
        val epgIndex = tabs.indexOf("番組表")
        val videoIndex = tabs.indexOf("ビデオ")
        val homeIndex = tabs.indexOf("ホーム")
        val controller = MainRootNavigationController(state = state)

        // Active module (Settings + overlay) on EPG tab -> preserves EPG tab, closes settings, loads EPG, clears overlays
        state.currentTabIndex = epgIndex
        state.openSettings()
        state.isEpgJumpMenuOpen = true
        var closeSettingsCalls = 0
        var ensureEpgCalls = 0

        controller.returnToLauncherHome(
            hideAppsTab = false,
            onCloseSettings = {
                closeSettingsCalls++
                state.closeSettings()
            },
            onEnsureEpgLoaded = { ensureEpgCalls++ },
        )

        assertEquals(epgIndex, state.currentTabIndex)
        assertEquals(1, closeSettingsCalls)
        assertEquals(1, ensureEpgCalls)
        assertFalse(state.isSettingsOpen)
        assertFalse(state.isEpgJumpMenuOpen)

        // Active playback on Video tab -> preserves Video tab and does NOT trigger EPG load
        state.currentTabIndex = videoIndex
        state.playbackState.enterLive(channel())
        controller.returnToLauncherHome(
            hideAppsTab = false,
            onCloseSettings = { closeSettingsCalls++ },
            onEnsureEpgLoaded = { ensureEpgCalls++ },
        )

        assertEquals(videoIndex, state.currentTabIndex)
        assertEquals(PlaybackTarget.None, state.playbackState.playbackTarget)
        assertEquals(1, closeSettingsCalls)
        assertEquals(1, ensureEpgCalls)

        // Idle on EPG tab -> resets back to Home tab without triggering EPG load
        state.currentTabIndex = epgIndex
        controller.returnToLauncherHome(
            hideAppsTab = false,
            onCloseSettings = { closeSettingsCalls++ },
            onEnsureEpgLoaded = { ensureEpgCalls++ },
        )

        assertEquals(homeIndex, state.currentTabIndex)
        assertEquals(1, ensureEpgCalls)
    }

    @Test
    fun closeAiConcierge_restoresEpgFocusOnEpgTabAndIncrementsTickOnOtherTabs() {
        val state = MainRootState()
        val tabs = state.getVisibleTabs(hideAppsTab = false)
        val epgIndex = tabs.indexOf("番組表")
        val homeIndex = tabs.indexOf("ホーム")
        val controller = MainRootNavigationController(state = state)

        // On EPG tab with restoreFocus = true -> calls onRestoreEpgFocus, does not bump tick
        state.currentTabIndex = epgIndex
        state.isAiConciergeOpen = true
        var resetAiCalls = 0
        var restoreEpgCalls = 0
        var clearSearchCalls = 0

        controller.closeAiConcierge(
            restoreFocus = true,
            hideAppsTab = false,
            onResetAiState = { resetAiCalls++ },
            onRestoreEpgFocus = { restoreEpgCalls++ },
            onClearEpgSearch = { clearSearchCalls++ },
        )

        assertFalse(state.isAiConciergeOpen)
        assertEquals(1, resetAiCalls)
        assertEquals(1, restoreEpgCalls)
        assertEquals(0, state.aiFocusReturnTick)
        assertEquals(1, clearSearchCalls)

        // On Home tab with restoreFocus = true -> increments aiFocusReturnTick
        state.currentTabIndex = homeIndex
        state.isAiConciergeOpen = true
        controller.closeAiConcierge(
            restoreFocus = true,
            hideAppsTab = false,
            onResetAiState = { resetAiCalls++ },
            onRestoreEpgFocus = { restoreEpgCalls++ },
            onClearEpgSearch = { clearSearchCalls++ },
        )

        assertFalse(state.isAiConciergeOpen)
        assertEquals(2, resetAiCalls)
        assertEquals(1, restoreEpgCalls)
        assertEquals(1, state.aiFocusReturnTick)
        assertEquals(2, clearSearchCalls)

        // With restoreFocus = false -> neither restores EPG nor increments tick
        state.currentTabIndex = homeIndex
        state.isAiConciergeOpen = true
        controller.closeAiConcierge(
            restoreFocus = false,
            hideAppsTab = false,
            onResetAiState = { resetAiCalls++ },
            onRestoreEpgFocus = { restoreEpgCalls++ },
            onClearEpgSearch = { clearSearchCalls++ },
        )

        assertEquals(3, resetAiCalls)
        assertEquals(1, restoreEpgCalls)
        assertEquals(1, state.aiFocusReturnTick)
        assertEquals(3, clearSearchCalls)
    }

    private fun epgProgram() = EpgProgram(
        id = "epg-1",
        channel_id = "gr-test",
        title = "Test EPG",
        description = "",
        detail = null,
        start_time = "2026-08-09T00:00:00+09:00",
        end_time = "2026-08-09T01:00:00+09:00",
        duration = 3600,
        is_free = true,
        genres = null,
        video_type = null,
        audio_type = null,
        audio_sampling_rate = null,
    )

    private fun reserveItem() = ReserveItem(
        id = 1,
        channel = ReserveChannel(id = "gr-test", name = "Test Channel"),
        program = ReserveProgramDetail(id = "epg-1", title = "Test Reserve"),
    )

    private fun channel(id: String = "gr-test") = Channel(
        id = id,
        displayChannelId = "gr011",
        name = "Test Channel",
        channelNumber = "011",
        networkId = 1,
        serviceId = 1,
        type = "GR",
        isWatchable = true,
        isDisplay = true,
        programPresent = null,
        programFollowing = null,
        remocon_Id = 1,
    )

    private fun recording(id: Int = 42) = RecordedProgram(
        id = id,
        title = "Test Recording",
        description = "",
        startTime = "2026-08-09T00:00:00+09:00",
        endTime = "2026-08-09T01:00:00+09:00",
        duration = 3600.0,
        isPartiallyRecorded = false,
        recordedVideo = RecordedVideo(
            id = id,
            status = "Recorded",
            filePath = "/recordings/test.ts",
            duration = 3600.0,
            containerFormat = "MPEG-TS",
            videoCodec = "H.264",
            audioCodec = "AAC-LC",
        ),
    )
}
