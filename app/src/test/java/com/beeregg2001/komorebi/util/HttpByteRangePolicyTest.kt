package com.beeregg2001.komorebi.util

import org.junit.Assert.assertFalse
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
}
