package com.beeregg2001.komorebi.ui.live

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.SettingsInputHdmi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.beeregg2001.komorebi.data.TifNoSignalStyle
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

internal const val TIF_NO_SIGNAL_EFFECT_TAG = "tif-no-signal-effect"

private const val EFFECT_JANK_WINDOW_FRAMES = 60
private const val EFFECT_JANK_SLOW_THRESHOLD = 30
private const val EFFECT_JANK_DT_NANOS = 50_000_000L

// Shared immutable drawing constants (zero per-frame allocations)
private val ARIB_GRAY_40 = Color(0xFF666666)
private val ARIB_GRAY_15 = Color(0xFF262626)
private val ARIB_MAIN_BARS = arrayOf(
    Color(0xFFBFBFBF), // 75% White
    Color(0xFFBFBF00), // Yellow
    Color(0xFF00BFBF), // Cyan
    Color(0xFF00BF00), // Green
    Color(0xFFBF00BF), // Magenta
    Color(0xFFBF0000), // Red
    Color(0xFF0000BF), // Blue
)
private val ARIB_PLUGE_COLORS = arrayOf(
    Color(0xFF000000),
    Color(0xFFFFFFFF),
    Color(0xFF000000),
    Color(0xFF060606),
    Color(0xFF0E0E0E),
    Color(0xFF1A1A1A),
    Color(0xFF000000),
)
private val ARIB_RAMP_COLORS = listOf(Color(0xFF080808), Color(0xFFEBEBEB))
private val ARIB_DIM_SCRIM = Color.Black.copy(alpha = 0.22f)
private val ARIB_SCANLINE_COLOR = Color.Black.copy(alpha = 0.14f)

private val STATIC_HUM_COLORS = listOf(
    Color.Transparent,
    Color.White.copy(alpha = 0.075f),
    Color.White.copy(alpha = 0.03f),
    Color.Transparent,
)
private val STATIC_VIGNETTE_COLORS = listOf(
    Color.Transparent,
    Color.Black.copy(alpha = 0.22f),
    Color.Black.copy(alpha = 0.72f),
)
private val STATIC_SCANLINE_COLOR = Color.Black.copy(alpha = 0.26f)

private val WAVE_GRID_COLOR = Color(0xFF2DD4BF).copy(alpha = 0.06f)
private val WAVE_AXIS_COLOR = Color(0xFF2DD4BF).copy(alpha = 0.14f)
private val WAVE_TRACE_OUTER = Color(0xFF2DD4BF).copy(alpha = 0.15f)
private val WAVE_TRACE_MID = Color(0xFF2DD4BF).copy(alpha = 0.38f)
private val WAVE_TRACE_CORE = Color(0xFFCCFBF1)
private val WAVE_STROKE_OUTER = Stroke(width = 14f, cap = StrokeCap.Round, join = StrokeJoin.Round)
private val WAVE_STROKE_MID = Stroke(width = 6f, cap = StrokeCap.Round, join = StrokeJoin.Round)
private val WAVE_STROKE_CORE = Stroke(width = 2.2f, cap = StrokeCap.Round, join = StrokeJoin.Round)

private val AURORA_ORB1_COLORS = listOf(Color(0xFF3B82F6).copy(alpha = 0.24f), Color.Transparent)
private val AURORA_ORB2_COLORS = listOf(Color(0xFF14B8A6).copy(alpha = 0.22f), Color.Transparent)
private val AURORA_ORB3_COLORS = listOf(Color(0xFF8B5CF6).copy(alpha = 0.20f), Color.Transparent)

private val RADAR_ACCENT = Color(0xFF38BDF8)
private val RADAR_RING_COLOR = RADAR_ACCENT.copy(alpha = 0.09f)
private val RADAR_CROSSHAIR_COLOR = RADAR_ACCENT.copy(alpha = 0.06f)
private val RADAR_SWEEP_LINE_COLORS = listOf(RADAR_ACCENT.copy(alpha = 0.55f), RADAR_ACCENT.copy(alpha = 0.05f))
private val RADAR_SWEEP_CONE_STOPS = arrayOf(
    0.00f to Color.Transparent,
    0.84f to Color.Transparent,
    0.96f to RADAR_ACCENT.copy(alpha = 0.11f),
    1.00f to RADAR_ACCENT.copy(alpha = 0.24f),
)
private val RADAR_RING_STROKE = Stroke(width = 1.2f)
private val RADAR_RIPPLE_STROKE = Stroke(width = 2.4f)

