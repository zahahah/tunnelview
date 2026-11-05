package com.zahah.tunnelview.network

import org.junit.Assert.assertEquals
import org.junit.Test

class ReconnectBackoffTest {
    @Test fun `retries grow from five seconds and remain bounded`() {
        val policy = ReconnectBackoff()
        assertEquals(listOf(5000L, 10000L, 20000L, 30000L, 30000L, 30000L),
            List(6) { policy.next() })
    }

    @Test fun `successful connection or stop restores prompt retry`() {
        val policy = ReconnectBackoff()
        repeat(4) { policy.next() }
        policy.reset()
        assertEquals(5000L, policy.next())
    }
}
