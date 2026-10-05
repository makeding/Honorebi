package com.beeregg2001.komorebi.ui.home

import org.junit.Assert.*
import org.junit.Test

class FocusMemoryTest {
    @Test fun repeatedItemReturnProducesNewRequest() {
        val memory = FocusMemory()
        memory.remember(0, "history", "123")
        val first = memory.request!!
        memory.prepare(first.id)
        memory.consume(first.id)
        assertNull(memory.request)
        memory.remember(0, "history", "123")
        assertTrue(memory.request!!.id > first.id)
        assertEquals("123", memory.request!!.itemId)
        assertNotEquals(memory.requestId, memory.preparedId)
    }

    @Test fun staleCompletionCannotDiscardNewTarget() {
        val memory = FocusMemory()
        memory.remember(0, "hot", "a")
        val oldId = memory.requestId!!
        memory.remember(0, "hot", "b")
        memory.prepare(oldId)
        memory.consume(oldId)
        assertEquals("b", memory.request!!.itemId)
        assertNull(memory.preparedId)
        assertEquals(0L, memory.completedId)
    }

    @Test fun tabAndContentEntryHaveDifferentDestinations() {
        val memory = FocusMemory()
        memory.remember(3)
        val tab = memory.request!!
        assertFalse(tab.contentTop)
        assertNull(tab.section)
        memory.requestContent(3)
        assertTrue(memory.request!!.contentTop)
        assertEquals(3, memory.request!!.tab)
        assertTrue(memory.request!!.id > tab.id)
    }
}
