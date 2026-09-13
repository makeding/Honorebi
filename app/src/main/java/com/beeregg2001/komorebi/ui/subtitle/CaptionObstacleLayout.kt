package com.beeregg2001.komorebi.ui.subtitle

import androidx.compose.ui.geometry.Rect

/** All coordinates are in the caption plane. Results are positive upward distances. */
internal fun calculateCaptionObstacleOffsets(
    imageRegions: List<List<NativeCaptionRegion>>,
    obstacles: List<Rect>,
): List<List<Float>> {
    val regions = imageRegions.flatten().map {
        Rect(it.x.toFloat(), it.y.toFloat(), (it.x + it.width).toFloat(), (it.y + it.height).toFloat())
    }
    val solid = obstacles.filter { it.width > 0f && it.height > 0f }
    val groups = regions.indices.map { mutableSetOf(it) }.toMutableList()
    val active = regions.map { region -> solid.any { region.overlaps(it) } }

    fun displacement(group: Set<Int>): Float {
        if (group.none { active[it] }) return 0f
        var distance = 0f
        // Each increase passes the top of at least one obstacle/member pair.
        do {
            val previous = distance
            for (index in group) {
                val region = regions[index]
                val moved = Rect(region.left, region.top - distance, region.right, region.bottom - distance)
                for (obstacle in solid) {
                    if (moved.overlaps(obstacle)) {
                        distance = maxOf(distance, region.bottom - obstacle.top)
                    }
                }
            }
        } while (distance > previous)
        return distance
    }

    fun sweptTouches(first: Rect, distance: Float, second: Rect): Boolean =
        first.left < second.right && first.right > second.left &&
            first.top - distance <= second.bottom && first.bottom >= second.top

    // Merge against full target sweeps, never the current animation frame. Restart
    // after a merge so every member contributes to the new group's minimal shift.
    var merged: Boolean
    do {
        merged = false
        val distances = groups.map(::displacement)
        outer@ for (a in groups.indices) {
            for (b in a + 1 until groups.size) {
                if (groups[a].any { first -> groups[b].any { second ->
                        (distances[a] > 0f && sweptTouches(regions[first], distances[a], regions[second])) ||
                            (distances[b] > 0f && sweptTouches(regions[second], distances[b], regions[first]))
                    } }) {
                    groups[a].addAll(groups.removeAt(b))
                    merged = true
                    break@outer
                }
            }
        }
    } while (merged)

    val offsets = FloatArray(regions.size)
    for (group in groups) {
        // Keep the entire group inside the plane even when avoidance is impossible.
        val distance = displacement(group).coerceAtMost(group.minOf { regions[it].top }.coerceAtLeast(0f))
        group.forEach { offsets[it] = distance }
    }
    var index = 0
    return imageRegions.map { image -> List(image.size) { offsets[index++] } }
}
