@file:OptIn(ExperimentalComposeUiApi::class, ExperimentalTvMaterial3Api::class)

package com.beeregg2001.komorebi.ui.video.player

import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.*
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.*
import coil.compose.AsyncImage
import com.beeregg2001.komorebi.common.UrlBuilder
import com.beeregg2001.komorebi.data.model.AudioMode
import com.beeregg2001.komorebi.data.model.CmSkipMode
import com.beeregg2001.komorebi.data.model.Channel
import com.beeregg2001.komorebi.data.model.RecordedProgram
import kotlinx.coroutines.delay
import com.beeregg2001.komorebi.data.model.StreamQuality
import com.beeregg2001.komorebi.ui.components.recordedThumbnailModel
import com.beeregg2001.komorebi.ui.player.HdrToneMapping
import com.beeregg2001.komorebi.ui.player.PlaybackMediaInfo
import com.beeregg2001.komorebi.ui.player.PlaybackUiCapabilities
import com.beeregg2001.komorebi.ui.player.PlayerMenuTile
import com.beeregg2001.komorebi.ui.player.RecordedPlayerMenuTileStyle
import com.beeregg2001.komorebi.ui.player.PlayerSubMenuContainer
import com.beeregg2001.komorebi.ui.subtitle.NativeCaptionLanguage
import com.beeregg2001.komorebi.ui.theme.KomorebiTheme

