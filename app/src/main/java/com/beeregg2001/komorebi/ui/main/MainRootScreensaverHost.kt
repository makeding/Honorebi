package com.beeregg2001.komorebi.ui.main

import android.content.ComponentName
import android.content.Intent
import android.media.AudioManager
import android.view.KeyEvent
import android.view.Window
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import com.beeregg2001.komorebi.common.safeRequestFocus
import com.beeregg2001.komorebi.ui.screensaver.EndedPlaybackScreenHold
import com.beeregg2001.komorebi.ui.screensaver.InAppScreensaverState
import com.beeregg2001.komorebi.ui.screensaver.PausedPlayback
import com.beeregg2001.komorebi.ui.screensaver.PlayerScreensaverContent
import com.beeregg2001.komorebi.ui.screensaver.ScreensaverContent
import com.beeregg2001.komorebi.ui.screensaver.ScreensaverInteractionTracker
import com.beeregg2001.komorebi.ui.screensaver.handleScreensaverKey
import com.beeregg2001.komorebi.ui.screensaver.rememberInAppScreensaverState
import com.beeregg2001.komorebi.ui.screensaver.rememberUpcomingRecordings
import com.beeregg2001.komorebi.viewmodel.SettingsViewModel

@Stable
internal class MainRootScreensaverHostState(
    internal val screensaverState: InAppScreensaverState,
    val usePlayerSaver: Boolean,
    val screensaverImages: List<String>,
    val pausedPlayback: PausedPlayback?,
    val playbackEnded: Boolean,
    val systemSleepAllowed: Boolean,
    val trigger: () -> Unit,
) {
    val isVisible: Boolean get() = screensaverState.isVisible
    fun dismiss() = screensaverState.dismiss()
}

