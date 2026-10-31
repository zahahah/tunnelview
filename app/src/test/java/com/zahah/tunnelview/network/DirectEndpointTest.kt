package com.zahah.tunnelview.network

import org.junit.Assert.assertEquals
import org.junit.Test

class DirectEndpointTest {
    @Test fun `editing and saving retains scheme port and path`() {
        listOf("https://example.com", "https://example.com:8443/app?q=1", "http://example.com:8080", "192.168.1.2:3000", "https://[::1]:8443/app").forEach { original ->
            val (host, port) = parseLocalEndpoint(original)
            assertEquals(original, buildLocalEndpoint(host.orEmpty(), port.orEmpty()))
        }
    }

    @Test fun `explicit port replaces an inline port`() {
        assertEquals("https://example.com:9443/app", buildLocalEndpoint("https://example.com:8443/app", "9443"))
    }
}
