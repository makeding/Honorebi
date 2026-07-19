@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)

package com.beeregg2001.komorebi.ui.setting

import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Warning
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.tv.material3.*
import com.beeregg2001.komorebi.common.safeRequestFocus
import com.beeregg2001.komorebi.ui.theme.KomorebiTheme
import com.beeregg2001.komorebi.ui.theme.getSeasonalBackgroundBrush
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalTime

@RequiresApi(Build.VERSION_CODES.O)
@Composable
fun DeviceCapabilitiesScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val report = remember { DeviceCapabilityDetector.detect(context) }
    val colors = KomorebiTheme.colors
    val closeRequester = remember { FocusRequester() }
    val detailScrollState = rememberScrollState()
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val backgroundBrush = getSeasonalBackgroundBrush(KomorebiTheme.theme, remember { LocalTime.now() })

    LaunchedEffect(Unit) {
        delay(120)
        closeRequester.safeRequestFocus()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background)
            .background(backgroundBrush)
            .padding(horizontal = 56.dp, vertical = 38.dp)
            .onPreviewKeyEvent {
                if (it.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (it.key) {
                    Key.DirectionDown -> {
                        if (detailScrollState.canScrollForward) {
                            scope.launch {
                                detailScrollState.animateScrollTo(
                                    (detailScrollState.value + 220).coerceAtMost(detailScrollState.maxValue)
                                )
                            }
                        }
                        true
                    }
                    Key.DirectionUp -> {
                        if (detailScrollState.canScrollBackward) {
                            scope.launch {
                                detailScrollState.animateScrollTo(
                                    (detailScrollState.value - 220).coerceAtLeast(0)
                                )
                            }
                        }
                        true
                    }
                    Key.Back, Key.Escape -> {
                        onBack()
                        true
                    }
                    else -> false
                }
            }
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Memory, null, tint = colors.accent, modifier = Modifier.size(40.dp))
            Spacer(Modifier.width(16.dp))
            Column {
                Text("テレビ再生能力", style = MaterialTheme.typography.headlineLarge, color = colors.textPrimary, fontWeight = FontWeight.Bold)
                Text("${report.deviceName} / ${report.androidVersion}", color = colors.textSecondary)
            }
        }

        Spacer(Modifier.height(28.dp))
        Row(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            CapabilityCard("放送の直接再生", Modifier.weight(0.9f).fillMaxHeight()) {
                CapabilityVerdict(
                    title = "BS4K (3840x2160 / HEVC Main10)",
                    supported = report.supportsBs4kDirect,
                    supportedText = "直接再生できる見込みです",
                    unsupportedText = "直接再生の要件を満たしていません"
                )
                Spacer(Modifier.height(22.dp))
                CapabilityVerdict(
                    title = "BS8K (7680x4320 / HEVC Main10)",
                    supported = report.supportsBs8kDirect,
                    supportedText = "直接再生できる見込みです",
                    unsupportedText = "このテレビでは直接再生できません"
                )
                if (!report.supportsBs8kDirect) {
                    Spacer(Modifier.height(16.dp))
                    Text(
                        "BS8K はサーバー側で 4K 以下へ変換するか、8K HEVC 対応機器が必要です。",
                        color = colors.textSecondary,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }

            CapabilityCard("検出結果", Modifier.weight(1.1f).fillMaxHeight()) {
                Column(
                    modifier = Modifier.verticalScroll(detailScrollState),
                    verticalArrangement = Arrangement.spacedBy(13.dp)
                ) {
                    CapabilityDetail("HEVC 最大確認モード", report.maxVerifiedMode)
                    CapabilityDetail("HEVC Main10", yesNo(report.supportsHevcMain10))
                    CapabilityDetail("4K / 60fps", yesNo(report.supports4k60))
                    CapabilityDetail("8K / 30fps", yesNo(report.supports8k30))
                    CapabilityDetail("8K / 60fps", yesNo(report.supports8k60))
                    CapabilityDetail("ディスプレイ HDR", report.hdrTypes.ifEmpty { listOf("未検出") }.joinToString(" / "))
                    CapabilityDetail(
                        "音声出力の申告",
                        report.maxReportedAudioChannels?.let { "最大 ${it}ch" } ?: "不明"
                    )
                    CapabilityDetail(
                        "MMT 22.2ch 音声",
                        "自動回避（5.1ch / stereo を選択）"
                    )
                    CapabilityDetail(
                        "HEVC デコーダー",
                        report.hevcDecoders.ifEmpty { listOf("未検出") }.joinToString("\n")
                    )
                }
            }
        }

        Spacer(Modifier.height(22.dp))
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            if (detailScrollState.maxValue > 0) {
                Text("↑↓  詳細をスクロール", color = colors.textSecondary)
            }
            Spacer(Modifier.weight(1f))
            Button(
                onClick = onBack,
                modifier = Modifier.width(180.dp).focusRequester(closeRequester)
            ) { Text("閉じる") }
        }
    }
}

@Composable
private fun CapabilityCard(title: String, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val colors = KomorebiTheme.colors
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(20.dp),
        colors = SurfaceDefaults.colors(containerColor = colors.surface.copy(alpha = 0.92f))
    ) {
        Column(modifier = Modifier.padding(28.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge, color = colors.textPrimary, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(24.dp))
            content()
        }
    }
}

@Composable
private fun CapabilityVerdict(
    title: String,
    supported: Boolean,
    supportedText: String,
    unsupportedText: String
) {
    val colors = KomorebiTheme.colors
    Row(verticalAlignment = Alignment.Top) {
        Icon(
            if (supported) Icons.Default.CheckCircle else Icons.Default.Error,
            null,
            tint = if (supported) androidx.compose.ui.graphics.Color(0xFF4CAF50) else androidx.compose.ui.graphics.Color(0xFFFF5252),
            modifier = Modifier.size(30.dp)
        )
        Spacer(Modifier.width(14.dp))
        Column {
            Text(title, color = colors.textPrimary, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(if (supported) supportedText else unsupportedText, color = colors.textSecondary)
        }
    }
}

@Composable
private fun CapabilityDetail(label: String, value: String) {
    val colors = KomorebiTheme.colors
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = colors.textSecondary, modifier = Modifier.weight(0.48f))
        Text(value, color = colors.textPrimary, modifier = Modifier.weight(0.52f), fontWeight = FontWeight.SemiBold)
    }
}

private fun yesNo(value: Boolean): String = if (value) "対応" else "非対応"
