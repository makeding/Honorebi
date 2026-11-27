package com.beeregg2001.komorebi.ui.main

import com.beeregg2001.komorebi.ui.player.*

import com.beeregg2001.komorebi.data.model.Channel
import com.beeregg2001.komorebi.data.model.EpgProgram
import com.beeregg2001.komorebi.data.model.ProgramSearchCondition
import com.beeregg2001.komorebi.data.model.RecordSettings
import com.beeregg2001.komorebi.data.model.RecordedProgram
import com.beeregg2001.komorebi.data.model.RecordedVideo
import com.beeregg2001.komorebi.data.model.ReservationCondition
import com.beeregg2001.komorebi.data.model.ReserveChannel
import com.beeregg2001.komorebi.data.model.ReserveItem
import com.beeregg2001.komorebi.data.model.ReserveProgramDetail
import com.beeregg2001.komorebi.ui.home.UpgradeTourAction
import com.beeregg2001.komorebi.ui.home.UpgradeTourCatalog
import com.beeregg2001.komorebi.ui.setting.SettingsCategory
import com.beeregg2001.komorebi.ui.setting.SettingsDestination
import com.beeregg2001.komorebi.ui.setting.SettingsFocusTarget
import com.beeregg2001.komorebi.ui.video.smb.SmbItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [28])
class MainRootStateTest {

    @Test
    fun remotePlaybackClosesSettingsAndRestoresFullscreenWithoutResettingSession() {
        val state = MainRootState()
        state.playbackState.enterRecorded(recording())
        state.playbackState.enterMiniPlayer()
        state.openSettings(SettingsDestination.DeviceCapabilities)
        state.isDataReady = true
        state.isUiReady = true
        val session = state.playbackState.playbackSession

        state.bringRemotePlaybackToFront()

        assertFalse(state.isSettingsOpen)
        assertEquals(SettingsDestination.Default, state.settingsDestination)
        assertFalse(state.playbackState.isMiniPlayerMode)
        assertEquals(session, state.playbackState.playbackSession)
        assertTrue(state.isDataReady)
        assertTrue(state.isUiReady)
        assertEquals(1, state.remotePlaybackFocusTick)
        assertTrue(state.playbackState.beginRecordedSwitch(recording(43), reason = PlaybackSwitchReason.RemoteOpen))
        assertFalse(state.playbackState.isMiniPlayerMode)
        assertEquals(session, state.playbackState.playbackSession)
    }

    @Test
    fun playbackTarget_startsNone_andEachEnterOperationReplacesThePreviousKind() {
        val state = MainRootState()
        val channel = channel()
        val recording = recording()
        val smbItem = smbItem()

        assertEquals(PlaybackTarget.None, state.playbackState.playbackTarget)
        assertFalse(state.playbackState.isPlaybackActive)

        state.playbackState.enterLive(channel)
        assertEquals(PlaybackTarget.Live(channel), state.playbackState.playbackTarget)
        assertTrue(state.playbackState.isPlaybackActive)
        assertEquals(channel, state.playbackState.livePlayback?.channel)
        assertNull(state.playbackState.recordedPlayback)
        assertNull(state.playbackState.smbPlayback)

        state.playbackState.enterRecorded(recording)
        assertEquals(PlaybackTarget.Recorded(recording), state.playbackState.playbackTarget)
        assertNull(state.playbackState.livePlayback)
        assertEquals(recording, state.playbackState.recordedPlayback?.program)
        assertNull(state.playbackState.smbPlayback)

        state.playbackState.enterSmb(smbItem)
        assertEquals(PlaybackTarget.Smb(smbItem), state.playbackState.playbackTarget)
        assertNull(state.playbackState.livePlayback)
        assertNull(state.playbackState.recordedPlayback)
        assertEquals(smbItem, state.playbackState.smbPlayback?.item)
    }

    @Test
    fun enterRecorded_fromMiniLive_replacesLiveAndRestoresVideoControls() {
        val state = MainRootState()
        val recording = recording()
        state.playbackState.enterLive(channel())
        state.playbackState.isMiniPlayerMode = true
        state.playbackState.showPlayerControls = false

        state.playbackState.enterRecorded(recording, initialPositionMs = 12_345L)

        assertEquals(PlaybackTarget.Recorded(recording), state.playbackState.playbackTarget)
        assertNull(state.playbackState.livePlayback)
        assertFalse(state.playbackState.isMiniPlayerMode)
        assertTrue(state.playbackState.showPlayerControls)
        assertEquals(12_345L, state.playbackState.initialPlaybackPositionMs)
        assertEquals(recording.id.toString(), state.playbackState.lastSelectedProgramId)
        assertNull(state.playbackState.lastSelectedChannelId)
    }

