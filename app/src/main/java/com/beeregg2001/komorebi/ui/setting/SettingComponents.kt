@file:OptIn(ExperimentalTvMaterial3Api::class, ExperimentalComposeUiApi::class)

package com.beeregg2001.komorebi.ui.setting

import android.graphics.Bitmap
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.NativeKeyEvent
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.tv.material3.*
import com.beeregg2001.komorebi.common.AppStrings
import com.beeregg2001.komorebi.common.safeRequestFocus
import com.beeregg2001.komorebi.common.safeRequestFocusWithRetry
import com.beeregg2001.komorebi.ui.theme.tnum
import com.beeregg2001.komorebi.ui.theme.KomorebiTheme
import com.beeregg2001.komorebi.ui.theme.TvCardFocusBorderWidth
import com.beeregg2001.komorebi.ui.theme.TvCardRadiusListRow
import com.beeregg2001.komorebi.ui.components.DialogActionButton
import com.beeregg2001.komorebi.ui.components.DialogActionRole
import com.beeregg2001.komorebi.ui.components.DialogBackdropScrim
import com.beeregg2001.komorebi.ui.components.DialogQrCard
import com.beeregg2001.komorebi.ui.components.DialogUrlBox
import com.beeregg2001.komorebi.ui.components.ThemedDialogSurface
import com.beeregg2001.komorebi.ui.components.dialogForeground
import com.beeregg2001.komorebi.viewmodel.SettingsViewModel
import com.beeregg2001.komorebi.viewmodel.SmbServer
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

sealed class SettingDialogState {
    object None : SettingDialogState()
    data class Input(val title: String, val initialValue: String, val onConfirm: (String) -> Unit) :
        SettingDialogState()
    data class CloudflareAccess(
        val clientId: String,
        val clientSecret: String,
        val onConfirm: suspend (String, String) -> Unit,
    ) : SettingDialogState()

    data class BatchInput(val onConfirm: (String, String) -> Unit) : SettingDialogState()
    data class Selection(
        val title: String,
        val options: List<Pair<String, String>>,
        val current: String,
        val onSelect: (String) -> Unit
    ) : SettingDialogState()

    data class MultiSelection(
        val title: String,
        val options: List<Pair<String, String>>,
        val currentSelections: Set<String>,
        val onConfirm: (Set<String>) -> Unit
    ) : SettingDialogState()

    data class ConfirmClear(val title: String, val message: String, val onConfirm: () -> Unit) :
        SettingDialogState()

    object Licenses : SettingDialogState()
    data class LinkQr(val title: String, val url: String) : SettingDialogState()
    object GeminiSetup : SettingDialogState()
    data class ConnectionSetup(val source: String?) : SettingDialogState()
    object DeviceCapabilities : SettingDialogState()
    object HonomiLogin : SettingDialogState()
    object HonomiLogout : SettingDialogState()
    object DefaultHomeGuide : SettingDialogState()

    data class SmbAction(val target: SmbServer) : SettingDialogState()
    data class SmbSetup(val target: SmbServer? = null) : SettingDialogState()
    data class SmbManualInput(val target: SmbServer? = null, val onConfirm: (SmbServer) -> Unit) :
        SettingDialogState()
}

data class Category(val name: String, val icon: ImageVector)

fun getThemeFromModeAndSeason(isDark: Boolean, season: String): String {
    return when (season) {
        "DEFAULT" -> if (isDark) "MONOTONE" else "HIGHTONE"
        "SPRING" -> if (isDark) "SPRING" else "SPRING_LIGHT"
        "SUMMER" -> if (isDark) "SUMMER" else "SUMMER_LIGHT"
        "AUTUMN" -> if (isDark) "AUTUMN" else "AUTUMN_LIGHT"
        "WINTER" -> if (isDark) "WINTER_DARK" else "WINTER_LIGHT"
        "BLUE" -> if (isDark) "EPG_BLUE" else "EPG_BLUE_LIGHT"
        else -> if (isDark) "MONOTONE" else "HIGHTONE"
    }
}

@Composable
fun SettingsSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    val colors = KomorebiTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            title,
            color = colors.textSecondary,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(start = 8.dp, bottom = 4.dp)
        )
        content()
    }
}

