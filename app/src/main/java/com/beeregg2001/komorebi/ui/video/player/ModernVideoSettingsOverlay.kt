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
import com.beeregg2001.komorebi.common.safeRequestFocusWithRetry
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
import com.beeregg2001.komorebi.ui.player.PlaybackUiCapabilities
import com.beeregg2001.komorebi.ui.subtitle.NativeCaptionLanguage
import com.beeregg2001.komorebi.ui.theme.KomorebiTheme

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
    cmSkipMode: CmSkipMode,
    availableQualities: List<StreamQuality>,
    onAudioToggle: () -> Unit,
    onSpeedToggle: () -> Unit,
    onSubtitleToggle: () -> Unit,
    onQualitySelect: (StreamQuality) -> Unit,
    onCommentToggle: () -> Unit,
    onLCropToggle: () -> Unit,
    onCmSkipModeToggle: () -> Unit,
    capabilities: PlaybackUiCapabilities = PlaybackUiCapabilities.Recorded,
    onClose: () -> Unit
) {
    val colors = KomorebiTheme.colors
    var selectedCategory by remember { mutableStateOf<SubMenuCategory?>(null) }
    val initialFocusRequester = remember { FocusRequester() }
    val qualityListRequester = remember { FocusRequester() }
    val qualityEnabled = capabilities.quality && availableQualities.isNotEmpty()
    val initialFocusRow = when {
        capabilities.audio -> "audio"
        capabilities.speed -> "speed"
        capabilities.subtitles -> "subtitles"
        qualityEnabled -> "quality"
        capabilities.cmSkip -> "cmSkip"
        capabilities.comments -> "comments"
        capabilities.crop -> "crop"
        else -> "audio"
    }

    val handleBack = {
        if (selectedCategory != null) {
            selectedCategory = null
        } else {
            onClose()
        }
    }

    BackHandler {
        handleBack()
    }

    LaunchedEffect(selectedCategory) {
        withFrameNanos { }
        if (selectedCategory == SubMenuCategory.QUALITY) {
            qualityListRequester.safeRequestFocusWithRetry(
                tag = "ModernVideoSettings",
                maxRetries = 8,
                delayMillis = 80L,
                isCurrent = { selectedCategory == SubMenuCategory.QUALITY }
            )
        } else {
            initialFocusRequester.safeRequestFocusWithRetry(
                tag = "ModernVideoSettings",
                maxRetries = 8,
                delayMillis = 80L,
                isCurrent = { selectedCategory == null }
            )
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.4f))
            .onPreviewKeyEvent {
                val code = it.nativeKeyEvent.keyCode
                if (code == android.view.KeyEvent.KEYCODE_BACK ||
                    code == android.view.KeyEvent.KEYCODE_ESCAPE
                ) {
                    if (it.type == KeyEventType.KeyDown) {
                        handleBack()
                    }
                    true
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
                        modifier = Modifier
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 8.dp, vertical = 6.dp)
                            .graphicsLayer { clip = false },
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        ModernSettingRow(
                            title = "音声切替",
                            value = if (currentAudioMode == AudioMode.MAIN) "主音声" else "副音声",
                            icon = Icons.Default.Audiotrack,
                            onClick = onAudioToggle,
                            modifier = if (initialFocusRow == "audio") Modifier.focusRequester(initialFocusRequester) else Modifier,
                            enabled = capabilities.audio
                        )
                        ModernSettingRow(
                            title = "再生速度",
                            value = "${currentSpeed}x",
                            icon = Icons.Default.Speed,
                            onClick = onSpeedToggle,
                            modifier = if (initialFocusRow == "speed") Modifier.focusRequester(initialFocusRequester) else Modifier,
                            enabled = capabilities.speed
                        )
                        ModernSettingRow(
                            title = "字幕",
                            value = if (isSubtitleEnabled) "表示" else "非表示",
                            icon = Icons.Default.Subtitles,
                            onClick = onSubtitleToggle,
                            modifier = if (initialFocusRow == "subtitles") Modifier.focusRequester(initialFocusRequester) else Modifier,
                            enabled = capabilities.subtitles
                        )
                        ModernSettingRow(
                            title = "画質",
                            value = currentQuality.label,
                            icon = Icons.Default.HighQuality,
                            onClick = {
                                if (qualityEnabled) selectedCategory = SubMenuCategory.QUALITY
                            },
                            modifier = if (initialFocusRow == "quality") Modifier.focusRequester(initialFocusRequester) else Modifier,
                            enabled = qualityEnabled
                        )
                        ModernSettingRow(
                            title = "CMスキップ",
                            value = cmSkipMode.displayLabel,
                            icon = Icons.Default.FastForward,
                            onClick = onCmSkipModeToggle,
                            highlight = cmSkipMode != CmSkipMode.OFF,
                            modifier = if (initialFocusRow == "cmSkip") Modifier.focusRequester(initialFocusRequester) else Modifier,
                            enabled = capabilities.cmSkip
                        )
                        ModernSettingRow(
                            title = "実況コメント",
                            value = if (isCommentEnabled) "表示" else "非表示",
                            icon = Icons.Default.Chat,
                            onClick = onCommentToggle,
                            modifier = if (initialFocusRow == "comments") Modifier.focusRequester(initialFocusRequester) else Modifier,
                            enabled = capabilities.comments
                        )
                        ModernSettingRow(
                            title = "L字クロップ",
                            value = if (isLCropEnabled) "有効" else "設定",
                            icon = Icons.Default.Crop,
                            onClick = onLCropToggle,
                            highlight = isLCropEnabled,
                            modifier = if (initialFocusRow == "crop") Modifier.focusRequester(initialFocusRequester) else Modifier,
                            enabled = capabilities.crop
                        )
                    }
                } else if (category == SubMenuCategory.QUALITY) {
                    val selectedIndex = availableQualities.indexOfFirst { it.value == currentQuality.value }
                    val focusTargetIndex = if (selectedIndex >= 0) selectedIndex else 0
                    Column(
                        modifier = Modifier
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 8.dp, vertical = 6.dp)
                            .graphicsLayer { clip = false },
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        availableQualities.forEachIndexed { index, quality ->
                            val isSelected = currentQuality.value == quality.value
                            ModernSettingRow(
                                title = quality.label,
                                value = if (isSelected) "✓" else "",
                                icon = if (isSelected) Icons.Default.CheckCircle else Icons.Default.Settings,
                                onClick = {
                                    onQualitySelect(quality)
                                    selectedCategory = null
                                },
                                highlight = isSelected,
                                modifier = if (index == focusTargetIndex) Modifier.focusRequester(
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
