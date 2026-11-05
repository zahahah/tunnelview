package com.zahah.tunnelview.testfixtures

import android.content.Context
import android.os.SystemClock
import android.webkit.WebView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.zahah.tunnelview.MainActivity
import com.zahah.tunnelview.Prefs
import com.zahah.tunnelview.R
import com.zahah.tunnelview.storage.CredentialsStore
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class CacheToggleFlowTest {
    @Test fun cacheDisabledDoesNotSerializeAndReenableCapturesWithoutReload() {
        val server = MockWebServer()
        server.start()
        server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest) =
                MockResponse().setHeader("Content-Type", "text/html").setBody("""
                    <html><body><script>
                    window.cacheReads=0;
                    const d=Object.getOwnPropertyDescriptor(Element.prototype,'outerHTML');
                    Object.defineProperty(document.documentElement,'outerHTML',{
                        configurable:true,get:function(){window.cacheReads++;return d.get.call(this);}
                    });
                    window.pageIdentity='same-page';
                    </script></body></html>
                """.trimIndent())
        }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        runBlocking {
            CredentialsStore.getInstance(context).apply {
                setHttpHeaderName(null)
                setHttpHeaderValue(null)
            }
        }
        context.getSharedPreferences("prefs", Context.MODE_PRIVATE).edit().clear()
            .putBoolean("directConnectionEnabled", true)
            .putBoolean("cacheLastPage", false)
            .putString("localIpEndpoint", server.url("/").toString()).commit()
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                for (i in 0..60) {
                    if (evaluate(scenario, "window.pageIdentity || null") != "null") break
                    SystemClock.sleep(100)
                }
                SystemClock.sleep(500)
                assertEquals("false", evaluate(scenario, "window.__netHooked2 === true"))
                assertEquals("0", evaluate(scenario, "window.cacheReads"))
                Prefs(context).cacheLastPage = true
                var reads = 0
                for (i in 0..40) {
                    reads = evaluate(scenario, "window.cacheReads").toIntOrNull() ?: 0
                    if (reads > 0) break
                    SystemClock.sleep(100)
                }
                assertTrue("Cache did not capture after enabling", reads > 0)
                Prefs(context).cacheLastPage = false
                SystemClock.sleep(700)
                val before = evaluate(scenario, "window.cacheReads").toInt()
                evaluate(scenario, "document.body.setAttribute('data-change','1'); true")
                SystemClock.sleep(4500)
                assertEquals("Disabled cache serialized a changed page", before,
                    evaluate(scenario, "window.cacheReads").toInt())
                Prefs(context).cacheLastPage = true
                for (i in 0..40) {
                    reads = evaluate(scenario, "window.cacheReads").toIntOrNull() ?: 0
                    if (reads > before) break
                    SystemClock.sleep(100)
                }
                assertTrue("Cache did not recapture after re-enabling", reads > before)
                assertEquals("\"same-page\"", evaluate(scenario, "window.pageIdentity"))
            }
        } finally {
            server.shutdown()
        }
    }

    private fun evaluate(scenario: ActivityScenario<MainActivity>, script: String): String {
        val latch = CountDownLatch(1)
        var result = "null"
        scenario.onActivity {
            it.findViewById<WebView>(R.id.webview).evaluateJavascript(script) { value ->
                result = value
                latch.countDown()
            }
        }
        assertTrue("JavaScript callback timed out", latch.await(5, TimeUnit.SECONDS))
        return result
    }
}
