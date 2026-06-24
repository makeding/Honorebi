@file:OptIn(ExperimentalComposeUiApi::class, ExperimentalTvMaterial3Api::class)

package com.beeregg2001.komorebi.ui.video.player

import android.view.KeyEvent
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
import com.beeregg2001.komorebi.data.model.RecordedProgram
import kotlinx.coroutines.delay
import com.beeregg2001.komorebi.data.model.StreamQuality
import com.beeregg2001.komorebi.ui.components.recordedThumbnailModel
import com.beeregg2001.komorebi.ui.theme.KomorebiTheme

@Composable
fun VideoTopSubMenuUI(
    currentProgram: RecordedProgram,
    seriesPrograms: List<RecordedProgram>,
    quickPrograms: List<RecordedProgram>,
    backendType: String,
    konomiIp: String,
    konomiPort: String,
    currentAudioMode: AudioMode,
    currentSpeed: Float,
    isSubtitleEnabled: Boolean,
    currentQuality: StreamQuality,
    isCommentEnabled: Boolean,
    isLCropEnabled: Boolean,
    isAutoCmSkipEnabled: Boolean,
    availableQualities: List<StreamQuality>,
    focusRequester: FocusRequester,
    onAudioToggle: () -> Unit,
    onSpeedToggle: () -> Unit,
    onSubtitleToggle: () -> Unit,
    onQualitySelect: (StreamQuality) -> Unit,
    onCommentToggle: () -> Unit,
    canOpenKeyframeGrid: Boolean = false,
    onKeyframeGridToggle: () -> Unit = {},
    onLCropToggle: () -> Unit,
    onAutoCmSkipToggle: () -> Unit,
    onVideoSelect: (RecordedProgram) -> Unit,
    onCloseMenu: () -> Unit,
    // ★ 追加: 各機能のサポート状況を受け取るフラグ (既存に影響しないようデフォルトは true)
    isAudioSupported: Boolean = true,
    isQualitySupported: Boolean = true,
    isCommentSupported: Boolean = true,
    isSubtitleSupported: Boolean = true,
    isAutoCmSkipSupported: Boolean = true
) {
    val colors = KomorebiTheme.colors
    var selectedCategory by remember { mutableStateOf<SubMenuCategory?>(null) }
    val quickVideoButtonRequester = remember { FocusRequester() }
    val qualityButtonRequester = remember { FocusRequester() }
    val qualityListRequester = remember { FocusRequester() }
    val quickVideoListRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        delay(50)
        try {
            focusRequester.requestFocus()
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
                    (keyEvent.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_BACK ||
                            keyEvent.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_ESCAPE)
                ) {
                    if (selectedCategory != null) {
                        val targetRequester =
                            if (selectedCategory == SubMenuCategory.QUICK_VIDEOS) quickVideoButtonRequester else qualityButtonRequester
                        selectedCategory = null
                        try {
                            targetRequester.requestFocus()
                        } catch (e: Exception) {
                        }
                        true
                    } else false
                } else false
            },
        contentAlignment = Alignment.TopCenter
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
                        selectedCategory =
                            if (selectedCategory == SubMenuCategory.QUICK_VIDEOS) null else SubMenuCategory.QUICK_VIDEOS
                    },
                    modifier = Modifier
                        .focusRequester(quickVideoButtonRequester)
                        .focusProperties {
                            if (selectedCategory != SubMenuCategory.QUICK_VIDEOS) down =
                                FocusRequester.Cancel
                        },
                    contentColor = colors.textPrimary,
                    enabled = seriesPrograms.isNotEmpty() || quickPrograms.isNotEmpty()
                )
                if (canOpenKeyframeGrid) {
                    VideoMenuTileItem(
                        title = "サムネイル",
                        icon = Icons.Default.GridView,
                        subtitle = "一覧",
                        onClick = onKeyframeGridToggle,
                        modifier = Modifier.focusProperties { down = FocusRequester.Cancel },
                        contentColor = colors.textPrimary,
                        enabled = true
                    )
                }
                VideoMenuTileItem(
                    title = "音声切替",
                    icon = Icons.Default.Audiotrack,
                    subtitle = if (currentAudioMode == AudioMode.MAIN) "主音声" else "副音声",
                    onClick = onAudioToggle,
                    modifier = Modifier
                        .focusRequester(focusRequester)
                        .focusProperties { down = FocusRequester.Cancel },
                    contentColor = colors.textPrimary,
                    enabled = isAudioSupported // ★ 適用
                )
                VideoMenuTileItem(
                    title = "再生速度",
                    icon = Icons.Default.Speed,
                    subtitle = "${currentSpeed}x",
                    onClick = onSpeedToggle,
                    modifier = Modifier.focusProperties { down = FocusRequester.Cancel },
                    contentColor = colors.textPrimary,
                    enabled = true // 速度は常に利用可能
                )
                VideoMenuTileItem(
                    title = "字幕",
                    icon = Icons.Default.Subtitles,
                    subtitle = if (isSubtitleEnabled) "表示" else "非表示",
                    onClick = onSubtitleToggle,
                    modifier = Modifier.focusProperties { down = FocusRequester.Cancel },
                    contentColor = colors.textPrimary,
                    enabled = isSubtitleSupported // ★ 適用
                )
                VideoMenuTileItem(
                    title = "L字クロップ",
                    icon = Icons.Default.Crop,
                    subtitle = if (isLCropEnabled) "有効" else "設定",
                    onClick = onLCropToggle,
                    modifier = Modifier.focusProperties { down = FocusRequester.Cancel },
                    contentColor = if (isLCropEnabled) colors.accent else colors.textPrimary,
                    enabled = true // L字クロップは常に利用可能
                )

                VideoMenuTileItem(
                    title = "自動CMスキップ",
                    icon = Icons.Default.FastForward,
                    subtitle = if (isAutoCmSkipEnabled) "有効" else "無効",
                    onClick = onAutoCmSkipToggle,
                    modifier = Modifier.focusProperties { down = FocusRequester.Cancel },
                    contentColor = if (isAutoCmSkipEnabled) colors.accent else colors.textPrimary,
                    enabled = isAutoCmSkipSupported // ★ 適用
                )

                VideoMenuTileItem(
                    title = "実況コメント",
                    icon = Icons.Default.Chat,
                    subtitle = if (isCommentEnabled) "表示" else "非表示",
                    onClick = onCommentToggle,
                    modifier = Modifier.focusProperties { down = FocusRequester.Cancel },
                    contentColor = colors.textPrimary,
                    enabled = isCommentSupported // ★ 適用
                )
                VideoMenuTileItem(
                    title = "画質",
                    icon = Icons.Default.HighQuality,
                    subtitle = currentQuality.label,
                    onClick = {
                        if (isQualitySupported && availableQualities.isNotEmpty()) { // ★ 修正: 無効時は無視
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
                    enabled = isQualitySupported && availableQualities.isNotEmpty() // ★ 適用
                )
            }

            AnimatedVisibility(
                visible = selectedCategory == SubMenuCategory.QUICK_VIDEOS,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                QuickVideoPanel(
                    currentProgram = currentProgram,
                    seriesPrograms = seriesPrograms,
                    quickPrograms = quickPrograms,
                    backendType = backendType,
                    konomiIp = konomiIp,
                    konomiPort = konomiPort,
                    focusRequester = quickVideoListRequester,
                    upRequester = quickVideoButtonRequester,
                    onVideoSelect = {
                        onVideoSelect(it)
                        selectedCategory = null
                        onCloseMenu()
                    }
                )
            }

            AnimatedVisibility(
                visible = selectedCategory == SubMenuCategory.QUALITY,
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
    backendType: String,
    konomiIp: String,
    konomiPort: String,
    focusRequester: FocusRequester,
    upRequester: FocusRequester,
    onVideoSelect: (RecordedProgram) -> Unit
) {
    val colors = KomorebiTheme.colors
    val recentFocusRequester = remember { FocusRequester() }
    val initialSeriesFocusRequester = if (seriesPrograms.isEmpty()) recentFocusRequester else focusRequester

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
                focusRequester = initialSeriesFocusRequester,
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
                focusRequester = recentFocusRequester,
                upRequester = if (seriesPrograms.isNotEmpty()) initialSeriesFocusRequester else upRequester,
                downRequester = null,
                onVideoSelect = onVideoSelect
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
                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp),
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
        modifier = modifier.size(width = 240.dp, height = 112.dp)
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
    val colors = KomorebiTheme.colors
    Surface(
        onClick = onClick,
        enabled = enabled,
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.1f),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = colors.textPrimary.copy(alpha = 0.1f),
            contentColor = if (enabled) contentColor else colors.textPrimary.copy(alpha = 0.3f),
            focusedContainerColor = colors.textPrimary,
            focusedContentColor = if (colors.isDark) Color.Black else Color.White
        ),
        shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(12.dp)),
        modifier = modifier
            .size(width, height)
            // ★ 追加: 非対応項目は半透明にしてグレーアウトを強調
            .alpha(if (enabled) 1f else 0.4f)
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(28.dp)
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold
            )
            if (subtitle.isNotEmpty()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                    color = LocalContentColor.current.copy(alpha = 0.7f)
                )
            }
        }
    }
}

