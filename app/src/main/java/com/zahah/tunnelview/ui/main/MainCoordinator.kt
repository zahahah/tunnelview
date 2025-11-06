package com.zahah.tunnelview.ui.main

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.Bitmap
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebBackForwardList
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleCoroutineScope
import androidx.lifecycle.lifecycleScope
import com.zahah.tunnelview.Actions
import com.zahah.tunnelview.Defaults
import com.zahah.tunnelview.Events
import com.zahah.tunnelview.Keys
import com.zahah.tunnelview.NtfySseService
import com.zahah.tunnelview.Prefs
import com.zahah.tunnelview.R
import com.zahah.tunnelview.SettingsActivity
import com.zahah.tunnelview.TunnelService
import com.zahah.tunnelview.shortSnack
import com.zahah.tunnelview.data.ProxyEndpointSource
import com.zahah.tunnelview.data.ProxyRepository
import com.zahah.tunnelview.data.ProxyStatus
import com.zahah.tunnelview.storage.CredentialsStore
import com.zahah.tunnelview.ui.debug.ConnectionDiagnosticsActivity
import com.zahah.tunnelview.logging.ConnEvent
import com.zahah.tunnelview.logging.ConnLogger
import com.zahah.tunnelview.ssh.TunnelManager
import com.zahah.tunnelview.network.HttpClient
import com.zahah.tunnelview.webview.WebViewConfigurator
import com.zahah.tunnelview.work.EndpointSyncWorker
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Cache
import okhttp3.CacheControl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.internal.closeQuietly
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt
import kotlin.math.max
import kotlin.text.Charsets

