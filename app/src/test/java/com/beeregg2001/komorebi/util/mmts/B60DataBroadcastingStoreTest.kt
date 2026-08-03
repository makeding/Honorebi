package com.beeregg2001.komorebi.util.mmts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class B60DataBroadcastingStoreTest {
    @Test
    fun lctBackgroundFollowsMatchingApplicationContext() {
        val store = B60DataBroadcastingStore()
        store.beginSession("bs4k")

        store.onLayoutConfiguration(contextId = 7, backgroundColorRgb = 0x123456)
        store.onApplicationState(contextId = 7)

        assertEquals(0x123456, store.status.value.lctBackgroundColorRgb)

        store.onLayoutConfiguration(contextId = 8, backgroundColorRgb = 0xabcdef)
        assertEquals(0x123456, store.status.value.lctBackgroundColorRgb)
    }

    @Test
    fun mismatchedOrNewSessionLayoutDoesNotLeak() {
        val store = B60DataBroadcastingStore()
        store.beginSession("bs4k")
        store.onLayoutConfiguration(contextId = 7, backgroundColorRgb = 0x123456)

        store.onApplicationState(contextId = 8)
        assertNull(store.status.value.lctBackgroundColorRgb)

        store.beginSession("bs4k-next")
        assertNull(store.status.value.lctBackgroundColorRgb)
    }

    private fun B60DataBroadcastingStore.onApplicationState(contextId: Long) {
        onApplicationState(
            contextId = contextId,
            applicationType = 0,
            organizationId = 0,
            applicationId = 0,
            controlCode = 0,
            applicationPriority = 0,
            entryPath = "",
            transportUrls = emptyList(),
            collectionState = 0,
            resourceCount = 0,
            entryReady = false
        )
    }
}
