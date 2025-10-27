package com.zahah.tunnelview.ssh

import android.content.Context
import android.content.pm.ApplicationInfo
import com.zahah.tunnelview.Prefs
import com.zahah.tunnelview.Timing
import com.zahah.tunnelview.data.ProxyEndpoint
import com.zahah.tunnelview.data.ProxyEndpointSource
import com.zahah.tunnelview.data.ProxyRepository
import com.zahah.tunnelview.logging.ConnEvent
import com.zahah.tunnelview.logging.ConnLogger
import com.zahah.tunnelview.network.ConnectivityObserver
import com.zahah.tunnelview.network.GitEndpointFetcher
import com.zahah.tunnelview.network.RemoteEndpointResult
import com.zahah.tunnelview.storage.CredentialsStore
import com.zahah.tunnelview.work.EndpointSyncWorker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.isActive
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.min
import kotlin.random.Random

/**
 * Coordinates SSH tunnel lifecycle, reconnection backoff, connectivity awareness, and exposes
 * state updates via [StateFlow] to both the service layer and UI/diagnostics screens.
 */
class TunnelManager private constructor(context: Context) {

    sealed class State {
        object Idle : State()
        object WaitingForEndpoint : State()
        data class WaitingForNetwork(val endpoint: ProxyEndpoint?) : State()
        data class Connecting(val endpoint: ProxyEndpoint?, val attempt: Int) : State()
        data class Connected(val endpoint: ProxyEndpoint, val connectedAtMillis: Long) : State()
        data class LocalBypass(val endpoint: ProxyEndpoint?, val sinceMillis: Long) : State()
        data class Failed(
            val endpoint: ProxyEndpoint?,
            val attempt: Int,
            val phase: ConnEvent.Phase,
            val message: String,
            val throwableClass: String?,
            val occurredAtMillis: Long,
        ) : State()
        object Stopping : State()
    }

    data class Failure(
        val endpoint: ProxyEndpoint?,
        val attempt: Int,
        val phase: ConnEvent.Phase,
        val message: String,
        val throwableClass: String?,
        val occurredAtMillis: Long,
    )

    data class Snapshot(
        val state: State,
        val endpoint: ProxyEndpoint?,
        val attempt: Int,
        val lastFailure: Failure?,
        val lastSuccessAtMillis: Long?,
        val forceIpv4: Boolean,
        val connectTimeoutMillis: Int,
        val socketTimeoutMillis: Int,
        val keepAliveIntervalSeconds: Int,
    )

