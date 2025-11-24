package com.zahah.tunnelview

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.core.content.ContextCompat
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.zahah.tunnelview.ui.settings.SettingsScreen
import com.zahah.tunnelview.ui.theme.AppThemeManager
import com.zahah.tunnelview.work.EndpointSyncWorker
import com.zahah.tunnelview.ui.theme.TunnelViewTheme
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.zahah.tunnelview.appbuilder.TemplateAppBuilder
import com.zahah.tunnelview.network.HttpClient
import com.zahah.tunnelview.update.GitUpdateChecker
import com.zahah.tunnelview.storage.CredentialsStore
import com.zahah.tunnelview.AppDefaultsProvider
import com.zahah.tunnelview.update.UpdateDialogFactory
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import androidx.lifecycle.lifecycleScope
import android.widget.Toast
import androidx.appcompat.app.AlertDialog

class SettingsActivity : ComponentActivity() {

    private lateinit var prefs: Prefs
    private val appDefaults by lazy { AppDefaultsProvider.defaults(applicationContext) }
    private val sharedPrefs: SharedPreferences by lazy {
        getSharedPreferences("prefs", Context.MODE_PRIVATE)
    }
    private val credentialsStore by lazy { CredentialsStore.getInstance(applicationContext) }
    private val updateChecker: GitUpdateChecker by lazy {
        GitUpdateChecker(applicationContext, HttpClient.shared(applicationContext))
    }
    @Suppress("unused")
    private val appUpdateKeyPlaceholder by lazy {
        applicationContext.resources.openRawResource(R.raw.id_ed25519_git_app_updates)
            .bufferedReader()
            .use { it.readText() }
    }
    private var updateProgressDialog: AlertDialog? = null
    private var updateCheckJob: Job? = null
    private var updateCheckCancelled = false

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLocaleManager.wrapContext(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        setTitle(R.string.settings_title)
        setContent {
            var themeColorId by remember { mutableStateOf(prefs.themeColorId) }
            var themeModeId by remember { mutableStateOf(prefs.themeModeId) }
            LaunchedEffect(themeModeId) { AppThemeManager.apply(themeModeId) }
            TunnelViewTheme(themeModeId = themeModeId, colorOptionId = themeColorId) {
                var storedPassword by remember { mutableStateOf(prefs.settingsPassword.orEmpty()) }
                var unlocked by rememberSaveable { mutableStateOf(storedPassword.isEmpty()) }
                var authError by remember { mutableStateOf<String?>(null) }
                DisposableEffect(Unit) {
                    val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
                        when (key) {
                            PrefKeys.SETTINGS_PASSWORD -> storedPassword = prefs.settingsPassword.orEmpty()
                            Prefs.KEY_THEME_COLOR -> themeColorId = prefs.themeColorId
                            Prefs.KEY_THEME_MODE -> themeModeId = prefs.themeModeId
                        }
                    }
                    sharedPrefs.registerOnSharedPreferenceChangeListener(listener)
                    onDispose {
                        sharedPrefs.unregisterOnSharedPreferenceChangeListener(listener)
                    }
                }
                LaunchedEffect(storedPassword) {
                    if (storedPassword.isEmpty()) {
                        unlocked = true
                        authError = null
                    }
                }
                if (unlocked || storedPassword.isEmpty()) {
                    SettingsScreen(
                        themeModeId = themeModeId,
                        themeColorId = themeColorId,
                        onSyncNow = {
                            EndpointSyncWorker.enqueueImmediate(applicationContext)
                            lifecycleScope.launch {
                                updateChecker.checkForUpdates(force = true)?.let { candidate ->
                                    showUpdatePrompt(candidate)
                                }
                            }
                        },
                        onTestReconnect = {
                            val intent = Intent(this, TunnelService::class.java).setAction(Actions.RECONNECT)
                            if (prefs.persistentNotificationEnabled) {
                                ContextCompat.startForegroundService(this, intent)
                            } else {
                                startService(intent)
                            }
                        },
                        onCheckUpdates = {
                            if (updateCheckJob?.isActive == true) return@SettingsScreen
                            updateCheckCancelled = false
                            updateCheckJob = lifecycleScope.launch {
                                showCheckingDialog()
                                try {
                                    if (!credentialsStore.gitUpdateEnabled(appDefaults.appUpdateFileName.isNotBlank())) {
                                        if (!updateCheckCancelled) {
                                            showNoUpdateDialog(getString(R.string.git_update_not_configured))
                                        }
                                        return@launch
                                    }
                                    val candidate = try {
                                        updateChecker.checkForUpdates(force = true)
                                    } catch (cancelled: CancellationException) {
                                        return@launch
                                    } catch (error: Throwable) {
                                        val now = System.currentTimeMillis()
                                        prefs.lastGitUpdateStatus = "Check failed: ${error.message ?: error::class.java.simpleName}"
                                        prefs.lastGitUpdateStatusAt = now
                                        prefs.lastGitUpdateCheckAtMillis = now
                                        if (!updateCheckCancelled) {
                                            showNoUpdateDialog(
                                                buildNoUpdateMessage(
                                                    fallback = getString(R.string.git_update_error_generic)
                                                )
                                            )
                                        }
                                        return@launch
                                    }
                                    if (!isActive || updateCheckCancelled) return@launch
                                    if (candidate != null) {
                                        showUpdatePrompt(candidate)
                                    } else {
                                        showNoUpdateDialog(buildNoUpdateMessage())
                                    }
                                } finally {
                                    hideCheckingDialog()
                                    updateCheckJob = null
                                    updateCheckCancelled = false
                                }
                            }
                        },
                        onExit = { finish() }
                    )
                } else {
                    SettingsPasswordGate(
                        errorMessage = authError,
                        onSubmit = { input ->
                            if (input == storedPassword) {
                                unlocked = true
                                authError = null
                            } else {
                                authError = getString(R.string.settings_password_gate_error)
                            }
                        },
                        onCancel = { finish() }
                    )
                }
            }
        }
    }

    private fun showUpdatePrompt(candidate: GitUpdateChecker.UpdateCandidate) {
        val versionLabel = candidate.versionName
            ?.takeIf { it.isNotBlank() }
            ?.let { "$it (${candidate.versionCode})" }
            ?: candidate.versionCode.toString()
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.git_update_available_title)
            .setMessage(getString(R.string.git_update_available_message, versionLabel))
            .setPositiveButton(R.string.git_update_action_update) { dialog, _ ->
                dialog.dismiss()
                installCandidate(candidate)
            }
            .setNegativeButton(R.string.git_update_action_remind_later) { dialog, _ ->
                dialog.dismiss()
                updateChecker.remindLater(candidate.versionCode)
                runCatching { candidate.file.delete() }
            }
            .show()
    }

    private fun installCandidate(candidate: GitUpdateChecker.UpdateCandidate) {
        runCatching {
            updateChecker.install(candidate)
        }.onFailure {
            showNoUpdateDialog(getString(R.string.git_update_install_failed))
        }
    }

    private fun showNoUpdateDialog(message: String) {
        UpdateDialogFactory.showNoUpdateDialog(this, message)
    }

    private fun buildNoUpdateMessage(fallback: String? = null): String {
        return UpdateDialogFactory.buildNoUpdateMessage(this, prefs, fallback)
    }

    private fun showCheckingDialog() {
        hideCheckingDialog()
        updateCheckCancelled = false
        updateProgressDialog = UpdateDialogFactory.showCheckingDialog(this) {
            updateCheckCancelled = true
            updateCheckJob?.cancel()
            hideCheckingDialog()
        }
    }

    private fun hideCheckingDialog() {
        updateProgressDialog?.dismiss()
        updateProgressDialog = null
    }
}