@Composable
internal fun TifNoSignalEffectSurface(
    style: TifNoSignalStyle,
    statusText: String,
    inputLabel: String,
    isPassthroughInput: Boolean,
    showOverlay: Boolean,
    paused: Boolean,
    dimmed: Boolean,
    onDegraded: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .graphicsLayer { alpha = if (dimmed || paused) 0.45f else 1f }
            .testTag(TIF_NO_SIGNAL_EFFECT_TAG),
    ) {
        when (style) {
            TifNoSignalStyle.ARIB_COLOR_BARS -> AribColorBarsEffect(
                statusText = statusText,
                inputLabel = inputLabel,
                paused = paused,
            )
            TifNoSignalStyle.ANALOG_STATIC -> AnalogStaticEffect(
                statusText = statusText,
                inputLabel = inputLabel,
                showOverlay = showOverlay,
                paused = paused,
                onDegraded = onDegraded,
            )
            TifNoSignalStyle.SIGNAL_WAVE -> SignalWaveEffect(
                statusText = statusText,
                inputLabel = inputLabel,
                paused = paused,
                onDegraded = onDegraded,
            )
            TifNoSignalStyle.MINIMAL_CLOCK -> MinimalClockEffect(
                statusText = statusText,
                inputLabel = inputLabel,
                isPassthroughInput = isPassthroughInput,
                paused = paused,
            )
            TifNoSignalStyle.AURORA_AMBIENT -> AuroraAmbientEffect(
                statusText = statusText,
                inputLabel = inputLabel,
                isPassthroughInput = isPassthroughInput,
                paused = paused,
                onDegraded = onDegraded,
            )
            TifNoSignalStyle.DOT_MATRIX -> DotMatrixEffect(
                statusText = statusText,
                inputLabel = inputLabel,
                paused = paused,
                onDegraded = onDegraded,
            )
            TifNoSignalStyle.STARFIELD -> StarfieldEffect(
                statusText = statusText,
                inputLabel = inputLabel,
                paused = paused,
                onDegraded = onDegraded,
            )
            TifNoSignalStyle.SIGNAL_RADAR -> SignalRadarEffect(
                statusText = statusText,
                inputLabel = inputLabel,
                isPassthroughInput = isPassthroughInput,
                paused = paused,
                onDegraded = onDegraded,
            )
            else -> Unit
        }
    }
}

@Composable
private fun rememberElapsedSeconds(
    paused: Boolean,
    onDegraded: () -> Unit,
): State<Float> {
    val elapsed = remember { mutableFloatStateOf(0f) }
    val latestOnDegraded by rememberUpdatedState(onDegraded)
    LaunchedEffect(paused) {
        if (paused) return@LaunchedEffect
        var lastFrameNanos = 0L
        val dtRing = LongArray(EFFECT_JANK_WINDOW_FRAMES)
        var ringCursor = 0
        var filledFrames = 0
        var slowCount = 0

        while (isActive) {
            withFrameNanos { frameNanos ->
                if (lastFrameNanos != 0L) {
                    val rawDt = (frameNanos - lastFrameNanos).coerceAtLeast(0L)
                    if (filledFrames == EFFECT_JANK_WINDOW_FRAMES && dtRing[ringCursor] > EFFECT_JANK_DT_NANOS) {
                        slowCount--
                    }
                    dtRing[ringCursor] = rawDt
                    if (rawDt > EFFECT_JANK_DT_NANOS) slowCount++
                    ringCursor = (ringCursor + 1) % EFFECT_JANK_WINDOW_FRAMES
                    if (filledFrames < EFFECT_JANK_WINDOW_FRAMES) filledFrames++

                    if (slowCount > EFFECT_JANK_SLOW_THRESHOLD) {
                        latestOnDegraded()
                        return@withFrameNanos
                    }

                    val clampedSec = (rawDt / 1_000_000_000f).coerceAtMost(0.1f)
                    elapsed.floatValue += clampedSec
                }
                lastFrameNanos = frameNanos
            }
            if (slowCount > EFFECT_JANK_SLOW_THRESHOLD) break
        }
    }
    return elapsed
}

