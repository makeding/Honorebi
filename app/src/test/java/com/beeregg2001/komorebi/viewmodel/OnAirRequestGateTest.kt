package com.beeregg2001.komorebi.viewmodel

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class OnAirRequestGateTest {
    @Test
    fun newerRequestRejectsLateNonCooperativeResponse() {
        val gate = OnAirRequestGate()
        val first = gate.next()
        val second = gate.next()

        assertFalse(gate.isCurrent(first))
        assertTrue(gate.isCurrent(second))
    }

    @Test
    fun weekdayIsAlwaysComputedInJapan() {
        val losAngelesSunday = ZonedDateTime.of(2026, 9, 13, 8, 0, 0, 0, ZoneId.of("America/Los_Angeles"))
        // This instant is Monday in Japan.
        assertTrue(currentJapanWeekday(losAngelesSunday) == 0)
    }

    @Test
    fun backendHostPortOrAuthChangeInvalidatesTheCurrentOnAirSession() {
        val original = OnAirBackendConfiguration("KONOMITV", "tv.example", "7000", "token-a")

        assertTrue(OnAirBackendConfiguration("EDCB", "tv.example", "7000", "token-a").requiresReloadFrom(original))
        assertTrue(OnAirBackendConfiguration("KONOMITV", "other.example", "7000", "token-a").requiresReloadFrom(original))
        assertTrue(OnAirBackendConfiguration("KONOMITV", "tv.example", "7001", "token-a").requiresReloadFrom(original))
        assertTrue(OnAirBackendConfiguration("KONOMITV", "tv.example", "7000", "token-b").requiresReloadFrom(original))
    }
}
