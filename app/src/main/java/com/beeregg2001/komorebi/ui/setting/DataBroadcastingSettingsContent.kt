@file:OptIn(ExperimentalComposeUiApi::class)

package com.beeregg2001.komorebi.ui.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.SettingsRemote
import androidx.compose.runtime.Composable
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
import com.beeregg2001.komorebi.data.DataBroadcastingSettings
import com.beeregg2001.komorebi.ui.theme.KomorebiTheme

object DataBroadcastingItemIndex {
    const val ZIPCODE = 0
    const val PREFECTURE = 1
    const val ALLOW_EXTERNAL_NETWORK = 2
    const val VIRTUAL_REMOTE_MODE = 3
    const val PRELOAD_B24 = 4
    const val RESET_STORAGE = 5
    const val COUNT = 6
}

@Composable
fun DataBroadcastingSettingsContent(
    settings: DataBroadcastingSettings,
    itemRs: List<FocusRequester>,
    sidebarR: FocusRequester,
    onEditZipcode: () -> Unit,
    onEditPrefecture: () -> Unit,
    onToggleAllowExternalNetwork: () -> Unit,
    onEditVirtualRemoteMode: () -> Unit,
    onTogglePreloadB24: () -> Unit,
    onResetStorage: () -> Unit,
    onClick: (FocusRequester) -> Unit,
) {
    val colors = KomorebiTheme.colors

    fun itemModifier(index: Int): Modifier = Modifier
        .focusRequester(itemRs[index])
        .focusProperties {
            left = sidebarR
            up = if (index > 0) itemRs[index - 1] else FocusRequester.Cancel
            down = if (index < DataBroadcastingItemIndex.COUNT - 1) itemRs[index + 1] else FocusRequester.Cancel
        }

    @Composable
    fun SectionNote(text: String) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            fontSize = 13.sp,
            color = colors.textSecondary,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
    }

    Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
        Text(
            text = AppStrings.SETTINGS_CATEGORY_DATA_BROADCASTING,
            style = MaterialTheme.typography.headlineMedium,
            color = colors.textPrimary,
            fontWeight = FontWeight.Bold,
        )

        SettingsSection("地域・受信機情報") {
            SettingItem(
                title = "お住まいの郵便番号",
                value = settings.displayZipcode,
                icon = Icons.Default.LocationOn,
                modifier = itemModifier(DataBroadcastingItemIndex.ZIPCODE),
                onClick = {
                    onClick(itemRs[DataBroadcastingItemIndex.ZIPCODE])
                    onEditZipcode()
                },
            )
            SettingItem(
                title = "お住まいの都道府県",
                value = settings.displayPrefecture,
                icon = Icons.Default.Map,
                modifier = itemModifier(DataBroadcastingItemIndex.PREFECTURE),
                onClick = {
                    onClick(itemRs[DataBroadcastingItemIndex.PREFECTURE])
                    onEditPrefecture()
                },
            )
            SectionNote("設定した郵便番号・都道府県をもとに、データ放送の天気予報や地域ニュースなどの地域情報が表示されます。")
        }

        SettingsSection("通信・プライバシー") {
            SettingItem(
                title = "データ放送からのインターネット接続を許可",
                value = if (settings.allowExternalNetwork) "ON" else "OFF",
                icon = Icons.Default.Public,
                modifier = itemModifier(DataBroadcastingItemIndex.ALLOW_EXTERNAL_NETWORK),
                onClick = {
                    onClick(itemRs[DataBroadcastingItemIndex.ALLOW_EXTERNAL_NETWORK])
                    onToggleAllowExternalNetwork()
                },
            )
            SectionNote("オンにすると、双方向番組やネット連携コンテンツを利用できます。視聴データがテレビ局へ送信される場合があるため、通常はオフをおすすめします。")
        }

        SettingsSection("リモコン操作・読み込み") {
            SettingItem(
                title = "仮想リモコンバーの表示",
                value = settings.virtualRemoteMode.label,
                icon = Icons.Default.SettingsRemote,
                modifier = itemModifier(DataBroadcastingItemIndex.VIRTUAL_REMOTE_MODE),
                onClick = {
                    onClick(itemRs[DataBroadcastingItemIndex.VIRTUAL_REMOTE_MODE])
                    onEditVirtualRemoteMode()
                },
            )
            SettingItem(
                title = "2Kデータ放送を事前受信（再生5秒後）",
                value = if (settings.preloadB24InBackground) "ON" else "OFF",
                icon = Icons.Default.SettingsRemote,
                modifier = itemModifier(DataBroadcastingItemIndex.PRELOAD_B24),
                onClick = {
                    onClick(itemRs[DataBroadcastingItemIndex.PRELOAD_B24])
                    onTogglePreloadB24()
                },
            )
            SectionNote("四色キーや数字キーがないリモコンでも操作できる仮想リモコンバーの設定と、2Kライブ再生開始5秒後に裏でデータ放送を事前受信する設定です。動作が重い端末では事前受信をオフにしてください。")
        }

        SettingsSection("保存データ") {
            SettingItem(
                title = "データ放送の保存データをリセット",
                value = "実行",
                icon = Icons.Default.DeleteOutline,
                modifier = itemModifier(DataBroadcastingItemIndex.RESET_STORAGE),
                onClick = {
                    onClick(itemRs[DataBroadcastingItemIndex.RESET_STORAGE])
                    onResetStorage()
                },
            )
            SectionNote("データ放送内の設定・ミニゲームの得点・スタンプ等の保存データ（NVRAM）を消去します。郵便番号や都道府県設定は維持されます。")
        }
    }
}
