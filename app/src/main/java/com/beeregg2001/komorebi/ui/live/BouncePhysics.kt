package com.beeregg2001.komorebi.ui.live

import kotlin.math.abs
import kotlin.random.Random

internal class BouncePhysics(
    private val paletteSize: Int,
    seed: Long = System.nanoTime(),
) {
    companion object {
        const val DVD_VX_DP_PER_SEC = 132f
        const val DVD_VY_DP_PER_SEC = 96f
        const val HONOREBI_VX_DP_PER_SEC = 112f
        const val HONOREBI_VY_DP_PER_SEC = 82f
        const val DEFAULT_INSET_DP = 16f
        const val MAX_DT_NANOS = 50_000_000L
    }

    private val random = Random(seed)
    private var minX = 0f
    private var maxX = 0f
    private var minY = 0f
    private var maxY = 0f
    private var initialized = false

    var x: Float = 0f
        private set
    var y: Float = 0f
        private set
    var vx: Float = 0f
        private set
    var vy: Float = 0f
        private set
    var colorIndex: Int = 0
        private set

    fun reset(
        containerW: Float,
        containerH: Float,
        logoW: Float,
        logoH: Float,
        vxPxPerSec: Float,
        vyPxPerSec: Float,
        insetPx: Float = 0f,
        startX: Float? = null,
        startY: Float? = null,
    ) {
        updateBounds(containerW, containerH, logoW, logoH, insetPx)
        x = if (startX != null) {
            startX.coerceIn(minX, maxX)
        } else if (maxX > minX) {
            minX + random.nextFloat() * (maxX - minX)
        } else {
            minX
        }
        y = if (startY != null) {
            startY.coerceIn(minY, maxY)
        } else if (maxY > minY) {
            minY + random.nextFloat() * (maxY - minY)
        } else {
            minY
        }
        vx = if (startX != null) {
            vxPxPerSec
        } else {
            if (random.nextBoolean()) abs(vxPxPerSec) else -abs(vxPxPerSec)
        }
        vy = if (startY != null) {
            vyPxPerSec
        } else {
            if (random.nextBoolean()) abs(vyPxPerSec) else -abs(vyPxPerSec)
        }
        colorIndex = if (paletteSize > 0) random.nextInt(paletteSize) else 0
        initialized = true
    }

    fun resize(
        containerW: Float,
        containerH: Float,
        logoW: Float,
        logoH: Float,
        insetPx: Float = 0f,
    ) {
        updateBounds(containerW, containerH, logoW, logoH, insetPx)
        x = x.coerceIn(minX, maxX)
        y = y.coerceIn(minY, maxY)
    }

    fun step(dtNanos: Long): Boolean {
        if (!initialized || dtNanos <= 0L) return false
        val clampedDtSec = dtNanos.coerceIn(0L, MAX_DT_NANOS) / 1_000_000_000f
        var nx = x + vx * clampedDtSec
        var ny = y + vy * clampedDtSec
        var bounced = false

        if (minX >= maxX) {
            nx = minX
        } else if (nx <= minX) {
            nx = (minX + (minX - nx)).coerceAtMost(maxX)
            vx = abs(vx)
            bounced = true
        } else if (nx >= maxX) {
            nx = (maxX - (nx - maxX)).coerceAtLeast(minX)
            vx = -abs(vx)
            bounced = true
        }

        if (minY >= maxY) {
            ny = minY
        } else if (ny <= minY) {
            ny = (minY + (minY - ny)).coerceAtMost(maxY)
            vy = abs(vy)
            bounced = true
        } else if (ny >= maxY) {
            ny = (maxY - (ny - maxY)).coerceAtLeast(minY)
            vy = -abs(vy)
            bounced = true
        }

        x = nx.coerceIn(minX, maxX)
        y = ny.coerceIn(minY, maxY)

        if (bounced && paletteSize > 1) {
            colorIndex = (colorIndex + 1) % paletteSize
        }
        return bounced
    }

    private fun updateBounds(
        containerW: Float,
        containerH: Float,
        logoW: Float,
        logoH: Float,
        insetPx: Float,
    ) {
        val safeInset = insetPx.coerceAtLeast(0f)
        val availableW = (containerW - logoW).coerceAtLeast(0f)
        val availableH = (containerH - logoH).coerceAtLeast(0f)
        if (availableW >= safeInset * 2f) {
            minX = safeInset
            maxX = availableW - safeInset
        } else {
            val centerX = availableW / 2f
            minX = centerX
            maxX = centerX
        }
        if (availableH >= safeInset * 2f) {
            minY = safeInset
            maxY = availableH - safeInset
        } else {
            val centerY = availableH / 2f
            minY = centerY
            maxY = centerY
        }
    }
}