// ---------------------------------------------------------------------------
// 1. ARIB STD-B28 inspired 75% Broadcast Color Bars
// ---------------------------------------------------------------------------
@Composable
private fun AribColorBarsEffect(
    statusText: String,
    inputLabel: String,
    paused: Boolean,
) {
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(paused) {
        if (paused) return@LaunchedEffect
        while (isActive) {
            delay(1000L)
            tick++
        }
    }
    // Gentle OLED burn-in pixel shift over time (with slight overscan so edges stay seamless)
    val shiftX = sin(tick * 0.06f) * 8f
    val shiftY = cos(tick * 0.045f) * 5f
    val tallyOn = (tick % 2) == 0

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        Spacer(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = 1.025f
                    scaleY = 1.025f
                    translationX = shiftX
                    translationY = shiftY
                }
                .drawWithCache {
                    val w = size.width
                    val h = size.height
                    val sideW = w * 0.125f
                    val centerW = w - sideW * 2f
                    val barW = centerW / 7f

                    val topH = h * (7f / 12f)
                    val midH = h * (1f / 12f)
                    val rampH = h * (1f / 12f)
                    val rampY = topH + midH
                    val botY = rampY + rampH
                    val botH = h - botY
                    val scanStep = 3.dp.toPx().coerceAtLeast(4f)

                    val rampBrush = Brush.horizontalGradient(
                        colors = ARIB_RAMP_COLORS,
                        startX = sideW,
                        endX = sideW + centerW,
                    )

                    onDrawBehind {
                        // Pattern 1: Top 75% color bars with 40% gray side panels
                        drawRect(ARIB_GRAY_40, Offset.Zero, Size(sideW, topH))
                        for (i in ARIB_MAIN_BARS.indices) {
                            val left = sideW + i * barW
                            val right = if (i == ARIB_MAIN_BARS.lastIndex) w - sideW else sideW + (i + 1) * barW
                            drawRect(ARIB_MAIN_BARS[i], Offset(left, 0f), Size(right - left, topH))
                        }
                        drawRect(ARIB_GRAY_40, Offset(w - sideW, 0f), Size(sideW, topH))

                        // Pattern 2: Middle chroma / 100% white strip
                        drawRect(Color(0xFF00BFBF), Offset(0f, topH), Size(sideW, midH))
                        drawRect(Color(0xFF009999), Offset(sideW, topH), Size(barW, midH))
                        drawRect(Color(0xFFFFFFFF), Offset(sideW + barW, topH), Size(barW * 6f, midH))
                        drawRect(Color(0xFF0000BF), Offset(w - sideW, topH), Size(sideW, midH))

                        // Pattern 3: Luminance ramp
                        drawRect(Color(0xFFBFBF00), Offset(0f, rampY), Size(sideW, rampH))
                        drawRect(
                            brush = rampBrush,
                            topLeft = Offset(sideW, rampY),
                            size = Size(centerW, rampH),
                        )
                        drawRect(Color(0xFFBF0000), Offset(w - sideW, rampY), Size(sideW, rampH))

                        // Pattern 4: Bottom PLUGE & black/white reference blocks
                        drawRect(ARIB_GRAY_15, Offset(0f, botY), Size(sideW, botH))
                        for (i in ARIB_PLUGE_COLORS.indices) {
                            val left = sideW + i * barW
                            val right = if (i == ARIB_PLUGE_COLORS.lastIndex) w - sideW else sideW + (i + 1) * barW
                            drawRect(ARIB_PLUGE_COLORS[i], Offset(left, botY), Size(right - left, botH))
                        }
                        drawRect(ARIB_GRAY_15, Offset(w - sideW, botY), Size(sideW, botH))

                        // Subtle broadcast scanlines + dark-room luminance softening
                        drawRect(ARIB_DIM_SCRIM)
                        var y = 0f
                        while (y < h) {
                            drawLine(
                                color = ARIB_SCANLINE_COLOR,
                                start = Offset(0f, y),
                                end = Offset(w, y),
                                strokeWidth = 1.5f,
                            )
                            y += scanStep
                        }
                    }
                },
        )

        // Upper-center broadcast monitor card (safe above ~110dp bottom preview HUD)
        val cardShape = RoundedCornerShape(12.dp)
        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .padding(bottom = 52.dp)
                .graphicsLayer {
                    translationX = -shiftX * 0.5f
                    translationY = -shiftY * 0.5f
                }
                .background(Color(0xEB0B0D12), cardShape)
                .border(1.5.dp, Color.White.copy(alpha = 0.28f), cardShape)
                .padding(horizontal = 32.dp, vertical = 18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(9.dp)
                        .background(
                            if (tallyOn) Color(0xFFEF4444) else Color(0xFF7F1D1D),
                            CircleShape,
                        ),
                )
                Text(
                    text = "NO SIGNAL",
                    color = Color.White,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    letterSpacing = 4.sp,
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "$inputLabel · $statusText",
                color = Color.White.copy(alpha = 0.8f),
                fontSize = 14.sp,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 420.dp),
            )
        }
    }
}

// ---------------------------------------------------------------------------
// 2. Vintage CRT Analog Static / Snow
// ---------------------------------------------------------------------------
private const val STATIC_FRAME_COUNT = 10
private const val STATIC_TEX_W = 192
private const val STATIC_TEX_H = 108

private fun buildStaticFrames(): List<ImageBitmap> {
    val rng = Random(20261123)
    val pixels = IntArray(STATIC_TEX_W * STATIC_TEX_H)
    return List(STATIC_FRAME_COUNT) {
        for (i in pixels.indices) {
            // Soft low-contrast vintage phosphor snow (16..138) with subtle cool CRT cast
            val base = (rng.nextInt(16, 132) + rng.nextInt(16, 132)) ushr 1
            val spike = if (rng.nextInt(16) == 0) rng.nextInt(-10, 18) else 0
            val v = (base + spike).coerceIn(14, 146)
            val b = (v + 4).coerceAtMost(150)
            pixels[i] = (0xFF shl 24) or (v shl 16) or (v shl 8) or b
        }
        Bitmap.createBitmap(pixels, STATIC_TEX_W, STATIC_TEX_H, Bitmap.Config.ARGB_8888).asImageBitmap()
    }
}

