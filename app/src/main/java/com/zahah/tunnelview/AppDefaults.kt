package com.zahah.tunnelview

import android.content.Context
import java.io.BufferedReader
import java.io.InputStreamReader
import org.json.JSONObject
import com.zahah.tunnelview.BuildConfig

data class AppDefaults(
    val internalHost: String,
    val internalPort: String,
    val localPort: String,
    val sshUser: String,
    val gitRepoUrl: String,
    val gitFilePath: String,
    val sshPrivateKey: String,
    val gitPrivateKey: String,
    val settingsPassword: String,
    val appBuilderEnabled: Boolean
)

object AppDefaultsProvider {
    private const val ASSET_PATH = "app_defaults.json"
    @Volatile
    private var cached: AppDefaults? = null

    fun defaults(context: Context): AppDefaults {
        val existing = cached
        if (existing != null) return existing
        val safeContext = context.applicationContext ?: context
        val loaded = loadFromAssets(safeContext) ?: fallback()
        cached = loaded
        return loaded
    }

    private fun loadFromAssets(context: Context): AppDefaults? {
        return runCatching {
            context.assets.open(ASSET_PATH).use { stream ->
                BufferedReader(InputStreamReader(stream)).use { reader ->
                    val json = JSONObject(reader.readText())
                    val base = fallback()
                    AppDefaults(
                        internalHost = json.optString("internalHost", base.internalHost).ifBlank { base.internalHost },
                        internalPort = json.optString("internalPort", base.internalPort).ifBlank { base.internalPort },
                        localPort = json.optString("localPort", base.localPort).ifBlank { base.localPort },
                        sshUser = json.optString("sshUser", base.sshUser).ifBlank { base.sshUser },
                        gitRepoUrl = json.optString("gitRepoUrl", base.gitRepoUrl).ifBlank { base.gitRepoUrl },
                        gitFilePath = json.optString("gitFilePath", base.gitFilePath).ifBlank { base.gitFilePath },
                        sshPrivateKey = json.optString("sshPrivateKey", base.sshPrivateKey).ifBlank { base.sshPrivateKey },
                        gitPrivateKey = json.optString("gitPrivateKey", base.gitPrivateKey).ifBlank { base.gitPrivateKey },
                        settingsPassword = json.optString("settingsPassword", base.settingsPassword)
                            .ifBlank { base.settingsPassword },
                        appBuilderEnabled = json.optBoolean("appBuilderEnabled", base.appBuilderEnabled)
                    )
                }
            }
        }.getOrNull()
    }

    private fun fallback(): AppDefaults {
        val decodeMultiline: (String) -> String = { it.replace("\\n", "\n") }
        val defaultLocalPort = BuildConfig.DEFAULT_LOCAL_PORT.orEmpty().ifBlank { "8090" }
        return AppDefaults(
            internalHost = BuildConfig.DEFAULT_INTERNAL_HOST.orEmpty(),
            internalPort = BuildConfig.DEFAULT_INTERNAL_PORT.orEmpty(),
            localPort = defaultLocalPort,
            sshUser = BuildConfig.DEFAULT_SSH_USER.orEmpty(),
            gitRepoUrl = BuildConfig.DEFAULT_GIT_REPO_URL.orEmpty(),
            gitFilePath = BuildConfig.DEFAULT_GIT_FILE_PATH.orEmpty(),
            sshPrivateKey = decodeMultiline(BuildConfig.DEFAULT_SSH_PRIVATE_KEY.orEmpty()),
            gitPrivateKey = decodeMultiline(BuildConfig.DEFAULT_GIT_PRIVATE_KEY.orEmpty()),
            settingsPassword = BuildConfig.DEFAULT_SETTINGS_PASSWORD.orEmpty(),
            appBuilderEnabled = true
        )
    }
}