    private val appContext = context.applicationContext
    private val prefs = Prefs(appContext)
    private val proxyRepository = ProxyRepository.get(appContext)
    private val credentialsStore = CredentialsStore.getInstance(appContext)
    private val logger = ConnLogger.getInstance(appContext)
    private val sshClient = SshClient(appContext, logger)
    private val connectivityObserver = ConnectivityObserver(appContext)
    private val gitHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }
    private val gitFetcher by lazy { GitEndpointFetcher(appContext, gitHttpClient) }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val isDebugBuild: Boolean =
        (appContext.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private val _snapshot = MutableStateFlow(
        Snapshot(
            state = State.Idle,
            endpoint = proxyRepository.current(),
            attempt = 0,
            lastFailure = null,
            lastSuccessAtMillis = null,
            forceIpv4 = prefs.forceIpv4,
            connectTimeoutMillis = prefs.sshConnectTimeoutMillis(),
            socketTimeoutMillis = prefs.sshSocketTimeoutMillis(),
            keepAliveIntervalSeconds = prefs.sshKeepAliveIntervalSeconds,
        )
    )
    val snapshot: StateFlow<Snapshot> = _snapshot.asStateFlow()

    private val _ready = MutableStateFlow(false)
    val ready: StateFlow<Boolean> = _ready.asStateFlow()

    private val wakeChannel = Channel<Unit>(capacity = Channel.CONFLATED)
    private var endpointWatcherJob = scope.launch { observeEndpoints() }
    private var connectionJob = scope.launch { connectionLoop() }
    private var listenJob = scope.launch { }
    private var gitRefreshJob: Job? = null
    private var lastGitRefreshAt = 0L
    @Volatile
    private var started = true
    private var currentTunnel: SshClient.ActiveTunnel? = null
    private val suppressForwarderErrors = AtomicBoolean(false)
    private val lastLocalDirectSuccessAt = AtomicLong(0L)
    private val manualOverrideFallbackLoggedAt = AtomicLong(0L)

    init {
        scope.launch {
            val repoConfigured = credentialsStore.gitRepoUrl()?.trim()?.isNotEmpty() == true
            if (repoConfigured) {
                EndpointSyncWorker.enqueueImmediate(appContext)
                maybeRefreshEndpointFromGit(_snapshot.value.endpoint)
            }
        }
    }

    fun ensureStarted() {
        if (started) return
        started = true
        connectivityObserver.start()
        endpointWatcherJob = scope.launch { observeEndpoints() }
        connectionJob = scope.launch { connectionLoop() }
    }

    suspend fun stop() {
        if (!started) return
        started = false
        _state.value = State.Stopping
        connectivityObserver.stop()
        connectionJob.cancelAndJoin()
        endpointWatcherJob.cancelAndJoin()
        gitRefreshJob?.cancel()
        gitRefreshJob = null
        lastGitRefreshAt = 0L
        suppressForwarderErrors.set(true)
        listenJob.cancel()
        closeCurrentTunnel()
        suppressForwarderErrors.set(false)
        _ready.value = false
        _state.value = State.Idle
        _snapshot.value = _snapshot.value.copy(
            state = State.Idle,
            attempt = 0,
            endpoint = proxyRepository.current()
        )
    }

    fun forceReconnect(reason: String? = null) {
        if (!started) return
        loggerScopeLog(ConnEvent.Level.INFO, ConnEvent.Phase.OTHER, "Force reconnect requested: ${reason ?: "-"}")
        scope.launch {
            closeCurrentTunnel()
            wakeChannel.trySend(Unit)
        }
    }

    suspend fun testConnection(): Result<Unit> = withContext(Dispatchers.IO) {
        val endpoint = _snapshot.value.endpoint ?: return@withContext Result.failure(
            IllegalStateException("Nenhum endpoint configurado no momento")
        )
        val params = try {
            buildTunnelParams(endpoint, attempt = 1)
        } catch (config: TunnelConfigurationException) {
            return@withContext Result.failure(config)
        }
        return@withContext runCatching {
            sshClient.testHandshake(params)
        }
    }

    fun currentEndpoint(): ProxyEndpoint? = _snapshot.value.endpoint

    private suspend fun observeEndpoints() {
        proxyRepository.refresh()
        proxyRepository.endpointFlow.collectLatest { endpoint ->
            val previous = _snapshot.value.endpoint
            _snapshot.value = _snapshot.value.copy(endpoint = endpoint)
            if (endpoint == null) {
                updateState(State.WaitingForEndpoint, attempt = 0)
                return@collectLatest
            }
            if (!sameEndpoint(previous, endpoint)) {
                loggerScopeLog(
                    ConnEvent.Level.INFO,
                    ConnEvent.Phase.OTHER,
                    "Endpoint updated via ${endpoint.source.name}: ${endpoint.host}:${endpoint.port}",
                    endpoint = endpoint
                )
                scope.launch {
                    if (currentTunnel != null) {
                        closeCurrentTunnel()
                    }
                    wakeChannel.trySend(Unit)
                }
            }
        }
    }

    private suspend fun connectionLoop() {
        connectivityObserver.start()
        var attempt = 0
        var backoffMillis = INITIAL_BACKOFF_MS
        while (scope.isActive) {
            if (!started) {
                delayWithWake(backoffMillis)
                continue
            }
            val endpoint = _snapshot.value.endpoint
            if (endpoint == null) {
                updateState(State.WaitingForEndpoint, attempt)
                EndpointSyncWorker.enqueueImmediate(appContext)
                maybeRefreshEndpointFromGit(endpoint)
                delayWithWake(WAIT_ENDPOINT_BACKOFF_MS)
                attempt = 0
                backoffMillis = INITIAL_BACKOFF_MS
                continue
            }
            val manualLocalOnly = endpoint.source == ProxyEndpointSource.MANUAL && prefs.sshHost.isNullOrBlank()
            if (manualLocalOnly) {
                if (shouldBypassForLocal()) {
                    enterLocalBypass(endpoint)
                } else {
                    updateState(State.WaitingForNetwork(endpoint), attempt = 0)
                }
                attempt = 0
                backoffMillis = INITIAL_BACKOFF_MS
                delayWithWake(LOCAL_BYPASS_CHECK_INTERVAL_MS)
                continue
            }
            if (!ensureNetworkAvailable(endpoint)) {
                attempt = 0
                backoffMillis = INITIAL_BACKOFF_MS
                continue
            }
            if (shouldBypassForLocal()) {
                enterLocalBypass(endpoint)
                attempt = 0
                backoffMillis = INITIAL_BACKOFF_MS
                delayWithWake(LOCAL_BYPASS_CHECK_INTERVAL_MS)
                continue
            } else {
                leaveLocalBypassIfNeeded(endpoint)
            }

            attempt += 1
            updateState(State.Connecting(endpoint, attempt), attempt)

            val params = try {
                buildTunnelParams(endpoint, attempt)
            } catch (configError: TunnelConfigurationException) {
                handleFailure(endpoint, attempt, configError)
                delayWithWake(CONFIG_ERROR_BACKOFF_MS)
                continue
            }

            val tunnel = runCatching { sshClient.openTunnel(params) }
                .onFailure { failure ->
                    handleFailure(endpoint, attempt, failure)
                }
                .getOrNull()

            if (tunnel == null) {
                val interrupted = delayWithWake(backoffMillis)
                backoffMillis = nextBackoff(backoffMillis)
                if (interrupted) {
                    attempt = 0
                    backoffMillis = INITIAL_BACKOFF_MS
                }
                continue
            }

            if (!tunnel.isBound()) {
                tunnel.close()
                handleFailure(
                    endpoint,
                    attempt,
                    IllegalStateException("Forwarder was not able to bind local port ${params.localPort}")
                )
                delayWithWake(backoffMillis)
                backoffMillis = nextBackoff(backoffMillis)
                continue
            }

            onTunnelConnected(endpoint, tunnel)
            attempt = 0
            backoffMillis = INITIAL_BACKOFF_MS
        }
    }

    private suspend fun ensureNetworkAvailable(endpoint: ProxyEndpoint): Boolean {
        val status = connectivityObserver.status.value
        return if (status == ConnectivityObserver.Status.AVAILABLE) {
            true
        } else {
            updateState(State.WaitingForNetwork(endpoint), attempt = 0)
            connectivityObserver.status
                .filter { it == ConnectivityObserver.Status.AVAILABLE }
                .first()
            true
        }
    }

    private suspend fun onTunnelConnected(endpoint: ProxyEndpoint, tunnel: SshClient.ActiveTunnel) {
        currentTunnel = tunnel
        _ready.value = true
        if (isManualSshOverrideActive()) {
            prefs.manualSshOverrideFailureStartedAt = 0L
        }
        val connectedAt = System.currentTimeMillis()
        updateState(State.Connected(endpoint, connectedAt), attempt = 0, lastSuccessAt = connectedAt)
        loggerScopeLog(
            ConnEvent.Level.INFO,
            ConnEvent.Phase.FORWARD,
            "Tunnel is active via ${endpoint.host}:${endpoint.port}",
            endpoint = endpoint
        )
        listenJob.cancel()
        listenJob = scope.launch {
            try {
                tunnel.listenBlocking()
                if (!suppressForwarderErrors.get()) {
                    loggerScopeLog(
                        ConnEvent.Level.WARN,
                        ConnEvent.Phase.FORWARD,
                        "Tunnel closed unexpectedly",
                        endpoint = endpoint
                    )
                }
            } catch (cancel: CancellationException) {
                if (!suppressForwarderErrors.get()) {
                    loggerScopeLog(
                        ConnEvent.Level.INFO,
                        ConnEvent.Phase.FORWARD,
                        "Tunnel listener cancelled",
                        endpoint = endpoint
                    )
                }
                throw cancel
            } catch (error: Throwable) {
                if (!suppressForwarderErrors.get()) {
                    loggerScopeLog(
                        ConnEvent.Level.ERROR,
                        ConnEvent.Phase.FORWARD,
                        "Tunnel listener error: ${error.message}",
                        endpoint = endpoint,
                        throwable = error
                    )
                    updateState(
                        State.Failed(
                            endpoint = endpoint,
                            attempt = 1,
                            phase = ConnEvent.Phase.FORWARD,
                            message = error.message ?: "Erro desconhecido",
                            throwableClass = error::class.java.name,
                            occurredAtMillis = System.currentTimeMillis()
                        ),
                        attempt = 0,
                        lastFailure = Failure(
                            endpoint = endpoint,
                            attempt = 1,
                            phase = ConnEvent.Phase.FORWARD,
                            message = error.message ?: "Erro desconhecido",
                            throwableClass = error::class.java.name,
                            occurredAtMillis = System.currentTimeMillis()
                        )
                    )
                }
            } finally {
                _ready.value = false
                wakeChannel.trySend(Unit)
            }
        }
        listenJob.join()
        closeCurrentTunnel()
        suppressForwarderErrors.set(false)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun delayWithWake(durationMillis: Long): Boolean {
        if (durationMillis <= 0) return false
        return select {
            wakeChannel.onReceive { true }
            onTimeout(durationMillis) { false }
        }
    }

    private fun nextBackoff(current: Long): Long {
        val doubled = current * 2
        val capped = min(doubled, MAX_BACKOFF_MS)
        val jitter = Random.nextLong(0L, capped / 2)
        return capped + jitter
    }

    private suspend fun handleFailure(endpoint: ProxyEndpoint?, attempt: Int, failure: Throwable) {
        if (shouldBypassForLocal()) {
            enterLocalBypass(endpoint)
            return
        }
        val phase = if (failure is SshClient.TunnelConnectException) failure.phase else ConnEvent.Phase.OTHER
        val cause = if (failure is SshClient.TunnelConnectException) failure.cause ?: failure else failure
        val message = cause.message ?: cause::class.java.simpleName
        val occurredAt = System.currentTimeMillis()
        updateState(
            State.Failed(
                endpoint = endpoint,
                attempt = attempt,
                phase = phase,
                message = message,
                throwableClass = cause::class.java.name,
                occurredAtMillis = occurredAt
            ),
            attempt = attempt,
            lastFailure = Failure(
                endpoint = endpoint,
                attempt = attempt,
                phase = phase,
                message = message,
                throwableClass = cause::class.java.name,
                occurredAtMillis = occurredAt
            )
        )
        logger.log(
            ConnEvent(
                timestampMillis = occurredAt,
                level = ConnEvent.Level.ERROR,
                phase = phase,
                message = "Attempt $attempt failed: $message",
                endpoint = endpoint,
                attempt = attempt,
                throwableClass = cause::class.java.name,
                throwableMessage = cause.message,
                stacktracePreview = cause.stackTraceToString().take(MAX_STACKTRACE_CHARS)
            )
        )
        val manualOverrideActive = isManualSshOverrideActive()
        if (manualOverrideActive) {
            val failureWindowMs = Timing.MANUAL_SSH_OVERRIDE_MIN_FAILURE_MS
            val failureStartedAt = prefs.manualSshOverrideFailureStartedAt
            val elapsed = if (failureStartedAt > 0L) occurredAt - failureStartedAt else 0L
            if (failureStartedAt == 0L || elapsed < failureWindowMs) {
                if (failureStartedAt == 0L) {
                    prefs.manualSshOverrideFailureStartedAt = occurredAt
                }
                if (manualOverrideFallbackLoggedAt.get() != prefs.manualSshOverrideFailureStartedAt) {
                    manualOverrideFallbackLoggedAt.set(prefs.manualSshOverrideFailureStartedAt)
                    loggerScopeLog(
                        ConnEvent.Level.INFO,
                        ConnEvent.Phase.OTHER,
                        "Manual SSH override ativo; aguardando ${failureWindowMs / 1000}s de tentativas antes do fallback",
                        endpoint = endpoint
                    )
                }
                return
            }
            prefs.lastManualSshConfigAt = 0L
            prefs.manualSshOverrideFailureStartedAt = 0L
            manualOverrideFallbackLoggedAt.set(0L)
            val fallbackApplied = prefs.consumePendingFallbackSshIfEligible(0L, occurredAt)
            if (fallbackApplied) {
                loggerScopeLog(
                    ConnEvent.Level.INFO,
                    ConnEvent.Phase.OTHER,
                    "Endpoint SSH fallback reaplicado após falhas prolongadas no override manual",
                    endpoint = endpoint
                )
            }
        } else {
            prefs.manualSshOverrideFailureStartedAt = 0L
            manualOverrideFallbackLoggedAt.set(0L)
        }
        EndpointSyncWorker.enqueueImmediate(appContext)
        maybeRefreshEndpointFromGit(endpoint)
    }

    private fun maybeRefreshEndpointFromGit(endpoint: ProxyEndpoint?) {
        if (isManualSshOverrideActive()) {
            return
        }
        val repoUrl = credentialsStore.gitRepoUrl()?.trim()?.takeIf { it.isNotEmpty() } ?: return
        val filePath = credentialsStore.gitFilePath()?.trim()?.takeIf { it.isNotEmpty() } ?: return
        if (endpoint != null &&
            (endpoint.source == ProxyEndpointSource.MANUAL || endpoint.source == ProxyEndpointSource.DEFAULT)
        ) {
            return
        }
        val now = System.currentTimeMillis()
        val lastNtfyAt = prefs.lastNtfyEndpointAt
        if (lastNtfyAt > 0L && now - lastNtfyAt <= STALE_NTFY_THRESHOLD_MS) {
            return
        }
        if (gitRefreshJob?.isActive == true) return
        if (now - lastGitRefreshAt < GIT_REFRESH_COOLDOWN_MS) return

        lastGitRefreshAt = now
        gitRefreshJob = scope.launch {
            val params = GitEndpointFetcher.Params(
                repoUrl = repoUrl,
                branch = credentialsStore.gitBranch()?.takeIf { it.isNotBlank() } ?: "main",
                filePath = filePath,
                privateKey = credentialsStore.gitPrivateKey()
            )
            val result = runCatching { gitFetcher.fetch(params) }
                .onFailure { error ->
                    loggerScopeLog(
                        ConnEvent.Level.WARN,
                        ConnEvent.Phase.OTHER,
                        "Git endpoint refresh falhou: ${error.message ?: error::class.java.simpleName}",
                        endpoint = endpoint,
                        throwable = error
                    )
                }
                .getOrNull()

            when (result) {
                is RemoteEndpointResult.Success -> {
                    loggerScopeLog(
                        ConnEvent.Level.INFO,
                        ConnEvent.Phase.OTHER,
                        "Endpoint atualizado via git: ${result.endpoint.host}:${result.endpoint.port}",
                        endpoint = result.endpoint
                    )
                    proxyRepository.updateEndpoint(result.endpoint)
                }

                is RemoteEndpointResult.InvalidFormat -> {
                    proxyRepository.reportError("Arquivo git inválido")
                }

                is RemoteEndpointResult.AuthError -> {
                    proxyRepository.reportAuthFailure("Falha ao autenticar repositório git (${result.code})")
                }

                is RemoteEndpointResult.HttpError -> {
                    proxyRepository.reportError("Erro HTTP ${result.code} ao buscar endpoint git")
                }

                RemoteEndpointResult.Empty -> {
                    proxyRepository.reportError("Arquivo git vazio")
                }

                is RemoteEndpointResult.NetworkError -> {
                    proxyRepository.reportError("Erro de rede ao buscar endpoint git")
                }

                null -> Unit
            }
        }
    }

    private fun shouldBypassForLocal(): Boolean {
        val lastSuccessAt = lastLocalDirectSuccessAt.get()
        if (lastSuccessAt <= 0L) return false
        val now = System.currentTimeMillis()
        val delta = now - lastSuccessAt
        return delta in 0..LOCAL_BYPASS_FRESHNESS_MS
    }

    private fun enterLocalBypass(endpoint: ProxyEndpoint?) {
        _ready.value = false
        val current = _state.value
        val since = if (current is State.LocalBypass && sameEndpoint(current.endpoint, endpoint)) {
            current.sinceMillis
        } else {
            System.currentTimeMillis()
        }
        if (current is State.LocalBypass && sameEndpoint(current.endpoint, endpoint)) {
            return
        }
        updateState(State.LocalBypass(endpoint, since), attempt = 0)
        if (current !is State.LocalBypass) {
            loggerScopeLog(
                ConnEvent.Level.INFO,
                ConnEvent.Phase.OTHER,
                "Ignorando túnel SSH: conexão local ativa",
                endpoint = endpoint
            )
        }
    }

    private fun leaveLocalBypassIfNeeded(endpoint: ProxyEndpoint) {
        val current = _state.value
        if (current is State.LocalBypass) {
            loggerScopeLog(
                ConnEvent.Level.INFO,
                ConnEvent.Phase.OTHER,
                "Conexão local indisponível; retomando tentativas do túnel SSH",
                endpoint = endpoint
            )
        }
    }

    fun registerLocalDirectSuccess() {
        lastLocalDirectSuccessAt.set(System.currentTimeMillis())
        enterLocalBypass(_snapshot.value.endpoint)
        if (_state.value is State.LocalBypass) {
            wakeChannel.trySend(Unit)
        }
    }

    fun registerLocalDirectFailure() {
        lastLocalDirectSuccessAt.set(0L)
        if (_state.value is State.LocalBypass) {
            wakeChannel.trySend(Unit)
        }
    }

    private fun updateState(
        newState: State,
        attempt: Int,
        lastFailure: Failure? = _snapshot.value.lastFailure,
        lastSuccessAt: Long? = _snapshot.value.lastSuccessAtMillis,
    ) {
        _state.value = newState
        _snapshot.value = _snapshot.value.copy(
            state = newState,
            attempt = attempt,
            lastFailure = lastFailure,
            lastSuccessAtMillis = lastSuccessAt,
            forceIpv4 = prefs.forceIpv4,
            connectTimeoutMillis = prefs.sshConnectTimeoutMillis(),
            socketTimeoutMillis = prefs.sshSocketTimeoutMillis(),
            keepAliveIntervalSeconds = prefs.sshKeepAliveIntervalSeconds
        )
    }

    private suspend fun closeCurrentTunnel() {
        val tunnel = currentTunnel ?: return
        suppressForwarderErrors.set(true)
        val job = listenJob
        job.cancel()
        runCatching { tunnel.close() }
        val joined = withTimeoutOrNull(5_000) {
            job.join()
        }
        if (joined == null) {
            loggerScopeLog(
                ConnEvent.Level.WARN,
                ConnEvent.Phase.FORWARD,
                "Timeout aguardando encerramento do listener do túnel",
                endpoint = _snapshot.value.endpoint
            )
        }
        currentTunnel = null
        _ready.value = false
        suppressForwarderErrors.set(false)
    }

    private fun sameEndpoint(a: ProxyEndpoint?, b: ProxyEndpoint?): Boolean {
        if (a == null && b == null) return true
        if (a == null || b == null) return false
        return a.host.equals(b.host, ignoreCase = true) && a.port == b.port && a.source == b.source
    }

    private suspend fun buildTunnelParams(endpoint: ProxyEndpoint, attempt: Int): SshClient.TunnelParams {
        val manualTarget = endpoint.takeIf {
            it.source == ProxyEndpointSource.MANUAL || it.source == ProxyEndpointSource.DEFAULT
        }
        val remoteHost = manualTarget?.host?.trim().takeUnless { it.isNullOrBlank() } ?: prefs.remoteHost.trim()
        val remotePort = manualTarget?.port ?: prefs.remotePort
        val manualOverrideActive = isManualSshOverrideActive()
        val now = System.currentTimeMillis()
        val pendingApplied = if (!manualOverrideActive) {
            prefs.consumePendingFallbackSshIfEligible(Timing.MANUAL_SSH_OVERRIDE_MIN_FAILURE_MS, now)
        } else {
            false
        }
        if (pendingApplied) {
            loggerScopeLog(
                ConnEvent.Level.INFO,
                ConnEvent.Phase.OTHER,
                "Endpoint SSH fallback reaplicado após override manual",
                endpoint = endpoint
            )
        }
        val sshEndpoint = endpoint.takeIf {
            it.source == ProxyEndpointSource.NTFY || it.source == ProxyEndpointSource.FALLBACK
        }
        val manualSshHost = prefs.sshHost?.trim()?.takeUnless { it.isNullOrEmpty() }
        val manualSshPort = prefs.sshPort?.takeIf { it > 0 }
        val sshHostCandidate = when {
            manualOverrideActive && manualSshHost != null -> manualSshHost
            sshEndpoint != null -> sshEndpoint.host
            manualSshHost != null -> manualSshHost
            endpoint.source == ProxyEndpointSource.MANUAL || endpoint.source == ProxyEndpointSource.DEFAULT -> null
            else -> prefs.remoteHost
        }
        val sshPortCandidate = when {
            manualOverrideActive && manualSshPort != null -> manualSshPort
            sshEndpoint != null -> sshEndpoint.port
            manualSshPort != null -> manualSshPort
            endpoint.source == ProxyEndpointSource.MANUAL || endpoint.source == ProxyEndpointSource.DEFAULT -> null
            else -> DEFAULT_SSH_PORT
        }
        val localPort = prefs.localPort
        val fingerprint = credentialsStore.sshFingerprintSha256()
        val connectTimeoutMillis = prefs.sshConnectTimeoutMillis()
        val socketTimeoutMillis = prefs.sshSocketTimeoutMillis()
        val keepAliveSeconds = prefs.sshKeepAliveIntervalSeconds
        val forceIpv4 = prefs.forceIpv4
        if (remoteHost.isBlank()) {
            throw TunnelConfigurationException("Remote host não configurado")
        }
        if (remotePort !in 1..65535) {
            throw TunnelConfigurationException("Porta remota inválida: $remotePort")
        }
        val sshHost = sshHostCandidate?.trim().orEmpty()
        if (sshHost.isEmpty()) {
            throw TunnelConfigurationException("Host SSH não configurado")
        }
        val sshPort = sshPortCandidate ?: throw TunnelConfigurationException("Porta SSH não configurada")
        if (sshPort !in 1..65535) {
            throw TunnelConfigurationException("Porta SSH inválida: $sshPortCandidate")
        }
        return SshClient.TunnelParams(
            sshHost = sshHost,
            sshPort = sshPort,
            sshUser = prefs.sshUser,
            localPort = localPort,
            remoteHost = remoteHost,
            remotePort = remotePort,
            connectTimeoutMillis = connectTimeoutMillis,
            socketTimeoutMillis = socketTimeoutMillis,
            keepAliveIntervalSeconds = keepAliveSeconds,
            fingerprintSha256 = fingerprint,
            usePassword = prefs.usePassword,
            password = prefs.sshPassword,
            privateKeyPem = prefs.sshPrivateKeyPem,
            forceIpv4 = forceIpv4,
            strictHostKey = !isDebugBuild,
            attempt = attempt,
        )
    }

    private fun loggerScopeLog(
        level: ConnEvent.Level,
        phase: ConnEvent.Phase,
        message: String,
        endpoint: ProxyEndpoint? = null,
        throwable: Throwable? = null,
    ) {
        scope.launch {
            logger.log(
                ConnEvent(
                    timestampMillis = System.currentTimeMillis(),
                    level = level,
                    phase = phase,
                    message = message,
                    endpoint = endpoint,
                    throwableClass = throwable?.javaClass?.name,
                    throwableMessage = throwable?.message,
                    stacktracePreview = throwable?.stackTraceToString()?.take(MAX_STACKTRACE_CHARS)
                )
            )
        }
    }

    private fun isManualSshOverrideActive(): Boolean = prefs.lastManualSshConfigAt > 0L

    class TunnelConfigurationException(message: String) : IllegalStateException(message)

    companion object {
        private const val DEFAULT_SSH_PORT = 22
        private const val INITIAL_BACKOFF_MS = 2_000L
        private const val MAX_BACKOFF_MS = 5 * 60 * 1000L
        private const val CONFIG_ERROR_BACKOFF_MS = 10_000L
        private const val WAIT_ENDPOINT_BACKOFF_MS = 5_000L
        private const val MAX_STACKTRACE_CHARS = 4_096
        private const val STALE_NTFY_THRESHOLD_MS = 30_000L
        private const val GIT_REFRESH_COOLDOWN_MS = 20_000L
        private const val LOCAL_BYPASS_FRESHNESS_MS = 60_000L
        private const val LOCAL_BYPASS_CHECK_INTERVAL_MS = 5_000L

        @Volatile
        private var instance: TunnelManager? = null

        fun getInstance(context: Context): TunnelManager {
            return instance ?: synchronized(this) {
                instance ?: TunnelManager(context).also { instance = it }
            }
        }
    }
}