@Composable
fun VideoTopSubMenuUI(
    mediaInfo: PlaybackMediaInfo,
    currentProgram: RecordedProgram?,
    seriesPrograms: List<RecordedProgram>,
    quickPrograms: List<RecordedProgram>,
    animeChannels: List<Channel> = emptyList(),
    backendType: String,
    konomiIp: String,
    konomiPort: String,
    currentAudioMode: AudioMode,
    currentSpeed: Float,
    isSubtitleEnabled: Boolean,
    subtitleLanguages: List<NativeCaptionLanguage>,
    currentSubtitleLanguageId: Int,
    currentQuality: StreamQuality,
    isCommentEnabled: Boolean,
    isLCropEnabled: Boolean,
    cmSkipMode: CmSkipMode,
    hdrRenderMode: String,
    isHdrRenderModeSupported: Boolean,
    isDataBroadcastingAvailable: Boolean,
    isDataBroadcastingActive: Boolean,
    availableQualities: List<StreamQuality>,
    focusRequester: FocusRequester,
    onAudioToggle: () -> Unit,
    onSpeedToggle: () -> Unit,
    onProgramInfo: () -> Unit,
    onSubtitleToggle: () -> Unit,
    onSubtitleLanguageToggle: () -> Unit,
    onQualitySelect: (StreamQuality) -> Unit,
    onCommentToggle: () -> Unit,
    onLCropToggle: () -> Unit,
    onCmSkipModeToggle: () -> Unit,
    onHdrRenderModeToggle: () -> Unit,
    onDataBroadcastingToggle: () -> Unit,
    onVideoSelect: (RecordedProgram) -> Unit,
    onChannelSelect: (Channel) -> Unit = {},
    canOpenKeyframeGrid: Boolean = false,
    onKeyframeGridToggle: () -> Unit = {},
    openQuickVideosInitially: Boolean = false,
    isVisible: Boolean = true,
    onCloseMenu: () -> Unit,
    capabilities: PlaybackUiCapabilities = PlaybackUiCapabilities.Recorded,
) {
    val colors = KomorebiTheme.colors
    var selectedCategory by remember(mediaInfo.stableId) {
        mutableStateOf(if (openQuickVideosInitially && capabilities.quickSelection) SubMenuCategory.QUICK_VIDEOS else null)
    }
    val quickVideoButtonRequester = remember { FocusRequester() }
    val qualityButtonRequester = remember { FocusRequester() }
    val qualityListRequester = remember { FocusRequester() }
    val quickVideoListRequester = remember { FocusRequester() }
    val showHdrTile = capabilities.hdr && isHdrRenderModeSupported
    var hdrTileFocused by remember { mutableStateOf(false) }
    LaunchedEffect(showHdrTile) {
        if (!showHdrTile && hdrTileFocused) {
            focusRequester.requestFocus()
            hdrTileFocused = false
        }
    }
    val currentSubtitleLanguage = subtitleLanguages.firstOrNull {
        it.id == currentSubtitleLanguageId
    } ?: subtitleLanguages.firstOrNull()

    val handleBack = {
        if (selectedCategory != null) {
            val targetRequester =
                if (selectedCategory == SubMenuCategory.QUICK_VIDEOS) quickVideoButtonRequester else qualityButtonRequester
            selectedCategory = null
            try {
                targetRequester.requestFocus()
            } catch (e: Exception) {
            }
        } else {
            onCloseMenu()
        }
    }

    BackHandler(enabled = isVisible) {
        handleBack()
    }

    LaunchedEffect(isVisible, openQuickVideosInitially) {
        if (!isVisible) return@LaunchedEffect
        selectedCategory = if (openQuickVideosInitially && capabilities.quickSelection) SubMenuCategory.QUICK_VIDEOS else null
        delay(50)
        try {
            if (openQuickVideosInitially) {
                quickVideoListRequester.requestFocus()
            } else {
                focusRequester.requestFocus()
            }
        } catch (e: Exception) {
        }
    }

    LaunchedEffect(selectedCategory) {
        if (selectedCategory == SubMenuCategory.QUALITY || selectedCategory == SubMenuCategory.QUICK_VIDEOS) {
            delay(100)
            try {
                if (selectedCategory == SubMenuCategory.QUALITY) {
                    qualityListRequester.requestFocus()
                } else {
                    quickVideoListRequester.requestFocus()
                }
            } catch (e: Exception) {
            }
        }
    }

    LaunchedEffect(openQuickVideosInitially) {
        if (openQuickVideosInitially && capabilities.quickSelection) {
            selectedCategory = SubMenuCategory.QUICK_VIDEOS
        }
    }

    PlayerSubMenuContainer(
        modifier = Modifier
            .graphicsLayer {
                alpha = if (isVisible) 1f else 0f
                translationY = if (isVisible) 0f else -80f
            }
            .focusProperties { canFocus = isVisible }
            .onPreviewKeyEvent { keyEvent ->
                if (keyEvent.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_BACK ||
                    keyEvent.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_ESCAPE
                ) {
                    if (keyEvent.type == KeyEventType.KeyDown) {
                        handleBack()
                    }
                    true
                } else {
                    false
                }
            }
            .onKeyEvent { keyEvent ->
                when {
                    keyEvent.type == KeyEventType.KeyDown &&
                            keyEvent.key == Key.DirectionUp &&
                            selectedCategory == null && capabilities.quickSelection -> {
                        selectedCategory = SubMenuCategory.QUICK_VIDEOS
                        true
                    }

                    keyEvent.type == KeyEventType.KeyDown &&
                            keyEvent.key == Key.DirectionUp &&
                            selectedCategory == SubMenuCategory.QUICK_VIDEOS && capabilities.quickSelection -> {
                        onCloseMenu()
                        true
                    }

                    else -> false
                }
            }
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally),
                modifier = Modifier
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 32.dp, vertical = 8.dp)
            ) {
                VideoMenuTileItem(
                    title = "クイック選局",
                    icon = Icons.Default.Tv,
                    subtitle = "シリーズ / 録画",
                    onClick = {
                        if (capabilities.quickSelection) {
                            selectedCategory = if (selectedCategory == SubMenuCategory.QUICK_VIDEOS) {
                                null
                            } else {
                                SubMenuCategory.QUICK_VIDEOS
                            }
                        }
                    },
                    modifier = Modifier
                        .focusRequester(quickVideoButtonRequester)
                        .focusProperties {
                            if (selectedCategory != SubMenuCategory.QUICK_VIDEOS) down =
                                FocusRequester.Cancel
                        },
                    contentColor = colors.textPrimary,
                    enabled = capabilities.quickSelection &&
                        (seriesPrograms.isNotEmpty() || quickPrograms.isNotEmpty() || animeChannels.isNotEmpty())
                )
                VideoMenuTileItem(
                    title = "サムネイル",
                    icon = Icons.Default.GridView,
                    subtitle = if (capabilities.thumbnailGrid && canOpenKeyframeGrid) "一覧" else "未生成",
                    onClick = onKeyframeGridToggle,
                    modifier = Modifier.focusProperties { down = FocusRequester.Cancel },
                    contentColor = if (capabilities.thumbnailGrid && canOpenKeyframeGrid) colors.textPrimary else colors.textSecondary,
                    enabled = capabilities.thumbnailGrid && canOpenKeyframeGrid
                )
                VideoMenuTileItem(
                    title = "音声切替",
                    icon = Icons.Default.Audiotrack,
                    subtitle = if (currentAudioMode == AudioMode.MAIN) "主音声" else "副音声",
                    onClick = onAudioToggle,
                    modifier = Modifier
                        .focusProperties { down = FocusRequester.Cancel },
                    contentColor = colors.textPrimary,
                    enabled = capabilities.audio
                )
                VideoMenuTileItem(
                    title = "番組情報",
                    icon = Icons.Default.Info,
                    subtitle = "詳細を開く",
                    onClick = onProgramInfo,
                    modifier = Modifier.focusRequester(focusRequester)
                        .focusProperties { down = FocusRequester.Cancel },
                    contentColor = if (capabilities.programInfo) colors.textPrimary else colors.textSecondary,
                    enabled = capabilities.programInfo
                )
                VideoMenuTileItem(
                    title = "画質",
                    icon = Icons.Default.HighQuality,
                    subtitle = currentQuality.label,
                    onClick = {
                        if (capabilities.quality && availableQualities.isNotEmpty()) {
                            selectedCategory =
                                if (selectedCategory == SubMenuCategory.QUALITY) null else SubMenuCategory.QUALITY
                        }
                    },
                    modifier = Modifier
                        .focusRequester(qualityButtonRequester)
                        .focusProperties {
                            if (selectedCategory != SubMenuCategory.QUALITY) down =
                                FocusRequester.Cancel
                        },
                    contentColor = colors.textPrimary,
                    enabled = capabilities.quality && availableQualities.isNotEmpty()
                )
                VideoMenuTileItem(
                    title = "再生速度",
                    icon = Icons.Default.Speed,
                    subtitle = "${currentSpeed}x",
                    onClick = onSpeedToggle,
                    modifier = Modifier.focusProperties { down = FocusRequester.Cancel },
                    contentColor = colors.textPrimary,
                    enabled = capabilities.speed
                )
                VideoMenuTileItem(
                    title = "字幕",
                    icon = Icons.Default.Subtitles,
                    subtitle = if (isSubtitleEnabled) "表示" else "非表示",
                    onClick = onSubtitleToggle,
                    modifier = Modifier.focusProperties { down = FocusRequester.Cancel },
                    contentColor = colors.textPrimary,
                    enabled = capabilities.subtitles
                )
                if (subtitleLanguages.size > 1 && currentSubtitleLanguage != null) {
                    VideoMenuTileItem(
                        title = "字幕言語",
                        icon = Icons.Default.Translate,
                        subtitle = "第${currentSubtitleLanguage.id}言語・${currentSubtitleLanguage.displayName}",
                        onClick = onSubtitleLanguageToggle,
                        modifier = Modifier.focusProperties { down = FocusRequester.Cancel },
                        contentColor = colors.textPrimary,
                        enabled = capabilities.subtitles && capabilities.subtitleLanguage
                    )
                }
                VideoMenuTileItem(
                    title = "L字クロップ",
                    icon = Icons.Default.Crop,
                    subtitle = if (isLCropEnabled) "有効" else "設定",
                    onClick = onLCropToggle,
                    modifier = Modifier.focusProperties { down = FocusRequester.Cancel },
                    contentColor = if (isLCropEnabled) colors.accent else colors.textPrimary,
                    enabled = capabilities.crop
                )

                VideoMenuTileItem(
                    title = "CMスキップ",
                    icon = Icons.Default.FastForward,
                    subtitle = cmSkipMode.displayLabel,
                    onClick = onCmSkipModeToggle,
                    modifier = Modifier.focusProperties { down = FocusRequester.Cancel },
                    contentColor = if (cmSkipMode != CmSkipMode.OFF) colors.accent else colors.textPrimary,
                    enabled = capabilities.cmSkip
                )

                VideoMenuTileItem(
                    title = "実況コメント",
                    icon = Icons.Default.Chat,
                    subtitle = if (isCommentEnabled) "表示" else "非表示",
                    onClick = onCommentToggle,
                    modifier = Modifier.focusProperties { down = FocusRequester.Cancel },
                    contentColor = colors.textPrimary,
                    enabled = capabilities.comments
                )
                if (showHdrTile) {
                VideoMenuTileItem(
                        title = "HDR 表示",
                        icon = Icons.Default.HighQuality,
                        subtitle = if (hdrRenderMode == HdrToneMapping.RENDER_MODE_SDR) {
                            "SDR 変換"
                        } else {
                            "HLG そのまま"
                        },
                        onClick = onHdrRenderModeToggle,
                        modifier = Modifier.onFocusChanged { if (showHdrTile) hdrTileFocused = it.isFocused }
                            .focusProperties { down = FocusRequester.Cancel },
                        contentColor = if (capabilities.hdr && isHdrRenderModeSupported && hdrRenderMode == HdrToneMapping.RENDER_MODE_SDR) {
                            colors.accent
                        } else {
                            colors.textPrimary
                        },
                        enabled = capabilities.hdr && isHdrRenderModeSupported,
                    )
                }
                VideoMenuTileItem(
                        title = "データ放送",
                        icon = Icons.Default.Tv,
                        subtitle = if (isDataBroadcastingActive) "表示中" else "開く",
                        onClick = onDataBroadcastingToggle,
                        modifier = Modifier.focusProperties { down = FocusRequester.Cancel },
                        contentColor = if (capabilities.dataBroadcasting && isDataBroadcastingActive) {
                            colors.accent
                        } else {
                            colors.textPrimary
                        },
                        enabled = capabilities.dataBroadcasting && isDataBroadcastingAvailable,
                    )

            }

            AnimatedVisibility(
                visible = selectedCategory == SubMenuCategory.QUICK_VIDEOS && currentProgram != null,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                currentProgram?.let { program -> QuickVideoPanel(
                    currentProgram = program,
                    seriesPrograms = seriesPrograms,
                    quickPrograms = quickPrograms,
                    animeChannels = animeChannels,
                    backendType = backendType,
                    konomiIp = konomiIp,
                    konomiPort = konomiPort,
                    focusRequester = quickVideoListRequester,
                    upRequester = quickVideoButtonRequester,
                    onVideoSelect = {
                        onVideoSelect(it)
                        selectedCategory = null
                        onCloseMenu()
                    },
                    onChannelSelect = {
                        onChannelSelect(it)
                        selectedCategory = null
                        onCloseMenu()
                    }
                ) }
            }

            AnimatedVisibility(
                visible = selectedCategory == SubMenuCategory.QUALITY && capabilities.quality,
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
                        availableQualities.forEach { quality ->
                            val isSelected = currentQuality.value == quality.value

                            VideoMenuTileItem(
                                title = quality.label,
                                icon = if (isSelected) Icons.Default.CheckCircle else Icons.Default.Settings,
                                subtitle = if (isSelected) "選択中" else "",
                                onClick = {
                                    onQualitySelect(quality)
                                    selectedCategory = null
                                    try {
                                        qualityButtonRequester.requestFocus()
                                    } catch (e: Exception) {
                                    }
                                },
                                width = 160.dp,
                                height = 100.dp,
                                modifier = Modifier
                                    .then(
                                        if (isSelected) Modifier.focusRequester(
                                            qualityListRequester
                                        ) else Modifier
                                    )
                                    .focusProperties {
                                        up = qualityButtonRequester
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

@Composable
private fun QuickVideoPanel(
    currentProgram: RecordedProgram,
    seriesPrograms: List<RecordedProgram>,
    quickPrograms: List<RecordedProgram>,
    animeChannels: List<Channel>,
    backendType: String,
    konomiIp: String,
    konomiPort: String,
    focusRequester: FocusRequester,
    upRequester: FocusRequester,
    onVideoSelect: (RecordedProgram) -> Unit,
    onChannelSelect: (Channel) -> Unit
) {
    val colors = KomorebiTheme.colors
    val recentFocusRequester = remember { FocusRequester() }
    val channelFocusRequester = remember { FocusRequester() }
    val seriesFocusRequester = if (seriesPrograms.isNotEmpty()) focusRequester else null
    val recentInitialFocusRequester =
        if (seriesPrograms.isEmpty() && quickPrograms.isNotEmpty()) focusRequester else recentFocusRequester
    val channelInitialFocusRequester =
        if (seriesPrograms.isEmpty() && quickPrograms.isEmpty()) focusRequester else channelFocusRequester

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
            QuickVideoSection(
                title = "シリーズ",
                programs = seriesPrograms,
                currentProgramId = currentProgram.id,
                backendType = backendType,
                konomiIp = konomiIp,
                konomiPort = konomiPort,
                focusRequester = seriesFocusRequester,
                upRequester = upRequester,
                downRequester = if (quickPrograms.isNotEmpty()) recentFocusRequester else null,
                onVideoSelect = onVideoSelect
            )
            QuickVideoSection(
                title = "最近の録画",
                programs = quickPrograms,
                currentProgramId = currentProgram.id,
                backendType = backendType,
                konomiIp = konomiIp,
                konomiPort = konomiPort,
                focusRequester = recentInitialFocusRequester,
                upRequester = if (seriesPrograms.isNotEmpty()) focusRequester else upRequester,
                downRequester = if (animeChannels.isNotEmpty()) channelFocusRequester else null,
                onVideoSelect = onVideoSelect
            )
            QuickChannelSection(
                title = "放送中のアニメ",
                channels = animeChannels,
                focusRequester = channelInitialFocusRequester,
                upRequester = if (quickPrograms.isNotEmpty()) recentInitialFocusRequester else upRequester,
                onChannelSelect = onChannelSelect
            )
        }
    }
}

@Composable
private fun QuickVideoSection(
    title: String,
    programs: List<RecordedProgram>,
    currentProgramId: Int,
    backendType: String,
    konomiIp: String,
    konomiPort: String,
    focusRequester: FocusRequester?,
    upRequester: FocusRequester,
    downRequester: FocusRequester?,
    onVideoSelect: (RecordedProgram) -> Unit
) {
    val colors = KomorebiTheme.colors

    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = colors.textPrimary,
            modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)
        )

        if (programs.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(112.dp)
                    .background(colors.textPrimary.copy(alpha = 0.08f), RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center
            ) {
                Text("該当録画なし", color = colors.textSecondary)
            }
        } else {
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(horizontal = 18.dp, vertical = 12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                items(programs, key = { it.id }) { program ->
                    val isSelected = program.id == currentProgramId
                    val requesterModifier =
                        if ((isSelected || program == programs.first()) && focusRequester != null) {
                            Modifier.focusRequester(focusRequester)
                        } else {
                            Modifier
                        }
                    QuickVideoCard(
                        program = program,
                        isSelected = isSelected,
                        backendType = backendType,
                        konomiIp = konomiIp,
                        konomiPort = konomiPort,
                        onClick = { onVideoSelect(program) },
                        modifier = requesterModifier
                            .onPreviewKeyEvent { keyEvent ->
                                if (keyEvent.type == KeyEventType.KeyDown && keyEvent.key == Key.DirectionUp) {
                                    runCatching { upRequester.requestFocus() }
                                    true
                                } else {
                                    false
                                }
                            }
                            .focusProperties {
                                up = upRequester
                                down = downRequester ?: FocusRequester.Cancel
                            }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun QuickChannelSection(
    title: String,
    channels: List<Channel>,
    focusRequester: FocusRequester,
    upRequester: FocusRequester,
    onChannelSelect: (Channel) -> Unit
) {
    val colors = KomorebiTheme.colors

    Column(modifier = Modifier.fillMaxWidth()) {
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
                    .height(84.dp)
                    .background(colors.textPrimary.copy(alpha = 0.08f), RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center
            ) {
                Text("放送中のアニメなし", color = colors.textSecondary)
            }
        } else {
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(horizontal = 18.dp, vertical = 12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                items(channels, key = { it.id }) { channel ->
                    val requesterModifier =
                        if (channel == channels.first()) Modifier.focusRequester(focusRequester) else Modifier
                    QuickChannelCard(
                        channel = channel,
                        onClick = { onChannelSelect(channel) },
                        modifier = requesterModifier.focusProperties {
                            up = upRequester
                            down = FocusRequester.Cancel
                        }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun QuickChannelCard(
    channel: Channel,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = KomorebiTheme.colors
    val focusedContentColor = if (colors.isDark) Color.Black else Color.White

    Surface(
        onClick = onClick,
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.08f),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = colors.surface.copy(alpha = 0.72f),
            focusedContainerColor = colors.textPrimary,
            contentColor = colors.textPrimary,
            focusedContentColor = focusedContentColor
        ),
        shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(12.dp)),
        modifier = modifier.size(width = 260.dp, height = 88.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .background(colors.accent.copy(alpha = 0.18f), RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.Tv, contentDescription = null, tint = colors.accent)
            }
            Spacer(Modifier.width(12.dp))
            Column(verticalArrangement = Arrangement.Center, modifier = Modifier.weight(1f)) {
                Text(
                    text = channel.programPresent?.title ?: channel.name,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = channel.name,
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun QuickVideoCard(
    program: RecordedProgram,
    isSelected: Boolean,
    backendType: String,
    konomiIp: String,
    konomiPort: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = KomorebiTheme.colors
    val thumbnailUrl = program.directThumbnailUrl ?: program.apiThumbnailUrl
    ?: UrlBuilder.getThumbnailUrl(backendType, konomiIp, konomiPort, program.id.toString())

    Surface(
        onClick = onClick,
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.08f),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (isSelected) colors.surface else colors.surface.copy(alpha = 0.7f),
            focusedContainerColor = colors.textPrimary,
            contentColor = colors.textPrimary,
            focusedContentColor = if (colors.isDark) Color.Black else Color.White
        ),
        shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(12.dp)),
        modifier = modifier
            .size(width = 240.dp, height = 112.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AsyncImage(
                model = program.recordedThumbnailModel(thumbnailUrl),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(width = 86.dp, height = 52.dp)
                    .background(Color.Black.copy(alpha = 0.2f), RoundedCornerShape(6.dp))
            )
            Spacer(Modifier.width(12.dp))
            Column(verticalArrangement = Arrangement.Center, modifier = Modifier.weight(1f)) {
                Text(
                    text = program.title,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = program.seriesName?.takeIf { it.isNotBlank() }
                        ?: program.channel?.name
                        ?: "",
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = LocalContentColor.current.copy(alpha = 0.7f)
                )
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun VideoMenuTileItem(
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
    PlayerMenuTile(
        title = title,
        icon = icon,
        subtitle = subtitle,
        onClick = onClick,
        style = RecordedPlayerMenuTileStyle,
        modifier = modifier,
        enabled = enabled,
        width = width,
        height = height,
        contentColor = contentColor
    )
}
