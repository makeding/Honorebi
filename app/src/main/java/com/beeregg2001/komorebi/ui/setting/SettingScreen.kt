@file:OptIn(ExperimentalComposeUiApi::class)

package com.beeregg2001.komorebi.ui.setting

import android.content.pm.PackageManager
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.tv.material3.ExperimentalTvMaterial3Api
import com.beeregg2001.komorebi.data.SettingsRepository
import com.beeregg2001.komorebi.data.model.DeviceAuthRequest
import com.beeregg2001.komorebi.data.repository.tif.TifInput
import com.beeregg2001.komorebi.ui.home.LocalUpgradeTourActive
import com.beeregg2001.komorebi.viewmodel.ChannelViewModel
import com.beeregg2001.komorebi.viewmodel.SettingsViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal val TIME_FORMAT_OPTIONS = listOf(
    "28時間表記 (24:00–27:59)" to "28H",
    "24時間表記" to "24H",
    "12時間表記 (AM/PM)" to "12H"
)

internal const val SETTING_CATEGORY_COALESCE_MS = 200L

@Composable
internal fun SettingsInitialFocusEffect(
    onRequestInitialFocus: suspend () -> Unit,
) {
    val isUpgradeTourActive = LocalUpgradeTourActive.current
    val currentOnRequestInitialFocus by rememberUpdatedState(onRequestInitialFocus)
    var hasAppliedInitialFocus by remember { mutableStateOf(false) }
    LaunchedEffect(isUpgradeTourActive) {
        if (isUpgradeTourActive || hasAppliedInitialFocus) return@LaunchedEffect
        hasAppliedInitialFocus = true
        currentOnRequestInitialFocus()
    }
}

@RequiresApi(Build.VERSION_CODES.O)
@OptIn(ExperimentalTvMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onClearLastChannel: () -> Unit = {},
    onClearWatchHistory: () -> Unit = {},
    destination: SettingsDestination = SettingsDestination.Default,
    onOpenWhatsNew: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel(),
    channelViewModel: ChannelViewModel = hiltViewModel()
) {
    SettingsMenuScreen(
        onBack = onBack,
        onClearLastChannel = onClearLastChannel,
        onClearWatchHistory = onClearWatchHistory,
        destination = destination,
        onOpenWhatsNew = onOpenWhatsNew,
        viewModel = viewModel,
        channelViewModel = channelViewModel,
    )
}