@Composable
private fun AnalogStaticEffect(
    statusText: String,
    inputLabel: String,
    showOverlay: Boolean,
    paused: Boolean,
    onDegraded: () -> Unit,
) {
    val frames = remember { buildStaticFrames() }
    val elapsedState = rememberElapsedSeconds(paused = paused, onDegraded = onDegraded)

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        Spacer(
            modifier = Modifier
                .fillMaxSize()
                .drawWithCache {
                    val dstSize = IntSize(
                        size.width.toInt().coerceAtLeast(1),
                        size.height.toInt().coerceAtLeast(1),
                    )
                    val humBandH = (size.height * 0.14f).coerceAtLeast(80f)
                    val humBrush = Brush.verticalGradient(
                        colors = STATIC_HUM_COLORS,
                        startY = 0f,
                        endY = humBandH,
                    )
                    val vignetteBrush = Brush.radialGradient(
                        colors = STATIC_VIGNETTE_COLORS,
                        center = Offset(size.width / 2f, size.height / 2f),
                        radius = size.maxDimension * 0.68f,
                    )
                    val scanStep = 2.5.dp.toPx().coerceAtLeast(4f)

                    onDrawBehind {
                        val elapsed = elapsedState.value
                        val frameIdx = ((elapsed * 18f).toInt().coerceAtLeast(0)) % frames.size
                        drawImage(
                            image = frames[frameIdx],
                            dstOffset = IntOffset.Zero,
                            dstSize = dstSize,
                            filterQuality = FilterQuality.None,
                        )

                        // Slow drifting analog 60Hz hum bar (zero per-frame brush allocation)
                        val humY = ((elapsed * 0.14f) % 1.3f - 0.15f) * size.height - humBandH * 0.5f
                        translate(top = humY) {
                            drawRect(
                                brush = humBrush,
                                size = Size(size.width, humBandH),
                            )
                        }

                        // CRT scanlines
                        var y = 0f
                        while (y < size.height) {
                            drawLine(
                                color = STATIC_SCANLINE_COLOR,
                                start = Offset(0f, y),
                                end = Offset(size.width, y),
                                strokeWidth = 1.8f,
                            )
                            y += scanStep
                        }

                        // CRT corner vignette
                        drawRect(brush = vignetteBrush)
                    }
                },
        )

        if (!showOverlay) {
            // Retro CRT phosphor-green OSD (top-left safe area, hidden when live OSD is visible)
            val osdShape = RoundedCornerShape(6.dp)
            Column(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(start = 52.dp, top = 44.dp)
                    .graphicsLayer {
                        val elapsed = elapsedState.value
                        translationX = sin(elapsed * 0.12f) * 14f
                        translationY = cos(elapsed * 0.09f) * 8f
                    }
                    .background(Color(0x9906120A), osdShape)
                    .border(1.dp, Color(0xFF4ADE80).copy(alpha = 0.45f), osdShape)
                    .padding(horizontal = 18.dp, vertical = 12.dp),
            ) {
                Text(
                    text = inputLabel.uppercase(Locale.US),
                    color = Color(0xFF4ADE80),
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    letterSpacing = 2.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 360.dp),
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "NO SIGNAL  //  $statusText",
                    color = Color(0xFF86EFAC).copy(alpha = 0.9f),
                    fontSize = 13.sp,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 420.dp),
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 3. Oscilloscope Signal Wave (OLED-friendly)
// ---------------------------------------------------------------------------
@Composable
private fun SignalWaveEffect(
    statusText: String,
    inputLabel: String,
    paused: Boolean,
    onDegraded: () -> Unit,
) {
    val elapsedState = rememberElapsedSeconds(paused = paused, onDegraded = onDegraded)
    val wavePath = remember { Path() }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val elapsed = elapsedState.value
            val w = size.width
            val h = size.height
            val midY = h * 0.42f

            // Subtle oscilloscope reticle
            val cols = 12
            val rows = 6
            for (i in 1 until cols) {
                val x = w * i / cols
                drawLine(WAVE_GRID_COLOR, Offset(x, 0f), Offset(x, h), strokeWidth = 1f)
            }
            for (j in 1 until rows) {
                val y = h * j / rows
                drawLine(WAVE_GRID_COLOR, Offset(0f, y), Offset(w, y), strokeWidth = 1f)
            }
            // Center baseline axis with subtle graticule ticks
            drawLine(WAVE_AXIS_COLOR, Offset(0f, midY), Offset(w, midY), strokeWidth = 1f)
            val tickStep = w / 48f
            val tickHalf = 4f
            var tx = tickStep
            while (tx < w) {
                drawLine(WAVE_AXIS_COLOR, Offset(tx, midY - tickHalf), Offset(tx, midY + tickHalf), strokeWidth = 1f)
                tx += tickStep
            }

            // Travelling signal-probe pulse wave
            val cycleSec = 3.0f
            val pulseCenter = ((elapsed % cycleSec) / cycleSec) * 1.4f - 0.2f
            val amp = h * 0.115f
            val samples = 140

            wavePath.reset()
            for (i in 0..samples) {
                val u = i.toFloat() / samples
                val x = u * w
                val dist = u - pulseCenter
                val envelope = exp(-(dist * dist) / 0.0022f)
                val carrier = sin(dist * 75f)
                val microNoise = sin(u * 26f + elapsed * 2.4f) * 2.2f
                val y = midY - (envelope * carrier * amp) + microNoise
                if (i == 0) wavePath.moveTo(x, y) else wavePath.lineTo(x, y)
            }

            drawPath(path = wavePath, color = WAVE_TRACE_OUTER, style = WAVE_STROKE_OUTER)
            drawPath(path = wavePath, color = WAVE_TRACE_MID, style = WAVE_STROKE_MID)
            drawPath(path = wavePath, color = WAVE_TRACE_CORE, style = WAVE_STROKE_CORE)

            // Phosphor hotspot riding the pulse center when on screen
            if (pulseCenter in 0f..1f) {
                val px = pulseCenter * w
                val py = midY + sin(pulseCenter * 26f + elapsed * 2.4f) * 2.2f
                drawCircle(color = WAVE_TRACE_OUTER, radius = 16f, center = Offset(px, py))
                drawCircle(color = WAVE_TRACE_CORE.copy(alpha = 0.85f), radius = 3.5f, center = Offset(px, py))
            }
        }

        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .padding(top = 120.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = inputLabel,
                color = Color(0xFF99F6E4),
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                fontFamily = FontFamily.Monospace,
                letterSpacing = 2.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 420.dp),
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = statusText,
                color = Color.White.copy(alpha = 0.58f),
                fontSize = 13.sp,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 420.dp),
            )
        }
    }
}

