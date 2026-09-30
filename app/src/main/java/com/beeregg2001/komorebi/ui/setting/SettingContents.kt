@file:OptIn(ExperimentalComposeUiApi::class)

package com.beeregg2001.komorebi.ui.setting

import android.os.Build
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.tv.material3.*
import com.beeregg2001.komorebi.BuildConfig
import com.beeregg2001.komorebi.R
import com.beeregg2001.komorebi.common.AppStrings
import com.beeregg2001.komorebi.data.model.StreamQuality
import com.beeregg2001.komorebi.ui.theme.KomorebiTheme
import com.beeregg2001.komorebi.viewmodel.PostRecordingBatch
import com.beeregg2001.komorebi.viewmodel.SmbServer
import java.time.LocalTime
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

@Composable
private fun ValidationErrorText(message: String) {
    Row(
        modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Default.Warning,
            contentDescription = "Error",
            tint = Color(0xFFE53935),
            modifier = Modifier.size(20.dp)
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = Color(0xFFE53935),
            fontWeight = FontWeight.Bold
        )
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun GeneralSettingsContent(
    receiveBetaUpdates: Boolean,
    startupTab: String,
    startupChannelName: String,
    excludePaid: String,
    onToggleBetaUpdates: (Boolean) -> Unit,
    betaUpdateR: FocusRequester,
    startupTabR: FocusRequester,
    startupChannelR: FocusRequester,
    onEditStartupTab: () -> Unit,
    onEditStartupChannel: () -> Unit,
    capabilityR: FocusRequester,
    onCapabilities: () -> Unit,
    exPaidR: FocusRequester,
    onToggleExcludePaid: () -> Unit,
    nhkExclusionLabel: String,
    nhkExclusionR: FocusRequester,
    onEditNHKExclusion: () -> Unit,
    sidebarR: FocusRequester,
    onClick: (FocusRequester) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
        Text(
            AppStrings.SETTINGS_CATEGORY_GENERAL,
            style = MaterialTheme.typography.headlineMedium,
            color = KomorebiTheme.colors.textPrimary,
            fontWeight = FontWeight.Bold
        )

        SettingsSection("アプリ設定") {
            SettingItem(
                title = "ベータ版のアップデートを受け取る",
                value = if (receiveBetaUpdates) "ON" else "OFF",
                icon = Icons.Default.SystemUpdate,
                modifier = Modifier
                    .focusRequester(betaUpdateR)
                    .focusProperties {
                        left = sidebarR
                        up = FocusRequester.Cancel
                        down = startupTabR
                    },
                onClick = { onClick(betaUpdateR); onToggleBetaUpdates(!receiveBetaUpdates) }
            )
        }

        SettingsSection("起動時の動作") {
            SettingItem(
                title = AppStrings.SETTINGS_ITEM_STARTUP_TAB,
                value = startupTab,
                icon = Icons.Default.Launch,
                modifier = Modifier
                    .focusRequester(startupTabR)
                    .focusProperties {
                        left = sidebarR
                        up = betaUpdateR
                        down = startupChannelR
                    },
                onClick = { onClick(startupTabR); onEditStartupTab() }
            )
            SettingItem(
                title = AppStrings.SETTINGS_ITEM_STARTUP_CHANNEL,
                value = startupChannelName,
                icon = Icons.Default.LiveTv,
                modifier = Modifier
                    .focusRequester(startupChannelR)
                    .focusProperties {
                        left = sidebarR
                        up = startupTabR
                        down = capabilityR
                    },
                onClick = { onClick(startupChannelR); onEditStartupChannel() }
            )
        }

        SettingsSection("デバイス") {
            SettingItem(
                title = "テレビ再生能力",
                value = "HEVC / 4K / 8K / HDR / 音声を確認",
                icon = Icons.Default.Memory,
                modifier = Modifier
                    .focusRequester(capabilityR)
                    .focusProperties {
                        left = sidebarR
                        up = startupChannelR
                        down = exPaidR
                    },
                onClick = { onClick(capabilityR); onCapabilities() }
            )
        }

        SettingsSection("視聴") {
            SettingItem(
                AppStrings.SETTINGS_ITEM_EXCLUDE_PAID,
                excludePaid,
                Icons.Default.Lock,
                modifier = Modifier
                    .focusRequester(exPaidR)
                    .focusProperties {
                        left = sidebarR
                        up = capabilityR
                        down = nhkExclusionR
                    },
                onClick = { onClick(exPaidR); onToggleExcludePaid() })
            SettingItem(
                "N〇K除外モード",
                nhkExclusionLabel,
                Icons.Default.Lock,
                modifier = Modifier.focusRequester(nhkExclusionR).focusProperties {
                    left = sidebarR
                    up = exPaidR
                    down = FocusRequester.Cancel
                },
                onClick = { onClick(nhkExclusionR); onEditNHKExclusion() },
            )
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun RecordingSettingsContent(
    defaultRecordListView: String,
    onEditDefaultView: () -> Unit,
    batchList: List<PostRecordingBatch>,
    onAdd: () -> Unit,
    onDelete: (PostRecordingBatch) -> Unit,
    recordViewR: FocusRequester,
    addR: FocusRequester,
    itemRs: List<FocusRequester>,
    sidebarR: FocusRequester,
    onClick: (FocusRequester) -> Unit
) {
    val colors = KomorebiTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
        Text(
            "録画設定",
            style = MaterialTheme.typography.headlineMedium,
            color = colors.textPrimary,
            fontWeight = FontWeight.Bold
        )

        SettingsSection(AppStrings.SETTINGS_SECTION_RECORD_LIST) {
            SettingItem(
                AppStrings.SETTINGS_ITEM_DEFAULT_RECORD_VIEW,
                if (defaultRecordListView == "GRID") AppStrings.SETTINGS_VALUE_VIEW_GRID else AppStrings.SETTINGS_VALUE_VIEW_LIST,
                Icons.Default.GridView,
                modifier = Modifier
                    .focusRequester(recordViewR)
                    .focusProperties {
                        left = sidebarR
                        up = FocusRequester.Cancel
                        down = addR
                    },
                onClick = { onClick(recordViewR); onEditDefaultView() }
            )
        }

        SettingsSection("録画後実行バッチの設定") {
            SettingItem(
                title = "新しいバッチを追加",
                value = "",
                icon = Icons.Default.Add,
                modifier = Modifier
                    .focusRequester(addR)
                    .focusProperties {
                        left = sidebarR
                        up = recordViewR
                        down =
                            if (batchList.isEmpty()) FocusRequester.Cancel else itemRs.firstOrNull()
                                ?: FocusRequester.Cancel
                    },
                onClick = { onClick(addR); onAdd() }
            )

            if (batchList.isEmpty()) {
                Text(
                    "登録されたバッチはありません",
                    modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.textSecondary.copy(0.6f)
                )
            } else {
                batchList.forEachIndexed { index, batch ->
                    val requester = itemRs.getOrNull(index) ?: remember { FocusRequester() }
                    val isLast = index == batchList.lastIndex
                    SettingItem(
                        title = batch.name,
                        value = "削除",
                        icon = Icons.Default.Terminal,
                        modifier = Modifier
                            .focusRequester(requester)
                            .focusProperties {
                                left = sidebarR
                                up = if (index == 0) addR else itemRs[index - 1]
                                down = if (isLast) FocusRequester.Cancel else itemRs[index + 1]
                            },
                        onClick = { onClick(requester); onDelete(batch) }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun ConnectionSettingsContent(
    backendType: String,
    edcbIp: String,
    edcbPort: String,
    edcbHttpPort: String,
    epgStationIp: String,
    epgStationPort: String,
    kIp: String,
    kPort: String,
    mIp: String,
    mPort: String,
    prefSrc: String,
    cfAccessClientId: String,
    cfAccessClientSecret: String,
    edcbPlayMethod: String,
    smbServerList: List<SmbServer>,
    honomiUserName: String?,
    onHonomiAccountClick: () -> Unit,
    onAddSmbServer: () -> Unit,
    onSmbServerClick: (SmbServer) -> Unit,
    addSmbR: FocusRequester,
    smbItemRs: List<FocusRequester>,
    onSelectEdcbPlayMethod: () -> Unit,
    edcbPlayMethodR: FocusRequester,
    onEdit: (String, String) -> Unit,
    onSelectBackend: () -> Unit,
    onSelectSrc: () -> Unit,
    onEditCloudflareAccess: () -> Unit,
    backendTypeR: FocusRequester,
    backendIpR: FocusRequester,
    backendPortR: FocusRequester,
    edcbHttpPortR: FocusRequester,
    prefSrcR: FocusRequester,
    overrideIpR: FocusRequester,
    overridePortR: FocusRequester,
    honomiAccountR: FocusRequester,
    cloudflareAccessR: FocusRequester,
    sidebarR: FocusRequester,
    onClick: (FocusRequester) -> Unit
) {
    val colors = KomorebiTheme.colors

    // ★ 修正: ループによるフォーカスの点滅（奪い合い）を防ぐため、
    // レイアウトの安定（ダイアログの退出アニメーション等）を350msだけ静かに待ち、
    // その直後に「1発だけ」確実なフォーカス要求を行うスマートな方式に変更。
    var previousBackendType by remember { mutableStateOf(backendType) }
    LaunchedEffect(backendType) {
        if (previousBackendType != backendType) {
            previousBackendType = backendType
            delay(350)
            try {
                backendTypeR.requestFocus()
            } catch (e: Exception) {
            }
        }
    }

    var previousPrefSrc by remember { mutableStateOf(prefSrc) }
    LaunchedEffect(prefSrc) {
        if (previousPrefSrc != prefSrc) {
            previousPrefSrc = prefSrc
            delay(350)
            try {
                prefSrcR.requestFocus()
            } catch (e: Exception) {
            }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
        Text(
            AppStrings.SETTINGS_CATEGORY_CONNECTION,
            style = MaterialTheme.typography.headlineMedium,
            color = KomorebiTheme.colors.textPrimary,
            fontWeight = FontWeight.Bold
        )

        SettingsSection("メインシステム設定") {
            val backendLabel = when (backendType) {
                "EDCB" -> "EDCB (EpgTimerSrv)"
                "MIRAKURUN_ONLY" -> "Mirakurun (録画なし)"
                else -> "KonomiTV"
            }

            SettingItem(
                title = "利用するシステム",
                value = backendLabel,
                icon = Icons.Default.Dns,
                modifier = Modifier
                    .focusRequester(backendTypeR)
                    .focusProperties {
                        left = sidebarR
                        up = FocusRequester.Cancel
                        down = backendIpR
                    },
                onClick = { onClick(backendTypeR); onSelectBackend() }
            )

            val currentIp = when (backendType) {
                "EDCB" -> edcbIp
                "MIRAKURUN_ONLY" -> mIp
                else -> kIp
            }
            val currentPort = when (backendType) {
                "EDCB" -> edcbPort
                "MIRAKURUN_ONLY" -> mPort
                else -> kPort
            }
            val ipTitle = when (backendType) {
                "EDCB" -> "EDCB (IPアドレス)"
                "MIRAKURUN_ONLY" -> "Mirakurun (IPアドレス)"
                else -> "KonomiTV (IPアドレス)"
            }
            val portTitle = when (backendType) {
                "EDCB" -> "EDCB (TCPポート)"
                "MIRAKURUN_ONLY" -> "Mirakurun (ポート)"
                else -> "KonomiTV (ポート)"
            }
            val edcbHttpPortTitle = "EDCB (HTTP/HTTPSポート)"

            SettingItem(
                title = ipTitle,
                value = currentIp.ifEmpty { AppStrings.SETTINGS_VALUE_UNSET },
                icon = Icons.Default.Link,
                modifier = Modifier
                    .focusRequester(backendIpR)
                    .focusProperties { left = sidebarR; up = backendTypeR; down = backendPortR },
                onClick = { onClick(backendIpR); onEdit(ipTitle, currentIp) }
            )
            SettingItem(
                title = portTitle,
                value = currentPort,
                icon = Icons.Default.Numbers,
                modifier = Modifier
                    .focusRequester(backendPortR)
                    .focusProperties {
                        left = sidebarR; up = backendIpR;
                        down =
                            if (backendType == "EDCB") edcbHttpPortR else if (backendType == "KONOMITV") honomiAccountR else addSmbR
                    },
                onClick = { onClick(backendPortR); onEdit(portTitle, currentPort) }
            )
            if (backendType == "EDCB") {
                SettingItem(
                    title = edcbHttpPortTitle,
                    value = edcbHttpPort,
                    icon = Icons.Default.Numbers,
                    modifier = Modifier
                        .focusRequester(edcbHttpPortR)
                        .focusProperties {
                            left = sidebarR
                            up = backendPortR
                            down =
                                if (backendType == "EDCB") edcbPlayMethodR else if (backendType != "MIRAKURUN_ONLY") prefSrcR else addSmbR
                        },
                    onClick = { onClick(edcbHttpPortR); onEdit(edcbHttpPortTitle, edcbHttpPort) }
                )
            }

            if (currentIp.isBlank() || currentPort.isBlank()) {
                ValidationErrorText("メインシステムのIPアドレスまたはポート番号が未設定です。\n番組情報の取得や録画機能が正常に動作しません。")
            }
        }

        if (backendType == "KONOMITV") {
            SettingsSection("HonomiTV アカウント") {
                SettingItem(
                    title = if (honomiUserName == null) "ログイン" else "ログアウト",
                    value = honomiUserName ?: "再生履歴を同期",
                    icon = Icons.Default.AccountCircle,
                    modifier = Modifier.focusRequester(honomiAccountR).focusProperties {
                        left = sidebarR
                        up = backendPortR
                        down = prefSrcR
                    },
                    onClick = { onClick(honomiAccountR); onHonomiAccountClick() },
                )
            }
        }

        if (backendType == "EDCB") {
            SettingsSection("EDCB 録画再生設定") {
                SettingItem(
                    title = "録画ファイルの再生方式",
                    value = if (edcbPlayMethod == "DIRECT") "直接アクセス (高速シーク可)" else "API経由 (api/xcode)",
                    icon = Icons.Default.PlayCircleOutline,
                    modifier = Modifier
                        .focusRequester(edcbPlayMethodR)
                        .focusProperties {
                            left = sidebarR
                            up = edcbHttpPortR
                            down = prefSrcR
                        },
                    onClick = { onClick(edcbPlayMethodR); onSelectEdcbPlayMethod() }
                )
            }
        }

        val hasOverride = prefSrc == "MIRAKURUN" || (prefSrc == "EDCB" && backendType != "EDCB")
        if (backendType != "MIRAKURUN_ONLY") {
            SettingsSection("ライブ視聴ソースの優先設定") {
                val srcLabel = when {
                    prefSrc == "MIRAKURUN" -> "Mirakurun を優先"
                    prefSrc == "EDCB" && backendType != "EDCB" -> "EDCB (TCP) を優先"
                    prefSrc == "EDCB" && backendType == "EDCB" -> "EDCB (ダイレクトストリーミング)"
                    else -> "メインシステムに従う（トランスコード）"
                }

                SettingItem(
                    title = "優先ソース",
                    value = srcLabel,
                    icon = Icons.Default.PriorityHigh,
                    modifier = Modifier
                        .focusRequester(prefSrcR)
                        .focusProperties {
                            left = sidebarR
                            up = if (backendType == "EDCB") edcbPlayMethodR else if (backendType == "KONOMITV") honomiAccountR else backendPortR
                            down = if (hasOverride) overrideIpR else cloudflareAccessR
                        },
                    onClick = { onClick(prefSrcR); onSelectSrc() }
                )
            }

            if (prefSrc == "MIRAKURUN") {
                SettingsSection("Mirakurun 接続設定") {
                    SettingItem(
                        title = "IPアドレス (Mirakurun)",
                        value = mIp.ifEmpty { AppStrings.SETTINGS_VALUE_UNSET },
                        icon = Icons.Default.Router,
                        modifier = Modifier
                            .focusRequester(overrideIpR)
                            .focusProperties {
                                left = sidebarR; up = prefSrcR; down = overridePortR
                            },
                        onClick = { onClick(overrideIpR); onEdit("Mirakurun (IPアドレス)", mIp) }
                    )
                    SettingItem(
                        title = "ポート番号 (Mirakurun)",
                        value = mPort,
                        icon = Icons.Default.Numbers,
                        modifier = Modifier
                            .focusRequester(overridePortR)
                            .focusProperties {
                                left = sidebarR; up = overrideIpR; down = cloudflareAccessR
                            },
                        onClick = { onClick(overridePortR); onEdit("Mirakurun (ポート)", mPort) }
                    )

                    if (mIp.isBlank() || mPort.isBlank()) {
                        ValidationErrorText("優先ソース（Mirakurun）のIPアドレスまたはポート番号が未設定です。\nこのままではライブ視聴機能が正常に動作しません。")
                    }
                }
            } else if (prefSrc == "EDCB" && backendType != "EDCB") {
                SettingsSection("EDCB 接続設定") {
                    SettingItem(
                        title = "IPアドレス (EDCB)",
                        value = edcbIp.ifEmpty { AppStrings.SETTINGS_VALUE_UNSET },
                        icon = Icons.Default.Router,
                        modifier = Modifier
                            .focusRequester(overrideIpR)
                            .focusProperties {
                                left = sidebarR; up = prefSrcR; down = overridePortR
                            },
                        onClick = { onClick(overrideIpR); onEdit("EDCB (IPアドレス)", edcbIp) }
                    )
                    SettingItem(
                        title = "ポート番号 (EDCB)",
                        value = edcbPort,
                        icon = Icons.Default.Numbers,
                        modifier = Modifier
                            .focusRequester(overridePortR)
                            .focusProperties {
                                left = sidebarR; up = overrideIpR; down = cloudflareAccessR
                            },
                        onClick = { onClick(overridePortR); onEdit("EDCB (ポート)", edcbPort) }
                    )

                    if (edcbIp.isBlank() || edcbPort.isBlank()) {
                        ValidationErrorText("優先ソース（EDCB）のIPアドレスまたはポート番号が未設定です。\nこのままではライブ視聴機能が正常に動作しません。")
                    }
                }
            }
        }

        SettingsSection("Cloudflare Access") {
            SettingItem(
                title = "サービス トークン",
                value = if (cfAccessClientId.isNotBlank() && cfAccessClientSecret.isNotBlank()) "設定済み" else "未設定",
                icon = Icons.Default.Cloud,
                modifier = Modifier.focusRequester(cloudflareAccessR).focusProperties {
                    left = sidebarR
                    up = if (hasOverride) overridePortR else prefSrcR
                    down = addSmbR
                },
                onClick = { onClick(cloudflareAccessR); onEditCloudflareAccess() },
            )
            Text(
                "HTTPS の設定済みバックエンドだけにサービス トークンを送信します。",
                modifier = Modifier.padding(horizontal = 24.dp),
                color = colors.textSecondary,
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        SettingsSection("ファイルライブラリ (SMB) 接続設定") {
            SettingItem(
                title = "新しいSMBサーバーを追加",
                value = "",
                icon = Icons.Default.Add,
                modifier = Modifier
                    .focusRequester(addSmbR)
                    .focusProperties {
                        left = sidebarR
                        up = cloudflareAccessR
                        down =
                            if (smbServerList.isEmpty()) FocusRequester.Cancel else smbItemRs.firstOrNull()
                                ?: FocusRequester.Cancel
                    },
                onClick = { onClick(addSmbR); onAddSmbServer() }
            )

            if (smbServerList.isEmpty()) {
                Text(
                    "登録されたSMBサーバーはありません",
                    modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.textSecondary.copy(0.6f)
                )
            } else {
                smbServerList.forEachIndexed { index, server ->
                    val requester = smbItemRs.getOrNull(index) ?: remember { FocusRequester() }
                    val isLast = index == smbServerList.lastIndex
                    SettingItem(
                        title = server.name,
                        value = server.ip,
                        icon = Icons.Default.Storage, // ドライブ風のアイコン
                        modifier = Modifier
                            .focusRequester(requester)
                            .focusProperties {
                                left = sidebarR
                                up = if (index == 0) addSmbR else smbItemRs[index - 1]
                                down = if (isLast) FocusRequester.Cancel else smbItemRs[index + 1]
                            },
                        onClick = { onClick(requester); onSmbServerClick(server) }
                    )
                }
            }
        }
    }
}

@Composable
fun PlaybackSettingsContent(
    liveQ: String,
    videoQ: String,
    liveSub: String,
    videoSub: String,
    subtitleFont: String,
    layerOrder: String,
    audioMode: String,
    uiMode: String,
    autoCmSkip: String,
    preferOriginalMpegTs: String,
    availableQualities: List<StreamQuality>,
    liveR: FocusRequester,
    videoR: FocusRequester,
    liveSubR: FocusRequester,
    videoSubR: FocusRequester,
    subtitleFontR: FocusRequester,
    audioR: FocusRequester,
    layerR: FocusRequester,
    uiModeR: FocusRequester,
    autoCmSkipR: FocusRequester,
    preferOriginalR: FocusRequester,
    sidebarR: FocusRequester,
    onL: () -> Unit,
    onV: () -> Unit,
    onLiveSub: () -> Unit,
    onVideoSub: () -> Unit,
    onSubtitleFont: () -> Unit,
    onAudioMode: () -> Unit,
    onLayer: () -> Unit,
    onUiMode: () -> Unit,
    onAutoCmSkip: () -> Unit,
    onPreferOriginalMpegTs: () -> Unit,
    onClick: (FocusRequester) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
        Text(
            AppStrings.SETTINGS_CATEGORY_PLAYBACK,
            style = MaterialTheme.typography.headlineMedium,
            color = KomorebiTheme.colors.textPrimary,
            fontWeight = FontWeight.Bold
        )
        SettingsSection(AppStrings.SETTINGS_SECTION_QUALITY) {
            SettingItem(
                AppStrings.SETTINGS_ITEM_LIVE_QUALITY,
                availableQualities.find { it.value == liveQ }?.label
                    ?: availableQualities.firstOrNull()?.label ?: "Unknown",
                Icons.Default.LiveTv,
                modifier = Modifier
                    .focusRequester(liveR)
                    .focusProperties {
                        left = sidebarR
                        up = FocusRequester.Cancel
                        down = videoR
                    },
                onClick = { onClick(liveR); onL() })
            SettingItem(
                AppStrings.SETTINGS_ITEM_VIDEO_QUALITY,
                availableQualities.find { it.value == videoQ }?.label
                    ?: availableQualities.firstOrNull()?.label ?: "Unknown",
                Icons.Default.VideoFile,
                modifier = Modifier
                    .focusRequester(videoR)
                    .focusProperties {
                        left = sidebarR
                        up = liveR
                        down = preferOriginalR
                    },
                onClick = { onClick(videoR); onV() })
            SettingItem(
                title = "MPEG-TS オリジナル再生",
                value = if (preferOriginalMpegTs == "ON") "有効" else "無効",
                icon = Icons.Default.HighQuality,
                modifier = Modifier
                    .focusRequester(preferOriginalR)
                    .focusProperties {
                        left = sidebarR
                        up = videoR
                        down = liveSubR
                    },
                onClick = {
                    onClick(preferOriginalR)
                    onPreferOriginalMpegTs()
                }
            )
        }
        SettingsSection(AppStrings.SETTINGS_SECTION_SUBTITLE_AUDIO) {
            SettingItem(
                AppStrings.SETTINGS_ITEM_LIVE_SUBTITLE_DEFAULT,
                liveSub,
                Icons.Default.Subtitles,
                modifier = Modifier
                    .focusRequester(liveSubR)
                    .focusProperties {
                        left = sidebarR
                        up = preferOriginalR
                        down = videoSubR
                    },
                onClick = { onClick(liveSubR); onLiveSub() })
            SettingItem(
                AppStrings.SETTINGS_ITEM_VIDEO_SUBTITLE_DEFAULT,
                videoSub,
                Icons.Default.ClosedCaption,
                modifier = Modifier
                    .focusRequester(videoSubR)
                    .focusProperties {
                        left = sidebarR
                        up = liveSubR
                        down = subtitleFontR
                    },
                onClick = { onClick(videoSubR); onVideoSub() })
            SettingItem(
                AppStrings.SETTINGS_ITEM_SUBTITLE_FONT,
                if (subtitleFont == "arib") AppStrings.SETTINGS_VALUE_FONT_ARIB else AppStrings.SETTINGS_VALUE_FONT_DEFAULT,
                Icons.Default.TextFields,
                modifier = Modifier
                    .focusRequester(subtitleFontR)
                    .focusProperties {
                        left = sidebarR
                        up = videoSubR
                        down = audioR
                    },
                onClick = { onClick(subtitleFontR); onSubtitleFont() })
            SettingItem(
                AppStrings.SETTINGS_ITEM_AUDIO_OUTPUT_MODE,
                if (audioMode == "DOWNMIX") AppStrings.SETTINGS_VALUE_AUDIO_DOWNMIX else AppStrings.SETTINGS_VALUE_AUDIO_PASSTHROUGH,
                Icons.Default.AudioFile,
                modifier = Modifier
                    .focusRequester(audioR)
                    .focusProperties {
                        left = sidebarR
                        up = subtitleFontR
                        down = layerR
                    },
                onClick = { onClick(audioR); onAudioMode() })
        }
        SettingsSection(AppStrings.SETTINGS_SECTION_COMMENT_LAYER) {
            SettingItem(
                AppStrings.SETTINGS_ITEM_SUBTITLE_COMMENT_LAYER,
                if (layerOrder == "CommentOnTop") AppStrings.DIALOG_LAYER_COMMENT_TOP else AppStrings.DIALOG_LAYER_SUBTITLE_TOP,
                Icons.Default.Layers,
                modifier = Modifier
                    .focusRequester(layerR)
                    .focusProperties {
                        left = sidebarR
                        up = audioR
                        down = uiModeR
                    },
                onClick = { onClick(layerR); onLayer() })
        }

        SettingsSection("プレイヤー操作・UI設定") {
            SettingItem(
                title = "プレイヤーUIモード",
                value = if (uiMode == "CLASSIC") "クラシック (D-Pad完結)" else "モダン (オンスクリーン操作)",
                icon = Icons.Default.SettingsRemote,
                modifier = Modifier
                    .focusRequester(uiModeR)
                    .focusProperties {
                        left = sidebarR
                        up = layerR
                        down = autoCmSkipR
                    },
                onClick = { onClick(uiModeR); onUiMode() }
            )

            SettingItem(
                title = "CMスキップ",
                value = when (autoCmSkip) {
                    "MANUAL" -> "手動（決定ボタン）"
                    "AUTO" -> "自動"
                    else -> "オフ"
                },
                icon = Icons.Default.FastForward,
                modifier = Modifier
                    .focusRequester(autoCmSkipR)
                    .focusProperties {
                        left = sidebarR
                        up = uiModeR
                        down = FocusRequester.Cancel
                    },
                onClick = { onClick(autoCmSkipR); onAutoCmSkip() }
            )
        }
    }
}

@Composable
fun EpgSettingsContent(
    pref: SettingPreferences,
    onEditColumn: () -> Unit,
    onEditHour: () -> Unit,
    onEditFontSize: () -> Unit,
    colR: FocusRequester,
    hourR: FocusRequester,
    fontR: FocusRequester,
    sidebarR: FocusRequester,
    onClick: (FocusRequester) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
        Text(
            "番組表設定",
            style = MaterialTheme.typography.headlineMedium,
            color = KomorebiTheme.colors.textPrimary,
            fontWeight = FontWeight.Bold
        )
        SettingsSection("番組表表示設定") {
            SettingItem(
                "表示チャンネル数 (横軸)",
                "${pref.epgColumnCount} チャンネル",
                Icons.Default.ViewColumn,
                modifier = Modifier
                    .focusRequester(colR)
                    .focusProperties {
                        left = sidebarR; down = hourR; up = FocusRequester.Cancel
                    },
                onClick = { onClick(colR); onEditColumn() }
            )
            SettingItem(
                "表示時間数 (縦幅)",
                "${pref.epgVisibleHours} 時間",
                Icons.Default.Height,
                modifier = Modifier
                    .focusRequester(hourR)
                    .focusProperties {
                        left = sidebarR; up = colR; down = fontR
                    },
                onClick = { onClick(hourR); onEditHour() }
            )
            SettingItem(
                "文字サイズ",
                "${(pref.epgFontSizeScale.toFloat() * 100).toInt()}%",
                Icons.Default.FormatSize,
                modifier = Modifier
                    .focusRequester(fontR)
                    .focusProperties {
                        left = sidebarR; up = hourR; down = FocusRequester.Cancel
                    },
                onClick = { onClick(fontR); onEditFontSize() }
            )
        }
    }
}


@Composable
fun HomeDisplaySettingsContent(
    genre: String,
    pickupTime: String,
    genreR: FocusRequester,
    timeR: FocusRequester,
    sidebarR: FocusRequester,
    onG: () -> Unit,
    onTime: () -> Unit,
    onClick: (FocusRequester) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
        Text(
            AppStrings.SETTINGS_CATEGORY_HOME,
            style = MaterialTheme.typography.headlineMedium,
            color = KomorebiTheme.colors.textPrimary,
            fontWeight = FontWeight.Bold
        )
        SettingsSection(AppStrings.SETTINGS_SECTION_HOME_PICKUP) {
            SettingItem(
                AppStrings.SETTINGS_ITEM_PICKUP_GENRE,
                genre,
                Icons.Default.AutoAwesome,
                modifier = Modifier
                    .focusRequester(genreR)
                    .focusProperties {
                        left = sidebarR
                        up = FocusRequester.Cancel
                        down = timeR
                    },
                onClick = { onClick(genreR); onG() })
            SettingItem(
                AppStrings.SETTINGS_ITEM_PICKUP_TIME,
                pickupTime,
                Icons.Default.Schedule,
                modifier = Modifier
                    .focusRequester(timeR)
                    .focusProperties {
                        left = sidebarR
                        up = genreR
                        down = FocusRequester.Cancel
                    },
                onClick = { onClick(timeR); onTime() })
        }
    }
}

@Composable
fun LauncherSettingsContent(
    showAppsTab: Boolean,
    appColumns: String,
    showAppLabels: Boolean,
    environment: HomeEnvironment,
    isDefaultHome: Boolean,
    itemRs: List<FocusRequester>,
    sidebarR: FocusRequester,
    onToggleAppsTab: () -> Unit,
    onEditColumns: () -> Unit,
    onToggleAppLabels: () -> Unit,
    onOpenDefaultHomeGuide: () -> Unit,
    onClick: (FocusRequester) -> Unit
) {
    val environmentLabel = when (environment) {
        HomeEnvironment.ANDROID_TV -> "Android TV"
        HomeEnvironment.GOOGLE_TV -> "Google TV"
        HomeEnvironment.UNKNOWN -> "不明な端末"
    }

    Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
        Text(
            AppStrings.SETTINGS_CATEGORY_LAUNCHER,
            style = MaterialTheme.typography.headlineMedium,
            color = KomorebiTheme.colors.textPrimary,
            fontWeight = FontWeight.Bold
        )

        SettingsSection(AppStrings.SETTINGS_SECTION_LAUNCHER_TABS) {
            SettingItem(
                title = AppStrings.SETTINGS_ITEM_SHOW_APPS_TAB,
                value = if (showAppsTab) "ON" else "OFF",
                icon = Icons.Default.Apps,
                modifier = Modifier
                    .focusRequester(itemRs[0])
                    .focusProperties {
                        left = sidebarR
                        up = FocusRequester.Cancel
                        down = itemRs[1]
                    },
                onClick = { onClick(itemRs[0]); onToggleAppsTab() }
            )
        }

        SettingsSection(AppStrings.SETTINGS_SECTION_LAUNCHER_APPS) {
            SettingItem(
                title = AppStrings.SETTINGS_ITEM_LAUNCHER_APP_COLUMNS,
                value = if (appColumns == "AUTO") AppStrings.SETTINGS_VALUE_LAUNCHER_COLUMNS_AUTO else "${appColumns}列",
                icon = Icons.Default.GridView,
                modifier = Modifier
                    .focusRequester(itemRs[1])
                    .focusProperties {
                        left = sidebarR
                        up = itemRs[0]
                        down = itemRs[2]
                    },
                onClick = { onClick(itemRs[1]); onEditColumns() }
            )
            SettingItem(
                title = AppStrings.SETTINGS_ITEM_LAUNCHER_APP_LABELS,
                value = if (showAppLabels) "ON" else "OFF",
                icon = Icons.Default.TextFields,
                modifier = Modifier
                    .focusRequester(itemRs[2])
                    .focusProperties {
                        left = sidebarR
                        up = itemRs[1]
                        down = itemRs[3]
                    },
                onClick = { onClick(itemRs[2]); onToggleAppLabels() }
            )
        }

        SettingsSection(AppStrings.SETTINGS_SECTION_LAUNCHER_DEFAULT_HOME) {
            SettingItem(
                title = AppStrings.SETTINGS_ITEM_DEFAULT_HOME,
                value = "$environmentLabel / " + (if (isDefaultHome) AppStrings.SETTINGS_VALUE_DEFAULT_HOME_ON else AppStrings.SETTINGS_VALUE_DEFAULT_HOME_OFF),
                icon = Icons.Default.Home,
                modifier = Modifier
                    .focusRequester(itemRs[3])
                    .focusProperties {
                        left = sidebarR
                        up = itemRs[2]
                        down = FocusRequester.Cancel
                    },
                onClick = { onClick(itemRs[3]); onOpenDefaultHomeGuide() }
            )
        }
    }
}

@RequiresApi(Build.VERSION_CODES.O)
@Composable
fun DisplaySettingsContent(
    isDarkMode: Boolean,
    themeSeason: String,
    preferences: SettingPreferences,
    modeR: FocusRequester,
    colorR: FocusRequester,
    timeFormatR: FocusRequester,
    hideSubChannelsR: FocusRequester,
    sidebarR: FocusRequester,
    onMode: () -> Unit,
    onColor: () -> Unit,
    onEditTimeFormat: () -> Unit,
    onToggleHideSubChannels: () -> Unit,
    onClick: (FocusRequester) -> Unit
) {
    val isTimeLinked = themeSeason == "KOMOREBI" || themeSeason == "KYLE"
    val baseThemeLabel =
        if (isTimeLinked) "時間連動テーマ" else if (!isDarkMode) AppStrings.SETTINGS_VALUE_THEME_DARK else AppStrings.SETTINGS_VALUE_THEME_LIGHT

    var currentTime by remember { mutableStateOf(LocalTime.now()) }
    LaunchedEffect(Unit) {
        while (isActive) {
            delay(60000)
            currentTime = LocalTime.now()
        }
    }

    val hour = currentTime.hour
    val timeZoneName = when {
        hour in 5..8 -> "MORNING"
        hour in 9..15 -> "DAY"
        hour in 16..18 -> "EVENING"
        else -> "NIGHT"
    }

    val colorSettingTitle =
        if (isTimeLinked) "時間連動セット" else AppStrings.SETTINGS_ITEM_THEME_COLOR
    val detailThemeLabel = if (isTimeLinked) {
        when (themeSeason) {
            "KOMOREBI" -> when (timeZoneName) {
                "MORNING" -> "朝焼け"
                "DAY" -> "木漏れ日"
                "EVENING" -> "夕焼け"
                else -> "月光"
            }

            "KYLE" -> when (timeZoneName) {
                "MORNING" -> "朝凪のカイル"
                "DAY" -> "海辺のカイル"
                "EVENING" -> "夕凪のカイル"
                else -> "深海の夜のカイル"
            }

            else -> themeSeason
        }
    } else {
        when (themeSeason) {
            "SPRING" -> AppStrings.SETTINGS_VALUE_SEASON_SPRING
            "SUMMER" -> AppStrings.SETTINGS_VALUE_SEASON_SUMMER
            "AUTUMN" -> AppStrings.SETTINGS_VALUE_SEASON_AUTUMN
            "WINTER" -> AppStrings.SETTINGS_VALUE_SEASON_WINTER
            "BLUE" -> AppStrings.SETTINGS_VALUE_SEASON_BLUE
            "KOMOREBI_DAY" -> "木漏れ日"
            "KOMOREBI_NIGHT" -> "月光"
            "KYLE_DAY" -> "海辺のカイル"
            "KYLE_NIGHT" -> "深海のカイル"
            else -> AppStrings.SETTINGS_VALUE_SEASON_DEFAULT
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
        Text(
            AppStrings.SETTINGS_CATEGORY_DISPLAY,
            style = MaterialTheme.typography.headlineMedium,
            color = KomorebiTheme.colors.textPrimary,
            fontWeight = FontWeight.Bold
        )
        SettingsSection(AppStrings.SETTINGS_SECTION_THEME) {
            SettingItem(
                AppStrings.SETTINGS_ITEM_BASE_THEME,
                baseThemeLabel,
                Icons.Default.Brightness4,
                modifier = Modifier
                    .focusRequester(modeR)
                    .focusProperties {
                        left = sidebarR
                        up = FocusRequester.Cancel
                        down = colorR
                    },
                onClick = { onClick(modeR); onMode() })

            SettingItem(
                colorSettingTitle,
                detailThemeLabel,
                Icons.Default.ColorLens,
                modifier = Modifier
                    .focusRequester(colorR)
                    .focusProperties {
                        left = sidebarR
                        up = modeR
                        down = timeFormatR
                    },
                onClick = { onClick(colorR); onColor() })
        }
        SettingsSection(AppStrings.SETTINGS_SECTION_UI_CUSTOM) {
            SettingItem(
                "時刻の表示形式",
                if (preferences.timeFormat == "12H") "12時間表記 (AM/PM)" else "24時間表記",
                Icons.Default.Schedule,
                modifier = Modifier
                    .focusRequester(timeFormatR)
                    .focusProperties {
                        left = sidebarR
                        up = colorR
                        down = hideSubChannelsR
                    },
                onClick = { onClick(timeFormatR); onEditTimeFormat() })

            SettingItem(
                title = "サブチャンネルを非表示にする",
                value = if (preferences.hideSubChannels) "ON" else "OFF",
                icon = Icons.Default.FilterListOff,
                modifier = Modifier
                    .focusRequester(hideSubChannelsR)
                    .focusProperties {
                        left = sidebarR
                        up = timeFormatR
                        down = FocusRequester.Cancel
                    },
                onClick = { onClick(hideSubChannelsR); onToggleHideSubChannels() }
            )
        }
    }
}

@Composable
fun CommentSettingsContent(
    def: String,
    speed: String,
    size: String,
    opacity: String,
    max: String,
    onEdit: (String, String) -> Unit,
    onT: () -> Unit,
    defR: FocusRequester,
    spR: FocusRequester,
    szR: FocusRequester,
    opR: FocusRequester,
    mxR: FocusRequester,
    sidebarR: FocusRequester,
    onClick: (FocusRequester) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
        Text(
            AppStrings.SETTINGS_CATEGORY_COMMENT,
            style = MaterialTheme.typography.headlineMedium,
            color = KomorebiTheme.colors.textPrimary,
            fontWeight = FontWeight.Bold
        )
        SettingsSection(AppStrings.SETTINGS_SECTION_COMMENT_DISPLAY) {
            SettingItem(
                AppStrings.SETTINGS_ITEM_DEFAULT_DISPLAY,
                def,
                Icons.Default.Visibility,
                modifier = Modifier
                    .focusRequester(defR)
                    .focusProperties {
                        left = sidebarR
                        up = FocusRequester.Cancel
                        down = spR
                    },
                onClick = { onClick(defR); onT() })
            SettingItem(
                AppStrings.SETTINGS_ITEM_COMMENT_SPEED,
                "${speed}x",
                Icons.Default.Speed,
                modifier = Modifier
                    .focusRequester(spR)
                    .focusProperties {
                        left = sidebarR
                        up = defR
                        down = szR
                    },
                onClick = {
                    onClick(spR); onEdit(
                    AppStrings.SETTINGS_INPUT_COMMENT_SPEED,
                    speed
                )
                })
            SettingItem(
                AppStrings.SETTINGS_ITEM_COMMENT_SIZE,
                "${size}x",
                Icons.Default.TextFormat,
                modifier = Modifier
                    .focusRequester(szR)
                    .focusProperties {
                        left = sidebarR
                        up = spR
                        down = opR
                    },
                onClick = {
                    onClick(szR); onEdit(
                    AppStrings.SETTINGS_INPUT_COMMENT_SIZE,
                    size
                )
                })
            SettingItem(
                AppStrings.SETTINGS_ITEM_COMMENT_OPACITY,
                opacity,
                Icons.Default.Opacity,
                modifier = Modifier
                    .focusRequester(opR)
                    .focusProperties {
                        left = sidebarR
                        up = szR
                        down = mxR
                    },
                onClick = {
                    onClick(opR); onEdit(
                    AppStrings.SETTINGS_INPUT_COMMENT_OPACITY,
                    opacity
                )
                })
            SettingItem(
                AppStrings.SETTINGS_ITEM_COMMENT_MAX_LINES,
                max,
                Icons.Default.VerticalAlignTop,
                modifier = Modifier
                    .focusRequester(mxR)
                    .focusProperties {
                        left = sidebarR
                        up = opR
                        down = FocusRequester.Cancel
                    },
                onClick = {
                    onClick(mxR); onEdit(
                    AppStrings.SETTINGS_INPUT_COMMENT_MAX_LINES,
                    max
                )
                })
        }
    }
}

@Composable
fun LabSettingsContent(
    mirakurunDual: String,
    dualR: FocusRequester,
    sidebarR: FocusRequester,
    onToggleMirakurunDual: () -> Unit,
    onClick: (FocusRequester) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
        Text(
            AppStrings.SETTINGS_CATEGORY_LAB,
            style = MaterialTheme.typography.headlineMedium,
            color = KomorebiTheme.colors.textPrimary,
            fontWeight = FontWeight.Bold
        )

        SettingsSection("プレイヤー (実験的)") {
            SettingItem(
                title = "Mirakurunソースの2画面同時再生・PiPモードを許可",
                value = mirakurunDual,
                icon = Icons.Default.VerticalSplit,
                modifier = Modifier
                    .focusRequester(dualR)
                    .focusProperties {
                        left = sidebarR
                        up = FocusRequester.Cancel
                        down = FocusRequester.Cancel
                    },
                onClick = { onClick(dualR); onToggleMirakurunDual() }
            )
        }

    }
}

@Composable
fun CacheManagementContent(
    onClearAll: () -> Unit,
    onClearChannelLogo: () -> Unit,
    onClearThumbnail: () -> Unit,
    onClearEpg: () -> Unit,
    onClearRecording: () -> Unit,
    onClearChannelHistory: () -> Unit,
    onClearWatchHistory: () -> Unit,
    allR: FocusRequester,
    logoR: FocusRequester,
    thumbR: FocusRequester,
    epgR: FocusRequester,
    recordingR: FocusRequester,
    channelHistoryR: FocusRequester,
    watchHistoryR: FocusRequester,
    sidebarR: FocusRequester,
    onClick: (FocusRequester) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
        Text(
            AppStrings.SETTINGS_CATEGORY_CACHE,
            style = MaterialTheme.typography.headlineMedium,
            color = KomorebiTheme.colors.textPrimary,
            fontWeight = FontWeight.Bold
        )

        SettingsSection("一括操作") {
            SettingItem(
                title = "すべてのキャッシュを削除",
                value = "",
                icon = Icons.Default.DeleteSweep,
                modifier = Modifier
                    .focusRequester(allR)
                    .focusProperties {
                        left = sidebarR
                        up = FocusRequester.Cancel
                        down = logoR
                    },
                onClick = { onClick(allR); onClearAll() }
            )
        }

        SettingsSection("個別に削除") {
            SettingItem(
                title = "局ロゴキャッシュを削除",
                value = "",
                icon = Icons.Default.LiveTv,
                modifier = Modifier
                    .focusRequester(logoR)
                    .focusProperties {
                        left = sidebarR
                        up = allR
                        down = thumbR
                    },
                onClick = { onClick(logoR); onClearChannelLogo() }
            )
            SettingItem(
                title = "サムネイルキャッシュを削除",
                value = "",
                icon = Icons.Default.Image,
                modifier = Modifier
                    .focusRequester(thumbR)
                    .focusProperties {
                        left = sidebarR
                        up = logoR
                        down = epgR
                    },
                onClick = { onClick(thumbR); onClearThumbnail() }
            )
            SettingItem(
                title = "番組表キャッシュを削除",
                value = "",
                icon = Icons.Default.GridOn,
                modifier = Modifier
                    .focusRequester(epgR)
                    .focusProperties {
                        left = sidebarR
                        up = thumbR
                        down = recordingR
                    },
                onClick = { onClick(epgR); onClearEpg() }
            )
            SettingItem(
                title = "録画リストキャッシュを削除",
                value = "",
                icon = Icons.Default.VideoLibrary,
                modifier = Modifier
                    .focusRequester(recordingR)
                    .focusProperties {
                        left = sidebarR
                        up = epgR
                        down = channelHistoryR
                    },
                onClick = { onClick(recordingR); onClearRecording() }
            )
        }

        SettingsSection(AppStrings.SETTINGS_SECTION_HISTORY) {
            SettingItem(
                AppStrings.SETTINGS_ITEM_CLEAR_CHANNEL_HISTORY,
                "",
                Icons.Default.History,
                modifier = Modifier
                    .focusRequester(channelHistoryR)
                    .focusProperties {
                        left = sidebarR
                        up = recordingR
                        down = watchHistoryR
                    },
                onClick = { onClick(channelHistoryR); onClearChannelHistory() })
            SettingItem(
                AppStrings.SETTINGS_ITEM_CLEAR_WATCH_HISTORY,
                "",
                Icons.Default.DeleteSweep,
                modifier = Modifier
                    .focusRequester(watchHistoryR)
                    .focusProperties {
                        left = sidebarR
                        up = channelHistoryR
                        down = FocusRequester.Cancel
                    },
                onClick = { onClick(watchHistoryR); onClearWatchHistory() })
        }
    }
}

@Composable
fun AppInfoContent(
    onShowProject: () -> Unit,
    onShowLicenses: () -> Unit,
    versionR: FocusRequester,
    projectR: FocusRequester,
    licR: FocusRequester,
    sidebarR: FocusRequester,
    onClick: (FocusRequester) -> Unit
) {
    val colors = KomorebiTheme.colors
    val context = LocalContext.current
    var versionPresses by remember { mutableIntStateOf(0) }
    var onFire by remember { mutableStateOf(false) }

    val flamePulse by rememberInfiniteTransition(label = "hono").animateFloat(
        initialValue = 1f,
        targetValue = 1.18f,
        animationSpec = infiniteRepeatable(
            animation = tween(620, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "honoPulse"
    )

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier.size(176.dp),
            contentAlignment = Alignment.Center
        ) {
            if (onFire) {
                Icon(
                    imageVector = Icons.Default.LocalFireDepartment,
                    contentDescription = null,
                    tint = Color(0xFFFF7A00),
                    modifier = Modifier
                        .size(128.dp)
                        .graphicsLayer {
                            scaleX = flamePulse
                            scaleY = flamePulse
                        }
                )
            } else {
                Image(
                    painter = painterResource(R.drawable.honorebi_mark),
                    contentDescription = "Honorebi",
                    modifier = Modifier.size(112.dp)
                )
            }
        }

        Text(
            "Honorebi",
            style = MaterialTheme.typography.displayMedium,
            color = KomorebiTheme.colors.textPrimary,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(20.dp))

        SettingItem(
            title = "Version",
            value = BuildConfig.VERSION_NAME,
            icon = Icons.Default.Info,
            modifier = Modifier
                .width(420.dp)
                .focusRequester(versionR)
                .focusProperties {
                    left = sidebarR
                    up = FocusRequester.Cancel
                    down = projectR
                },
            onClick = {
                onClick(versionR)
                if (!onFire) {
                    versionPresses += 1
                    when (versionPresses) {
                        4 -> Toast.makeText(context, "🔥🔥🔥🔥", Toast.LENGTH_SHORT).show()
                        5 -> onFire = true
                    }
                }
            }
        )

        Spacer(Modifier.height(24.dp))

        SettingItem(
            AppStrings.SETTINGS_ITEM_PROJECT_GITHUB,
            "GitHub",
            Icons.Default.Code,
            modifier = Modifier
                .width(420.dp)
                .focusRequester(projectR)
                .focusProperties {
                    left = sidebarR
                    up = versionR
                    down = licR
                },
            onClick = { onClick(projectR); onShowProject() })

        Spacer(Modifier.height(12.dp))

        SettingItem(
            AppStrings.SETTINGS_ITEM_OSS_LICENSES,
            "",
            Icons.Default.Info,
            modifier = Modifier
                .width(420.dp)
                .focusRequester(licR)
                .focusProperties {
                    left = sidebarR
                    up = projectR
                    down = FocusRequester.Cancel
                },
            onClick = { onClick(licR); onShowLicenses() })
    }
}
