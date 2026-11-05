package com.zahah.tunnelview.testfixtures

import android.content.Context
import android.os.PowerManager
import android.os.SystemClock
import android.webkit.WebView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.zahah.tunnelview.MainActivity
import com.zahah.tunnelview.R
import com.zahah.tunnelview.storage.CredentialsStore
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Job
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class ScreenOffFlowTest {
    @Test fun screenOffPausesAndResumesWebViewWithoutReloadingPage() {
        val server = MockWebServer()
        server.start()
        server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest) =
                MockResponse().setHeader("Content-Type", "text/html")
                    .setBody("<html><body>test<script>window.pageIdentity='live-'+Math.random(); window.visibilityEvents=[]; document.addEventListener('visibilitychange',()=>window.visibilityEvents.push(document.hidden));</script></body></html>")
        }
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        runBlocking {
            val store = CredentialsStore.getInstance(context)
            store.setHttpHeaderName(null)
            store.setHttpHeaderValue(null)
        }
        context.getSharedPreferences("prefs", Context.MODE_PRIVATE).edit().clear()
            .putBoolean("directConnectionEnabled", true)
            .putString("localIpEndpoint", server.url("/").toString()).commit()
        val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                var identity = "null"
                for (i in 0..60) {
                    identity = evaluate(scenario, "window.pageIdentity || null")
                    if (identity != "null") break
                    SystemClock.sleep(100)
                }
                assertNotEquals("Page did not load", "null", identity)
                var coordinator: Any? = null
                scenario.onActivity {
                    coordinator = MainActivity::class.java.getDeclaredField("coordinator")
                        .apply { isAccessible = true }.get(it)
                }
                val paused = coordinator!!.javaClass.getDeclaredField("webViewPausedForScreenOff")
                    .apply { isAccessible = true }
                val foreground = coordinator!!.javaClass.getDeclaredField("isForeground")
                    .apply { isAccessible = true }
                val monitor = coordinator!!.javaClass.getDeclaredField("monitorJob")
                    .apply { isAccessible = true }
                instrumentation.uiAutomation.executeShellCommand("input keyevent KEYCODE_SLEEP").close()
                for (i in 0..80) {
                    if (!power.isInteractive && paused.getBoolean(coordinator)) break
                    SystemClock.sleep(100)
                }
                assertFalse("Screen did not turn off", power.isInteractive)
                assertTrue("WebView was not paused on screen off", paused.getBoolean(coordinator))
                assertFalse("Screen-off page still marked foreground", foreground.getBoolean(coordinator))
                assertFalse("Connection monitor continued after screen off",
                    (monitor.get(coordinator) as? Job)?.isActive == true)
                SystemClock.sleep(500)
                instrumentation.uiAutomation.executeShellCommand("input keyevent KEYCODE_WAKEUP").close()
                instrumentation.uiAutomation.executeShellCommand("wm dismiss-keyguard").close()
                for (i in 0..100) {
                    if (power.isInteractive && !paused.getBoolean(coordinator) &&
                        foreground.getBoolean(coordinator)) break
                    SystemClock.sleep(100)
                }
                assertTrue("Screen did not wake", power.isInteractive)
                assertFalse("WebView did not resume", paused.getBoolean(coordinator))
                assertTrue("Page did not return to foreground", foreground.getBoolean(coordinator))
                assertEquals("Page was unexpectedly reloaded", identity,
                    evaluate(scenario, "window.pageIdentity || null"))
                assertEquals("Page did not observe screen-off visibility", "true",
                    evaluate(scenario, "window.visibilityEvents.includes(true)"))
            }
        } finally {
            // Never leave the test emulator locked after a failure.
            instrumentation.uiAutomation.executeShellCommand("input keyevent KEYCODE_WAKEUP").close()
            instrumentation.uiAutomation.executeShellCommand("wm dismiss-keyguard").close()
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
