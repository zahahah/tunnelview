package com.zahah.tunnelview.webview

import org.junit.Assert.*
import org.junit.Test

class ExternalLinkPolicyTest {
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