// ---------------------------------------------------------------------------
// 4. Minimal Standby Clock (OLED power-saving + burn-in pixel shift)
// ---------------------------------------------------------------------------
@Composable
private fun MinimalClockEffect(
    statusText: String,
    inputLabel: String,
    isPassthroughInput: Boolean,
    paused: Boolean,
) {
    var nowMillis by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(paused) {
        if (paused) return@LaunchedEffect
        while (isActive) {
            nowMillis = System.currentTimeMillis()
            delay(1000L)
        }
    }

    val timeFmt = remember { SimpleDateFormat("HH:mm", Locale.JAPANESE) }
    val dateFmt = remember { SimpleDateFormat("M月d日 (E)", Locale.JAPANESE) }
    val minuteBucket = (nowMillis / 60_000L).toInt()
    val timeText = remember(minuteBucket) { timeFmt.format(Date(nowMillis)) }
    val dateText = remember(minuteBucket) { dateFmt.format(Date(nowMillis)) }
    val shiftX = sin(minuteBucket * 0.7f) * 32f
    val shiftY = cos(minuteBucket * 0.5f) * 18f

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .padding(bottom = 52.dp)
                .graphicsLayer {
                    translationX = shiftX
                    translationY = shiftY
                },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = timeText,
                style = TextStyle(
                    color = Color.White.copy(alpha = 0.92f),
                    fontSize = 104.sp,
                    lineHeight = 110.sp,
                    fontWeight = FontWeight.ExtraLight,
                    letterSpacing = 4.sp,
                    fontFeatureSettings = "tnum",
                ),
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = dateText,
                style = TextStyle(
                    color = Color.White.copy(alpha = 0.55f),
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Normal,
                    letterSpacing = 2.sp,
                    fontFeatureSettings = "tnum",
                ),
            )
            Spacer(modifier = Modifier.height(24.dp))
            Row(
                modifier = Modifier
                    .background(Color.White.copy(alpha = 0.06f), RoundedCornerShape(999.dp))
                    .border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(999.dp))
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = if (isPassthroughInput) Icons.Default.SettingsInputHdmi else Icons.Default.LiveTv,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.7f),
                    modifier = Modifier.size(16.dp),
                )
                Text(
                    text = "$inputLabel · $statusText",
                    color = Color.White.copy(alpha = 0.72f),
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 380.dp),
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 5. Aurora Ambient Gradient
// ---------------------------------------------------------------------------
@Composable
private fun AuroraAmbientEffect(
    statusText: String,
    inputLabel: String,
    isPassthroughInput: Boolean,
    paused: Boolean,
    onDegraded: () -> Unit,
) {
    val elapsedState = rememberElapsedSeconds(paused = paused, onDegraded = onDegraded)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF05070E)),
        contentAlignment = Alignment.Center,
    ) {
        Spacer(
            modifier = Modifier
                .fillMaxSize()
                .drawWithCache {
                    val w = size.width
                    val h = size.height
                    val baseR = size.maxDimension * 0.55f
                    val r1 = baseR * 0.95f
                    val r2 = baseR * 0.88f
                    val r3 = baseR * 0.82f

                    val orb1Brush = Brush.radialGradient(
                        colors = AURORA_ORB1_COLORS,
                        center = Offset.Zero,
                        radius = r1,
                    )
                    val orb2Brush = Brush.radialGradient(
                        colors = AURORA_ORB2_COLORS,
                        center = Offset.Zero,
                        radius = r2,
                    )
                    val orb3Brush = Brush.radialGradient(
                        colors = AURORA_ORB3_COLORS,
                        center = Offset.Zero,
                        radius = r3,
                    )

                    onDrawBehind {
                        val elapsed = elapsedState.value
                        val x1 = w * (0.28f + 0.16f * sin(elapsed * 0.22f))
                        val y1 = h * (0.34f + 0.14f * cos(elapsed * 0.18f))
                        translate(left = x1, top = y1) {
                            drawCircle(brush = orb1Brush, radius = r1, center = Offset.Zero)
                        }

                        val x2 = w * (0.72f + 0.14f * cos(elapsed * 0.19f))
                        val y2 = h * (0.58f + 0.15f * sin(elapsed * 0.24f))
                        translate(left = x2, top = y2) {
                            drawCircle(brush = orb2Brush, radius = r2, center = Offset.Zero)
                        }

                        val x3 = w * (0.52f + 0.18f * sin(elapsed * 0.15f + 1.5f))
                        val y3 = h * (0.28f + 0.16f * cos(elapsed * 0.21f + 0.8f))
                        translate(left = x3, top = y3) {
                            drawCircle(brush = orb3Brush, radius = r3, center = Offset.Zero)
                        }
                    }
                },
        )

        val cardShape = RoundedCornerShape(20.dp)
        Column(
            modifier = Modifier
                .padding(bottom = 48.dp)
                .background(Color(0x880B101C), cardShape)
                .border(1.dp, Color.White.copy(alpha = 0.14f), cardShape)
                .padding(horizontal = 36.dp, vertical = 22.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                imageVector = if (isPassthroughInput) Icons.Default.SettingsInputHdmi else Icons.Default.LiveTv,
                contentDescription = null,
                tint = Color(0xFF93C5FD),
                modifier = Modifier.size(28.dp),
            )
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = inputLabel,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color = Color.White.copy(alpha = 0.94f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 360.dp),
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = statusText,
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.68f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 380.dp),
            )
        }
    }
}

