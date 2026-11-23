package com.beeregg2001.komorebi.ui.live

import android.graphics.Bitmap
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
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
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.beeregg2001.komorebi.R
import com.beeregg2001.komorebi.data.TifNoSignalAccentColor
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
private val ARIB_WHITE_75 = Color(0xFFBFBFBF)
private val ARIB_WHITE_100 = Color(0xFFFFFFFF)
private val ARIB_MAIN_BARS = arrayOf(
    ARIB_WHITE_75,     // 75% White
    Color(0xFFBFBF00), // 75% Yellow
    Color(0xFF00BFBF), // 75% Cyan
    Color(0xFF00BF00), // 75% Green
    Color(0xFFBF00BF), // 75% Magenta
    Color(0xFFBF0000), // 75% Red
    Color(0xFF0000BF), // 75% Blue
)
private val ARIB_RAMP_STOPS = arrayOf(
    0.00f to Color(0xFF000000),
    0.18f to Color(0xFF000000),
    0.82f to Color(0xFFFFFFFF),
    1.00f to Color(0xFFFFFFFF),
)
private val ARIB_TELOP_OUTLINE_STROKE = Stroke(width = 5.5f, join = StrokeJoin.Round)

private val STATIC_HUM_COLORS = listOf(
    Color.Transparent,
    Color.Black.copy(alpha = 0.22f),
    Color.White.copy(alpha = 0.10f),
    Color.Transparent,
)
private val STATIC_VIGNETTE_COLORS = listOf(
    Color.Transparent,
    Color.Black.copy(alpha = 0.16f),
    Color.Black.copy(alpha = 0.68f),
)
private val STATIC_SCANLINE_COLOR = Color.Black.copy(alpha = 0.34f)
private val STATIC_OSD_SHADOW = Shadow(
    color = Color.Black,
    offset = Offset(3f, 3f),
    blurRadius = 1f,
)

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
    accentColor: TifNoSignalAccentColor = TifNoSignalAccentColor.RANDOM,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .graphicsLayer { alpha = if (dimmed || paused) 0.45f else 1f }
            .testTag(TIF_NO_SIGNAL_EFFECT_TAG),
    ) {
        when (style) {
            TifNoSignalStyle.NOK_CALLSIGN -> NokCallsignEffect(
                statusText = statusText,
                inputLabel = inputLabel,
                accentColor = accentColor,
                paused = paused,
            )
            TifNoSignalStyle.ARIB_COLOR_BARS -> AribColorBarsEffect(
                statusText = statusText,
                inputLabel = inputLabel,
                showOverlay = showOverlay,
                paused = paused,
            )
            TifNoSignalStyle.ARIB_COLOR_BARS_SCROLL -> AribScrollingColorBarsEffect(
                statusText = statusText,
                inputLabel = inputLabel,
                showOverlay = showOverlay,
                paused = paused,
                onDegraded = onDegraded,
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
            TifNoSignalStyle.CRAYON_TITLE -> CrayonTitleEffect(
                statusText = statusText,
                inputLabel = inputLabel,
                paused = paused,
                onDegraded = onDegraded,
            )
            TifNoSignalStyle.PRECURE_SPARKLE -> PrecureSparkleEffect(
                statusText = statusText,
                inputLabel = inputLabel,
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
// 1. ARIB STD-B28 Japanese Digital Broadcast Color Bars (Static & Scrolling)
// ---------------------------------------------------------------------------
private fun DrawScope.drawAribColorBarsTile(
    w: Float,
    h: Float,
    rampBrush: Brush,
    includeIqBlocks: Boolean = false,
) {
    val sideW = w * 0.125f
    val centerW = w - sideW * 2f
    val barW = centerW / 7f

    val topH = h * (7f / 12f)
    val midH = h * (1f / 12f)
    val rampH = h * (1f / 12f)
    val rampY = topH + midH
    val botY = rampY + rampH
    val botH = h - botY

    // Pattern 1 (7/12): 40% gray sidebands + 7x 75% color bars
    drawRect(ARIB_GRAY_40, Offset.Zero, Size(sideW + 0.5f, topH + 0.5f))
    for (i in ARIB_MAIN_BARS.indices) {
        val left = sideW + i * barW
        val right = if (i == ARIB_MAIN_BARS.lastIndex) w - sideW else sideW + (i + 1) * barW
        drawRect(ARIB_MAIN_BARS[i], Offset(left, 0f), Size(right - left + 0.5f, topH + 0.5f))
    }
    drawRect(ARIB_GRAY_40, Offset(w - sideW, 0f), Size(sideW + 0.5f, topH + 0.5f))

    // Pattern 2 (1/12): 100% Cyan | (-I or 100% White) | 75% White | 100% Blue
    drawRect(Color(0xFF00FFFF), Offset(0f, topH), Size(sideW + 0.5f, midH + 0.5f))
    if (includeIqBlocks) {
        drawRect(Color(0xFF003B5C), Offset(sideW, topH), Size(barW + 0.5f, midH + 0.5f))
    } else {
        drawRect(ARIB_WHITE_100, Offset(sideW, topH), Size(barW + 0.5f, midH + 0.5f))
    }
    drawRect(ARIB_WHITE_75, Offset(sideW + barW, topH), Size(barW * 6f + 0.5f, midH + 0.5f))
    drawRect(Color(0xFF0000FF), Offset(w - sideW, topH), Size(sideW + 0.5f, midH + 0.5f))

    // Pattern 3 (1/12): 100% Yellow | (+Q optional) + 0%->100% Luminance Ramp | 100% Red
    drawRect(Color(0xFFFFFF00), Offset(0f, rampY), Size(sideW + 0.5f, rampH + 0.5f))
    if (includeIqBlocks) {
        drawRect(Color(0xFF3B0066), Offset(sideW, rampY), Size(barW + 0.5f, rampH + 0.5f))
        drawRect(
            brush = rampBrush,
            topLeft = Offset(sideW + barW, rampY),
            size = Size(barW * 6f + 0.5f, rampH + 0.5f),
        )
    } else {
        drawRect(
            brush = rampBrush,
            topLeft = Offset(sideW, rampY),
            size = Size(centerW + 0.5f, rampH + 0.5f),
        )
    }
    drawRect(Color(0xFFFF0000), Offset(w - sideW, rampY), Size(sideW + 0.5f, rampH + 0.5f))

    // Pattern 4 (3/12): 15% Gray | Black | 100% White block | Black + subtle PLUGE | 15% Gray
    drawRect(ARIB_GRAY_15, Offset(0f, botY), Size(sideW + 0.5f, botH + 0.5f))
    val black1W = barW * 1.5f
    val whiteBlockW = barW * 2.0f
    val black2X = sideW + black1W + whiteBlockW
    val black2W = (w - sideW) - black2X
    drawRect(Color.Black, Offset(sideW, botY), Size(black1W + 0.5f, botH + 0.5f))
    drawRect(ARIB_WHITE_100, Offset(sideW + black1W, botY), Size(whiteBlockW + 0.5f, botH + 0.5f))
    drawRect(Color.Black, Offset(black2X, botY), Size(black2W + 0.5f, botH + 0.5f))
    val plugeW = barW * 0.28f
    drawRect(Color(0xFF0A0A0A), Offset(black2X + black2W * 0.42f, botY), Size(plugeW, botH + 0.5f))
    drawRect(Color(0xFF141414), Offset(black2X + black2W * 0.56f, botY), Size(plugeW, botH + 0.5f))
    drawRect(ARIB_GRAY_15, Offset(w - sideW, botY), Size(sideW + 0.5f, botH + 0.5f))
}

@Composable
private fun AribBroadcastTelopText(
    text: String,
    fontSizeSp: Float = 26f,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier) {
        Text(
            text = text,
            style = TextStyle(
                color = Color.Black,
                fontSize = fontSizeSp.sp,
                lineHeight = (fontSizeSp + 4f).sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp,
                drawStyle = ARIB_TELOP_OUTLINE_STROKE,
            ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 520.dp),
        )
        Text(
            text = text,
            style = TextStyle(
                color = Color.White,
                fontSize = fontSizeSp.sp,
                lineHeight = (fontSizeSp + 4f).sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp,
            ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 520.dp),
        )
    }
}

@Composable
private fun AribColorBarsEffect(
    statusText: String,
    inputLabel: String,
    showOverlay: Boolean,
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
    val shiftX = sin(tick * 0.05f) * 6f
    val shiftY = cos(tick * 0.04f) * 4f

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        Spacer(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = 1.02f
                    scaleY = 1.02f
                    translationX = shiftX
                    translationY = shiftY
                }
                .drawWithCache {
                    val w = size.width
                    val h = size.height
                    val sideW = w * 0.125f
                    val centerW = w - sideW * 2f
                    val rampBrush = Brush.horizontalGradient(
                        colorStops = ARIB_RAMP_STOPS,
                        startX = sideW,
                        endX = sideW + centerW,
                    )
                    onDrawBehind {
                        drawAribColorBarsTile(w = w, h = h, rampBrush = rampBrush, includeIqBlocks = false)
                    }
                },
        )

        if (!showOverlay) {
            AribBroadcastTelopText(
                text = statusText.ifBlank { "信号がありません" },
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(start = 152.dp, top = 56.dp)
                    .graphicsLayer {
                        translationX = shiftX * 0.6f
                        translationY = shiftY * 0.6f
                    },
            )
        }

        Text(
            text = "HonoRebi",
            color = Color.White.copy(alpha = 0.34f),
            fontSize = 16.sp,
            fontWeight = FontWeight.Black,
            letterSpacing = 1.5.sp,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(end = 28.dp, top = 26.dp),
        )

        Column(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 150.dp, bottom = 36.dp)
                .graphicsLayer {
                    translationX = shiftX * 0.5f
                    translationY = shiftY * 0.5f
                },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = inputLabel.uppercase(Locale.US),
                color = Color.White,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                letterSpacing = 2.5.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 280.dp),
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = "HONOREBI DIGITAL TV",
                color = Color.White.copy(alpha = 0.9f),
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 1.5.sp,
                maxLines = 1,
            )
        }
    }
}

