package com.beeregg2001.komorebi.ui.main

import android.view.KeyEvent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import com.beeregg2001.komorebi.common.pickCmEscapeChannel
import com.beeregg2001.komorebi.data.model.Channel
import kotlinx.coroutines.delay

@Stable
internal class MainRootRemoteKeyController(
    val state: MainRootState,
    val aiFeaturesEnabled: Boolean = false,
) {
    var channelNumberBuffer by mutableStateOf("")
        internal set
    var channelNumberInputTick by mutableIntStateOf(0)
        internal set
    var channelNumberNotFound by mutableStateOf<String?>(null)
        internal set
    var channelNumberNotFoundTick by mutableIntStateOf(0)
        internal set
    var consumedShortcutKeyCode by mutableStateOf<Int?>(null)
        internal set
    var consumedOsdActionKeyCode by mutableStateOf<Int?>(null)
        internal set
    var cmPanicReturnChannel by mutableStateOf<Channel?>(null)
        internal set
    var isLongPressHandled by mutableStateOf(false)
        internal set

    fun onLivePlaybackChanged(liveChannel: Channel?) {
        if (liveChannel == null) {
            cmPanicReturnChannel = null
        }
    }

    fun onOsPipChanged(isInOsPip: Boolean) {
        if (isInOsPip) {
            channelNumberBuffer = ""
            channelNumberNotFound = null
            consumedShortcutKeyCode = null
            consumedOsdActionKeyCode = null
        }
    }

    fun clearNotFound() {
        channelNumberNotFound = null
    }

    fun buildRemoteKeyContext(
        isInOsPip: Boolean,
        showMainContent: Boolean,
        isUpgradeTourActive: Boolean,
        hostModals: RootHostModalState,
    ): RemoteKeyContext {
        val liveChannel = state.playbackState.livePlayback?.channel
        val isLiveFullscreen = liveChannel != null && !state.playbackState.isMiniPlayerMode
        val isVideoFullscreen =
            (state.playbackState.recordedPlayback != null || state.playbackState.smbPlayback != null) &&
                !state.playbackState.isMiniPlayerMode
        val isTifPassthrough = liveChannel != null &&
            liveChannel.id.startsWith("tif:") &&
            (state.backendType != "TIF" || liveChannel.id.startsWith("tif:input:"))
        val hasRootOverlay = state.hasRemoteKeyBlockingOverlay(
            showMainContent = showMainContent,
            isUpgradeTourActive = isUpgradeTourActive,
            hostModals = hostModals,
        )
        return RemoteKeyContext(
            isInOsPip = isInOsPip,
            isSettingsOpen = state.isSettingsOpen,
            isAiOverlayOpen = state.hasAiOverlay(),
            hasRootOverlay = hasRootOverlay,
            isLivePlaybackFullscreen = isLiveFullscreen,
            isTifPassthrough = isTifPassthrough,
            isRecordedPlaybackFullscreen = isVideoFullscreen,
        )
    }

    fun buildOsdState(
        tunableChannels: List<Channel>,
        currentLiveType: String?,
    ): ChannelNumberOsdState {
        val matchedChannelPreview = if (channelNumberBuffer.isEmpty()) {
            null
        } else {
            ChannelNumberResolver.resolve(channelNumberBuffer, tunableChannels, currentLiveType)
        }
        return ChannelNumberOsdState(
            inputBuffer = channelNumberBuffer,
            matchedChannelName = matchedChannelPreview?.name,
            notFoundInput = channelNumberNotFound,
        )
    }

    fun commitChannelNumber(
        tunableChannels: List<Channel>,
        currentLiveType: String?,
        onSaveLastChannel: (Channel) -> Unit,
    ) {
        val input = channelNumberBuffer
        channelNumberBuffer = ""
        if (input.isNotEmpty()) {
            val resolved = ChannelNumberResolver.resolve(input, tunableChannels, currentLiveType)
            if (resolved != null) {
                channelNumberNotFound = null
                state.playbackState.dismissPlayerOverlaysForRemoteControl()
                state.playbackState.enterLive(
                    channel = resolved,
                    exitMiniPlayer = state.playbackState.livePlayback == null,
                )
                onSaveLastChannel(resolved)
            } else {
                channelNumberNotFound = input
                channelNumberNotFoundTick++
            }
        }
    }

    fun handleRemoteShortcut(
        shortcut: RemoteShortcut,
        tabs: List<String>,
        hasRootOverlay: Boolean,
        tifActive: Boolean,
        lastChannels: List<Channel>,
        tunableChannels: List<Channel>,
        defaultSystemChannel: Channel?,
        isChannelExcluded: (Channel) -> Boolean,
        onDismissRootOverlays: () -> Unit,
        onEnsureEpgLoaded: () -> Unit,
        onSaveLastChannel: (Channel) -> Unit,
    ) {
        when (shortcut) {
            RemoteShortcut.GUIDE -> {
                onDismissRootOverlays()
                val tabIndex = tabs.indexOf("番組表")
                if (tabIndex != -1) {
                    if (state.playbackState.isPlaybackActive) {
                        state.playbackState.enterMiniPlayer()
                    }
                    state.currentTabIndex = tabIndex
                    onEnsureEpgLoaded()
                } else {
                    state.showToast(
                        "番組表を開けません：現在の接続先は番組表に対応していません。接続設定でメインシステムを確認してください。"
                    )
                }
            }
            RemoteShortcut.DVR -> {
                onDismissRootOverlays()
                val tabIndex = tabs.indexOf("ビデオ")
                if (tabIndex != -1) {
                    if (state.playbackState.isPlaybackActive) {
                        state.playbackState.enterMiniPlayer()
                    }
                    state.currentTabIndex = tabIndex
                    state.isRecordListOpen = true
                } else {
                    state.showToast(
                        "録画リストを開けません：現在の接続先は録画リストに対応していません。接続設定でメインシステムを確認してください。"
                    )
                }
            }
            RemoteShortcut.TV -> {
                if (state.playbackState.livePlayback != null &&
                    !state.playbackState.isMiniPlayerMode &&
                    !hasRootOverlay &&
                    !state.isSettingsOpen
                ) {
                    state.playbackState.playerShowOverlay = true
                } else if (state.playbackState.livePlayback != null) {
                    onDismissRootOverlays()
                    state.playbackState.exitMiniPlayer()
                } else {
                    onDismissRootOverlays()
                    val lastCandidate = lastChannels.firstOrNull { ch ->
                        !isChannelExcluded(ch) &&
                            (tifActive || !ch.id.startsWith("tif:"))
                    }?.let { last ->
                        tunableChannels.firstOrNull {
                            it.id == last.id || it.displayChannelId == last.displayChannelId
                        } ?: last
                    }
                    val targetChannel = lastCandidate
                        ?: defaultSystemChannel?.takeIf { !isChannelExcluded(it) }
                    if (targetChannel != null) {
                        state.playbackState.dismissPlayerOverlaysForRemoteControl()
                        state.playbackState.enterLive(targetChannel)
                        onSaveLastChannel(targetChannel)
                    } else {
                        val liveIndex = tabs.indexOf("ライブ")
                        if (liveIndex != -1) state.currentTabIndex = liveIndex
                    }
                }
            }
        }
    }

    fun handleCmPanicZap(
        tunableChannels: List<Channel>,
        onSaveLastChannel: (Channel) -> Unit,
    ): Boolean {
        val current = state.playbackState.livePlayback?.channel
            ?.takeIf { !state.playbackState.isMiniPlayerMode }
            ?: return false
        val returnTarget = cmPanicReturnChannel
        if (returnTarget != null && returnTarget.id != current.id) {
            cmPanicReturnChannel = null
            state.playbackState.enterLive(returnTarget)
            onSaveLastChannel(returnTarget)
            state.showToast("元のチャンネルへ復帰: ${returnTarget.name}")
        } else {
            val escape = pickCmEscapeChannel(tunableChannels, current.id)
            if (escape != null) {
                cmPanicReturnChannel = current
                state.playbackState.enterLive(escape)
                onSaveLastChannel(escape)
                state.showToast("CM緊急退避 → ${escape.name}（黄ボタンで復帰）")
            } else {
                state.showToast("退避先の実況チャンネルが見つかりません")
            }
        }
        return true
    }

    fun handlePreviewKeyEvent(
        nativeEvent: KeyEvent,
        cmPanicZapEnabled: Boolean,
        tunableChannels: List<Channel>,
        onSaveLastChannel: (Channel) -> Unit,
    ): Boolean {
        val nativeKeyCode = nativeEvent.keyCode
        if (nativeEvent.action == KeyEvent.ACTION_UP && consumedOsdActionKeyCode == nativeKeyCode) {
            consumedOsdActionKeyCode = null
            return true
        }
        val liveChannel = state.playbackState.livePlayback?.channel
        val isLiveFullscreen = liveChannel != null && !state.playbackState.isMiniPlayerMode
        if (cmPanicZapEnabled &&
            isLiveFullscreen &&
            nativeKeyCode == KeyEvent.KEYCODE_PROG_YELLOW &&
            nativeEvent.action == KeyEvent.ACTION_DOWN &&
            nativeEvent.repeatCount == 0
        ) {
            if (handleCmPanicZap(tunableChannels, onSaveLastChannel)) {
                consumedOsdActionKeyCode = nativeKeyCode
                return true
            }
        }
        if (channelNumberBuffer.isNotEmpty()) {
            val isBackKey = nativeKeyCode == KeyEvent.KEYCODE_BACK ||
                nativeKeyCode == KeyEvent.KEYCODE_ESCAPE
            val isConfirmKey = nativeKeyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
                nativeKeyCode == KeyEvent.KEYCODE_ENTER ||
                nativeKeyCode == KeyEvent.KEYCODE_NUMPAD_ENTER
            if (isBackKey) {
                if (nativeEvent.action == KeyEvent.ACTION_DOWN) {
                    channelNumberBuffer = ""
                    channelNumberNotFound = null
                    consumedOsdActionKeyCode = nativeKeyCode
                }
                return true
            }
            if (isConfirmKey) {
                if (nativeEvent.action == KeyEvent.ACTION_DOWN && nativeEvent.repeatCount == 0) {
                    consumedOsdActionKeyCode = nativeKeyCode
                    commitChannelNumber(tunableChannels, liveChannel?.type, onSaveLastChannel)
                }
                return true
            }
        }

        val isCenterKey = nativeKeyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
            nativeKeyCode == KeyEvent.KEYCODE_ENTER ||
            nativeKeyCode == KeyEvent.KEYCODE_NUMPAD_ENTER

        // ★ 追加: 「プレイヤーのUI（シークバーやボタン）が表示されている時」は、
        // 親玉（MainRoot）がイベントを横取りせず、子要素（PlayerControlsの早送り連打など）にイベントを譲る！
        val isVideoUiVisible =
            (state.playbackState.recordedPlayback != null || state.playbackState.smbPlayback != null) && state.playbackState.showPlayerControls
        val isLiveUiVisible = state.playbackState.livePlayback != null && state.playbackState.playerShowOverlay
        if ((isVideoUiVisible || isLiveUiVisible) && isCenterKey) {
            return false // 横取りせず、子要素へスルーさせる
        }

        if (!aiFeaturesEnabled) return false

        if (isCenterKey && nativeEvent.action == KeyEvent.ACTION_UP && isLongPressHandled) {
            isLongPressHandled = false
            return true
        }
        if (state.isAiConciergeOpen || state.showAiKeyboardInput) return false
        if (isCenterKey && nativeEvent.action == KeyEvent.ACTION_DOWN) {
            if ((nativeEvent.isLongPress || nativeEvent.repeatCount > 0) && !isLongPressHandled) {
                isLongPressHandled = true
                state.isAiConciergeOpen = true
                state.aiTicketManager.issue(AiFocusTicket.PANEL_DEFAULT)
                return true
            }
            if (isLongPressHandled) return true
        }
        return false
    }

    fun handleKeyEvent(
        nativeEvent: KeyEvent,
        remoteShortcutKeysEnabled: Boolean,
        remoteNumberTuningEnabled: Boolean,
        remoteKeyContext: RemoteKeyContext,
        tabs: List<String>,
        tifActive: Boolean,
        lastChannels: List<Channel>,
        tunableChannels: List<Channel>,
        defaultSystemChannel: Channel?,
        isChannelExcluded: (Channel) -> Boolean,
        onDismissRootOverlays: () -> Unit,
        onEnsureEpgLoaded: () -> Unit,
        onSaveLastChannel: (Channel) -> Unit,
    ): Boolean {
        val nativeKeyCode = nativeEvent.keyCode
        if (nativeEvent.action == KeyEvent.ACTION_UP && consumedShortcutKeyCode == nativeKeyCode) {
            consumedShortcutKeyCode = null
            return true
        }
        val shortcut = RemoteKeyShortcuts.shortcutFor(nativeKeyCode)
        if (shortcut != null &&
            remoteShortcutKeysEnabled &&
            RemoteKeyShortcuts.shortcutsAllowed(remoteKeyContext)
        ) {
            if (nativeEvent.action == KeyEvent.ACTION_DOWN) {
                if (nativeEvent.repeatCount == 0) {
                    consumedShortcutKeyCode = nativeKeyCode
                    channelNumberBuffer = ""
                    channelNumberNotFound = null
                    handleRemoteShortcut(
                        shortcut = shortcut,
                        tabs = tabs,
                        hasRootOverlay = remoteKeyContext.hasRootOverlay,
                        tifActive = tifActive,
                        lastChannels = lastChannels,
                        tunableChannels = tunableChannels,
                        defaultSystemChannel = defaultSystemChannel,
                        isChannelExcluded = isChannelExcluded,
                        onDismissRootOverlays = onDismissRootOverlays,
                        onEnsureEpgLoaded = onEnsureEpgLoaded,
                        onSaveLastChannel = onSaveLastChannel,
                    )
                }
                return true
            }
        }
        val digit = RemoteKeyShortcuts.digitFor(nativeKeyCode)
        if (digit != null &&
            remoteNumberTuningEnabled &&
            RemoteKeyShortcuts.numberTuningAllowed(remoteKeyContext)
        ) {
            if (nativeEvent.action == KeyEvent.ACTION_DOWN) {
                if (nativeEvent.repeatCount == 0) {
                    consumedShortcutKeyCode = nativeKeyCode
                    channelNumberNotFound = null
                    channelNumberBuffer = RemoteKeyShortcuts.append(channelNumberBuffer, digit)
                    channelNumberInputTick++
                }
                return true
            }
        }
        return false
    }
}