@Composable
fun CategoryItem(
    title: String,
    icon: ImageVector,
    isSelected: Boolean,
    onFocused: () -> Unit,
    onClick: () -> Unit,
    enabled: Boolean = true,
    modifier: Modifier = Modifier
) {
    val colors = KomorebiTheme.colors
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    Surface(
        selected = isSelected,
        onClick = { if (enabled) onClick() },
        modifier = modifier
            .fillMaxWidth()
            .focusProperties { canFocus = enabled }
            .onFocusChanged { if (it.isFocused && enabled) onFocused() },
        interactionSource = interactionSource,
        colors = SelectableSurfaceDefaults.colors(
            containerColor = Color.Transparent,
            selectedContainerColor = colors.accentSoft,
            focusedContainerColor = colors.surface2,
            contentColor = if (enabled) colors.textSecondary else colors.textSecondary.copy(alpha = 0.3f),
            selectedContentColor = colors.textPrimary,
            focusedContentColor = colors.textPrimary,
            focusedSelectedContainerColor = colors.surface2,
            focusedSelectedContentColor = colors.textPrimary,
        ),
        border = SelectableSurfaceDefaults.border(
            focusedBorder = Border(BorderStroke(2.dp, colors.accent)),
            focusedSelectedBorder = Border(BorderStroke(2.dp, colors.accent)),
        ),
        shape = SelectableSurfaceDefaults.shape(MaterialTheme.shapes.medium),
        scale = SelectableSurfaceDefaults.scale(
            focusedScale = if (enabled) 1.02f else 1.0f,
            focusedSelectedScale = if (enabled) 1.02f else 1.0f,
        )
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (isSelected) {
                Box(
                    Modifier
                        .width(3.dp)
                        .height(20.dp)
                        .background(colors.accent, MaterialTheme.shapes.small)
                )
                Spacer(Modifier.width(13.dp))
            } else {
                Spacer(Modifier.width(16.dp))
            }
            Icon(
                icon,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = if (isSelected || isFocused) colors.accent else colors.textSecondary.copy(
                    alpha = if (enabled) 1f else 0.3f
                )
            )
            Spacer(Modifier.width(14.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = if (isSelected || isFocused) colors.textPrimary else colors.textSecondary.copy(
                    alpha = if (enabled) 1f else 0.3f
                )
            )
        }
    }
}

@Composable
fun SettingItem(
    title: String,
    value: String,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    focusedContainerColor: Color? = null,
    focusedContentColor: Color? = null,
    onClick: () -> Unit
) {
    val colors = KomorebiTheme.colors
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val customHighlight = focusedContainerColor != null
    val focusedBgColor = focusedContainerColor ?: colors.surface2
    val focusedTextColor = focusedContentColor ?: if (customHighlight) dialogForeground(focusedBgColor) else colors.textPrimary

    Surface(
        enabled = enabled,
        onClick = { if (enabled) onClick() },
        modifier = modifier
            .fillMaxWidth()
            .focusProperties { canFocus = enabled },
        interactionSource = interactionSource,
        colors = ClickableSurfaceDefaults.colors(
            containerColor = colors.surface1,
            focusedContainerColor = focusedBgColor,
            contentColor = colors.textPrimary.copy(alpha = if (enabled) 1f else 0.4f),
            focusedContentColor = focusedTextColor
        ),
        border = ClickableSurfaceDefaults.border(
            border = Border(BorderStroke(1.dp, colors.borderSubtle)),
            focusedBorder = if (customHighlight) Border.None else Border(BorderStroke(2.dp, colors.accent))
        ),
        shape = ClickableSurfaceDefaults.shape(MaterialTheme.shapes.medium),
        scale = ClickableSurfaceDefaults.scale(focusedScale = if (enabled) 1.015f else 1.0f)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 18.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (icon != null) {
                Icon(
                    icon,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                    tint = if (isFocused && enabled) {
                        if (customHighlight) focusedTextColor else colors.accent
                    } else {
                        colors.textPrimary.copy(
                            alpha = if (enabled) 0.7f else 0.3f
                        )
                    }
                )
                Spacer(Modifier.width(16.dp))
            }
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
                color = if (isFocused && enabled) focusedTextColor else Color.Unspecified
            )
            Text(
                text = value,
                textAlign = TextAlign.End,
                style = MaterialTheme.typography.bodyLarge.tnum,
                color = if (isFocused && enabled) {
                    if (customHighlight) focusedTextColor else colors.accent
                } else {
                    colors.textSecondary.copy(
                        alpha = if (enabled) 1f else 0.5f
                    )
                }
            )
        }
    }
}

