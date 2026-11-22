package com.beeregg2001.komorebi.ui.live

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
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
import androidx.compose.material.icons.filled.SettingsInputHdmi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.beeregg2001.komorebi.R
import com.beeregg2001.komorebi.data.TifNoSignalStyle
import com.beeregg2001.komorebi.ui.screensaver.ScreensaverContent
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

internal val DVD_BOUNCE_PALETTE = listOf(
    Color(0xFF38BDF8),
    Color(0xFFF472B6),
    Color(0xFFFACC15),
    Color(0xFF4ADE80),
    Color(0xFFA78BFA),
    Color(0xFFFB923C),
    Color(0xFFF87171),
    Color(0xFF2DD4BF),
)

internal val HONOREBI_BOUNCE_PALETTE = listOf(
    Color(0xFFFF8A4C),
    Color(0xFFF472B6),
    Color(0xFFA855F7),
    Color(0xFF38BDF8),
    Color(0xFFFBBF24),
    Color(0xFF34D399),
)

internal const val TIF_NO_SIGNAL_BOUNCE_LOGO_TAG = "tif-no-signal-bounce-logo"
internal const val TIF_NO_SIGNAL_SCREENSAVER_PILL_TAG = "tif-no-signal-screensaver-pill"

private val DVD_LOGO_WIDTH = 212.dp
private val DVD_LOGO_HEIGHT = 104.dp
private val HONOREBI_LOGO_WIDTH = 236.dp
private val HONOREBI_LOGO_HEIGHT = 132.dp
private const val JANK_WINDOW_FRAMES = 60
private const val JANK_SLOW_THRESHOLD_FRAMES = 30
private const val JANK_FRAME_DT_NANOS = 50_000_000L

