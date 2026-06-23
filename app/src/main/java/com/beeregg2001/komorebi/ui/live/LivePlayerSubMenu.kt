@file:OptIn(ExperimentalComposeUiApi::class, ExperimentalTvMaterial3Api::class)

package com.beeregg2001.komorebi.ui.live

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.*
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.*
import com.beeregg2001.komorebi.common.AppStrings
import com.beeregg2001.komorebi.data.model.AudioMode
import com.beeregg2001.komorebi.data.model.Channel
import kotlinx.coroutines.delay
import com.beeregg2001.komorebi.data.model.StreamQuality
import com.beeregg2001.komorebi.data.model.StreamSource
import com.beeregg2001.komorebi.ui.theme.KomorebiTheme

enum class LiveSubMenuCategory {
    QUICK_CHANNELS, AUDIO, QUALITY, SOURCE
}

@Composable
fun LiveTopSubMenuUI(
    mainBackendType: String,
    currentStreamSource: StreamSource,
    isEdcbDirect: Boolean,
    availableSources: List<StreamSource>,
    currentAudioMode: AudioMode,
    isSubtitleEnabled: Boolean,
    currentQuality: StreamQuality,
    isCommentEnabled: Boolean,
    isLCropEnabled: Boolean,
    isRecording: Boolean,
    canStartChasePlayback: Boolean,
    isSignalInfoVisible: Boolean,
    isDualDisplayMode: Boolean,
    groupedChannels: Map<String, List<Channel>>,
    currentChannelId: String,
    onDualDisplayToggle: () -> Unit,
    onSwapScreens: () -> Unit,
    onChannelSelect: (Channel) -> Unit,
    onChasePlayback: () -> Unit,
    onRecordToggle: () -> Unit,
    onSignalInfoToggle: () -> Unit,
    focusRequester: FocusRequester,
    logoUrls: Map<String, String>,
    shouldCropLogo: Boolean,
    onSourceSelect: (StreamSource, Boolean) -> Unit,
    onAudioToggle: () -> Unit,
    onSubtitleToggle: () -> Unit,
    onQualitySelect: (StreamQuality) -> Unit,
    onCommentToggle: () -> Unit,
    onLCropToggle: () -> Unit,
    onCloseMenu: () -> Unit,
    availableQualities: List<StreamQuality> = StreamQuality.DEFAULT_QUALITIES
) {
    val colors = KomorebiTheme.colors
    var selectedCategory by remember { mutableStateOf<LiveSubMenuCategory?>(null) }
    val listFocusRequester = remember { FocusRequester() }
    val quickChannelButtonRequester = remember { FocusRequester() }
    val mainQualityButtonRequester = remember { FocusRequester() }
    val mainSourceButtonRequester = remember { FocusRequester() }

    // ★ 修正: name を value に変更して Unresolved reference エラーを解消
    val effectiveQualities = remember(isDualDisplayMode, availableQualities) {
        if (isDualDisplayMode) {
            availableQualities.filter { !it.value.contains("1080") && !it.label.contains("1080") }
        } else {
            availableQualities
        }
    }

    // ★ 修正: name を value に変更して Unresolved reference エラーを解消
    val effectiveQuality = remember(currentQuality, isDualDisplayMode, effectiveQualities) {
        if (isDualDisplayMode && (currentQuality.value.contains("1080") || currentQuality.label.contains(
                "1080"
            ))
        ) {
            effectiveQualities.find { it.value.contains("720") || it.label.contains("720") }
                ?: effectiveQualities.firstOrNull()
                ?: currentQuality
        } else {
            currentQuality
        }
    }

    LaunchedEffect(Unit) {
        delay(50)
        try {
            quickChannelButtonRequester.requestFocus()
        } catch (e: Exception) {
            try {
                focusRequester.requestFocus()
            } catch (e: Exception) {
            }
        }
    }

    LaunchedEffect(selectedCategory) {
        if (selectedCategory != null) {
            delay(100)
            try {
                listFocusRequester.requestFocus()
            } catch (e: Exception) {
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .wrapContentHeight()
            .background(
                Brush.verticalGradient(
                    colors = listOf(colors.background.copy(alpha = 0.9f), Color.Transparent)
                )
            )
            .padding(top = 24.dp, bottom = 48.dp)
            .onKeyEvent { keyEvent ->
                if (keyEvent.type == KeyEventType.KeyDown &&
                    (keyEvent.nativeKeyEvent.keyCode == android.view.KeyEvent.KEYCODE_BACK ||
                            keyEvent.nativeKeyEvent.keyCode == android.view.KeyEvent.KEYCODE_ESCAPE)
                ) {
                    if (selectedCategory != null) {
                        val targetRequester = when (selectedCategory) {
                            LiveSubMenuCategory.QUICK_CHANNELS -> quickChannelButtonRequester
                            LiveSubMenuCategory.QUALITY -> mainQualityButtonRequester
                            LiveSubMenuCategory.SOURCE -> mainSourceButtonRequester
                            else -> focusRequester
                        }
                        selectedCategory = null
                        try {
                            targetRequester.requestFocus()
                        } catch (e: Exception) {
                        }
                        true
                    } else {
                        onCloseMenu()
                        true
                    }
                } else false
            },
        contentAlignment = Alignment.TopCenter
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth()
        ) {
            val sourceSubtitle = when {
                currentStreamSource == StreamSource.EDCB && isEdcbDirect -> "EDCB (TCP)"
                currentStreamSource == StreamSource.EDCB && !isEdcbDirect -> "EDCB (トランスコード)"
                currentStreamSource == StreamSource.MIRAKURUN -> "Mirakurun"
                else -> "KonomiTV"
            }

            val activeSourceLabel = when {
                currentStreamSource == StreamSource.EDCB && isEdcbDirect -> "EDCB (TCPダイレクト)"
                currentStreamSource == StreamSource.EDCB && !isEdcbDirect -> "EDCB (トランスコード)"
                currentStreamSource == StreamSource.MIRAKURUN -> "Mirakurun"
                else -> "KonomiTV"
            }

            val grQuickChannels = remember(groupedChannels) {
                groupedChannels["GR"].orEmpty()
                    .filter { it.isDisplay && it.isWatchable && !it.is_subchannel }
                    .sortedWith(compareBy<Channel> { it.remocon_Id.takeIf { id -> id > 0 } ?: Int.MAX_VALUE }
                        .thenBy { it.channelNumber })
                    .take(10)
            }
            val animeChannels = remember(groupedChannels) {
                groupedChannels.values.flatten()
                    .filter { it.isDisplay && it.isWatchable && !it.is_subchannel && it.isAnimeNow() }
                    .distinctBy { it.id }
                    .sortedWith(compareBy<Channel> { if (it.type == "GR") 0 else 1 }
                        .thenBy { it.remocon_Id.takeIf { id -> id > 0 } ?: Int.MAX_VALUE }
                        .thenBy { it.channelNumber })
                    .take(12)
            }

            // --- メインタイルメニュー行 ---
            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally),
                modifier = Modifier
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 32.dp, vertical = 8.dp)
            ) {
                LiveMenuTileItem(
                    title = "クイック選局", icon = Icons.Default.Tv,
                    subtitle = "常用 / アニメ",
                    onClick = {
                        selectedCategory =
                            if (selectedCategory == LiveSubMenuCategory.QUICK_CHANNELS) null else LiveSubMenuCategory.QUICK_CHANNELS
                    },
                    modifier = Modifier
                        .focusRequester(quickChannelButtonRequester)
                        .focusProperties {
                            if (selectedCategory != LiveSubMenuCategory.QUICK_CHANNELS) down =
                                FocusRequester.Cancel
                        },
                    contentColor = colors.textPrimary
                )

                if (isDualDisplayMode) {
                    LiveMenuTileItem(
                        title = "二画面", icon = Icons.Default.PictureInPicture,
                        subtitle = "終了",
                        onClick = { onDualDisplayToggle(); onCloseMenu() },
                        modifier = Modifier.focusProperties { down = FocusRequester.Cancel },
                        contentColor = colors.textPrimary
                    )

                    LiveMenuTileItem(
                        title = "左右入替", icon = Icons.Default.SwapHoriz,
                        subtitle = "画面を交換",
                        onClick = { onSwapScreens(); onCloseMenu() },
                        modifier = Modifier.focusProperties { down = FocusRequester.Cancel },
                        contentColor = colors.textPrimary
                    )

                    LiveMenuTileItem(
                        title = "字幕", icon = Icons.Default.ClosedCaption,
                        subtitle = if (isSubtitleEnabled) "表示" else "非表示",
                        onClick = onSubtitleToggle,
                        modifier = Modifier.focusProperties { down = FocusRequester.Cancel },
                        contentColor = colors.textPrimary
                    )

                    LiveMenuTileItem(
                        title = "画質", icon = Icons.Default.Settings,
                        subtitle = effectiveQuality.label,
                        onClick = {
                            selectedCategory =
                                if (selectedCategory == LiveSubMenuCategory.QUALITY) null else LiveSubMenuCategory.QUALITY
                        },
                        modifier = Modifier
                            .focusRequester(mainQualityButtonRequester)
                            .focusProperties {
                                if (selectedCategory != LiveSubMenuCategory.QUALITY) down =
                                    FocusRequester.Cancel
                            },
                        contentColor = colors.textPrimary
                    )
                } else {
                    if (isRecording) {
                        LiveMenuTileItem(
                            title = "追いかけ再生",
                            icon = Icons.Default.PlayCircle,
                            subtitle = if (canStartChasePlayback) "録画中番組を再生" else "準備中",
                            onClick = onChasePlayback,
                            modifier = Modifier.focusProperties { down = FocusRequester.Cancel },
                            contentColor = if (canStartChasePlayback) colors.accent else colors.textPrimary.copy(alpha = 0.55f)
                        )
                    }

                    LiveMenuTileItem(
                        title = if (isRecording) "録画停止" else "録画開始",
                        icon = if (isRecording) Icons.Default.StopCircle else Icons.Default.RadioButtonChecked,
                        subtitle = if (isRecording) "録画中" else "番組を録画",
                        onClick = onRecordToggle,
                        modifier = Modifier.focusProperties { down = FocusRequester.Cancel },
                        contentColor = if (isRecording) Color(0xFFFF5252) else colors.textPrimary
                    )

                    LiveMenuTileItem(
                        title = "二画面", icon = Icons.Default.PictureInPicture,
                        subtitle = "開始",
                        onClick = { onDualDisplayToggle(); onCloseMenu() },
                        modifier = Modifier.focusProperties { down = FocusRequester.Cancel },
                        contentColor = colors.textPrimary
                    )

                    LiveMenuTileItem(
                        title = "音声切替", icon = Icons.Default.Audiotrack,
                        subtitle = if (currentAudioMode == AudioMode.MAIN) "主音声" else "副音声",
                        onClick = onAudioToggle,
                        modifier = Modifier.focusProperties { down = FocusRequester.Cancel },
                        contentColor = colors.textPrimary
                    )

                    LiveMenuTileItem(
                        title = "信号情報", icon = Icons.Default.Info,
                        subtitle = if (isSignalInfoVisible) "表示中" else "非表示",
                        onClick = { onSignalInfoToggle(); onCloseMenu() },
                        modifier = Modifier.focusProperties { down = FocusRequester.Cancel },
                        contentColor = colors.textPrimary
                    )

                    LiveMenuTileItem(
                        title = "字幕", icon = Icons.Default.Subtitles,
                        subtitle = if (isSubtitleEnabled) "表示" else "非表示",
                        onClick = onSubtitleToggle,
                        modifier = Modifier.focusProperties { down = FocusRequester.Cancel },
                        contentColor = colors.textPrimary
                    )

                    LiveMenuTileItem(
                        title = "L字クロップ", icon = Icons.Default.Crop,
                        subtitle = if (isLCropEnabled) "有効" else "設定",
                        onClick = onLCropToggle,
                        modifier = Modifier.focusProperties { down = FocusRequester.Cancel },
                        contentColor = if (isLCropEnabled) colors.accent else colors.textPrimary
                    )

                    LiveMenuTileItem(
                        title = "実況コメント", icon = Icons.Default.Chat,
                        subtitle = if (isCommentEnabled) "表示" else "非表示",
                        onClick = onCommentToggle,
                        modifier = Modifier.focusProperties { down = FocusRequester.Cancel },
                        contentColor = colors.textPrimary
                    )

                    LiveMenuTileItem(
                        title = "画質", icon = Icons.Default.HighQuality,
                        subtitle = effectiveQuality.label,
                        onClick = {
                            selectedCategory =
                                if (selectedCategory == LiveSubMenuCategory.QUALITY) null else LiveSubMenuCategory.QUALITY
                        },
                        modifier = Modifier
                            .focusRequester(mainQualityButtonRequester)
                            .focusProperties {
                                if (selectedCategory != LiveSubMenuCategory.QUALITY) down =
                                    FocusRequester.Cancel
                            },
                        contentColor = colors.textPrimary
                    )

                    LiveMenuTileItem(
                        title = "ソース", icon = Icons.Default.CastConnected,
                        subtitle = sourceSubtitle,
                        onClick = {
                            selectedCategory =
                                if (selectedCategory == LiveSubMenuCategory.SOURCE) null else LiveSubMenuCategory.SOURCE
                        },
                        modifier = Modifier
                            .focusRequester(mainSourceButtonRequester)
                            .focusProperties {
                                if (selectedCategory != LiveSubMenuCategory.SOURCE) down =
                                    FocusRequester.Cancel
                            },
                        contentColor = colors.textPrimary
                    )
                }
            }

            // --- 展開メニュー: クイック選局 ---
            AnimatedVisibility(
                visible = selectedCategory == LiveSubMenuCategory.QUICK_CHANNELS,
                enter = EnterTransition.None,
                exit = ExitTransition.None
            ) {
                QuickChannelPanel(
                    grChannels = grQuickChannels,
                    animeChannels = animeChannels,
                    currentChannelId = currentChannelId,
                    onChannelSelect = {
                        onChannelSelect(it)
                        selectedCategory = null
                        onCloseMenu()
                    },
                    logoUrls = logoUrls,
                    shouldCropLogo = shouldCropLogo,
                    focusRequester = listFocusRequester,
                    upRequester = quickChannelButtonRequester
                )
            }

            // --- 展開メニュー: 画質 ---
            AnimatedVisibility(
                visible = selectedCategory == LiveSubMenuCategory.QUALITY,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Spacer(Modifier.height(16.dp))
                    Box(
                        modifier = Modifier
                            .width(400.dp)
                            .height(2.dp)
                            .background(colors.textPrimary.copy(alpha = 0.2f))
                    )
                    Spacer(Modifier.height(16.dp))

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 32.dp, vertical = 8.dp)
                    ) {
                        effectiveQualities.forEachIndexed { index, quality ->
                            val isSelected = effectiveQuality.value == quality.value

                            LiveMenuTileItem(
                                title = quality.label,
                                icon = if (isSelected) Icons.Default.CheckCircle else Icons.Default.Settings,
                                subtitle = if (isSelected) "選択中" else "",
                                onClick = {
                                    onQualitySelect(quality)
                                    selectedCategory = null
                                    try {
                                        mainQualityButtonRequester.requestFocus()
                                    } catch (e: Exception) {
                                    }
                                },
                                width = 160.dp,
                                height = 100.dp,
                                modifier = Modifier
                                    .then(
                                        if (isSelected || (index == 0 && effectiveQualities.none { it.value == effectiveQuality.value })) Modifier.focusRequester(
                                            listFocusRequester
                                        ) else Modifier
                                    )
                                    .focusProperties {
                                        up = mainQualityButtonRequester
                                        down = FocusRequester.Cancel
                                    },
                                contentColor = colors.textPrimary
                            )
                        }
                    }
                }
            }

            // --- 展開メニュー: ソース ---
            AnimatedVisibility(
                visible = selectedCategory == LiveSubMenuCategory.SOURCE,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Spacer(Modifier.height(16.dp))
                    Box(
                        modifier = Modifier
                            .width(400.dp)
                            .height(2.dp)
                            .background(colors.textPrimary.copy(alpha = 0.2f))
                    )
                    Spacer(Modifier.height(16.dp))

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 32.dp, vertical = 8.dp)
                    ) {
                        val sourceOptions = mutableListOf<Pair<String, () -> Unit>>()
                        if (availableSources.contains(StreamSource.KONOMITV)) {
                            sourceOptions.add("KonomiTV" to {
                                onSourceSelect(StreamSource.KONOMITV, false)
                            })
                        }
                        if (availableSources.contains(StreamSource.EDCB)) {
                            if (mainBackendType == "EDCB") {
                                sourceOptions.add("EDCB (トランスコード)" to {
                                    onSourceSelect(StreamSource.EDCB, false)
                                })
                            }
                            sourceOptions.add("EDCB (TCPダイレクト)" to {
                                onSourceSelect(StreamSource.EDCB, true)
                            })
                        }
                        if (availableSources.contains(StreamSource.MIRAKURUN)) {
                            sourceOptions.add("Mirakurun" to {
                                onSourceSelect(StreamSource.MIRAKURUN, false)
                            })
                        }

                        sourceOptions.forEachIndexed { index, (label, action) ->
                            val isSelected = label == activeSourceLabel

                            LiveMenuTileItem(
                                title = label,
                                icon = if (isSelected) Icons.Default.CheckCircle else Icons.Default.Dns,
                                subtitle = if (isSelected) "選択中" else "",
                                onClick = {
                                    action()
                                    selectedCategory = null
                                    try {
                                        mainSourceButtonRequester.requestFocus()
                                    } catch (e: Exception) {
                                    }
                                },
                                width = 160.dp,
                                height = 100.dp,
                                modifier = Modifier
                                    .then(
                                        if (isSelected || (index == 0 && sourceOptions.none { it.first == activeSourceLabel })) Modifier.focusRequester(
                                            listFocusRequester
                                        ) else Modifier
                                    )
                                    .focusProperties {
                                        up = mainSourceButtonRequester
                                        down = FocusRequester.Cancel
                                    },
                                contentColor = colors.textPrimary
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun LiveMenuTileItem(
    title: String,
    icon: ImageVector,
    subtitle: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    width: Dp = 160.dp,
    height: Dp = 100.dp,
    contentColor: Color = Color.White
) {
    val colors = KomorebiTheme.colors
    Surface(
        onClick = onClick,
        enabled = enabled,
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.1f),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = colors.textPrimary.copy(0.1f),
            contentColor = if (enabled) contentColor else colors.textPrimary.copy(0.3f),
            focusedContainerColor = colors.textPrimary,
            focusedContentColor = if (colors.isDark) Color.Black else Color.White
        ),
        shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(12.dp)),
        modifier = modifier
            .size(width, height)
            .alpha(if (enabled) 1f else 0.5f)
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(icon, null, modifier = Modifier.size(28.dp))
            Spacer(Modifier.height(8.dp))
            Text(title, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
            if (subtitle.isNotEmpty()) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                    color = LocalContentColor.current.copy(0.7f)
                )
            }
        }
    }
}