@OptIn(
    ExperimentalTvMaterial3Api::class,
    ExperimentalComposeUiApi::class,
    ExperimentalAnimationApi::class
)
@Composable
fun AnimatedVisibilityScope.ModernVideoSettingsOverlay(
    currentAudioMode: AudioMode,
    currentSpeed: Float,
    isSubtitleEnabled: Boolean,
    currentQuality: StreamQuality,
    isCommentEnabled: Boolean,
    isLCropEnabled: Boolean,
    isAutoCmSkipEnabled: Boolean,
    availableQualities: List<StreamQuality>,
    onAudioToggle: () -> Unit,
    onSpeedToggle: () -> Unit,
    onSubtitleToggle: () -> Unit,
    onQualitySelect: (StreamQuality) -> Unit,
    onCommentToggle: () -> Unit,
    canOpenKeyframeGrid: Boolean = false,
    onKeyframeGridToggle: () -> Unit = {},
    onLCropToggle: () -> Unit,
    onAutoCmSkipToggle: () -> Unit,
    // ★ 追加: 各機能のサポート状況を受け取るフラグ
    isAudioSupported: Boolean = true,
    isQualitySupported: Boolean = true,
    isCommentSupported: Boolean = true,
    isSubtitleSupported: Boolean = true,
    isAutoCmSkipSupported: Boolean = true,
    onClose: () -> Unit
) {
    val colors = KomorebiTheme.colors
    var selectedCategory by remember { mutableStateOf<SubMenuCategory?>(null) }
    val initialFocusRequester = remember { FocusRequester() }
    val qualityListRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        delay(150)
        try {
            initialFocusRequester.requestFocus()
        } catch (e: Exception) {
        }
    }

    LaunchedEffect(selectedCategory) {
        if (selectedCategory == SubMenuCategory.QUALITY) {
            delay(100)
            try {
                qualityListRequester.requestFocus()
            } catch (e: Exception) {
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.4f))
            .onKeyEvent {
                if (it.type == KeyEventType.KeyDown &&
                    (it.nativeKeyEvent.keyCode == android.view.KeyEvent.KEYCODE_BACK ||
                            it.nativeKeyEvent.keyCode == android.view.KeyEvent.KEYCODE_ESCAPE)
                ) {
                    if (selectedCategory != null) {
                        selectedCategory = null
                        try {
                            initialFocusRequester.requestFocus()
                        } catch (e: Exception) {
                        }
                        true
                    } else {
                        onClose()
                        true
                    }
                } else false
            },
        contentAlignment = Alignment.CenterEnd
    ) {
        Column(
            modifier = Modifier
                .animateEnterExit(
                    enter = slideInHorizontally { fullWidth -> fullWidth },
                    exit = slideOutHorizontally { fullWidth -> fullWidth }
                )
                .fillMaxHeight()
                .width(360.dp)
                .background(colors.surface.copy(alpha = 0.95f))
                .border(1.dp, colors.textPrimary.copy(alpha = 0.1f))
                .padding(horizontal = 24.dp, vertical = 32.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Settings, contentDescription = null, tint = colors.textPrimary)
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = if (selectedCategory == SubMenuCategory.QUALITY) "画質の選択" else "プレイヤー設定",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = colors.textPrimary
                )
            }
            Spacer(modifier = Modifier.height(24.dp))

            AnimatedContent(targetState = selectedCategory, label = "SettingsMenu") { category ->
                if (category == null) {
                    Column(
                        modifier = Modifier.verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        if (canOpenKeyframeGrid) {
                            ModernSettingRow(
                                title = "サムネイル",
                                value = "一覧",
                                icon = Icons.Default.GridView,
                                onClick = onKeyframeGridToggle,
                                modifier = Modifier.focusRequester(initialFocusRequester),
                                enabled = true
                            )
                        }
                        ModernSettingRow(
                            title = "音声切替",
                            value = if (currentAudioMode == AudioMode.MAIN) "主音声" else "副音声",
                            icon = Icons.Default.Audiotrack,
                            onClick = onAudioToggle,
                            modifier = if (canOpenKeyframeGrid) Modifier else Modifier.focusRequester(initialFocusRequester),
                            enabled = isAudioSupported // ★ 適用
                        )
                        ModernSettingRow(
                            title = "再生速度",
                            value = "${currentSpeed}x",
                            icon = Icons.Default.Speed,
                            onClick = onSpeedToggle,
                            enabled = true
                        )
                        ModernSettingRow(
                            title = "字幕",
                            value = if (isSubtitleEnabled) "表示" else "非表示",
                            icon = Icons.Default.Subtitles,
                            onClick = onSubtitleToggle,
                            enabled = isSubtitleSupported // ★ 適用
                        )
                        ModernSettingRow(
                            title = "画質",
                            value = currentQuality.label,
                            icon = Icons.Default.HighQuality,
                            onClick = {
                                if (isQualitySupported) selectedCategory = SubMenuCategory.QUALITY
                            }, // ★ 無効時は開かない
                            enabled = isQualitySupported && availableQualities.isNotEmpty() // ★ 適用
                        )
                        ModernSettingRow(
                            title = "自動CMスキップ",
                            value = if (isAutoCmSkipEnabled) "有効" else "無効",
                            icon = Icons.Default.FastForward,
                            onClick = onAutoCmSkipToggle,
                            highlight = isAutoCmSkipEnabled,
                            enabled = isAutoCmSkipSupported // ★ 適用
                        )
                        ModernSettingRow(
                            title = "実況コメント",
                            value = if (isCommentEnabled) "表示" else "非表示",
                            icon = Icons.Default.Chat,
                            onClick = onCommentToggle,
                            enabled = isCommentSupported // ★ 適用
                        )
                        ModernSettingRow(
                            title = "L字クロップ",
                            value = if (isLCropEnabled) "有効" else "設定",
                            icon = Icons.Default.Crop,
                            onClick = onLCropToggle,
                            highlight = isLCropEnabled,
                            enabled = true
                        )
                    }
                } else if (category == SubMenuCategory.QUALITY) {
                    Column(
                        modifier = Modifier.verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        availableQualities.forEach { quality ->
                            val isSelected = currentQuality.value == quality.value
                            ModernSettingRow(
                                title = quality.label,
                                value = if (isSelected) "✓" else "",
                                icon = if (isSelected) Icons.Default.CheckCircle else Icons.Default.Settings,
                                onClick = {
                                    onQualitySelect(quality)
                                    selectedCategory = null
                                    try {
                                        initialFocusRequester.requestFocus()
                                    } catch (e: Exception) {
                                    }
                                },
                                highlight = isSelected,
                                modifier = if (isSelected) Modifier.focusRequester(
                                    qualityListRequester
                                ) else Modifier
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
fun ModernSettingRow(
    title: String,
    value: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    highlight: Boolean = false,
    enabled: Boolean = true
) {
    val colors = KomorebiTheme.colors
    var isFocused by remember { mutableStateOf(false) }

    Surface(
        onClick = { if (enabled) onClick() },
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
        scale = if (enabled) ClickableSurfaceDefaults.scale(focusedScale = 1.05f) else ClickableSurfaceDefaults.scale(
            focusedScale = 1f
        ),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (highlight && enabled) colors.accent.copy(alpha = 0.1f) else Color.Transparent,
            focusedContainerColor = if (enabled) colors.accent else Color.White.copy(alpha = 0.1f),
            contentColor = if (enabled) colors.textPrimary else colors.textSecondary.copy(alpha = 0.5f),
            focusedContentColor = if (enabled) (if (colors.isDark) Color.Black else Color.White) else colors.textSecondary.copy(
                alpha = 0.5f
            )
        ),
        modifier = modifier
            .fillMaxWidth()
            .height(56.dp)
            // ★ 追加: 非対応項目は半透明にしてグレーアウトを強調
            .alpha(if (enabled) 1f else 0.4f)
            .onFocusChanged { isFocused = it.isFocused }
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = if (isFocused) Color.Unspecified else if (highlight && enabled) colors.accent else colors.textSecondary
            )
            Spacer(modifier = Modifier.width(16.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium,
                color = if (isFocused) Color.Unspecified else colors.textSecondary
            )
        }
    }
}
