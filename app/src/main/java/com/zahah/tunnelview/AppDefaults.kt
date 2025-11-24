package com.zahah.tunnelview

import android.content.Context
import java.io.BufferedReader
import java.io.InputStreamReader
import org.json.JSONObject
import com.zahah.tunnelview.BuildConfig

data class AppDefaults(
    val remoteInternalHost: String,
    val remoteInternalPort: String,
    val directHost: String,
    val directPort: String,
    val localPort: String,
    val httpAddress: String,
    val httpHeader: String,
    val httpKey: String,
    val ntfyTopic: String,
    val httpEnabled: Boolean,
    val sshUser: String,
    val gitRepoUrl: String,
    val gitFilePath: String,
    val gitUpdateFileName: String,
    val appUpdateRepoUrl: String,
    val appUpdateBranch: String,
    val appUpdateFileName: String,
    val appUpdatePrivateKey: String,
    val sshPrivateKey: String,
    val gitPrivateKey: String,
    val settingsPassword: String,
    val appBuilderEnabled: Boolean,
    val hideConnectionMessages: Boolean
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
                    val legacyInternalHost = json.optString("internalHost")
                    val legacyInternalPort = json.optString("internalPort")
                    AppDefaults(
                        remoteInternalHost = json.optString("remoteInternalHost", legacyInternalHost)
                            .ifBlank { legacyInternalHost }
                            .ifBlank { base.remoteInternalHost },
                        remoteInternalPort = json.optString("remoteInternalPort", legacyInternalPort)
                            .ifBlank { legacyInternalPort }
                            .ifBlank { base.remoteInternalPort },
                        directHost = json.optString("directHost", base.directHost).ifBlank { base.directHost },
                        directPort = json.optString("directPort", base.directPort).ifBlank { base.directPort },
                        localPort = json.optString("localPort", base.localPort).ifBlank { base.localPort },
                        httpAddress = json.optString("httpAddress", base.httpAddress).ifBlank { base.httpAddress },
                        httpHeader = json.optString("httpHeader", base.httpHeader).ifBlank { base.httpHeader },
                        httpKey = json.optString("httpKey", base.httpKey).ifBlank { base.httpKey },
                        ntfyTopic = json.optString("ntfyTopic", base.ntfyTopic).ifBlank { base.ntfyTopic },
                        httpEnabled = json.optBoolean("httpEnabled", base.httpEnabled),
                        sshUser = json.optString("sshUser", base.sshUser).ifBlank { base.sshUser },
                        gitRepoUrl = json.optString("gitRepoUrl", base.gitRepoUrl).ifBlank { base.gitRepoUrl },
                        gitFilePath = json.optString("gitFilePath", base.gitFilePath).ifBlank { base.gitFilePath },
                        gitUpdateFileName = json.optString("gitUpdateFileName", base.gitUpdateFileName)
                            .ifBlank { base.gitUpdateFileName },
                        appUpdateRepoUrl = json.optString("appUpdateRepoUrl", base.appUpdateRepoUrl)
                            .ifBlank { base.appUpdateRepoUrl },
                        appUpdateBranch = json.optString("appUpdateBranch", base.appUpdateBranch)
                            .ifBlank { base.appUpdateBranch },
                        appUpdateFileName = json.optString("appUpdateFileName", base.appUpdateFileName)
                            .ifBlank { base.appUpdateFileName },
                        appUpdatePrivateKey = json.optString("appUpdatePrivateKey", base.appUpdatePrivateKey)
                            .ifBlank { base.appUpdatePrivateKey },
                        sshPrivateKey = json.optString("sshPrivateKey", base.sshPrivateKey).ifBlank { base.sshPrivateKey },
                        gitPrivateKey = json.optString("gitPrivateKey", base.gitPrivateKey).ifBlank { base.gitPrivateKey },
                        settingsPassword = json.optString("settingsPassword", base.settingsPassword)
                            .ifBlank { base.settingsPassword },
                        appBuilderEnabled = json.optBoolean("appBuilderEnabled", base.appBuilderEnabled),
                        hideConnectionMessages = json.optBoolean("hideConnectionMessages", base.hideConnectionMessages)
                    )
                }
            }
        }.getOrNull()
    }

    private fun fallback(): AppDefaults {
        val decodeMultiline: (String) -> String = { it.replace("\\n", "\n") }
        val defaultLocalPort = BuildConfig.DEFAULT_LOCAL_PORT.orEmpty().ifBlank { "8090" }
        val defaultHttpAddress = BuildConfig.DEFAULT_HTTP_ADDRESS.orEmpty()
        val defaultNtfyTopic = BuildConfig.DEFAULT_NTFY.orEmpty()
        val defaultGitUpdateFile = BuildConfig.DEFAULT_APP_UPDATE_FILE.orEmpty()
        val defaultGitRepo = BuildConfig.DEFAULT_GIT_REPO_URL.orEmpty()
        val defaultAppUpdateKey = decodeMultiline(BuildConfig.DEFAULT_APP_UPDATE_PRIVATE_KEY.orEmpty())
        return AppDefaults(
            remoteInternalHost = BuildConfig.DEFAULT_REMOTE_INTERNAL_HOST.orEmpty(),
            remoteInternalPort = BuildConfig.DEFAULT_REMOTE_INTERNAL_PORT.orEmpty(),
            directHost = BuildConfig.DEFAULT_DIRECT_HOST.orEmpty(),
            directPort = BuildConfig.DEFAULT_DIRECT_PORT.orEmpty(),
            localPort = defaultLocalPort,
            httpAddress = defaultHttpAddress,
            httpHeader = BuildConfig.DEFAULT_HTTP_HEADER.orEmpty(),
            httpKey = BuildConfig.DEFAULT_HTTP_KEY.orEmpty(),
            ntfyTopic = defaultNtfyTopic,
            httpEnabled = defaultHttpAddress.isNotBlank(),
            sshUser = BuildConfig.DEFAULT_SSH_USER.orEmpty(),
            gitRepoUrl = defaultGitRepo,
            gitFilePath = BuildConfig.DEFAULT_GIT_FILE_PATH.orEmpty(),
            gitUpdateFileName = defaultGitUpdateFile,
            appUpdateRepoUrl = BuildConfig.DEFAULT_APP_UPDATE_REPO_URL.orEmpty()
                .ifBlank { defaultGitRepo },
            appUpdateBranch = BuildConfig.DEFAULT_APP_UPDATE_BRANCH.orEmpty()
                .ifBlank { "main" },
            appUpdateFileName = BuildConfig.DEFAULT_APP_UPDATE_FILE.orEmpty()
                .ifBlank { defaultGitUpdateFile },
            appUpdatePrivateKey = defaultAppUpdateKey.ifBlank { decodeMultiline(BuildConfig.DEFAULT_GIT_PRIVATE_KEY.orEmpty()) },
            sshPrivateKey = decodeMultiline(BuildConfig.DEFAULT_SSH_PRIVATE_KEY.orEmpty()),
            gitPrivateKey = decodeMultiline(BuildConfig.DEFAULT_GIT_PRIVATE_KEY.orEmpty()),
            settingsPassword = BuildConfig.DEFAULT_SETTINGS_PASSWORD.orEmpty(),
            appBuilderEnabled = BuildConfig.APP_BUILDER_ENABLED,
            hideConnectionMessages = BuildConfig.DEFAULT_HIDE_CONNECTION_MESSAGES
        )
    }
}
