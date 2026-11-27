@file:OptIn(ExperimentalComposeUiApi::class, ExperimentalTvMaterial3Api::class)

package com.beeregg2001.komorebi.ui.setting

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.ExperimentalTvMaterial3Api
import com.beeregg2001.komorebi.data.SettingsRepository
import com.beeregg2001.komorebi.data.model.DeviceAuthRequest
import com.beeregg2001.komorebi.ui.components.LocalShowToast
import com.beeregg2001.komorebi.ui.screensaver.PlayerScreensaverContent
import com.beeregg2001.komorebi.ui.screensaver.ScreensaverContent
import com.beeregg2001.komorebi.ui.screensaver.rememberUpcomingRecordings
import com.beeregg2001.komorebi.viewmodel.SettingsViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
internal fun BoxScope.SettingDialogsHost(
    controller: SettingNavigationController,
    prefs: SettingPreferences,
    repository: SettingsRepository,
    viewModel: SettingsViewModel,
    homeEnvironment: HomeEnvironment,
    isDefaultHome: Boolean,
    screensaverImages: List<String>,
    screensaverTransition: String,
    screensaverShowUpcoming: Boolean,
    screensaverPlayerStyle: String,
    honomiLoginInProgress: Boolean,
    honomiLoginError: String?,
    onHonomiLoginErrorChange: (String?) -> Unit,
    honomiPairing: DeviceAuthRequest?,
    onHonomiPairingChange: (DeviceAuthRequest?) -> Unit,
    onCloseDialog: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val showToast = LocalShowToast.current

    // --- ダイアログ表示制御 ---
    LaunchedEffect(controller.activeDialog, honomiPairing) {
        val pairing = honomiPairing ?: return@LaunchedEffect
        if (controller.activeDialog !is SettingDialogState.HonomiLogin) return@LaunchedEffect
        val deadline = System.currentTimeMillis() + pairing.expiresIn * 1000L
        while (System.currentTimeMillis() < deadline && controller.activeDialog is SettingDialogState.HonomiLogin) {
            delay(pairing.interval * 1000L)
            viewModel.pollHonomiPairing(pairing)
                .onSuccess { session ->
                    if (session != null) {
                        showToast("${session.userName} とペアリングしました")
                        onCloseDialog()
                        return@LaunchedEffect
                    }
                }
                .onFailure {
                    onHonomiLoginErrorChange(it.message ?: "ペアリングに失敗しました")
                    return@LaunchedEffect
                }
        }
        if (controller.activeDialog is SettingDialogState.HonomiLogin) {
            onHonomiLoginErrorChange("ペアリングコードの有効期限が切れました")
        }
    }

    when (val state = controller.activeDialog) {
        is SettingDialogState.HonomiLogout -> HonomiLogoutDialog(
            onConfirm = {
                viewModel.logoutHonomi()
                showToast("HonomiTV からログアウトしました")
                onCloseDialog()
            },
            onDismiss = { onCloseDialog() },
        )

        is SettingDialogState.Input -> InputDialog(
            state.title,
            state.initialValue,
            { onCloseDialog() },
            { state.onConfirm(it); onCloseDialog() },
        )

        is SettingDialogState.CloudflareAccess -> CloudflareAccessDialog(
            state.clientId,
            state.clientSecret,
            { onCloseDialog() },
            { clientId, clientSecret -> state.onConfirm(clientId, clientSecret) },
        )

        is SettingDialogState.BatchInput -> BatchInputDialog(
            { onCloseDialog() },
            { n, p -> state.onConfirm(n, p); onCloseDialog() },
        )

        is SettingDialogState.Selection -> SelectionDialog(
            state.title,
            state.options,
            state.current,
            { onCloseDialog() },
            { state.onSelect(it); onCloseDialog() },
        )

        is SettingDialogState.MultiSelection -> MultiSelectionDialog(
            state.title,
            state.options,
            state.currentSelections,
            { onCloseDialog() },
            { state.onConfirm(it); onCloseDialog() },
        )

        is SettingDialogState.ConfirmClear -> ConfirmClearDialog(
            state.title,
            state.message,
            if (state.title.contains("警告")) "有効にする" else "削除",
            { state.onConfirm(); onCloseDialog() },
            { onCloseDialog() },
        )

        is SettingDialogState.Licenses -> OpenSourceLicensesScreen(onBack = { onCloseDialog() })

        is SettingDialogState.LinkQr -> LinkQrDialog(
            state.title,
            state.url,
            { onCloseDialog() },
        )

        is SettingDialogState.DeviceCapabilities -> DeviceCapabilitiesScreen(onBack = { onCloseDialog() })

        is SettingDialogState.DefaultHomeGuide -> DefaultHomeGuideDialog(
            environment = homeEnvironment,
            isDefaultHome = isDefaultHome,
            onOpenHomeSettings = {
                if (!LauncherEnvironmentDetector.openHomeSettings(context)) {
                    showToast("ホーム設定を開けませんでした")
                }
                onCloseDialog()
            },
            onDismiss = { onCloseDialog() },
        )

        is SettingDialogState.HonomiLogin -> HonomiLoginDialog(
            honomiLoginInProgress,
            honomiLoginError,
            { if (!honomiLoginInProgress) onCloseDialog() },
            {
                scope.launch {
                    viewModel.createHonomiPairing()
                        .onSuccess { onHonomiPairingChange(it) }
                        .onFailure { onHonomiLoginErrorChange(it.message ?: "ログインに失敗しました") }
                }
            },
            honomiPairing,
            honomiPairing?.verificationUrl,
        )

        is SettingDialogState.ConnectionSetup -> {
            val localIp by viewModel.localIpAddress.collectAsState()
            val setupState by viewModel.connectionSetupState.collectAsState()
            ConnectionSetupDialog(
                title = "接続先の設定",
                serverIp = localIp,
                state = setupState,
                onStart = { viewModel.startConnectionSetup(state.source) },
                onStop = { viewModel.stopGeminiLocalServer() },
                onDismiss = { onCloseDialog() },
                onManualInput = { onCloseDialog() },
            )
        }

        is SettingDialogState.ScreensaverWebSetup -> {
            val localIp by viewModel.localIpAddress.collectAsState()
            val setupState by viewModel.connectionSetupState.collectAsState()
            ScreensaverWebSetupDialog(
                serverIp = localIp,
                state = setupState,
                savedImageCount = screensaverImages.size,
                onStart = { viewModel.startScreensaverSetup() },
                onStop = { viewModel.stopGeminiLocalServer() },
                onDismiss = { onCloseDialog() },
            )
        }

        is SettingDialogState.GeminiSetup -> {
            val localIp by viewModel.localIpAddress.collectAsState()
            GeminiSetupDialog(
                prefs.geminiApiKey,
                localIp,
                { viewModel.startGeminiLocalServer() },
                { viewModel.stopGeminiLocalServer() },
                { onCloseDialog() },
                {
                    viewModel.stopGeminiLocalServer()
                    controller.activeDialog = SettingDialogState.Input(
                        "Gemini API Key",
                        prefs.geminiApiKey,
                    ) { key ->
                        scope.launch {
                            repository.saveString(
                                SettingsRepository.GEMINI_API_KEY,
                                key,
                            )
                        }
                    }
                },
                {
                    viewModel.stopGeminiLocalServer()
                    scope.launch {
                        repository.saveString(
                            SettingsRepository.GEMINI_API_KEY,
                            "",
                        )
                    }
                    onCloseDialog()
                },
            )
        }

        is SettingDialogState.SmbAction -> SelectionDialog(
            title = "${state.target.name} の操作",
            options = listOf("編集する" to "EDIT", "削除する" to "DELETE"),
            current = "",
            onDismiss = { onCloseDialog() },
            onSelect = { action ->
                if (action == "EDIT") {
                    controller.activeDialog = SettingDialogState.SmbSetup(state.target)
                } else if (action == "DELETE") {
                    controller.activeDialog = SettingDialogState.ConfirmClear(
                        "SMBサーバーの削除",
                        "「${state.target.name}」を削除しますか？",
                    ) { viewModel.deleteSmbServer(state.target.id) }
                }
            },
        )

        is SettingDialogState.SmbSetup -> {
            val localIp by viewModel.localIpAddress.collectAsState()
            val setupState by viewModel.connectionSetupState.collectAsState()
            ConnectionSetupDialog(
                title = "接続設定",
                serverIp = localIp,
                state = setupState,
                onStart = { viewModel.startConnectionSetup() },
                onStop = { viewModel.stopGeminiLocalServer() },
                onDismiss = { onCloseDialog() },
                onManualInput = {
                    viewModel.stopGeminiLocalServer()
                    controller.activeDialog =
                        SettingDialogState.SmbManualInput(state.target) { viewModel.saveSmbServer(it) }
                },
            )
        }

        is SettingDialogState.SmbManualInput -> {
            SmbManualInputDialog(
                state.target,
                { onCloseDialog() },
                { viewModel.saveSmbServer(it); onCloseDialog() },
            )
        }

        is SettingDialogState.NoSignalStylePreview -> TifNoSignalStylePreviewOverlay(
            currentStyle = prefs.tifNoSignalStyle,
            currentAccentColor = prefs.tifNoSignalAccentColor,
            screensaverImageUris = screensaverImages,
            screensaverIntervalSeconds = 5,
            screensaverTransition = screensaverTransition,
            onSelect = {
                viewModel.updateTifNoSignalStyle(it.name)
                onCloseDialog()
            },
            onSelectAccentColor = {
                viewModel.updateTifNoSignalAccentColor(it.name)
            },
            onDismiss = { onCloseDialog() },
        )

        else -> {}
    }

    if (controller.showInternalImagePicker) {
        ScreensaverImagePicker(
            onDismiss = { controller.showInternalImagePicker = false; onCloseDialog() },
            onImport = { uris -> viewModel.importScreensaverImages(uris) },
        )
    }

    if (controller.showScreensaverGallery) {
        ScreensaverImageGallery(
            imageUris = screensaverImages,
            onDelete = { removed -> viewModel.deleteScreensaverImages(removed) },
            onDismiss = { controller.showScreensaverGallery = false; onCloseDialog() },
        )
    }

    if (controller.screensaverPreview || controller.screensaverPlayerPreview) {
        val isPlayerPreview = controller.screensaverPlayerPreview
        val upcoming = rememberUpcomingRecordings(
            enabled = screensaverShowUpcoming && !(isPlayerPreview && screensaverPlayerStyle == "PLAYER"),
            visible = true,
        )
        Dialog(
            onDismissRequest = {
                controller.screensaverPreview = false
                controller.screensaverPlayerPreview = false
                onCloseDialog()
            },
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .onKeyEvent { event ->
                        if (event.type == KeyEventType.KeyDown && event.key == Key.Back) {
                            controller.screensaverPreview = false
                            controller.screensaverPlayerPreview = false
                            onCloseDialog()
                            true
                        } else {
                            false
                        }
                    }
                    .clickable {
                        controller.screensaverPreview = false
                        controller.screensaverPlayerPreview = false
                        onCloseDialog()
                    },
            ) {
                if (isPlayerPreview && screensaverPlayerStyle == "PLAYER") {
                    PlayerScreensaverContent(
                        title = "プレビュー",
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black),
                    )
                } else {
                    ScreensaverContent(
                        imageUris = screensaverImages,
                        intervalSeconds = 5,
                        transition = screensaverTransition,
                        upcomingRecordings = upcoming,
                    )
                }
            }
        }
    }
}
