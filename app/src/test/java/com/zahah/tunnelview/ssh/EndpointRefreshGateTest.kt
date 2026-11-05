package com.zahah.tunnelview.ssh

import org.junit.Assert.*
import org.junit.Test

class EndpointRefreshGateTest {
    @Test fun `first refresh is immediate and repeat requests are limited`() {
        val gate = EndpointRefreshGate()
        assertTrue(gate.shouldRefresh(100))
        assertFalse(gate.shouldRefresh(5_100))
        assertFalse(gate.shouldRefresh(15_099))
        assertTrue(gate.shouldRefresh(15_100))
    }

    @Test fun `recovered endpoint and clock resets allow immediate refresh`() {
        val gate = EndpointRefreshGate()
        assertTrue(gate.shouldRefresh(40_000))
        gate.reset()
        assertTrue(gate.shouldRefresh(40_001))
        assertTrue(gate.shouldRefresh(1))
    }
}