    @Test
    fun enterSmb_fromMiniLive_replacesLiveAndRestoresVideoControls() {
        val state = MainRootState()
        val smbItem = smbItem()
        state.playbackState.enterLive(channel())
        state.playbackState.isMiniPlayerMode = true
        state.playbackState.showPlayerControls = false

        state.playbackState.enterSmb(smbItem, initialPositionMs = 54_321L)

        assertEquals(PlaybackTarget.Smb(smbItem), state.playbackState.playbackTarget)
        assertNull(state.playbackState.livePlayback)
        assertFalse(state.playbackState.isMiniPlayerMode)
        assertTrue(state.playbackState.showPlayerControls)
        assertEquals(54_321L, state.playbackState.initialPlaybackPositionMs)
        assertEquals(smbItem.path, state.playbackState.lastPlayedSmbPath)
    }

    @Test
    fun enterLive_fromRecorded_replacesRecordedAndClearsRecordingSelectionHistory() {
        val state = MainRootState()
        val channel = channel()
        state.playbackState.enterRecorded(recording(), initialPositionMs = 7_000L)
        state.playbackState.isMiniPlayerMode = true

        state.playbackState.enterLive(channel)

        assertEquals(PlaybackTarget.Live(channel), state.playbackState.playbackTarget)
        assertEquals(channel, state.playbackState.livePlayback?.channel)
        assertNull(state.playbackState.recordedPlayback)
        assertFalse(state.playbackState.isMiniPlayerMode)
        assertEquals(channel.id, state.playbackState.lastSelectedChannelId)
        assertNull(state.playbackState.lastSelectedProgramId)
    }

    @Test
    fun leavePlayback_clearsTargetAndMarksReturnToLauncher() {
        val state = MainRootState()
        state.playbackState.enterSmb(smbItem())
        state.playbackState.isMiniPlayerMode = true
        state.playbackState.showPlayerControls = false

        state.playbackState.leavePlayback()

        assertEquals(PlaybackTarget.None, state.playbackState.playbackTarget)
        assertFalse(state.playbackState.isPlaybackActive)
        assertFalse(state.playbackState.isMiniPlayerMode)
        assertTrue(state.playbackState.showPlayerControls)
        assertTrue(state.playbackState.isReturningFromPlayer)
    }

    @Test
    fun recordedSwitch_isAvailableThroughTheRootCompatibilityFacade() {
        val state = MainRootState()
        val first = recording()
        val next = recording(id = 43)
        state.playbackState.enterRecorded(first)
        val session = requireNotNull(state.playbackState.playbackSession)

        assertTrue(state.playbackState.beginRecordedSwitch(next, reason = PlaybackSwitchReason.NextEpisode))
        assertEquals(PlaybackTarget.Recorded(first), state.playbackState.playbackTarget)
        assertEquals(PlaybackTarget.Recorded(next), state.playbackState.renderPlaybackTarget)
        assertEquals(PlaybackPhase.Switching::class, state.playbackState.playbackPhase::class)
        assertTrue(state.playbackState.commitRecordedSwitch(state.playbackState.recordedPlaybackToken))

        assertEquals(PlaybackTarget.Recorded(next), state.playbackState.playbackTarget)
        assertEquals(session, state.playbackState.playbackSession)
    }

    @Test
    fun enterMiniPlayer_withoutPlaybackTarget_returnsFalseAndKeepsItClosed() {
        val state = MainRootState()

        assertFalse(state.playbackState.enterMiniPlayer())

        assertEquals(PlaybackTarget.None, state.playbackState.playbackTarget)
        assertFalse(state.playbackState.isMiniPlayerMode)
    }

    @Test
    fun enterMiniPlayer_withAnyPlaybackTarget_returnsTrue() {
        val cases = listOf<(MainRootState) -> Unit>(
            { it.playbackState.enterLive(channel()) },
            { it.playbackState.enterRecorded(recording()) },
            { it.playbackState.enterSmb(smbItem()) },
        )

        cases.forEach { enterPlayback ->
            val state = MainRootState()
            enterPlayback(state)

            assertTrue(state.playbackState.enterMiniPlayer())
            assertTrue(state.playbackState.isMiniPlayerMode)
            assertTrue(state.playbackState.isPlaybackActive)
        }
    }

    @Test
    fun exitMiniPlayer_preservesPlaybackTarget() {
        val state = MainRootState()
        val recording = recording()
        state.playbackState.enterRecorded(recording)
        assertTrue(state.playbackState.enterMiniPlayer())

        state.playbackState.exitMiniPlayer()

        assertFalse(state.playbackState.isMiniPlayerMode)
        assertEquals(PlaybackTarget.Recorded(recording), state.playbackState.playbackTarget)
        assertEquals(recording, state.playbackState.recordedPlayback?.program)
    }

