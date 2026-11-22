@file:OptIn(ExperimentalComposeUiApi::class)

package com.beeregg2001.komorebi.ui.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.SettingsInputHdmi
import androidx.compose.material.icons.filled.Tv
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.beeregg2001.komorebi.common.AppStrings
import com.beeregg2001.komorebi.data.TifNoSignalStyle
import com.beeregg2001.komorebi.ui.theme.KomorebiTheme

@Composable
fun DeepTvIntegrationSettingsContent(
    supported: Boolean,
    enabled: Boolean,
    lockedByMainSystem: Boolean,
    hideEmptyInputs: Boolean,
    noSignalStyle: TifNoSignalStyle,
    itemRs: List<FocusRequester>,
    sidebarR: FocusRequester,
    onToggleEnabled: () -> Unit,
    onToggleHideEmpty: () -> Unit,
    onEditNoSignalStyle: () -> Unit,
    onRefreshTif: () -> Unit,
    onClick: (FocusRequester) -> Unit,
) {
    val colors = KomorebiTheme.colors
    val expanded = supported && (enabled || lockedByMainSystem)
    val tifFirst = remember { FocusRequester() }
    val tifLast = remember { FocusRequester() }

    val toggleValue = when {
        lockedByMainSystem -> "ON（メインシステム）"
        !supported -> "非対応"
        enabled -> "ON"
        else -> "OFF"
    }
    val statusNote = when {
        lockedByMainSystem -> "メインシステムがTV入力のため無効にできません。接続設定でメインシステムを変更してください。"
        !supported -> "この端末では Android TV Input Framework 対応の外部入力・チューナーが検出されなかったため利用できません。"
        enabled -> "ホーム右上の「入力切替」やメインシステム選択から、HDMI外部入力・TVチャンネルをアプリ内で開きます。"
        else -> "アプリ内のTV入力統合はオフです。ホームの「入力切替」はテレビ標準の入力切替アプリを起動します。"
    }

    Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = AppStrings.SETTINGS_CATEGORY_DEEP_TV,
                style = MaterialTheme.typography.headlineMedium,
                color = colors.textPrimary,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = "テレビ本体のHDMI外部入力や内蔵チューナー（Android TV Input Framework）をアプリ内で直接操作・再生します。",
                style = MaterialTheme.typography.bodyMedium,
                color = colors.textSecondary,
            )
        }

        SettingsSection("統合機能") {
            SettingItem(
                title = "ディープTV統合を有効にする",
                value = toggleValue,
                icon = Icons.Default.SettingsInputHdmi,
                enabled = !lockedByMainSystem,
                modifier = Modifier
                    .focusRequester(itemRs[0])
                    .focusProperties {
                        left = sidebarR
                        up = FocusRequester.Cancel
                        down = if (expanded) itemRs[1] else FocusRequester.Cancel
                    },
                onClick = {
                    onClick(itemRs[0])
                    onToggleEnabled()
                },
            )
            Text(
                text = statusNote,
                style = MaterialTheme.typography.bodySmall,
                fontSize = 13.sp,
                color = colors.textSecondary,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }

        if (expanded) {
            SettingsSection("表示・動作設定") {
                SettingItem(
                    title = "チャンネル未登録のTV入力を非表示",
                    value = if (hideEmptyInputs) "ON" else "OFF",
                    icon = Icons.Default.FilterList,
                    modifier = Modifier
                        .focusRequester(itemRs[1])
                        .focusProperties {
                            left = sidebarR
                            up = if (lockedByMainSystem) FocusRequester.Cancel else itemRs[0]
                            down = itemRs[2]
                        },
                    onClick = {
                        onClick(itemRs[1])
                        onToggleHideEmpty()
                    },
                )
                SettingItem(
                    title = "TV入力の信号なし表示",
                    value = noSignalStyle.label,
                    icon = Icons.Default.Tv,
                    modifier = Modifier
                        .focusRequester(itemRs[2])
                        .focusProperties {
                            left = sidebarR
                            up = itemRs[1]
                            down = tifFirst
                        },
                    onClick = {
                        onClick(itemRs[2])
                        onEditNoSignalStyle()
                    },
                )
            }

            SettingsSection("TV入力ソース管理") {
                TifInputSettings(
                    onRefresh = onRefreshTif,
                    first = tifFirst,
                    last = tifLast,
                    before = itemRs[2],
                    after = FocusRequester.Cancel,
                    sidebar = sidebarR,
                )
            }
        }
    }
}
