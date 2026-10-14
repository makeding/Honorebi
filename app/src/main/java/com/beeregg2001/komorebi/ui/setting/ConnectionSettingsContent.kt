@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.beeregg2001.komorebi.ui.setting

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.beeregg2001.komorebi.common.AppStrings
import com.beeregg2001.komorebi.common.safeRequestFocus
import com.beeregg2001.komorebi.data.ConnectionSource
import com.beeregg2001.komorebi.data.ConnectionSourcePolicy
import com.beeregg2001.komorebi.ui.theme.KomorebiTheme
import com.beeregg2001.komorebi.viewmodel.SmbServer

@Composable
fun ConnectionSettingsContent(
    prefs: SettingPreferences,
    honomiUserName: String?,
    onHonomiAccountClick: () -> Unit,
    onAddSmbServer: () -> Unit,
    onSmbServerClick: (SmbServer) -> Unit,
    smbItemRs: List<FocusRequester>,
    onSelectEdcbPlayMethod: () -> Unit,
    onEdit: (String, String) -> Unit,
    onWebSetup: (ConnectionSource?) -> Unit,
    onSelectBackend: (List<ConnectionSource>) -> Unit,
    onSelectSrc: () -> Unit,
    onEditCloudflareAccess: () -> Unit,
    itemRs: List<FocusRequester>,
    sidebarR: FocusRequester,
    scrollState: ScrollState,
    isDialogOpen: Boolean,
    onClick: (FocusRequester) -> Unit,
) {
    var source by remember { mutableStateOf<ConnectionSource?>(null) }
    var origin by remember { mutableIntStateOf(0) }
    var navigating by remember { mutableStateOf(false) }
    val configured = ConnectionSourcePolicy.configured(
        prefs.konomiIp, prefs.konomiPort, prefs.edcbIp, prefs.edcbPort,
        prefs.mirakurunIp, prefs.mirakurunPort,
    )
    val canSelectMain = configured.size > 1
    val colors = KomorebiTheme.colors

    fun returnToSources() { source = null; navigating = true }
    BackHandler(enabled = source != null && !isDialogOpen) { returnToSources() }
    LaunchedEffect(source) {
        if (navigating) {
            scrollState.scrollTo(0)
            withFrameNanos { }
            // Root and detail lists share requesters so sidebar entry always remains attached.
            itemRs[if (source == null) origin else 1].safeRequestFocus("ConnectionSource_Navigate")
            navigating = false
        }
    }

    @Composable
    fun row(index: Int, title: String, value: String, icon: ImageVector,
            up: FocusRequester, down: FocusRequester, enabled: Boolean = true, action: () -> Unit) {
        SettingItem(title, value, icon, enabled = enabled,
            modifier = Modifier.focusRequester(itemRs[index]).focusProperties {
                left = sidebarR; this.up = up; this.down = down
            }, onClick = { onClick(itemRs[index]); action() })
    }

    Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
        Text(source?.let { "${it.label} 設定" } ?: AppStrings.SETTINGS_CATEGORY_CONNECTION,
            style = MaterialTheme.typography.headlineMedium, color = colors.textPrimary,
            fontWeight = FontWeight.Bold)
        val detail = source
        if (detail != null) {
            row(0, "接続設定に戻る", "", Icons.Default.ArrowBack,
                FocusRequester.Cancel, itemRs[1]) { returnToSources() }
            row(1, "スマホで設定", "", Icons.Default.QrCode,
                itemRs[0], itemRs[2]) { onWebSetup(detail) }
            SettingsSection("接続先") {
                val host = when (detail) {
                    ConnectionSource.KONOMITV -> prefs.konomiIp.takeUnless { it == ConnectionSourcePolicy.KONOMI_PLACEHOLDER }.orEmpty()
                    ConnectionSource.EDCB -> prefs.edcbIp
                    ConnectionSource.MIRAKURUN -> prefs.mirakurunIp
                }
                val port = when (detail) {
                    ConnectionSource.KONOMITV -> prefs.konomiPort
                    ConnectionSource.EDCB -> prefs.edcbPort
                    ConnectionSource.MIRAKURUN -> prefs.mirakurunPort
                }
                row(2, "IPアドレス", host.ifBlank { AppStrings.SETTINGS_VALUE_UNSET }, Icons.Default.Link,
                    itemRs[1], itemRs[3]) { onEdit("${detail.label} (IPアドレス)", host) }
                val portTitle = if (detail == ConnectionSource.EDCB) "TCPポート" else "ポート"
                row(3, portTitle, port, Icons.Default.Numbers, itemRs[2],
                    if (detail == ConnectionSource.MIRAKURUN) FocusRequester.Cancel else itemRs[4]) {
                    onEdit("${detail.label} ($portTitle)", port)
                }
                if (detail == ConnectionSource.EDCB) {
                    row(4, "HTTP/HTTPSポート", prefs.edcbHttpPort, Icons.Default.Numbers,
                        itemRs[3], itemRs[5]) { onEdit("EDCB (HTTP/HTTPSポート)", prefs.edcbHttpPort) }
                }
            }
            if (detail == ConnectionSource.KONOMITV) {
                SettingsSection("HonomiTV アカウント") {
                    row(4, if (honomiUserName == null) "ログイン" else "ログアウト",
                        honomiUserName ?: "再生履歴を同期", Icons.Default.AccountCircle,
                        itemRs[3], FocusRequester.Cancel, action = onHonomiAccountClick)
                }
            }
            if (detail == ConnectionSource.EDCB) {
                SettingsSection("録画再生") {
                    row(5, "録画ファイルの再生方式",
                        if (prefs.edcbRecordPlayMethod == "DIRECT") "直接アクセス (高速シーク可)" else "API経由 (api/xcode)",
                        Icons.Default.PlayCircleOutline, itemRs[4], FocusRequester.Cancel,
                        action = onSelectEdcbPlayMethod)
                }
            }
        } else {
            row(0, "スマホで設定", "", Icons.Default.QrCode, FocusRequester.Cancel, itemRs[1]) { onWebSetup(null) }
            SettingsSection("接続先") {
                ConnectionSource.entries.forEachIndexed { index, entry ->
                    row(index + 1, entry.label, if (entry in configured) "設定済み" else AppStrings.SETTINGS_VALUE_UNSET,
                        Icons.Default.Dns,
                        itemRs[index],
                        if (index == 2 && !canSelectMain) itemRs[5] else itemRs[index + 2]) {
                        origin = index + 1; source = entry; navigating = true
                    }
                }
            }
            SettingsSection("メインシステム") {
                val selected = ConnectionSourcePolicy.resolve(prefs.backendType, configured)
                row(4, "メインシステム",
                    configured.firstOrNull { it.backend == selected }?.label ?: AppStrings.SETTINGS_VALUE_UNSET,
                    Icons.Default.CheckCircle, itemRs[3], itemRs[5], enabled = canSelectMain) {
                    onSelectBackend(configured)
                }
            }
            SettingsSection("ライブ視聴") {
                row(5, "優先ソース", when (prefs.preferredSource) {
                    "MIRAKURUN" -> "Mirakurun を優先"
                    "EDCB" -> "EDCB (ダイレクトストリーミング)"
                    else -> "メインシステムに従う（トランスコード）"
                }, Icons.Default.PriorityHigh, itemRs[if (canSelectMain) 4 else 3], itemRs[6],
                    action = onSelectSrc)
            }
            SettingsSection("Cloudflare Access") {
                row(6, "サービス トークン",
                    if (prefs.cfAccessClientId.isNotBlank() && prefs.cfAccessClientSecret.isNotBlank()) "設定済み" else "未設定",
                    Icons.Default.Cloud, itemRs[5], itemRs[7], action = onEditCloudflareAccess)
                Text("HTTPS の設定済みバックエンドだけにサービス トークンを送信します。",
                    modifier = Modifier.padding(horizontal = 24.dp), color = colors.textSecondary,
                    style = MaterialTheme.typography.bodyMedium)
            }
            SettingsSection("ファイルライブラリ (SMB) 接続設定") {
                row(7, "新しいSMBサーバーを追加", "", Icons.Default.Add,
                    itemRs[6], smbItemRs.firstOrNull() ?: FocusRequester.Cancel, action = onAddSmbServer)
                if (prefs.smbServerList.isEmpty()) {
                    Text("登録されたSMBサーバーはありません", modifier = Modifier.padding(16.dp),
                        style = MaterialTheme.typography.bodyMedium, color = colors.textSecondary.copy(0.6f))
                }
                prefs.smbServerList.forEachIndexed { index, server ->
                    val requester = smbItemRs[index]
                    SettingItem(server.name, server.ip, Icons.Default.Storage,
                        modifier = Modifier.focusRequester(requester).focusProperties {
                            left = sidebarR
                            up = if (index == 0) itemRs[7] else smbItemRs[index - 1]
                            down = smbItemRs.getOrNull(index + 1) ?: FocusRequester.Cancel
                        }, onClick = { onClick(requester); onSmbServerClick(server) })
                }
            }
        }
    }
}