    @Test
    fun enterLive_withExitMiniPlayerFalse_keepsMiniModeWhileSwitchingChannel() {
        val state = MainRootState()
        val nextChannel = channel(id = "gr-next")
        state.playbackState.enterLive(channel())
        assertTrue(state.playbackState.enterMiniPlayer())

        state.playbackState.enterLive(nextChannel, exitMiniPlayer = false)

        assertTrue(state.playbackState.isMiniPlayerMode)
        assertEquals(PlaybackTarget.Live(nextChannel), state.playbackState.playbackTarget)
        assertEquals(nextChannel.id, state.playbackState.lastSelectedChannelId)
    }

    @Test
    fun resetForLauncherHome_clearsTargetAndRestoresPlaybackUiDefaults() {
        val state = MainRootState()
        state.currentTabIndex = state.getVisibleTabs().indexOf("ビデオ")
        val previousFocusTick = state.launcherHomeFocusTick
        state.playbackState.enterLive(channel())
        state.playbackState.initialPlaybackPositionMs = 9_999L
        state.playbackState.isMiniPlayerMode = true
        state.playbackState.isPlayerMiniListOpen = true
        state.playbackState.playerShowOverlay = true
        state.playbackState.playerIsManualOverlay = true
        state.playbackState.playerIsPinnedOverlay = true
        state.playbackState.playerIsSubMenuOpen = true
        state.playbackState.isPlayerSubMenuOpen = true
        state.playbackState.isPlayerSceneSearchOpen = true
        state.playbackState.showPlayerControls = false
        state.playbackState.isReturningFromPlayer = true
        state.triggerHomeBack = true

        state.resetForLauncherHome()

        assertEquals(0, state.currentTabIndex)
        assertEquals(PlaybackTarget.None, state.playbackState.playbackTarget)
        assertEquals(0L, state.playbackState.initialPlaybackPositionMs)
        assertFalse(state.playbackState.isMiniPlayerMode)
        assertFalse(state.playbackState.isPlayerMiniListOpen)
        assertFalse(state.playbackState.playerShowOverlay)
        assertFalse(state.playbackState.playerIsManualOverlay)
        assertFalse(state.playbackState.playerIsPinnedOverlay)
        assertFalse(state.playbackState.playerIsSubMenuOpen)
        assertFalse(state.playbackState.isPlayerSubMenuOpen)
        assertFalse(state.playbackState.isPlayerSceneSearchOpen)
        assertTrue(state.playbackState.showPlayerControls)
        assertFalse(state.playbackState.isReturningFromPlayer)
        assertEquals(previousFocusTick + 1, state.launcherHomeFocusTick)
        assertFalse(state.triggerHomeBack)
    }

    @Test
    fun resetForLauncherHome_selectsEveryConfiguredVisibleTab() {
        listOf(false, true).forEach { hideAppsTab ->
            val state = MainRootState()
            state.getVisibleTabs(hideAppsTab).forEachIndexed { expectedIndex, tab ->
                state.resetForLauncherHome(tab, hideAppsTab)

                assertEquals(tab, expectedIndex, state.currentTabIndex)
            }
        }
    }

    @Test
    fun resetForLauncherHome_fallsBackForHiddenOrUnsupportedTabs() {
        val state = MainRootState()

        state.resetForLauncherHome("アプリ", hideAppsTab = true)
        assertEquals(0, state.currentTabIndex)

        state.resetForLauncherHome("unsupported")
        assertEquals(0, state.currentTabIndex)
    }

    @Test
    fun resetForLauncherHome_reappliesChangedSettingAndFocusWithoutPlayback() {
        val state = MainRootState()
        state.playbackState.enterLive(channel())

        state.resetForLauncherHome("ライブ")
        assertEquals(state.getVisibleTabs().indexOf("ライブ"), state.currentTabIndex)

        state.resetForLauncherHome("ビデオ", hideAppsTab = true)
        assertEquals(state.getVisibleTabs(hideAppsTab = true).indexOf("ビデオ"), state.currentTabIndex)
        assertEquals(PlaybackTarget.None, state.playbackState.playbackTarget)
        assertEquals(2, state.launcherHomeFocusTick)
        assertFalse(state.triggerHomeBack)
    }

    @Test
    fun returnToCurrentTabFromModule_preservesCurrentTabAndRestoresFocusFromPlayback() {
        val state = MainRootState()
        val videoIndex = state.getVisibleTabs().indexOf("ビデオ")
        state.currentTabIndex = videoIndex
        state.playbackState.enterLive(channel())
        val previousFocusTick = state.launcherHomeFocusTick

        state.returnToCurrentTabFromModule()

        assertEquals(videoIndex, state.currentTabIndex)
        assertEquals(PlaybackTarget.None, state.playbackState.playbackTarget)
        assertTrue(state.playbackState.isReturningFromPlayer)
        assertEquals(previousFocusTick, state.launcherHomeFocusTick)
        assertFalse(state.triggerHomeBack)
    }