@Composable
private fun QuickChannelPanel(
    grChannels: List<Channel>,
    animeChannels: List<Channel>,
    currentChannelId: String,
    onChannelSelect: (Channel) -> Unit,
    logoUrls: Map<String, String>,
    shouldCropLogo: Boolean,
    focusRequester: FocusRequester,
    upRequester: FocusRequester
) {
    val colors = KomorebiTheme.colors
    val animeFocusRequester = remember { FocusRequester() }
    val animeInitialFocusRequester = if (grChannels.isEmpty()) focusRequester else animeFocusRequester

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(16.dp))
        Box(
            modifier = Modifier
                .width(480.dp)
                .height(2.dp)
                .background(colors.textPrimary.copy(alpha = 0.2f))
        )
        Spacer(Modifier.height(16.dp))

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 32.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            QuickChannelSection(
                title = "GR",
                channels = grChannels,
                currentChannelId = currentChannelId,
                onChannelSelect = onChannelSelect,
                logoUrls = logoUrls,
                shouldCropLogo = shouldCropLogo,
                focusRequester = focusRequester,
                upRequester = upRequester,
                downRequester = if (animeChannels.isNotEmpty()) animeInitialFocusRequester else null,
                modifier = Modifier.fillMaxWidth()
            )
            QuickChannelSection(
                title = "放送中のアニメ",
                channels = animeChannels,
                currentChannelId = currentChannelId,
                onChannelSelect = onChannelSelect,
                logoUrls = logoUrls,
                shouldCropLogo = shouldCropLogo,
                focusRequester = animeInitialFocusRequester,
                upRequester = if (grChannels.isNotEmpty()) focusRequester else upRequester,
                downRequester = null,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun QuickChannelSection(
    title: String,
    channels: List<Channel>,
    currentChannelId: String,
    onChannelSelect: (Channel) -> Unit,
    logoUrls: Map<String, String>,
    shouldCropLogo: Boolean,
    focusRequester: FocusRequester?,
    upRequester: FocusRequester,
    downRequester: FocusRequester?,
    modifier: Modifier = Modifier
) {
    val colors = KomorebiTheme.colors

    Column(modifier = modifier) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = colors.textPrimary,
            modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)
        )

        if (channels.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(90.dp)
                    .background(
                        colors.textPrimary.copy(alpha = 0.08f),
                        RoundedCornerShape(12.dp)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "該当チャンネルなし",
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.textSecondary
                )
            }
        } else {
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                items(channels, key = { it.id }) { channel ->
                    val isSelected = channel.id == currentChannelId
                    val requesterModifier =
                        if ((isSelected || channel == channels.first()) && focusRequester != null) {
                            Modifier.focusRequester(focusRequester)
                        } else {
                            Modifier
                        }

                    ChannelCardItem(
                        channel = channel,
                        isSelected = isSelected,
                        logoUrl = logoUrls.logoUrlFor(channel),
                        shouldCropLogo = shouldCropLogo,
                        onClick = { onChannelSelect(channel) },
                        modifier = requesterModifier.focusProperties {
                            up = upRequester
                            down = downRequester ?: FocusRequester.Cancel
                        }
                    )
                }
            }
        }
    }
}

private fun Channel.isAnimeNow(): Boolean {
    val program = programPresent ?: return false
    val genreHit = program.genres?.any {
        it.major.contains("アニメ") || it.middle.contains("アニメ")
    } == true
    if (genreHit) return true

    val text = buildString {
        append(program.title)
        append(' ')
        append(program.description)
        program.detail?.values?.forEach {
            append(' ')
            append(it)
        }
    }
    return text.contains("アニメ") || text.contains("anime", ignoreCase = true)
}