// ★ 修正: 決定キーで入力モードにする（UX改善）と、システムへの自然な移動を実装
@Composable
fun DialogTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    isPassword: Boolean = false,
    focusRequester: FocusRequester = remember { FocusRequester() }
) {
    val colors = KomorebiTheme.colors
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    var isFocused by remember { mutableStateOf(false) }
    var isEditing by remember { mutableStateOf(false) } // ★ 追加: 決定キーで入力モードへ

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
            .background(
                colors.textPrimary.copy(if (isEditing) 0.15f else 0.05f),
                RoundedCornerShape(8.dp)
            )
            .border(
                BorderStroke(2.dp, if (isFocused) colors.accent else Color.Transparent),
                RoundedCornerShape(8.dp)
            ),
        contentAlignment = Alignment.CenterStart
    ) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            textStyle = TextStyle(color = colors.textPrimary, fontSize = 16.sp),
            cursorBrush = SolidColor(colors.textPrimary),
            visualTransformation = if (isPassword) PasswordVisualTransformation() else VisualTransformation.None,
            readOnly = !isEditing, // ★ 追加: 普段は読み取り専用にしてキーボードの自動表示を防ぐ
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .focusRequester(focusRequester)
                .onFocusChanged { itState ->
                    isFocused = itState.isFocused
                    if (!itState.isFocused) {
                        isEditing = false // フォーカスが外れたら編集モード解除
                    }
                }
                .onPreviewKeyEvent { event ->
                    if (event.type == KeyEventType.KeyDown) {
                        when (event.nativeKeyEvent.keyCode) {
                            android.view.KeyEvent.KEYCODE_DPAD_DOWN -> {
                                isEditing = false
                                keyboardController?.hide()
                                focusManager.moveFocus(FocusDirection.Down)
                                true
                            }

                            android.view.KeyEvent.KEYCODE_DPAD_UP -> {
                                isEditing = false
                                keyboardController?.hide()
                                focusManager.moveFocus(FocusDirection.Up)
                                true
                            }

                            android.view.KeyEvent.KEYCODE_DPAD_CENTER,
                            android.view.KeyEvent.KEYCODE_ENTER,
                            android.view.KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                                isEditing = true
                                keyboardController?.show()
                                true
                            }

                            android.view.KeyEvent.KEYCODE_BACK,
                            android.view.KeyEvent.KEYCODE_ESCAPE -> {
                                if (isEditing) {
                                    isEditing = false
                                    keyboardController?.hide()
                                    true
                                } else {
                                    false
                                }
                            }

                            else -> false
                        }
                    } else false
                },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = {
                isEditing = false
                keyboardController?.hide()
                focusManager.moveFocus(FocusDirection.Down)
            }),
            decorationBox = { innerTextField ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (value.isEmpty()) Text(
                        text = placeholder,
                        color = colors.textSecondary.copy(0.5f),
                        fontSize = 16.sp
                    )
                    innerTextField()
                }
            }
        )
    }
}

@Composable
fun InputDialog(
    title: String,
    initialValue: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    val colors = KomorebiTheme.colors
    var value by remember { mutableStateOf(initialValue) }
    val focusRequester = remember { FocusRequester() }
    var isClosing by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { delay(150); focusRequester.safeRequestFocus() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(DialogBackdropScrim)
            .focusGroup()
            .focusProperties {
                exit = { if (isClosing) FocusRequester.Default else FocusRequester.Cancel }
            }
            .onKeyEvent {
                if (it.type == KeyEventType.KeyDown && it.nativeKeyEvent.keyCode == NativeKeyEvent.KEYCODE_BACK) {
                    isClosing = true; onDismiss(); true
                } else false
            },
        contentAlignment = Alignment.Center
    ) {
        ThemedDialogSurface(width = 540.dp) {
            Column(
                verticalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                Text(
                    title,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )
                DialogTextField(
                    value = value,
                    onValueChange = { value = it },
                    placeholder = "",
                    focusRequester = focusRequester
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    DialogActionButton(DialogActionRole.Secondary, { isClosing = true; onDismiss() }, Modifier.weight(1f)) { Text("キャンセル") }
                    DialogActionButton(DialogActionRole.Primary, { isClosing = true; onConfirm(value) }, Modifier.weight(1f)) { Text("OK") }
                }
            }
        }
    }
}