    @Test
    fun returnToCurrentTabFromModule_withoutActivePlaybackBumpsLauncherHomeFocusTick() {
        val state = MainRootState()
        val liveIndex = state.getVisibleTabs().indexOf("ライブ")
        state.currentTabIndex = liveIndex
        state.isSettingsOpen = true
        val previousFocusTick = state.launcherHomeFocusTick

        state.returnToCurrentTabFromModule()

        assertEquals(liveIndex, state.currentTabIndex)
        assertFalse(state.playbackState.isReturningFromPlayer)
        assertEquals(previousFocusTick + 1, state.launcherHomeFocusTick)
        assertFalse(state.triggerHomeBack)
    }

    @Test
    fun launcherHomeFocusPendingTracksResetAndReturnTicksUntilConsumed() {
        val state = MainRootState()
        assertFalse(state.hasPendingLauncherHomeFocus)

        state.resetForLauncherHome()
        assertTrue(state.hasPendingLauncherHomeFocus)
        state.consumeLauncherHomeFocus(state.launcherHomeFocusTick)
        assertFalse(state.hasPendingLauncherHomeFocus)

        // Returning from active playback delegates to player-return focus and does not mark pending Home focus.
        state.playbackState.enterLive(channel())
        state.returnToCurrentTabFromModule()
        assertFalse(state.hasPendingLauncherHomeFocus)

        // Returning from a non-playback overlay/settings marks pending Home focus until consumed.
        state.isSettingsOpen = true
        state.returnToCurrentTabFromModule()
        assertTrue(state.hasPendingLauncherHomeFocus)
        state.consumeLauncherHomeFocus(state.launcherHomeFocusTick)
        assertFalse(state.hasPendingLauncherHomeFocus)
    }

    @Test
    fun closeSettings_preservesReadyHomeStateAndCurrentTab() {
        val state = MainRootState()
        val epgIndex = state.getVisibleTabs().indexOf("番組表")
        state.currentTabIndex = epgIndex
        state.isDataReady = true
        state.isUiReady = true
        state.openSettings(SettingsDestination.Item(SettingsFocusTarget.COMMENT_ENGINE))

        state.closeSettings()

        assertFalse(state.isSettingsOpen)
        assertEquals(SettingsDestination.Default, state.settingsDestination)
        assertTrue(state.isDataReady)
        assertTrue(state.isUiReady)
        assertEquals(epgIndex, state.currentTabIndex)
    }

    @Test
    fun shouldRefreshDataAfterSettingsClose_onlyWhenDataSettingsChangedOrRecovering() {
        assertFalse(
            shouldRefreshDataAfterSettingsClose(
                dataRevisionChanged = false,
                isDataReady = true,
                isOfflineMode = false,
                isChannelError = false,
            )
        )
        assertTrue(
            shouldRefreshDataAfterSettingsClose(
                dataRevisionChanged = true,
                isDataReady = true,
                isOfflineMode = false,
                isChannelError = false,
            )
        )
        assertTrue(
            shouldRefreshDataAfterSettingsClose(
                dataRevisionChanged = false,
                isDataReady = false,
                isOfflineMode = false,
                isChannelError = false,
            )
        )
        assertTrue(
            shouldRefreshDataAfterSettingsClose(
                dataRevisionChanged = false,
                isDataReady = true,
                isOfflineMode = true,
                isChannelError = false,
            )
        )
        assertTrue(
            shouldRefreshDataAfterSettingsClose(
                dataRevisionChanged = false,
                isDataReady = true,
                isOfflineMode = false,
                isChannelError = true,
            )
        )
    }

    @Test
    fun upgradeTourHostState_emptyStepsRefusesManualOpenWithoutClosingSettingsOrActivating() {
        val tourState = UpgradeTourHostState(steps = emptyList(), introducedVersionCode = null)
        var closeSettingsCalls = 0
        var unavailableCalls = 0

        val opened = tourState.openManually(
            onCloseSettings = { closeSettingsCalls++ },
            onUnavailable = { unavailableCalls++ },
        )

        assertFalse(opened)
        assertEquals(0, closeSettingsCalls)
        assertEquals(1, unavailableCalls)
        assertFalse(tourState.isManualOpen)
        assertFalse(
            tourState.isActive(
                showMainContent = true,
                isPlaybackActive = false,
                isSettingsOpen = false,
                hasBlockingOverlay = false,
                shouldAutoShow = false,
            ),
        )
    }

