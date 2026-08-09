package com.beeregg2001.komorebi.media

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SystemMediaSessionOwnershipTest {

    @Test
    fun lateDetachFromA_doesNotDetachNewerB() {
        val ownership = SystemMediaSessionOwnership()
        ownership.open(epoch = 7)
        val a = requireNotNull(ownership.acquire(epoch = 7))
        val b = requireNotNull(ownership.acquire(epoch = 7))

        assertFalse(ownership.detach(a))
        assertTrue(ownership.isCurrent(b))
        assertTrue(ownership.detach(b))
    }

    @Test
    fun closeOnlyAppliesToItsOwnEpoch_andIsIdempotent() {
        val ownership = SystemMediaSessionOwnership()
        assertTrue(ownership.open(epoch = 1))
        assertFalse(ownership.open(epoch = 1))
        assertTrue(ownership.close(epoch = 1))
        assertFalse(ownership.close(epoch = 1))

        assertTrue(ownership.open(epoch = 2))
        assertFalse(ownership.close(epoch = 1))
        assertNotNull(ownership.acquire(epoch = 2))
    }

    @Test
    fun attachmentsCannotCrossTheRootEpochBoundary() {
        val ownership = SystemMediaSessionOwnership()
        ownership.open(epoch = 1)
        val old = requireNotNull(ownership.acquire(epoch = 1))

        ownership.open(epoch = 2)

        assertFalse(ownership.isCurrent(old))
        assertFalse(ownership.detach(old))
        assertNull(ownership.acquire(epoch = 1))
        assertNotNull(ownership.acquire(epoch = 2))
    }
}