@Composable
private fun SettingsPasswordGate(
    errorMessage: String?,
    onSubmit: (String) -> Unit,
    onCancel: () -> Unit,
) {
    var password by rememberSaveable { mutableStateOf("") }
    var visible by rememberSaveable { mutableStateOf(false) }
    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = stringResource(id = R.string.settings_password_gate_title),
                style = MaterialTheme.typography.titleLarge
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(id = R.string.settings_password_gate_description),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(24.dp))
            OutlinedTextField(
                modifier = Modifier.fillMaxWidth(),
                value = password,
                onValueChange = { password = it },
                singleLine = true,
                label = { Text(text = stringResource(id = R.string.settings_password_gate_placeholder)) },
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    imeAction = ImeAction.Done
                ),
                keyboardActions = KeyboardActions(
                    onDone = {
                        onSubmit(password)
                        password = ""
                    }
                ),
                visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = { visible = !visible }) {
                        Icon(
                            imageVector = if (visible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                            contentDescription = stringResource(id = R.string.settings_password_toggle_visibility)
                        )
                    }
                }
            )
            errorMessage?.let {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            } ?: Spacer(modifier = Modifier.height(8.dp))
            Button(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp),
                onClick = {
                    onSubmit(password)
                    password = ""
                }
            ) {
                Text(text = stringResource(id = R.string.settings_password_gate_unlock))
            }
            TextButton(
                modifier = Modifier.fillMaxWidth(),
                onClick = onCancel
            ) {
                Text(text = stringResource(id = R.string.settings_password_gate_cancel))
            }
        }
    }
}