    @Test
    fun upgradeTourHostState_stepTransitionsManageSettingsDeepLinksAndResetOnBackAndFinish() {
        val rootState = MainRootState()
        val steps = UpgradeTourCatalog.tourForVersion(42)
        val tourState = UpgradeTourHostState(
            steps = steps,
            introducedVersionCode = UpgradeTourCatalog.tourIntroducedVersionFor(42),
        )
        var markSeenCalls = 0

        assertTrue(
            tourState.openManually(
                onCloseSettings = { rootState.closeSettings() },
                onUnavailable = {},
            ),
        )
        assertTrue(
            tourState.isActive(
                showMainContent = true,
                isPlaybackActive = false,
                isSettingsOpen = rootState.isSettingsOpen,
                hasBlockingOverlay = false,
                shouldAutoShow = false,
            ),
        )

        // Step 3 opens Deep TV settings.
        tourState.handleStepChanged(3, rootState)
        assertTrue(tourState.isControllingSettings)
        assertTrue(rootState.isSettingsOpen)
        assertEquals(
            SettingsDestination.Category(SettingsCategory.DEEP_TV),
            rootState.settingsDestination,
        )

        // Step 4 switches to Comment Engine settings item.
        tourState.handleStepChanged(4, rootState)
        assertTrue(tourState.isControllingSettings)
        assertTrue(rootState.isSettingsOpen)
        assertEquals(
            SettingsDestination.Item(SettingsFocusTarget.COMMENT_ENGINE),
            rootState.settingsDestination,
        )

        // Stepping back to step 2 closes settings cleanly via closeSettings().
        tourState.handleStepChanged(2, rootState)
        assertFalse(tourState.isControllingSettings)
        assertFalse(rootState.isSettingsOpen)
        assertEquals(SettingsDestination.Default, rootState.settingsDestination)

        // Stepping into step 4 and finishing closes settings, resets destination, and marks seen.
        tourState.handleStepChanged(4, rootState)
        assertTrue(rootState.isSettingsOpen)
        tourState.finish(rootState) { markSeenCalls++ }

        assertFalse(tourState.isControllingSettings)
        assertFalse(tourState.isManualOpen)
        assertTrue(tourState.isDismissed)
        assertFalse(rootState.isSettingsOpen)
        assertEquals(SettingsDestination.Default, rootState.settingsDestination)
        assertEquals(1, markSeenCalls)
    }

    @Test
    fun upgradeTourHostState_homeIntentDismissesOnNonSettingsStepsAndIsNoOpWhenInactive() {
        val rootState = MainRootState()
        val tourState = UpgradeTourHostState(
            steps = UpgradeTourCatalog.tourForVersion(42),
            introducedVersionCode = UpgradeTourCatalog.tourIntroducedVersionFor(42),
        )
        var markSeenCalls = 0

        assertTrue(
            tourState.openManually(
                onCloseSettings = { rootState.closeSettings() },
                onUnavailable = {},
            ),
        )
        tourState.handleStepChanged(0, rootState)
        assertFalse(tourState.isControllingSettings)

        assertTrue(tourState.dismissForHomeIntent(shouldAutoShow = false) { markSeenCalls++ })
        assertFalse(tourState.isManualOpen)
        assertTrue(tourState.isDismissed)
        assertEquals(1, markSeenCalls)

        assertFalse(tourState.dismissForHomeIntent(shouldAutoShow = false) { markSeenCalls++ })
        assertEquals(1, markSeenCalls)
    }

    @Test
    fun upgradeTourHostState_dismissIfActiveClearsTourForSettingsIntentWithoutClosingSettings() {
        val rootState = MainRootState()
        val tourState = UpgradeTourHostState(
            steps = UpgradeTourCatalog.tourForVersion(42),
            introducedVersionCode = UpgradeTourCatalog.tourIntroducedVersionFor(42),
        )
        var markSeenCalls = 0

        // Non-settings step: dismisses manual tour and marks seen.
        assertTrue(tourState.openManually(onCloseSettings = {}, onUnavailable = {}))
        tourState.handleStepChanged(0, rootState)
        assertFalse(tourState.isControllingSettings)
        assertTrue(tourState.dismissIfActive(shouldAutoShow = false) { markSeenCalls++ })
        assertFalse(tourState.isManualOpen)
        assertTrue(tourState.isDismissed)
        assertEquals(1, markSeenCalls)

        // Settings-controlled step: clears tour control without closing Settings itself.
        val tourOnSettingsStep = UpgradeTourHostState(
            steps = UpgradeTourCatalog.tourForVersion(42),
            introducedVersionCode = UpgradeTourCatalog.tourIntroducedVersionFor(42),
        )
        assertTrue(tourOnSettingsStep.openManually(onCloseSettings = {}, onUnavailable = {}))
        tourOnSettingsStep.handleStepChanged(4, rootState)
        assertTrue(tourOnSettingsStep.isControllingSettings)
        assertTrue(rootState.isSettingsOpen)

        assertTrue(tourOnSettingsStep.dismissIfActive(shouldAutoShow = false) { markSeenCalls++ })
        assertFalse(tourOnSettingsStep.isControllingSettings)
        assertFalse(tourOnSettingsStep.isManualOpen)
        assertTrue(tourOnSettingsStep.isDismissed)
        assertTrue(rootState.isSettingsOpen)
        assertEquals(2, markSeenCalls)
    }

