package com.beeregg2001.komorebi.util

/** A non-zero byte request must receive a partial body, never a silent full-body fallback. */
internal object HttpByteRangePolicy {
    fun acceptsResponse(requestPosition: Long, responseCode: Int): Boolean =
        responseCode in 200..299 && (requestPosition <= 0L || responseCode == 206)
}
