@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.beeregg2001.komorebi.ui.video.player

import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.runtime.Composable
import com.beeregg2001.komorebi.data.model.Channel
import com.beeregg2001.komorebi.data.model.RecordedProgram
import com.beeregg2001.komorebi.ui.player.RecordedPlaybackToken
import com.beeregg2001.komorebi.ui.video.smb.SmbItem
import com.beeregg2001.komorebi.ui.video.smb.SmbPlaybackMetadata
import com.beeregg2001.komorebi.ui.video.player.policy.RecordedSwitchTerminalFailure
import com.beeregg2001.komorebi.viewmodel.SettingsViewModel
import com.beeregg2001.komorebi.viewmodel.VideoPlayerViewModel

/** Chooses a source-specific playback scene before either scene initializes its state. */
@RequiresApi(Build.VERSION_CODES.O)
@Composable
fun VideoPlayerScreen(
    program: RecordedProgram?, smbItem: SmbItem? = null,
    smbMetadata: SmbPlaybackMetadata? = smbItem?.let(SmbPlaybackMetadata::from),
    initialPositionMs: Long = 0, initialQuality: String = "1080p-60fps", isNetworkAvailable: Boolean = true,
    showControls: Boolean, onShowControlsChange: (Boolean) -> Unit, isSubMenuOpen: Boolean,
    onSubMenuToggle: (Boolean) -> Unit, isSceneSearchOpen: Boolean, onSceneSearchToggle: (Boolean) -> Unit,
    recentRecordings: List<RecordedProgram> = emptyList(), animeChannels: List<Channel> = emptyList(),
    onProgramSelect: (RecordedProgram, RecordedProgramSelectionReason) -> Unit = { _, _ -> },
    recordedPlaybackToken: RecordedPlaybackToken? = null, isCurrentRecordedPlayback: (RecordedPlaybackToken) -> Boolean = { true },
    isRecordedSwitching: Boolean = false, onProgramReady: (Int, RecordedPlaybackToken) -> Unit = { _, _ -> },
    onRecordedSwitchTerminalFailure: (RecordedPlaybackToken, RecordedSwitchTerminalFailure) -> Unit = { _, _ -> },
    shouldPersistWatchHistory: () -> Boolean = { true }, onChannelSelect: (Channel) -> Unit = {}, onPlaybackEnded: () -> Unit = {},
    onBackPressed: () -> Unit, onShowToast: (String) -> Unit, isPiPMode: Boolean = false, onPiPRequested: () -> Unit = {},
    videoPlayerViewModel: VideoPlayerViewModel, settingsViewModel: SettingsViewModel,
) {
    if (program == null) {
        SmbPlayerScreen(requireNotNull(smbItem), requireNotNull(smbMetadata), initialPositionMs, showControls, onShowControlsChange, isSubMenuOpen, onSubMenuToggle, onBackPressed, onShowToast, isPiPMode, onPiPRequested, settingsViewModel)
    } else RecordedPlayerScreen(program, initialPositionMs, initialQuality, isNetworkAvailable, showControls, onShowControlsChange, isSubMenuOpen, onSubMenuToggle, isSceneSearchOpen, onSceneSearchToggle, recentRecordings, animeChannels, onProgramSelect, recordedPlaybackToken, isCurrentRecordedPlayback, isRecordedSwitching, onProgramReady, onRecordedSwitchTerminalFailure, shouldPersistWatchHistory, onChannelSelect, onPlaybackEnded, onBackPressed, onShowToast, isPiPMode, onPiPRequested, videoPlayerViewModel, settingsViewModel)
}