@Composable
fun HonomiLoginDialog(
    isLoading: Boolean,
    error: String?,
    onDismiss: () -> Unit,
    onStart: () -> Unit,
    pairing: com.beeregg2001.komorebi.data.model.DeviceAuthRequest?,
    verificationUrl: String?,
) {
    val colors = KomorebiTheme.colors
    val qrBitmap = verificationUrl?.let { rememberQrBitmap(it, 260, margin = 1) }
    LaunchedEffect(Unit) { onStart() }

    Box(
        modifier = Modifier.fillMaxSize().background(DialogBackdropScrim).focusGroup()
            .onKeyEvent {
                if (it.type == KeyEventType.KeyDown && it.nativeKeyEvent.keyCode == NativeKeyEvent.KEYCODE_BACK && !isLoading) {
                    onDismiss(); true
                } else false
            },
        contentAlignment = Alignment.Center,
    ) {
        ThemedDialogSurface(width = 640.dp) {
            Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
                Text("HonomiTV とペアリング", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(28.dp),
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(
                            "QRコードを読み取るか、URLを開いてコードを入力してください。",
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.textSecondary,
                        )
                        if (!verificationUrl.isNullOrBlank()) {
                            DialogUrlBox(url = verificationUrl, label = "認証URL")
                        }
                        if (pairing != null) {
                            Text(
                                text = pairing.userCode.chunked(4).joinToString(" "),
                                style = MaterialTheme.typography.displaySmall.tnum,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 4.sp,
                                color = colors.accent,
                            )
                        }
                        if (error != null) {
                            Text(error, color = colors.danger, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    DialogQrCard(
                        bitmap = qrBitmap,
                        contentDescription = "HonomiTV 認証QRコード",
                        size = 200.dp,
                    )
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    DialogActionButton(DialogActionRole.Secondary, onDismiss, Modifier.weight(1f), enabled = !isLoading) { Text("キャンセル") }
                    if (isLoading) CircularProgressIndicator(Modifier.size(36.dp).align(Alignment.CenterVertically))
                }
            }
        }
    }
}

@Composable
fun LinkQrDialog(
    title: String,
    url: String,
    onDismiss: () -> Unit,
) {
    val colors = KomorebiTheme.colors
    val qrBitmap = rememberQrBitmap(url, 300, margin = 1)
    val closeRequester = remember { FocusRequester() }

    LaunchedEffect(url) {
        delay(120)
        closeRequester.safeRequestFocusWithRetry("LinkQrDialog_close")
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(DialogBackdropScrim)
            .focusGroup()
            .focusProperties { exit = { FocusRequester.Cancel } }
            .onKeyEvent {
                if (it.type == KeyEventType.KeyDown && it.nativeKeyEvent.keyCode == NativeKeyEvent.KEYCODE_BACK) {
                    onDismiss(); true
                } else false
            },
        contentAlignment = Alignment.Center,
    ) {
        ThemedDialogSurface(width = 520.dp) {
            Column(
                verticalArrangement = Arrangement.spacedBy(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    title,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    AppStrings.DIALOG_LINK_HINT,
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.textSecondary,
                    textAlign = TextAlign.Center
                )
                DialogQrCard(
                    bitmap = qrBitmap,
                    contentDescription = title,
                    size = 220.dp,
                )
                DialogUrlBox(url = url)
                DialogActionButton(
                    DialogActionRole.Secondary,
                    onDismiss,
                    Modifier.fillMaxWidth().focusRequester(closeRequester),
                ) { Text("閉じる") }
            }
        }
    }
}

@Composable
fun DefaultHomeGuideDialog(
    environment: HomeEnvironment,
    isDefaultHome: Boolean,
    onOpenHomeSettings: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = KomorebiTheme.colors
    val qrBitmap = rememberQrBitmap(LauncherEnvironmentDetector.TV_TWEAKS_URL, 170)
    val primaryRequester = remember { FocusRequester() }
    val closeRequester = remember { FocusRequester() }

    // ダイアログ表示直後はノードが未 attach のことがあるため、リトライ付きで
    // 確実にダイアログ内へフォーカスを移す。失敗すると背面の設定リストに
    // フォーカスが残り、操作を奪われたように見える。
    LaunchedEffect(environment) {
        delay(120)
        if (environment == HomeEnvironment.ANDROID_TV) {
            primaryRequester.safeRequestFocusWithRetry("DefaultHomeGuide_primary")
        } else {
            closeRequester.safeRequestFocusWithRetry("DefaultHomeGuide_close")
        }
    }

    val environmentLabel = when (environment) {
        HomeEnvironment.ANDROID_TV -> "Android TV"
        HomeEnvironment.GOOGLE_TV -> "Google TV"
        HomeEnvironment.UNKNOWN -> "不明なデバイス"
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(DialogBackdropScrim)
            .focusGroup()
            // ダイアログ外（背面の設定画面）へフォーカスが逃げないようにする。
            .focusProperties { exit = { FocusRequester.Cancel } }
            .onKeyEvent {
                if (it.type == KeyEventType.KeyDown && it.nativeKeyEvent.keyCode == NativeKeyEvent.KEYCODE_BACK) {
                    onDismiss(); true
                } else false
            },
        contentAlignment = Alignment.Center,
    ) {
        // 1080p の Android TV は概ね 540dp 高。横並びレイアウトで画面内に収める。
        val dialogMaxHeight = maxHeight * 0.9f
        val dialogWidth = if (environment == HomeEnvironment.GOOGLE_TV) 640.dp else 560.dp

        ThemedDialogSurface(
            width = dialogWidth,
            modifier = Modifier.heightIn(max = dialogMaxHeight),
        ) {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(vertical = 2.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        AppStrings.DIALOG_DEFAULT_HOME_TITLE,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        "$environmentLabel ・ " + if (isDefaultHome) AppStrings.SETTINGS_VALUE_DEFAULT_HOME_ON
                        else AppStrings.SETTINGS_VALUE_DEFAULT_HOME_OFF,
                        style = MaterialTheme.typography.labelLarge,
                        color = if (isDefaultHome) colors.accent else colors.textSecondary,
                        fontWeight = FontWeight.Bold,
                    )
                }

                when (environment) {
                    HomeEnvironment.ANDROID_TV -> {
                        Text(
                            "システムのホーム設定からこのアプリを既定のホームに指定できます。",
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.textSecondary,
                        )
                        DialogActionButton(
                            DialogActionRole.Primary,
                            onOpenHomeSettings,
                            Modifier.fillMaxWidth().focusRequester(primaryRequester),
                        ) { Text("ホーム設定を開く") }
                    }

                    HomeEnvironment.GOOGLE_TV -> {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(24.dp),
                        ) {
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                Text(
                                    "Google TV は標準設定から変更できないため、tvtweaks などの ADB ツールで設定します。",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = colors.textSecondary,
                                )
                                DialogUrlBox(
                                    url = LauncherEnvironmentDetector.TV_TWEAKS_URL,
                                    label = "ガイドURL",
                                )
                            }
                            DialogQrCard(
                                bitmap = qrBitmap,
                                contentDescription = "tvtweaks QRコード",
                                size = 160.dp,
                            )
                        }
                    }

                    HomeEnvironment.UNKNOWN -> {
                        Text(
                            "この端末ではホームアプリの変更がサポートされていない可能性があります。",
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.textSecondary,
                        )
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp, bottom = 2.dp),
                    horizontalArrangement = Arrangement.End,
                ) {
                    DialogActionButton(
                        DialogActionRole.Secondary,
                        onDismiss,
                        Modifier.focusRequester(closeRequester),
                    ) { Text("閉じる") }
                }
            }
        }
    }
}