@Composable
internal fun rememberMainRootRemoteKeyController(
    state: MainRootState,
    aiFeaturesEnabled: Boolean = false,
): MainRootRemoteKeyController = remember(state, aiFeaturesEnabled) {
    MainRootRemoteKeyController(
        state = state,
        aiFeaturesEnabled = aiFeaturesEnabled,
    )
}

@Composable
internal fun MainRootRemoteKeyEffects(
    controller: MainRootRemoteKeyController,
    liveChannel: Channel?,
    isInOsPip: Boolean,
    tunableChannels: List<Channel>,
    currentLiveType: String?,
    onSaveLastChannel: (Channel) -> Unit,
) {
    val currentTunableChannels by rememberUpdatedState(tunableChannels)
    val currentType by rememberUpdatedState(currentLiveType)
    val currentOnSaveLastChannel by rememberUpdatedState(onSaveLastChannel)

    LaunchedEffect(liveChannel) {
        controller.onLivePlaybackChanged(liveChannel)
    }
    LaunchedEffect(controller.channelNumberBuffer, controller.channelNumberInputTick) {
        if (controller.channelNumberBuffer.isEmpty()) return@LaunchedEffect
        delay(RemoteKeyShortcuts.commitDelayMs(controller.channelNumberBuffer))
        controller.commitChannelNumber(
            tunableChannels = currentTunableChannels,
            currentLiveType = currentType,
            onSaveLastChannel = currentOnSaveLastChannel,
        )
    }
    LaunchedEffect(controller.channelNumberNotFound, controller.channelNumberNotFoundTick) {
        if (controller.channelNumberNotFound == null) return@LaunchedEffect
        delay(RemoteKeyShortcuts.NOT_FOUND_DISPLAY_MS)
        controller.clearNotFound()
    }
    LaunchedEffect(isInOsPip) {
        controller.onOsPipChanged(isInOsPip)
    }
}