    @Test
    fun upgradeTourHostState_blockingOverlayPreventsActivationEvenWhenManuallyOpened() {
        val tourState = UpgradeTourHostState(
            steps = UpgradeTourCatalog.tourForVersion(42),
            introducedVersionCode = UpgradeTourCatalog.tourIntroducedVersionFor(42),
        )
        assertTrue(tourState.openManually(onCloseSettings = {}, onUnavailable = {}))

        assertFalse(
            tourState.isActive(
                showMainContent = true,
                isPlaybackActive = false,
                isSettingsOpen = false,
                hasBlockingOverlay = true,
                shouldAutoShow = true,
            ),
        )
        assertTrue(
            tourState.isActive(
                showMainContent = true,
                isPlaybackActive = false,
                isSettingsOpen = false,
                hasBlockingOverlay = false,
                shouldAutoShow = false,
            ),
        )
    }

    @Test
    fun upgradeTourHostState_handleStepActionOpensTargetSettingsAndMarksSeen() {
        val steps = UpgradeTourCatalog.tourForVersion(42)
        var markSeenCalls = 0

        val commentState = MainRootState()
        val commentTour = UpgradeTourHostState(steps, UpgradeTourCatalog.tourIntroducedVersionFor(42))
        assertTrue(commentTour.openManually(onCloseSettings = {}, onUnavailable = {}))
        commentTour.handleStepAction(UpgradeTourAction.OPEN_COMMENT_ENGINE_SETTINGS, commentState) { markSeenCalls++ }
        assertFalse(commentTour.isControllingSettings)
        assertFalse(commentTour.isManualOpen)
        assertTrue(commentTour.isDismissed)
        assertTrue(commentState.isSettingsOpen)
        assertEquals(
            SettingsDestination.Item(SettingsFocusTarget.COMMENT_ENGINE),
            commentState.settingsDestination,
        )

        val deepTvState = MainRootState()
        val deepTvTour = UpgradeTourHostState(steps, UpgradeTourCatalog.tourIntroducedVersionFor(42))
        assertTrue(deepTvTour.openManually(onCloseSettings = {}, onUnavailable = {}))
        deepTvTour.handleStepAction(UpgradeTourAction.OPEN_DEEP_TV_SETTINGS, deepTvState) { markSeenCalls++ }
        assertFalse(deepTvTour.isControllingSettings)
        assertFalse(deepTvTour.isManualOpen)
        assertTrue(deepTvTour.isDismissed)
        assertTrue(deepTvState.isSettingsOpen)
        assertEquals(
            SettingsDestination.Category(SettingsCategory.DEEP_TV),
            deepTvState.settingsDestination,
        )
        assertEquals(2, markSeenCalls)
    }

    @Test
    fun mainRootState_hasRootOverlayDestinationDetectsEveryModalAndSubScreenFlag() {
        val state = MainRootState()
        assertFalse(state.hasRootOverlayDestination())

        val epg = epgProgram()
        val reserve = reserveItem()
        val condition = reservationCondition()
        val recording = recording()

        val toggles: List<Pair<(MainRootState) -> Unit, (MainRootState) -> Unit>> = listOf(
            { s: MainRootState -> s.showConnectionErrorDialog = true } to { s: MainRootState -> s.showConnectionErrorDialog = false },
            { s: MainRootState -> s.isRecordListOpen = true } to { s: MainRootState -> s.isRecordListOpen = false },
            { s: MainRootState -> s.isSeriesListOpen = true } to { s: MainRootState -> s.isSeriesListOpen = false },
            { s: MainRootState -> s.isSmbLibraryOpen = true } to { s: MainRootState -> s.isSmbLibraryOpen = false },
            { s: MainRootState -> s.isOnAirOpen = true } to { s: MainRootState -> s.isOnAirOpen = false },
            { s: MainRootState -> s.isEpgJumpMenuOpen = true } to { s: MainRootState -> s.isEpgJumpMenuOpen = false },
            { s: MainRootState -> s.showDeleteConfirmDialog = true } to { s: MainRootState -> s.showDeleteConfirmDialog = false },
            { s: MainRootState -> s.epgSelectedProgram = epg } to { s: MainRootState -> s.epgSelectedProgram = null },
            { s: MainRootState -> s.selectedReserve = reserve } to { s: MainRootState -> s.selectedReserve = null },
            { s: MainRootState -> s.editingReserveItem = reserve } to { s: MainRootState -> s.editingReserveItem = null },
            { s: MainRootState -> s.editingNewProgram = epg } to { s: MainRootState -> s.editingNewProgram = null },
            { s: MainRootState -> s.reserveToDelete = reserve } to { s: MainRootState -> s.reserveToDelete = null },
            { s: MainRootState -> s.selectedProgramForAutoReserve = recording } to { s: MainRootState -> s.selectedProgramForAutoReserve = null },
            { s: MainRootState -> s.editingCondition = condition } to { s: MainRootState -> s.editingCondition = null },
            { s: MainRootState -> s.selectedConditionReserveItem = reserve } to { s: MainRootState -> s.selectedConditionReserveItem = null },
        )

        toggles.forEachIndexed { index, (enable, disable) ->
            enable(state)
            assertTrue("toggle $index should activate hasRootOverlayDestination", state.hasRootOverlayDestination())
            disable(state)
            assertFalse("toggle $index should clear hasRootOverlayDestination", state.hasRootOverlayDestination())
        }
    }

