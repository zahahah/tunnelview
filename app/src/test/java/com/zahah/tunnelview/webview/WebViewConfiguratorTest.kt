package com.zahah.tunnelview.webview

import android.webkit.WebSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebViewConfiguratorTest {

    @Test
    fun `default config enables required capabilities and keeps risky flags off`() {
        val config = WebViewConfigurator.defaultSecurityConfig()

        assertTrue(config.javaScriptEnabled)
        assertTrue(config.domStorageEnabled)
        assertTrue(config.databaseEnabled)
        assertEquals(WebSettings.LOAD_DEFAULT, config.cacheMode)
        assertTrue(config.allowFileAccess)
        assertTrue(config.allowContentAccess)
        assertTrue(config.allowFileAccessFromFileUrls)
        assertTrue(config.allowUniversalAccessFromFileUrls)
        assertEquals(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE, config.mixedContentMode)
        assertTrue(config.loadsImagesAutomatically)
        assertFalse(config.blockNetworkImage)
        assertTrue(config.acceptCookies)
        assertTrue(config.acceptThirdPartyCookies)
    }
}
