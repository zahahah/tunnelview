package com.zahah.tunnelview.webview

import android.os.Build
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView

data class WebViewSecurityConfig(
    val javaScriptEnabled: Boolean = true,
    val domStorageEnabled: Boolean = true,
    val databaseEnabled: Boolean = true,
    val cacheMode: Int = WebSettings.LOAD_DEFAULT,
    val allowFileAccess: Boolean = true,
    val allowContentAccess: Boolean = true,
    val allowFileAccessFromFileUrls: Boolean = true,
    val allowUniversalAccessFromFileUrls: Boolean = true,
    val mixedContentMode: Int = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE,
    val loadsImagesAutomatically: Boolean = true,
    val blockNetworkImage: Boolean = false,
    val acceptCookies: Boolean = true,
    val acceptThirdPartyCookies: Boolean = true,
)

object WebViewConfigurator {

    fun defaultSecurityConfig(): WebViewSecurityConfig = WebViewSecurityConfig()

    fun applyDefaultSecurity(webView: WebView) {
        val config = defaultSecurityConfig()
        apply(webView.settings, config)
        if (config.acceptCookies) {
            CookieManager.getInstance().setAcceptCookie(true)
        }
        if (config.acceptThirdPartyCookies && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)
        }
    }

    @Suppress("DEPRECATION")
    fun apply(settings: WebSettings, config: WebViewSecurityConfig) {
        with(settings) {
            javaScriptEnabled = config.javaScriptEnabled
            domStorageEnabled = config.domStorageEnabled
            databaseEnabled = config.databaseEnabled
            cacheMode = config.cacheMode
            allowFileAccess = config.allowFileAccess
            allowContentAccess = config.allowContentAccess
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
                allowFileAccessFromFileURLs = config.allowFileAccessFromFileUrls
                allowUniversalAccessFromFileURLs = config.allowUniversalAccessFromFileUrls
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                mixedContentMode = config.mixedContentMode
            }
            loadsImagesAutomatically = config.loadsImagesAutomatically
            blockNetworkImage = config.blockNetworkImage
        }
    }
}
