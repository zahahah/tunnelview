package com.zahah.tunnelview.ui.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.systemBars
import android.provider.OpenableColumns
import android.net.Uri
import com.zahah.tunnelview.AppDefaultsProvider
import com.zahah.tunnelview.AppLocaleManager
import com.zahah.tunnelview.BuildConfig
import com.zahah.tunnelview.PrefKeys
import com.zahah.tunnelview.Prefs
import com.zahah.tunnelview.R
import com.zahah.tunnelview.Timing
import com.zahah.tunnelview.data.ProxyEndpointSource
import com.zahah.tunnelview.parseTcpEndpoint
import com.zahah.tunnelview.storage.CredentialsStore
import com.zahah.tunnelview.appbuilder.AppBuildRequest
import com.zahah.tunnelview.appbuilder.AppBuildResult
import com.zahah.tunnelview.appbuilder.CustomSigningConfig
import com.zahah.tunnelview.appbuilder.TemplateAppBuilder
import kotlinx.coroutines.launch

private enum class SettingsPage { ROOT, REMOTE_UPDATES, SSH, NETWORK, PREFERENCES, APP_BUILDER }

private val PACKAGE_NAME_REGEX = Regex("^[a-zA-Z][A-Za-z0-9_]*(\\.[a-zA-Z][A-Za-z0-9_]*)+")
private const val MIN_SETTINGS_PASSWORD_LENGTH = 4

