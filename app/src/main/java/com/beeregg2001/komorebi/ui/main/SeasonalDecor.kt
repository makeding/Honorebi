package com.beeregg2001.komorebi.ui.main

import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import com.beeregg2001.komorebi.ui.theme.TimeZone
import com.beeregg2001.komorebi.ui.theme.getTimeZone
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.time.LocalTime
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * Subtle ambient seasonal motif confined strictly to the top-right corner
 * (x in 0.74..0.98, y in 0.12..0.26) below the top-right system strip band (y < 0.12).
 *
 * - Early-returns immediately on "DEFAULT" (allocates zero state/effects/draw nodes).
 * - Zero infinite animations (0fps idle GPU/CPU cost on 32-bit TVs).
 * - Uses [drawWithCache] so paths are precomputed once per size/theme change.
 * - Max 5–6 subtle shapes at alpha <= 0.08f.
 */
@RequiresApi(Build.VERSION_CODES.O)
@Composable
fun SeasonalDecor(
    season: String,
    isDark: Boolean,
    modifier: Modifier = Modifier,
    timeZone: TimeZone? = null,
) {
    if (season == "DEFAULT" || season.isBlank()) return

    val isTimeLinked = season.startsWith("KOMOREBI") || season.startsWith("KYLE")
    var resolvedTimeZone by remember(season, timeZone) {
        mutableStateOf(timeZone ?: if (isTimeLinked) getTimeZone(LocalTime.now()) else TimeZone.NIGHT)
    }

    if (isTimeLinked && timeZone == null) {
        LaunchedEffect(season) {
            while (isActive) {
                delay(60_000L)
                resolvedTimeZone = getTimeZone(LocalTime.now())
            }
        }
    }

    val actualSeason = when (season) {
        "KOMOREBI", "KOMOREBI_DAY", "KOMOREBI_NIGHT" -> "KOMOREBI_${resolvedTimeZone.name}"
        "KYLE", "KYLE_DAY", "KYLE_NIGHT" -> "KYLE_${resolvedTimeZone.name}"
        else -> season
    }

    Spacer(
        modifier = modifier
            .fillMaxSize()
            .drawWithCache {
                val w = size.width
                val h = size.height
                val random = Random(actualSeason.hashCode() * 31 + (if (isDark) 1 else 0))

                // Confined ambient zone: x in [0.74, 0.98], y in [0.12, 0.26]
                fun zoneOffset(): Offset = Offset(
                    x = w * (0.76f + random.nextFloat() * 0.20f),
                    y = h * (0.14f + random.nextFloat() * 0.10f),
                )

                when (actualSeason) {
                    "SPRING" -> {
                        val baseColor = if (isDark) Color(0xFFFFC0CB) else Color(0xFFD81B60)
                        val petals = List(5) {
                            val center = zoneOffset()
                            val r = 10f + random.nextFloat() * 10f
                            val angle = random.nextFloat() * 360f
                            val alpha = 0.05f + random.nextFloat() * 0.03f
                            CachedPathShape(
                                path = buildSakuraPetalPath(center, r),
                                color = baseColor.copy(alpha = alpha),
                                rotation = angle,
                                pivot = center,
                            )
                        }
                        onDrawBehind {
                            petals.forEach { shape ->
                                withTransform({ rotate(shape.rotation, shape.pivot) }) {
                                    drawPath(shape.path, shape.color)
                                }
                            }
                        }
                    }

                    "SUMMER" -> {
                        if (isDark) {
                            val stars = List(5) {
                                val center = zoneOffset()
                                val r = 7f + random.nextFloat() * 7f
                                val alpha = 0.05f + random.nextFloat() * 0.03f
                                CachedPathShape(
                                    path = buildTwinkleStarPath(center, r),
                                    color = Color.White.copy(alpha = alpha),
                                )
                            }
                            onDrawBehind {
                                stars.forEach { drawPath(it.path, it.color) }
                            }
                        } else {
                            val cloudColor = Color(0xFF0288D1).copy(alpha = 0.06f)
                            val clouds = List(3) {
                                val center = zoneOffset()
                                val cw = 42f + random.nextFloat() * 32f
                                buildCloudPath(center, cw)
                            }
                            onDrawBehind {
                                clouds.forEach { drawPath(it, cloudColor) }
                            }
                        }
                    }

                    "AUTUMN" -> {
                        val leafColors = listOf(
                            Color(0xFFD2691E),
                            Color(0xFFFF7043),
                            Color(0xFFDAA520),
                        )
                        val leaves = List(5) {
                            val center = zoneOffset()
                            val r = 10f + random.nextFloat() * 10f
                            val angle = random.nextFloat() * 360f
                            val alpha = 0.05f + random.nextFloat() * 0.03f
                            CachedPathShape(
                                path = buildMapleLeafPath(center, r),
                                color = leafColors[it % leafColors.size].copy(alpha = alpha),
                                rotation = angle,
                                pivot = center,
                            )
                        }
                        onDrawBehind {
                            leaves.forEach { shape ->
                                withTransform({ rotate(shape.rotation, shape.pivot) }) {
                                    drawPath(shape.path, shape.color)
                                }
                            }
                        }
                    }

                    "WINTER" -> {
                        val baseColor = if (isDark) Color.White else Color(0xFF0288D1)
                        val flakes = List(5) {
                            val center = zoneOffset()
                            val r = 8f + random.nextFloat() * 8f
                            val angleOffset = random.nextFloat() * 60f
                            val alpha = 0.05f + random.nextFloat() * 0.03f
                            CachedSnowflake(
                                segments = buildSnowflakeSegments(center, r, angleOffset),
                                strokeWidth = r * 0.14f,
                                color = baseColor.copy(alpha = alpha),
                            )
                        }
                        onDrawBehind {
                            flakes.forEach { flake ->
                                flake.segments.forEach { (start, end) ->
                                    drawLine(flake.color, start, end, strokeWidth = flake.strokeWidth)
                                }
                            }
                        }
                    }

                    "KOMOREBI_MORNING", "KOMOREBI_DAY", "KOMOREBI_EVENING", "KOMOREBI_NIGHT" -> {
                        val moteColor = when (actualSeason) {
                            "KOMOREBI_MORNING" -> Color(0xFFFFD599)
                            "KOMOREBI_DAY" -> Color(0xFF5A7247)
                            "KOMOREBI_EVENING" -> Color(0xFFFFB280)
                            else -> Color(0xFFDDE7F5)
                        }
                        val motes = List(5) {
                            val center = zoneOffset()
                            val r = 8f + random.nextFloat() * 18f
                            val alpha = 0.04f + random.nextFloat() * 0.04f
                            CachedCircle(center, r, moteColor.copy(alpha = alpha))
                        }
                        onDrawBehind {
                            motes.forEach { mote ->
                                drawCircle(mote.color, radius = mote.radius, center = mote.center)
                            }
                        }
                    }

                    "KYLE_MORNING", "KYLE_DAY", "KYLE_EVENING", "KYLE_NIGHT" -> {
                        val accentColor = when (actualSeason) {
                            "KYLE_MORNING" -> Color(0xFFAEE8F2)
                            "KYLE_DAY" -> Color(0xFF00796B)
                            "KYLE_EVENING" -> Color(0xFFFFAB66)
                            else -> Color(0xFF8FBEDC)
                        }
                        val silhouetteCenter = Offset(w * 0.87f, h * 0.19f)
                        val silhouetteSize = (h * 0.065f).coerceIn(28f, 52f)
                        val silhouettePath = buildKyleSilhouettePath(silhouetteCenter, silhouetteSize)
                        val silhouetteColor = accentColor.copy(alpha = 0.07f)
                        val bubbles = List(4) {
                            val center = Offset(
                                x = w * (0.76f + random.nextFloat() * 0.20f),
                                y = h * (0.14f + random.nextFloat() * 0.10f),
                            )
                            val r = 4f + random.nextFloat() * 8f
                            val alpha = 0.05f + random.nextFloat() * 0.03f
                            CachedCircle(center, r, accentColor.copy(alpha = alpha))
                        }
                        val bubbleStroke = Stroke(width = 1.5f)
                        onDrawBehind {
                            drawPath(silhouettePath, silhouetteColor)
                            bubbles.forEach { bubble ->
                                drawCircle(
                                    color = bubble.color,
                                    radius = bubble.radius,
                                    center = bubble.center,
                                    style = bubbleStroke,
                                )
                            }
                        }
                    }

                    else -> onDrawBehind {}
                }
            }
    )
}