// ---------------------------------------------------------------------------
// 6. Retro LED Dot Matrix Display
// ---------------------------------------------------------------------------
private const val DOT_MESSAGE = "NO SIGNAL"
private const val DOT_ROWS = 7
private const val DOT_COLS = DOT_MESSAGE.length * 6 - 1

private val DOT_GLYPHS: Map<Char, IntArray> = mapOf(
    'N' to intArrayOf(0b10001, 0b11001, 0b10101, 0b10011, 0b10001, 0b10001, 0b10001),
    'O' to intArrayOf(0b01110, 0b10001, 0b10001, 0b10001, 0b10001, 0b10001, 0b01110),
    'S' to intArrayOf(0b01111, 0b10000, 0b10000, 0b01110, 0b00001, 0b00001, 0b11110),
    'I' to intArrayOf(0b01110, 0b00100, 0b00100, 0b00100, 0b00100, 0b00100, 0b01110),
    'G' to intArrayOf(0b01110, 0b10001, 0b10000, 0b10111, 0b10001, 0b10001, 0b01110),
    'A' to intArrayOf(0b01110, 0b10001, 0b10001, 0b11111, 0b10001, 0b10001, 0b10001),
    'L' to intArrayOf(0b10000, 0b10000, 0b10000, 0b10000, 0b10000, 0b10000, 0b11111),
    ' ' to intArrayOf(0, 0, 0, 0, 0, 0, 0),
)

// Precomputed flat coordinate arrays for lit LEDs (zero map/char boxing in draw loop)
private val DOT_LIT_COORDS: IntArray = run {
    val coords = ArrayList<Int>(256)
    for (charIdx in DOT_MESSAGE.indices) {
        val glyph = DOT_GLYPHS[DOT_MESSAGE[charIdx]] ?: continue
        for (r in 0 until DOT_ROWS) {
            val rowBits = glyph[r]
            for (bit in 0 until 5) {
                if (((rowBits shr (4 - bit)) and 1) == 1) {
                    coords.add(charIdx * 6 + bit)
                    coords.add(r)
                }
            }
        }
    }
    IntArray(coords.size) { coords[it] }
}

private val DOT_PANEL_BG = Color(0xFF0A0C10)
private val DOT_PANEL_BORDER = Color.White.copy(alpha = 0.08f)
private val DOT_PANEL_BORDER_STROKE = Stroke(width = 1.5f)
private val DOT_OFF_COLOR = Color(0xFF1A1D24)
private val DOT_AMBER = Color(0xFFF59E0B)
private val DOT_CORE = Color(0xFFFEF3C7)