@Composable
fun SettingsScreen(
    modifier: Modifier = Modifier,
    onSyncNow: () -> Unit = {},
    onTestReconnect: () -> Unit = {},
    onExit: () -> Unit = {},
) {
    val context = LocalContext.current
    val appDefaults = remember { AppDefaultsProvider.defaults(context) }
    val prefs = remember { Prefs(context) }
    val credentialsStore = remember { CredentialsStore.getInstance(context) }
    val sharedPrefs = remember { context.getSharedPreferences("prefs", Context.MODE_PRIVATE) }
    val scope = rememberCoroutineScope()

    var currentPage by rememberSaveable { mutableStateOf(SettingsPage.ROOT.name) }
    var saving by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    var topic by rememberSaveable { mutableStateOf("") }
    var remoteUrl by rememberSaveable { mutableStateOf("") }
    var accessKey by rememberSaveable { mutableStateOf("") }
    var gitRepoUrl by rememberSaveable { mutableStateOf(appDefaults.gitRepoUrl) }
    var gitBranch by rememberSaveable { mutableStateOf("main") }
    var gitFilePath by rememberSaveable { mutableStateOf(appDefaults.gitFilePath) }
    var gitPrivateKey by rememberSaveable { mutableStateOf(appDefaults.gitPrivateKey) }

    var sshHost by rememberSaveable { mutableStateOf("") }
    var sshPort by rememberSaveable { mutableStateOf("") }
    var sshUser by rememberSaveable { mutableStateOf("") }
    var sshPrivateKey by rememberSaveable { mutableStateOf(appDefaults.sshPrivateKey) }
    var usePassword by rememberSaveable { mutableStateOf(false) }
    var sshPassword by rememberSaveable { mutableStateOf("") }
    var fingerprint by rememberSaveable { mutableStateOf("") }

    var localPort by rememberSaveable { mutableStateOf("") }
    var remoteHost by rememberSaveable { mutableStateOf("") }
    var remotePort by rememberSaveable { mutableStateOf("") }
    var localLanHost by rememberSaveable { mutableStateOf("") }
    var localLanPort by rememberSaveable { mutableStateOf("") }

    var cacheLastPage by rememberSaveable { mutableStateOf(false) }
    var persistentNotification by rememberSaveable { mutableStateOf(false) }
    var connectionDebug by rememberSaveable { mutableStateOf(false) }
    var forceIpv4 by rememberSaveable { mutableStateOf(false) }
    var autoSaveEnabled by rememberSaveable { mutableStateOf(prefs.autoSaveSettings) }
    var appLanguage by rememberSaveable { mutableStateOf(prefs.appLanguage) }
    var settingsPasswordEnabled by rememberSaveable { mutableStateOf(!prefs.settingsPassword.isNullOrEmpty()) }
    var settingsPasswordInput by rememberSaveable { mutableStateOf("") }
    var settingsPasswordConfirm by rememberSaveable { mutableStateOf("") }
    var settingsPasswordError by remember { mutableStateOf<String?>(null) }

    val appBuilder = remember { TemplateAppBuilder(context) }
    var builderAppName by rememberSaveable { mutableStateOf("") }
    var builderPackage by rememberSaveable { mutableStateOf("") }
    var builderDefaultHost by rememberSaveable { mutableStateOf(appDefaults.internalHost) }
    var builderDefaultPort by rememberSaveable { mutableStateOf(appDefaults.internalPort) }
    var builderDefaultLocalPort by rememberSaveable { mutableStateOf(appDefaults.localPort) }
    var builderDefaultSshUser by rememberSaveable { mutableStateOf(appDefaults.sshUser) }
    var builderDefaultGitRepo by rememberSaveable { mutableStateOf(appDefaults.gitRepoUrl) }
    var builderDefaultGitFile by rememberSaveable { mutableStateOf(appDefaults.gitFilePath) }
    var builderDefaultSshKey by rememberSaveable { mutableStateOf(appDefaults.sshPrivateKey) }
    var builderDefaultGitKey by rememberSaveable { mutableStateOf(appDefaults.gitPrivateKey) }
    var builderDefaultSettingsPassword by rememberSaveable { mutableStateOf(appDefaults.settingsPassword) }
    var builderResult by remember { mutableStateOf<AppBuildResult?>(null) }
    var builderStatus by remember { mutableStateOf<String?>(null) }
    var builderError by remember { mutableStateOf<String?>(null) }
    var builderBusy by remember { mutableStateOf(false) }
    var builderIconName by remember { mutableStateOf<String?>(null) }
    var builderIconMime by remember { mutableStateOf<String?>(null) }
    var builderIconBytes by remember { mutableStateOf<ByteArray?>(null) }
    var builderUseCustomSigning by rememberSaveable { mutableStateOf(false) }
    var builderSigningAlias by rememberSaveable { mutableStateOf("release") }
    var builderPrivateKeyPem by rememberSaveable { mutableStateOf("") }
    var builderCertificatePem by rememberSaveable { mutableStateOf("") }
    var builderPortError by remember { mutableStateOf<String?>(null) }
    var builderLocalPortError by remember { mutableStateOf<String?>(null) }

    val iconPickerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) {
            return@rememberLauncherForActivityResult
        }
        builderIconBytes = context.contentResolver.openInputStream(uri)?.use { stream ->
            stream.readBytes()
        }
        builderIconMime = context.contentResolver.getType(uri)
        builderIconName = resolveDisplayName(context, uri) ?: uri.lastPathSegment
    }

    var lastEndpoint by remember {
        mutableStateOf(sharedPrefs.getString(PrefKeys.LAST_ENDPOINT, null))
    }
    var lastEndpointSource by remember {
        mutableStateOf(sharedPrefs.getString(PrefKeys.LAST_ENDPOINT_SOURCE, null))
    }

    DisposableEffect(sharedPrefs) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == PrefKeys.LAST_ENDPOINT || key == PrefKeys.LAST_ENDPOINT_SOURCE) {
                lastEndpoint = sharedPrefs.getString(PrefKeys.LAST_ENDPOINT, null)
                lastEndpointSource = sharedPrefs.getString(PrefKeys.LAST_ENDPOINT_SOURCE, null)
            }
            if (key == "sshHost") {
                sshHost = prefs.sshHost.orEmpty()
            }
            if (key == "sshPort") {
                sshPort = prefs.sshPort?.takeIf { it > 0 }?.toString().orEmpty()
            }
        }
        sharedPrefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose {
            sharedPrefs.unregisterOnSharedPreferenceChangeListener(listener)
        }
    }

    LaunchedEffect(prefs, credentialsStore) {
        topic = credentialsStore.ntfyTopic().orEmpty()
        remoteUrl = credentialsStore.remoteFileUrl().orEmpty()
        accessKey = credentialsStore.accessKey().orEmpty()
        val defaultGitRepo = appDefaults.gitRepoUrl
        val defaultGitFile = appDefaults.gitFilePath
        gitRepoUrl = credentialsStore.gitRepoUrl().orEmpty().ifBlank { defaultGitRepo }
        gitBranch = credentialsStore.gitBranch().orEmpty().ifBlank { "main" }
        gitFilePath = credentialsStore.gitFilePath().orEmpty()
            .ifBlank { defaultGitFile }
        gitPrivateKey = credentialsStore.gitPrivateKey().orEmpty()

        sshHost = prefs.sshHost.orEmpty()
        sshPort = prefs.sshPort?.takeIf { it > 0 }?.toString().orEmpty()
        sshUser = prefs.sshUser
        sshPrivateKey = prefs.sshPrivateKeyPem.orEmpty()
        usePassword = prefs.usePassword
        sshPassword = prefs.sshPassword.orEmpty()
        fingerprint = credentialsStore.sshFingerprintSha256().orEmpty()

        localPort = prefs.localPort.toString()
        remoteHost = prefs.remoteHost
        remotePort = prefs.remotePort.takeIf { it > 0 }?.toString().orEmpty()
        val (lanHost, lanPort) = parseLocalEndpoint(prefs.localIpEndpointRaw())
        localLanHost = lanHost.orEmpty()
        localLanPort = lanPort.orEmpty()

        cacheLastPage = prefs.cacheLastPage
        persistentNotification = prefs.persistentNotificationEnabled
        connectionDebug = prefs.connectionDebugLoggingEnabled
        forceIpv4 = prefs.forceIpv4
        autoSaveEnabled = prefs.autoSaveSettings
        appLanguage = prefs.appLanguage
        settingsPasswordEnabled = !prefs.settingsPassword.isNullOrEmpty()
        settingsPasswordInput = ""
        settingsPasswordConfirm = ""
        settingsPasswordError = null
    }

    LaunchedEffect(lastEndpoint, lastEndpointSource) {
        val parsed = lastEndpoint?.let { parseTcpEndpoint(it) } ?: return@LaunchedEffect
        val source = lastEndpointSource?.let { runCatching { ProxyEndpointSource.valueOf(it) }.getOrNull() }
        val manualOverrideActive = prefs.lastManualSshConfigAt > 0L
        val (endpointHost, endpointPort) = parsed
        val shouldOverride = when (source) {
            ProxyEndpointSource.MANUAL,
            ProxyEndpointSource.DEFAULT -> sshHost.isBlank()
            else -> !manualOverrideActive
        }
        if (shouldOverride) {
            sshHost = endpointHost
            sshPort = endpointPort.toString()
        }
    }

    fun triggerSave(showMessage: Boolean) {
        val trimmedPassword = settingsPasswordInput.trim()
        val trimmedPasswordConfirm = settingsPasswordConfirm.trim()
        val passwordRequired = settingsPasswordEnabled
        val existingPassword = prefs.settingsPassword.orEmpty()
        val passwordToPersist = when {
            !passwordRequired -> ""
            trimmedPassword.isNotEmpty() || trimmedPasswordConfirm.isNotEmpty() -> {
                if (trimmedPassword.length < MIN_SETTINGS_PASSWORD_LENGTH) {
                    val error = context.getString(
                        R.string.settings_password_error_length,
                        MIN_SETTINGS_PASSWORD_LENGTH
                    )
                    settingsPasswordError = error
                    message = error
                    return
                }
                if (trimmedPassword != trimmedPasswordConfirm) {
                    val error = context.getString(R.string.settings_password_error_mismatch)
                    settingsPasswordError = error
                    message = error
                    return
                }
                trimmedPassword
            }
            existingPassword.isNotEmpty() -> existingPassword
            else -> {
                val error = context.getString(R.string.settings_password_error_required)
                settingsPasswordError = error
                message = error
                return
            }
        }
        settingsPasswordError = null

        scope.launch {
            saving = true
            runCatching {
                credentialsStore.setNtfyTopic(topic.trim().ifEmpty { null })
                credentialsStore.setRemoteFileUrl(remoteUrl.trim().ifEmpty { null })
                credentialsStore.setAccessKey(accessKey.trim().ifEmpty { null })
                credentialsStore.setGitRepoUrl(gitRepoUrl.trim().ifEmpty { null })
                credentialsStore.setGitBranch(gitBranch.trim().ifEmpty { null })
                credentialsStore.setGitFilePath(gitFilePath.trim().ifEmpty { null })
                credentialsStore.setGitPrivateKey(gitPrivateKey.trim().ifEmpty { null })
                credentialsStore.setSshFingerprintSha256(fingerprint.trim().ifEmpty { null })

                val trimmedSshHost = sshHost.trim().ifEmpty { null }
                val parsedSshPort = sshPort.trim().toIntOrNull()?.takeIf { it > 0 }
                val previousSshHost = prefs.sshHost
                val previousSshPort = prefs.sshPort
                prefs.sshHost = trimmedSshHost
                prefs.sshPort = parsedSshPort
                val manualSshChanged = trimmedSshHost != previousSshHost || parsedSshPort != previousSshPort
                if (manualSshChanged) {
                    val now = System.currentTimeMillis()
                    if (trimmedSshHost.isNullOrBlank() && parsedSshPort == null) {
                        prefs.lastManualSshConfigAt = 0L
                        prefs.manualSshOverrideFailureStartedAt = 0L
                    } else {
                        prefs.lastManualSshConfigAt = now
                        prefs.manualSshOverrideFailureStartedAt = 0L
                    }
                    prefs.pendingFallbackSshHost = null
                    prefs.pendingFallbackSshPort = null
                }
                prefs.sshUser = sshUser.trim()
                val normalizedKey = sshPrivateKey
                    .replace("\r\n", "\n")
                    .trim()
                    .ifEmpty { null }
                prefs.sshPrivateKeyPem = normalizedKey
                prefs.usePassword = usePassword
                prefs.sshPassword = if (usePassword) sshPassword.takeIf { it.isNotEmpty() } else null

                localPort.trim().toIntOrNull()?.let { prefs.localPort = it }
                prefs.remoteHost = remoteHost.trim()
                val remotePortInput = remotePort.trim()
                if (remotePortInput.isEmpty()) {
                    prefs.remotePort = 0
                } else {
                    remotePortInput.toIntOrNull()?.let { prefs.remotePort = it }
                }
                prefs.localIpEndpoint = buildLocalEndpoint(localLanHost.trim(), localLanPort.trim())
                prefs.useManualEndpoint = true

                prefs.cacheLastPage = cacheLastPage
                if (!cacheLastPage) {
                    prefs.cachedHtml = null
                    prefs.cachedBaseUrl = null
                    prefs.cachedHtmlPath = null
                    prefs.cachedArchivePath = null
                    prefs.cachedFullUrl = null
                    prefs.cachedRelativePath = null
                }

                prefs.persistentNotificationEnabled = persistentNotification
                prefs.connectionDebugLoggingEnabled = connectionDebug
                prefs.forceIpv4 = forceIpv4
                prefs.autoSaveSettings = autoSaveEnabled
                prefs.appLanguage = appLanguage
                prefs.settingsPassword = if (passwordRequired) passwordToPersist else ""
            }.onSuccess {
                if (!passwordRequired || trimmedPassword.isNotEmpty()) {
                    settingsPasswordInput = ""
                    settingsPasswordConfirm = ""
                }
                if (showMessage) {
                    message = context.getString(R.string.settings_secure_saved)
                }
            }
            saving = false
        }
    }

    fun triggerAppBuild() {
        val trimmedName = builderAppName.trim()
        val trimmedPackage = builderPackage.trim()
        val trimmedDefaultHost = builderDefaultHost.trim()
        val trimmedDefaultPort = builderDefaultPort.trim()
        val trimmedDefaultLocalPort = builderDefaultLocalPort.trim()
        val trimmedDefaultSshUser = builderDefaultSshUser.trim()
        val trimmedDefaultGitRepo = builderDefaultGitRepo.trim()
        val trimmedDefaultGitFile = builderDefaultGitFile.trim()
        val trimmedDefaultSshKey = builderDefaultSshKey.trim()
        val trimmedDefaultGitKey = builderDefaultGitKey.trim()
        val trimmedDefaultSettingsPassword = builderDefaultSettingsPassword.trim()
        if (!PACKAGE_NAME_REGEX.matches(trimmedPackage)) {
            builderError = context.getString(R.string.app_builder_invalid_package)
            builderStatus = null
            return
        }
        if (builderUseCustomSigning) {
            val privateKey = builderPrivateKeyPem.trim()
            val certificate = builderCertificatePem.trim()
            if (privateKey.isEmpty() || certificate.isEmpty()) {
                builderError = context.getString(R.string.app_builder_missing_signing)
                builderStatus = null
                return
            }
        }
        val portValue = trimmedDefaultPort.toIntOrNull()
        if (trimmedDefaultPort.isNotEmpty() && (portValue == null || portValue !in 1..65535)) {
            val portErrorMessage = context.getString(R.string.app_builder_default_port_error)
            builderPortError = portErrorMessage
            builderError = portErrorMessage
            builderStatus = null
            return
        }
        val localPortValue = trimmedDefaultLocalPort.toIntOrNull()
        if (trimmedDefaultLocalPort.isNotEmpty() && (localPortValue == null || localPortValue !in 1..65535)) {
            val portErrorMessage = context.getString(R.string.app_builder_default_port_error)
            builderLocalPortError = portErrorMessage
            builderError = portErrorMessage
            builderStatus = null
            return
        }
        builderAppName = trimmedName
        builderPackage = trimmedPackage
        builderDefaultHost = trimmedDefaultHost
        builderDefaultPort = trimmedDefaultPort
        builderDefaultLocalPort = trimmedDefaultLocalPort
        builderDefaultSshUser = trimmedDefaultSshUser
        builderDefaultGitRepo = trimmedDefaultGitRepo
        builderDefaultGitFile = trimmedDefaultGitFile
        builderDefaultSshKey = trimmedDefaultSshKey
        builderDefaultGitKey = trimmedDefaultGitKey
        builderDefaultSettingsPassword = trimmedDefaultSettingsPassword
        builderError = null
        builderStatus = context.getString(R.string.app_builder_status_in_progress)
        builderResult = null
        builderBusy = true
        builderPortError = null
        builderLocalPortError = null
        scope.launch {
            runCatching {
                appBuilder.build(
                    AppBuildRequest(
                        appName = trimmedName,
                        packageName = trimmedPackage,
                        defaultInternalHost = trimmedDefaultHost,
                        defaultInternalPort = trimmedDefaultPort,
                        defaultLocalPort = trimmedDefaultLocalPort,
                        defaultSshUser = trimmedDefaultSshUser,
                        defaultGitRepoUrl = trimmedDefaultGitRepo,
                        defaultGitFilePath = trimmedDefaultGitFile,
                        defaultSshPrivateKey = trimmedDefaultSshKey,
                        defaultGitPrivateKey = trimmedDefaultGitKey,
                        defaultSettingsPassword = trimmedDefaultSettingsPassword,
                        iconBytes = builderIconBytes,
                        iconMimeType = builderIconMime,
                        customSigning = if (builderUseCustomSigning) {
                            CustomSigningConfig(
                                alias = builderSigningAlias.trim().ifBlank { "custom" },
                                privateKeyPem = builderPrivateKeyPem.trim(),
                                certificatePem = builderCertificatePem.trim()
                            )
                        } else {
                            null
                        }
                    )
                )
            }.onSuccess { result ->
                builderResult = result
                builderStatus = context.getString(R.string.app_builder_status_ready, result.fileName)
            }.onFailure { throwable ->
                builderResult = null
                builderStatus = null
                builderError = context.getString(
                    R.string.app_builder_status_failed,
                    throwable.localizedMessage ?: throwable.toString()
                )
            }
            builderBusy = false
        }
    }

    fun exitScreen() {
        if (autoSaveEnabled) {
            triggerSave(showMessage = false)
        }
        onExit()
    }

    val baseModifier = modifier.windowInsetsPadding(WindowInsets.systemBars)
    val currentPageEnum = remember(currentPage) { SettingsPage.valueOf(currentPage) }

    BackHandler {
        if (currentPageEnum == SettingsPage.ROOT) {
            exitScreen()
        } else {
            if (autoSaveEnabled) {
                triggerSave(showMessage = false)
            }
            currentPage = SettingsPage.ROOT.name
        }
    }

    when (currentPageEnum) {
        SettingsPage.ROOT -> SettingsRootPage(
            modifier = baseModifier,
            lastEndpoint = lastEndpoint,
            lastEndpointSource = lastEndpointSource,
            message = message,
            saving = saving,
            onSave = { triggerSave(showMessage = true) },
            onOpenRemoteUpdates = { currentPage = SettingsPage.REMOTE_UPDATES.name },
            onOpenSsh = { currentPage = SettingsPage.SSH.name },
            onOpenNetwork = { currentPage = SettingsPage.NETWORK.name },
            onOpenPreferences = { currentPage = SettingsPage.PREFERENCES.name },
            onOpenAppBuilder = { currentPage = SettingsPage.APP_BUILDER.name },
            onExit = ::exitScreen,
        )

        SettingsPage.REMOTE_UPDATES -> RemoteUpdatesPage(
            modifier = baseModifier,
            topic = topic,
            remoteUrl = remoteUrl,
            accessKey = accessKey,
            gitRepoUrl = gitRepoUrl,
            gitBranch = gitBranch,
            gitFilePath = gitFilePath,
            gitPrivateKey = gitPrivateKey,
            lastEndpoint = lastEndpoint,
            lastEndpointSource = lastEndpointSource,
            onBack = {
                if (autoSaveEnabled) triggerSave(showMessage = false)
                currentPage = SettingsPage.ROOT.name
            },
            onTopicChange = { topic = it },
            onRemoteUrlChange = { remoteUrl = it },
            onAccessKeyChange = { accessKey = it },
            onGitRepoChange = { gitRepoUrl = it },
            onGitBranchChange = { gitBranch = it },
            onGitFilePathChange = { gitFilePath = it },
            onGitKeyChange = { gitPrivateKey = it },
            onSyncNow = onSyncNow,
        )

        SettingsPage.SSH -> SshSettingsPage(
            modifier = baseModifier,
            sshHost = sshHost,
            sshPort = sshPort,
            sshUser = sshUser,
            sshPrivateKey = sshPrivateKey,
            usePassword = usePassword,
            sshPassword = sshPassword,
            fingerprint = fingerprint,
            onBack = {
                if (autoSaveEnabled) triggerSave(showMessage = false)
                currentPage = SettingsPage.ROOT.name
            },
            onHostChange = { sshHost = it },
            onPortChange = { sshPort = it },
            onUserChange = { sshUser = it },
            onKeyChange = { sshPrivateKey = it },
            onToggleUsePassword = { checked ->
                usePassword = checked
                if (!checked) sshPassword = ""
            },
            onPasswordChange = { sshPassword = it },
            onFingerprintChange = { fingerprint = it },
        )

        SettingsPage.NETWORK -> NetworkSettingsPage(
            modifier = baseModifier,
            localPort = localPort,
            remoteHost = remoteHost,
            remotePort = remotePort,
            localLanHost = localLanHost,
            localLanPort = localLanPort,
            onBack = {
                if (autoSaveEnabled) triggerSave(showMessage = false)
                currentPage = SettingsPage.ROOT.name
            },
            onLocalPortChange = { localPort = it },
            onRemoteHostChange = { remoteHost = it },
            onRemotePortChange = { remotePort = it },
            onLocalLanHostChange = { localLanHost = it },
            onLocalLanPortChange = { localLanPort = it },
        )

        SettingsPage.PREFERENCES -> PreferencesPage(
            modifier = baseModifier,
            cacheLastPage = cacheLastPage,
            persistentNotification = persistentNotification,
            connectionDebug = connectionDebug,
            forceIpv4 = forceIpv4,
            autoSaveEnabled = autoSaveEnabled,
            languageCode = appLanguage,
            settingsPasswordEnabled = settingsPasswordEnabled,
            settingsPasswordInput = settingsPasswordInput,
            settingsPasswordConfirm = settingsPasswordConfirm,
            settingsPasswordError = settingsPasswordError,
            onBack = {
                if (autoSaveEnabled) triggerSave(showMessage = false)
                currentPage = SettingsPage.ROOT.name
            },
            onCacheChange = { cacheLastPage = it },
            onPersistentNotificationChange = { persistentNotification = it },
            onConnectionDebugChange = { connectionDebug = it },
            onForceIpv4Change = { forceIpv4 = it },
            onAutoSaveChange = { enabled ->
                autoSaveEnabled = enabled
                prefs.autoSaveSettings = enabled
                if (enabled) triggerSave(showMessage = false)
            },
            onSettingsPasswordEnabledChange = { enabled ->
                settingsPasswordEnabled = enabled
                settingsPasswordError = null
                if (!enabled) {
                    settingsPasswordInput = ""
                    settingsPasswordConfirm = ""
                }
            },
            onSettingsPasswordInputChange = {
                settingsPasswordInput = it
                settingsPasswordError = null
            },
            onSettingsPasswordConfirmChange = {
                settingsPasswordConfirm = it
                settingsPasswordError = null
            },
            onLanguageChange = { code ->
                if (appLanguage != code) {
                    appLanguage = code
                    AppLocaleManager.applyLanguage(context, code)
                    message = context.getString(R.string.settings_language_updated)
                }
            },
            onTestReconnect = onTestReconnect
        )

        SettingsPage.APP_BUILDER -> AppBuilderPage(
            modifier = baseModifier,
            appName = builderAppName,
            packageName = builderPackage,
            packageValid = builderPackage.isBlank() || PACKAGE_NAME_REGEX.matches(builderPackage),
            statusMessage = builderStatus,
            errorMessage = builderError,
            resultPath = builderResult?.fileName,
            isBuilding = builderBusy,
            selectedIconName = builderIconName,
            useCustomSigning = builderUseCustomSigning,
            defaultInternalHost = builderDefaultHost,
            defaultInternalPort = builderDefaultPort,
            defaultLocalPort = builderDefaultLocalPort,
            defaultSshUser = builderDefaultSshUser,
            defaultGitRepoUrl = builderDefaultGitRepo,
            defaultGitFilePath = builderDefaultGitFile,
            defaultPortError = builderPortError,
            defaultLocalPortError = builderLocalPortError,
            defaultSshKey = builderDefaultSshKey,
            defaultGitKey = builderDefaultGitKey,
            defaultSettingsPassword = builderDefaultSettingsPassword,
            onBack = {
                if (!builderBusy) {
                    currentPage = SettingsPage.ROOT.name
                }
            },
            onAppNameChange = { builderAppName = it },
            onPackageChange = { builderPackage = it },
            onDefaultHostChange = { builderDefaultHost = it },
            onDefaultPortChange = {
                builderDefaultPort = it
                builderPortError = null
            },
            onDefaultLocalPortChange = {
                builderDefaultLocalPort = it
                builderLocalPortError = null
            },
            onDefaultSshUserChange = { builderDefaultSshUser = it },
            onDefaultGitRepoChange = { builderDefaultGitRepo = it },
            onDefaultGitFileChange = { builderDefaultGitFile = it },
            onDefaultSshKeyChange = { builderDefaultSshKey = it },
            onDefaultGitKeyChange = { builderDefaultGitKey = it },
            onDefaultSettingsPasswordChange = { builderDefaultSettingsPassword = it },
            onPickIcon = { iconPickerLauncher.launch("image/*") },
            onToggleCustomSigning = { builderUseCustomSigning = it },
            signingAlias = builderSigningAlias,
            onSigningAliasChange = { builderSigningAlias = it },
            privateKeyPem = builderPrivateKeyPem,
            onPrivateKeyChange = { builderPrivateKeyPem = it },
            certificatePem = builderCertificatePem,
            onCertificateChange = { builderCertificatePem = it },
            onBuild = { triggerAppBuild() },
            onInstall = builderResult?.let { result -> { appBuilder.install(result.downloadUri) } }
        )
    }
}

