package com.beeregg2001.komorebi.ui.home

import android.content.pm.PackageManager
import android.media.tv.TvInputManager
import android.os.Handler
import android.os.Looper
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.SettingsInputHdmi
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
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
import com.beeregg2001.komorebi.ui.components.ThemedDialogSurface
import com.beeregg2001.komorebi.ui.theme.KomorebiTheme
import kotlinx.coroutines.CancellationException

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun TvInputSourceDialog(
    onOpenChannel: (Channel) -> Unit,
    onSystemPicker: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    val colors = KomorebiTheme.colors
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var generation by remember { mutableIntStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    val repository = remember(context) { TifRepository(context.applicationContext) }
    var tunerChannels by remember(generation) { mutableStateOf<Map<String, List<Channel>>>(emptyMap()) }
    val inputs = remember(generation) { TifInput.inputs(context) }
    val tvInputManager = remember(context) { context.getSystemService(TvInputManager::class.java) }
    val inputStates = remember(generation, inputs) {
        inputs.associate { info ->
            info.id to runCatching { tvInputManager?.getInputState(info.id) }.getOrNull()
        }
    }
    val ports = remember(inputs) { repository.passthroughChannels(inputs) }
    val tunerInputs = remember(inputs) { inputs.filterNot { it.isPassthroughInput } }
    val granted = remember(generation) {
        ContextCompat.checkSelfPermission(context, TifInput.READ_LISTINGS_PERMISSION) == PackageManager.PERMISSION_GRANTED
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { generation++ }
    val firstItemFocus = remember { FocusRequester() }
    val closeFocus = remember { FocusRequester() }
    var hasRequestedInitialFocus by remember { mutableStateOf(false) }

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

    val hasAnySelectableItem = ports.isNotEmpty() || tunerInputs.any { tunerChannels[it.id].orEmpty().isNotEmpty() }
    LaunchedEffect(loading, hasAnySelectableItem) {
        if (!loading && !hasRequestedInitialFocus) {
            hasRequestedInitialFocus = true
            val target = if (hasAnySelectableItem) firstItemFocus else closeFocus
            target.safeRequestFocusWithRetry("TvInputSourceDialog")
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        ThemedDialogSurface(width = 580.dp) {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(
                    text = "入力切替",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = colors.textPrimary,
                )

                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 360.dp),
                    contentPadding = PaddingValues(vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (loading) {
                        item(key = "loading") {
                            Text(
                                text = "TV入力を読み込み中…",
                                style = MaterialTheme.typography.bodyMedium,
                                color = colors.textSecondary,
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
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
                                    .padding(horizontal = 14.dp, vertical = 10.dp),
                            )
                        }
                    }

                    if (!loading && inputs.isEmpty()) {
                        item(key = "empty") {
                            Text(
                                text = "TV入力がありません。端末の設定で入力を追加してください。",
                                style = MaterialTheme.typography.bodyMedium,
                                color = colors.textSecondary,
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
                            )
                        }
                    }

                    if (!granted && tunerInputs.isNotEmpty()) {
                        item(key = "permission") {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(colors.surface2, RoundedCornerShape(12.dp))
                                    .border(1.dp, colors.borderSubtle, RoundedCornerShape(12.dp))
                                    .padding(14.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                Text(
                                    text = "チューナーのチャンネル一覧を表示するには、TV番組一覧へのアクセス許可が必要です。",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = colors.textSecondary,
                                )
                                DialogActionButton(
                                    role = DialogActionRole.Primary,
                                    onClick = { permission.launch(TifInput.READ_LISTINGS_PERMISSION) },
                                ) { Text("TV番組一覧を許可") }
                            }
                        }
                    }

                    var firstAssigned = false

                    if (ports.isNotEmpty()) {
                        item(key = "ports_header") {
                            Text(
                                text = "外部入力",
                                style = MaterialTheme.typography.titleSmall,
                                color = colors.textSecondary,
                                modifier = Modifier.padding(start = 4.dp, top = 4.dp, bottom = 2.dp),
                            )
                        }
                        itemsIndexed(ports, key = { _, ch -> ch.id }) { idx, channel ->
                            val isFirst = !firstAssigned && idx == 0
                            if (isFirst) firstAssigned = true
                            val rawInputId = android.net.Uri.decode(channel.id.removePrefix("tif:input:"))
                            val stateBadge = when (inputStates[rawInputId]) {
                                TvInputManager.INPUT_STATE_CONNECTED -> "接続中"
                                TvInputManager.INPUT_STATE_CONNECTED_STANDBY -> "待機中"
                                TvInputManager.INPUT_STATE_DISCONNECTED -> "未接続"
                                else -> null
                            }
                            TvInputChoiceRow(
                                label = channel.name,
                                icon = Icons.Default.SettingsInputHdmi,
                                statusBadge = stateBadge,
                                isConnected = inputStates[rawInputId] == TvInputManager.INPUT_STATE_CONNECTED,
                                onClick = { onOpenChannel(channel) },
                                modifier = if (isFirst) Modifier.focusRequester(firstItemFocus) else Modifier,
                            )
                        }
                    }

                    for (input in tunerInputs) {
                        val matches = tunerChannels[input.id].orEmpty()
                        item(key = "tuner_header_${input.id}") {
                            Column(modifier = Modifier.padding(start = 4.dp, top = 8.dp, bottom = 2.dp)) {
                                Text(
                                    text = TifInput.label(context, input),
                                    style = MaterialTheme.typography.titleSmall,
                                    color = colors.textSecondary,
                                )
                                if (granted && !loading && error == null && matches.isEmpty()) {
                                    Text(
                                        text = "チャンネルがありません（設定でスキャンしてください）",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = colors.textMuted,
                                        modifier = Modifier.padding(top = 2.dp),
                                    )
                                }
                            }
                        }
                        itemsIndexed(matches, key = { _, ch -> ch.id }) { idx, channel ->
                            val isFirst = !firstAssigned && idx == 0
                            if (isFirst) firstAssigned = true
                            TvInputChoiceRow(
                                label = channel.name,
                                icon = Icons.Default.LiveTv,
                                onClick = { onOpenChannel(channel) },
                                modifier = if (isFirst) Modifier.focusRequester(firstItemFocus) else Modifier,
                            )
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
                ) {
                    if (onSystemPicker != null) {
                        DialogActionButton(
                            role = DialogActionRole.Secondary,
                            onClick = onSystemPicker,
                        ) { Text("端末の入力切替") }
                    }
                    DialogActionButton(
                        role = DialogActionRole.Secondary,
                        onClick = { generation++ },
                    ) { Text("再読み込み") }
                    DialogActionButton(
                        role = DialogActionRole.Secondary,
                        onClick = onDismiss,
                        modifier = Modifier.focusRequester(closeFocus),
                    ) { Text("閉じる") }
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun TvInputChoiceRow(
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
            contentColor = colors.textPrimary,
            focusedContentColor = colors.textPrimary,
        ),
        border = ClickableSurfaceDefaults.border(
            border = Border(BorderStroke(1.dp, colors.borderSubtle), shape = shape),
            focusedBorder = Border(BorderStroke(2.dp, colors.accent), shape = shape),
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.0f),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (isFocused) colors.accent else colors.textSecondary,
                modifier = Modifier.size(20.dp),
            )
            Text(
                text = label,
                style = MaterialTheme.typography.titleMedium,
                color = colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (statusBadge != null) {
                Text(
                    text = statusBadge,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (isConnected) colors.accent else colors.textMuted,
                    modifier = Modifier
                        .background(
                            color = if (isConnected) colors.accentSoft else colors.surface1,
                            shape = RoundedCornerShape(6.dp),
                        )
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                )
            }
            if (isFocused) {
                Icon(
                    imageVector = Icons.Default.ChevronRight,
                    contentDescription = null,
                    tint = colors.accent,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}