@Composable
fun BatchInputDialog(onDismiss: () -> Unit, onConfirm: (String, String) -> Unit) {
    val colors = KomorebiTheme.colors
    var name by remember { mutableStateOf("") }
    var path by remember { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }
    var isClosing by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { delay(150); focusRequester.safeRequestFocus() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(DialogBackdropScrim)
            .focusGroup()
            .focusProperties {
                exit = { if (isClosing) FocusRequester.Default else FocusRequester.Cancel }
            }
            .onKeyEvent {
                if (it.type == KeyEventType.KeyDown && it.nativeKeyEvent.keyCode == NativeKeyEvent.KEYCODE_BACK) {
                    isClosing = true; onDismiss(); true
                } else false
            },
        contentAlignment = Alignment.Center
    ) {
        ThemedDialogSurface(width = 540.dp) {
            Column(
                verticalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                Text(
                    "バッチの登録",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "バッチ名称",
                        style = MaterialTheme.typography.labelMedium,
                        color = colors.textSecondary
                    )
                    DialogTextField(
                        value = name,
                        onValueChange = { name = it },
                        placeholder = "例: エンコード実行",
                        focusRequester = focusRequester
                    )
                }
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "フルパス",
                        style = MaterialTheme.typography.labelMedium,
                        color = colors.textSecondary
                    )
                    DialogTextField(
                        value = path,
                        onValueChange = { path = it },
                        placeholder = "/var/local/edcb/transcode.sh"
                    )
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    DialogActionButton(DialogActionRole.Secondary, { isClosing = true; onDismiss() }, Modifier.weight(1f)) { Text("キャンセル") }
                    DialogActionButton(DialogActionRole.Primary, onClick = {
                            if (name.isNotBlank() && path.isNotBlank()) {
                                isClosing = true
                                onConfirm(name, path)
                            }
                        }, modifier = Modifier.weight(1f),
                        enabled = name.isNotBlank() && path.isNotBlank()
                    ) { Text("追加") }
                }
            }
        }
    }
}

@Composable
fun SmbManualInputDialog(
    target: SmbServer?,
    onDismiss: () -> Unit,
    onConfirm: (SmbServer) -> Unit
) {
    val colors = KomorebiTheme.colors
    var name by remember { mutableStateOf(target?.name ?: "") }
    var ip by remember { mutableStateOf(target?.ip ?: "") }
    var port by remember { mutableStateOf(target?.port ?: "445") }
    var user by remember { mutableStateOf(target?.user ?: "") }
    var password by remember { mutableStateOf(target?.password ?: "") }

    val focusRequester = remember { FocusRequester() }
    val scrollState = rememberScrollState()
    var isClosing by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { delay(150); focusRequester.safeRequestFocus() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(DialogBackdropScrim)
            .focusGroup()
            .focusProperties {
                exit = { if (isClosing) FocusRequester.Default else FocusRequester.Cancel }
            }
            .onKeyEvent {
                if (it.type == KeyEventType.KeyDown && it.nativeKeyEvent.keyCode == NativeKeyEvent.KEYCODE_BACK) {
                    isClosing = true; onDismiss(); true
                } else false
            },
        contentAlignment = Alignment.Center
    ) {
        ThemedDialogSurface(width = 540.dp) {
            Column(
                modifier = Modifier
                    .heightIn(max = 500.dp)
            ) {
                Text(
                    if (target != null) "SMBサーバーの編集" else "SMBサーバーの追加",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 20.dp)
                )

                Column(
                    modifier = Modifier.verticalScroll(scrollState),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            "表示名 (必須)",
                            style = MaterialTheme.typography.labelMedium,
                            color = colors.textSecondary
                        )
                        DialogTextField(
                            value = name,
                            onValueChange = { name = it },
                            placeholder = "例: リビングのNAS",
                            focusRequester = focusRequester
                        )
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            "IPアドレス (必須)",
                            style = MaterialTheme.typography.labelMedium,
                            color = colors.textSecondary
                        )
                        DialogTextField(
                            value = ip,
                            onValueChange = { ip = it },
                            placeholder = "例: 192.168.1.10"
                        )
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            "ポート番号",
                            style = MaterialTheme.typography.labelMedium,
                            color = colors.textSecondary
                        )
                        DialogTextField(
                            value = port,
                            onValueChange = { port = it },
                            placeholder = "例: 445"
                        )
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            "ユーザー名 (空ならゲスト)",
                            style = MaterialTheme.typography.labelMedium,
                            color = colors.textSecondary
                        )
                        DialogTextField(
                            value = user,
                            onValueChange = { user = it },
                            placeholder = "ユーザー名"
                        )
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            "パスワード",
                            style = MaterialTheme.typography.labelMedium,
                            color = colors.textSecondary
                        )
                        DialogTextField(
                            value = password,
                            onValueChange = { password = it },
                            placeholder = "パスワード",
                            isPassword = true
                        )
                    }

                    // ★ 究極の解決策: ボタンをスクロールコンテナの中に移動！
                    // これにより、パスワード欄から「下キー」を押すとシステムが自然にスクロールしてボタンへ移動してくれます。
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        DialogActionButton(DialogActionRole.Secondary, { isClosing = true; onDismiss() }, Modifier.weight(1f)) { Text("キャンセル") }
                        DialogActionButton(DialogActionRole.Primary, onClick = {
                                if (name.isNotBlank() && ip.isNotBlank()) {
                                    isClosing = true
                                    val id = target?.id ?: java.util.UUID.randomUUID().toString()
                                    onConfirm(SmbServer(id, name, ip, port, user, password))
                                }
                            }, modifier = Modifier.weight(1f),
                            enabled = name.isNotBlank() && ip.isNotBlank()
                        ) { Text(if (target != null) "保存" else "追加") }
                    }
                }
            }
        }
    }
}

