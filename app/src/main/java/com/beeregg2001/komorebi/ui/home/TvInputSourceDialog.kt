package com.beeregg2001.komorebi.ui.home

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.tv.TvInputInfo
import android.media.tv.TvInputManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Input
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SettingsInputHdmi
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.beeregg2001.komorebi.common.safeRequestFocusWithRetry
import com.beeregg2001.komorebi.data.model.Channel
import com.beeregg2001.komorebi.data.repository.tif.TifInput
import com.beeregg2001.komorebi.data.repository.tif.TifRepository
import com.beeregg2001.komorebi.ui.components.DialogActionButton
import com.beeregg2001.komorebi.ui.components.DialogActionRole
import com.beeregg2001.komorebi.ui.theme.KomorebiTheme
import kotlinx.coroutines.CancellationException

private fun isAirPlayInput(context: Context, info: TvInputInfo): Boolean {
    val id = info.id.lowercase()
    val label = TifInput.label(context, info).lowercase()
    return id.contains("airplay") || label.contains("airplay")
}

private fun resolveAirPlayLaunchIntent(context: Context): Intent? {
    val pm = context.packageManager
    val candidates = listOf(
        Intent(Intent.ACTION_MAIN).apply {
            component = ComponentName(
                "com.mediatek.partner.airplay_sys",
                "com.mediatek.partner.airplay_sys.AirPlayAwarenessActivity",
            )
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        },
        Intent("Show.Settings.AirplayAPK").apply {
            setPackage("com.mediatek.partner.airplay_sys")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        },
    )
    return candidates.firstOrNull { pm.resolveActivity(it, 0) != null }
}