@Composable
private fun DotMatrixEffect(
    statusText: String,
    inputLabel: String,
    paused: Boolean,
    onDegraded: () -> Unit,
) {
    val elapsedState = rememberElapsedSeconds(paused = paused, onDegraded = onDegraded)

    Box(modifier = Modifier.fillMaxSize().background(Color(0xFF030406))) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val elapsed = elapsedState.value
            val pitch = min(size.width * 0.62f / DOT_COLS, size.height * 0.21f / DOT_ROWS)
            val radius = pitch * 0.34f
            val totalW = DOT_COLS * pitch
            val totalH = DOT_ROWS * pitch
            val originX = (size.width - totalW) / 2f
            val originY = size.height * 0.41f - totalH / 2f

            // Subtle studio LED bezel housing around the dot matrix
            val padCols = 4
            val padRows = 2
            val panelLeft = originX - (padCols + 0.8f) * pitch
            val panelTop = originY - (padRows + 0.8f) * pitch
            val panelW = (DOT_COLS + (padCols + 0.8f) * 2f) * pitch
            val panelH = (DOT_ROWS + (padRows + 0.8f) * 2f) * pitch
            val corner = CornerRadius(pitch * 1.1f, pitch * 1.1f)
            drawRoundRect(
                color = DOT_PANEL_BG,
                topLeft = Offset(panelLeft, panelTop),
                size = Size(panelW, panelH),
                cornerRadius = corner,
            )
            drawRoundRect(
                color = DOT_PANEL_BORDER,
                topLeft = Offset(panelLeft, panelTop),
                size = Size(panelW, panelH),
                cornerRadius = corner,
                style = DOT_PANEL_BORDER_STROKE,
            )

            // Unlit matrix dots
            val offRadius = radius * 0.72f
            for (r in -padRows until (DOT_ROWS + padRows)) {
                val cy = originY + (r + 0.5f) * pitch
                for (c in -padCols until (DOT_COLS + padCols)) {
                    val cx = originX + (c + 0.5f) * pitch
                    drawCircle(DOT_OFF_COLOR, radius = offRadius, center = Offset(cx, cy))
                }
            }

            // Lit amber LEDs with subtle horizontal shimmer wave
            val haloRadius = radius * 1.9f
            val coreRadius = radius * 0.45f
            var idx = 0
            while (idx < DOT_LIT_COORDS.size) {
                val col = DOT_LIT_COORDS[idx]
                val row = DOT_LIT_COORDS[idx + 1]
                val shimmer = 0.78f + 0.22f * sin(elapsed * 2.2f - col * 0.14f)
                val center = Offset(
                    originX + (col + 0.5f) * pitch,
                    originY + (row + 0.5f) * pitch,
                )
                drawCircle(color = DOT_AMBER.copy(alpha = 0.22f * shimmer), radius = haloRadius, center = center)
                drawCircle(color = DOT_AMBER.copy(alpha = 0.95f * shimmer), radius = radius, center = center)
                drawCircle(color = DOT_CORE.copy(alpha = 0.7f * shimmer), radius = coreRadius, center = center)
                idx += 2
            }
        }

        Row(
            modifier = Modifier
                .align(Alignment.Center)
                .padding(top = 128.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .graphicsLayer {
                        alpha = 0.35f + 0.65f * ((sin(elapsedState.value * 2.5f) + 1f) * 0.5f)
                    }
                    .background(DOT_AMBER, CircleShape),
            )
            Text(
                text = "$inputLabel · $statusText",
                color = Color(0xFFFDE68A).copy(alpha = 0.78f),
                fontSize = 14.sp,
                fontFamily = FontFamily.Monospace,
                letterSpacing = 1.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 460.dp),
            )
        }
    }
}

// ---------------------------------------------------------------------------
// 7. Classic 3D Starfield Drift
// ---------------------------------------------------------------------------
private class StarSeed(
    val x: Float,
    val y: Float,
    val zOffset: Float,
    val tint: Color,
)

private fun buildStars(count: Int = 96): Array<StarSeed> {
    val rng = Random(42069)
    val tints = arrayOf(
        Color(0xFFFFFFFF),
        Color(0xFFE0F2FE),
        Color(0xFFBAE6FD),
        Color(0xFFEDE9FE),
    )
    return Array(count) {
        StarSeed(
            x = rng.nextFloat() * 2f - 1f,
            y = rng.nextFloat() * 2f - 1f,
            zOffset = rng.nextFloat(),
            tint = tints[rng.nextInt(tints.size)],
        )
    }
}