@Composable
internal fun TifNoSignalLayer(
    mode: NoSignalRenderMode,
    style: TifNoSignalStyle,
    statusText: String,
    inputLabel: String,
    isPassthroughInput: Boolean,
    screensaverImageUris: List<String>,
    screensaverIntervalSeconds: Int,
    screensaverTransition: String,
    showOverlay: Boolean,
    paused: Boolean,
    onDegraded: () -> Unit,
    modifier: Modifier = Modifier,
    physicsSeed: Long = System.nanoTime(),
    onLogoComposed: (() -> Unit)? = null,
) {
    Crossfade(
        targetState = mode,
        animationSpec = tween(durationMillis = 200),
        modifier = modifier
            .fillMaxSize()
            .clearAndSetSemantics { contentDescription = statusText },
        label = "TifNoSignalLayer",
    ) { activeMode ->
        when (activeMode) {
            NoSignalRenderMode.BANNER -> Unit
            NoSignalRenderMode.BOUNCE -> {
                TifNoSignalBounceSurface(
                    style = style,
                    statusText = statusText,
                    inputLabel = inputLabel,
                    isPassthroughInput = isPassthroughInput,
                    paused = paused,
                    onDegraded = onDegraded,
                    physicsSeed = physicsSeed,
                    onLogoComposed = onLogoComposed,
                )
            }
            NoSignalRenderMode.SCREENSAVER -> {
                Box(modifier = Modifier.fillMaxSize()) {
                    ScreensaverContent(
                        imageUris = screensaverImageUris,
                        intervalSeconds = screensaverIntervalSeconds,
                        transition = screensaverTransition,
                        upcomingRecordings = null,
                        modifier = Modifier.fillMaxSize(),
                    )
                    if (!showOverlay) {
                        TifScreensaverNoSignalPill(
                            inputLabel = inputLabel,
                            statusText = statusText,
                            modifier = Modifier
                                .align(Alignment.TopStart)
                                .padding(48.dp)
                                .testTag(TIF_NO_SIGNAL_SCREENSAVER_PILL_TAG),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TifNoSignalBounceSurface(
    style: TifNoSignalStyle,
    statusText: String,
    inputLabel: String,
    isPassthroughInput: Boolean,
    paused: Boolean,
    onDegraded: () -> Unit,
    physicsSeed: Long,
    onLogoComposed: (() -> Unit)?,
) {
    val isHonorebi = style == TifNoSignalStyle.HONOREBI_BOUNCE
    val palette = if (isHonorebi) HONOREBI_BOUNCE_PALETTE else DVD_BOUNCE_PALETTE
    val logoWidthDp = if (isHonorebi) HONOREBI_LOGO_WIDTH else DVD_LOGO_WIDTH
    val logoHeightDp = if (isHonorebi) HONOREBI_LOGO_HEIGHT else DVD_LOGO_HEIGHT

    val density = LocalDensity.current
    val logoWidthPx = with(density) { logoWidthDp.toPx() }
    val logoHeightPx = with(density) { logoHeightDp.toPx() }
    val insetPx = with(density) { BouncePhysics.DEFAULT_INSET_DP.dp.toPx() }
    val vxPxPerSec = with(density) {
        (if (isHonorebi) BouncePhysics.HONOREBI_VX_DP_PER_SEC else BouncePhysics.DVD_VX_DP_PER_SEC).dp.toPx()
    }
    val vyPxPerSec = with(density) {
        (if (isHonorebi) BouncePhysics.HONOREBI_VY_DP_PER_SEC else BouncePhysics.DVD_VY_DP_PER_SEC).dp.toPx()
    }

    val physics = remember(style, physicsSeed) { BouncePhysics(palette.size, physicsSeed) }
    var initialized by remember(style, physicsSeed) { mutableStateOf(false) }
    var containerSize by remember { mutableStateOf(IntSize.Zero) }

    val posX = remember(style, physicsSeed) { mutableFloatStateOf(0f) }
    val posY = remember(style, physicsSeed) { mutableFloatStateOf(0f) }
    val colorIndex = remember(style, physicsSeed) { mutableIntStateOf(0) }
    val bounceScale = remember(style, physicsSeed) { Animatable(1f) }
    val bounceGlow = remember(style, physicsSeed) { Animatable(0f) }

    LaunchedEffect(containerSize, style, logoWidthPx, logoHeightPx, insetPx, vxPxPerSec, vyPxPerSec) {
        if (containerSize.width <= 0 || containerSize.height <= 0) return@LaunchedEffect
        if (!initialized) {
            physics.reset(
                containerW = containerSize.width.toFloat(),
                containerH = containerSize.height.toFloat(),
                logoW = logoWidthPx,
                logoH = logoHeightPx,
                vxPxPerSec = vxPxPerSec,
                vyPxPerSec = vyPxPerSec,
                insetPx = insetPx,
            )
            initialized = true
        } else {
            physics.resize(
                containerW = containerSize.width.toFloat(),
                containerH = containerSize.height.toFloat(),
                logoW = logoWidthPx,
                logoH = logoHeightPx,
                insetPx = insetPx,
            )
        }
        posX.floatValue = physics.x
        posY.floatValue = physics.y
        colorIndex.intValue = physics.colorIndex
    }

    LaunchedEffect(style, paused, initialized, containerSize) {
        if (paused || !initialized || containerSize.width <= 0 || containerSize.height <= 0) {
            return@LaunchedEffect
        }
        var lastFrameNanos = 0L
        val dtRing = LongArray(JANK_WINDOW_FRAMES)
        var ringCursor = 0
        var filledFrames = 0
        var slowCount = 0

        while (isActive) {
            withFrameNanos { frameNanos ->
                if (lastFrameNanos != 0L) {
                    val rawDt = (frameNanos - lastFrameNanos).coerceAtLeast(0L)
                    if (filledFrames == JANK_WINDOW_FRAMES && dtRing[ringCursor] > JANK_FRAME_DT_NANOS) {
                        slowCount--
                    }
                    dtRing[ringCursor] = rawDt
                    if (rawDt > JANK_FRAME_DT_NANOS) {
                        slowCount++
                    }
                    ringCursor = (ringCursor + 1) % JANK_WINDOW_FRAMES
                    if (filledFrames < JANK_WINDOW_FRAMES) filledFrames++

                    if (slowCount > JANK_SLOW_THRESHOLD_FRAMES) {
                        onDegraded()
                        return@withFrameNanos
                    }

                    val bounced = physics.step(rawDt)
                    posX.floatValue = physics.x
                    posY.floatValue = physics.y
                    if (bounced) {
                        colorIndex.intValue = physics.colorIndex
                        if (isHonorebi) {
                            launch {
                                bounceScale.snapTo(1.06f)
                                bounceScale.animateTo(1f, tween(durationMillis = 180))
                            }
                            launch {
                                bounceGlow.snapTo(0.6f)
                                bounceGlow.animateTo(0f, tween(durationMillis = 400))
                            }
                        }
                    }
                }
                lastFrameNanos = frameNanos
            }
            if (slowCount > JANK_SLOW_THRESHOLD_FRAMES) break
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .onSizeChanged { containerSize = it },
    ) {
        Box(
            modifier = Modifier
                .size(logoWidthDp, logoHeightDp)
                .testTag(TIF_NO_SIGNAL_BOUNCE_LOGO_TAG)
                .graphicsLayer {
                    translationX = posX.floatValue
                    translationY = posY.floatValue
                    alpha = if (paused) 0.35f else 1f
                    if (isHonorebi) {
                        val scale = bounceScale.value
                        scaleX = scale
                        scaleY = scale
                    }
                },
        ) {
            if (isHonorebi) {
                HonorebiBounceLockup(
                    statusText = statusText,
                    inputLabel = inputLabel,
                    accentColorProvider = { palette[colorIndex.intValue % palette.size] },
                    glowAlphaProvider = { bounceGlow.value },
                    onLogoComposed = onLogoComposed,
                )
            } else {
                DvdBounceLockup(
                    isPassthroughInput = isPassthroughInput,
                    statusText = statusText,
                    inputLabel = inputLabel,
                    tintProvider = { palette[colorIndex.intValue % palette.size] },
                    onLogoComposed = onLogoComposed,
                )
            }
        }
    }
}

@Composable
private fun DvdBounceLockup(
    isPassthroughInput: Boolean,
    statusText: String,
    inputLabel: String,
    tintProvider: () -> Color,
    onLogoComposed: (() -> Unit)?,
) {
    onLogoComposed?.invoke()
    Box(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
            .drawWithContent {
                drawContent()
                drawRect(
                    color = tintProvider(),
                    blendMode = BlendMode.SrcIn,
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = if (isPassthroughInput) "HDMI" else "TV",
                color = Color.White,
                fontSize = 38.sp,
                lineHeight = 40.sp,
                fontWeight = FontWeight.Black,
                fontStyle = FontStyle.Italic,
                letterSpacing = 2.sp,
                maxLines = 1,
            )
            Spacer(
                modifier = Modifier
                    .size(width = 164.dp, height = 18.dp)
                    .drawWithCache {
                        val holeW = 28.dp.toPx()
                        val holeH = 5.dp.toPx()
                        val path = Path().apply {
                            fillType = PathFillType.EvenOdd
                            addOval(Rect(0f, 0f, size.width, size.height))
                            val left = (size.width - holeW) / 2f
                            val top = (size.height - holeH) / 2f
                            addOval(Rect(left, top, left + holeW, top + holeH))
                        }
                        onDrawBehind {
                            drawPath(path = path, color = Color.White)
                        }
                    },
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = statusText,
                color = Color.White,
                fontSize = 13.sp,
                lineHeight = 16.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 196.dp),
            )
            Text(
                text = inputLabel,
                color = Color.White.copy(alpha = 0.75f),
                fontSize = 13.sp,
                lineHeight = 16.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 196.dp),
            )
        }
    }
}

@Composable
private fun HonorebiBounceLockup(
    statusText: String,
    inputLabel: String,
    accentColorProvider: () -> Color,
    glowAlphaProvider: () -> Float,
    onLogoComposed: (() -> Unit)?,
) {
    onLogoComposed?.invoke()
    val pillShape = RoundedCornerShape(14.dp)
    val accentColor = accentColorProvider()
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier.size(76.dp),
            contentAlignment = Alignment.Center,
        ) {
            Spacer(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = glowAlphaProvider() }
                    .drawWithCache {
                        val brush = Brush.radialGradient(
                            colors = listOf(
                                accentColor.copy(alpha = 0.85f),
                                accentColor.copy(alpha = 0.25f),
                                Color.Transparent,
                            ),
                            center = Offset(size.width / 2f, size.height / 2f),
                            radius = size.minDimension / 2f,
                        )
                        onDrawBehind {
                            drawCircle(brush = brush)
                        }
                    },
            )
            Image(
                painter = painterResource(id = R.drawable.honorebi_mark),
                contentDescription = null,
                modifier = Modifier.size(76.dp),
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        Column(
            modifier = Modifier
                .background(Color(0xE010131A), pillShape)
                .border(1.5.dp, accentColor, pillShape)
                .padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(7.dp)
                        .background(accentColor, CircleShape),
                )
                Text(
                    text = "Honorebi · $inputLabel",
                    color = Color.White,
                    fontSize = 12.sp,
                    lineHeight = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 188.dp),
                )
            }
            Text(
                text = statusText,
                color = Color.White.copy(alpha = 0.82f),
                fontSize = 11.sp,
                lineHeight = 14.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 204.dp),
            )
        }
    }
}

@Composable
private fun TifScreensaverNoSignalPill(
    inputLabel: String,
    statusText: String,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(999.dp)
    Row(
        modifier = modifier
            .background(Color.Black.copy(alpha = 0.58f), shape)
            .border(1.dp, Color.White.copy(alpha = 0.14f), shape)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            imageVector = Icons.Default.SettingsInputHdmi,
            contentDescription = null,
            tint = Color.White.copy(alpha = 0.88f),
            modifier = Modifier.size(16.dp),
        )
        Text(
            text = "$inputLabel · $statusText",
            style = MaterialTheme.typography.labelMedium,
            color = Color.White.copy(alpha = 0.92f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 360.dp),
        )
    }
}