class MainCoordinator(
    private val activity: AppCompatActivity,
    private val fileChooserLauncher: ActivityResultLauncher<Intent>,
    private val requestNotificationPermission: (String) -> Unit,
    private val tunnelManager: TunnelManager,
) {

    private lateinit var webView: WebView
    private lateinit var progress: View
    private lateinit var toolbar: MaterialToolbar
    private lateinit var appBar: ViewGroup
    private lateinit var rootView: View
    private lateinit var contentContainer: FrameLayout
    private lateinit var prefs: Prefs
    private val proxyRepository: ProxyRepository by lazy { ProxyRepository.get(activity) }
    private val credentialsStore: CredentialsStore by lazy { CredentialsStore.getInstance(activity) }
    private lateinit var tunnelErrorContainer: View
    private lateinit var tunnelErrorMessage: TextView
    private lateinit var tunnelRetryButton: MaterialButton
    private lateinit var tunnelSettingsButton: MaterialButton
    private lateinit var offlineProgress: ProgressBar
    private lateinit var offlineInfoButton: FloatingActionButton

    private var receiverRegistered = false
    private var pendingTunnelAction: String? = null
    private var pendingSseStart: Boolean = false
    private var fallbackWatchersRunning: Boolean = false
    private var httpReconnectJob: Job? = null
    private var toolbarVisible = false
    private var showingCachedSnapshot = false
    private var keepContentVisibleDuringLoad = false
    private var tunnelAttempted = false
    private var directReconnectAttempted = false
    private var tunnelReconnectAttempted = false
    private var httpReconnectAttempted = false
    private var directReconnectFailed = false
    private var tunnelReconnectFailed = false
    private var httpReconnectFailed = false
    private var tunnelForceReconnectPending = false
    private var currentTarget = LoadTarget.DIRECT
    private var lastAnnouncedTarget: LoadTarget? = null
    private var lastTapAt = 0L
    private var lastTapTarget: ToggleTarget? = null
    private var lastHttpProbeAt = 0L
    private var lastDirectProbeAt = 0L
    private val doubleTapTimeout by lazy { ViewConfiguration.getDoubleTapTimeout().toLong() }
    private var statusBarInset = 0
    private var contentBottomPadding = 0
    private var collapsedMargin = 0
    private var contentInsetLeft = 0
    private var contentInsetRight = 0
    private var toolbarPinnedByUser = false
    private val fallbackHandler = Handler(Looper.getMainLooper())
    private var directTimeoutRunnable: Runnable? = null
    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private var connectionJob: Job? = null
    private var monitorJob: Job? = null
    private var tunnelStateJob: Job? = null
    private var endpointJob: Job? = null
    private var proxyStatusJob: Job? = null
    private var lastEndpointSignature: Pair<String, Int>? = null
    private var skipFirstEndpointEmission = true
    private val connectionRetryDelayMs = 3_000L
    private val monitorIntervalMs = 5_000L
    private val httpProbeIntervalWhileOnTunnelMs = TimeUnit.SECONDS.toMillis(10)
    private val directProbeIntervalMs = TimeUnit.SECONDS.toMillis(10)
    private var shouldSnapshotCurrentPage = false
    private var pendingSnapshot = false
    private var lastSnapshotUrl: String? = null
    private var lastSnapshotHash: Int = 0
    private val offlineCacheDir by lazy { File(filesDir, OFFLINE_CACHE_DIR) }
    private val offlineBridge = OfflineBridge()
    private var loadingCachedHtml = false
    private var snapshotRetryTarget = LoadTarget.DIRECT
    private var offlineAssistEligibleAt: Long? = null
    private var offlineAssistRunnable: Runnable? = null
    private val offlineAssistDelayMs = 7_000L
    private var connectivityManager: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var legacyConnectivityRegistered = false
    private var lastKnownNetworkAvailable: Boolean? = null
    private var resumeConnectionOnForeground = false
    private var resumeConnectionForce = false
    private var resumeMonitorOnForeground = false
    private var isForeground = false
    private var keepWebViewVisibleDuringLoading = false
    private var pendingTunnelNavigation = false
    private val currentTunnelState: TunnelManager.State
        get() = tunnelManager.state.value
    private val httpPreferenceKeys = setOf(
        "httpConnectionEnabled",
        "httpAddress"
    )
    private val prefsChangeListener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key != null && httpPreferenceKeys.contains(key)) {
                onHttpPreferencesChanged()
            }
        }
    private val credentialsChangeListener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == CredentialsStore.KEY_HTTP_HEADER_NAME || key == CredentialsStore.KEY_HTTP_HEADER_VALUE) {
                onHttpPreferencesChanged()
            }
        }

    private enum class LoadTarget { DIRECT, HTTP, TUNNEL }
    private enum class ToggleTarget { SHOW, HIDE }
    private data class EdgeInsetsInfo(
        val top: Int,
        val left: Int,
        val right: Int,
        val navigationBottom: Int,
        val imeBottom: Int
    )

    private fun logConnection(level: Int, tag: String, lazyMessage: () -> String) {
        if (!::prefs.isInitialized || !prefs.connectionDebugLoggingEnabled) return
        when (level) {
            android.util.Log.DEBUG -> android.util.Log.d(tag, lazyMessage())
            android.util.Log.INFO -> android.util.Log.i(tag, lazyMessage())
            android.util.Log.WARN -> android.util.Log.w(tag, lazyMessage())
            android.util.Log.ERROR -> android.util.Log.e(tag, lazyMessage())
            else -> android.util.Log.v(tag, lazyMessage())
        }
    }

    private val rootTapListener = View.OnTouchListener { view, event ->
        if (event.actionMasked != MotionEvent.ACTION_DOWN) return@OnTouchListener false
        val now = SystemClock.elapsedRealtime()
        val hideTap = toolbarVisible && (view === appBar || view === toolbar)
        val showTap = !toolbarVisible && shouldAllowGlobalRevealTap() && isRevealSurface(view)
        val target = when {
            hideTap -> ToggleTarget.HIDE
            showTap -> ToggleTarget.SHOW
            else -> null
        }
        if (target != null && handleToggleTap(target, now)) {
            return@OnTouchListener true
        }
        lastTapAt = now
        lastTapTarget = target
        false
    }

    private fun shouldAllowGlobalRevealTap(): Boolean =
        progress.isVisible || tunnelErrorContainer.isVisible || showingCachedSnapshot

    private fun isRevealSurface(view: View): Boolean =
        view === rootView ||
            view === contentContainer ||
            view === progress ||
            view === tunnelErrorContainer ||
            (view === webView && showingCachedSnapshot)
    private val sharedHttpClient: OkHttpClient by lazy {
        HttpClient.shared(activity.applicationContext)
    }
    private val http: OkHttpClient by lazy {
        val cacheDirectory = File(cacheDir, "webview-http-cache").apply { mkdirs() }
        val cacheSize = 50L * 1024L * 1024L
        sharedHttpClient.newBuilder()
            .connectTimeout(7, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .cache(Cache(cacheDirectory, cacheSize))
            .build()
    }

    private val healthCheckClient: OkHttpClient by lazy {
        sharedHttpClient.newBuilder()
            .cache(null)
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .callTimeout(7, TimeUnit.SECONDS)
            .build()
    }

    private val connLogger by lazy { ConnLogger.getInstance(activity.applicationContext) }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != Events.BROADCAST) return
            val msg = intent.getStringExtra(Events.MESSAGE) ?: return
            when {
                msg.startsWith("CONNECTED") -> {
                    tunnelForceReconnectPending = false
                    val shouldRestart =
                        pendingTunnelNavigation ||
                            currentTarget == LoadTarget.TUNNEL ||
                            tunnelAttempted ||
                            webView.url.isNullOrEmpty() ||
                            showingCachedSnapshot
                    if (shouldRestart) {
                        pendingTunnelNavigation = false
                        webView.postDelayed({ startPrimaryNavigation(force = true) }, 250L)
                    } else {
                        logConnection(android.util.Log.DEBUG, "WEBNET") {
                            "Ignorando CONNECTED; targetAtual=$currentTarget tunnelTentado=$tunnelAttempted"
                        }
                    }
                }
                msg.startsWith("WAITING_NETWORK") -> {
                    showFriendlyError(activity.getString(R.string.tunnel_waiting_network))
                }
                msg.startsWith("WAITING_ENDPOINT") -> {
                    showFriendlyError(activity.getString(R.string.waiting_dynamic_endpoint))
                }
                msg.startsWith("ERROR") -> {
                    Toast.makeText(activity, msg, Toast.LENGTH_LONG).show()
                    showFriendlyError(msg)
                    tunnelForceReconnectPending = true
                }
            }
        }
    }
    private val legacyConnectivityReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val connected = isNetworkConnected()
            handleNetworkAvailabilityChange(connected)
        }
    }

    fun onCreate(savedInstanceState: Bundle?) {
        prefs = Prefs(activity)
        prefs.registerListener(prefsChangeListener)
        credentialsStore.registerChangeListener(credentialsChangeListener)
        bindViews()
        setupInsets()
        configureWebView()
        setupToolbar()
        setupContent(savedInstanceState)
        registerStatusReceiver()
        observeTunnelState()
        observeEndpointUpdates()
        observeProxyStatus()
        registerNetworkObserver()
        WebView.setWebContentsDebuggingEnabled(true)
    }

    fun onStart() {
        isForeground = true
        val shouldStartTunnel = shouldStartTunnelService()
        if (shouldStartTunnel) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ensurePostNotificationsThenStart()
            } else {
                startTunnelServiceSafely()
            }
        }
        val queuedAction = pendingTunnelAction
        pendingTunnelAction = null
        val shouldRunFallbackWatchers = !isHttpConfigured()
        if (shouldRunFallbackWatchers) {
            startFallbackWatchers(forceImmediate = true)
        } else {
            stopFallbackWatchers()
        }
        if (shouldStartTunnel && queuedAction != null) {
            startTunnelServiceSafely(queuedAction)
        }
        webView.postDelayed({
            if (showingCachedSnapshot) {
                startPrimaryNavigation(force = true)
            } else if (webView.url.isNullOrEmpty()) {
                startPrimaryNavigation(force = true)
            } else {
                showContent()
            }
        }, 400L)
        resumeConnectionFlow()
    }

    fun onResume() {
        isForeground = true
        resumeConnectionFlow()
    }

    fun onStop() {
        isForeground = false
        pauseConnectionFlow()
        stopFallbackWatchers()
        EndpointSyncWorker.cancelPeriodic(activity.applicationContext)
    }

    fun onDestroy() {
        if (receiverRegistered) {
            activity.unregisterReceiver(receiver)
            receiverRegistered = false
        }
        if (::prefs.isInitialized) {
            prefs.unregisterListener(prefsChangeListener)
        }
        credentialsStore.unregisterChangeListener(credentialsChangeListener)
        unregisterNetworkObserver()
        tunnelStateJob?.cancel()
        filePathCallback?.onReceiveValue(null)
        filePathCallback = null
        connectionJob?.cancel()
        monitorJob?.cancel()
        endpointJob?.cancel()
        endpointJob = null
        proxyStatusJob?.cancel()
        proxyStatusJob = null
        resetReconnectState()
        pendingSnapshot = false
        lastSnapshotUrl = null
        lastSnapshotHash = 0
        cancelDirectFallback()
        stopFallbackWatchers()
    }

    @Suppress("UNUSED_PARAMETER")
    fun onConfigurationChanged(newConfig: Configuration) {
        rootView.requestApplyInsets()
        webView.requestLayout()
    }

    fun onSaveInstanceState(outState: Bundle) {
        webView.saveState(outState)
        outState.putBoolean(KEY_TOOLBAR_PINNED, toolbarPinnedByUser)
        outState.putBoolean(KEY_TOOLBAR_VISIBLE, toolbarVisible)
    }

    fun handleBackPressed(): Boolean {
        val history = webView.copyBackForwardList()
        val currentIndex = history.currentIndex
        val previousMeaningful = findPreviousMeaningfulHistoryIndex(history, currentIndex - 1)
        if (previousMeaningful >= 0) {
            val previousUrl = runCatching { history.getItemAtIndex(previousMeaningful)?.url }
                .getOrNull()
                ?.takeUnless { it.isNullOrBlank() }
            if (previousUrl != null) {
                val currentBase = baseUrlFor(currentTarget)
                val previousBase = baseFrom(previousUrl)
                if (!urlsEquivalent(previousBase, currentBase)) {
                    remapUrlToBase(previousUrl, currentBase)?.let { remapped ->
                        loadUrlForTarget(remapped, currentTarget)
                        return true
                    }
                }
            }
            val steps = previousMeaningful - currentIndex
            if (steps != 0 && webView.canGoBackOrForward(steps)) {
                webView.goBackOrForward(steps)
                return true
            }
            if (webView.canGoBack()) {
                webView.goBack()
                return true
            }
        }

        val homeUrl = baseUrlFor(currentTarget)
        val currentUrl = webView.url
        if (currentUrl != null && urlsEquivalent(currentUrl, homeUrl)) {
            webView.reload()
            return true
        }
        if (currentTarget != LoadTarget.TUNNEL) {
            tunnelAttempted = false
            scheduleDirectFallback()
        } else {
            tunnelAttempted = true
            cancelDirectFallback()
        }
        keepContentVisibleDuringLoad = false
        showLoading()
        loadUrlForTarget(homeUrl, currentTarget)
        return true
    }

    private fun findPreviousMeaningfulHistoryIndex(
        history: WebBackForwardList,
        startIndex: Int
    ): Int {
        val size = history.size
        if (size == 0) return -1
        var index = startIndex.coerceAtMost(size - 1)
        while (index >= 0) {
            val url = runCatching { history.getItemAtIndex(index)?.url }.getOrNull()
            if (!isSnapshotHistoryUrl(url)) {
                return index
            }
            index--
        }
        return -1
    }

    private fun isSnapshotHistoryUrl(url: String?): Boolean {
        if (url.isNullOrBlank()) return true
        val normalized = url.lowercase()
        if (normalized == "about:blank") return true
        return normalized.startsWith("file://") ||
            normalized.startsWith("data:") ||
            normalized.startsWith("chrome-error://")
    }

    fun onNotificationPermissionResult(granted: Boolean) {
        if (granted) {
            if (shouldStartTunnelService()) {
                startTunnelServiceSafely()
            }
        } else {
            Toast.makeText(
                activity,
                activity.getString(R.string.post_notifications_denied),
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun onHttpPreferencesChanged() {
        val httpConfigured = isHttpConfigured()
        val keepVisible = keepContentVisibleDuringLoad || webView.isVisible
        activity.runOnUiThread {
            if (!httpConfigured) {
                startFallbackWatchers(forceImmediate = true)
                tunnelForceReconnectPending = true
                requestTunnelReconnect(force = false)
            } else {
                stopFallbackWatchers()
            }
            startConnectionLoop(force = true, keepWebVisible = keepVisible)
        }
    }

    fun onFileChooserResult(result: ActivityResult) {
        val callback = filePathCallback ?: return
        val uris = mutableListOf<Uri>()
        if (result.resultCode == Activity.RESULT_OK) {
            val intent = result.data
            val clipData = intent?.clipData
            when {
                clipData != null && clipData.itemCount > 0 -> {
                    for (i in 0 until clipData.itemCount) {
                        clipData.getItemAt(i)?.uri?.let { uris += it }
                    }
                }
                intent?.data != null -> uris += intent.data!!
            }
        }
        if (uris.isNotEmpty()) {
            callback.onReceiveValue(uris.toTypedArray())
        } else {
            callback.onReceiveValue(null)
        }
        filePathCallback = null
    }

    private fun bindViews() {
        appBar = activity.findViewById(R.id.appbar)
        toolbar = activity.findViewById(R.id.toolbar)
        rootView = activity.findViewById(R.id.root)
        contentContainer = activity.findViewById(R.id.content_container)
        webView = activity.findViewById(R.id.webview)
        progress = activity.findViewById(R.id.progress)
        tunnelErrorContainer = activity.findViewById(R.id.tunnel_error_container)
        tunnelErrorMessage = activity.findViewById(R.id.tunnel_error_message)
        tunnelRetryButton = activity.findViewById(R.id.tunnel_retry_button)
        tunnelSettingsButton = activity.findViewById(R.id.tunnel_settings_button)
        offlineProgress = activity.findViewById(R.id.offline_progress)
        offlineInfoButton = activity.findViewById(R.id.offline_info_button)
        tunnelErrorContainer.setOnTouchListener(rootTapListener)
        tunnelRetryButton.setOnClickListener {
            requestTunnelReconnect(force = true)
            hideFriendlyError()
            startPrimaryNavigation(force = true)
        }
        tunnelSettingsButton.setOnClickListener {
            activity.startActivity(Intent(activity, SettingsActivity::class.java))
        }
        offlineInfoButton.setOnClickListener { showOfflineDialog() }
    }

    private fun setupInsets() {
        rootView.isClickable = true
        rootView.setOnTouchListener(rootTapListener)
        appBar.isClickable = true
        appBar.setOnTouchListener(rootTapListener)
        toolbar.isClickable = true
        toolbar.setOnTouchListener(rootTapListener)
        contentContainer.setOnTouchListener(rootTapListener)
        webView.setOnTouchListener(rootTapListener)

        ViewCompat.setOnApplyWindowInsetsListener(rootView) { _, insets ->
            val edgeInsets = computeEdgeInsets(insets)
            statusBarInset = edgeInsets.top
            collapsedMargin = edgeInsets.top
            updateRootPadding()
            updateAppBarPadding()
            insets
        }

        ViewCompat.setOnApplyWindowInsetsListener(contentContainer) { _, insets ->
            val edgeInsets = computeEdgeInsets(insets)
            applyBottomSpacing(edgeInsets)
            insets
        }

        ViewCompat.requestApplyInsets(appBar)
        ViewCompat.requestApplyInsets(webView)
        ViewCompat.requestApplyInsets(contentContainer)
        ViewCompat.requestApplyInsets(rootView)
    }

    private fun setupToolbar() {
        toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_open_settings -> {
                    activity.startActivity(Intent(activity, SettingsActivity::class.java))
                    true
                }
                R.id.action_open_ntfy -> {
                    activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://ntfy.sh")))
                    true
                }
                R.id.action_open_diagnostics -> {
                    activity.startActivity(Intent(activity, ConnectionDiagnosticsActivity::class.java))
                    true
                }
                else -> false
            }
        }
    }

    private fun setupContent(savedInstanceState: Bundle?) {
        val restoredIndex = savedInstanceState?.let { webView.restoreState(it) }?.currentIndex ?: -1
        if (restoredIndex >= 0) {
            toolbarPinnedByUser = savedInstanceState?.getBoolean(KEY_TOOLBAR_PINNED, false) ?: false
            val visible = savedInstanceState?.getBoolean(KEY_TOOLBAR_VISIBLE, toolbarPinnedByUser) ?: false
            if (toolbarPinnedByUser || visible) {
                showToolbarImmediate()
            } else {
                hideToolbarImmediate(force = true)
            }
            showContent()
        } else {
            toolbarPinnedByUser = false
            hideToolbarImmediate(force = true)
            progress.isVisible = true
            webView.isVisible = false
            if (prefs.cacheLastPage) {
                showCachedPageIfAvailable()
            }
        }
    }

    private fun configureWebView() {
        webView.addJavascriptInterface(offlineBridge, "AndroidOffline")
        progress.isClickable = true
        progress.setOnTouchListener(rootTapListener)
        WebViewConfigurator.applyDefaultSecurity(webView)

        webView.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(cm: ConsoleMessage): Boolean {
                android.util.Log.d(
                    "WebViewConsole",
                    "[${cm.messageLevel()}] ${cm.sourceId()}:${cm.lineNumber()} ${cm.message()}"
                )
                return super.onConsoleMessage(cm)
            }

            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                logConnection(android.util.Log.DEBUG, "WEBPROG") { "Progresso: $newProgress%" }
                super.onProgressChanged(view, newProgress)
            }

            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>,
                fileChooserParams: FileChooserParams
            ): Boolean {
                this@MainCoordinator.filePathCallback?.onReceiveValue(null)
                this@MainCoordinator.filePathCallback = filePathCallback
                val intent = try {
                    fileChooserParams.createIntent().apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                    }
                } catch (e: ActivityNotFoundException) {
                    Toast.makeText(activity, R.string.file_chooser_error, Toast.LENGTH_LONG).show()
                    this@MainCoordinator.filePathCallback = null
                    return false
                }

                return try {
                    fileChooserLauncher.launch(intent)
                    true
                } catch (e: Exception) {
                    Toast.makeText(activity, R.string.file_chooser_error, Toast.LENGTH_LONG).show()
                    this@MainCoordinator.filePathCallback?.onReceiveValue(null)
                    this@MainCoordinator.filePathCallback = null
                    false
                }
            }
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val mapped = toLocalLoopbackIfNeeded(request.url)
                return when {
                    currentTarget == LoadTarget.TUNNEL && mapped != request.url -> {
                        view.loadUrl(mapped.toString())
                        true
                    }
                    currentTarget == LoadTarget.HTTP -> {
                        loadUrlForTarget(request.url.toString(), LoadTarget.HTTP)
                        true
                    }
                    else -> false
                }
            }

            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                val orig = Uri.parse(url)
                val mapped = toLocalLoopbackIfNeeded(orig)
                if (mapped != orig) {
                    view.stopLoading()
                    view.loadUrl(mapped.toString())
                    return
                }
                pendingSnapshot = true
                shouldSnapshotCurrentPage = true
                lastSnapshotUrl = null
                lastSnapshotHash = 0
                if (keepContentVisibleDuringLoad) {
                    progress.isVisible = true
                } else {
                    showLoading()
                }
                logConnection(android.util.Log.DEBUG, "WEBNAV") { "Main start: $url" }
                super.onPageStarted(view, url, favicon)
                injectNetworkShim()
            }

            override fun onPageFinished(view: WebView, url: String) {
                logConnection(android.util.Log.DEBUG, "WEBNAV") { "Main finished: $url" }
                super.onPageFinished(view, url)
                keepContentVisibleDuringLoad = false
                val isSnapshotUrl = url.startsWith("file://") || url.startsWith("data:")
                if (!url.startsWith("chrome-error://")) {
                    if (loadingCachedHtml) {
                        showingCachedSnapshot = true
                        loadingCachedHtml = false
                    } else if (!isSnapshotUrl) {
                        showingCachedSnapshot = false
                        if (prefs.cacheLastPage) {
                            extractRelativePath(url)?.let { prefs.cachedRelativePath = it }
                        }
                    }
                    showContent()
                    tunnelAttempted = false
                    resetReconnectState()
                    hideFriendlyError()
                } else {
                    android.util.Log.w(
                        "WEBNAV",
                        "Main finished with error URL=$url; snapshot=$showingCachedSnapshot"
                    )
                }
                cancelDirectFallback()

                if (prefs.cacheLastPage &&
                    shouldSnapshotCurrentPage &&
                    !showingCachedSnapshot &&
                    !url.startsWith("chrome-error://")
                ) {
                    snapshotPage()
                }
                shouldSnapshotCurrentPage = false
                injectDiagnostics()
                injectNetworkShim()
            }

            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest,
                error: android.webkit.WebResourceError
            ) {
                super.onReceivedError(view, request, error)
                android.util.Log.e(
                    "WEBERR",
                    "Main error ${error.errorCode} ${error.description} @ ${request.url} (main=${request.isForMainFrame})"
                )
                if (request.isForMainFrame) {
                    val code = error.errorCode
                    if (shouldFallbackToTunnel(code)) {
                        tunnelAttempted = true
                        startConnectionLoop(force = true)
                    } else if (prefs.cacheLastPage && showCachedPageIfAvailable()) {
                        keepContentVisibleDuringLoad = true
                        showContent()
                    } else {
                        showFriendlyError(activity.getString(R.string.tunnel_error_generic))
                    }
                    if (currentTarget == LoadTarget.TUNNEL) {
                        EndpointSyncWorker.enqueueImmediate(activity.applicationContext)
                    }
                } else if (prefs.cacheLastPage) {
                    handleCachedArchiveFailure(request.url, error.errorCode)
                }
            }

            override fun onReceivedHttpError(
                view: WebView,
                request: WebResourceRequest,
                errorResponse: WebResourceResponse
            ) {
                super.onReceivedHttpError(view, request, errorResponse)
                if (request.isForMainFrame) {
                    shouldSnapshotCurrentPage = false
                    val status = errorResponse.statusCode
                    android.util.Log.e("WEBERR", "Main HTTP $status ${errorResponse.reasonPhrase}")
                    cancelDirectFallback()
                    if (!shouldShowOfflineForHttp(status)) {
                        return
                    }
                    if (prefs.cacheLastPage) {
                        showCachedPageIfAvailable()
                    }
                    view.postDelayed({ startPrimaryNavigation(force = false) }, 2_000L)
                } else {
                    android.util.Log.e(
                        "WEBERR",
                        "Sub HTTP ${errorResponse.statusCode} ${errorResponse.reasonPhrase} @ ${request.url}"
                    )
                }
            }

            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest
            ): WebResourceResponse? {
                val isMain = request.isForMainFrame
                val method = request.method.uppercase(Locale.US)
                if (method != "GET") return null
                return when (currentTarget) {
                    LoadTarget.TUNNEL -> {
                        if (isMain) {
                            logConnection(android.util.Log.DEBUG, "WEBREQ") {
                                "MAIN $method ${request.url}"
                            }
                            null
                        } else {
                            val orig = request.url
                            val url = toLocalLoopbackIfNeeded(orig).toString()
                            logConnection(android.util.Log.DEBUG, "WEBREQ") { "$method $orig -> $url" }
                            interceptRequestWithClient(
                                url = url,
                                request = request,
                                applyHttpHeaders = false,
                                isMainFrame = isMain
                            )
                        }
                    }
                    LoadTarget.HTTP -> {
                        val url = request.url.toString()
                        if (isMain) {
                            logConnection(android.util.Log.DEBUG, "WEBREQ") { "MAIN HTTP $method $url" }
                        } else if (prefs.connectionDebugLoggingEnabled) {
                            logConnection(android.util.Log.DEBUG, "WEBREQ") { "HTTP $method $url" }
                        }
                        interceptRequestWithClient(
                            url = url,
                            request = request,
                            applyHttpHeaders = true,
                            isMainFrame = isMain
                        )
                    }
                    else -> null
                }
            }
        }
    }

    private fun interceptRequestWithClient(
        url: String,
        request: WebResourceRequest,
        applyHttpHeaders: Boolean,
        isMainFrame: Boolean
    ): WebResourceResponse? {
        val isRemoteHttp = applyHttpHeaders
        val summarizedUrl = summarizeUrlForDiagnostics(url)
        return try {
            val appliedHeaders = if (applyHttpHeaders) {
                httpRequestHeaders()
            } else {
                null
            }
            if (isRemoteHttp) {
                if (isMainFrame) {
                    logHttpEvent(
                        ConnEvent.Level.INFO,
                        "HTTP navigation request → $summarizedUrl"
                    )
                } else if (prefs.connectionDebugLoggingEnabled) {
                    logConnection(android.util.Log.DEBUG, "WEBREQ") {
                        "HTTP asset request → $summarizedUrl"
                    }
                }
            }
            val builder = Request.Builder()
                .url(url)
                .header("Accept", request.requestHeaders["Accept"] ?: "*/*")
                .header("User-Agent", request.requestHeaders["User-Agent"] ?: "WebView")
                .apply {
                    CookieManager.getInstance().getCookie(url)?.let {
                        header("Cookie", it)
                    }
                    request.requestHeaders["Referer"]?.let { header("Referer", it) }
                    request.requestHeaders["Cache-Control"]?.let { header("Cache-Control", it) }
                    request.requestHeaders["If-Modified-Since"]?.let { header("If-Modified-Since", it) }
                    request.requestHeaders["If-None-Match"]?.let { header("If-None-Match", it) }
                    appliedHeaders?.forEach { (name, value) ->
                        header(name, value)
                    }
                }

            val response = http.newCall(builder.build()).execute()
            val body = response.body ?: run {
                if (isRemoteHttp) {
                    logHttpEvent(
                        ConnEvent.Level.WARN,
                        "HTTP asset response without body ← $summarizedUrl",
                        code = response.code
                    )
                }
                response.closeQuietly()
                return null
            }
            val code = response.code
            val mime = response.header("Content-Type")?.substringBefore(";") ?: guessMime(url)
            val enc = response.header("Content-Type")
                ?.substringAfter("charset=", "")
                ?.ifBlank { null }
                ?: "utf-8"
            val sizeHint = body.contentLength()
            val respSource = when {
                response.cacheResponse != null -> "cache"
                response.networkResponse != null -> "net"
                else -> "n/a"
            }

            logConnection(android.util.Log.DEBUG, "WEBRESP") {
                "↩ ${code} ${mime} ${if (sizeHint >= 0) "${sizeHint}B" else "stream"} [$respSource] <- $url"
            }
            if (isRemoteHttp) {
                val eventLevel = if (code in 200..399) ConnEvent.Level.INFO else ConnEvent.Level.WARN
                val baseMessage = if (isMainFrame) {
                    "HTTP navigation response ← $summarizedUrl"
                } else {
                    "HTTP asset response ← $summarizedUrl"
                }
                val shouldLogEvent = isMainFrame || eventLevel != ConnEvent.Level.INFO
                if (shouldLogEvent) {
                    logHttpEvent(eventLevel, baseMessage, code = code)
                }
            }

            val upstream = body.byteStream()
            val proxyStream = object : InputStream() {
                override fun read(): Int = upstream.read()
                override fun read(b: ByteArray): Int = upstream.read(b)
                override fun read(b: ByteArray, off: Int, len: Int): Int = upstream.read(b, off, len)
                override fun available(): Int = upstream.available()
                override fun skip(n: Long): Long = upstream.skip(n)
                override fun close() = upstream.close()
            }

            val reason = response.message.ifBlank { defaultReasonPhrase(code) }
            WebResourceResponse(mime, enc, proxyStream).apply {
                setStatusCodeAndReasonPhrase(code, reason)
                val headers = mutableMapOf<String, String>()
                response.headers.forEach { header ->
                    headers[header.first] = header.second
                }
                responseHeaders = headers
            }
        } catch (t: Throwable) {
            if (isRemoteHttp) {
                val message = if (isMainFrame) {
                    "HTTP navigation failed → $summarizedUrl"
                } else {
                    "HTTP asset request failed → $summarizedUrl"
                }
                logHttpEvent(ConnEvent.Level.ERROR, message, throwable = t)
            }
            android.util.Log.e("WEBREQ", "Erro interceptando $url", t)
            null
        }
    }

    private fun defaultReasonPhrase(code: Int): String = when (code) {
        200 -> "OK"
        201 -> "Created"
        202 -> "Accepted"
        204 -> "No Content"
        301 -> "Moved Permanently"
        302 -> "Found"
        304 -> "Not Modified"
        400 -> "Bad Request"
        401 -> "Unauthorized"
        403 -> "Forbidden"
        404 -> "Not Found"
        500 -> "Internal Server Error"
        502 -> "Bad Gateway"
        503 -> "Service Unavailable"
        else -> "OK"
    }

    private fun summarizeUrlForDiagnostics(raw: String): String {
        return runCatching {
            val uri = Uri.parse(raw)
            val scheme = uri.scheme ?: return raw
            val host = uri.host ?: return raw
            val path = uri.path?.takeIf { it.isNotBlank() } ?: "/"
            val defaultPort = defaultPortForScheme(scheme)
            val port = uri.port
            buildString {
                append(scheme)
                append("://")
                append(host)
                if (port != -1 && port != defaultPort) {
                    append(":").append(port)
                }
                append(path)
            }
        }.getOrDefault(raw)
    }

    private fun defaultPortForScheme(scheme: String?): Int = when (scheme?.lowercase(Locale.US)) {
        "http" -> 80
        "https" -> 443
        else -> -1
    }

    private fun registerStatusReceiver() {
        val filter = IntentFilter(Events.BROADCAST)
        if (Build.VERSION.SDK_INT >= 33) {
            activity.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            activity.registerReceiver(receiver, filter)
        }
        receiverRegistered = true
    }

    private fun observeTunnelState() {
        tunnelStateJob?.cancel()
        tunnelStateJob = lifecycleScope.launch {
            tunnelManager.state.collect { state ->
                when (state) {
                    is TunnelManager.State.Connected -> hideFriendlyError()
                    is TunnelManager.State.Connecting -> hideFriendlyError()
                    is TunnelManager.State.LocalBypass -> hideFriendlyError()
                    is TunnelManager.State.WaitingForEndpoint -> showFriendlyError(
                        activity.getString(R.string.waiting_dynamic_endpoint)
                    )
                    is TunnelManager.State.WaitingForNetwork -> showFriendlyError(
                        activity.getString(R.string.tunnel_waiting_network)
                    )
                    is TunnelManager.State.Failed -> showFriendlyError(state.message)
                    else -> Unit
                }
            }
        }
    }

    private fun observeEndpointUpdates() {
        endpointJob?.cancel()
        endpointJob = lifecycleScope.launch {
            proxyRepository.endpointFlow.collect { endpoint ->
                val signature = endpoint?.let { it.host to it.port }
                if (skipFirstEndpointEmission) {
                    skipFirstEndpointEmission = false
                    lastEndpointSignature = signature
                    return@collect
                }
                if (signature == null || signature == lastEndpointSignature) return@collect
                lastEndpointSignature = signature
                if (endpoint.source == ProxyEndpointSource.MANUAL || endpoint.source == ProxyEndpointSource.DEFAULT) return@collect
                logConnection(android.util.Log.INFO, "WEBENDPOINT") {
                    "Endpoint atualizado (${endpoint.source}) -> ${endpoint.host}:${endpoint.port}"
                }
                if (currentTunnelState !is TunnelManager.State.Connecting) {
                    tunnelManager.forceReconnect("endpoint_update")
                }
                val messageRes = when (endpoint.source) {
                    ProxyEndpointSource.FALLBACK -> R.string.fallback_endpoint_applied
                    ProxyEndpointSource.NTFY -> R.string.ntfy_endpoint_applied
                    else -> R.string.ntfy_endpoint_applied
                }
                rootView.post {
                    rootView.shortSnack(
                        activity.getString(
                            messageRes,
                            endpoint.host,
                            endpoint.port
                        )
                    )
                }
            }
        }
    }

    private fun observeProxyStatus() {
        proxyStatusJob?.cancel()
        proxyStatusJob = lifecycleScope.launch {
            proxyRepository.statusFlow.collect { status ->
                when (status) {
                    is ProxyStatus.Error -> rootView.post {
                        rootView.shortSnack(status.message)
                    }

                    is ProxyStatus.AuthFailure -> rootView.post {
                        rootView.shortSnack(status.message)
                    }

                    is ProxyStatus.EndpointApplied -> Unit
                }
            }
        }
    }

    private fun registerNetworkObserver() {
        connectivityManager =
            activity.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        lastKnownNetworkAvailable = isNetworkConnected()
        logConnection(
            android.util.Log.INFO,
            "WEBNET"
        ) { "Registrando observador de rede; estado inicial=${lastKnownNetworkAvailable}" }
        val manager = connectivityManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    logConnection(android.util.Log.INFO, "WEBNET") {
                        "Callback onAvailable() -> avaliando rede"
                    }
                    handleNetworkAvailabilityChange(true)
                }

                override fun onLost(network: Network) {
                    logConnection(android.util.Log.WARN, "WEBNET") {
                        "Callback onLost() -> verificando conectividade"
                    }
                    handleNetworkAvailabilityChange(isNetworkConnected())
                }
            }
            try {
                manager.registerDefaultNetworkCallback(callback)
                networkCallback = callback
                logConnection(android.util.Log.INFO, "WEBNET") {
                    "Callback padrão registrado com sucesso"
                }
            } catch (t: Throwable) {
                logConnection(android.util.Log.WARN, "WEBNET") {
                    "Falha ao registrar network callback: ${t.message}"
                }
            }
        } else {
            try {
                @Suppress("DEPRECATION")
                activity.registerReceiver(
                    legacyConnectivityReceiver,
                    IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION)
                )
                legacyConnectivityRegistered = true
                logConnection(android.util.Log.INFO, "WEBNET") {
                    "Receiver legado de conectividade registrado"
                }
            } catch (t: Throwable) {
                logConnection(android.util.Log.WARN, "WEBNET") {
                    "Falha ao registrar receiver legado: ${t.message}"
                }
            }
        }
    }

    private fun unregisterNetworkObserver() {
        val manager = connectivityManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            networkCallback?.let { callback ->
                try {
                    manager?.unregisterNetworkCallback(callback)
                } catch (_: Throwable) {
                }
            }
            networkCallback = null
        }
        if (legacyConnectivityRegistered) {
            try {
                activity.unregisterReceiver(legacyConnectivityReceiver)
            } catch (_: Throwable) {
            }
            legacyConnectivityRegistered = false
        }
    }

    private fun handleNetworkAvailabilityChange(isAvailable: Boolean) {
        val previous = lastKnownNetworkAvailable
        lastKnownNetworkAvailable = isAvailable
        logConnection(android.util.Log.INFO, "WEBNET") {
            "Disponibilidade de rede alterada de=$previous para=$isAvailable " +
                "showingSnapshot=$showingCachedSnapshot currentTarget=$currentTarget"
        }
        if (!isAvailable) {
            logConnection(android.util.Log.WARN, "WEBNET") {
                "Rede indisponível; marcando túnel para reconexão forçada"
            }
            tunnelForceReconnectPending = true
            return
        }
        if (isAvailable && previous == false) {
            tunnelForceReconnectPending = true
            activity.runOnUiThread {
                keepContentVisibleDuringLoad = showingCachedSnapshot || webView.isVisible
                connectionJob?.cancel()
                logConnection(android.util.Log.INFO, "WEBNET") {
                    "Rede restabelecida; iniciando processo de reconexão automática"
                }
                startPrimaryNavigation(force = true)
            }
        }
    }

    private fun ensurePostNotificationsThenStart() {
        if (!prefs.persistentNotificationEnabled) {
            startTunnelServiceSafely()
            return
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            startTunnelServiceSafely()
            return
        }
        val permission = Manifest.permission.POST_NOTIFICATIONS
        when {
            activity.shouldShowRequestPermissionRationale(permission) ->
                requestNotificationPermission(permission)
            activity.checkSelfPermission(permission) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED ->
                startTunnelServiceSafely()
            else -> requestNotificationPermission(permission)
        }
    }

    private fun startTunnelServiceSafely(action: String = Actions.START) {
        if (!activity.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            pendingTunnelAction = action
            return
        }
        val appContext = activity.applicationContext
        val startIntent = Intent(appContext, TunnelService::class.java).apply { this.action = action }
        val useForeground = action == Actions.START || !TunnelService.isRunning()
        try {
            if (useForeground) {
                ContextCompat.startForegroundService(appContext, startIntent)
            } else {
                appContext.startService(startIntent)
            }
            pendingTunnelAction = null
        } catch (e: IllegalStateException) {
            android.util.Log.w("WEBNAV", "Unable to start service, falling back: ${e.message}")
            try {
                appContext.startService(startIntent)
                pendingTunnelAction = null
            } catch (inner: IllegalStateException) {
                android.util.Log.e("WEBNAV", "Failed to start tunnel service: ${inner.message}")
                pendingTunnelAction = action
            }
        }
    }

    private fun stopTunnelService() {
        val appContext = activity.applicationContext
        if (!TunnelService.isRunning()) {
            pendingTunnelAction = null
            return
        }
        val stopIntent = Intent(appContext, TunnelService::class.java).apply { action = Actions.STOP }
        runCatching {
            appContext.startService(stopIntent)
        }.onFailure {
            android.util.Log.w("WEBNAV", "Unable to stop tunnel service: ${it.message}")
        }
        pendingTunnelAction = null
    }

    private fun startNtfyUpdates() {
        if (!::prefs.isInitialized) return
        if (prefs.ntfyFetchUserOverride == false || !prefs.ntfyFetchEnabled) {
            stopNtfyUpdates()
            return
        }
        val topic = credentialsStore.ntfyTopic()?.trim().orEmpty()
        if (topic.isEmpty()) {
            stopNtfyUpdates()
            return
        }
        val appContext = activity.applicationContext
        val intent = Intent(appContext, NtfySseService::class.java).apply {
            action = Actions.START_SSE
            putExtra(Keys.ENDPOINT, topic)
        }
        try {
            ContextCompat.startForegroundService(appContext, intent)
            pendingSseStart = false
        } catch (e: IllegalStateException) {
            android.util.Log.w("WEBENDPOINT", "Falha ao iniciar SSE: ${e.message}")
            pendingSseStart = true
        }
    }

    private fun stopNtfyUpdates() {
        val intent = Intent(activity, NtfySseService::class.java).apply {
            action = Actions.STOP_SSE
        }
        try {
            activity.startService(intent)
        } catch (_: IllegalStateException) {
        }
    }

    private fun startFallbackWatchers(forceImmediate: Boolean) {
        if (fallbackWatchersRunning) return
        val appContext = activity.applicationContext
        EndpointSyncWorker.schedulePeriodic(appContext)
        if (forceImmediate) {
            EndpointSyncWorker.enqueueImmediate(appContext)
        }
        startNtfyUpdates()
        fallbackWatchersRunning = true
    }

    private fun stopFallbackWatchers() {
        if (!fallbackWatchersRunning) {
            stopNtfyUpdates()
            pendingSseStart = false
            EndpointSyncWorker.cancelPeriodic(activity.applicationContext)
            return
        }
        stopNtfyUpdates()
        pendingSseStart = false
        EndpointSyncWorker.cancelPeriodic(activity.applicationContext)
        fallbackWatchersRunning = false
    }

    private fun ensureFallbackWatchersRunning(forceImmediate: Boolean) {
        if (!fallbackWatchersRunning) {
            startFallbackWatchers(forceImmediate)
        }
    }

    private fun requestTunnelReconnect(force: Boolean = false) {
        val state = currentTunnelState
        val shouldForce = force || tunnelForceReconnectPending
        if (!shouldForce && (state is TunnelManager.State.Connected || state is TunnelManager.State.Connecting)) {
            logConnection(android.util.Log.DEBUG, "WEBNET") {
                "Ignorando reconexão do túnel; state=$state force=$force pendingForce=$tunnelForceReconnectPending"
            }
            return
        }
        logConnection(android.util.Log.INFO, "WEBNET") {
            "Solicitando reconexão do túnel (force=$shouldForce, estadoAtual=$state)"
        }
        if (shouldForce) {
            val isConnected = state is TunnelManager.State.Connected
            val tunnelReady = tunnelManager.ready.value
            if (isConnected && tunnelReady) {
                logConnection(android.util.Log.DEBUG, "WEBNET") {
                    "Ignorando force reconnect porque túnel já está ativo (state=$state ready=$tunnelReady)"
                }
                tunnelForceReconnectPending = false
                return
            }
            tunnelForceReconnectPending = false
            tunnelManager.forceReconnect("ui_force")
            startTunnelServiceSafely(Actions.RECONNECT)
        } else {
            startTunnelServiceSafely(Actions.START)
        }
    }

    private fun startPrimaryNavigation(force: Boolean) {
        if (shouldStartTunnelService()) {
            startTunnelServiceSafely()
        }
        tunnelAttempted = false
        resetReconnectState()
        startConnectionLoop(force)
    }

    private fun resetReconnectState() {
        val hadState =
            directReconnectAttempted || tunnelReconnectAttempted || httpReconnectAttempted ||
                directReconnectFailed || tunnelReconnectFailed || httpReconnectFailed ||
                offlineAssistEligibleAt != null || offlineAssistRunnable != null
        directReconnectAttempted = false
        tunnelReconnectAttempted = false
        httpReconnectAttempted = false
        directReconnectFailed = false
        tunnelReconnectFailed = false
        httpReconnectFailed = false
        pendingTunnelNavigation = false
        offlineAssistEligibleAt = null
        cancelOfflineAssistVisibilityUpdate()
        snapshotRetryTarget = preferredInitialTarget()
        if (hadState) {
            updateOfflineAssistVisibility()
        }
    }

    private fun markReconnectFailure(target: LoadTarget) {
        var changed = false
        when (target) {
            LoadTarget.DIRECT -> {
                if (!directReconnectAttempted) {
                    directReconnectAttempted = true
                    changed = true
                }
                if (!directReconnectFailed) {
                    directReconnectFailed = true
                    changed = true
                }
            }
            LoadTarget.HTTP -> {
                if (!httpReconnectAttempted) {
                    httpReconnectAttempted = true
                    changed = true
                }
                if (!httpReconnectFailed) {
                    httpReconnectFailed = true
                    changed = true
                }
            }
            LoadTarget.TUNNEL -> {
                if (!tunnelReconnectAttempted) {
                    tunnelReconnectAttempted = true
                    changed = true
                }
                if (!tunnelReconnectFailed) {
                    tunnelReconnectFailed = true
                    changed = true
                }
            }
        }
        val directDone = !isDirectConfigured() || (directReconnectAttempted && directReconnectFailed)
        val httpDone = !isHttpConfigured() || (httpReconnectAttempted && httpReconnectFailed)
        val tunnelDone = tunnelReconnectFailed
        if (directDone && httpDone && tunnelDone && offlineAssistEligibleAt == null) {
            offlineAssistEligibleAt = SystemClock.elapsedRealtime() + offlineAssistDelayMs
            scheduleOfflineAssistVisibilityUpdate(offlineAssistDelayMs)
            changed = true
        }
        if (directDone && httpDone && !pendingTunnelNavigation) {
            pendingTunnelNavigation = currentTarget != LoadTarget.TUNNEL
        }
        if (directReconnectFailed && tunnelReconnectFailed && prefs.cacheLastPage) {
            if (!showingCachedSnapshot) {
                if (showCachedPageIfAvailable()) {
                    changed = true
                }
            } else {
                if (!keepContentVisibleDuringLoad) {
                    showContent()
                }
            }
        }
        if (changed) {
            updateOfflineAssistVisibility()
        }
    }

    private fun cancelOfflineAssistVisibilityUpdate() {
        offlineAssistRunnable?.let { fallbackHandler.removeCallbacks(it) }
        offlineAssistRunnable = null
    }

    private fun scheduleOfflineAssistVisibilityUpdate(delayMs: Long) {
        cancelOfflineAssistVisibilityUpdate()
        val runnable = Runnable {
            offlineAssistRunnable = null
            updateOfflineAssistVisibility()
        }
        offlineAssistRunnable = runnable
        fallbackHandler.postDelayed(runnable, delayMs)
    }

    private fun pauseConnectionFlow() {
        if (webView.isVisible) {
            keepWebViewVisibleDuringLoading = true
        }
        if (connectionJob?.isActive == true) {
            resumeConnectionOnForeground = true
            resumeConnectionForce = true
        }
        connectionJob?.cancel()
        connectionJob = null

        if (monitorJob?.isActive == true) {
            resumeMonitorOnForeground = true
        }
        monitorJob?.cancel()
        monitorJob = null
    }

    private fun resumeConnectionFlow() {
        if (!isForeground) return
        when {
            resumeConnectionOnForeground -> {
                val force = resumeConnectionForce
                val keepVisible = keepWebViewVisibleDuringLoading
                resumeConnectionOnForeground = false
                resumeConnectionForce = false
                resumeMonitorOnForeground = false
                keepWebViewVisibleDuringLoading = false
                startConnectionLoop(force = force, keepWebVisible = keepVisible)
            }

            resumeMonitorOnForeground -> {
                resumeMonitorOnForeground = false
                keepWebViewVisibleDuringLoading = false
                startConnectionMonitor()
            }
        }
    }

    private fun startConnectionLoop(force: Boolean, keepWebVisible: Boolean = false) {
        if (!isForeground) {
            resumeConnectionOnForeground = true
            resumeConnectionForce = resumeConnectionForce || force
            keepWebViewVisibleDuringLoading = keepWebViewVisibleDuringLoading || keepWebVisible
            return
        }
        monitorJob?.cancel()
        connectionJob?.cancel()
        if (!showingCachedSnapshot && prefs.cacheLastPage && !isNetworkConnected()) {
            showCachedPageIfAvailable()
        }
        resetReconnectState()
        connectionJob = lifecycleScope.launch {
            logConnection(
                android.util.Log.INFO,
                "WEBCONN"
            ) { "Iniciando ciclo de conexão (force=$force, snapshot=$showingCachedSnapshot)" }
            if (showingCachedSnapshot) {
                progress.isVisible = false
            } else {
                if (keepWebVisible) {
                    progress.isVisible = false
                    if (!webView.isVisible) {
                        webView.isVisible = true
                    }
                } else {
                    showLoading()
                }
            }
            var nextForce = force
            var keepVisibleNext = keepWebVisible || showingCachedSnapshot
            while (isActive) {
                val keepVisibleForAttempt = keepVisibleNext || showingCachedSnapshot
                keepVisibleNext = showingCachedSnapshot
                if (showingCachedSnapshot) {
                    val target = snapshotRetryTarget
                    logConnection(
                        android.util.Log.DEBUG,
                        "WEBCONN"
                    ) { "Tentando recuperar snapshot via $target (force=$nextForce)" }
                    if (target == LoadTarget.TUNNEL) {
                        ensureFallbackWatchersRunning(forceImmediate = true)
                        requestTunnelReconnect()
                        if (!awaitTunnelReady()) {
                            markReconnectFailure(target)
                            delay(connectionRetryDelayMs)
                            continue
                        }
                    }
                    val available = isTargetReachable(target)
                    if (available) {
                        resetReconnectState()
                        snapshotRetryTarget = preferredInitialTarget()
                        recoverFromSnapshot(target)
                        startConnectionMonitor()
                        return@launch
                    }
                    markReconnectFailure(target)
                    if (target != LoadTarget.TUNNEL) {
                        val state = currentTunnelState
                        if (state !is TunnelManager.State.Connected && state !is TunnelManager.State.Connecting) {
                            logConnection(
                                android.util.Log.DEBUG,
                                "WEBCONN"
                            ) { "Snapshot -> alvo $target indisponível; solicitando túnel somente se inativo (state=$state)" }
                            tunnelForceReconnectPending = true
                        }
                    }
                    snapshotRetryTarget = nextRetryTarget(target)
                    delay(connectionRetryDelayMs)
                    continue
                }

                var connectedTarget: LoadTarget? = null
                val order = connectionOrder()
                for (target in order) {
                    when (target) {
                        LoadTarget.DIRECT -> {
                            val directAvailable = isTargetReachable(LoadTarget.DIRECT)
                            logConnection(
                                android.util.Log.DEBUG,
                                "WEBCONN"
                            ) { "Verificação direta -> $directAvailable (force=$nextForce)" }
                            if (directAvailable) {
                                resetReconnectState()
                                loadTarget(LoadTarget.DIRECT, nextForce, keepWebVisible = keepVisibleForAttempt)
                                connectedTarget = LoadTarget.DIRECT
                                break
                            } else {
                                markReconnectFailure(LoadTarget.DIRECT)
                                val state = currentTunnelState
                                if (state !is TunnelManager.State.Connected && state !is TunnelManager.State.Connecting) {
                                    tunnelForceReconnectPending = true
                                }
                            }
                        }
                        LoadTarget.HTTP -> {
                            val httpAvailable = isTargetReachable(LoadTarget.HTTP)
                            logConnection(
                                android.util.Log.DEBUG,
                                "WEBCONN"
                            ) { "Verificação HTTP -> $httpAvailable (force=$nextForce)" }
                            if (httpAvailable) {
                                resetReconnectState()
                                loadTarget(LoadTarget.HTTP, nextForce, keepWebVisible = keepVisibleForAttempt)
                                connectedTarget = LoadTarget.HTTP
                                break
                            } else {
                                markReconnectFailure(LoadTarget.HTTP)
                                val state = currentTunnelState
                                if (state !is TunnelManager.State.Connected && state !is TunnelManager.State.Connecting) {
                                    tunnelForceReconnectPending = true
                                }
                            }
                        }
                        LoadTarget.TUNNEL -> {
                            ensureFallbackWatchersRunning(forceImmediate = true)
                            requestTunnelReconnect()
                            if (!awaitTunnelReady()) {
                                markReconnectFailure(LoadTarget.TUNNEL)
                                break
                            }
                            val tunnelAvailable = isTargetReachable(LoadTarget.TUNNEL)
                            logConnection(
                                android.util.Log.DEBUG,
                                "WEBCONN"
                            ) { "Verificação túnel -> $tunnelAvailable (force=$nextForce)" }
                            if (tunnelAvailable) {
                                tunnelAttempted = true
                                resetReconnectState()
                                loadTarget(LoadTarget.TUNNEL, true, keepWebVisible = keepVisibleForAttempt)
                                connectedTarget = LoadTarget.TUNNEL
                                break
                            } else {
                                markReconnectFailure(LoadTarget.TUNNEL)
                                EndpointSyncWorker.enqueueImmediate(activity.applicationContext)
                            }
                        }
                    }
                }

                if (connectedTarget != null) {
                    if (connectedTarget != LoadTarget.TUNNEL) {
                        stopTunnelService()
                        stopFallbackWatchers()
                    }
                    startConnectionMonitor()
                    return@launch
                }

                delay(connectionRetryDelayMs)
                nextForce = true
                if (!showingCachedSnapshot && currentTunnelState !is TunnelManager.State.Failed) {
                    val message = activity.getString(R.string.tunnel_error_generic)
                    logConnection(
                        android.util.Log.WARN,
                        "WEBCONN"
                    ) { "Falha ao conectar; superfície offline exibida (targetAtual=$currentTarget)" }
                    showFriendlyError(message)
                }
            }
        }
    }

    private fun startConnectionMonitor() {
        if (!isForeground) {
            resumeMonitorOnForeground = true
            return
        }
        monitorJob?.cancel()
        monitorJob = lifecycleScope.launch {
            logConnection(
                android.util.Log.DEBUG,
                "WEBCONN"
            ) { "Monitor de conexão iniciado (targetAtual=$currentTarget)" }
            while (isActive) {
                delay(monitorIntervalMs)
                if (connectionJob?.isActive == true) continue

                val httpConfigured = isHttpConfigured()
                val directConfigured = isDirectConfigured()
                val now = SystemClock.elapsedRealtime()
                if (!directConfigured) {
                    lastDirectProbeAt = 0L
                    if (currentTarget == LoadTarget.DIRECT) {
                        startConnectionLoop(force = true)
                        return@launch
                    }
                } else {
                    val shouldProbeDirect = currentTarget != LoadTarget.DIRECT ||
                        now - lastDirectProbeAt >= directProbeIntervalMs
                    if (shouldProbeDirect) {
                        if (currentTarget == LoadTarget.DIRECT) {
                            lastDirectProbeAt = now
                        }
                        val directAvailable = isTargetReachable(
                            LoadTarget.DIRECT,
                            silent = currentTarget == LoadTarget.DIRECT
                        )
                        if (directAvailable) {
                            if (showingCachedSnapshot) {
                                logConnection(
                                    android.util.Log.DEBUG,
                                    "WEBNAV"
                                ) { "Monitor: direct available while snapshot visible; recovering" }
                                recoverFromSnapshot(LoadTarget.DIRECT)
                                continue
                            }
                            if (currentTarget != LoadTarget.DIRECT) {
                                lastDirectProbeAt = now
                                logConnection(
                                    android.util.Log.DEBUG,
                                    "WEBNAV"
                                ) { "Monitor: switching to direct after health success" }
                                startConnectionLoop(force = true)
                                return@launch
                            }
                            continue
                        } else if (currentTarget == LoadTarget.DIRECT) {
                            startConnectionLoop(force = true)
                            return@launch
                        }
                    }
                }

                if (!httpConfigured) {
                    if (currentTarget == LoadTarget.HTTP) {
                        logConnection(
                            android.util.Log.INFO,
                            "WEBNAV"
                        ) { "HTTP desativado; alternando para alvo prioritário" }
                        startConnectionLoop(force = true)
                        return@launch
                    }
                    lastHttpProbeAt = 0L
                } else {
                    val shouldProbeHttp = currentTarget != LoadTarget.TUNNEL ||
                        now - lastHttpProbeAt >= httpProbeIntervalWhileOnTunnelMs
                    if (shouldProbeHttp) {
                        if (currentTarget == LoadTarget.TUNNEL) {
                            lastHttpProbeAt = now
                        }
                        val httpAvailable = isTargetReachable(LoadTarget.HTTP, silent = true)
                        if (httpAvailable) {
                            if (showingCachedSnapshot) {
                                logConnection(
                                    android.util.Log.DEBUG,
                                    "WEBNAV"
                                ) { "Monitor: HTTP available while snapshot visible; recovering" }
                                recoverFromSnapshot(LoadTarget.HTTP)
                                continue
                            }
                            val shouldSwitchToHttp =
                                currentTarget != LoadTarget.HTTP &&
                                    (currentTarget == LoadTarget.TUNNEL ||
                                        isHigherPriority(LoadTarget.HTTP, currentTarget))
                            if (shouldSwitchToHttp) {
                                logConnection(
                                    android.util.Log.DEBUG,
                                    "WEBNAV"
                                ) { "Monitor: switching to HTTP after health success" }
                                startConnectionLoop(force = true)
                                return@launch
                            }
                            continue
                        } else if (currentTarget == LoadTarget.HTTP) {
                            startConnectionLoop(force = true)
                            return@launch
                        }
                    }
                }

                val tunnelAvailable = isTargetReachable(LoadTarget.TUNNEL, silent = true)
                if (!tunnelAvailable) {
                    EndpointSyncWorker.enqueueImmediate(activity.applicationContext)
                    startConnectionLoop(force = true)
                    return@launch
                }
            }
        }
    }

    private fun isHigherPriority(target: LoadTarget, than: LoadTarget): Boolean {
        if (target == than) return false
        val order = connectionOrder()
        val targetIndex = order.indexOf(target).takeIf { it >= 0 } ?: Int.MAX_VALUE
        val thanIndex = order.indexOf(than).takeIf { it >= 0 } ?: Int.MAX_VALUE
        return targetIndex < thanIndex
    }

    private suspend fun awaitTunnelReady(timeoutMillis: Long = 20_000L): Boolean {
        if (tunnelManager.ready.value) return true
        return withTimeoutOrNull(timeoutMillis) {
            tunnelManager.ready.filter { it }.first()
        } != null
    }

    private suspend fun isTargetReachable(target: LoadTarget, silent: Boolean = false): Boolean {
        if (target == LoadTarget.DIRECT && !isDirectConfigured()) {
            return false
        }
        if (target == LoadTarget.HTTP && !isHttpConfigured()) {
            return false
        }
        val base = baseUrlFor(target)
        val (cookie, userAgent) = withContext(Dispatchers.Main.immediate) {
            val c = try {
                CookieManager.getInstance().getCookie(base)
            } catch (_: Throwable) {
                null
            }
            val ua = try {
                webView.settings.userAgentString
            } catch (_: Throwable) {
                null
            }
            c to (ua ?: "TunnelView/health")
        }
        val extraHeader = if (target == LoadTarget.HTTP) {
            credentialsStore.httpHeaderConfig()
        } else {
            null
        }

        return withContext(Dispatchers.IO) {
            try {
                if (target == LoadTarget.HTTP && !silent) {
                    val headerLabel = extraHeader?.name ?: "none"
                    logHttpEvent(ConnEvent.Level.INFO, "HTTP health check → $base (header=$headerLabel)")
                }
                val request = Request.Builder()
                    .url(base)
                    .cacheControl(CacheControl.Builder().noCache().noStore().build())
                    .header("User-Agent", userAgent)
                    .apply {
                        if (!cookie.isNullOrEmpty()) {
                            header("Cookie", cookie)
                        }
                        extraHeader?.let { header(it.name, it.value) }
                    }
                    .build()
                val response = healthCheckClient.newCall(request).execute()
                val code = response.code
                val success = if (target == LoadTarget.HTTP) {
                    code in 200..399
                } else {
                    code in 100..599
                }
                if (target == LoadTarget.HTTP && !silent) {
                    val eventLevel = if (success) ConnEvent.Level.INFO else ConnEvent.Level.WARN
                    val message = if (success) {
                        "HTTP health check succeeded"
                    } else {
                        "HTTP health check returned unexpected status"
                    }
                    logHttpEvent(eventLevel, message, code = code)
                }
                response.closeQuietly()
                if (target == LoadTarget.DIRECT) {
                    if (success) {
                        tunnelManager.registerLocalDirectSuccess()
                    } else {
                        tunnelManager.registerLocalDirectFailure()
                    }
                }
                if (!silent) {
                    logConnection(android.util.Log.DEBUG, "WEBHEALTH") {
                        "Health $base -> code=$code success=$success"
                    }
                }
                success
            } catch (t: Throwable) {
                if (target == LoadTarget.DIRECT) {
                    tunnelManager.registerLocalDirectFailure()
                }
                if (!silent) {
                    logConnection(android.util.Log.WARN, "WEBHEALTH") {
                        "Health $target failed: ${t.message}"
                    }
                }
                if (target == LoadTarget.HTTP && !silent) {
                    logHttpEvent(
                        ConnEvent.Level.WARN,
                        "HTTP health check failed",
                        throwable = t
                    )
                }
                false
            }
        }
    }

    private fun loadTarget(target: LoadTarget, force: Boolean, keepWebVisible: Boolean) {
        if (target == LoadTarget.DIRECT && !isDirectConfigured()) {
            logConnection(
                android.util.Log.DEBUG,
                "WEBNAV"
            ) { "Conexão direta não configurada; carregando túnel" }
            loadTarget(fallbackTarget(LoadTarget.DIRECT), force, keepWebVisible)
            return
        }
        if (target == LoadTarget.HTTP && !isHttpConfigured()) {
            logConnection(
                android.util.Log.DEBUG,
                "WEBNAV"
            ) { "Conexão HTTP não configurada; carregando fallback" }
            loadTarget(fallbackTarget(LoadTarget.HTTP), force, keepWebVisible)
            return
        }
        currentTarget = target
        updateActiveConnectionIndicator(target)
        pendingTunnelNavigation = false
        if (target != LoadTarget.TUNNEL) {
            tunnelAttempted = false
            scheduleDirectFallback()
        } else {
            tunnelAttempted = true
            cancelDirectFallback()
        }
        val wasShowingSnapshot = showingCachedSnapshot
        keepContentVisibleDuringLoad = when {
            wasShowingSnapshot && prefs.cacheLastPage -> true
            keepWebVisible -> true
            else -> false
        }
        if (keepContentVisibleDuringLoad) {
            progress.isVisible = !keepWebVisible
            if (keepWebVisible && !webView.isVisible) {
                webView.isVisible = true
            }
        } else {
            showLoading(keepWebVisible = keepWebVisible)
        }
        val preferSnapshotUrl = wasShowingSnapshot
        val desiredUrl = desiredUrlFor(target, preferSnapshotUrl)
        val currentUrl = webView.url
        logConnection(android.util.Log.DEBUG, "WEBNAV") {
            "Load target=$target force=$force url=$desiredUrl"
        }
        if (wasShowingSnapshot) {
            showingCachedSnapshot = false
            recoverFromSnapshot(target)
            return
        }
        val alreadyOnDesired =
            !force && currentUrl != null && urlsEquivalent(currentUrl, desiredUrl)
        if (alreadyOnDesired) {
            if (target != LoadTarget.TUNNEL) {
                cancelDirectFallback()
            }
            return
        }
        loadUrlForTarget(desiredUrl, target)
    }

    private fun recoverFromSnapshot(target: LoadTarget = preferredInitialTarget()) {
        val actualTarget = when (target) {
            LoadTarget.DIRECT -> if (isDirectConfigured()) {
                LoadTarget.DIRECT
            } else {
                fallbackTarget(LoadTarget.DIRECT)
            }
            LoadTarget.HTTP -> if (isHttpConfigured()) {
                LoadTarget.HTTP
            } else {
                fallbackTarget(LoadTarget.HTTP)
            }
            LoadTarget.TUNNEL -> LoadTarget.TUNNEL
        }
        resetReconnectState()
        showingCachedSnapshot = false
        pendingSnapshot = false
        snapshotRetryTarget = preferredInitialTarget()
        currentTarget = actualTarget
        updateActiveConnectionIndicator(actualTarget)
        pendingTunnelNavigation = false
        if (actualTarget != LoadTarget.TUNNEL) {
            tunnelAttempted = false
            scheduleDirectFallback()
        } else {
            tunnelAttempted = true
            cancelDirectFallback()
        }
        val url = desiredUrlFor(actualTarget, preferSnapshot = false)
        logConnection(android.util.Log.DEBUG, "WEBNAV") {
            "recoverFromSnapshot -> loading $url (current=${webView.url})"
        }
        webView.post {
            keepContentVisibleDuringLoad = true
            updateOfflineLoadingVisibility()
            webView.stopLoading()
                webView.loadUrl("about:blank")
                webView.postDelayed({
                    logConnection(android.util.Log.DEBUG, "WEBNAV") {
                        "recoverFromSnapshot -> now loading $url"
                    }
                    loadUrlForTarget(url, actualTarget)
                }, 50L)
        }
        updateOfflineAssistVisibility()
    }

    private fun desiredUrlFor(target: LoadTarget, preferSnapshot: Boolean): String {
        val base = baseUrlFor(target)
        val current = webView.url
        val isSnapshotUrl = current != null && (
            current.startsWith("file://") || current.startsWith("data:")
            )
        val isBlank = current.isNullOrEmpty() || current == "about:blank"
        val isBase = current != null && urlsEquivalent(current, base)
        val hasCachedPath =
            !prefs.cachedRelativePath.isNullOrBlank() || !prefs.cachedFullUrl.isNullOrBlank()
        if (!preferSnapshot) {
            return rebuildCachedUrl(base, prefs.cachedFullUrl, prefs.cachedRelativePath)
        }
        val shouldUseCached = prefs.cacheLastPage && (
            preferSnapshot ||
                isSnapshotUrl ||
                ((isBlank || isBase) && hasCachedPath)
            )
        if (!shouldUseCached) return base
        return rebuildCachedUrl(base, prefs.cachedFullUrl, prefs.cachedRelativePath)
    }

    private fun urlsEquivalent(a: String, b: String): Boolean {
        if (a == b) return true
        val normA = if (a.endsWith("/")) a.dropLast(1) else a
        val normB = if (b.endsWith("/")) b.dropLast(1) else b
        return normA == normB
    }

    private fun baseUrlFor(target: LoadTarget): String =
        when (target) {
            LoadTarget.DIRECT -> directBaseUrl()
            LoadTarget.HTTP -> httpBaseUrl()
            LoadTarget.TUNNEL -> tunnelBaseUrl()
        }

    private fun isDirectConfigured(): Boolean =
        prefs.hasDirectEndpointConfigured()

    private fun isHttpConfigured(): Boolean {
        if (!prefs.httpConnectionEnabled || prefs.httpAddress.isBlank()) return false
        return credentialsStore.httpHeaderConfig() != null
    }

    private fun directBaseUrl(): String {
        val manual = prefs.localIpEndpoint?.trim()?.takeIf { it.isNotEmpty() }
        return manual?.let { normalizeBase(it) } ?: tunnelBaseUrl()
    }

    private fun httpBaseUrl(): String {
        val address = prefs.httpAddress.trim()
        if (address.isEmpty()) return tunnelBaseUrl()
        return normalizeBase(address)
    }

    private fun httpRequestHeaders(): Map<String, String>? {
        if (!isHttpConfigured()) return null
        val config = credentialsStore.httpHeaderConfig() ?: return null
        return mapOf(config.name to config.value)
    }

    private fun loadUrlForTarget(url: String, target: LoadTarget) {
        if (target == LoadTarget.HTTP) {
            val headers = httpRequestHeaders()
            if (headers != null) {
                webView.loadUrl(url, headers)
                return
            }
        }
        webView.loadUrl(url)
    }

    private fun connectionOrder(): List<LoadTarget> =
        listOfNotNull(
            LoadTarget.DIRECT.takeIf { isDirectConfigured() },
            LoadTarget.HTTP.takeIf { isHttpConfigured() },
            LoadTarget.TUNNEL
        )

    private fun shouldStartTunnelService(): Boolean =
        preferredInitialTarget() == LoadTarget.TUNNEL

    private fun preferredInitialTarget(): LoadTarget {
        return when {
            isDirectConfigured() -> LoadTarget.DIRECT
            isHttpConfigured() -> LoadTarget.HTTP
            else -> LoadTarget.TUNNEL
        }
    }

    private fun fallbackTarget(from: LoadTarget): LoadTarget {
        val baseOrder = listOf(LoadTarget.DIRECT, LoadTarget.HTTP, LoadTarget.TUNNEL)
        val startIndex = baseOrder.indexOf(from).takeIf { it >= 0 } ?: 0
        for (step in 1 until baseOrder.size) {
            val candidate = baseOrder[(startIndex + step) % baseOrder.size]
            when (candidate) {
                LoadTarget.DIRECT -> if (isDirectConfigured()) return LoadTarget.DIRECT
                LoadTarget.HTTP -> if (isHttpConfigured()) return LoadTarget.HTTP
                LoadTarget.TUNNEL -> return LoadTarget.TUNNEL
            }
        }
        return LoadTarget.TUNNEL
    }

    private fun nextRetryTarget(current: LoadTarget): LoadTarget {
        val order = connectionOrder()
        if (order.isEmpty()) return LoadTarget.TUNNEL
        val index = order.indexOf(current)
        return if (index == -1) {
            order.first()
        } else {
            order[(index + 1) % order.size]
        }
    }

    private fun logHttpEvent(
        level: ConnEvent.Level,
        message: String,
        code: Int? = null,
        throwable: Throwable? = null
    ) {
        lifecycleScope.launch {
            connLogger.log(
                ConnEvent(
                    timestampMillis = System.currentTimeMillis(),
                    level = level,
                    phase = ConnEvent.Phase.HTTP,
                    message = code?.let { "$message (code=$it)" } ?: message,
                    throwableClass = throwable?.javaClass?.name,
                    throwableMessage = throwable?.message,
                    stacktracePreview = throwable?.stackTraceToString()?.take(600)
                )
            )
        }
    }

    private fun tunnelBaseUrl(): String =
        normalizeBase("http://127.0.0.1:${prefs.localPort}/")

    private fun showCachedPage(html: String, baseUrl: String?) {
        showingCachedSnapshot = true
        keepContentVisibleDuringLoad = false
        snapshotRetryTarget = preferredInitialTarget()
        hideFriendlyError()
        showContent()
        updateOfflineLoadingVisibility()
        updateOfflineAssistVisibility()
        val fallbackBase = when {
            isDirectConfigured() -> directBaseUrl()
            isHttpConfigured() -> httpBaseUrl()
            else -> tunnelBaseUrl()
        }
        val base = normalizeBase(baseUrl ?: fallbackBase)
        val initialTarget = preferredInitialTarget()
        currentTarget = initialTarget
        updateActiveConnectionIndicator(initialTarget)
        tunnelAttempted = initialTarget == LoadTarget.TUNNEL
        cancelDirectFallback()
        pendingSnapshot = false
        val resolvedUrl = rebuildCachedUrl(base, prefs.cachedFullUrl, prefs.cachedRelativePath)
        val rawUrl = prefs.cachedFullUrl ?: resolvedUrl
        lastSnapshotUrl = rawUrl
        lastSnapshotHash = html.hashCode()
        loadingCachedHtml = true
        webView.loadDataWithBaseURL(base, html, "text/html", "utf-8", resolvedUrl)
    }

    private fun showCachedPageIfAvailable(): Boolean {
        if (loadCachedArchiveIfAvailable()) return true
        return showCachedHtmlFallback()
    }

    private fun showCachedHtmlFallback(): Boolean {
        val inlineHtml = prefs.cachedHtml
        val html = inlineHtml ?: loadCachedHtmlFromDisk()
        if (html.isNullOrEmpty()) return false
        showCachedPage(html, prefs.cachedBaseUrl)
        return true
    }

    private fun snapshotPage() {
        if (!prefs.cacheLastPage || showingCachedSnapshot) return
        pendingSnapshot = true
        shouldSnapshotCurrentPage = true
        lastSnapshotUrl = null
        lastSnapshotHash = 0
    }

    private fun loadCachedArchiveIfAvailable(): Boolean {
        val path = prefs.cachedArchivePath ?: return false
        val file = File(path)
        if (!file.exists()) {
            prefs.cachedArchivePath = null
            return false
        }
        showingCachedSnapshot = true
        keepContentVisibleDuringLoad = false
        snapshotRetryTarget = preferredInitialTarget()
        showContent()
        hideFriendlyError()
        updateOfflineLoadingVisibility()
        updateOfflineAssistVisibility()
        val initialTarget = preferredInitialTarget()
        currentTarget = initialTarget
        updateActiveConnectionIndicator(initialTarget)
        tunnelAttempted = initialTarget == LoadTarget.TUNNEL
        cancelDirectFallback()
        pendingSnapshot = false
        val base = prefs.cachedBaseUrl?.let { normalizeBase(it) } ?: when {
            isDirectConfigured() -> directBaseUrl()
            isHttpConfigured() -> httpBaseUrl()
            else -> tunnelBaseUrl()
        }
        val resolvedUrl = rebuildCachedUrl(base, prefs.cachedFullUrl, prefs.cachedRelativePath)
        val rawUrl = prefs.cachedFullUrl ?: resolvedUrl
        lastSnapshotUrl = rawUrl
        lastSnapshotHash = 0
        val uri = Uri.fromFile(file)
        webView.loadUrl(uri.toString())
        return true
    }

    private fun handleSnapshotPayload(payload: String?) {
        if (!prefs.cacheLastPage) return
        if (payload.isNullOrEmpty()) {
            pendingSnapshot = true
            return
        }
        try {
            val obj = JSONObject(payload)
            val pending = obj.optInt("pending", obj.optInt("p", 0))
            if (pending > 0) {
                pendingSnapshot = true
                return
            }
            val html = obj.optString("html", obj.optString("h", ""))
            if (html.isEmpty()) {
                pendingSnapshot = true
                return
            }
            val url = obj.optString("url", obj.optString("u", webView.url ?: ""))
            val captureUrl = if (url.isNotEmpty()) url else (webView.url ?: "")
            if (captureUrl.startsWith("file://")) {
                pendingSnapshot = false
                return
            }
            if (captureUrl != lastSnapshotUrl) {
                pendingSnapshot = true
            }
            val base = baseFrom(captureUrl.ifEmpty { directBaseUrl() })
            val htmlHash = html.hashCode()
            val pendingDiffers = pendingSnapshot && htmlHash != lastSnapshotHash
            val shouldCapture =
                pendingDiffers || captureUrl != lastSnapshotUrl || htmlHash != lastSnapshotHash
            if (!shouldCapture) return

            pendingSnapshot = false
            lastSnapshotHash = htmlHash
            prefs.cachedFullUrl = captureUrl
            val relative = extractRelativePath(captureUrl)
            prefs.cachedRelativePath = relative
            lastSnapshotUrl = captureUrl
            lifecycleScope.launch {
                persistOfflineSnapshot(html, base)
            }
            shouldSnapshotCurrentPage = false
        } catch (t: Throwable) {
            android.util.Log.e("WEBNAV", "Failed to handle snapshot payload", t)
            pendingSnapshot = true
        }
    }

    private fun loadCachedHtmlFromDisk(): String? {
        val path = prefs.cachedHtmlPath ?: return null
        val file = File(path)
        if (!file.exists()) {
            prefs.cachedHtmlPath = null
            return null
        }
        return try {
            file.readText(Charsets.UTF_8)
        } catch (t: Throwable) {
            android.util.Log.e("WEBNAV", "Failed to read offline snapshot", t)
            null
        }
    }

    private suspend fun persistOfflineSnapshot(html: String, baseUrl: String) {
        val dir = offlineCacheDir
        if (!dir.exists() && !dir.mkdirs()) {
            android.util.Log.e(
                "WEBNAV",
                "Failed to prepare offline cache dir ${dir.absolutePath}"
            )
            return
        }
        val htmlFile = File(dir, OFFLINE_CACHE_FILE)
        val archiveFile = File(dir, OFFLINE_CACHE_ARCHIVE)
        withContext(Dispatchers.IO) {
            try {
                htmlFile.writeText(html, Charsets.UTF_8)
                prefs.cachedHtmlPath = htmlFile.absolutePath
                prefs.cachedHtmlTimestamp = System.currentTimeMillis()
                prefs.cachedBaseUrl = baseUrl
                prefs.cachedHtml = if (html.length <= MAX_PREF_HTML_CHARS) html else null
            } catch (t: Throwable) {
                android.util.Log.e("WEBNAV", "Failed to persist offline HTML", t)
            }
        }

        withContext(Dispatchers.Main) {
            try {
                webView.saveWebArchive(archiveFile.absolutePath, false) { savedPath ->
                    lifecycleScope.launch(Dispatchers.IO) {
                        prefs.cachedArchivePath =
                            savedPath?.takeIf { it.isNotEmpty() } ?: archiveFile.absolutePath
                    }
                }
            } catch (t: Throwable) {
                android.util.Log.e("WEBNAV", "Failed to persist web archive", t)
            }
        }
    }

    private fun extractRelativePath(url: String): String? {
        if (url.isBlank()) return null
        return try {
            val uri = Uri.parse(url)
            val scheme = uri.scheme?.lowercase()
            if (scheme != "http" && scheme != "https") return null
            val path = uri.encodedPath ?: ""
            val query = uri.encodedQuery?.let { "?$it" } ?: ""
            val fragment = uri.encodedFragment?.let { "#$it" } ?: ""
            val combined = path + query + fragment
            combined.takeIf { it.isNotEmpty() }
        } catch (_: Throwable) {
            null
        }
    }

    private fun rebuildCachedUrl(base: String, full: String?, relative: String?): String {
        val rel = relative?.takeIf { it.isNotBlank() }
        if (rel != null) {
            return appendRelativePath(base, rel)
        }
        val fullUrl = full?.takeIf { it.isNotBlank() }
        if (fullUrl != null) {
            try {
                val baseUri = Uri.parse(base)
                val cachedUri = Uri.parse(fullUrl)
                return cachedUri.buildUpon()
                    .scheme(baseUri.scheme)
                    .encodedAuthority(baseUri.encodedAuthority)
                    .build()
                    .toString()
            } catch (_: Throwable) {
            }
        }
        return base
    }

    private fun appendRelativePath(base: String, relative: String?): String {
        val rel = relative?.takeIf { it.isNotBlank() } ?: return base
        val normalizedBase = if (base.endsWith("/")) base else "$base/"
        return normalizedBase + rel.trimStart('/')
    }

    private fun updateActiveConnectionIndicator(target: LoadTarget) {
        val changed = lastAnnouncedTarget != target
        val now = SystemClock.elapsedRealtime()
        when (target) {
            LoadTarget.TUNNEL -> {
                if (changed) {
                    lastHttpProbeAt = now
                }
                // keep direct probe running while on tunnel
            }
            LoadTarget.HTTP -> {
                lastHttpProbeAt = now
                lastDirectProbeAt = 0L
            }
            LoadTarget.DIRECT -> {
                lastDirectProbeAt = now
                lastHttpProbeAt = 0L
            }
        }
        val messageRes = when (target) {
            LoadTarget.DIRECT -> R.string.connection_mode_direct
            LoadTarget.HTTP -> R.string.connection_mode_http
            LoadTarget.TUNNEL -> R.string.connection_mode_tunnel
        }
        val url = baseUrlFor(target)
        val message = activity.getString(messageRes, url)
        if (::toolbar.isInitialized) {
            toolbar.subtitle = message
        }
        if (changed && !showingCachedSnapshot && ::rootView.isInitialized) {
            rootView.shortSnack(message)
        }
        logConnection(android.util.Log.INFO, "WEBCONN") {
            "Modo de conexão ativo: $target -> $url"
        }
        lastAnnouncedTarget = target
    }

    private fun baseFrom(url: String): String {
        return try {
            val uri = Uri.parse(url)
            uri.buildUpon()
                .encodedPath("/")
                .encodedQuery(null)
                .fragment(null)
                .build()
                .toString()
        } catch (_: Throwable) {
            url
        }
    }

    private fun remapUrlToBase(url: String, base: String): String? {
        return try {
            val original = Uri.parse(url)
            val baseUri = Uri.parse(base)
            baseUri.buildUpon()
                .encodedPath(original.encodedPath ?: "/")
                .encodedQuery(original.encodedQuery)
                .fragment(original.fragment)
                .build()
                .toString()
        } catch (_: Throwable) {
            null
        }
    }

    private fun normalizeBase(raw: String): String {
        var value = raw.trim()
        if (value.isEmpty()) return value
        val startsWithHttp =
            value.startsWith("http://", ignoreCase = true) ||
                value.startsWith("https://", ignoreCase = true)
        if (startsWithHttp) {
            return if (value.endsWith("/")) value else "$value/"
        }
        if (!value.startsWith("http://") && !value.startsWith("https://")) {
            value = "http://$value"
        }
        return if (value.endsWith("/")) value else "$value/"
    }

    private fun scheduleDirectFallback() {
        cancelDirectFallback()
        if (currentTarget != LoadTarget.DIRECT) {
            return
        }
        val runnable = Runnable {
            if (currentTarget != LoadTarget.TUNNEL && !tunnelAttempted) {
                android.util.Log.w("WEBNAV", "Primary load timeout ($currentTarget); switching to tunnel")
                tunnelAttempted = true
                startConnectionLoop(force = true)
            }
        }
        directTimeoutRunnable = runnable
        fallbackHandler.postDelayed(runnable, 4_000L)
    }

    private fun cancelDirectFallback() {
        directTimeoutRunnable?.let { fallbackHandler.removeCallbacks(it) }
        directTimeoutRunnable = null
    }

    private fun shouldFallbackToTunnel(errorCode: Int): Boolean {
        if (currentTarget == LoadTarget.TUNNEL || tunnelAttempted) return false
        return when (errorCode) {
            WebViewClient.ERROR_CONNECT,
            WebViewClient.ERROR_TIMEOUT,
            WebViewClient.ERROR_HOST_LOOKUP,
            WebViewClient.ERROR_UNKNOWN,
            WebViewClient.ERROR_IO,
            WebViewClient.ERROR_FAILED_SSL_HANDSHAKE,
            WebViewClient.ERROR_UNSUPPORTED_AUTH_SCHEME,
            WebViewClient.ERROR_PROXY_AUTHENTICATION,
            WebViewClient.ERROR_BAD_URL,
            WebViewClient.ERROR_UNSUPPORTED_SCHEME -> true
            else -> false
        }
    }

    private fun handleCachedArchiveFailure(url: Uri, errorCode: Int): Boolean {
        if (!prefs.cacheLastPage) return false
        val scheme = url.scheme ?: return false
        if (scheme != "file") return false
        val lastSegment = url.lastPathSegment ?: return false
        if (lastSegment != OFFLINE_CACHE_ARCHIVE) return false
        android.util.Log.w(
            "WEBNAV",
            "Cached archive load failed with error $errorCode; trying HTML fallback"
        )
        prefs.cachedArchivePath = null
        return showCachedHtmlFallback()
    }

    private fun shouldShowOfflineForHttp(statusCode: Int): Boolean {
        if (statusCode <= 0) return true
        return statusCode in 500..599 || statusCode == 429 || statusCode == 408
    }

    private fun toLocalLoopbackIfNeeded(u: Uri): Uri {
        if (currentTarget != LoadTarget.TUNNEL) return u
        val remoteHost = prefs.remoteHost.lowercase()
        val remotePort = prefs.remotePort
        val reqPort = (u.port.takeIf { it != -1 } ?: if (u.scheme == "https") 443 else 80)
        val isRemote =
            (u.host?.lowercase() == remoteHost && reqPort == remotePort) ||
                (u.host == "localhost" && reqPort == remotePort) ||
                (u.host == "0.0.0.0" && reqPort == remotePort)

        return if (isRemote) {
            u.buildUpon()
                .scheme("http")
                .encodedAuthority("127.0.0.1:${prefs.localPort}")
                .build()
        } else u
    }

    private fun guessMime(url: String): String = when {
        url.endsWith(".js") -> "application/javascript"
        url.endsWith(".css") -> "text/css"
        url.endsWith(".png") -> "image/png"
        url.endsWith(".jpg") || url.endsWith(".jpeg") -> "image/jpeg"
        url.endsWith(".webp") -> "image/webp"
        url.endsWith(".svg") -> "image/svg+xml"
        url.endsWith(".json") -> "application/json"
        else -> "text/html"
    }

    private fun showLoading(keepWebVisible: Boolean = false) {
        progress.isVisible = true
        if (!keepWebVisible) {
            webView.isVisible = false
        } else if (!webView.isVisible) {
            webView.isVisible = true
        }
        if (!toolbarPinnedByUser) {
            hideToolbar()
        }
        updateOfflineLoadingVisibility()
        updateOfflineAssistVisibility()
    }

    private fun showContent() {
        progress.isVisible = false
        webView.isVisible = true
        if (toolbarPinnedByUser) {
            showToolbar()
        }
        updateOfflineLoadingVisibility()
        updateOfflineAssistVisibility()
    }

    private fun hideToolbar() {
        if (toolbarPinnedByUser) return
        setToolbarVisibility(false, animate = true)
    }

    private fun showToolbar() {
        setToolbarVisibility(true, animate = true)
    }

    private fun showToolbarImmediate() {
        setToolbarVisibility(true, animate = false)
    }

    private fun hideToolbarImmediate(force: Boolean = false) {
        if (!force && toolbarPinnedByUser) return
        setToolbarVisibility(false, animate = false)
    }

    private fun setToolbarVisibility(visible: Boolean, animate: Boolean) {
        if (toolbarVisible == visible) return
        toolbarVisible = visible
        appBar.animate().cancel()
        if (visible) {
            appBar.isVisible = true
            if (animate) {
                appBar.alpha = 0f
                appBar.animate().alpha(1f).setDuration(180L).start()
            } else {
                appBar.alpha = 1f
            }
        } else {
            if (animate) {
                appBar.animate().alpha(0f).setDuration(120L).withEndAction {
                    appBar.isVisible = false
                    appBar.alpha = 1f
                }.start()
            } else {
                appBar.isVisible = false
                appBar.alpha = 1f
            }
        }
        updateRootPadding()
        updateAppBarPadding()
    }

    private fun handleToggleTap(target: ToggleTarget, now: Long): Boolean {
        if (lastTapTarget == target && now - lastTapAt <= doubleTapTimeout) {
            lastTapTarget = null
            lastTapAt = 0L
            when (target) {
                ToggleTarget.SHOW -> {
                    toolbarPinnedByUser = true
                    showToolbar()
                }
                ToggleTarget.HIDE -> {
                    toolbarPinnedByUser = false
                    hideToolbar()
                }
            }
            return true
        }
        return false
    }

    private fun updateRootPadding() {
        val top = if (toolbarVisible) 0 else collapsedMargin
        rootView.updatePadding(top = top)
    }

    private fun applyBottomSpacing(edgeInsets: EdgeInsetsInfo) {
        val navigationInset = edgeInsets.navigationBottom
        val imeInset = edgeInsets.imeBottom
        val effectiveBottom = max(navigationInset, imeInset)
        val extraBottom = (effectiveBottom - navigationInset).coerceAtLeast(0)
        val insetLeft = edgeInsets.left
        val insetRight = edgeInsets.right
        val containerNeedsUpdate =
            contentContainer.paddingBottom != navigationInset ||
                contentContainer.paddingLeft != insetLeft ||
                contentContainer.paddingRight != insetRight
        val webViewNeedsUpdate =
            webView.paddingLeft != 0 ||
                webView.paddingRight != 0 ||
                webView.paddingBottom != extraBottom
        if (!containerNeedsUpdate && !webViewNeedsUpdate && contentBottomPadding == effectiveBottom) {
            return
        }
        contentInsetLeft = insetLeft
        contentInsetRight = insetRight
        contentBottomPadding = effectiveBottom
        contentContainer.setPadding(insetLeft, contentContainer.paddingTop, insetRight, navigationInset)
        webView.setPadding(0, webView.paddingTop, 0, extraBottom)
        webView.updateLayoutParams<FrameLayout.LayoutParams> {
            if (bottomMargin != extraBottom) bottomMargin = extraBottom
        }
        progress.updateLayoutParams<FrameLayout.LayoutParams> {
            bottomMargin = extraBottom
        }
        tunnelErrorContainer.updateLayoutParams<FrameLayout.LayoutParams> {
            bottomMargin = extraBottom
        }
        updateAppBarPadding()
    }

    private fun computeEdgeInsets(insets: WindowInsetsCompat): EdgeInsetsInfo {
        val statusBars = insets.getInsets(WindowInsetsCompat.Type.statusBars())
        val navigationBars = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
        val systemGestures = insets.getInsets(WindowInsetsCompat.Type.systemGestures())
        val cutout = insets.getInsets(WindowInsetsCompat.Type.displayCutout())
        val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
        val topInset = max(statusBars.top, cutout.top)
        val leftInset = max(max(statusBars.left, navigationBars.left), cutout.left)
        val rightInset = max(max(statusBars.right, navigationBars.right), cutout.right)
        val navInsetBottom = max(max(navigationBars.bottom, systemGestures.bottom), cutout.bottom)
        return EdgeInsetsInfo(
            top = topInset,
            left = leftInset,
            right = rightInset,
            navigationBottom = navInsetBottom,
            imeBottom = ime.bottom
        )
    }

    private fun updateAppBarPadding() {
        val desiredTop = if (toolbarVisible) statusBarInset else 0
        val desiredLeft = contentInsetLeft
        val desiredRight = contentInsetRight
        if (appBar.paddingTop != desiredTop ||
            appBar.paddingLeft != desiredLeft ||
            appBar.paddingRight != desiredRight
        ) {
            appBar.setPadding(desiredLeft, desiredTop, desiredRight, appBar.paddingBottom)
        }
    }

    private fun injectNetworkShim() {
        val header = credentialsStore.httpHeaderConfig() ?: return
        val name = header.name.trim()
        val value = header.value.trim()
        if (name.isEmpty() || value.isEmpty()) return
        val js = """
            (function() {
                const HEADER_NAME = ${jsStringLiteral(name)};
                const HEADER_VALUE = ${jsStringLiteral(value)};
                if (!HEADER_NAME || !HEADER_VALUE) { return; }
                if (window.__tunnelviewHeaderShim === HEADER_NAME + HEADER_VALUE) { return; }
                window.__tunnelviewHeaderShim = HEADER_NAME + HEADER_VALUE;
                const mergeHeaders = (existing) => {
                    const headers = new Headers(existing || {});
                    headers.set(HEADER_NAME, HEADER_VALUE);
                    return headers;
                };
                const withCredentials = (init) => Object.assign({ credentials: 'include' }, init || {});
                if (typeof window.fetch === 'function') {
                    const origFetch = window.fetch;
                    window.fetch = function(input, init) {
                        const nextInit = withCredentials(init);
                        if (input instanceof Request) {
                            nextInit.headers = mergeHeaders(nextInit.headers || input.headers);
                            return origFetch.call(this, new Request(input, nextInit));
                        }
                        nextInit.headers = mergeHeaders(nextInit.headers);
                        return origFetch.call(this, input, nextInit);
                    };
                }
                if (window.XMLHttpRequest) {
                    const proto = XMLHttpRequest.prototype;
                    const origOpen = proto.open;
                    proto.open = function() {
                        return origOpen.apply(this, arguments);
                    };
                    const origSend = proto.send;
                    proto.send = function(body) {
                        try { this.setRequestHeader(HEADER_NAME, HEADER_VALUE); } catch (_) {}
                        return origSend.apply(this, arguments);
                    };
                }
                const submitViaFetch = async (form) => {
                    if (!form || form.__tvSubmitting) return false;
                    const method = (form.method || 'GET').toUpperCase();
                    if (method === 'GET') return false;
                    const action = form.action || window.location.href;
                    form.__tvSubmitting = true;
                    try {
                        const response = await fetch(action, {
                            method,
                            body: new FormData(form),
                            headers: mergeHeaders(),
                            credentials: 'include'
                        });
                        const text = await response.text();
                        document.open();
                        document.write(text);
                        document.close();
                    } catch (_) {
                    } finally {
                        form.__tvSubmitting = false;
                    }
                    return true;
                };
                document.addEventListener('submit', function(event) {
                    if (!event || !event.target) return;
                    if (submitViaFetch(event.target)) {
                        event.preventDefault();
                    }
                }, true);
                if (window.HTMLFormElement) {
                    const origSubmit = HTMLFormElement.prototype.submit;
                    HTMLFormElement.prototype.submit = function() {
                        if (!submitViaFetch(this)) {
                            return origSubmit.apply(this, arguments);
                        }
                    };
                }
            })();
        """.trimIndent()
        webView.post { webView.evaluateJavascript(js, null) }
    }

    private fun injectDiagnostics() {
        val diagJs = """
                (function() {
                    if (window.__netHooked2) return;
                    window.__netHooked2 = true;

                    let __pendingNet = 0;
                    let __snapshotTimer = null;
                    const notifySnapshot = () => {
                        if (!window.AndroidOffline || typeof AndroidOffline.onSnapshotCandidate !== 'function') return;
                        if (__snapshotTimer) window.clearTimeout(__snapshotTimer);
                        __snapshotTimer = window.setTimeout(() => {
                            try {
                                AndroidOffline.onSnapshotCandidate(JSON.stringify({
                                    pending: __pendingNet || 0,
                                    html: document.documentElement.outerHTML,
                                    url: window.location.href
                                }));
                            } catch (_) {}
                        }, 300);
                    };
                    const trackStart = () => { __pendingNet++; };
                    const trackEnd = () => {
                        if (__pendingNet > 0) __pendingNet--;
                        notifySnapshot();
                    };

                    window.setTimeout(notifySnapshot, 1200);
                    window.addEventListener('load', notifySnapshot, { once: false });
                    document.addEventListener('readystatechange', () => {
                        if (document.readyState === 'complete') notifySnapshot();
                    });
                    window.setInterval(notifySnapshot, 4000);

                    const origFetch = window.fetch;
                    window.fetch = async function(input, init) {
                        const method = (init && init.method) ? init.method : 'GET';
                        const url = (typeof input === 'string') ? input : (input && input.url);
                        trackStart();
                        try {
                        const resp = await origFetch(input, init);
                        return resp;
                        } catch (e) {
                        throw e;
                        } finally {
                        trackEnd();
                        }
                    };

                    const OrigXHR = window.XMLHttpRequest;
                    function HookedXHR() {
                        const xhr = new OrigXHR();
                        let finished = false;
                        const finish = () => {
                        if (!finished) {
                            finished = true;
                            trackEnd();
                        }
                        };
                        const o = xhr.open;
                        xhr.open = function(m,u){ return o.apply(xhr, arguments); };
                        const sendOrig = xhr.send;
                        xhr.send = function(){
                        finished = false;
                        trackStart();
                        return sendOrig.apply(xhr, arguments);
                        };
                        xhr.addEventListener('load', function(){ finish(); });
                        xhr.addEventListener('error', function(){ finish(); });
                        xhr.addEventListener('abort', function(){ finish(); });
                        return xhr;
                    }
                    window.XMLHttpRequest = HookedXHR;
                })();
        """.trimIndent()
        webView.evaluateJavascript(diagJs, null)
    }

    private fun jsStringLiteral(value: String): String {
        val escaped = value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "")
        return "\"$escaped\""
    }

    private fun showFriendlyError(reason: String?) {
        if (showingCachedSnapshot) {
            hideFriendlyError()
            return
        }
        val message = reason?.takeIf { it.isNotBlank() } ?: activity.getString(R.string.tunnel_error_generic)
        tunnelErrorMessage.text = message
        tunnelErrorContainer.isVisible = true
        tunnelErrorContainer.announceForAccessibility(message)
        updateOfflineAssistVisibility()
    }

    private fun hideFriendlyError() {
        tunnelErrorContainer.isVisible = false
        updateOfflineAssistVisibility()
    }

    private fun updateOfflineAssistVisibility() {
        if (::offlineInfoButton.isInitialized) {
            val webReady = ::webView.isInitialized && webView.isVisible
            val baseVisible = showingCachedSnapshot && webReady
            val failuresComplete = directReconnectFailed && tunnelReconnectFailed
            val eligible = offlineAssistEligibleAt?.let { SystemClock.elapsedRealtime() >= it } ?: false
            offlineInfoButton.isVisible = baseVisible && failuresComplete && eligible
        }
    }

    private fun updateOfflineLoadingVisibility() {
        if (::offlineProgress.isInitialized) {
            offlineProgress.isVisible = showingCachedSnapshot
        }
    }

    private fun showOfflineDialog() {
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.offline_info_title)
            .setMessage(R.string.offline_info_message)
            .setPositiveButton(R.string.offline_force_reload) { dialog, _ ->
                dialog.dismiss()
                requestTunnelReconnect(force = true)
                startPrimaryNavigation(force = true)
            }
            .setNeutralButton(R.string.offline_open_settings) { dialog, _ ->
                dialog.dismiss()
                activity.startActivity(Intent(activity, SettingsActivity::class.java))
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun isNetworkConnected(): Boolean {
        val connectivity = connectivityManager
            ?: (activity.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ).also { connectivityManager = it } ?: return true
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val network = connectivity.activeNetwork ?: return false
            val capabilities = connectivity.getNetworkCapabilities(network) ?: return false
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) ||
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } else {
            @Suppress("DEPRECATION")
            val info = connectivity.activeNetworkInfo
            @Suppress("DEPRECATION")
            info?.isConnected == true
        }
    }

    private val lifecycleScope: LifecycleCoroutineScope
        get() = activity.lifecycleScope

    private val resources get() = activity.resources
    private val filesDir get() = activity.filesDir
    private val cacheDir get() = activity.cacheDir

    private inner class OfflineBridge {
        @JavascriptInterface
        fun onSnapshotCandidate(payload: String?) {
            if (!prefs.cacheLastPage) return
            webView.post { handleSnapshotPayload(payload) }
        }
    }

    companion object {
        private const val KEY_TOOLBAR_PINNED = "toolbarPinned"
        private const val KEY_TOOLBAR_VISIBLE = "toolbarVisible"
        private const val OFFLINE_CACHE_DIR = "offline-cache"
        private const val OFFLINE_CACHE_FILE = "last_page.html"
        private const val OFFLINE_CACHE_ARCHIVE = "last_page.mht"
        private const val MAX_PREF_HTML_CHARS = 500_000
    }
}
