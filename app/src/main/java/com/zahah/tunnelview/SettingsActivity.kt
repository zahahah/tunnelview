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

class SettingsActivity : ComponentActivity() {

    private lateinit var prefs: Prefs
    private val sharedPrefs: SharedPreferences by lazy {
        getSharedPreferences("prefs", Context.MODE_PRIVATE)
    }

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
                        onSyncNow = { EndpointSyncWorker.enqueueImmediate(applicationContext) },
                        onTestReconnect = {
                            val intent = Intent(this, TunnelService::class.java).setAction(Actions.RECONNECT)
                            if (prefs.persistentNotificationEnabled) {
                                ContextCompat.startForegroundService(this, intent)
                            } else {
                                startService(intent)
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
