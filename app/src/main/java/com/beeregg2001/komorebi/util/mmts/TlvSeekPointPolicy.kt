package com.beeregg2001.komorebi.util.mmts

internal const val MAX_INDEXED_FORWARD_SCAN_BYTES = 16L * 1024L * 1024L

/**
 * An exact but old RAP can be slower than a proportional range estimate for
 * high-bitrate 4K. Keep an indexed point only when the average-bitrate scan to
 * the target fits the same 16 MiB backoff budget used by estimated seeking.
 */
internal fun shouldUseIndexedTlvSeekPoint(
    distanceFromIndexedPointUs: Long,
    recordingDurationUs: Long,
    inputLengthBytes: Long,
    hasFollowingIndexedPoint: Boolean,
): Boolean {
    if (hasFollowingIndexedPoint) return true
    if (
        distanceFromIndexedPointUs < 0L ||
        recordingDurationUs <= 0L ||
        inputLengthBytes <= 0L
    ) {
        return false
    }
    val estimatedForwardScanBytes =
        distanceFromIndexedPointUs.toDouble() /
            recordingDurationUs.toDouble() * inputLengthBytes.toDouble()
    return estimatedForwardScanBytes <= MAX_INDEXED_FORWARD_SCAN_BYTES.toDouble()
}

internal fun estimatedIndexedForwardScanBytes(
    distanceFromIndexedPointUs: Long,
    recordingDurationUs: Long,
    inputLengthBytes: Long,
): Long = if (
    distanceFromIndexedPointUs < 0L || recordingDurationUs <= 0L || inputLengthBytes <= 0L
) {
    Long.MAX_VALUE
} else {
    (distanceFromIndexedPointUs.toDouble() /
        recordingDurationUs.toDouble() * inputLengthBytes.toDouble()).toLong()
}