@Composable
private fun SettingsRootPage(
    modifier: Modifier,
    lastEndpoint: String?,
    lastEndpointSource: String?,
    message: String?,
    saving: Boolean,
    onSave: () -> Unit,
    onOpenRemoteUpdates: () -> Unit,
    onOpenSsh: () -> Unit,
    onOpenNetwork: () -> Unit,
    onOpenPreferences: () -> Unit,
    onOpenAppBuilder: () -> Unit,
    onExit: () -> Unit,
) {
    val endpointText = lastEndpoint?.let { parseTcpEndpoint(it) }?.let { (host, port) ->
        val source = lastEndpointSource?.let { runCatching { ProxyEndpointSource.valueOf(it) }.getOrNull() }
        val sourceLabel = source?.let {
            when (it) {
                ProxyEndpointSource.NTFY -> stringResource(id = R.string.settings_section_ntfy_title)
                ProxyEndpointSource.FALLBACK -> stringResource(id = R.string.settings_endpoint_source_fallback)
                ProxyEndpointSource.MANUAL -> stringResource(id = R.string.settings_endpoint_source_manual)
                ProxyEndpointSource.DEFAULT -> stringResource(id = R.string.settings_endpoint_source_default)
            }
        } ?: "-"
        stringResource(id = R.string.diagnostics_endpoint_label, host, port, sourceLabel)
    } ?: stringResource(id = R.string.label_ntfy_endpoint_placeholder)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(max = 10_000.dp)
            .padding(horizontal = 16.dp, vertical = 16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        BackHeader(
            title = stringResource(id = R.string.settings_sections_title),
            onBack = onExit
        )
        Text(
            text = endpointText,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        SettingsNavigationCard(
            title = stringResource(id = R.string.settings_secure_section_title),
            description = stringResource(id = R.string.settings_remote_updates_description),
            onClick = onOpenRemoteUpdates
        )
        SettingsNavigationCard(
            title = stringResource(id = R.string.settings_section_ssh_title),
            description = stringResource(id = R.string.settings_section_ssh_description),
            onClick = onOpenSsh
        )
        SettingsNavigationCard(
            title = stringResource(id = R.string.settings_section_network_title),
            description = stringResource(id = R.string.settings_section_network_description),
            onClick = onOpenNetwork
        )
        SettingsNavigationCard(
            title = stringResource(id = R.string.settings_section_preferences_title),
            description = stringResource(id = R.string.settings_section_preferences_description),
            onClick = onOpenPreferences
        )
        SettingsNavigationCard(
            title = stringResource(id = R.string.settings_section_builder_title),
            description = stringResource(id = R.string.settings_section_builder_description),
            onClick = onOpenAppBuilder
        )
        message?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary
            )
        }
        Button(
            modifier = Modifier.fillMaxWidth(),
            enabled = !saving,
            onClick = onSave
        ) {
            Text(text = stringResource(id = R.string.settings_secure_save))
        }
    }
}

