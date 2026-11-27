package com.beeregg2001.komorebi.ui.main

import android.app.Application
import com.beeregg2001.komorebi.data.model.Channel
import com.beeregg2001.komorebi.data.model.EpgProgram
import com.beeregg2001.komorebi.data.model.RecordedProgram
import com.beeregg2001.komorebi.data.model.RecordedVideo
import com.beeregg2001.komorebi.data.model.ReserveChannel
import com.beeregg2001.komorebi.data.model.ReserveItem
import com.beeregg2001.komorebi.data.model.ReserveProgramDetail
import com.beeregg2001.komorebi.navigation.PlaybackOpenRequest
import com.beeregg2001.komorebi.navigation.PlaybackOpenSource
import com.beeregg2001.komorebi.ui.player.PlaybackTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class MainRootStartupAndNHKPolicyTest {

    @Test
    fun resolveStartupTargetChannel_handlesOffLastWatchedTifFallbackAndBackendRules() {
        val tifCh = channel(id = "tif:ch1", name = "TIF 1")
        val gr1 = channel(id = "gr-1", name = "GR 1")
        val gr2 = channel(id = "gr-2", name = "GR 2")
        val mixed = listOf(tifCh, gr1, gr2)

        assertNull(resolveStartupTargetChannel(null, "gr-1", mixed, tifBackend = false, tifActive = true))
        assertNull(resolveStartupTargetChannel("OFF", "gr-1", mixed, tifBackend = false, tifActive = true))
        assertNull(resolveStartupTargetChannel("LAST_WATCHED", "gr-1", emptyList(), tifBackend = false, tifActive = true))

        // LAST_WATCHED exact match
        assertEquals(
            gr2,
            resolveStartupTargetChannel("LAST_WATCHED", "gr-2", mixed, tifBackend = false, tifActive = false),
        )

        // LAST_WATCHED pointing to tif: when !tifActive falls back to first non-TIF channel
        assertEquals(
            gr1,
            resolveStartupTargetChannel("LAST_WATCHED", "tif:ch1", mixed, tifBackend = false, tifActive = false),
        )
        assertNull(
            resolveStartupTargetChannel("LAST_WATCHED", "tif:ch1", listOf(tifCh), tifBackend = false, tifActive = false),
        )

        // LAST_WATCHED pointing to tif: when tifActive = true resolves tifCh
        assertEquals(
            tifCh,
            resolveStartupTargetChannel("LAST_WATCHED", "tif:ch1", mixed, tifBackend = true, tifActive = true),
        )

        // Unknown ID on non-TIF backend falls back to first()
        assertEquals(
            tifCh,
            resolveStartupTargetChannel("unknown-id", null, mixed, tifBackend = false, tifActive = true),
        )

        // Unknown ID on TIF backend returns null
        assertNull(
            resolveStartupTargetChannel("unknown-id", null, mixed, tifBackend = true, tifActive = true),
        )

        // Unknown tif: ID returns null even on non-TIF backend when tifActive = true
        assertNull(
            resolveStartupTargetChannel("tif:missing", null, mixed, tifBackend = false, tifActive = true),
        )
    }

    @Test
    fun isMainSystemReady_evaluatesSplashSettingsStartupChannelOfflineAndEmptyTif() {
        val state = MainRootState()

        // Not ready before startup tab applied
        state.isDataReady = true
        state.isSplashFinished = true
        state.hasAppliedStartupTab = false
        assertFalse(
            isMainSystemReady(
                state = state,
                isSettingsInitialized = true,
                startupChannelSetting = "OFF",
                tifBackend = false,
                isChannelLoading = false,
                hasAnyChannels = true,
            ),
        )

        // Ready when settings uninitialized and splash finished + startup tab applied + OFF
        state.isDataReady = false
        state.hasAppliedStartupTab = true
        assertTrue(
            isMainSystemReady(
                state = state,
                isSettingsInitialized = false,
                startupChannelSetting = "OFF",
                tifBackend = false,
                isChannelLoading = false,
                hasAnyChannels = false,
            ),
        )

        // Ready when data + splash + startup tab + OFF
        state.isDataReady = true
        assertTrue(
            isMainSystemReady(
                state = state,
                isSettingsInitialized = true,
                startupChannelSetting = "OFF",
                tifBackend = false,
                isChannelLoading = false,
                hasAnyChannels = true,
            ),
        )

        // Not ready when splash not finished
        state.isSplashFinished = false
        assertFalse(
            isMainSystemReady(
                state = state,
                isSettingsInitialized = true,
                startupChannelSetting = "OFF",
                tifBackend = false,
                isChannelLoading = false,
                hasAnyChannels = true,
            ),
        )
        state.isSplashFinished = true

        // Waiting for startup channel unless applied, offline, or empty TIF not loading
        assertFalse(
            isMainSystemReady(
                state = state,
                isSettingsInitialized = true,
                startupChannelSetting = "LAST_WATCHED",
                tifBackend = false,
                isChannelLoading = false,
                hasAnyChannels = true,
            ),
        )
        state.hasAppliedStartupChannel = true
        assertTrue(
            isMainSystemReady(
                state = state,
                isSettingsInitialized = true,
                startupChannelSetting = "LAST_WATCHED",
                tifBackend = false,
                isChannelLoading = false,
                hasAnyChannels = true,
            ),
        )
        state.hasAppliedStartupChannel = false

        // Offline mode allows readiness without startup channel
        state.isOfflineMode = true
        assertTrue(
            isMainSystemReady(
                state = state,
                isSettingsInitialized = true,
                startupChannelSetting = "LAST_WATCHED",
                tifBackend = false,
                isChannelLoading = false,
                hasAnyChannels = true,
            ),
        )
        state.isOfflineMode = false

        // Empty TIF backend after loading finishes allows readiness; loading or non-empty does not
        assertTrue(
            isMainSystemReady(
                state = state,
                isSettingsInitialized = true,
                startupChannelSetting = "LAST_WATCHED",
                tifBackend = true,
                isChannelLoading = false,
                hasAnyChannels = false,
            ),
        )
        assertFalse(
            isMainSystemReady(
                state = state,
                isSettingsInitialized = true,
                startupChannelSetting = "LAST_WATCHED",
                tifBackend = true,
                isChannelLoading = true,
                hasAnyChannels = false,
            ),
        )
        assertFalse(
            isMainSystemReady(
                state = state,
                isSettingsInitialized = true,
                startupChannelSetting = "LAST_WATCHED",
                tifBackend = true,
                isChannelLoading = false,
                hasAnyChannels = true,
            ),
        )
    }

    @Test
    fun excludeCurrentNHKContent_resetsExcludedPlaybackClearsExcludedModalsAndHonorsInvalidateFlag() {
        val nhkChannel = channel(id = "gr-nhk", name = "NHK総合")
        val okChannel = channel(id = "gr-ok", name = "日テレ")
        val nhkProgram = recording(id = 100)
        val okProgram = recording(id = 200)
        val nhkEpg = epgProgram(channelId = "gr-nhk")
        val okEpg = epgProgram(channelId = "gr-ok")
        val nhkReserve = reserveItem(id = 1, channelId = "gr-nhk")
        val okReserve = reserveItem(id = 2, channelId = "gr-ok")

        // 1. Excluded live playback resets playback and sends Idle
        val liveState = MainRootState().apply {
            playbackState.enterLive(nhkChannel)
        }
        var idleCalls = 0
        val liveExcluded = excludeCurrentNHKContent(
            state = liveState,
            isExclusionActive = true,
            isChannelExcluded = { it.id == nhkChannel.id },
            isProgramExcluded = { it.id == nhkProgram.id },
            isChannelIdExcluded = { it == nhkChannel.id },
            isReserveExcluded = { it.channel.id == nhkChannel.id },
            onSendIdleState = { idleCalls++ },
        )
        assertTrue(liveExcluded)
        assertEquals(PlaybackTarget.None, liveState.playbackState.playbackTarget)
        assertEquals(1, idleCalls)

        // 2. Excluded recorded playback resets playback and sends Idle
        val recState = MainRootState().apply {
            playbackState.enterRecorded(nhkProgram)
        }
        val recExcluded = excludeCurrentNHKContent(
            state = recState,
            isExclusionActive = true,
            isChannelExcluded = { it.id == nhkChannel.id },
            isProgramExcluded = { it.id == nhkProgram.id },
            isChannelIdExcluded = { it == nhkChannel.id },
            isReserveExcluded = { it.channel.id == nhkChannel.id },
            onSendIdleState = { idleCalls++ },
        )
        assertTrue(recExcluded)
        assertEquals(PlaybackTarget.None, recState.playbackState.playbackTarget)
        assertEquals(2, idleCalls)

        // 3. Selective modal clearing and invalidatePendingOpens = false
        val modalState = MainRootState().apply {
            playbackState.enterLive(okChannel)
            epgSelectedProgram = nhkEpg
            selectedProgramForAutoReserve = nhkProgram
            selectedReserve = nhkReserve
            editingReserveItem = okReserve
            reserveToDelete = nhkReserve
            selectedConditionReserveItem = okReserve
            editingNewProgram = nhkEpg
        }
        val pendingToken = modalState.playbackState.beginPlaybackOpenIntent()
        val nonExcludedPlayback = excludeCurrentNHKContent(
            state = modalState,
            isExclusionActive = true,
            isChannelExcluded = { it.id == nhkChannel.id },
            isProgramExcluded = { it.id == nhkProgram.id },
            isChannelIdExcluded = { it == nhkChannel.id },
            isReserveExcluded = { it.channel.id == nhkChannel.id },
            onSendIdleState = { idleCalls++ },
            invalidatePendingOpens = false,
        )
        assertFalse(nonExcludedPlayback)
        assertEquals(PlaybackTarget.Live(okChannel), modalState.playbackState.playbackTarget)
        assertEquals(2, idleCalls)
        assertTrue(modalState.playbackState.isCurrentPlaybackOpenIntent(pendingToken))
        assertNull(modalState.epgSelectedProgram)
        assertNull(modalState.selectedProgramForAutoReserve)
        assertNull(modalState.selectedReserve)
        assertNotNull(modalState.editingReserveItem)
        assertNull(modalState.reserveToDelete)
        assertNotNull(modalState.selectedConditionReserveItem)
        assertNull(modalState.editingNewProgram)

        // 4. invalidatePendingOpens = true invalidates pending open intent
        modalState.editingReserveItem = nhkReserve
        modalState.selectedConditionReserveItem = nhkReserve
        modalState.editingNewProgram = okEpg
        excludeCurrentNHKContent(
            state = modalState,
            isExclusionActive = true,
            isChannelExcluded = { it.id == nhkChannel.id },
            isProgramExcluded = { it.id == nhkProgram.id },
            isChannelIdExcluded = { it == nhkChannel.id },
            isReserveExcluded = { it.channel.id == nhkChannel.id },
            onSendIdleState = { idleCalls++ },
            invalidatePendingOpens = true,
        )
        assertFalse(modalState.playbackState.isCurrentPlaybackOpenIntent(pendingToken))
        assertNull(modalState.editingReserveItem)
        assertNull(modalState.selectedConditionReserveItem)
        assertEquals(okEpg, modalState.editingNewProgram)

        // 5. Inactive exclusion preserves modals and pending open intents
        val inactiveState = MainRootState().apply {
            epgSelectedProgram = nhkEpg
        }
        val inactiveToken = inactiveState.playbackState.beginPlaybackOpenIntent()
        excludeCurrentNHKContent(
            state = inactiveState,
            isExclusionActive = false,
            isChannelExcluded = { true },
            isProgramExcluded = { true },
            isChannelIdExcluded = { true },
            isReserveExcluded = { true },
            onSendIdleState = { idleCalls++ },
            invalidatePendingOpens = true,
        )
        assertEquals(nhkEpg, inactiveState.epgSelectedProgram)
        assertTrue(inactiveState.playbackState.isCurrentPlaybackOpenIntent(inactiveToken))
    }

    @Test
    fun playbackOpenFailureMessage_returnsExactLocalizedCopyForSourceAndType() {
        assertEquals(
            "リモートで指定されたチャンネルを開けませんでした",
            playbackOpenFailureMessage(
                PlaybackOpenRequest.Live(displayChannelId = "gr011", source = PlaybackOpenSource.Remote),
            ),
        )
        assertEquals(
            "指定されたチャンネルを開けませんでした",
            playbackOpenFailureMessage(
                PlaybackOpenRequest.Live(displayChannelId = "gr011", source = PlaybackOpenSource.DeepLink),
            ),
        )
        assertEquals(
            "リモートで指定された録画を開けませんでした",
            playbackOpenFailureMessage(
                PlaybackOpenRequest.Recording(recordedProgramId = 1, source = PlaybackOpenSource.Remote),
            ),
        )
        assertEquals(
            "指定された録画を開けませんでした",
            playbackOpenFailureMessage(
                PlaybackOpenRequest.Recording(recordedProgramId = 1, source = PlaybackOpenSource.DeepLink),
            ),
        )
    }

    private fun channel(id: String, name: String) = Channel(
        id = id,
        displayChannelId = id,
        name = name,
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

    private fun epgProgram(channelId: String) = EpgProgram(
        id = "epg-$channelId",
        channel_id = channelId,
        title = "Program",
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

    private fun reserveItem(id: Int, channelId: String) = ReserveItem(
        id = id,
        channel = ReserveChannel(id = channelId, name = "Channel"),
        program = ReserveProgramDetail(id = "epg-$id", title = "Reserve"),
    )

    private fun recording(id: Int) = RecordedProgram(
        id = id,
        title = "Recording $id",
        description = "",
        startTime = "2026-08-09T00:00:00+09:00",
        endTime = "2026-08-09T01:00:00+09:00",
        duration = 3600.0,
        isPartiallyRecorded = false,
        recordedVideo = RecordedVideo(
            id = id,
            status = "Recorded",
            filePath = "/recordings/$id.ts",
            duration = 3600.0,
            containerFormat = "MPEG-TS",
            videoCodec = "H.264",
            audioCodec = "AAC-LC",
        ),
    )
}