private data class CachedPathShape(
    val path: Path,
    val color: Color,
    val rotation: Float = 0f,
    val pivot: Offset = Offset.Zero,
)

private data class CachedCircle(
    val center: Offset,
    val radius: Float,
    val color: Color,
)

private data class CachedSnowflake(
    val segments: List<Pair<Offset, Offset>>,
    val strokeWidth: Float,
    val color: Color,
)

private fun buildKyleSilhouettePath(center: Offset, size: Float): Path {
    val w = size
    val h = size * 0.45f
    return Path().apply {
        moveTo(center.x - w * 0.6f, center.y - h * 0.8f)
        quadraticBezierTo(
            center.x - w * 0.75f, center.y - h * 0.8f,
            center.x - w * 0.9f, center.y - h * 0.1f,
        )
        cubicTo(
            center.x - w * 1.05f, center.y,
            center.x - w * 1.0f, center.y + h * 0.2f,
            center.x - w * 0.8f, center.y + h * 0.3f,
        )
        cubicTo(
            center.x - w * 0.3f, center.y + h * 0.75f,
            center.x + w * 0.3f, center.y + h * 0.4f,
            center.x + w * 0.65f, center.y + h * 0.2f,
        )
        quadraticBezierTo(
            center.x + w * 0.85f, center.y + h * 0.1f,
            center.x + w * 1.15f, center.y + h * 0.85f,
        )
        quadraticBezierTo(
            center.x + w * 1.05f, center.y + h * 0.25f,
            center.x + w * 0.9f, center.y,
        )
        quadraticBezierTo(
            center.x + w * 1.05f, center.y - h * 0.25f,
            center.x + w * 1.15f, center.y - h * 0.85f,
        )
        quadraticBezierTo(
            center.x + w * 0.85f, center.y - h * 0.1f,
            center.x + w * 0.65f, center.y - h * 0.3f,
        )
        cubicTo(
            center.x + w * 0.3f, center.y - h * 0.9f,
            center.x - w * 0.4f, center.y - h * 0.9f,
            center.x - w * 0.6f, center.y - h * 0.8f,
        )
        close()

        // 背びれ
        moveTo(center.x, center.y - h * 0.8f)
        quadraticBezierTo(
            center.x + w * 0.15f, center.y - h * 1.8f,
            center.x + w * 0.35f, center.y - h * 1.5f,
        )
        quadraticBezierTo(
            center.x + w * 0.2f, center.y - h * 1.1f,
            center.x + w * 0.25f, center.y - h * 0.65f,
        )
        close()

        // 胸びれ
        moveTo(center.x - w * 0.4f, center.y + h * 0.35f)
        quadraticBezierTo(
            center.x - w * 0.25f, center.y + h * 1.1f,
            center.x - w * 0.05f, center.y + h * 1.3f,
        )
        quadraticBezierTo(
            center.x - w * 0.15f, center.y + h * 0.8f,
            center.x - w * 0.15f, center.y + h * 0.45f,
        )
        close()
    }
}

