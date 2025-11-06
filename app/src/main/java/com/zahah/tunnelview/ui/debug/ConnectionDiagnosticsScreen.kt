package com.zahah.tunnelview.ui.debug

import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.zahah.tunnelview.R
import com.zahah.tunnelview.AppLocaleManager
import com.zahah.tunnelview.Prefs
import com.zahah.tunnelview.logging.ConnEvent
import com.zahah.tunnelview.logging.ConnLogger
import com.zahah.tunnelview.ssh.TunnelManager
import com.zahah.tunnelview.storage.CredentialsStore
import com.zahah.tunnelview.ui.theme.AppThemeManager
import com.zahah.tunnelview.ui.theme.TunnelViewTheme
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

class ConnectionDiagnosticsActivity : ComponentActivity() {

    private val tunnelManager by lazy { TunnelManager.getInstance(applicationContext) }
    private val connLogger by lazy { ConnLogger.getInstance(applicationContext) }
    private val prefs by lazy { Prefs(this) }
    private val sharedPrefs: SharedPreferences by lazy {
        getSharedPreferences("prefs", Context.MODE_PRIVATE)
    }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLocaleManager.wrapContext(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val snapshot by tunnelManager.snapshot.collectAsState()
            val events by connLogger.events.collectAsState()
            val snackbarHostState = remember { SnackbarHostState() }
            var isTesting by remember { mutableStateOf(false) }
            val scope = rememberCoroutineScope()
            var themeColorId by remember { mutableStateOf(prefs.themeColorId) }
            var themeModeId by remember { mutableStateOf(prefs.themeModeId) }
            var verboseLogsEnabled by remember { mutableStateOf(prefs.connectionDebugLoggingEnabled) }
            DisposableEffect(Unit) {
                val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
                    when (key) {
                        Prefs.KEY_THEME_COLOR -> themeColorId = prefs.themeColorId
                        Prefs.KEY_THEME_MODE -> themeModeId = prefs.themeModeId
                        Prefs.KEY_CONNECTION_DEBUG_LOGGING -> verboseLogsEnabled = prefs.connectionDebugLoggingEnabled
                    }
                }
                sharedPrefs.registerOnSharedPreferenceChangeListener(listener)
                onDispose { sharedPrefs.unregisterOnSharedPreferenceChangeListener(listener) }
            }