@Composable
fun SelectionDialog(
    title: String,
    options: List<Pair<String, String>>,
    current: String,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit
) {
    val colors = KomorebiTheme.colors
    val initialIndex = remember(options, current) {
        options.indexOfFirst { it.second == current }.coerceAtLeast(0)
    }
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = initialIndex)

    var isClosing by remember { mutableStateOf(false) }
    val initialFocusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        delay(150)
        initialFocusRequester.safeRequestFocus("SelectionDialog_Initial")
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(DialogBackdropScrim)
            .focusGroup()
            .focusProperties {
                exit = { if (isClosing) FocusRequester.Default else FocusRequester.Cancel }
            }
            .focusRequester(initialFocusRequester)
            .onKeyEvent {
                if (it.nativeKeyEvent.keyCode == android.view.KeyEvent.KEYCODE_BACK && it.type == KeyEventType.KeyUp) {
                    isClosing = true; onDismiss(); return@onKeyEvent true
                }; false
            },
        contentAlignment = Alignment.Center
    ) {
        ThemedDialogSurface(width = 420.dp) {
            Text(
                title,
                style = MaterialTheme.typography.headlineSmall,
                color = colors.textPrimary,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 16.dp)
            )
            LazyColumn(
                state = listState,
                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.heightIn(max = 400.dp)
            ) {
                itemsIndexed(options) { index, (label, value) ->
                    val isSelected = value == current
                    val focusModifier =
                        if (isSelected || (current.isEmpty() && index == 0)) Modifier.focusRequester(
                            initialFocusRequester
                        ) else Modifier
                    SelectionDialogItem(
                        label = label,
                        isSelected = isSelected,
                        onClick = { isClosing = true; onSelect(value) },
                        modifier = focusModifier
                    )
                }

                // ★ 予防対応: LazyColumnの中にボタンを格納
                item {
                    Spacer(Modifier.height(8.dp))
                    DialogActionButton(
                        DialogActionRole.Secondary,
                        onClick = { isClosing = true; onDismiss() },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("キャンセル") }
                }
            }
        }
    }
}

@Composable
fun SelectionDialogItem(
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = KomorebiTheme.colors
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val shape = RoundedCornerShape(TvCardRadiusListRow)

    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().testTag("selection-item-$label"),
        interactionSource = interactionSource,
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (isSelected) colors.accentSoft else Color.Transparent,
            focusedContainerColor = colors.surface2,
            contentColor = colors.textPrimary,
            focusedContentColor = colors.textPrimary
        ),
        border = ClickableSurfaceDefaults.border(
            border = if (isSelected) Border(BorderStroke(1.dp, colors.borderSubtle), shape = shape) else Border.None,
            focusedBorder = Border(BorderStroke(TvCardFocusBorderWidth, colors.accent), shape = shape),
        ),
        shape = ClickableSurfaceDefaults.shape(shape),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.02f)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (isSelected || isFocused) FontWeight.SemiBold else FontWeight.Normal,
                modifier = Modifier.weight(1f),
                color = colors.textPrimary
            )
            if (isSelected) {
                Icon(
                    Icons.Default.Check,
                    null,
                    modifier = Modifier.size(20.dp),
                    tint = colors.accent
                )
            } else Spacer(Modifier.size(20.dp))
        }
    }
}