@Composable
private fun StarfieldEffect(
    statusText: String,
    inputLabel: String,
    paused: Boolean,
    onDegraded: () -> Unit,
) {
    val stars = remember { buildStars() }
    val elapsedState = rememberElapsedSeconds(paused = paused, onDegraded = onDegraded)

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val elapsed = elapsedState.value
            val cx = size.width / 2f
            val cy = size.height * 0.45f
            val projX = cx * 0.65f
            val projY = (size.height / 2f) * 0.65f
            val speed = 0.14f

            for (i in stars.indices) {
                val star = stars[i]
                var z = (star.zOffset - elapsed * speed) % 1f
                if (z <= 0.02f) z += 1f
                val prevZ = (z + 0.035f).coerceAtMost(1f)

                val sx = cx + (star.x / z) * projX
                val sy = cy + (star.y / z) * projY
                if (sx < -20f || sx > size.width + 20f || sy < -20f || sy > size.height + 20f) continue

                val px = cx + (star.x / prevZ) * projX
                val py = cy + (star.y / prevZ) * projY
                val closeness = (1f - z).coerceIn(0f, 1f)
                val farFade = ((1f - z) / 0.15f).coerceIn(0f, 1f)
                val alpha = (closeness * closeness * farFade).coerceIn(0.04f, 0.95f)
                val radius = 1.0f + closeness * 2.6f

                drawLine(
                    color = star.tint.copy(alpha = alpha * 0.45f),
                    start = Offset(px, py),
                    end = Offset(sx, sy),
                    strokeWidth = radius * 0.8f,
                    cap = StrokeCap.Round,
                )
                drawCircle(
                    color = star.tint.copy(alpha = alpha),
                    radius = radius,
                    center = Offset(sx, sy),
                )
            }
        }

        val badgeShape = RoundedCornerShape(16.dp)
        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .padding(bottom = 48.dp)
                .background(Color.Black.copy(alpha = 0.58f), badgeShape)
                .border(1.dp, Color.White.copy(alpha = 0.14f), badgeShape)
                .padding(horizontal = 24.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = inputLabel,
                color = Color.White.copy(alpha = 0.9f),
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 1.5.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 360.dp),
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = statusText,
                color = Color.White.copy(alpha = 0.6f),
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 360.dp),
            )
        }
    }
}

// ---------------------------------------------------------------------------
// 8. Signal Search Radar Ripples
// ---------------------------------------------------------------------------
@Composable
private fun SignalRadarEffect(
    statusText: String,
    inputLabel: String,
    isPassthroughInput: Boolean,
    paused: Boolean,
    onDegraded: () -> Unit,
) {
    val elapsedState = rememberElapsedSeconds(paused = paused, onDegraded = onDegraded)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF04070C)),
        contentAlignment = Alignment.Center,
    ) {
        Spacer(
            modifier = Modifier
                .fillMaxSize()
                .drawWithCache {
                    val center = Offset(size.width / 2f, size.height * 0.42f)
                    val maxR = size.minDimension * 0.37f
                    val sweepEnd = Offset(center.x + maxR, center.y)
                    val sweepConeBrush = Brush.sweepGradient(
                        *RADAR_SWEEP_CONE_STOPS,
                        center = center,
                    )
                    val sweepLineBrush = Brush.linearGradient(
                        colors = RADAR_SWEEP_LINE_COLORS,
                        start = center,
                        end = sweepEnd,
                    )

                    onDrawBehind {
                        val elapsed = elapsedState.value

                        // Static faint range rings & crosshair
                        for (i in 1..3) {
                            drawCircle(
                                color = RADAR_RING_COLOR,
                                radius = maxR * (i / 3f),
                                center = center,
                                style = RADAR_RING_STROKE,
                            )
                        }
                        drawLine(
                            color = RADAR_CROSSHAIR_COLOR,
                            start = Offset(center.x - maxR, center.y),
                            end = Offset(center.x + maxR, center.y),
                            strokeWidth = 1f,
                        )
                        drawLine(
                            color = RADAR_CROSSHAIR_COLOR,
                            start = Offset(center.x, center.y - maxR),
                            end = Offset(center.x, center.y + maxR),
                            strokeWidth = 1f,
                        )

                        // Rotating radar phosphor afterglow cone + leading beam (zero per-frame allocations)
                        val angleDeg = (elapsed * 48f) % 360f
                        rotate(degrees = angleDeg, pivot = center) {
                            drawCircle(
                                brush = sweepConeBrush,
                                radius = maxR,
                                center = center,
                            )
                            drawLine(
                                brush = sweepLineBrush,
                                start = center,
                                end = sweepEnd,
                                strokeWidth = 2.4f,
                                cap = StrokeCap.Round,
                            )
                        }

                        // 3 staggered expanding sonar ripples
                        val ringCount = 3
                        for (i in 0 until ringCount) {
                            val progress = ((elapsed * 0.34f + i.toFloat() / ringCount) % 1f)
                            val radius = 34f + progress * (maxR - 34f)
                            val alpha = (1f - progress) * (1f - progress) * 0.55f
                            drawCircle(
                                color = RADAR_ACCENT.copy(alpha = alpha),
                                radius = radius,
                                center = center,
                                style = RADAR_RIPPLE_STROKE,
                            )
                        }
                    }
                },
        )

        Column(
            modifier = Modifier.padding(bottom = 52.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .background(Color(0xFF0C1929), CircleShape)
                    .border(1.5.dp, RADAR_ACCENT.copy(alpha = 0.55f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = if (isPassthroughInput) Icons.Default.SettingsInputHdmi else Icons.Default.LiveTv,
                    contentDescription = null,
                    tint = Color(0xFF7DD3FC),
                    modifier = Modifier.size(26.dp),
                )
            }
            Spacer(modifier = Modifier.height(14.dp))
            Text(
                text = inputLabel,
                color = Color.White.copy(alpha = 0.92f),
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 1.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 360.dp),
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = statusText,
                color = Color(0xFFBAE6FD).copy(alpha = 0.72f),
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 360.dp),
            )
        }
    }
}
