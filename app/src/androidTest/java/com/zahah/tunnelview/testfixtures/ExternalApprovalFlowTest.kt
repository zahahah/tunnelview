package com.zahah.tunnelview.testfixtures

import kotlinx.coroutines.runBlocking
import android.content.Context
import android.os.SystemClock
import android.webkit.WebView
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.matcher.ViewMatchers.withText
import com.zahah.tunnelview.MainActivity
import com.zahah.tunnelview.storage.CredentialsStore
import com.zahah.tunnelview.SettingsActivity
import com.zahah.tunnelview.Prefs
import com.zahah.tunnelview.R
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class ExternalApprovalFlowTest {
    @Test fun approvalReturnPreservesPageAndDoesNotRequestXmlFallback() = runApproval(false)

    @Test fun httpCallbackRedirectCannotHideAnXmlFallback() = runApproval(true)

    @Test fun authenticatedHttpCallbackRedirectPreservesPage() = runApproval(true, true)

    private fun runApproval(httpMode: Boolean, authenticated: Boolean = false) {
        val origin = MockWebServer()
        val denied = MockWebServer()
        origin.start()
        denied.start()
        try {
            denied.enqueue(MockResponse().setResponseCode(403).setHeader("Content-Type", "application/xml")
                .setBody("<Error><Code>AccessDenied</Code><Message>Access Denied</Message></Error>"))
            val fallback = if (httpMode) origin.url("/after-approval") else denied.url("/denied")
            val html = """
                <html><body><button id="connect" onclick="
                window.location.href='externaltest://approve';
                setTimeout(function(){window.location.href='${fallback}';},1400);
                ">Connect</button><script>window.pageIdentity='live-'+Math.random();</script></body></html>
            """.trimIndent()
            origin.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest) =
                    if (authenticated && request.getHeader("X-Test-Access") != "dummy-access") {
                        MockResponse().setResponseCode(401)
                    } else if (request.path == "/after-approval") {
                        MockResponse().setResponseCode(302).setHeader("Location", denied.url("/denied"))
                    } else MockResponse().setHeader("Content-Type", "text/html").setBody(html)
            }
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            runBlocking {
                val store = CredentialsStore.getInstance(context)
                store.setHttpHeaderName(if (authenticated) "X-Test-Access" else null)
                store.setHttpHeaderValue(if (authenticated) "dummy-access" else null)
            }
            context.getSharedPreferences("prefs", Context.MODE_PRIVATE).edit().clear()
                .putBoolean("directConnectionEnabled", !httpMode)
                .putBoolean("httpConnectionEnabled", httpMode)
                .putString("httpAddress", origin.url("/").toString())
                .putString("localIpEndpoint", origin.url("/").toString()).commit()
            // Verify fresh installations need no manual opt-in.
            assertTrue(Prefs(context).allowExternalApps)
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                var identity = "null"
                for (i in 0..50) {
                    identity = evaluate(scenario, "window.pageIdentity || null")
                    if (identity != "null") break
                    SystemClock.sleep(100)
                }
                assertNotEquals("Page did not load", "null", identity)
                evaluate(scenario, "document.getElementById('connect').click(); true")
                onView(withText(R.string.external_app_open)).perform(click())
                SystemClock.sleep(3500)
                assertEquals(identity, evaluate(scenario, "window.pageIdentity || null"))
                assertEquals("true", evaluate(scenario,
                    "window.location.origin === '${origin.url("/").toString().trimEnd('/')}'"))
                assertNull("Fallback XML endpoint must never replace the dapp",
                    denied.takeRequest(300, TimeUnit.MILLISECONDS))
            }
        } finally {
            origin.shutdown()
            denied.shutdown()
        }
    }

    @Test fun permissionIsGenericOnlyInPreferencesAndRespectsExplicitDisable() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        context.getSharedPreferences("prefs", Context.MODE_PRIVATE).edit().clear().commit()
        assertTrue(Prefs(context).allowExternalApps)
        ActivityScenario.launch(SettingsActivity::class.java).use { scenario ->
            instrumentation.uiAutomation.waitForIdle(300, 5000)
            var label = ""
            var preferencesTitle = ""
            scenario.onActivity {
                label = it.getString(R.string.settings_external_apps_label)
                preferencesTitle = it.getString(R.string.settings_section_preferences_title)
            }
            var root = instrumentation.uiAutomation.rootInActiveWindow
            assertTrue(nodesWithText(root, label).isEmpty())
            var candidates = nodesWithText(root, preferencesTitle)
            for (i in 0..40) {
                if (candidates.isNotEmpty()) break
                SystemClock.sleep(100)
                root = instrumentation.uiAutomation.rootInActiveWindow
                candidates = nodesWithText(root, preferencesTitle)
            }
            fun describe(node: AccessibilityNodeInfo): String = buildString {
                if (!node.text.isNullOrBlank()) append(node.text).append(" | ")
                for (i in 0 until node.childCount) node.getChild(i)?.let { append(describe(it)) }
            }
            assertTrue("Preferences navigation card did not become visible: ${describe(root)}", candidates.isNotEmpty())
            assertTrue(nodesWithText(root, label).isEmpty())
            val preferences = candidates.first()
            var clickable: AccessibilityNodeInfo? = preferences
            while (clickable != null && !clickable.isClickable) clickable = clickable.parent
            assertNotNull(clickable)
            assertTrue(clickable!!.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            instrumentation.uiAutomation.waitForIdle(300, 5000)
            root = instrumentation.uiAutomation.rootInActiveWindow
            assertTrue(nodesWithText(root, label).isNotEmpty())
            fun firstCheckable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
                if (node.isCheckable) return node
                for (i in 0 until node.childCount) {
                    node.getChild(i)?.let { child ->
                        firstCheckable(child)?.let { return it }
                    }
                }
                return null
            }
            val toggle = firstCheckable(root)!!
            assertTrue(toggle.isChecked)
            assertTrue(toggle.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            instrumentation.uiAutomation.waitForIdle(300, 5000)
            assertFalse(Prefs(context).allowExternalApps)
            scenario.recreate()
            assertFalse(Prefs(context).allowExternalApps)
        }
    }

    private fun nodesWithText(root: AccessibilityNodeInfo, text: String): List<AccessibilityNodeInfo> {
        val found = mutableListOf<AccessibilityNodeInfo>()
        if (root.text?.toString() == text) found += root
        for (i in 0 until root.childCount) root.getChild(i)?.let { found += nodesWithText(it, text) }
        return found
    }

    private fun evaluate(scenario: ActivityScenario<MainActivity>, script: String): String {
        val latch = CountDownLatch(1)
        var result = "null"
        scenario.onActivity { activity ->
            val view = activity.findViewById<WebView>(R.id.webview)
            view.evaluateJavascript(script) { result = it; latch.countDown() }
        }
        assertTrue("JavaScript callback timed out", latch.await(5, TimeUnit.SECONDS))
        return result
    }
}
