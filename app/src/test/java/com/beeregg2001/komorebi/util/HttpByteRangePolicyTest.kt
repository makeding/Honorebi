package com.beeregg2001.komorebi.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HttpByteRangePolicyTest {
    @Test
    fun rangedRequest_rejectsAFullBody200Response() {
        assertTrue(HttpByteRangePolicy.acceptsResponse(0L, 200))
        assertTrue(HttpByteRangePolicy.acceptsResponse(1_000L, 206))
        assertFalse(HttpByteRangePolicy.acceptsResponse(1_000L, 200))
        assertFalse(HttpByteRangePolicy.acceptsResponse(1_000L, 416))
    }

    @Test
    fun resourceLength_prefersContentRangeTotalAndFallsBackToObservedEnd() {
        assertEquals(
            9_999L,
            HttpByteRangePolicy.resourceLength(1_000L, 2_000L, "bytes 1000-2999/9999"),
        )
        assertEquals(3_000L, HttpByteRangePolicy.resourceLength(1_000L, 2_000L, null))
        assertEquals(2_000L, HttpByteRangePolicy.resourceLength(0L, 2_000L, null))
        assertNull(HttpByteRangePolicy.resourceLength(0L, 0L, null))
    }
}