    @Test
    fun mainRootState_overlayPredicatesShareSingleSourceOfTruthAcrossLauncherTourAndRemoteKeys() {
        val state = MainRootState()
        val epg = epgProgram()
        val reserve = reserveItem()
        val condition = reservationCondition()
        val recording = recording()

        assertFalse(state.hasLauncherSubScreen())
        assertFalse(state.hasReserveOverlay())
        assertFalse(state.hasDetailOrReservationModal())
        assertFalse(state.hasAiOverlay())
        assertFalse(state.hasLauncherFullScreenOverlay())
        assertFalse(state.hasRootOverlayDestination())
        assertFalse(state.hasActiveModuleOrOverlay())
        assertFalse(state.hasUpgradeTourBlockingOverlay(isSettingsInitialized = true))
        assertFalse(state.hasRemoteKeyBlockingOverlay(showMainContent = true))

        // Sub-screens activate launcher full-screen, root overlay, active module, tour blocking, and remote-key blocking.
        val subScreenToggles: List<Pair<String, (MainRootState) -> Unit>> = listOf(
            "isRecordListOpen" to { it.isRecordListOpen = true },
            "isSeriesListOpen" to { it.isSeriesListOpen = true },
            "isSmbLibraryOpen" to { it.isSmbLibraryOpen = true },
            "isOnAirOpen" to { it.isOnAirOpen = true },
        )
        subScreenToggles.forEach { (name, enable) ->
            val s = MainRootState()
            enable(s)
            assertTrue("$name -> hasLauncherSubScreen", s.hasLauncherSubScreen())
            assertTrue("$name -> hasLauncherFullScreenOverlay", s.hasLauncherFullScreenOverlay())
            assertTrue("$name -> hasRootOverlayDestination", s.hasRootOverlayDestination())
            assertTrue("$name -> hasActiveModuleOrOverlay", s.hasActiveModuleOrOverlay())
            assertTrue("$name -> hasUpgradeTourBlockingOverlay", s.hasUpgradeTourBlockingOverlay(isSettingsInitialized = true))
            assertTrue("$name -> hasRemoteKeyBlockingOverlay", s.hasRemoteKeyBlockingOverlay(showMainContent = true))
            s.clearRootOverlays()
            assertFalse("$name cleared", s.hasRootOverlayDestination())
        }

        // Reservation & detail modals activate detail/reservation + launcher full-screen + all blocking predicates.
        val modalToggles: List<Triple<String, Boolean, (MainRootState) -> Unit>> = listOf(
            Triple("selectedReserve", true) { it.selectedReserve = reserve },
            Triple("editingReserveItem", true) { it.editingReserveItem = reserve },
            Triple("reserveToDelete", true) { it.reserveToDelete = reserve },
            Triple("editingCondition", true) { it.editingCondition = condition },
            Triple("selectedConditionReserveItem", true) { it.selectedConditionReserveItem = reserve },
            Triple("epgSelectedProgram", false) { it.epgSelectedProgram = epg },
            Triple("editingNewProgram", false) { it.editingNewProgram = epg },
            Triple("selectedProgramForAutoReserve", false) { it.selectedProgramForAutoReserve = recording },
            Triple("showDeleteConfirmDialog", false) { it.showDeleteConfirmDialog = true },
        )
        modalToggles.forEach { (name, isReserve, enable) ->
            val s = MainRootState()
            enable(s)
            assertEquals("$name -> hasReserveOverlay", isReserve, s.hasReserveOverlay())
            assertTrue("$name -> hasDetailOrReservationModal", s.hasDetailOrReservationModal())
            assertTrue("$name -> hasLauncherFullScreenOverlay", s.hasLauncherFullScreenOverlay())
            assertTrue("$name -> hasRootOverlayDestination", s.hasRootOverlayDestination())
            assertTrue("$name -> hasActiveModuleOrOverlay", s.hasActiveModuleOrOverlay())
            assertTrue("$name -> hasUpgradeTourBlockingOverlay", s.hasUpgradeTourBlockingOverlay(isSettingsInitialized = true))
            assertTrue("$name -> hasRemoteKeyBlockingOverlay", s.hasRemoteKeyBlockingOverlay(showMainContent = true))
            s.clearRootOverlays()
            assertFalse("$name cleared", s.hasDetailOrReservationModal())
        }

        // EPG jump menu must NOT trigger launcher full-screen (keeps nav rail mounted) but blocks root/tour/remote keys.
        state.isEpgJumpMenuOpen = true
        assertFalse(state.hasLauncherFullScreenOverlay())
        assertTrue(state.hasRootOverlayDestination())
        assertTrue(state.hasActiveModuleOrOverlay())
        assertTrue(state.hasUpgradeTourBlockingOverlay(isSettingsInitialized = true))
        assertTrue(state.hasRemoteKeyBlockingOverlay(showMainContent = true))
        state.clearRootOverlays()
        assertFalse(state.isEpgJumpMenuOpen)

        // AI overlays must NOT trigger launcher full-screen or root overlay destination, but activate AI/active/tour blocking.
        listOf<(MainRootState) -> Unit>(
            { it.isAiConciergeOpen = true },
            { it.showAiKeyboardInput = true },
        ).forEach { enable ->
            val s = MainRootState()
            enable(s)
            assertTrue(s.hasAiOverlay())
            assertFalse(s.hasLauncherFullScreenOverlay())
            assertFalse(s.hasRootOverlayDestination())
            assertTrue(s.hasActiveModuleOrOverlay())
            assertTrue(s.hasUpgradeTourBlockingOverlay(isSettingsInitialized = true))
            assertFalse(s.hasRemoteKeyBlockingOverlay(showMainContent = true))
            s.clearRootOverlays()
            assertFalse(s.hasAiOverlay())
        }

        // Settings opens launcher full-screen & active module without blocking tour (tour can control settings).
        state.openSettings()
        assertTrue(state.hasLauncherFullScreenOverlay())
        assertTrue(state.hasActiveModuleOrOverlay())
        assertFalse(state.hasRootOverlayDestination())
        assertFalse(state.hasUpgradeTourBlockingOverlay(isSettingsInitialized = true))
        state.closeSettings()

        // Host modal flags block both upgrade tour and remote keys.
        val hostModalCases = listOf(
            RootHostModalState(hasSyncError = true),
            RootHostModalState(isUpdateDialogVisible = true),
            RootHostModalState(isScreensaverVisible = true),
            RootHostModalState(isExitDialogVisible = true),
        )
        hostModalCases.forEach { modals ->
            assertTrue(modals.hasAnyModal)
            assertTrue(state.hasUpgradeTourBlockingOverlay(isSettingsInitialized = true, hostModals = modals))
            assertTrue(state.hasRemoteKeyBlockingOverlay(showMainContent = true, hostModals = modals))
        }
        assertTrue(state.hasUpgradeTourBlockingOverlay(isSettingsInitialized = false))
        assertTrue(state.hasRemoteKeyBlockingOverlay(showMainContent = false))
        assertTrue(state.hasRemoteKeyBlockingOverlay(showMainContent = true, isUpgradeTourActive = true))

        // clearRootOverlays clears every overlay field including openedSeriesTitle.
        state.epgSelectedProgram = epg
        state.selectedReserve = reserve
        state.editingReserveItem = reserve
        state.editingNewProgram = epg
        state.reserveToDelete = reserve
        state.selectedProgramForAutoReserve = recording
        state.editingCondition = condition
        state.selectedConditionReserveItem = reserve
        state.openedSeriesTitle = "Series A"
        state.isRecordListOpen = true
        state.isSeriesListOpen = true
        state.isSmbLibraryOpen = true
        state.isOnAirOpen = true
        state.isEpgJumpMenuOpen = true
        state.showDeleteConfirmDialog = true
        state.isAiConciergeOpen = true
        state.showAiKeyboardInput = true

        state.clearRootOverlays()

        assertNull(state.openedSeriesTitle)
        assertFalse(state.hasLauncherSubScreen())
        assertFalse(state.hasDetailOrReservationModal())
        assertFalse(state.hasAiOverlay())
        assertFalse(state.hasRootOverlayDestination())
        assertFalse(state.hasActiveModuleOrOverlay())
    }

    @Test
    fun mainRootState_showToastIncrementsTickForRepeatedMessagesAndClearToastResets() {
        val state = MainRootState()
        assertNull(state.toastMessage)
        assertEquals(0, state.toastTick)

        state.showToast("same")
        assertEquals("same", state.toastMessage)
        assertEquals(1, state.toastTick)

        state.showToast("same")
        assertEquals("same", state.toastMessage)
        assertEquals(2, state.toastTick)

        state.clearToast()
        assertNull(state.toastMessage)
        assertEquals(2, state.toastTick)
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

    private fun reservationCondition() = ReservationCondition(
        id = 1,
        reservationCount = 0,
        programSearchCondition = ProgramSearchCondition(keyword = "test"),
        recordSettings = RecordSettings(),
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

    private fun smbItem() = SmbItem(
        name = "test.mp4",
        path = "smb://server/share/test.mp4",
        isDirectory = false,
        size = 1024L,
        lastModified = 0L,
    )
}
