package com.zahah.tunnelview.ui.main

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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
import android.view.ViewPropertyAnimator
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
import com.zahah.tunnelview.ssh.TunnelManager
import com.zahah.tunnelview.webview.WebViewConfigurator
import com.zahah.tunnelview.work.EndpointSyncWorker
import com.google.android.material.appbar.AppBarLayout
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
    private lateinit var appBar: AppBarLayout
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
    private var toolbarVisible = true
    private var appBarAnimator: ViewPropertyAnimator? = null
    private var showingCachedSnapshot = false
    private var keepContentVisibleDuringLoad = false
    private var tunnelAttempted = false
    private var directReconnectAttempted = false
    private var tunnelReconnectAttempted = false
    private var directReconnectFailed = false
    private var tunnelReconnectFailed = false
    private var tunnelForceReconnectPending = false
    private var currentTarget = LoadTarget.DIRECT
    private var lastTapAt = 0L
    private var lastTapTarget: ToggleTarget? = null
    private val doubleTapTimeout by lazy { ViewConfiguration.getDoubleTapTimeout().toLong() }
    private var statusBarInset = 0
    private var systemBottomInset = 0
    private var contentBottomPadding = 0
    private var collapsedMargin = 0
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
    private var shouldSnapshotCurrentPage = false
    private var pendingSnapshot = false
    private var lastSnapshotUrl: String? = null
    private var lastSnapshotHash: Int = 0
    private val offlineCacheDir by lazy { File(filesDir, OFFLINE_CACHE_DIR) }
    private val offlineBridge = OfflineBridge()
    private val bottomBarExtraPx by lazy { (8 * resources.displayMetrics.density).roundToInt() }
    private var loadingCachedHtml = false
    private var snapshotRetryTarget = LoadTarget.DIRECT
    private var offlineAssistEligibleAt: Long? = null
    private var offlineAssistRunnable: Runnable? = null
    private val offlineAssistDelayMs = 7_000L
    private var connectivityManager: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var legacyConnectivityRegistered = false
    private var lastKnownNetworkAvailable: Boolean? = null
    private val currentTunnelState: TunnelManager.State
        get() = tunnelManager.state.value

    private enum class LoadTarget { DIRECT, TUNNEL }
    private enum class ToggleTarget { SHOW, HIDE }

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
        val hideTap = toolbarVisible &&
            (view === appBar || view === toolbar) &&
            event.y <= appBar.height
        val revealTap = !toolbarVisible && isTapWithinToolbarRevealZone(view, event.y)
        val showEligible = !toolbarVisible &&
            (progress.isVisible || showingCachedSnapshot || revealTap)
        val target = when {
            hideTap -> ToggleTarget.HIDE
            showEligible -> ToggleTarget.SHOW
            else -> null
        }
        if (target != null && handleToggleTap(target, now)) {
            return@OnTouchListener true
        }
        lastTapAt = now
        lastTapTarget = target
        false
    }

    private fun isTapWithinToolbarRevealZone(view: View, eventY: Float): Boolean {
        if (view === appBar || view === toolbar) return true
        val defaultHeight = (56 * resources.displayMetrics.density).toInt()
        val toolbarHeight = if (appBar.height > 0) appBar.height else defaultHeight
        val revealThreshold = (statusBarInset + toolbarHeight).coerceAtLeast(collapsedMargin)
        if (revealThreshold <= 0) return false
        return eventY <= revealThreshold
    }

    private val http: OkHttpClient by lazy {
        val cacheDirectory = File(cacheDir, "webview-http-cache").apply { mkdirs() }
        val cacheSize = 50L * 1024L * 1024L
        OkHttpClient.Builder()
            .connectTimeout(7, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .cache(Cache(cacheDirectory, cacheSize))
            .build()
    }

    private val healthCheckClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .callTimeout(7, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != Events.BROADCAST) return
            val msg = intent.getStringExtra(Events.MESSAGE) ?: return
            when {
                msg.startsWith("CONNECTED") -> {
                    tunnelForceReconnectPending = false
                    webView.postDelayed({ startPrimaryNavigation(force = true) }, 250L)
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ensurePostNotificationsThenStart()
        } else {
            startTunnelServiceSafely()
        }
        EndpointSyncWorker.schedulePeriodic(activity.applicationContext)
        EndpointSyncWorker.enqueueImmediate(activity.applicationContext)
        val queuedAction = pendingTunnelAction
        pendingTunnelAction = null
        val hadPendingSse = pendingSseStart
        pendingSseStart = false
        startNtfyUpdates()
        if (hadPendingSse) {
            // startNtfyUpdates above already tried to run; if it fails, pendingSseStart flips back to true
        }
        if (queuedAction != null) {
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
    }

    fun onStop() {
        stopNtfyUpdates()
        EndpointSyncWorker.cancelPeriodic(activity.applicationContext)
    }

    fun onDestroy() {
        if (receiverRegistered) {
            activity.unregisterReceiver(receiver)
            receiverRegistered = false
        }
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
        stopNtfyUpdates()
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
        if (currentTarget == LoadTarget.DIRECT) {
            tunnelAttempted = false
            scheduleDirectFallback()
        } else {
            tunnelAttempted = true
            cancelDirectFallback()
        }
        keepContentVisibleDuringLoad = false
        showLoading()
        webView.loadUrl(homeUrl)
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
            startTunnelServiceSafely()
        } else {
            Toast.makeText(
                activity,
                activity.getString(R.string.post_notifications_denied),
                Toast.LENGTH_LONG
            ).show()
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
            val status = insets.getInsets(WindowInsetsCompat.Type.statusBars())
            statusBarInset = status.top
            collapsedMargin = when {
                status.top <= 0 -> 0
                else -> (status.top / 24).coerceAtLeast(1)
            }
            val navBottom = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
            val imeBottom = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
            systemBottomInset = max(navBottom, imeBottom)
            updateRootPadding()
            updateAppBarPadding()
            insets
        }

        ViewCompat.setOnApplyWindowInsetsListener(contentContainer) { _, insets ->
            val navBottom = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
            val imeBottom = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
            applyBottomSpacing(navBottom, imeBottom)
            insets
        }

        ViewCompat.setOnApplyWindowInsetsListener(webView) { v, insets ->
            val navInsets = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            val imeInsets = insets.getInsets(WindowInsetsCompat.Type.ime())
            applyBottomSpacing(navInsets.bottom, imeInsets.bottom)
            val desiredBottom = contentBottomPadding
            val desiredLeft = navInsets.left
            val desiredRight = navInsets.right
            if (v.paddingLeft != desiredLeft || v.paddingRight != desiredRight || v.paddingBottom != desiredBottom) {
                v.setPadding(desiredLeft, v.paddingTop, desiredRight, desiredBottom)
            }
            insets
        }

        ViewCompat.requestApplyInsets(appBar)
        ViewCompat.requestApplyInsets(webView)
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
                return if (mapped != request.url) {
                    view.loadUrl(mapped.toString())
                    true
                } else {
                    false
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
                val method = request.method.uppercase()
                if (isMain) {
                    logConnection(android.util.Log.DEBUG, "WEBREQ") {
                        "MAIN $method ${request.url}"
                    }
                    return null
                }
                if (method != "GET") return null
                if (currentTarget != LoadTarget.TUNNEL) return null
                val orig = request.url
                val url = toLocalLoopbackIfNeeded(orig).toString()
                logConnection(android.util.Log.DEBUG, "WEBREQ") { "GET $orig -> $url" }

                return try {
                    val reqBuilder = Request.Builder()
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
                        }
                        .build()

                    val resp: Response = http.newCall(reqBuilder).execute()
                    val body = resp.body ?: run {
                        resp.closeQuietly()
                        return null
                    }

                    val code = resp.code
                    val mime =
                        resp.header("Content-Type")?.substringBefore(";") ?: guessMime(url)
                    val enc =
                        resp.header("Content-Type")?.substringAfter("charset=", "")?.ifBlank { null }
                            ?: "utf-8"
                    val sizeHint = body.contentLength()
                    val respSource = when {
                        resp.cacheResponse != null -> "cache"
                        resp.networkResponse != null -> "net"
                        else -> "n/a"
                    }

                    logConnection(android.util.Log.DEBUG, "WEBRESP") {
                        "↩ ${code} ${mime} ${if (sizeHint >= 0) "${sizeHint}B" else "stream"} [$respSource] <- $url"
                    }

                    val upstream = body.byteStream()
                    val proxyStream = object : InputStream() {
                        override fun read(): Int = upstream.read()
                        override fun read(b: ByteArray): Int = upstream.read(b)
                        override fun read(b: ByteArray, off: Int, len: Int): Int =
                            upstream.read(b, off, len)
                        override fun available(): Int = upstream.available()
                        override fun skip(n: Long): Long = upstream.skip(n)
                        override fun close() = upstream.close()
                    }

                    WebResourceResponse(mime, enc, proxyStream).apply {
                        setStatusCodeAndReasonPhrase(code, resp.message)
                        val headers = mutableMapOf<String, String>()
                        resp.headers.forEach { header ->
                            headers[header.first] = header.second
                        }
                        responseHeaders = headers
                    }
                } catch (t: Throwable) {
                    android.util.Log.e("WEBREQ", "Erro interceptando $url", t)
                    null
                }
            }
        }
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
        startTunnelServiceSafely()
        tunnelAttempted = false
        resetReconnectState()
        startConnectionLoop(force)
    }

    private fun resetReconnectState() {
        val hadState =
            directReconnectAttempted || tunnelReconnectAttempted ||
                directReconnectFailed || tunnelReconnectFailed ||
                offlineAssistEligibleAt != null || offlineAssistRunnable != null
        directReconnectAttempted = false
        tunnelReconnectAttempted = false
        directReconnectFailed = false
        tunnelReconnectFailed = false
        offlineAssistEligibleAt = null
        cancelOfflineAssistVisibilityUpdate()
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
        if (directReconnectFailed && tunnelReconnectFailed && offlineAssistEligibleAt == null) {
            offlineAssistEligibleAt = SystemClock.elapsedRealtime() + offlineAssistDelayMs
            scheduleOfflineAssistVisibilityUpdate(offlineAssistDelayMs)
            changed = true
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

    private fun startConnectionLoop(force: Boolean) {
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
                showLoading()
            }
            var nextForce = force
            while (isActive) {
                if (showingCachedSnapshot) {
                    val target = snapshotRetryTarget
                    logConnection(
                        android.util.Log.DEBUG,
                        "WEBCONN"
                    ) { "Tentando recuperar snapshot via $target (force=$nextForce)" }
                    if (target == LoadTarget.TUNNEL) {
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
                        snapshotRetryTarget = LoadTarget.DIRECT
                        recoverFromSnapshot(target)
                        startConnectionMonitor()
                        return@launch
                    }
                    markReconnectFailure(target)
                    if (target == LoadTarget.DIRECT) {
                        val state = currentTunnelState
                        if (state !is TunnelManager.State.Connected && state !is TunnelManager.State.Connecting) {
                            logConnection(
                                android.util.Log.DEBUG,
                                "WEBCONN"
                            ) { "Snapshot -> conexão direta indisponível; solicitando túnel somente se inativo (state=$state)" }
                            tunnelForceReconnectPending = true
                        }
                    }
                    snapshotRetryTarget = if (target == LoadTarget.DIRECT) {
                        LoadTarget.TUNNEL
                    } else {
                        LoadTarget.DIRECT
                    }
                    delay(connectionRetryDelayMs)
                    continue
                }

                val directAvailable = isTargetReachable(LoadTarget.DIRECT)
                logConnection(
                    android.util.Log.DEBUG,
                    "WEBCONN"
                ) { "Verificação direta -> $directAvailable (force=$nextForce)" }
                if (directAvailable) {
                    resetReconnectState()
                    loadTarget(LoadTarget.DIRECT, nextForce)
                    startConnectionMonitor()
                    return@launch
                } else {
                    markReconnectFailure(LoadTarget.DIRECT)
                    val state = currentTunnelState
                    if (state !is TunnelManager.State.Connected && state !is TunnelManager.State.Connecting) {
                        tunnelForceReconnectPending = true
                    }
                }

                requestTunnelReconnect()
                if (!awaitTunnelReady()) {
                    markReconnectFailure(LoadTarget.TUNNEL)
                    delay(connectionRetryDelayMs)
                    continue
                }
                val tunnelAvailable = isTargetReachable(LoadTarget.TUNNEL)
                logConnection(
                    android.util.Log.DEBUG,
                    "WEBCONN"
                ) { "Verificação túnel -> $tunnelAvailable (force=$nextForce)" }
                if (tunnelAvailable) {
                    tunnelAttempted = true
                    resetReconnectState()
                    loadTarget(LoadTarget.TUNNEL, true)
                    startConnectionMonitor()
                    return@launch
                } else {
                    markReconnectFailure(LoadTarget.TUNNEL)
                    EndpointSyncWorker.enqueueImmediate(activity.applicationContext)
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
        monitorJob?.cancel()
        monitorJob = lifecycleScope.launch {
            logConnection(
                android.util.Log.DEBUG,
                "WEBCONN"
            ) { "Monitor de conexão iniciado (targetAtual=$currentTarget)" }
            while (isActive) {
                delay(monitorIntervalMs)
                if (connectionJob?.isActive == true) continue

                val directAvailable = isTargetReachable(LoadTarget.DIRECT, silent = true)
                if (directAvailable) {
                    if (showingCachedSnapshot) {
                        logConnection(
                            android.util.Log.DEBUG,
                            "WEBNAV"
                        ) { "Monitor: direct available while snapshot visible; recovering" }
                        recoverFromSnapshot()
                        continue
                    }
                    if (currentTarget != LoadTarget.DIRECT) {
                        logConnection(
                            android.util.Log.DEBUG,
                            "WEBNAV"
                        ) { "Monitor: switching to direct after health success" }
                        startConnectionLoop(force = true)
                        return@launch
                    }
                    continue
                }

                if (currentTarget == LoadTarget.DIRECT) {
                    startConnectionLoop(force = true)
                    return@launch
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

        return withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder()
                    .url(base)
                    .cacheControl(CacheControl.Builder().noCache().noStore().build())
                    .header("User-Agent", userAgent)
                    .apply {
                        if (!cookie.isNullOrEmpty()) {
                            header("Cookie", cookie)
                        }
                    }
                    .build()
                val response = healthCheckClient.newCall(request).execute()
                val code = response.code
                val success = code in 100..599
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
                false
            }
        }
    }

    private fun loadTarget(target: LoadTarget, force: Boolean) {
        if (target == LoadTarget.DIRECT && !isDirectConfigured()) {
            logConnection(
                android.util.Log.DEBUG,
                "WEBNAV"
            ) { "Conexão direta não configurada; carregando túnel" }
            loadTarget(LoadTarget.TUNNEL, force)
            return
        }
        currentTarget = target
        if (target == LoadTarget.DIRECT) {
            tunnelAttempted = false
            scheduleDirectFallback()
        } else {
            cancelDirectFallback()
        }
        val wasShowingSnapshot = showingCachedSnapshot
        keepContentVisibleDuringLoad = wasShowingSnapshot && prefs.cacheLastPage && !force
        if (keepContentVisibleDuringLoad) {
            progress.isVisible = true
        } else {
            showLoading()
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
            if (target == LoadTarget.DIRECT) {
                cancelDirectFallback()
            }
            return
        }
        webView.loadUrl(desiredUrl)
    }

    private fun recoverFromSnapshot(target: LoadTarget = LoadTarget.DIRECT) {
        val actualTarget = if (target == LoadTarget.DIRECT && !isDirectConfigured()) {
            LoadTarget.TUNNEL
        } else {
            target
        }
        resetReconnectState()
        showingCachedSnapshot = false
        pendingSnapshot = false
        snapshotRetryTarget = LoadTarget.DIRECT
        currentTarget = actualTarget
        if (actualTarget == LoadTarget.DIRECT) {
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
                webView.loadUrl(url)
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
            LoadTarget.TUNNEL -> tunnelBaseUrl()
        }

    private fun isDirectConfigured(): Boolean =
        !prefs.localIpEndpoint.isNullOrBlank()

    private fun directBaseUrl(): String {
        val manual = prefs.localIpEndpoint?.trim()?.takeIf { it.isNotEmpty() }
        return manual?.let { normalizeBase(it) } ?: tunnelBaseUrl()
    }

    private fun tunnelBaseUrl(): String =
        normalizeBase("http://127.0.0.1:${prefs.localPort}/")

    private fun showCachedPage(html: String, baseUrl: String?) {
        showingCachedSnapshot = true
        keepContentVisibleDuringLoad = false
        snapshotRetryTarget = LoadTarget.DIRECT
        hideFriendlyError()
        showContent()
        updateOfflineLoadingVisibility()
        updateOfflineAssistVisibility()
        val base = normalizeBase(baseUrl ?: directBaseUrl())
        currentTarget = LoadTarget.DIRECT
        tunnelAttempted = false
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
        snapshotRetryTarget = LoadTarget.DIRECT
        showContent()
        hideFriendlyError()
        updateOfflineLoadingVisibility()
        updateOfflineAssistVisibility()
        currentTarget = LoadTarget.DIRECT
        tunnelAttempted = false
        cancelDirectFallback()
        pendingSnapshot = false
        val base = prefs.cachedBaseUrl?.let { normalizeBase(it) } ?: directBaseUrl()
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
        val runnable = Runnable {
            if (currentTarget == LoadTarget.DIRECT && !tunnelAttempted) {
                android.util.Log.w("WEBNAV", "Direct load timeout; switching to tunnel")
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
        if (currentTarget != LoadTarget.DIRECT || tunnelAttempted) return false
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

    private fun showLoading() {
        progress.isVisible = true
        webView.isVisible = false
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
        if (!toolbarVisible && appBar.translationY < 0f) return
        toolbarVisible = false
        updateRootPadding()
        updateAppBarPadding()
        val height =
            if (appBar.height > 0) appBar.height else (56 * resources.displayMetrics.density).toInt()
        val target = -height.toFloat()
        appBarAnimator?.cancel()
        appBarAnimator = null
        if (appBar.height == 0) {
            appBar.post {
                appBar.translationY = target
                updateRootPadding()
            }
        } else {
            appBarAnimator = appBar.animate()
                .translationY(target)
                .setDuration(180L)
                .withEndAction {
                    appBarAnimator = null
                    updateRootPadding()
                    updateAppBarPadding()
                }
        }
    }

    private fun showToolbar() {
        if (toolbarVisible) return
        toolbarVisible = true
        updateRootPadding()
        updateAppBarPadding()
        appBarAnimator?.cancel()
        appBarAnimator = null
        appBarAnimator = appBar.animate()
            .translationY(0f)
            .setDuration(180L)
            .withEndAction {
                appBarAnimator = null
                updateRootPadding()
                updateAppBarPadding()
            }
    }

    private fun showToolbarImmediate() {
        val action = Runnable {
            appBarAnimator?.cancel()
            toolbarVisible = true
            appBar.translationY = 0f
            updateRootPadding()
            updateAppBarPadding()
        }
        if (appBar.height == 0) appBar.post(action) else action.run()
    }

    private fun hideToolbarImmediate(force: Boolean = false) {
        if (!force && toolbarPinnedByUser) return
        val action = Runnable {
            appBarAnimator?.cancel()
            toolbarVisible = false
            val height =
                if (appBar.height > 0) appBar.height else (56 * resources.displayMetrics.density).toInt()
            appBar.translationY = -height.toFloat()
            updateRootPadding()
            updateAppBarPadding()
        }
        if (appBar.height == 0) appBar.post(action) else action.run()
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
        rootView.updatePadding(top = top, bottom = systemBottomInset)
    }

    private fun applyBottomSpacing(navInset: Int, imeInset: Int) {
        val navigationInset = if (navInset > 0) navInset + bottomBarExtraPx else 0
        val effectiveBottom = max(navigationInset, imeInset)
        if (contentBottomPadding == effectiveBottom &&
            contentContainer.paddingBottom == navigationInset
        ) return
        contentBottomPadding = effectiveBottom
        contentContainer.setPadding(
            contentContainer.paddingLeft,
            contentContainer.paddingTop,
            contentContainer.paddingRight,
            navigationInset
        )
        webView.setPadding(
            contentContainer.paddingLeft,
            webView.paddingTop,
            contentContainer.paddingRight,
            effectiveBottom
        )
        webView.updateLayoutParams<FrameLayout.LayoutParams> {
            if (bottomMargin != navigationInset) bottomMargin = navigationInset
        }
        progress.updateLayoutParams<FrameLayout.LayoutParams> {
            bottomMargin = effectiveBottom
        }
        tunnelErrorContainer.updateLayoutParams<FrameLayout.LayoutParams> {
            bottomMargin = effectiveBottom
        }
    }

    private fun updateAppBarPadding() {
        val desiredTop = if (toolbarVisible) statusBarInset else 0
        if (appBar.paddingTop != desiredTop) {
            appBar.setPadding(appBar.paddingLeft, desiredTop, appBar.paddingRight, appBar.paddingBottom)
        }
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