private fun buildSakuraPetalPath(center: Offset, size: Float): Path = Path().apply {
    moveTo(center.x, center.y + size)
    cubicTo(
        center.x + size, center.y + size * 0.2f,
        center.x + size * 0.8f, center.y - size,
        center.x + size * 0.15f, center.y - size * 0.7f,
    )
    lineTo(center.x, center.y - size * 0.4f)
    lineTo(center.x - size * 0.15f, center.y - size * 0.7f)
    cubicTo(
        center.x - size * 0.8f, center.y - size,
        center.x - size, center.y + size * 0.2f,
        center.x, center.y + size,
    )
    close()
}

private fun buildCloudPath(center: Offset, width: Float): Path = Path().apply {
    addOval(Rect(center.x - width * 0.4f, center.y - width * 0.15f, center.x, center.y + width * 0.2f))
    addOval(Rect(center.x - width * 0.15f, center.y - width * 0.35f, center.x + width * 0.25f, center.y + width * 0.15f))
    addOval(Rect(center.x + width * 0.05f, center.y - width * 0.2f, center.x + width * 0.4f, center.y + width * 0.15f))
    addRoundRect(
        RoundRect(
            center.x - width * 0.3f,
            center.y - width * 0.05f,
            center.x + width * 0.3f,
            center.y + width * 0.2f,
            CornerRadius(width * 0.1f),
        )
    )
}

