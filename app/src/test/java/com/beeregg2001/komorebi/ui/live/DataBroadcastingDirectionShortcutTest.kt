package com.beeregg2001.komorebi.ui.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DataBroadcastingDirectionShortcutTest {
    @Test
    fun singleTapEmitsOriginalDirectionAfterTimeout() {
        val shortcut = DataBroadcastingDirectionShortcut()

        val tap = shortcut.onTap(DataBroadcastingDirection.Up)

        assertNull(tap.immediateRemoteKey)
        assertNull(tap.colorKey)
        assertEquals("up", shortcut.onTimeout(requireNotNull(tap.pendingToken)))
    }

    @Test
    fun repeatedDirectionEmitsMappedColorWithoutDirection() {
        val mappings = listOf(
            DataBroadcastingDirection.Up to DataBroadcastingColorKey.Blue,
            DataBroadcastingDirection.Right to DataBroadcastingColorKey.Red,
            DataBroadcastingDirection.Down to DataBroadcastingColorKey.Green,
            DataBroadcastingDirection.Left to DataBroadcastingColorKey.Yellow
        )

        mappings.forEach { (direction, expectedColor) ->
            val shortcut = DataBroadcastingDirectionShortcut()
            val firstTap = shortcut.onTap(direction)
            val secondTap = shortcut.onTap(direction)

            assertNull(secondTap.immediateRemoteKey)
            assertNull(secondTap.pendingToken)
            assertEquals(expectedColor, secondTap.colorKey)
            assertNull(shortcut.onTimeout(requireNotNull(firstTap.pendingToken)))
        }
    }

    @Test
    fun differentDirectionFlushesPreviousAndWaitsForCurrent() {
        val shortcut = DataBroadcastingDirectionShortcut()
        val firstTap = shortcut.onTap(DataBroadcastingDirection.Up)

        val secondTap = shortcut.onTap(DataBroadcastingDirection.Right)

        assertEquals("up", secondTap.immediateRemoteKey)
        assertNull(secondTap.colorKey)
        assertNull(shortcut.onTimeout(requireNotNull(firstTap.pendingToken)))
        assertEquals("right", shortcut.onTimeout(requireNotNull(secondTap.pendingToken)))
    }

    @Test
    fun resetDropsPendingDirectionAndInvalidatesTimeout() {
        val shortcut = DataBroadcastingDirectionShortcut()
        val tap = shortcut.onTap(DataBroadcastingDirection.Down)

        shortcut.reset()

        assertNull(shortcut.onTimeout(requireNotNull(tap.pendingToken)))
        assertNull(shortcut.flush())
    }
}
