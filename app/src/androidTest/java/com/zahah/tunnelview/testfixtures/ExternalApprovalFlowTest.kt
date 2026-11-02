package com.zahah.tunnelview.testfixtures

import kotlinx.coroutines.runBlocking
import android.content.Context
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import android.os.SystemClock
import android.webkit.WebView
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.RootMatchers.isDialog
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
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class ExternalApprovalFlowTest {
    @Test fun approvalReturnPreservesPageAndDoesNotRequestXmlFallback() = runApproval(false)

    @Test fun httpCallbackRedirectCannotHideAnXmlFallback() = runApproval(true)

    @Test fun authenticatedHttpCallbackRedirectPreservesPage() = runApproval(true, true)

    @Test fun signatureAppLinkWithoutPairingUriReopensNativeApp() = runApproval(true, true, true)

    @Test fun asyncSignatureAppLinkReopensNativeApp() = runApproval(true, true, true, true)

    private fun runApproval(httpMode: Boolean, authenticated: Boolean = false,
        signAgain: Boolean = false, asyncSign: Boolean = false) {
        val origin = MockWebServer()
        val denied = MockWebServer()
        origin.start()
        denied.start()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val approvals = AtomicInteger()
        val approvalReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) { approvals.incrementAndGet() }
        }
        ContextCompat.registerReceiver(context, approvalReceiver,
            IntentFilter("com.zahah.tunnelview.TEST_EXTERNAL_APPROVED"), ContextCompat.RECEIVER_EXPORTED)
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
                    if (request.path == "/open-for-signature") {
                        MockResponse().setResponseCode(403).setHeader("Content-Type", "application/xml")
                            .setBody("<Error><Code>AccessDenied</Code><Message>Access Denied</Message></Error>")
                    } else if (authenticated && request.getHeader("X-Test-Access") != "dummy-access") {
                        MockResponse().setResponseCode(401)
                    } else if (request.path == "/after-approval") {
                        MockResponse().setResponseCode(302).setHeader("Location", denied.url("/denied"))
                    } else MockResponse().setHeader("Content-Type", "text/html").setBody(html)
            }
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
                var focused = false
                for (i in 0..50) {
                    scenario.onActivity { focused = it.window.decorView.hasWindowFocus() }
                    if (focused) break
                    SystemClock.sleep(100)
                }
                assertTrue("Main window never gained focus", focused)
                evaluate(scenario, "document.getElementById('connect').click(); true")
                onView(withText(R.string.external_app_open)).inRoot(isDialog()).perform(click())
                SystemClock.sleep(3500)
                assertEquals(identity, evaluate(scenario, "window.pageIdentity || null"))
                assertEquals("true", evaluate(scenario,
                    "window.location.origin === '${origin.url("/").toString().trimEnd('/')}'"))
                assertNull("Fallback XML endpoint must never replace the dapp",
                    denied.takeRequest(300, TimeUnit.MILLISECONDS))
                if (signAgain) {
                    assertEquals("Initial approval did not complete", 1, approvals.get())
                    // Signing wake-up links often have no pairing URI. Use a genuine user tap,
                    // which must not clear protection and load the app-link endpoint as HTML.
                    evaluate(scenario, """
                        document.body.innerHTML='<button id="sign" style="position:fixed;inset:0;width:100%;height:100%">Sign</button>';
                        document.getElementById('sign').onclick=function() {
                            window.location.href='${origin.url("/open-for-signature")}';
                            setTimeout(function(){window.location.href='${denied.url("/after-signing")}';},1400);
                        }; true;
                    """.trimIndent())
                    if (asyncSign) {
                        // Simulate the previous connection protection expiring before signing.
                        scenario.onActivity {
                            val coordinator = MainActivity::class.java.getDeclaredField("coordinator")
                                .apply { isAccessible = true }.get(it)
                            val guard = coordinator.javaClass.getDeclaredField("externalHandoff")
                                .apply { isAccessible = true }.get(coordinator)
                            guard.javaClass.getDeclaredMethod("clear").invoke(guard)
                        }
                        evaluate(scenario, "document.getElementById('sign').click(); true")
                        SystemClock.sleep(1800)
                        assertEquals("Page changed while waiting for external confirmation", identity,
                            evaluate(scenario, "window.pageIdentity || null"))
                        onView(withText(R.string.external_app_open)).inRoot(isDialog()).perform(click())
                    } else onView(withId(R.id.webview)).perform(click())
                    SystemClock.sleep(3500)
                    assertEquals("Signature link did not reopen the native app", 2, approvals.get())
                    assertEquals(identity, evaluate(scenario, "window.pageIdentity || null"))
                    assertNull("Signing fallback must not request an XML page",
                        denied.takeRequest(300, TimeUnit.MILLISECONDS))
                }
            }
        } finally {
            context.unregisterReceiver(approvalReceiver)
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