@Composable
fun MultiSelectionDialog(
    title: String,
    options: List<Pair<String, String>>,
    currentSelections: Set<String>,
    onDismiss: () -> Unit,
    onConfirm: (Set<String>) -> Unit
) {
    val colors = KomorebiTheme.colors
    var selections by remember { mutableStateOf(currentSelections) }
    val listState = rememberLazyListState()
    var isClosing by remember { mutableStateOf(false) }
    val initialFocusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        delay(50); try {
        initialFocusRequester.requestFocus()
    } catch (e: Exception) {
    }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(DialogBackdropScrim)
            .focusGroup()
            .focusProperties {
                exit = { if (isClosing) FocusRequester.Default else FocusRequester.Cancel }
            }
            .onKeyEvent {
                if (it.nativeKeyEvent.keyCode == android.view.KeyEvent.KEYCODE_BACK && it.type == KeyEventType.KeyUp) {
                    isClosing = true; onDismiss(); return@onKeyEvent true
                }; false
            },
        contentAlignment = Alignment.Center
    ) {
        ThemedDialogSurface(width = 460.dp) {
            Text(
                title,
                style = MaterialTheme.typography.headlineSmall,
                color = colors.textPrimary,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 16.dp)
            )
            LazyColumn(
                state = listState,
                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.heightIn(max = 350.dp)
            ) {
                itemsIndexed(options) { index, (label, value) ->
                    val isSelected = selections.contains(value)
                    val focusModifier =
                        if (index == 0) Modifier.focusRequester(initialFocusRequester) else Modifier
                    MultiSelectionDialogItem(
                        label = label,
                        isSelected = isSelected,
                        onClick = {
                            selections =
                                if (isSelected) selections - value else selections + value
                        },
                        modifier = focusModifier
                    )
                }

                // ★ 予防対応: LazyColumnの中にボタンを格納
                item {
                    Spacer(Modifier.height(16.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        DialogActionButton(
                            DialogActionRole.Secondary,
                            onClick = { isClosing = true; onDismiss() },
                            modifier = Modifier.weight(1f),
                        ) { Text("キャンセル") }
                        DialogActionButton(
                            DialogActionRole.Primary,
                            onClick = { isClosing = true; onConfirm(selections) },
                            modifier = Modifier.weight(1f),
                        ) { Text("確定", fontWeight = FontWeight.Bold) }
                    }
                }
            }
        }
    }
}

@Composable
fun MultiSelectionDialogItem(
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = KomorebiTheme.colors
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val shape = RoundedCornerShape(TvCardRadiusListRow)

    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().testTag("multi-selection-item-$label"),
        interactionSource = interactionSource,
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (isSelected) colors.accentSoft else Color.Transparent,
            focusedContainerColor = colors.surface2,
            contentColor = colors.textPrimary,
            focusedContentColor = colors.textPrimary
        ),
        border = ClickableSurfaceDefaults.border(
            border = if (isSelected) Border(BorderStroke(1.dp, colors.borderSubtle), shape = shape) else Border.None,
            focusedBorder = Border(BorderStroke(TvCardFocusBorderWidth, colors.accent), shape = shape),
        ),
        shape = ClickableSurfaceDefaults.shape(shape),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.02f)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = if (isSelected) Icons.Default.CheckBox else Icons.Default.CheckBoxOutlineBlank,
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = if (isSelected) colors.accent else colors.textSecondary
            )
            Spacer(Modifier.width(16.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (isSelected || isFocused) FontWeight.SemiBold else FontWeight.Normal,
                modifier = Modifier.weight(1f),
                color = colors.textPrimary
            )
        }
    }
}

@Composable
fun ConfirmClearDialog(
    title: String,
    message: String,
    confirmButtonText: String = AppStrings.BUTTON_DELETE,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val colors = KomorebiTheme.colors
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(100); focusRequester.safeRequestFocus() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(DialogBackdropScrim)
            .focusGroup()
            .focusProperties { exit = { FocusRequester.Cancel } }
            .onKeyEvent {
                if (it.type == KeyEventType.KeyDown && it.nativeKeyEvent.keyCode == NativeKeyEvent.KEYCODE_BACK) {
                    onDismiss(); true
                } else false
            }, contentAlignment = Alignment.Center
    ) {
        ThemedDialogSurface(width = 420.dp) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    title,
                    style = MaterialTheme.typography.headlineSmall,
                    color = colors.textPrimary,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.textSecondary
                )
                Spacer(modifier = Modifier.height(28.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    DialogActionButton(DialogActionRole.Secondary, onDismiss, Modifier.weight(1f)) { Text(AppStrings.BUTTON_CANCEL) }
                    DialogActionButton(
                        DialogActionRole.Destructive,
                        onConfirm,
                        Modifier.weight(1f).focusRequester(focusRequester),
                    ) { Text(confirmButtonText) }
                }
            }
        }
    }
}

