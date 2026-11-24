@file:OptIn(ExperimentalComposeUiApi::class)

package com.beeregg2001.komorebi.ui.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dialpad
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material.icons.filled.QueuePlayNext
import androidx.compose.material.icons.filled.SettingsInputHdmi
import androidx.compose.material.icons.filled.SettingsRemote
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.beeregg2001.komorebi.common.AppStrings
import com.beeregg2001.komorebi.data.DeepTvFeature
import com.beeregg2001.komorebi.data.DeepTvFeatureSettings
import com.beeregg2001.komorebi.data.TifNoSignalStyle
import com.beeregg2001.komorebi.ui.theme.KomorebiTheme

object DeepTvItemIndex {
    const val WATCH_NEXT = 0
    const val NEXT_EPISODE = 1
    const val PREVIEW_LIVE = 2
    const val PREVIEW_RECORDINGS = 3
    const val REMOTE_KEYS = 4
    const val NUMBER_TUNING = 5
    const val OS_PIP = 6
    const val TIF_TOGGLE = 7
    const val TIF_HIDE_EMPTY = 8
    const val TIF_NO_SIGNAL = 9
    const val CAST_ACCESS = 10
    const val TIF_SHOW_TUNERS = 11
    const val COUNT = 12
}

@Composable
fun DeepTvIntegrationSettingsContent(
    supported: Boolean,
    enabled: Boolean,
    lockedByMainSystem: Boolean,
    hideEmptyInputs: Boolean,
    showTunerInputs: Boolean,
    noSignalStyle: TifNoSignalStyle,
    features: DeepTvFeatureSettings = DeepTvFeatureSettings(),
    pipSupported: Boolean = true,
    itemRs: List<FocusRequester>,
    sidebarR: FocusRequester,
    onToggleFeature: (DeepTvFeature) -> Unit = {},
    onCycleBackgroundPlaybackMode: () -> Unit = {},
    onToggleEnabled: () -> Unit,
    onToggleHideEmpty: () -> Unit,
    onToggleShowTuners: () -> Unit,
    onEditNoSignalStyle: () -> Unit,
    onRefreshTif: () -> Unit,
    onClick: (FocusRequester) -> Unit,
    onFirstItemBoundsChanged: (Rect) -> Unit = {},
) {
    val colors = KomorebiTheme.colors
    val expanded = supported && (enabled || lockedByMainSystem)
    val tifFirst = remember { FocusRequester() }
    val tifLast = remember { FocusRequester() }

    val focusOrder = buildList {
        add(DeepTvItemIndex.WATCH_NEXT)
        if (features.watchNextEnabled) add(DeepTvItemIndex.NEXT_EPISODE)
        add(DeepTvItemIndex.PREVIEW_LIVE)
        add(DeepTvItemIndex.PREVIEW_RECORDINGS)
        add(DeepTvItemIndex.REMOTE_KEYS)
        add(DeepTvItemIndex.NUMBER_TUNING)
        add(DeepTvItemIndex.OS_PIP)
        if (!lockedByMainSystem) add(DeepTvItemIndex.TIF_TOGGLE)
        add(DeepTvItemIndex.CAST_ACCESS)
        if (expanded) {
            add(DeepTvItemIndex.TIF_SHOW_TUNERS)
            add(DeepTvItemIndex.TIF_HIDE_EMPTY)
            add(DeepTvItemIndex.TIF_NO_SIGNAL)
        }
    }

    fun itemModifier(index: Int): Modifier {
        val pos = focusOrder.indexOf(index)
        val upTarget = if (pos > 0) itemRs[focusOrder[pos - 1]] else FocusRequester.Cancel
        val downTarget = when {
            pos in 0 until focusOrder.lastIndex -> itemRs[focusOrder[pos + 1]]
            pos == focusOrder.lastIndex && expanded -> tifFirst
            else -> FocusRequester.Cancel
        }
        return Modifier
            .focusRequester(itemRs[index])
            .focusProperties {
                left = sidebarR
                up = upTarget
                down = downTarget
            }
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

    val toggleValue = when {
        lockedByMainSystem -> "ON（メインシステム）"
        !supported -> "非対応"
        enabled -> "ON"
        else -> "OFF"
    }
    val statusNote = when {
        lockedByMainSystem -> "メインシステムがTV入力のため無効にできません。接続設定でメインシステムを変更してください。"
        !supported -> "この端末では Android TV Input Framework 対応の外部入力・チューナーが検出されなかったため利用できません。"
        enabled -> "ホーム右上の「TV入力」やメインシステム選択から、HDMI外部入力・TVチャンネルをアプリ内で開きます。"
        else -> "アプリ内のTV入力統合はオフです。ホーム右上の「TV入力」はテレビ標準の入力切替アプリを起動します。"
    }

    Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
        Text(
            text = AppStrings.SETTINGS_CATEGORY_DEEP_TV,
            style = MaterialTheme.typography.headlineMedium,
            color = colors.textPrimary,
            fontWeight = FontWeight.Bold,
        )

        SettingsSection("Watch Next（次に観る）連携") {
            SettingItem(
                title = "Watch Next に番組を表示",
                value = if (features.watchNextEnabled) "ON" else "OFF",
                icon = Icons.Default.QueuePlayNext,
                modifier = itemModifier(DeepTvItemIndex.WATCH_NEXT),
                onClick = {
                    onClick(itemRs[DeepTvItemIndex.WATCH_NEXT])
                    onToggleFeature(DeepTvFeature.WATCH_NEXT)
                },
            )
            SettingItem(
                title = "シリーズ視聴完了時に次のエピソードを提案",
                value = if (features.watchNextNextEpisodeEnabled) "ON" else "OFF",
                icon = Icons.Default.SkipNext,
                enabled = features.watchNextEnabled,
                modifier = itemModifier(DeepTvItemIndex.NEXT_EPISODE),
                onClick = {
                    onClick(itemRs[DeepTvItemIndex.NEXT_EPISODE])
                    onToggleFeature(DeepTvFeature.WATCH_NEXT_NEXT_EPISODE)
                },
            )
            SectionNote("Android TV / Google TV ホームの「次に観る」に、視聴中の録画・新着録画・シリーズの次回エピソードを自動登録します。")
        }

        SettingsSection("ホーム画面チャンネル公開") {
            SettingItem(
                title = "「放送中の番組（ライブ）」チャンネルを公開",
                value = if (features.previewLiveEnabled) "ON" else "OFF",
                icon = Icons.Default.LiveTv,
                modifier = itemModifier(DeepTvItemIndex.PREVIEW_LIVE),
                onClick = {
                    onClick(itemRs[DeepTvItemIndex.PREVIEW_LIVE])
                    onToggleFeature(DeepTvFeature.PREVIEW_LIVE)
                },
            )
            SettingItem(
                title = "「新着録画」チャンネルを公開",
                value = if (features.previewRecordingsEnabled) "ON" else "OFF",
                icon = Icons.Default.VideoLibrary,
                modifier = itemModifier(DeepTvItemIndex.PREVIEW_RECORDINGS),
                onClick = {
                    onClick(itemRs[DeepTvItemIndex.PREVIEW_RECORDINGS])
                    onToggleFeature(DeepTvFeature.PREVIEW_RECORDINGS)
                },
            )
            SectionNote("Android TV ホーム画面に専用チャンネル行を追加します。オフにするとホーム画面から該当チャンネルを削除します。")
        }

        SettingsSection("TVリモコン操作") {
            SettingItem(
                title = "専用キーショートカット（番組表・録画・テレビ）",
                value = if (features.remoteShortcutKeysEnabled) "ON" else "OFF",
                icon = Icons.Default.SettingsRemote,
                modifier = itemModifier(DeepTvItemIndex.REMOTE_KEYS),
                onClick = {
                    onClick(itemRs[DeepTvItemIndex.REMOTE_KEYS])
                    onToggleFeature(DeepTvFeature.REMOTE_SHORTCUT_KEYS)
                },
            )
            SettingItem(
                title = "数字キー（0〜9）で直接選局",
                value = if (features.remoteNumberTuningEnabled) "ON" else "OFF",
                icon = Icons.Default.Dialpad,
                modifier = itemModifier(DeepTvItemIndex.NUMBER_TUNING),
                onClick = {
                    onClick(itemRs[DeepTvItemIndex.NUMBER_TUNING])
                    onToggleFeature(DeepTvFeature.REMOTE_NUMBER_TUNING)
                },
            )
            SectionNote("リモコンの「番組表」「録画」「テレビ」ボタンでの直接移動や、数字ボタン（リモコン番号 1〜12 / 3桁チャンネル番号）でのダイレクト選局を有効にします。")
        }

        SettingsSection("バックグラウンド再生・PiP") {
            val effectiveMode = if (!pipSupported && features.backgroundPlaybackMode == com.beeregg2001.komorebi.data.BackgroundPlaybackMode.PIP) {
                com.beeregg2001.komorebi.data.BackgroundPlaybackMode.OFF
            } else {
                features.backgroundPlaybackMode
            }
            SettingItem(
                title = "バックグラウンド・画面オフ時の再生動作",
                value = effectiveMode.label,
                icon = Icons.Default.PictureInPictureAlt,
                enabled = true,
                modifier = itemModifier(DeepTvItemIndex.OS_PIP),
                onClick = {
                    onClick(itemRs[DeepTvItemIndex.OS_PIP])
                    onCycleBackgroundPlaybackMode()
                },
            )
            SectionNote(
                when (effectiveMode) {
                    com.beeregg2001.komorebi.data.BackgroundPlaybackMode.PIP ->
                        "再生中にホーム移動するとOS標準の小窓（PiP）で再生を継続します。小窓を閉じると再生を停止します。"
                    com.beeregg2001.komorebi.data.BackgroundPlaybackMode.AUDIO_ONLY ->
                        "他のアプリ操作中や画面オフ時も音声のみ再生を継続します（通知・ロック画面から操作可能）。※TIF外部入力は非対応です。"
                    com.beeregg2001.komorebi.data.BackgroundPlaybackMode.OFF ->
                        "ホーム移動や画面オフ時に再生を停止します。"
                }
            )
        }

        SettingsSection("外部入力・内蔵チューナー（TIF）") {
            SettingItem(
                title = "TV入力（TIF）統合を有効にする",
                value = toggleValue,
                icon = Icons.Default.SettingsInputHdmi,
                enabled = !lockedByMainSystem,
                modifier = itemModifier(DeepTvItemIndex.TIF_TOGGLE),
                onClick = {
                    onClick(itemRs[DeepTvItemIndex.TIF_TOGGLE])
                    onToggleEnabled()
                },
            )
            SectionNote(statusNote)
        }

        CastAccessSettings(modifier = itemModifier(DeepTvItemIndex.CAST_ACCESS))

        if (expanded) {
            SettingsSection("表示・動作設定") {
                SettingItem(
                    title = "入力ソースにテレビ信号を表示",
                    value = if (showTunerInputs) "ON" else "OFF",
                    icon = Icons.Default.FilterList,
                    modifier = itemModifier(DeepTvItemIndex.TIF_SHOW_TUNERS),
                    onClick = {
                        onClick(itemRs[DeepTvItemIndex.TIF_SHOW_TUNERS])
                        onToggleShowTuners()
                    },
                )
                SettingItem(
                    title = "チャンネル未登録のTV入力を非表示",
                    value = if (hideEmptyInputs) "ON" else "OFF",
                    icon = Icons.Default.FilterList,
                    modifier = itemModifier(DeepTvItemIndex.TIF_HIDE_EMPTY),
                    onClick = {
                        onClick(itemRs[DeepTvItemIndex.TIF_HIDE_EMPTY])
                        onToggleHideEmpty()
                    },
                )
                SettingItem(
                    title = "TV入力の信号なし表示",
                    value = noSignalStyle.label,
                    icon = Icons.Default.Tv,
                    modifier = itemModifier(DeepTvItemIndex.TIF_NO_SIGNAL),
                    onClick = {
                        onClick(itemRs[DeepTvItemIndex.TIF_NO_SIGNAL])
                        onEditNoSignalStyle()
                    },
                )
            }

            SettingsSection("TV入力ソース管理") {
                TifInputSettings(
                    onRefresh = onRefreshTif,
                    first = tifFirst,
                    last = tifLast,
                    before = itemRs[DeepTvItemIndex.TIF_NO_SIGNAL],
                    after = FocusRequester.Cancel,
                    sidebar = sidebarR,
                )
            }
        }
    }
}