// ---------------------------------------------------------------------------
// 1b. Dynamic Diagonal-Scrolling ARIB Color Bars (Late-night DTV Test Broadcast)
// ---------------------------------------------------------------------------
@Composable
private fun AribScrollingColorBarsEffect(
    statusText: String,
    inputLabel: String,
    showOverlay: Boolean,
    paused: Boolean,
    onDegraded: () -> Unit,
) {
    val elapsedState = rememberElapsedSeconds(paused = paused, onDegraded = onDegraded)
    var nowMillis by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(paused) {
        if (paused) return@LaunchedEffect
        while (isActive) {
            nowMillis = System.currentTimeMillis()
            delay(1000L)
        }
    }

    val dateFmt = remember { SimpleDateFormat("yyyy年M月d日(E)", Locale.JAPANESE) }
    val timeFmt = remember { SimpleDateFormat("H:mm", Locale.JAPANESE) }
    val dateObj = remember(nowMillis / 1000L) { Date(nowMillis) }
    val clockShadow = remember { Shadow(color = Color.Black.copy(alpha = 0.24f), offset = Offset(1f, 1f), blurRadius = 2f) }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        Spacer(
            modifier = Modifier
                .fillMaxSize()
                .drawWithCache {
                    val w = size.width
                    val h = size.height
                    val sideW = w * 0.125f
                    val centerW = w - sideW * 2f
                    val barW = centerW / 7f
                    val rampBrush = Brush.horizontalGradient(
                        colorStops = ARIB_RAMP_STOPS,
                        startX = sideW + barW,
                        endX = sideW + centerW,
                    )
                    onDrawBehind {
                        val elapsed = elapsedState.value
                        // Smooth diagonal down-right scroll wrapping seamlessly in X and Y
                        val ox = ((elapsed * 0.14f) % 1f) * w
                        val oy = ((elapsed * 0.12f) % 1f) * h

                        translate(left = ox, top = oy) {
                            drawAribColorBarsTile(w, h, rampBrush, includeIqBlocks = true)
                        }
                        translate(left = ox - w, top = oy) {
                            drawAribColorBarsTile(w, h, rampBrush, includeIqBlocks = true)
                        }
                        translate(left = ox, top = oy - h) {
                            drawAribColorBarsTile(w, h, rampBrush, includeIqBlocks = true)
                        }
                        translate(left = ox - w, top = oy - h) {
                            drawAribColorBarsTile(w, h, rampBrush, includeIqBlocks = true)
                        }
                    }
                },
        )

        // Top-left Japanese broadcast outlined telop: center-aligned pair like "試験電波発射中 / JOOY-DTV"
        if (!showOverlay) {
            Column(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(start = 28.dp, top = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                AribBroadcastTelopText(
                    text = statusText.ifBlank { "信号がありません" },
                    fontSizeSp = 24f,
                )
                Spacer(modifier = Modifier.height(2.dp))
                AribBroadcastTelopText(
                    text = inputLabel.uppercase(Locale.US),
                    fontSizeSp = 22f,
                )
            }
        }

        // Lower-right broadcast date + time + HonoRebi logo lockup (aligned to MBS broadcast geometry)
        Column(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 68.dp, bottom = 84.dp),
            horizontalAlignment = Alignment.End,
        ) {
            Text(
                text = dateFmt.format(dateObj),
                style = TextStyle(
                    color = Color.White,
                    fontSize = 31.sp,
                    fontWeight = FontWeight.Light,
                    letterSpacing = 1.sp,
                    fontFeatureSettings = "tnum",
                    shadow = clockShadow,
                ),
                maxLines = 1,
            )
            Spacer(modifier = Modifier.height(10.dp))
            Row(
                modifier = Modifier.widthIn(min = 336.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = timeFmt.format(dateObj),
                    style = TextStyle(
                        color = Color.White,
                        fontSize = 34.sp,
                        fontWeight = FontWeight.Light,
                        letterSpacing = 1.sp,
                        fontFeatureSettings = "tnum",
                        shadow = clockShadow,
                    ),
                    maxLines = 1,
                    modifier = Modifier.padding(start = 16.dp),
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Image(
                        painter = painterResource(id = R.drawable.honorebi_mark),
                        contentDescription = null,
                        modifier = Modifier.size(40.dp),
                    )
                    Text(
                        text = "HonoRebi",
                        style = TextStyle(
                            color = Color.White,
                            fontSize = 28.sp,
                            fontWeight = FontWeight.Black,
                            letterSpacing = 1.sp,
                            shadow = clockShadow,
                        ),
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 2. Vintage CRT Analog Static / Snow
// ---------------------------------------------------------------------------
private const val STATIC_FRAME_COUNT = 6
private const val STATIC_TEX_W = 512
private const val STATIC_TEX_H = 288

private fun buildStaticFrames(): List<ImageBitmap> {
    val rng = Random(20261123)
    val pixels = IntArray(STATIC_TEX_W * STATIC_TEX_H)
    return List(STATIC_FRAME_COUNT) {
        for (y in 0 until STATIC_TEX_H) {
            // Real NTSC/PAL RF snow has subtle per-scanline luminance jitter and horizontal grain
            val lineBias = rng.nextInt(-26, 27)
            val rowOffset = y * STATIC_TEX_W
            var prev = 110
            for (x in 0 until STATIC_TEX_W) {
                val roll = rng.nextInt(100)
                val raw = when {
                    roll < 8 -> rng.nextInt(215, 256) // Crisp white RF snow burst
                    roll < 16 -> rng.nextInt(0, 24)   // Deep black signal dropout
                    else -> rng.nextInt(20, 212)      // Full-contrast static grain
                }
                // Slight horizontal analog bandwidth correlation (stretches snow into fine RF grain)
                val luma = ((prev * 28 + raw * 72) / 100 + lineBias).coerceIn(4, 250)
                prev = luma

                // Rare faint NTSC chroma snow sparkles
                val r: Int
                val g: Int
                val b: Int
                when (rng.nextInt(48)) {
                    0 -> {
                        r = (luma + 28).coerceAtMost(255)
                        g = (luma - 10).coerceAtLeast(0)
                        b = (luma - 8).coerceAtLeast(0)
                    }
                    1 -> {
                        r = (luma - 10).coerceAtLeast(0)
                        g = (luma + 14).coerceAtMost(255)
                        b = (luma + 26).coerceAtMost(255)
                    }
                    else -> {
                        r = luma
                        g = luma
                        b = (luma + 4).coerceAtMost(255)
                    }
                }
                pixels[rowOffset + x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
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
                    val humBandH = (size.height * 0.16f).coerceAtLeast(90f)
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
                    val scanStep = 2.dp.toPx().coerceAtLeast(3f)

                    onDrawBehind {
                        val elapsed = elapsedState.value
                        val tick = (elapsed * 24f).toInt().coerceAtLeast(0)
                        val frameIdx = tick % frames.size
                        // Flip X/Y across ticks so 6 frames produce 24 non-repeating variations
                        val flipX = if (((tick / frames.size) and 1) == 1) -1f else 1f
                        val flipY = if (((tick / (frames.size * 2)) and 1) == 1) -1f else 1f

                        scale(scaleX = flipX, scaleY = flipY, pivot = center) {
                            drawImage(
                                image = frames[frameIdx],
                                dstOffset = IntOffset.Zero,
                                dstSize = dstSize,
                                filterQuality = FilterQuality.Low,
                            )
                        }

                        // Subtle dark-room luminance clamp so full-contrast snow stays comfortable on TV
                        drawRect(Color.Black.copy(alpha = 0.24f))

                        // Dynamic horizontal RF interference streaks (1-3 thin bursts per frame)
                        var hash = tick * 1103515245 + 12345
                        for (s in 0 until 3) {
                            hash = hash * 1103515245 + 12345
                            val sy = ((hash ushr 16) and 0x7FFF) / 32767f * size.height
                            hash = hash * 1103515245 + 12345
                            val sx = ((hash ushr 16) and 0x7FFF) / 32767f * size.width * 0.7f
                            hash = hash * 1103515245 + 12345
                            val sw = (0.08f + ((hash ushr 16) and 0x7FFF) / 32767f * 0.32f) * size.width
                            val bright = (s and 1) == 0
                            drawLine(
                                color = if (bright) Color.White.copy(alpha = 0.42f) else Color.Black.copy(alpha = 0.55f),
                                start = Offset(sx, sy),
                                end = Offset((sx + sw).coerceAtMost(size.width), sy),
                                strokeWidth = 2f,
                            )
                        }

                        // Slow rolling 60Hz analog hum band
                        val humY = ((elapsed * 0.18f) % 1.35f - 0.18f) * size.height - humBandH * 0.5f
                        translate(top = humY) {
                            drawRect(
                                brush = humBrush,
                                size = Size(size.width, humBandH),
                            )
                        }

                        // Crisp CRT horizontal scanlines
                        var y = 0f
                        while (y < size.height) {
                            drawLine(
                                color = STATIC_SCANLINE_COLOR,
                                start = Offset(0f, y),
                                end = Offset(size.width, y),
                                strokeWidth = 1.5f,
                            )
                            y += scanStep
                        }

                        // CRT corner vignette
                        drawRect(brush = vignetteBrush)
                    }
                },
        )

        if (!showOverlay) {
            // Classic CRT TV phosphor-green OSD (bare crisp monospace with hard black shadow, no card box)
            Column(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(start = 56.dp, top = 46.dp)
                    .graphicsLayer {
                        val elapsed = elapsedState.value
                        translationX = sin(elapsed * 0.12f) * 12f
                        translationY = cos(elapsed * 0.09f) * 8f
                    },
            ) {
                Text(
                    text = inputLabel.uppercase(Locale.US),
                    style = TextStyle(
                        color = Color(0xFF38FF62),
                        fontSize = 30.sp,
                        fontWeight = FontWeight.ExtraBold,
                        fontFamily = FontFamily.Monospace,
                        letterSpacing = 3.sp,
                        shadow = STATIC_OSD_SHADOW,
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 380.dp),
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "NO SIGNAL  $statusText",
                    style = TextStyle(
                        color = Color(0xFF38FF62).copy(alpha = 0.92f),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        letterSpacing = 1.5.sp,
                        shadow = STATIC_OSD_SHADOW,
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 460.dp),
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
// 8. Crayon Pop Anime Episode Title Card (90s Crayon Illustration Aesthetic)
// ---------------------------------------------------------------------------
private enum class CrayonShapeKind { CIRCLE, TRIANGLE, STAR, SQUIGGLE, RING, DIAMOND }

private class CrayonMotif(
    val nx: Float,
    val ny: Float,
    val sizeDp: Float,
    val color: Color,
    val kind: CrayonShapeKind,
    val baseRot: Float,
    val phase: Float,
    val speed: Float,
)

private val CRAYON_INK = Color(0xFF2B2118)
private val CRAYON_BG_TOP = Color(0xFFF6E7B4)
private val CRAYON_BG_BOTTOM = Color(0xFFECD59B)
private val CRAYON_BG_COLORS = listOf(CRAYON_BG_TOP, CRAYON_BG_BOTTOM)
private val CRAYON_OUTLINE_STROKE = Stroke(width = 3.2f, cap = StrokeCap.Round, join = StrokeJoin.Round)
private val CRAYON_RING_STROKE = Stroke(width = 6.5f, cap = StrokeCap.Round, join = StrokeJoin.Round)
private val CRAYON_SQUIGGLE_STROKE = Stroke(width = 5.5f, cap = StrokeCap.Round, join = StrokeJoin.Round)
private val CRAYON_WHITE_STICKER_STROKE = Stroke(width = 16f, join = StrokeJoin.Round)
private val CRAYON_DARK_STICKER_STROKE = Stroke(width = 7f, join = StrokeJoin.Round)
private val CRAYON_CHAR_SHADOW = Shadow(color = Color(0xCC2B2118), offset = Offset(4f, 5f), blurRadius = 0f)

private val CRAYON_TITLE_CHARS = arrayOf("信", "号", "が", "な", "い", "ゾ", "！")
private val CRAYON_TITLE_COLORS = arrayOf(
    Color(0xFFE63946), // Crayon Red
    Color(0xFFF77F00), // Crayon Orange
    Color(0xFF2A9D8F), // Crayon Mint-Teal
    Color(0xFF1D70B8), // Crayon Sky-Blue
    Color(0xFF9B5DE5), // Crayon Grape
    Color(0xFFE63946), // Crayon Red
    Color(0xFFF4A261), // Crayon Marigold
)
private val CRAYON_TITLE_TILTS = floatArrayOf(-6f, 4.5f, -3.5f, 5.5f, -4.5f, 4f, -3f)

private fun buildCrayonMotifs(): Array<CrayonMotif> = arrayOf(
    // Left & top-left playful crayon motifs
    CrayonMotif(0.08f, 0.14f, 44f, Color(0xFFE63946), CrayonShapeKind.STAR, -14f, 0.2f, 1.1f),
    CrayonMotif(0.18f, 0.30f, 34f, Color(0xFF3A86FF), CrayonShapeKind.TRIANGLE, 18f, 1.4f, 0.9f),
    CrayonMotif(0.07f, 0.52f, 42f, Color(0xFF2A9D8F), CrayonShapeKind.SQUIGGLE, -12f, 2.1f, 1.3f),
    CrayonMotif(0.15f, 0.72f, 38f, Color(0xFFF77F00), CrayonShapeKind.RING, 8f, 0.7f, 1.0f),
    CrayonMotif(0.27f, 0.12f, 28f, Color(0xFF9B5DE5), CrayonShapeKind.CIRCLE, 0f, 3.0f, 0.8f),
    CrayonMotif(0.42f, 0.10f, 34f, Color(0xFF2A9D8F), CrayonShapeKind.DIAMOND, 15f, 1.8f, 1.2f),
    CrayonMotif(0.58f, 0.11f, 38f, Color(0xFFE63946), CrayonShapeKind.SQUIGGLE, 10f, 2.6f, 1.0f),
    // Right & top-right playful crayon motifs
    CrayonMotif(0.75f, 0.13f, 32f, Color(0xFFF77F00), CrayonShapeKind.TRIANGLE, -22f, 0.5f, 1.1f),
    CrayonMotif(0.90f, 0.16f, 46f, Color(0xFF3A86FF), CrayonShapeKind.STAR, 12f, 2.2f, 0.95f),
    CrayonMotif(0.84f, 0.36f, 36f, Color(0xFF9B5DE5), CrayonShapeKind.RING, -8f, 1.1f, 1.15f),
    CrayonMotif(0.93f, 0.56f, 40f, Color(0xFFE63946), CrayonShapeKind.DIAMOND, 20f, 3.4f, 1.0f),
    CrayonMotif(0.86f, 0.74f, 36f, Color(0xFF2A9D8F), CrayonShapeKind.CIRCLE, 0f, 0.9f, 1.25f),
    CrayonMotif(0.28f, 0.84f, 32f, Color(0xFF3A86FF), CrayonShapeKind.STAR, -18f, 2.8f, 0.85f),
    CrayonMotif(0.72f, 0.82f, 38f, Color(0xFFF77F00), CrayonShapeKind.SQUIGGLE, -6f, 1.6f, 1.1f),
)

private fun buildUnitTrianglePath(radius: Float): Path = Path().apply {
    moveTo(0f, -radius)
    lineTo(radius * 0.92f, radius * 0.78f)
    lineTo(-radius * 0.92f, radius * 0.78f)
    close()
}

private fun buildUnitStarPath(outerR: Float): Path = Path().apply {
    val innerR = outerR * 0.46f
    for (i in 0 until 10) {
        val angle = (-90.0 + i * 36.0) * kotlin.math.PI / 180.0
        val r = if ((i and 1) == 0) outerR else innerR
        val x = (cos(angle) * r).toFloat()
        val y = (sin(angle) * r).toFloat()
        if (i == 0) moveTo(x, y) else lineTo(x, y)
    }
    close()
}

private fun buildUnitDiamondPath(radius: Float): Path = Path().apply {
    moveTo(0f, -radius)
    lineTo(radius * 0.78f, 0f)
    lineTo(0f, radius)
    lineTo(-radius * 0.78f, 0f)
    close()
}

private fun buildUnitSquigglePath(halfW: Float): Path = Path().apply {
    val amp = halfW * 0.34f
    moveTo(-halfW, 0f)
    cubicTo(-halfW * 0.65f, -amp, -halfW * 0.35f, -amp, 0f, 0f)
    cubicTo(halfW * 0.35f, amp, halfW * 0.65f, amp, halfW, 0f)
}

@Composable
private fun CrayonTitleEffect(
    statusText: String,
    inputLabel: String,
    paused: Boolean,
    onDegraded: () -> Unit,
) {
    val motifs = remember { buildCrayonMotifs() }
    val elapsedState = rememberElapsedSeconds(paused = paused, onDegraded = onDegraded)

    Box(modifier = Modifier.fillMaxSize()) {
        Spacer(
            modifier = Modifier
                .fillMaxSize()
                .drawWithCache {
                    val bgBrush = Brush.verticalGradient(colors = CRAYON_BG_COLORS)
                    val dotColor = Color(0xFFD6B97A).copy(alpha = 0.34f)
                    val dotStep = 34.dp.toPx()
                    val dotRadius = 2.2.dp.toPx()
                    val misreg = 3.5.dp.toPx()

                    // Pre-build unit paths scaled per motif size (zero per-frame Path allocations)
                    val motifPaths = Array(motifs.size) { i ->
                        val r = motifs[i].sizeDp.dp.toPx() * 0.5f
                        when (motifs[i].kind) {
                            CrayonShapeKind.TRIANGLE -> buildUnitTrianglePath(r)
                            CrayonShapeKind.STAR -> buildUnitStarPath(r)
                            CrayonShapeKind.DIAMOND -> buildUnitDiamondPath(r)
                            CrayonShapeKind.SQUIGGLE -> buildUnitSquigglePath(r)
                            else -> null
                        }
                    }
                    val motifRadii = FloatArray(motifs.size) { motifs[it].sizeDp.dp.toPx() * 0.5f }

                    onDrawBehind {
                        val elapsed = elapsedState.value
                        // Warm pastel crayon paper background + subtle polka-dot paper pattern
                        drawRect(brush = bgBrush)
                        var py = dotStep * 0.5f
                        var rowIdx = 0
                        while (py < size.height) {
                            var px = if ((rowIdx and 1) == 0) dotStep * 0.5f else dotStep
                            while (px < size.width) {
                                drawCircle(dotColor, radius = dotRadius, center = Offset(px, py))
                                px += dotStep
                            }
                            py += dotStep
                            rowIdx++
                        }

                        // Playful floating hand-drawn crayon shapes with slight misregistered ink outline
                        for (i in motifs.indices) {
                            val m = motifs[i]
                            val r = motifRadii[i]
                            val cx = size.width * m.nx + sin(elapsed * m.speed * 0.7f + m.phase) * 7f
                            val cy = size.height * m.ny + cos(elapsed * m.speed + m.phase) * 9f
                            val rot = m.baseRot + sin(elapsed * m.speed * 0.8f + m.phase) * 8f

                            translate(left = cx, top = cy) {
                                rotate(degrees = rot, pivot = Offset.Zero) {
                                    when (m.kind) {
                                        CrayonShapeKind.CIRCLE -> {
                                            drawCircle(m.color, radius = r, center = Offset(misreg, misreg))
                                            drawCircle(CRAYON_INK, radius = r, center = Offset.Zero, style = CRAYON_OUTLINE_STROKE)
                                        }
                                        CrayonShapeKind.RING -> {
                                            drawCircle(m.color, radius = r, center = Offset(misreg * 0.6f, misreg * 0.6f), style = CRAYON_RING_STROKE)
                                            drawCircle(CRAYON_INK, radius = r, center = Offset.Zero, style = CRAYON_OUTLINE_STROKE)
                                        }
                                        CrayonShapeKind.SQUIGGLE -> {
                                            val p = motifPaths[i] ?: return@rotate
                                            translate(left = misreg, top = misreg) {
                                                drawPath(p, color = m.color, style = CRAYON_SQUIGGLE_STROKE)
                                            }
                                            drawPath(p, color = CRAYON_INK, style = CRAYON_OUTLINE_STROKE)
                                        }
                                        else -> {
                                            val p = motifPaths[i] ?: return@rotate
                                            translate(left = misreg, top = misreg) {
                                                drawPath(p, color = m.color)
                                            }
                                            drawPath(p, color = CRAYON_INK, style = CRAYON_OUTLINE_STROKE)
                                        }
                                    }
                                }
                            }
                        }
                    }
                },
        )

        // Centered bouncy Shin-chan style episode title card lockup (clear of ~110dp bottom preview HUD)
        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .padding(bottom = 54.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Top eyebrow crayon tag
            val tagShape = RoundedCornerShape(999.dp)
            Box(
                modifier = Modifier
                    .graphicsLayer { rotationZ = -2.2f }
                    .background(Color(0xFFFFFBF0), tagShape)
                    .border(2.5.dp, CRAYON_INK, tagShape)
                    .padding(horizontal = 20.dp, vertical = 6.dp),
            ) {
                Text(
                    text = "★  $inputLabel  ★",
                    color = CRAYON_INK,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = 2.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 360.dp),
                )
            }

            Spacer(modifier = Modifier.height(18.dp))

            // Iconic bouncy tilted multi-color sticker title: 「信号がないゾ！」
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                for (i in CRAYON_TITLE_CHARS.indices) {
                    val ch = CRAYON_TITLE_CHARS[i]
                    val fillColor = CRAYON_TITLE_COLORS[i]
                    val tilt = CRAYON_TITLE_TILTS[i]
                    Box(
                        modifier = Modifier.graphicsLayer {
                            val t = elapsedState.value
                            rotationZ = tilt + sin(t * 2.0f + i * 0.7f) * 2.2f
                            translationY = sin(t * 2.4f + i * 0.65f) * 5f
                        },
                    ) {
                        // Layer 1: thick white sticker outline + drop shadow
                        Text(
                            text = ch,
                            style = TextStyle(
                                color = Color.White,
                                fontSize = 62.sp,
                                fontWeight = FontWeight.Black,
                                drawStyle = CRAYON_WHITE_STICKER_STROKE,
                                shadow = CRAYON_CHAR_SHADOW,
                            ),
                        )
                        // Layer 2: hand-drawn dark crayon ink outline
                        Text(
                            text = ch,
                            style = TextStyle(
                                color = CRAYON_INK,
                                fontSize = 62.sp,
                                fontWeight = FontWeight.Black,
                                drawStyle = CRAYON_DARK_STICKER_STROKE,
                            ),
                        )
                        // Layer 3: vivid crayon fill
                        Text(
                            text = ch,
                            style = TextStyle(
                                color = fillColor,
                                fontSize = 62.sp,
                                fontWeight = FontWeight.Black,
                            ),
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(18.dp))

            // Hand-drawn subtitle pill
            val pillShape = RoundedCornerShape(14.dp)
            Box(
                modifier = Modifier
                    .graphicsLayer { rotationZ = 1.2f }
                    .background(Color(0xFFFFFBF0).copy(alpha = 0.94f), pillShape)
                    .border(2.dp, CRAYON_INK, pillShape)
                    .padding(horizontal = 22.dp, vertical = 8.dp),
            ) {
                Text(
                    text = statusText.ifBlank { "信号がありません" },
                    color = CRAYON_INK,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 420.dp),
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 9. N〇K Broadcast Station-ID Callsign Card (Up/Down customizable color + Random)
// ---------------------------------------------------------------------------
private val NOK_SILVER_EDGE = Color(0xFFDADADA)

@Composable
private fun NokCallsignEffect(
    statusText: String,
    inputLabel: String,
    accentColor: TifNoSignalAccentColor,
    paused: Boolean,
) {
    val randomFallback = remember {
        val concrete = TifNoSignalAccentColor.concreteEntries
        concrete[Random.nextInt(concrete.size)]
    }
    val resolvedPalette = if (accentColor == TifNoSignalAccentColor.RANDOM) randomFallback else accentColor
    val deepColor by animateColorAsState(
        targetValue = Color(resolvedPalette.deepArgb),
        animationSpec = tween(durationMillis = 240),
        label = "nokDeep",
    )
    val mainColor by animateColorAsState(
        targetValue = Color(resolvedPalette.mainArgb),
        animationSpec = tween(durationMillis = 240),
        label = "nokMain",
    )
    val ribbonColor by animateColorAsState(
        targetValue = Color(resolvedPalette.ribbonArgb),
        animationSpec = tween(durationMillis = 240),
        label = "nokRibbon",
    )

    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(paused) {
        if (paused) return@LaunchedEffect
        while (isActive) {
            delay(1000L)
            tick++
        }
    }
    val shiftX = sin(tick * 0.05f) * 6f
    val shiftY = cos(tick * 0.04f) * 4f

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(deepColor),
        contentAlignment = Alignment.Center,
    ) {
        Spacer(
            modifier = Modifier
                .fillMaxSize()
                .drawWithCache {
                    val w = size.width
                    val h = size.height
                    // Geometry matched to the TV line preview; all ribbon edges share one curve.
                    val slant = w * 0.162f
                    val elbowStartY = h * 0.885f
                    val bottomY = h * 1.01f

                    fun slantedCurvedFillRight(topX: Float): Path {
                        val elbowX = topX - slant * (elbowStartY / h)
                        val ctrlX = elbowX - w * 0.006f
                        val endX = elbowX + w * 0.004f
                        return Path().apply {
                            moveTo(topX, 0f)
                            lineTo(elbowX, elbowStartY)
                            cubicTo(
                                elbowX - slant * 0.063f,
                                h * 0.948f,
                                ctrlX, h * 0.982f,
                                endX, bottomY,
                            )
                            lineTo(w * 1.08f, bottomY)
                            lineTo(w * 1.08f, 0f)
                            close()
                        }
                    }

                    // Layered right-filling shapes:
                    // 1. Deep base (full screen)
                    // 2. Silver-gray highlight edge
                    val silverLayer = slantedCurvedFillRight(w * 0.1968f)
                    // 3. Left pastel ribbon
                    val leftRibbonLayer = slantedCurvedFillRight(w * 0.2183f)
                    // 4. Center rich mid-tone main panel
                    val mainPanelLayer = slantedCurvedFillRight(w * 0.2768f)
                    // 5. Right pastel ribbon
                    val rightRibbonLayer = slantedCurvedFillRight(w * 0.9443f)

                    onDrawBehind {
                        drawRect(deepColor)
                        drawPath(silverLayer, color = NOK_SILVER_EDGE)
                        drawPath(leftRibbonLayer, color = ribbonColor)
                        drawPath(mainPanelLayer, color = mainColor)
                        drawPath(rightRibbonLayer, color = ribbonColor)
                    }
                },
        )

        // Upper-center 3-line broadcast station-ID typography (1:1 NHK vertical placement, clear of preview HUD)
        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .padding(bottom = 56.dp)
                .graphicsLayer {
                    translationX = shiftX
                    translationY = shiftY
                },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = inputLabel.uppercase(Locale.US),
                color = Color.White,
                fontSize = 50.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 3.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 520.dp),
            )
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = "HonoRebi",
                color = Color.White,
                modifier = Modifier.graphicsLayer { scaleX = 0.80f },
                fontSize = 82.5.sp,
                lineHeight = 70.sp,
                fontWeight = FontWeight.Black,
                fontStyle = FontStyle.Italic,
                letterSpacing = 2.sp,
                maxLines = 1,
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = statusText.ifBlank { "ホノレビデジタルテレビジョン" },
                color = Color.White.copy(alpha = 0.96f),
                fontSize = 36.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 2.5.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 560.dp),
            )
        }
    }
}

// ---------------------------------------------------------------------------
// 10. PreCure Magical-Girl Sparkle Title Card
// ---------------------------------------------------------------------------
private enum class PrecureParticleKind { HEART, KIRAKIRA, BUBBLE }

private class PrecureParticle(
    val nx: Float,
    val ny: Float,
    val sizeDp: Float,
    val color: Color,
    val kind: PrecureParticleKind,
    val phase: Float,
    val speed: Float,
)

private val PRECURE_BG_COLORS = listOf(
    Color(0xFFFFE4F2),
    Color(0xFFF6D9FF),
    Color(0xFFD8EEFF),
)
private val PRECURE_PINK = Color(0xFFFF3E9D)
private val PRECURE_PLUM = Color(0xFF7A1F52)
private val PRECURE_GOLD = Color(0xFFFFC83D)
private val PRECURE_STICKER_OUTLINE = Stroke(width = 14f, join = StrokeJoin.Round)
private val PRECURE_TITLE_SHADOW = Shadow(color = Color(0x66FF3E9D), offset = Offset(0f, 5f), blurRadius = 8f)

private fun buildPrecureParticles(): Array<PrecureParticle> = arrayOf(
    PrecureParticle(0.08f, 0.20f, 34f, Color(0xFFFF5CAE), PrecureParticleKind.HEART, 0.3f, 0.045f),
    PrecureParticle(0.16f, 0.56f, 28f, Color(0xFFFFD166), PrecureParticleKind.KIRAKIRA, 1.1f, 0.038f),
    PrecureParticle(0.11f, 0.80f, 44f, Color(0xFFFFFFFF), PrecureParticleKind.BUBBLE, 2.0f, 0.032f),
    PrecureParticle(0.24f, 0.14f, 24f, Color(0xFF60A5FA), PrecureParticleKind.KIRAKIRA, 2.8f, 0.042f),
    PrecureParticle(0.32f, 0.78f, 30f, Color(0xFFC084FC), PrecureParticleKind.HEART, 0.8f, 0.036f),
    PrecureParticle(0.45f, 0.10f, 32f, Color(0xFFFFFFFF), PrecureParticleKind.KIRAKIRA, 1.7f, 0.040f),
    PrecureParticle(0.58f, 0.12f, 26f, Color(0xFFFF5CAE), PrecureParticleKind.HEART, 3.2f, 0.035f),
    PrecureParticle(0.72f, 0.16f, 36f, Color(0xFFFFFFFF), PrecureParticleKind.BUBBLE, 0.5f, 0.030f),
    PrecureParticle(0.84f, 0.22f, 32f, Color(0xFFFFD166), PrecureParticleKind.KIRAKIRA, 2.4f, 0.046f),
    PrecureParticle(0.91f, 0.48f, 36f, Color(0xFFFF5CAE), PrecureParticleKind.HEART, 1.4f, 0.040f),
    PrecureParticle(0.82f, 0.72f, 26f, Color(0xFF38BDF8), PrecureParticleKind.KIRAKIRA, 3.6f, 0.044f),
    PrecureParticle(0.68f, 0.84f, 42f, Color(0xFFFFFFFF), PrecureParticleKind.BUBBLE, 2.9f, 0.028f),
    PrecureParticle(0.20f, 0.36f, 22f, Color(0xFFFFFFFF), PrecureParticleKind.KIRAKIRA, 4.1f, 0.050f),
    PrecureParticle(0.78f, 0.38f, 28f, Color(0xFFC084FC), PrecureParticleKind.HEART, 0.9f, 0.039f),
)

private fun buildUnitHeartPath(r: Float): Path = Path().apply {
    moveTo(0f, r * 0.85f)
    cubicTo(-r * 1.15f, r * 0.25f, -r * 1.1f, -r * 0.75f, 0f, -r * 0.22f)
    cubicTo(r * 1.1f, -r * 0.75f, r * 1.15f, r * 0.25f, 0f, r * 0.85f)
    close()
}

private fun buildUnitKirakiraPath(r: Float): Path = Path().apply {
    val c = r * 0.24f
    moveTo(0f, -r)
    quadraticTo(c, -c, r, 0f)
    quadraticTo(c, c, 0f, r)
    quadraticTo(-c, c, -r, 0f)
    quadraticTo(-c, -c, 0f, -r)
    close()
}

@Composable
private fun PrecureSparkleEffect(
    statusText: String,
    inputLabel: String,
    paused: Boolean,
    onDegraded: () -> Unit,
) {
    val particles = remember { buildPrecureParticles() }
    val elapsedState = rememberElapsedSeconds(paused = paused, onDegraded = onDegraded)

    Box(modifier = Modifier.fillMaxSize()) {
        Spacer(
            modifier = Modifier
                .fillMaxSize()
                .drawWithCache {
                    val bgBrush = Brush.linearGradient(
                        colors = PRECURE_BG_COLORS,
                        start = Offset.Zero,
                        end = Offset(size.width, size.height),
                    )
                    val rayCenter = Offset(size.width * 0.5f, size.height * 0.44f)
                    val rayLen = size.maxDimension * 0.85f
                    val rayWedge = Path().apply {
                        moveTo(0f, 0f)
                        lineTo(rayLen, -rayLen * 0.13f)
                        lineTo(rayLen, rayLen * 0.13f)
                        close()
                    }
                    val particlePaths = Array(particles.size) { i ->
                        val r = particles[i].sizeDp.dp.toPx() * 0.5f
                        when (particles[i].kind) {
                            PrecureParticleKind.HEART -> buildUnitHeartPath(r)
                            PrecureParticleKind.KIRAKIRA -> buildUnitKirakiraPath(r)
                            PrecureParticleKind.BUBBLE -> null
                        }
                    }
                    val particleRadii = FloatArray(particles.size) { particles[it].sizeDp.dp.toPx() * 0.5f }
                    val bubbleStroke = Stroke(width = 2.dp.toPx())

                    onDrawBehind {
                        val elapsed = elapsedState.value
                        drawRect(brush = bgBrush)

                        // Soft rotating pastel magical sunburst rays
                        val baseRot = (elapsed * 6f) % 360f
                        translate(left = rayCenter.x, top = rayCenter.y) {
                            for (k in 0 until 10) {
                                rotate(degrees = baseRot + k * 36f, pivot = Offset.Zero) {
                                    drawPath(rayWedge, color = Color.White.copy(alpha = 0.22f))
                                }
                            }
                        }

                        // Rising & twinkling hearts, 4-point kirakira stars, and pastel bubbles
                        for (i in particles.indices) {
                            val p = particles[i]
                            val r = particleRadii[i]
                            var yNorm = (p.ny - elapsed * p.speed) % 1.15f
                            if (yNorm < -0.08f) yNorm += 1.15f
                            val cx = size.width * p.nx + sin(elapsed * 1.3f + p.phase) * 14f
                            val cy = size.height * yNorm
                            val twinkle = 0.55f + 0.45f * ((sin(elapsed * 3.0f + p.phase) + 1f) * 0.5f)

                            translate(left = cx, top = cy) {
                                when (p.kind) {
                                    PrecureParticleKind.BUBBLE -> {
                                        drawCircle(Color.White.copy(alpha = 0.24f * twinkle), radius = r, center = Offset.Zero)
                                        drawCircle(Color.White.copy(alpha = 0.65f * twinkle), radius = r, center = Offset.Zero, style = bubbleStroke)
                                        drawCircle(Color.White.copy(alpha = 0.8f * twinkle), radius = r * 0.22f, center = Offset(-r * 0.32f, -r * 0.32f))
                                    }
                                    PrecureParticleKind.KIRAKIRA -> {
                                        val path = particlePaths[i] ?: return@translate
                                        val s = 0.72f + 0.35f * twinkle
                                        scale(scaleX = s, scaleY = s, pivot = Offset.Zero) {
                                            drawPath(path, color = p.color.copy(alpha = 0.92f * twinkle))
                                        }
                                    }
                                    PrecureParticleKind.HEART -> {
                                        val path = particlePaths[i] ?: return@translate
                                        rotate(degrees = sin(elapsed * 1.6f + p.phase) * 14f, pivot = Offset.Zero) {
                                            drawPath(path, color = p.color.copy(alpha = 0.78f))
                                        }
                                    }
                                }
                            }
                        }
                    }
                },
        )

        // Centered magical-girl frosted heart crest (safe above ~110dp bottom preview HUD)
        val outerShape = RoundedCornerShape(36.dp)
        val innerShape = RoundedCornerShape(30.dp)
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .padding(bottom = 52.dp)
                .background(Color.White.copy(alpha = 0.78f), outerShape)
                .border(3.dp, PRECURE_PINK.copy(alpha = 0.85f), outerShape)
                .padding(6.dp)
                .border(1.5.dp, PRECURE_GOLD, innerShape)
                .padding(horizontal = 42.dp, vertical = 22.dp),
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                val ribbonShape = RoundedCornerShape(999.dp)
                Box(
                    modifier = Modifier
                        .background(PRECURE_PINK, ribbonShape)
                        .padding(horizontal = 18.dp, vertical = 5.dp),
                ) {
                    Text(
                        text = "♥  $inputLabel  ♥",
                        color = Color.White,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = 2.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = 340.dp),
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))

                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = "しんごう待機中♪",
                        style = TextStyle(
                            color = Color.White,
                            fontSize = 44.sp,
                            fontWeight = FontWeight.Black,
                            letterSpacing = 2.sp,
                            drawStyle = PRECURE_STICKER_OUTLINE,
                            shadow = PRECURE_TITLE_SHADOW,
                        ),
                        maxLines = 1,
                    )
                    Text(
                        text = "しんごう待機中♪",
                        style = TextStyle(
                            color = PRECURE_PINK,
                            fontSize = 44.sp,
                            fontWeight = FontWeight.Black,
                            letterSpacing = 2.sp,
                        ),
                        maxLines = 1,
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "✦  ${statusText.ifBlank { "信号がありません" }}  ✦",
                    color = PRECURE_PLUM,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 420.dp),
                )
            }
        }
    }
}