@Composable
fun GeminiSetupDialog(
    currentKey: String,
    serverIp: String,
    onStartServer: () -> Unit,
    onStopServer: () -> Unit,
    onDismiss: () -> Unit,
    onManualInputClick: () -> Unit,
    onDeleteKey: () -> Unit
) {
    val colors = KomorebiTheme.colors
    var isClosing by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }

    DisposableEffect(Unit) { onStartServer(); onDispose { onStopServer() } }
    LaunchedEffect(Unit) { delay(150); focusRequester.safeRequestFocus() }

    val viewModel: SettingsViewModel = hiltViewModel()
    val setupState by viewModel.connectionSetupState.collectAsState()
    val serverUrl = setupState.session?.let { "http://$serverIp:8081/#${it.token}" }
    val qrBitmap = serverUrl?.let { rememberQrBitmap(content = it, size = 300, margin = 1) }
    val isKeySet = currentKey.isNotBlank() && currentKey.startsWith("AIza")

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(DialogBackdropScrim)
            .focusGroup()
            .focusProperties {
                exit = { if (isClosing) FocusRequester.Default else FocusRequester.Cancel }
            }
            .onKeyEvent {
                if (it.nativeKeyEvent.keyCode == android.view.KeyEvent.KEYCODE_BACK && it.type == KeyEventType.KeyUp) {
                    isClosing = true; onDismiss(); return@onKeyEvent true
                }; false
            }, contentAlignment = Alignment.Center
    ) {
        ThemedDialogSurface(width = 680.dp) {
            Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.AutoAwesome,
                        null,
                        tint = colors.accent,
                        modifier = Modifier.size(28.dp)
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        "AIコンシェルジュ連携 (Gemini)",
                        style = MaterialTheme.typography.headlineSmall,
                        color = colors.textPrimary,
                        fontWeight = FontWeight.Bold
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(28.dp),
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                if (isKeySet) Icons.Default.CheckCircle else Icons.Default.Warning,
                                null,
                                tint = if (isKeySet) Color(0xFF34A853) else Color(0xFFFFB300),
                                modifier = Modifier.size(22.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                if (isKeySet) "設定済み" else "未設定",
                                style = MaterialTheme.typography.titleMedium,
                                color = colors.textPrimary,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Text(
                            "同じネットワークの端末で読み取るか、URLを開いてAPIキーを送信",
                            color = colors.textSecondary,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        DialogUrlBox(
                            url = serverUrl ?: "設定ページを準備しています…",
                            label = "設定URL",
                        )
                    }
                    DialogQrCard(
                        bitmap = qrBitmap,
                        contentDescription = "QR Code",
                        size = 212.dp,
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    DialogActionButton(
                        DialogActionRole.Secondary,
                        onClick = { isClosing = true; onDismiss() },
                        modifier = Modifier.focusRequester(focusRequester)
                    ) { Text(if (isKeySet) "閉じる" else "キャンセル") }
                    Spacer(Modifier.width(16.dp))
                    if (isKeySet) {
                        DialogActionButton(
                            DialogActionRole.Destructive,
                            onClick = { isClosing = true; onDeleteKey() },
                        ) { Text("連携を解除") }
                    } else {
                        DialogActionButton(
                            DialogActionRole.Primary,
                            onClick = { isClosing = true; onManualInputClick() },
                        ) { Text("手動で入力") }
                    }
                }
            }
        }
    }
}

@Composable
fun rememberQrBitmap(content: String, size: Int, margin: Int = 1): androidx.compose.ui.graphics.ImageBitmap? {
    return remember(content, size, margin) {
        try {
            val bitMatrix = QRCodeWriter().encode(
                content,
                BarcodeFormat.QR_CODE,
                size,
                size,
                mapOf(EncodeHintType.MARGIN to margin)
            )
            val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565)
            for (x in 0 until size) for (y in 0 until size) bmp.setPixel(
                x,
                y,
                if (bitMatrix.get(
                        x,
                        y
                    )
                ) android.graphics.Color.BLACK else android.graphics.Color.WHITE
            )
            bmp.asImageBitmap()
        } catch (e: Exception) {
            null
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun SettingToggleItem(
    title: String,
    description: String,
    icon: ImageVector,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = KomorebiTheme.colors
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val focusedBgColor = MaterialTheme.colorScheme.primary
    val focusedTextColor = dialogForeground(focusedBgColor)

    Surface(
        onClick = { onCheckedChange(!checked) },
        interactionSource = interactionSource,
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.Transparent,
            focusedContainerColor = focusedBgColor,
            focusedContentColor = focusedTextColor
        ),
        shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(8.dp)),
        modifier = modifier.fillMaxWidth()
    ) {
        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(32.dp),
                tint = if (isFocused) focusedTextColor else colors.textPrimary
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (isFocused) focusedTextColor else colors.textPrimary
                )
                if (description.isNotEmpty()) {
                    Text(
                        text = description,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (isFocused) focusedTextColor.copy(alpha = 0.8f) else colors.textSecondary
                    )
                }
            }
            Spacer(modifier = Modifier.width(16.dp))
            Icon(
                imageVector = if (checked) Icons.Default.CheckBox else Icons.Default.CheckBoxOutlineBlank,
                contentDescription = null,
                modifier = Modifier.size(32.dp),
                tint = if (isFocused) focusedTextColor else if (checked) colors.accent else colors.textSecondary
            )
        }
    }
}
