package com.zahah.tunnelview.webview

import org.junit.Assert.*
import org.junit.Test

class ExternalLinkPolicyTest {
    @Test fun `Rabby app links never fall through to its XML storage endpoint`() {
        val query = "uri=wc%3Atest%402%3Frelay-protocol%3Dirn%26symKey%3Ddummy&source=dapp"
        assertEquals("rabby://wc?$query",
            ExternalLinkPolicy.externalTarget("https://go.rabby.io/mobile/wc?$query"))
        assertTrue(ExternalLinkPolicy.isExternal("https://go.rabby.io/mobile/wc"))
        assertEquals("rabby://wc", ExternalLinkPolicy.externalTarget("https://go.rabby.io/mobile/wc"))
    }

    @Test fun `Rabby link conversion preserves escapes query and fragment verbatim`() {
        val suffix = "?uri=wc%3Atest%402%3Fx%3Da%252Bb%26symKey%3Ddummy#return"
        assertEquals("rabby://wc$suffix",
            ExternalLinkPolicy.externalTarget("https://GO.RABBY.IO:443/mobile/wc/$suffix"))
        assertEquals("rabby://wc$suffix", ExternalLinkPolicy.externalTarget("rabby://wc$suffix"))
    }

    @Test fun `Rabby normalization cannot be triggered by lookalike domains or unrelated paths`() {
        listOf("https://go.rabby.io.attacker.test/mobile/wc",
            "https://go.rabby.io@attacker.test/mobile/wc",
            "https://attacker.test/?uri=wc%3Atest",
            "https://go.rabby.io/mobile/download",
            "https://go.rabby.io:8443/mobile/wc",
            "http://go.rabby.io/mobile/wc").forEach {
            assertEquals(it, ExternalLinkPolicy.externalTarget(it))
        }
    }

    @Test fun `wallet pairing is dispatched without altering encoded payload`() {
        val url = "rabby://wc?uri=wc%3Atest%402%3FsymKey%3Dsecret"
        assertTrue(ExternalLinkPolicy.isExternal(url))
        assertTrue(ExternalLinkPolicy.isExternal("wc:test@2?relay-protocol=irn"))
        assertTrue(ExternalLinkPolicy.isExternal("intent://wc#Intent;scheme=rabby;end"))
        assertTrue(ExternalLinkPolicy.isSafeIntentTarget(url))
    }

    @Test fun `wallet universal links are external but ordinary web pages are not`() {
        assertTrue(ExternalLinkPolicy.isExternal("https://wallet.example/wc?uri=wc%3Atest%402"))
        assertFalse(ExternalLinkPolicy.isExternal("https://dapp.example/?uri=ordinary"))
        assertFalse(ExternalLinkPolicy.isExternal("https://dapp.example/app"))
        assertFalse(ExternalLinkPolicy.isExternal("https://dapp.example/?uri=%ZZ"))
    }

    @Test fun `intent target cannot reach internal content or nested intents`() {
        listOf("file:///secret", "content://private", "javascript:alert(1)", "data:text/html,test",
            "intent://nested", "android-app://app", "about:blank", "blob:https://site/id").forEach {
            assertFalse(it, ExternalLinkPolicy.isSafeIntentTarget(it))
        }
    }

    @Test fun `authentication interception is restricted to exact web origin`() {
        assertTrue(ExternalLinkPolicy.sameOrigin("https://host/app", "https://HOST:443/"))
        assertFalse(ExternalLinkPolicy.sameOrigin("https://wallet.example/", "https://host/"))
        assertFalse(ExternalLinkPolicy.sameOrigin("http://host/", "https://host/"))
        assertFalse(ExternalLinkPolicy.sameOrigin("https://host:8443/", "https://host/"))
        assertFalse(ExternalLinkPolicy.sameOrigin("rabby://host", "https://host/"))
        assertFalse(ExternalLinkPolicy.sameOrigin("not a URL", "https://host/"))
    }
}