@Composable
internal fun rememberMainRootScreensaverHostState(
    settingsViewModel: SettingsViewModel,
    appWakeState: AppWakeState,
    onInternalActivityLaunch: () -> Unit,
): MainRootScreensaverHostState {
    val context = LocalContext.current
    val currentOnInternalActivityLaunch by rememberUpdatedState(onInternalActivityLaunch)
    val screensaverEnabled by settingsViewModel.screensaverEnabled.collectAsState()
    val screensaverTimeout by settingsViewModel.screensaverTimeoutMinutes.collectAsState()
    val playerTimeout by settingsViewModel.screensaverPlayerTimeoutMinutes.collectAsState()
    val playerStyle by settingsViewModel.screensaverPlayerStyle.collectAsState()
    val endedHoldMinutes by settingsViewModel.playerEndSleepMinutes.collectAsState()
    val pausedPlayback by ScreensaverInteractionTracker.pausedPlayback.collectAsState()
    val playbackEnded by ScreensaverInteractionTracker.playbackEnded.collectAsState()
    val systemSleepAllowed by ScreensaverInteractionTracker.systemSleepAllowed.collectAsState()
    val usePlayerSaver = pausedPlayback != null && playerStyle == "PLAYER"
    EndedPlaybackScreenHold(endedHoldMinutes, appWakeState)
    val screensaverImages by settingsViewModel.screensaverImageUris.collectAsState()
    val screensaverState = rememberInAppScreensaverState(
        enabled = screensaverEnabled,
        timeoutMinutes = screensaverTimeout,
        hasImages = screensaverImages.isNotEmpty(),
        wakeState = appWakeState,
        playerTimeoutMinutes = playerTimeout,
    )

    val trigger: () -> Unit = remember(
        context,
        screensaverState,
        screensaverEnabled,
        screensaverImages,
        pausedPlayback,
    ) {
        {
            if (screensaverEnabled && (screensaverImages.isNotEmpty() || pausedPlayback != null)) {
                screensaverState.showNow()
            } else {
                val intent = Intent(Intent.ACTION_MAIN).apply {
                    component = ComponentName(
                        "com.android.systemui",
                        "com.android.systemui.Somnambulator",
                    )
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                try {
                    currentOnInternalActivityLaunch()
                    context.startActivity(intent)
                } catch (_: Exception) {
                    if (screensaverImages.isNotEmpty() || pausedPlayback != null) {
                        screensaverState.showNow()
                    }
                }
            }
        }
    }

    return remember(
        screensaverState,
        usePlayerSaver,
        screensaverImages,
        pausedPlayback,
        playbackEnded,
        systemSleepAllowed,
        trigger,
    ) {
        MainRootScreensaverHostState(
            screensaverState = screensaverState,
            usePlayerSaver = usePlayerSaver,
            screensaverImages = screensaverImages,
            pausedPlayback = pausedPlayback,
            playbackEnded = playbackEnded,
            systemSleepAllowed = systemSleepAllowed,
            trigger = trigger,
        )
    }
}

@Composable
internal fun MainRootScreensaverDialog(
    hostState: MainRootScreensaverHostState,
    settingsViewModel: SettingsViewModel,
    appWakeState: AppWakeState,
    isInOsPip: Boolean,
) {
    if (isInOsPip ||
        !appWakeState.isAwake ||
        !hostState.isVisible ||
        (hostState.screensaverImages.isEmpty() && hostState.pausedPlayback == null)
    ) {
        return
    }
    val context = LocalContext.current
    val screensaverInterval by settingsViewModel.screensaverIntervalSeconds.collectAsState()
    val screensaverTransition by settingsViewModel.screensaverTransition.collectAsState()
    val screensaverShowUpcoming by settingsViewModel.screensaverShowUpcoming.collectAsState()
    val upcoming = rememberUpcomingRecordings(
        enabled = screensaverShowUpcoming && !hostState.usePlayerSaver,
        visible = true,
    )

    Dialog(
        onDismissRequest = hostState::dismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        // Preserve the paused video rather than dimming the whole player window.
        val dialogView = LocalView.current
        SideEffect {
            dialogView.keepScreenOn =
                hostState.pausedPlayback != null || (hostState.playbackEnded && !hostState.systemSleepAllowed)
        }
        DisposableEffect(dialogView, hostState.usePlayerSaver) {
            val window = (dialogView.parent as? DialogWindowProvider)?.window
            val previousDim = window?.attributes?.dimAmount ?: 0f
            if (hostState.usePlayerSaver) window?.setDimAmount(0f)
            val originalCallback = window?.callback
            if (window != null && originalCallback != null) {
                window.callback = object : Window.Callback by originalCallback {
                    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
                        if (handleScreensaverKey(event, hostState::dismiss) { key ->
                                context.getSystemService(AudioManager::class.java)
                                    .dispatchMediaKeyEvent(key)
                            }
                        ) {
                            return true
                        }
                        return originalCallback.dispatchKeyEvent(event)
                    }
                }
            }
            onDispose {
                if (window != null && originalCallback != null && window.callback !== originalCallback) {
                    window.callback = originalCallback
                }
                window?.setDimAmount(previousDim)
            }
        }
        val screensaverFocusRequester = remember { FocusRequester() }
        LaunchedEffect(Unit) {
            screensaverFocusRequester.safeRequestFocus("MainRootScreensaver")
        }
        Box(
            Modifier
                .fillMaxSize()
                .focusRequester(screensaverFocusRequester)
                .focusable()
                .onPreviewKeyEvent { event ->
                    handleScreensaverKey(event.nativeKeyEvent, hostState::dismiss) { key ->
                        context.getSystemService(AudioManager::class.java).dispatchMediaKeyEvent(key)
                    }
                }
                .clickable { hostState.dismiss() }
        ) {
            if (hostState.usePlayerSaver) {
                PlayerScreensaverContent(
                    title = hostState.pausedPlayback?.title.orEmpty(),
                    subtitle = hostState.pausedPlayback?.subtitle.orEmpty(),
                )
            } else {
                ScreensaverContent(
                    imageUris = hostState.screensaverImages,
                    intervalSeconds = screensaverInterval,
                    transition = screensaverTransition,
                    upcomingRecordings = upcoming,
                )
            }
        }
    }
}