@RequiresApi(Build.VERSION_CODES.O)
@OptIn(ExperimentalTvMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun SettingsMenuScreen(
    onBack: () -> Unit,
    onClearLastChannel: () -> Unit = {},
    onClearWatchHistory: () -> Unit = {},
    destination: SettingsDestination = SettingsDestination.Default,
    onOpenWhatsNew: () -> Unit = {},
    viewModel: SettingsViewModel,
    channelViewModel: ChannelViewModel,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repository = remember { SettingsRepository(context) }
    val homeEnvironment = remember(context) { LauncherEnvironmentDetector.detect(context) }
    var isDefaultHome by remember { mutableStateOf(false) }

    val prefs = rememberSettingPreferences(repository)
    val controller = rememberSettingNavigationController(destination)
    val sidebarRows = remember { buildSettingSidebarRows() }

    val receiveBetaUpdates by viewModel.receiveBetaUpdates.collectAsState()
    val playerUiMode by viewModel.playerUiMode.collectAsState()
    val autoCmSkip by viewModel.autoCmSkip.collectAsState()
    val recordedSeekStepSeconds by viewModel.recordedSeekStepSeconds.collectAsState()
    val preferOriginalMpegTs by viewModel.preferOriginalMpegTs.collectAsState()
    val availableQualities by viewModel.availableQualities.collectAsState()
    val honomiSession by viewModel.honomiSession.collectAsState()
    val honomiLoginInProgress by viewModel.honomiLoginInProgress.collectAsState()
    val screensaverEnabled by viewModel.screensaverEnabled.collectAsState()
    val screensaverShowUpcoming by viewModel.screensaverShowUpcoming.collectAsState()
    val screensaverTimeout by viewModel.screensaverTimeoutMinutes.collectAsState()
    val screensaverPlayerTimeout by viewModel.screensaverPlayerTimeoutMinutes.collectAsState()
    val screensaverPlayerStyle by viewModel.screensaverPlayerStyle.collectAsState()
    val playerEndSleepMinutes by viewModel.playerEndSleepMinutes.collectAsState()
    val screensaverInterval by viewModel.screensaverIntervalSeconds.collectAsState()
    val screensaverTransition by viewModel.screensaverTransition.collectAsState()
    val screensaverImages by viewModel.screensaverImageUris.collectAsState()
    val groupedChannels by channelViewModel.groupedChannels.collectAsState()
    val flatChannels = remember(groupedChannels) { groupedChannels.values.flatten() }

    var honomiLoginError by remember { mutableStateOf<String?>(null) }
    var honomiPairing by remember { mutableStateOf<DeviceAuthRequest?>(null) }

    val tifSupported by produceState(false, context) {
        value = withContext(Dispatchers.IO) { TifInput.isSupported(context) }
    }
    val pipSupported by produceState(false, context) {
        value = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)
    }

    val batchItemRs =
        remember(prefs.postRecordingBatchList) { List(prefs.postRecordingBatchList.size) { FocusRequester() } }
    val smbItemRs =
        remember(prefs.smbServerList) { List(prefs.smbServerList.size) { FocusRequester() } }

    val mainScrollState = rememberScrollState()
    val sidebarScrollState = rememberScrollState()
    val closeDialog = { controller.closeDialog(scope) }

    SettingScaffold(
        controller = controller,
        destination = destination,
        sidebarRows = sidebarRows,
        mainScrollState = mainScrollState,
        sidebarScrollState = sidebarScrollState,
        onCloseDialog = closeDialog,
        onBack = onBack,
        categoryContent = { activeCategory ->
            SettingCategoryContentHost(
                activeCategory = activeCategory,
                controller = controller,
                prefs = prefs,
                repository = repository,
                viewModel = viewModel,
                channelViewModel = channelViewModel,
                mainScrollState = mainScrollState,
                flatChannels = flatChannels,
                availableQualities = availableQualities,
                playerUiMode = playerUiMode,
                autoCmSkip = autoCmSkip,
                recordedSeekStepSeconds = recordedSeekStepSeconds,
                preferOriginalMpegTs = preferOriginalMpegTs,
                receiveBetaUpdates = receiveBetaUpdates,
                honomiUserName = honomiSession?.userName,
                onHonomiAccountClick = {
                    if (honomiSession == null) {
                        honomiLoginError = null
                        honomiPairing = null
                        controller.activeDialog = SettingDialogState.HonomiLogin
                    } else {
                        controller.activeDialog = SettingDialogState.HonomiLogout
                    }
                },
                homeEnvironment = homeEnvironment,
                isDefaultHome = isDefaultHome,
                onRefreshDefaultHome = {
                    isDefaultHome = LauncherEnvironmentDetector.isDefaultHome(context)
                },
                tifSupported = tifSupported,
                pipSupported = pipSupported,
                screensaverEnabled = screensaverEnabled,
                screensaverShowUpcoming = screensaverShowUpcoming,
                screensaverTimeout = screensaverTimeout,
                screensaverPlayerTimeout = screensaverPlayerTimeout,
                screensaverPlayerStyle = screensaverPlayerStyle,
                playerEndSleepMinutes = playerEndSleepMinutes,
                screensaverInterval = screensaverInterval,
                screensaverTransition = screensaverTransition,
                screensaverImageCount = screensaverImages.size,
                batchItemRs = batchItemRs,
                smbItemRs = smbItemRs,
                onClearLastChannel = onClearLastChannel,
                onClearWatchHistory = onClearWatchHistory,
                onOpenWhatsNew = onOpenWhatsNew,
            )
        },
        dialogsContent = {
            SettingDialogsHost(
                controller = controller,
                prefs = prefs,
                repository = repository,
                viewModel = viewModel,
                homeEnvironment = homeEnvironment,
                isDefaultHome = isDefaultHome,
                screensaverImages = screensaverImages,
                screensaverTransition = screensaverTransition,
                screensaverShowUpcoming = screensaverShowUpcoming,
                screensaverPlayerStyle = screensaverPlayerStyle,
                honomiLoginInProgress = honomiLoginInProgress,
                honomiLoginError = honomiLoginError,
                onHonomiLoginErrorChange = { honomiLoginError = it },
                honomiPairing = honomiPairing,
                onHonomiPairingChange = { honomiPairing = it },
                onCloseDialog = closeDialog,
            )
        },
    )
}
