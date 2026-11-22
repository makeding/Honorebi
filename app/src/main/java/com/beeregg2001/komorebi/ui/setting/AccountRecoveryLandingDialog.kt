@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)

package com.beeregg2001.komorebi.ui.setting

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.SettingsEthernet
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import androidx.tv.material3.Text
import com.beeregg2001.komorebi.common.safeRequestFocusWithRetry
import com.beeregg2001.komorebi.ui.components.DialogActionButton
import com.beeregg2001.komorebi.ui.components.DialogActionRole
import com.beeregg2001.komorebi.ui.theme.KomorebiTheme
import com.beeregg2001.komorebi.viewmodel.SettingsViewModel
import kotlinx.coroutines.delay

@Composable
fun AccountRecoveryLandingDialog(
    state: SettingsViewModel.AccountRecoveryState,
    onImportPrevious: () -> Unit,
    onStartFresh: () -> Unit,
) {
    Dialog(
        onDismissRequest = {},
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
        ),
    ) {
        AccountRecoveryLandingContent(
            state = state,
            onImportPrevious = onImportPrevious,
            onStartFresh = onStartFresh,
        )
    }
}

@Composable
internal fun AccountRecoveryLandingContent(
    state: SettingsViewModel.AccountRecoveryState,
    onImportPrevious: () -> Unit,
    onStartFresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = KomorebiTheme.colors
    val importFocusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        delay(120)
        importFocusRequester.safeRequestFocusWithRetry("AccountRecovery_Import")
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(colors.background)
            .padding(horizontal = 56.dp, vertical = 40.dp)
            .testTag("account_recovery_landing"),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 920.dp)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .background(colors.accent.copy(alpha = 0.16f), RoundedCornerShape(999.dp))
                        .border(1.dp, colors.accent.copy(alpha = 0.45f), RoundedCornerShape(999.dp))
                        .padding(horizontal = 12.dp, vertical = 5.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.AutoAwesome,
                        contentDescription = null,
                        tint = colors.accent,
                        modifier = Modifier.size(16.dp),
                    )
                    Text(
                        text = "アプリデータ消去後のセットアップ",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.accent,
                    )
                }

                Text(
                    text = "アプリがピカピカになりました",
                    style = MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.Bold,
                    color = colors.textPrimary,
                )

                Text(
                    text = "アプリのデータが消去されたため、設定やキャッシュは初期状態に戻っています。始める前に、端末に残っていた設定を引き継ぐかお選びください。",
                    style = MaterialTheme.typography.bodyLarge,
                    color = colors.textSecondary,
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(24.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Surface(
                    modifier = Modifier
                        .weight(1.15f)
                        .border(1.dp, colors.borderSubtle, RoundedCornerShape(16.dp)),
                    shape = RoundedCornerShape(16.dp),
                    colors = SurfaceDefaults.colors(
                        containerColor = colors.surface1,
                        contentColor = colors.textPrimary,
                    ),
                ) {
                    Column(
                        modifier = Modifier
                            .padding(24.dp)
                            .focusGroup(),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        Text(
                            text = "以前の接続設定・アカウントを引き継ぎますか？",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = colors.textPrimary,
                        )

                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(colors.surface2, RoundedCornerShape(12.dp))
                                .border(1.dp, colors.borderSubtle, RoundedCornerShape(12.dp))
                                .padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            if (!state.userName.isNullOrBlank()) {
                                RecoveryItemRow(
                                    icon = Icons.Default.Person,
                                    label = "アカウントログイン",
                                    value = state.userName,
                                )
                            }
                            if (state.connectionLabels.isNotEmpty()) {
                                RecoveryItemRow(
                                    icon = Icons.Default.SettingsEthernet,
                                    label = "接続設定",
                                    value = state.connectionLabels.joinToString(" / "),
                                )
                            }
                            if (state.userName.isNullOrBlank() && state.connectionLabels.isEmpty()) {
                                Text(
                                    text = "保存済みのアカウント・接続情報が見つかりました。",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = colors.textSecondary,
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(2.dp))

                        DialogActionButton(
                            role = DialogActionRole.Primary,
                            onClick = onImportPrevious,
                            enabled = !state.isApplying,
                            modifier = Modifier
                                .fillMaxWidth()
                                .focusRequester(importFocusRequester)
                                .testTag("account_recovery_import_button"),
                        ) {
                            Text(
                                text = if (state.isApplying) "適用しています…" else "以前の接続設定・アカウントを引き継ぐ",
                                fontWeight = FontWeight.SemiBold,
                            )
                        }

                        DialogActionButton(
                            role = DialogActionRole.Secondary,
                            onClick = onStartFresh,
                            enabled = !state.isApplying,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("account_recovery_fresh_button"),
                        ) {
                            Text("新しく設定をやり直す")
                        }
                    }
                }

                Surface(
                    modifier = Modifier
                        .weight(0.85f)
                        .border(1.dp, colors.borderSubtle, RoundedCornerShape(16.dp)),
                    shape = RoundedCornerShape(16.dp),
                    colors = SurfaceDefaults.colors(
                        containerColor = colors.surface1,
                        contentColor = colors.textPrimary,
                    ),
                ) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(34.dp)
                                    .background(colors.accent.copy(alpha = 0.16f), CircleShape),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    imageVector = Icons.Default.CleaningServices,
                                    contentDescription = null,
                                    tint = colors.accent,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                            Text(
                                text = "次回お困りのときは",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = colors.textPrimary,
                            )
                        }

                        Text(
                            text = "表示の乱れや動作が重いときは、端末の「アプリデータを消去」を行わなくても、アプリ内の「設定 > キャッシュ管理」から各種キャッシュだけを直接削除できます。",
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.textSecondary,
                        )

                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(colors.surface2, RoundedCornerShape(12.dp))
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            CacheHintBullet("すべてのキャッシュを一括削除")
                            CacheHintBullet("局ロゴ・サムネイルキャッシュ")
                            CacheHintBullet("番組表・録画リストキャッシュ")
                        }

                        Text(
                            text = "※ キャッシュ削除では接続設定やアカウント情報は消えません。",
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.textMuted,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RecoveryItemRow(
    icon: ImageVector,
    label: String,
    value: String,
) {
    val colors = KomorebiTheme.colors
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = colors.accent,
            modifier = Modifier
                .padding(top = 2.dp)
                .size(18.dp),
        )
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = colors.textMuted,
            )
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = colors.textPrimary,
            )
        }
    }
}

@Composable
private fun CacheHintBullet(text: String) {
    val colors = KomorebiTheme.colors
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .background(colors.accent, CircleShape),
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = colors.textPrimary,
        )
    }
}