private fun passthroughIcon(info: TvInputInfo?): ImageVector = when (info?.type) {
    TvInputInfo.TYPE_HDMI -> Icons.Default.SettingsInputHdmi
    else -> Icons.Default.Input
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun TvInputSourceDialog(
    onOpenChannel: (Channel) -> Unit,
    onSystemPicker: (() -> Unit)?,
    onDismiss: () -> Unit,
    hideEmptyInputs: Boolean = true,
    onReturnToHonorebi: (() -> Unit)? = null,
) {
    val colors = KomorebiTheme.colors
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var generation by remember { mutableIntStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    val repository = remember(context) { TifRepository(context.applicationContext) }
    var tunerChannels by remember { mutableStateOf<Map<String, List<Channel>>>(emptyMap()) }
    val inputs = remember(generation) { TifInput.inputs(context) }
    val inputsById = remember(inputs) { inputs.associateBy { it.id } }
    val tvInputManager = remember(context) { context.getSystemService(TvInputManager::class.java) }
    val inputStates = remember(generation, inputs) {
        inputs.associate { info ->
            info.id to runCatching { tvInputManager?.getInputState(info.id) }.getOrNull()
        }
    }
    val ports = remember(inputs) { repository.passthroughChannels(inputs) }
    val airPlayLaunchIntent = remember(generation) { resolveAirPlayLaunchIntent(context) }
    val hasAirPlay = remember(inputs, airPlayLaunchIntent) {
        airPlayLaunchIntent != null || inputs.any { isAirPlayInput(context, it) }
    }
    val tunerInputs = remember(inputs) {
        inputs.filterNot { it.isPassthroughInput || isAirPlayInput(context, it) }
    }
    val visibleTunerInputs = remember(tunerInputs, tunerChannels, hideEmptyInputs, loading) {
        if (hideEmptyInputs) tunerInputs.filter { tunerChannels[it.id].orEmpty().isNotEmpty() }
        else tunerInputs
    }
    val granted = remember(generation) {
        ContextCompat.checkSelfPermission(context, TifInput.READ_LISTINGS_PERMISSION) == PackageManager.PERMISSION_GRANTED
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { generation++ }
    val firstItemFocus = remember { FocusRequester() }
    val refreshFocus = remember { FocusRequester() }
    var hasRequestedInitialFocus by remember { mutableStateOf(false) }
    var autoPromptedPermission by remember { mutableStateOf(false) }

    LaunchedEffect(granted, tunerInputs.isNotEmpty()) {
        if (!granted && tunerInputs.isNotEmpty() && !autoPromptedPermission) {
            autoPromptedPermission = true
            permission.launch(TifInput.READ_LISTINGS_PERMISSION)
        }
    }

    DisposableEffect(lifecycle, tvInputManager) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) generation++ }
        lifecycle.addObserver(observer)
        val callback = object : TvInputManager.TvInputCallback() {
            override fun onInputStateChanged(inputId: String, state: Int) { generation++ }
            override fun onInputAdded(inputId: String) { generation++ }
            override fun onInputRemoved(inputId: String) { generation++ }
            override fun onInputUpdated(inputId: String) { generation++ }
        }
        tvInputManager?.registerCallback(callback, Handler(Looper.getMainLooper()))
        onDispose {
            lifecycle.removeObserver(observer)
            tvInputManager?.unregisterCallback(callback)
        }
    }
    LaunchedEffect(generation) {
        loading = true
        error = null
        try {
            if (granted) tunerChannels = repository.tunerInputChoices(inputs)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = "TV入力を取得できません: ${e.message}"
        } finally {
            loading = false
        }
    }

    val hasAnySelectableItem = (onReturnToHonorebi != null) ||
        ports.isNotEmpty() ||
        (hasAirPlay && airPlayLaunchIntent != null) ||
        (!granted && tunerInputs.isNotEmpty()) ||
        visibleTunerInputs.any { tunerChannels[it.id].orEmpty().isNotEmpty() }

    LaunchedEffect(loading, hasAnySelectableItem) {
        if (!loading && !hasRequestedInitialFocus) {
            hasRequestedInitialFocus = true
            kotlinx.coroutines.delay(60)
            val target = if (hasAnySelectableItem) firstItemFocus else refreshFocus
            target.safeRequestFocusWithRetry("TvInputSourceDrawer")
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.42f)),
        ) {
            // Right-edge full-height TV Input Switcher drawer (zero blur, flat dark theme)
            Column(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .width(296.dp)
                    .fillMaxHeight()
                    .background(colors.surface1)
                    .focusProperties {
                        left = FocusRequester.Cancel
                        right = FocusRequester.Cancel
                    }
                    .focusGroup(),
            ) {
                Row(
                    modifier = Modifier.fillMaxSize(),
                ) {
                    Box(
                        modifier = Modifier
                            .width(1.dp)
                            .fillMaxHeight()
                            .background(colors.borderSubtle),
                    )
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .padding(horizontal = 16.dp, vertical = 20.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.padding(horizontal = 4.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Default.SettingsInputHdmi,
                                contentDescription = null,
                                tint = colors.accent,
                                modifier = Modifier.size(22.dp),
                            )
                            Text(
                                text = "入力切替",
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.Bold,
                                color = colors.textPrimary,
                            )
                        }

                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f),
                            contentPadding = PaddingValues(vertical = 4.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            if (loading && ports.isEmpty() && !(hasAirPlay && airPlayLaunchIntent != null) && tunerChannels.isEmpty()) {
                                item(key = "loading") {
                                    Text(
                                        text = "TV入力を読み込み中…",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = colors.textSecondary,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 8.dp),
                                    )
                                }
                            }

                            error?.let { message ->
                                item(key = "error") {
                                    Text(
                                        text = message,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = colors.danger,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .background(colors.danger.copy(alpha = 0.12f), RoundedCornerShape(10.dp))
                                            .padding(horizontal = 12.dp, vertical = 10.dp),
                                    )
                                }
                            }

                            if (!loading && ports.isEmpty() && !hasAirPlay && visibleTunerInputs.isEmpty()) {
                                item(key = "empty") {
                                    Text(
                                        text = "利用可能なTV入力がありません。",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = colors.textSecondary,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 8.dp),
                                    )
                                }
                            }

                            var firstAssigned = false

                            if (onReturnToHonorebi != null) {
                                item(key = "honorebi_home") {
                                    val isFirst = !firstAssigned
                                    if (isFirst) firstAssigned = true
                                    TvInputDrawerRow(
                                        label = "Honorebi",
                                        icon = Icons.Default.Home,
                                        onClick = onReturnToHonorebi,
                                        modifier = if (isFirst) Modifier.focusRequester(firstItemFocus) else Modifier,
                                    )
                                }
                            }

                            if (hasAirPlay && airPlayLaunchIntent != null) {
                                item(key = "airplay_item") {
                                    val isFirst = !firstAssigned
                                    if (isFirst) firstAssigned = true
                                    TvInputDrawerRow(
                                        label = "AirPlay",
                                        icon = Icons.Default.Cast,
                                        onClick = {
                                            runCatching { context.startActivity(airPlayLaunchIntent) }
                                                .onSuccess { onDismiss() }
                                                .onFailure { error = "AirPlay画面を開けませんでした" }
                                        },
                                        modifier = if (isFirst) Modifier.focusRequester(firstItemFocus) else Modifier,
                                    )
                                }
                            }

                            if (ports.isNotEmpty()) {
                                itemsIndexed(ports, key = { _, ch -> ch.id }) { idx, channel ->
                                    val isFirst = !firstAssigned && idx == 0
                                    if (isFirst) firstAssigned = true
                                    val rawInputId = Uri.decode(channel.id.removePrefix("tif:input:"))
                                    val state = inputStates[rawInputId]
                                    val isConnected = state == TvInputManager.INPUT_STATE_CONNECTED
                                    TvInputDrawerRow(
                                        label = channel.name,
                                        icon = passthroughIcon(inputsById[rawInputId]),
                                        statusBadge = if (isConnected) "接続中" else null,
                                        isConnected = isConnected,
                                        onClick = { onOpenChannel(channel) },
                                        modifier = if (isFirst) Modifier.focusRequester(firstItemFocus) else Modifier,
                                    )
                                }
                            }

                            if (!granted && tunerInputs.isNotEmpty()) {
                                item(key = "permission") {
                                    val isFirst = !firstAssigned
                                    if (isFirst) firstAssigned = true
                                    TvInputDrawerRow(
                                        label = "TV番組一覧を許可",
                                        icon = Icons.Default.LockOpen,
                                        statusBadge = "未許可",
                                        onClick = { permission.launch(TifInput.READ_LISTINGS_PERMISSION) },
                                        modifier = if (isFirst) Modifier.focusRequester(firstItemFocus) else Modifier,
                                    )
                                }
                            }

                            for (input in visibleTunerInputs) {
                                val matches = tunerChannels[input.id].orEmpty()
                                item(key = "tuner_header_${input.id}") {
                                    Column {
                                        DrawerSectionLabel(TifInput.label(context, input))
                                        if (granted && !loading && error == null && matches.isEmpty()) {
                                            Text(
                                                text = "チャンネル未登録",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = colors.textMuted,
                                                modifier = Modifier.padding(start = 6.dp, top = 2.dp),
                                            )
                                        }
                                    }
                                }
                                itemsIndexed(matches, key = { _, ch -> ch.id }) { idx, channel ->
                                    val isFirst = !firstAssigned && idx == 0
                                    if (isFirst) firstAssigned = true
                                    TvInputDrawerRow(
                                        label = channel.name,
                                        icon = Icons.Default.LiveTv,
                                        onClick = { onOpenChannel(channel) },
                                        modifier = if (isFirst) Modifier.focusRequester(firstItemFocus) else Modifier,
                                    )
                                }
                            }
                        }

                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(1.dp)
                                .background(colors.borderSubtle),
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            DrawerFooterAction(
                                label = "再読み込み",
                                icon = Icons.Default.Refresh,
                                onClick = { generation++ },
                                modifier = Modifier
                                    .weight(1f)
                                    .focusRequester(refreshFocus),
                            )
                            if (onSystemPicker != null) {
                                DrawerFooterAction(
                                    label = "端末設定",
                                    icon = Icons.Default.Input,
                                    onClick = onSystemPicker,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DrawerSectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = KomorebiTheme.colors.textSecondary,
        modifier = Modifier.padding(start = 6.dp, top = 6.dp, bottom = 2.dp),
    )
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun TvInputDrawerRow(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    statusBadge: String? = null,
    isConnected: Boolean = false,
) {
    val colors = KomorebiTheme.colors
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val shape = RoundedCornerShape(12.dp)

    Surface(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 3.dp, vertical = 2.dp),
        interactionSource = interactionSource,
        shape = ClickableSurfaceDefaults.shape(shape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = colors.surface2,
            focusedContainerColor = colors.accentSoft,
            pressedContainerColor = colors.accentSoft,
            contentColor = colors.textPrimary,
            focusedContentColor = colors.textPrimary,
            pressedContentColor = colors.textPrimary,
        ),
        border = ClickableSurfaceDefaults.border(
            border = Border(BorderStroke(1.dp, colors.borderSubtle), shape = shape),
            focusedBorder = Border(BorderStroke(2.dp, colors.accent), shape = shape),
            pressedBorder = Border(BorderStroke(2.dp, colors.accent), shape = shape),
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.0f),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (isFocused || isConnected) colors.accent else colors.textSecondary,
                modifier = Modifier.size(20.dp),
            )
            Text(
                text = label,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = if (isFocused) FontWeight.Bold else FontWeight.Medium,
                color = colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (statusBadge != null) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                    modifier = Modifier
                        .background(
                            color = if (isConnected) colors.accentSoft else colors.surface1,
                            shape = RoundedCornerShape(6.dp),
                        )
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                ) {
                    if (isConnected) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .background(colors.accent, CircleShape),
                        )
                    }
                    Text(
                        text = statusBadge,
                        style = MaterialTheme.typography.labelMedium,
                        color = if (isConnected) colors.accent else colors.textMuted,
                    )
                }
            }
            Box(
                modifier = Modifier.width(18.dp),
                contentAlignment = Alignment.Center,
            ) {
                if (isFocused) {
                    Icon(
                        imageVector = Icons.Default.ChevronRight,
                        contentDescription = null,
                        tint = colors.accent,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun DrawerFooterAction(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = KomorebiTheme.colors
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val shape = RoundedCornerShape(10.dp)

    Surface(
        onClick = onClick,
        modifier = modifier.padding(2.dp),
        interactionSource = interactionSource,
        shape = ClickableSurfaceDefaults.shape(shape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = colors.surface2,
            focusedContainerColor = colors.accentSoft,
            pressedContainerColor = colors.accentSoft,
            contentColor = colors.textSecondary,
            focusedContentColor = colors.textPrimary,
            pressedContentColor = colors.textPrimary,
        ),
        border = ClickableSurfaceDefaults.border(
            border = Border(BorderStroke(1.dp, colors.borderSubtle), shape = shape),
            focusedBorder = Border(BorderStroke(2.dp, colors.accent), shape = shape),
            pressedBorder = Border(BorderStroke(2.dp, colors.accent), shape = shape),
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.0f),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (isFocused) colors.accent else colors.textSecondary,
                modifier = Modifier.size(16.dp),
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                color = colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