            LaunchedEffect(themeModeId) { AppThemeManager.apply(themeModeId) }
            TunnelViewTheme(themeModeId = themeModeId, colorOptionId = themeColorId) {
                val filteredEvents = remember(events, verboseLogsEnabled) {
                    events.filter { shouldIncludeDiagnosticEvent(it, verboseLogsEnabled) }
                }
                ConnectionDiagnosticsScreen(
                    snapshot = snapshot,
                    events = filteredEvents,
                    snackbarHostState = snackbarHostState,
                    isTesting = isTesting,
                    onTestConnection = {
                        if (isTesting) return@ConnectionDiagnosticsScreen
                        scope.launch {
                            isTesting = true
                            val result = tunnelManager.testConnection()
                            val message = result.fold(
                                onSuccess = { getString(R.string.diagnostics_test_success) },
                                onFailure = {
                                    getString(
                                        R.string.diagnostics_test_failure,
                                        it.message ?: it::class.java.simpleName
                                    )
                                }
                            )
                            snackbarHostState.showSnackbar(message)
                            isTesting = false
                        }
                    },
                    onNavigateBack = { finish() }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectionDiagnosticsScreen(
    snapshot: TunnelManager.Snapshot,
    events: List<ConnEvent>,
    snackbarHostState: SnackbarHostState,
    isTesting: Boolean,
    onTestConnection: () -> Unit,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(text = stringResource(id = R.string.diagnostics_title)) },
                navigationIcon = {
                    Text(
                        text = "◀",
                        modifier = Modifier
                            .padding(horizontal = 16.dp, vertical = 12.dp)
                            .clickableNoRipple(onNavigateBack),
                        style = MaterialTheme.typography.titleLarge
                    )
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                DiagnosticsSummary(snapshot = snapshot)
            }
            item {
                Button(
                    onClick = onTestConnection,
                    enabled = !isTesting,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (isTesting) {
                        CircularProgressIndicator(
                            modifier = Modifier
                                .padding(end = 12.dp)
                                .size(18.dp),
                            strokeWidth = 2.dp
                        )
                        Text(text = stringResource(id = R.string.diagnostics_running_test))
                    } else {
                        Text(text = stringResource(id = R.string.diagnostics_test_button))
                    }
                }
            }
            item {
                Text(
                    text = stringResource(id = R.string.diagnostics_events_title),
                    style = MaterialTheme.typography.titleMedium
                )
            }
            if (events.isEmpty()) {
                item {
                    Text(
                        text = stringResource(id = R.string.diagnostics_no_events),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            } else {
                items(events.takeLast(80).asReversed()) { event ->
                    DiagnosticsEventRow(event)
                }
            }
            item { Spacer(modifier = Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun DiagnosticsSummary(snapshot: TunnelManager.Snapshot) {
    val dateFormat = remember { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM) }
    val context = LocalContext.current
    val prefs = remember { Prefs(context) }
    val credentialsStore = remember { CredentialsStore.getInstance(context) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val endpointText = snapshot.endpoint?.let {
            stringResource(
                id = R.string.diagnostics_endpoint_label,
                it.host,
                it.port,
                it.source.name
            )
        } ?: stringResource(id = R.string.diagnostics_endpoint_missing)
        Text(text = endpointText, style = MaterialTheme.typography.bodyMedium)

        val statusText = when (snapshot.state) {
            is TunnelManager.State.Connected -> stringResource(id = R.string.diagnostics_state_connected)
            is TunnelManager.State.Connecting -> stringResource(id = R.string.diagnostics_state_connecting)
            is TunnelManager.State.LocalBypass -> stringResource(id = R.string.diagnostics_state_local_bypass)
            is TunnelManager.State.WaitingForEndpoint -> stringResource(id = R.string.diagnostics_state_waiting_endpoint)
            is TunnelManager.State.WaitingForNetwork -> stringResource(id = R.string.diagnostics_state_waiting_network)
            is TunnelManager.State.Failed -> stringResource(id = R.string.diagnostics_state_failed)
            TunnelManager.State.Idle -> stringResource(id = R.string.diagnostics_state_idle)
            TunnelManager.State.Stopping -> stringResource(id = R.string.diagnostics_state_idle)
        }
        Text(
            text = stringResource(id = R.string.diagnostics_status, statusText),
            style = MaterialTheme.typography.bodyMedium
        )

        Text(
            text = stringResource(R.string.diagnostics_attempts, snapshot.attempt),
            style = MaterialTheme.typography.bodyMedium
        )
        val httpEnabled = prefs.httpConnectionEnabled
        val httpEnabledText = if (httpEnabled) {
            stringResource(id = R.string.diagnostics_yes)
        } else {
            stringResource(id = R.string.diagnostics_no)
        }
        val httpAddress = prefs.httpAddress.ifBlank { stringResource(id = R.string.diagnostics_value_none) }
        val headerConfig = credentialsStore.httpHeaderConfig()
        val httpHeaderName = headerConfig?.name?.ifBlank { null }
            ?: stringResource(id = R.string.diagnostics_value_none)
        val httpKeyConfiguredText = if (headerConfig?.value?.isNotBlank() == true) {
            stringResource(id = R.string.diagnostics_yes)
        } else {
            stringResource(id = R.string.diagnostics_no)
        }
        Text(
            text = stringResource(R.string.diagnostics_http_enabled, httpEnabledText),
            style = MaterialTheme.typography.bodyMedium
        )
        Text(
            text = stringResource(R.string.diagnostics_http_address, httpAddress),
            style = MaterialTheme.typography.bodyMedium
        )
        Text(
            text = stringResource(R.string.diagnostics_http_header, httpHeaderName),
            style = MaterialTheme.typography.bodyMedium
        )
        Text(
            text = stringResource(R.string.diagnostics_http_key_state, httpKeyConfiguredText),
            style = MaterialTheme.typography.bodyMedium
        )
        val forceIpv4Text = if (snapshot.forceIpv4) {
            stringResource(id = R.string.diagnostics_yes)
        } else {
            stringResource(id = R.string.diagnostics_no)
        }
        Text(
            text = stringResource(id = R.string.diagnostics_force_ipv4, forceIpv4Text),
            style = MaterialTheme.typography.bodyMedium
        )
        Text(
            text = stringResource(
                id = R.string.diagnostics_timeouts,
                snapshot.connectTimeoutMillis / 1000,
                snapshot.socketTimeoutMillis / 1000,
                snapshot.keepAliveIntervalSeconds
            ),
            style = MaterialTheme.typography.bodyMedium
        )
        val lastSuccess = snapshot.lastSuccessAtMillis?.let { dateFormat.format(Date(it)) }
            ?: stringResource(id = R.string.diagnostics_value_none)
        Text(
            text = stringResource(id = R.string.diagnostics_last_success, lastSuccess),
            style = MaterialTheme.typography.bodyMedium
        )
        val failure = snapshot.lastFailure
        val lastFailure = if (failure != null) {
            val whenText = dateFormat.format(Date(failure.occurredAtMillis))
            stringResource(
                id = R.string.diagnostics_last_failure_value,
                failure.phase.name,
                failure.message,
                whenText
            )
        } else {
            stringResource(id = R.string.diagnostics_value_none)
        }
        Text(
            text = stringResource(id = R.string.diagnostics_last_failure, lastFailure),
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

private fun shouldIncludeDiagnosticEvent(
    event: ConnEvent,
    verboseEnabled: Boolean
): Boolean {
    if (event.phase != ConnEvent.Phase.HTTP) return true
    return when (event.level) {
        ConnEvent.Level.ERROR, ConnEvent.Level.WARN -> true
        ConnEvent.Level.INFO, ConnEvent.Level.DEBUG -> verboseEnabled
    }
}

@Composable
private fun DiagnosticsEventRow(event: ConnEvent) {
    val dateFormat = remember { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
    ) {
        Text(
            text = dateFormat.format(Date(event.timestampMillis)),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary
        )
        Text(
            text = "${event.phase.name} · ${event.level.name}",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            text = event.message,
            style = MaterialTheme.typography.bodyMedium
        )
        event.endpoint?.let {
            Text(
                text = "${it.host}:${it.port} (${it.source.name})",
                style = MaterialTheme.typography.bodySmall
            )
        }
        event.throwableMessage?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
private fun Modifier.clickableNoRipple(onClick: () -> Unit): Modifier = this.then(
    clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) {
        onClick()
    }
)

@Preview(showBackground = true)
@Composable
private fun DiagnosticsSummaryPreview() {
    val snapshot = TunnelManager.Snapshot(
        state = TunnelManager.State.Connected(
            endpoint = com.zahah.tunnelview.data.ProxyEndpoint("example.com", 22, com.zahah.tunnelview.data.ProxyEndpointSource.NTFY),
            connectedAtMillis = System.currentTimeMillis()
        ),
        endpoint = com.zahah.tunnelview.data.ProxyEndpoint("example.com", 22, com.zahah.tunnelview.data.ProxyEndpointSource.NTFY),
        attempt = 2,
        lastFailure = null,
        lastSuccessAtMillis = System.currentTimeMillis(),
        forceIpv4 = false,
        connectTimeoutMillis = 20_000,
        socketTimeoutMillis = 20_000,
        keepAliveIntervalSeconds = 20
    )
    TunnelViewTheme {
        DiagnosticsSummary(snapshot)
    }
}