@Composable
private fun RemoteUpdatesPage(
    modifier: Modifier,
    topic: String,
    remoteUrl: String,
    accessKey: String,
    gitRepoUrl: String,
    gitBranch: String,
    gitFilePath: String,
    gitPrivateKey: String,
    lastEndpoint: String?,
    lastEndpointSource: String?,
    onBack: () -> Unit,
    onTopicChange: (String) -> Unit,
    onRemoteUrlChange: (String) -> Unit,
    onAccessKeyChange: (String) -> Unit,
    onGitRepoChange: (String) -> Unit,
    onGitBranchChange: (String) -> Unit,
    onGitFilePathChange: (String) -> Unit,
    onGitKeyChange: (String) -> Unit,
    onSyncNow: () -> Unit,
) {
    var ntfyExpanded by rememberSaveable { mutableStateOf(true) }
    var remoteFileExpanded by rememberSaveable { mutableStateOf(false) }
    var gitExpanded by rememberSaveable { mutableStateOf(true) }

    val endpointText = lastEndpoint?.let { parseTcpEndpoint(it) }?.let { (host, port) ->
        val source = lastEndpointSource?.let { runCatching { ProxyEndpointSource.valueOf(it) }.getOrNull() }
        val sourceLabel = source?.let {
            when (it) {
                ProxyEndpointSource.NTFY -> stringResource(id = R.string.settings_section_ntfy_title)
                ProxyEndpointSource.FALLBACK -> stringResource(id = R.string.settings_endpoint_source_fallback)
                ProxyEndpointSource.MANUAL -> stringResource(id = R.string.settings_endpoint_source_manual)
                ProxyEndpointSource.DEFAULT -> stringResource(id = R.string.settings_endpoint_source_default)
            }
        } ?: "-"
        stringResource(id = R.string.diagnostics_endpoint_label, host, port, sourceLabel)
    } ?: stringResource(id = R.string.label_ntfy_endpoint_placeholder)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(max = 10_000.dp)
            .padding(horizontal = 16.dp, vertical = 16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        BackHeader(
            title = stringResource(id = R.string.settings_secure_section_title),
            onBack = onBack
        )
        Text(
            text = endpointText,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        ExpandableSettingsSection(
            title = stringResource(id = R.string.settings_section_ntfy_title),
            description = stringResource(id = R.string.settings_section_ntfy_description),
            expanded = ntfyExpanded,
            onToggle = { ntfyExpanded = !ntfyExpanded }
        ) {
            OutlinedTextField(
                modifier = Modifier.fillMaxWidth(),
                value = topic,
                onValueChange = onTopicChange,
                label = { Text(stringResource(id = R.string.settings_ntfy_topic_label)) },
                placeholder = { Text(stringResource(id = R.string.settings_ntfy_topic_placeholder)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri)
            )
        }

        ExpandableSettingsSection(
            title = stringResource(id = R.string.settings_section_remote_file_title),
            description = stringResource(id = R.string.settings_section_remote_file_description),
            expanded = remoteFileExpanded,
            onToggle = { remoteFileExpanded = !remoteFileExpanded }
        ) {
            OutlinedTextField(
                modifier = Modifier.fillMaxWidth(),
                value = remoteUrl,
                onValueChange = onRemoteUrlChange,
                label = { Text(stringResource(id = R.string.settings_remote_url_label)) },
                placeholder = { Text(stringResource(id = R.string.settings_remote_url_placeholder)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri)
            )
            Spacer(modifier = Modifier.height(12.dp))
            OutlinedTextField(
                modifier = Modifier.fillMaxWidth(),
                value = accessKey,
                onValueChange = onAccessKeyChange,
                label = { Text(stringResource(id = R.string.settings_remote_key_label)) },
                placeholder = { Text(stringResource(id = R.string.settings_remote_key_placeholder)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password)
            )
        }

        ExpandableSettingsSection(
            title = stringResource(id = R.string.settings_git_section_title),
            description = stringResource(id = R.string.settings_section_git_description),
            expanded = gitExpanded,
            onToggle = { gitExpanded = !gitExpanded }
        ) {
            OutlinedTextField(
                modifier = Modifier.fillMaxWidth(),
                value = gitRepoUrl,
                onValueChange = onGitRepoChange,
                label = { Text(stringResource(id = R.string.settings_git_repo_label)) },
                placeholder = { Text(stringResource(id = R.string.settings_git_repo_placeholder)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri)
            )
            Spacer(modifier = Modifier.height(12.dp))
            OutlinedTextField(
                modifier = Modifier.fillMaxWidth(),
                value = gitBranch,
                onValueChange = onGitBranchChange,
                label = { Text(stringResource(id = R.string.settings_git_branch_label)) },
                placeholder = { Text(stringResource(id = R.string.settings_git_branch_placeholder)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text)
            )
            Spacer(modifier = Modifier.height(12.dp))
            OutlinedTextField(
                modifier = Modifier.fillMaxWidth(),
                value = gitFilePath,
                onValueChange = onGitFilePathChange,
                label = { Text(stringResource(id = R.string.settings_git_file_label)) },
                placeholder = { Text(stringResource(id = R.string.settings_git_file_placeholder)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text)
            )
            Spacer(modifier = Modifier.height(12.dp))
            OutlinedTextField(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 120.dp),
                value = gitPrivateKey,
                onValueChange = onGitKeyChange,
                label = { Text(stringResource(id = R.string.settings_git_key_label)) },
                placeholder = { Text(stringResource(id = R.string.settings_git_key_placeholder)) },
                singleLine = false,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text)
            )
        }

        TextButton(
            modifier = Modifier.align(Alignment.End),
            onClick = onSyncNow
        ) {
            Text(text = stringResource(id = R.string.settings_secure_sync_now))
        }

        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(id = R.string.settings_secure_hint),
            style = MaterialTheme.typography.bodySmall
        )
    }
}

@Composable
private fun SshSettingsPage(
    modifier: Modifier,
    sshHost: String,
    sshPort: String,
    sshUser: String,
    sshPrivateKey: String,
    usePassword: Boolean,
    sshPassword: String,
    fingerprint: String,
    onBack: () -> Unit,
    onHostChange: (String) -> Unit,
    onPortChange: (String) -> Unit,
    onUserChange: (String) -> Unit,
    onKeyChange: (String) -> Unit,
    onToggleUsePassword: (Boolean) -> Unit,
    onPasswordChange: (String) -> Unit,
    onFingerprintChange: (String) -> Unit,
) {
    var connectionExpanded by rememberSaveable { mutableStateOf(true) }
    var credentialExpanded by rememberSaveable { mutableStateOf(true) }
    var fingerprintExpanded by rememberSaveable { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(max = 10_000.dp)
            .padding(horizontal = 16.dp, vertical = 16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        BackHeader(
            title = stringResource(id = R.string.settings_section_ssh_title),
            onBack = onBack
        )

        ExpandableSettingsSection(
            title = stringResource(id = R.string.settings_section_ssh_title),
            description = stringResource(id = R.string.settings_section_ssh_description),
            expanded = connectionExpanded,
            onToggle = { connectionExpanded = !connectionExpanded }
        ) {
            OutlinedTextField(
                modifier = Modifier.fillMaxWidth(),
                value = sshHost,
                onValueChange = onHostChange,
                label = { Text(stringResource(id = R.string.hint_ssh_host)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri)
            )
            Spacer(modifier = Modifier.height(12.dp))
            OutlinedTextField(
                modifier = Modifier.fillMaxWidth(),
                value = sshPort,
                onValueChange = onPortChange,
                label = { Text(stringResource(id = R.string.hint_ssh_port)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
            )
            Spacer(modifier = Modifier.height(12.dp))
            OutlinedTextField(
                modifier = Modifier.fillMaxWidth(),
                value = sshUser,
                onValueChange = onUserChange,
                label = { Text(stringResource(id = R.string.hint_ssh_user)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text)
            )
        }

        PreferenceSwitchRow(
            title = stringResource(id = R.string.label_use_password),
            checked = usePassword,
            onCheckedChange = onToggleUsePassword
        )

        ExpandableSettingsSection(
            title = if (usePassword) {
                stringResource(id = R.string.hint_ssh_password)
            } else {
                stringResource(id = R.string.hint_ssh_private_key)
            },
            description = "",
            expanded = credentialExpanded,
            onToggle = { credentialExpanded = !credentialExpanded }
        ) {
            if (usePassword) {
                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = sshPassword,
                    onValueChange = onPasswordChange,
                    label = { Text(stringResource(id = R.string.hint_ssh_password)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password)
                )
            } else {
                OutlinedTextField(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 120.dp),
                    value = sshPrivateKey,
                    onValueChange = onKeyChange,
                    label = { Text(stringResource(id = R.string.hint_ssh_private_key)) },
                    placeholder = { Text(stringResource(id = R.string.settings_git_key_placeholder)) },
                    singleLine = false,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text)
                )
            }
        }

        ExpandableSettingsSection(
            title = stringResource(id = R.string.hint_ssh_fingerprint),
            description = "",
            expanded = fingerprintExpanded,
            onToggle = { fingerprintExpanded = !fingerprintExpanded }
        ) {
            OutlinedTextField(
                modifier = Modifier.fillMaxWidth(),
                value = fingerprint,
                onValueChange = onFingerprintChange,
                label = { Text(stringResource(id = R.string.hint_ssh_fingerprint)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text)
            )
        }
    }
}

@Composable
private fun NetworkSettingsPage(
    modifier: Modifier,
    localPort: String,
    remoteHost: String,
    remotePort: String,
    localLanHost: String,
    localLanPort: String,
    onBack: () -> Unit,
    onLocalPortChange: (String) -> Unit,
    onRemoteHostChange: (String) -> Unit,
    onRemotePortChange: (String) -> Unit,
    onLocalLanHostChange: (String) -> Unit,
    onLocalLanPortChange: (String) -> Unit,
) {
    var tunnelExpanded by rememberSaveable { mutableStateOf(true) }
    var localAccessExpanded by rememberSaveable { mutableStateOf(true) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(max = 10_000.dp)
            .padding(horizontal = 16.dp, vertical = 16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        BackHeader(
            title = stringResource(id = R.string.settings_section_network_title),
            onBack = onBack
        )

        ExpandableSettingsSection(
            title = stringResource(id = R.string.settings_section_network_title),
            description = stringResource(id = R.string.settings_section_network_description),
            expanded = tunnelExpanded,
            onToggle = { tunnelExpanded = !tunnelExpanded }
        ) {
            OutlinedTextField(
                modifier = Modifier.fillMaxWidth(),
                value = localPort,
                onValueChange = onLocalPortChange,
                label = { Text(stringResource(id = R.string.hint_local_port)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
            )
            Spacer(modifier = Modifier.height(12.dp))
            OutlinedTextField(
                modifier = Modifier.fillMaxWidth(),
                value = remoteHost,
                onValueChange = onRemoteHostChange,
                label = { Text(stringResource(id = R.string.hint_remote_host)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri)
            )
            Spacer(modifier = Modifier.height(12.dp))
            OutlinedTextField(
                modifier = Modifier.fillMaxWidth(),
                value = remotePort,
                onValueChange = onRemotePortChange,
                label = { Text(stringResource(id = R.string.hint_remote_port)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
            )
        }

        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.medium,
            tonalElevation = 1.dp
        ) {
            var showLocalInfo by rememberSaveable { mutableStateOf(false) }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                if (showLocalInfo) {
                    AlertDialog(
                        onDismissRequest = { showLocalInfo = false },
                        title = { Text(text = stringResource(id = R.string.direct_access_info_title)) },
                        text = { Text(text = stringResource(id = R.string.direct_access_info)) },
                        confirmButton = {
                            TextButton(onClick = { showLocalInfo = false }) {
                                Text(text = stringResource(id = R.string.dialog_close))
                            }
                        }
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    FilterChip(
                        selected = localAccessExpanded,
                        onClick = { localAccessExpanded = !localAccessExpanded },
                        label = { Text(text = stringResource(id = R.string.direct_access_button_label)) },
                        leadingIcon = if (localAccessExpanded) {
                            {
                                Icon(
                                    imageVector = Icons.Filled.Check,
                                    contentDescription = null
                                )
                            }
                        } else {
                            null
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primary,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                            selectedLeadingIconColor = MaterialTheme.colorScheme.onPrimary
                        )
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                    ) {
                        IconButton(
                            modifier = Modifier.size(32.dp),
                            onClick = { showLocalInfo = true }
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Info,
                                contentDescription = stringResource(id = R.string.direct_access_info_button_cd)
                            )
                        }
                    }
                }
                AnimatedVisibility(visible = localAccessExpanded) {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        OutlinedTextField(
                            modifier = Modifier.fillMaxWidth(),
                            value = localLanHost,
                            onValueChange = onLocalLanHostChange,
                            label = { Text(stringResource(id = R.string.hint_local_lan_host)) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri)
                        )
                        OutlinedTextField(
                            modifier = Modifier.fillMaxWidth(),
                            value = localLanPort,
                            onValueChange = onLocalLanPortChange,
                            label = { Text(stringResource(id = R.string.hint_local_lan_port)) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PreferencesPage(
    modifier: Modifier,
    cacheLastPage: Boolean,
    persistentNotification: Boolean,
    connectionDebug: Boolean,
    forceIpv4: Boolean,
    autoSaveEnabled: Boolean,
    languageCode: String,
    settingsPasswordEnabled: Boolean,
    settingsPasswordInput: String,
    settingsPasswordConfirm: String,
    settingsPasswordError: String?,
    onBack: () -> Unit,
    onCacheChange: (Boolean) -> Unit,
    onPersistentNotificationChange: (Boolean) -> Unit,
    onConnectionDebugChange: (Boolean) -> Unit,
    onForceIpv4Change: (Boolean) -> Unit,
    onAutoSaveChange: (Boolean) -> Unit,
    onSettingsPasswordEnabledChange: (Boolean) -> Unit,
    onSettingsPasswordInputChange: (String) -> Unit,
    onSettingsPasswordConfirmChange: (String) -> Unit,
    onLanguageChange: (String) -> Unit,
    onTestReconnect: () -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(max = 10_000.dp)
            .padding(horizontal = 16.dp, vertical = 16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        BackHeader(
            title = stringResource(id = R.string.settings_section_preferences_title),
            onBack = onBack
        )

        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.medium,
            tonalElevation = 1.dp
        ) {
            val languages = remember { AppLocaleManager.availableLanguages() }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = stringResource(id = R.string.settings_language_label),
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        text = stringResource(id = R.string.settings_language_description),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        languages.forEach { option ->
                            FilterChip(
                                selected = option.code == languageCode,
                                onClick = { onLanguageChange(option.code) },
                                label = { Text(text = stringResource(id = option.titleRes)) }
                            )
                        }
                    }
                }
                PreferenceSwitchRow(
                    title = stringResource(id = R.string.settings_auto_save_label),
                    checked = autoSaveEnabled,
                    onCheckedChange = onAutoSaveChange
                )
                SettingsPasswordSection(
                    enabled = settingsPasswordEnabled,
                    password = settingsPasswordInput,
                    confirmPassword = settingsPasswordConfirm,
                    errorMessage = settingsPasswordError,
                    onEnabledChange = onSettingsPasswordEnabledChange,
                    onPasswordChange = onSettingsPasswordInputChange,
                    onConfirmPasswordChange = onSettingsPasswordConfirmChange
                )
                PreferenceSwitchRow(
                    title = stringResource(id = R.string.label_cache_last_page),
                    checked = cacheLastPage,
                    onCheckedChange = onCacheChange
                )
                PreferenceSwitchRow(
                    title = stringResource(id = R.string.label_persistent_notification),
                    checked = persistentNotification,
                    onCheckedChange = onPersistentNotificationChange
                )
                PreferenceSwitchRow(
                    title = stringResource(id = R.string.label_connection_debug),
                    checked = connectionDebug,
                    onCheckedChange = onConnectionDebugChange
                )
                PreferenceSwitchRow(
                    title = stringResource(id = R.string.label_force_ipv4),
                    checked = forceIpv4,
                    onCheckedChange = onForceIpv4Change
                )
                Button(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = onTestReconnect
                ) {
                    Text(text = stringResource(id = R.string.btn_reconnect))
                }
            }
        }
    }
}

@Composable
private fun SettingsPasswordSection(
    enabled: Boolean,
    password: String,
    confirmPassword: String,
    errorMessage: String?,
    onEnabledChange: (Boolean) -> Unit,
    onPasswordChange: (String) -> Unit,
    onConfirmPasswordChange: (String) -> Unit,
) {
    var passwordVisible by rememberSaveable { mutableStateOf(false) }
    var confirmVisible by rememberSaveable { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = stringResource(id = R.string.settings_password_title),
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = stringResource(id = R.string.settings_password_description),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = enabled,
                onCheckedChange = onEnabledChange,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = MaterialTheme.colorScheme.primary
                )
            )
        }
        AnimatedVisibility(visible = enabled) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                PasswordOutlinedField(
                    value = password,
                    onValueChange = onPasswordChange,
                    label = stringResource(id = R.string.settings_password_new_label),
                    visible = passwordVisible,
                    onToggleVisibility = { passwordVisible = !passwordVisible }
                )
                PasswordOutlinedField(
                    value = confirmPassword,
                    onValueChange = onConfirmPasswordChange,
                    label = stringResource(id = R.string.settings_password_confirm_label),
                    visible = confirmVisible,
                    onToggleVisibility = { confirmVisible = !confirmVisible }
                )
                Text(
                    text = errorMessage
                        ?: stringResource(id = R.string.settings_password_hint, MIN_SETTINGS_PASSWORD_LENGTH),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (errorMessage != null) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }
        }
    }
}

@Composable
private fun PasswordOutlinedField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    visible: Boolean,
    onToggleVisibility: () -> Unit,
) {
    OutlinedTextField(
        modifier = Modifier.fillMaxWidth(),
        value = value,
        onValueChange = onValueChange,
        label = { Text(text = label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        trailingIcon = {
            IconButton(onClick = onToggleVisibility) {
                Icon(
                    imageVector = if (visible) {
                        Icons.Filled.VisibilityOff
                    } else {
                        Icons.Filled.Visibility
                    },
                    contentDescription = stringResource(id = R.string.settings_password_toggle_visibility)
                )
            }
        }
    )
}

@Composable
private fun AppBuilderPage(
    modifier: Modifier,
    appName: String,
    packageName: String,
    packageValid: Boolean,
    statusMessage: String?,
    errorMessage: String?,
    resultPath: String?,
    isBuilding: Boolean,
    selectedIconName: String?,
    useCustomSigning: Boolean,
    defaultInternalHost: String,
    defaultInternalPort: String,
    defaultLocalPort: String,
    defaultSshUser: String,
    defaultGitRepoUrl: String,
    defaultGitFilePath: String,
    defaultPortError: String?,
    defaultLocalPortError: String?,
    defaultSshKey: String,
    defaultGitKey: String,
    defaultSettingsPassword: String,
    onBack: () -> Unit,
    onAppNameChange: (String) -> Unit,
    onPackageChange: (String) -> Unit,
    onDefaultHostChange: (String) -> Unit,
    onDefaultPortChange: (String) -> Unit,
    onDefaultLocalPortChange: (String) -> Unit,
    onDefaultSshUserChange: (String) -> Unit,
    onDefaultGitRepoChange: (String) -> Unit,
    onDefaultGitFileChange: (String) -> Unit,
    onDefaultSshKeyChange: (String) -> Unit,
    onDefaultGitKeyChange: (String) -> Unit,
    onDefaultSettingsPasswordChange: (String) -> Unit,
    onPickIcon: () -> Unit,
    onToggleCustomSigning: (Boolean) -> Unit,
    signingAlias: String,
    onSigningAliasChange: (String) -> Unit,
    privateKeyPem: String,
    onPrivateKeyChange: (String) -> Unit,
    certificatePem: String,
    onCertificateChange: (String) -> Unit,
    onBuild: () -> Unit,
    onInstall: (() -> Unit)?,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(max = 10_000.dp)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        BackHeader(
            title = stringResource(id = R.string.app_builder_title),
            onBack = onBack
        )
        OutlinedTextField(
            modifier = Modifier.fillMaxWidth(),
            value = appName,
            onValueChange = onAppNameChange,
            label = { Text(text = stringResource(id = R.string.app_builder_name_label)) },
            singleLine = true
        )
        OutlinedTextField(
            modifier = Modifier.fillMaxWidth(),
            value = packageName,
            onValueChange = onPackageChange,
            label = { Text(text = stringResource(id = R.string.app_builder_package_label)) },
            singleLine = true,
            isError = packageName.isNotBlank() && !packageValid,
            supportingText = {
                if (packageName.isNotBlank() && !packageValid) {
                    Text(
                        text = stringResource(id = R.string.app_builder_invalid_package),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        )
        OutlinedButton(
            onClick = onPickIcon,
            enabled = !isBuilding
        ) {
            Text(text = stringResource(id = R.string.app_builder_select_icon))
        }
        Text(
            text = selectedIconName ?: stringResource(id = R.string.app_builder_pick_placeholder),
            style = MaterialTheme.typography.bodySmall,
            color = if (selectedIconName != null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.outline
        )
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = stringResource(id = R.string.app_builder_defaults_title),
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                text = stringResource(id = R.string.app_builder_defaults_description),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            OutlinedTextField(
                modifier = Modifier.fillMaxWidth(),
                value = defaultInternalHost,
                onValueChange = onDefaultHostChange,
                label = { Text(text = stringResource(id = R.string.app_builder_default_host_label)) },
                placeholder = { Text(text = stringResource(id = R.string.app_builder_default_host_placeholder)) },
                singleLine = true,
                enabled = !isBuilding,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri)
            )
            OutlinedTextField(
                modifier = Modifier.fillMaxWidth(),
                value = defaultInternalPort,
                onValueChange = onDefaultPortChange,
                label = { Text(text = stringResource(id = R.string.app_builder_default_port_label)) },
                placeholder = { Text(text = stringResource(id = R.string.app_builder_default_port_placeholder)) },
                singleLine = true,
                enabled = !isBuilding,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                isError = defaultPortError != null,
                supportingText = {
                    defaultPortError?.let { error ->
                        Text(
                            text = error,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            )
            OutlinedTextField(
                modifier = Modifier.fillMaxWidth(),
                value = defaultLocalPort,
                onValueChange = onDefaultLocalPortChange,
                label = { Text(text = stringResource(id = R.string.app_builder_default_local_port_label)) },
                placeholder = { Text(text = stringResource(id = R.string.app_builder_default_local_port_placeholder)) },
                singleLine = true,
                enabled = !isBuilding,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                isError = defaultLocalPortError != null,
                supportingText = {
                    defaultLocalPortError?.let { error ->
                        Text(
                            text = error,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            )
            OutlinedTextField(
                modifier = Modifier.fillMaxWidth(),
                value = defaultSshUser,
                onValueChange = onDefaultSshUserChange,
                label = { Text(text = stringResource(id = R.string.app_builder_default_ssh_user_label)) },
                placeholder = { Text(text = stringResource(id = R.string.app_builder_default_ssh_user_placeholder)) },
                singleLine = true,
                enabled = !isBuilding,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text)
            )
            OutlinedTextField(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 120.dp),
                value = defaultSshKey,
                onValueChange = onDefaultSshKeyChange,
                label = { Text(text = stringResource(id = R.string.app_builder_default_ssh_key_label)) },
                placeholder = { Text(text = stringResource(id = R.string.app_builder_default_ssh_key_placeholder)) },
                enabled = !isBuilding,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text)
            )
            OutlinedTextField(
                modifier = Modifier.fillMaxWidth(),
                value = defaultGitRepoUrl,
                onValueChange = onDefaultGitRepoChange,
                label = { Text(text = stringResource(id = R.string.app_builder_default_git_repo_label)) },
                placeholder = { Text(text = stringResource(id = R.string.app_builder_default_git_repo_placeholder)) },
                singleLine = true,
                enabled = !isBuilding,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri)
            )
            OutlinedTextField(
                modifier = Modifier.fillMaxWidth(),
                value = defaultGitFilePath,
                onValueChange = onDefaultGitFileChange,
                label = { Text(text = stringResource(id = R.string.app_builder_default_git_file_label)) },
                placeholder = { Text(text = stringResource(id = R.string.app_builder_default_git_file_placeholder)) },
                singleLine = true,
                enabled = !isBuilding,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text)
            )
            OutlinedTextField(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 120.dp),
                value = defaultGitKey,
                onValueChange = onDefaultGitKeyChange,
                label = { Text(text = stringResource(id = R.string.app_builder_default_git_key_label)) },
                placeholder = { Text(text = stringResource(id = R.string.app_builder_default_git_key_placeholder)) },
                enabled = !isBuilding,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text)
            )
            OutlinedTextField(
                modifier = Modifier.fillMaxWidth(),
                value = defaultSettingsPassword,
                onValueChange = onDefaultSettingsPasswordChange,
                label = { Text(text = stringResource(id = R.string.app_builder_default_settings_password_label)) },
                placeholder = { Text(text = stringResource(id = R.string.app_builder_default_settings_password_placeholder)) },
                singleLine = true,
                enabled = !isBuilding,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password)
            )
            Text(
                text = stringResource(id = R.string.app_builder_defaults_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        PreferenceSwitchRow(
            title = stringResource(id = R.string.app_builder_custom_signing_title),
            checked = useCustomSigning,
            onCheckedChange = onToggleCustomSigning
        )
        if (useCustomSigning) {
            OutlinedTextField(
                modifier = Modifier.fillMaxWidth(),
                value = signingAlias,
                onValueChange = onSigningAliasChange,
                label = { Text(text = stringResource(id = R.string.app_builder_alias_label)) },
                singleLine = true
            )
            OutlinedTextField(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 120.dp),
                value = privateKeyPem,
                onValueChange = onPrivateKeyChange,
                label = { Text(text = stringResource(id = R.string.app_builder_private_key_label)) },
                placeholder = { Text(text = stringResource(id = R.string.app_builder_private_key_placeholder)) },
                maxLines = 8
            )
            OutlinedTextField(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 120.dp),
                value = certificatePem,
                onValueChange = onCertificateChange,
                label = { Text(text = stringResource(id = R.string.app_builder_certificate_label)) },
                placeholder = { Text(text = stringResource(id = R.string.app_builder_certificate_placeholder)) },
                maxLines = 8
            )
        }
        statusMessage?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary
            )
        }
        errorMessage?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }
        resultPath?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Button(
            modifier = Modifier.fillMaxWidth(),
            enabled = !isBuilding && appName.isNotBlank() && packageName.isNotBlank() && packageValid,
            onClick = onBuild
        ) {
            if (isBuilding) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp
                )
                Spacer(modifier = Modifier.width(12.dp))
            }
            Text(text = stringResource(id = R.string.app_builder_build))
        }
        onInstall?.let { install ->
            TextButton(
                modifier = Modifier.fillMaxWidth(),
                enabled = !isBuilding,
                onClick = install
            ) {
                Text(text = stringResource(id = R.string.app_builder_install))
            }
        }
    }
}

@Composable
private fun SettingsNavigationCard(
    title: String,
    description: String,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ExpandableSettingsSection(
    title: String,
    description: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        tonalElevation = 1.dp
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onToggle)
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium
                    )
                    if (description.isNotEmpty()) {
                        Text(
                            text = description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Icon(
                    imageVector = if (expanded) Icons.Filled.KeyboardArrowDown else Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null
                )
            }
            AnimatedVisibility(
                visible = expanded,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    content()
                }
            }
        }
    }
}

@Composable
private fun BackHeader(
    title: String,
    onBack: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onBack),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
            contentDescription = stringResource(id = R.string.settings_back)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge
        )
    }
}

@Composable
private fun PreferenceSwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            modifier = Modifier.weight(1f),
            text = title,
            style = MaterialTheme.typography.bodyMedium
        )
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = MaterialTheme.colorScheme.primary
            )
        )
    }
}

private fun parseLocalEndpoint(endpoint: String?): Pair<String?, String?> {
    if (endpoint.isNullOrBlank()) return null to null
    var cleaned = endpoint.trim()
    cleaned = cleaned.removePrefix("https://").removePrefix("http://")
    val slashIndex = cleaned.indexOf('/')
    if (slashIndex >= 0) {
        cleaned = cleaned.substring(0, slashIndex)
    }
    val colonIndex = cleaned.lastIndexOf(':')
    return if (colonIndex >= 0 && colonIndex < cleaned.length - 1) {
        val host = cleaned.substring(0, colonIndex)
        val port = cleaned.substring(colonIndex + 1)
        host to port
    } else {
        cleaned to ""
    }
}

private fun buildLocalEndpoint(host: String, portRaw: String): String? {
    if (host.isEmpty()) return null
    val port = portRaw.toIntOrNull()
    return buildString {
        append(host)
        if (port != null) {
            append(':')
            append(port)
        }
    }
}

private fun resolveDisplayName(context: Context, uri: Uri): String? {
    return context.contentResolver
        .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
        ?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index != -1 && cursor.moveToFirst()) {
                cursor.getString(index)
            } else {
                null
            }
        }
}
