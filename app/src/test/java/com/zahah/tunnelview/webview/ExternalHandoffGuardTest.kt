package com.zahah.tunnelview.webview

import org.junit.Assert.*
import org.junit.Test

class ExternalHandoffGuardTest {
    @Test fun `automatic cross-origin fallback after approval cannot replace the live page`() {
        val guard = ExternalHandoffGuard()
        guard.begin("https://site.example/work", 100)
        assertTrue(guard.blocks("https://external.example/denied", true, false, 5_000))
    }
    @Test fun `normal callbacks resources and explicit user navigation remain allowed`() {
        val guard = ExternalHandoffGuard()
        guard.begin("https://site.example/work", 100)
        assertFalse(guard.blocks("https://site.example/callback", true, false, 5_000))
        assertFalse(guard.blocks("https://external.example/image", false, false, 5_000))
        assertFalse(guard.blocks("https://external.example/page", true, true, 5_000))
    }
    @Test fun `protection expires and can be cleared`() {
        val guard = ExternalHandoffGuard()
        assertFalse(guard.active(100))
        guard.begin("https://site.example/work", 100)
        assertTrue(guard.active(5_000))
        assertFalse(guard.active(300_101))
        guard.clear()
        assertFalse(guard.active(5_000))
    }
}