private fun buildTwinkleStarPath(center: Offset, size: Float): Path = Path().apply {
    moveTo(center.x, center.y - size)
    quadraticBezierTo(center.x, center.y, center.x + size, center.y)
    quadraticBezierTo(center.x, center.y, center.x, center.y + size)
    quadraticBezierTo(center.x, center.y, center.x - size, center.y)
    quadraticBezierTo(center.x, center.y, center.x, center.y - size)
    close()
}

private fun buildMapleLeafPath(center: Offset, size: Float): Path = Path().apply {
    moveTo(center.x, center.y + size)
    lineTo(center.x, center.y + size * 0.3f)
    lineTo(center.x + size * 0.7f, center.y + size * 0.4f)
    lineTo(center.x + size * 0.4f, center.y + size * 0.1f)
    lineTo(center.x + size * 0.9f, center.y - size * 0.2f)
    lineTo(center.x + size * 0.4f, center.y - size * 0.2f)
    lineTo(center.x, center.y - size)
    lineTo(center.x - size * 0.4f, center.y - size * 0.2f)
    lineTo(center.x - size * 0.9f, center.y - size * 0.2f)
    lineTo(center.x - size * 0.4f, center.y + size * 0.1f)
    lineTo(center.x - size * 0.7f, center.y + size * 0.4f)
    lineTo(center.x, center.y + size * 0.3f)
    close()
}

private fun buildSnowflakeSegments(
    center: Offset,
    radius: Float,
    angleOffset: Float,
): List<Pair<Offset, Offset>> {
    val segments = ArrayList<Pair<Offset, Offset>>(18)
    for (i in 0 until 6) {
        val angle = (angleOffset + i * 60f) * (PI.toFloat() / 180f)
        val end = Offset(center.x + radius * cos(angle), center.y + radius * sin(angle))
        segments.add(center to end)

        val branchDist = radius * 0.5f
        val branchRadius = radius * 0.35f
        val branchCenter = Offset(center.x + branchDist * cos(angle), center.y + branchDist * sin(angle))
        val branchAngle1 = angle + 45f * (PI.toFloat() / 180f)
        val branchAngle2 = angle - 45f * (PI.toFloat() / 180f)

        segments.add(
            branchCenter to Offset(
                branchCenter.x + branchRadius * cos(branchAngle1),
                branchCenter.y + branchRadius * sin(branchAngle1),
            )
        )
        segments.add(
            branchCenter to Offset(
                branchCenter.x + branchRadius * cos(branchAngle2),
                branchCenter.y + branchRadius * sin(branchAngle2),
            )
        )
    }
    return segments
}
