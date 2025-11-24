package com.zahah.tunnelview.storage

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.zahah.tunnelview.AppDefaultsProvider
import android.content.SharedPreferences.OnSharedPreferenceChangeListener

class CredentialsStore private constructor(context: Context) {

    private val appContext = context.applicationContext
    private val appDefaults = AppDefaultsProvider.defaults(appContext)
    private val securePrefs: SharedPreferences = createEncryptedPrefs(appContext)
    private val legacyPrefs: SharedPreferences =
        appContext.getSharedPreferences(LEGACY_PREFS, Context.MODE_PRIVATE)

    suspend fun setNtfyTopic(value: String?) {
        write(KEY_TOPIC, value)
    }

    suspend fun setRemoteFileUrl(value: String?) {
        write(KEY_REMOTE_FILE_URL, value)
    }

    suspend fun setAccessKey(value: String?) {
        write(KEY_ACCESS_KEY, value)
    }

    suspend fun setGitRepoUrl(value: String?) {
        write(KEY_GIT_REPO_URL, value)
    }

    suspend fun setGitBranch(value: String?) {
        write(KEY_GIT_BRANCH, value)
    }

    suspend fun setGitFilePath(value: String?) {
        write(KEY_GIT_FILE_PATH, value)
    }

    suspend fun setGitPrivateKey(value: String?) {
        write(KEY_GIT_PRIVATE_KEY, value)
    }

    suspend fun setAppUpdatePrivateKey(value: String?) {
        write(KEY_APP_UPDATE_PRIVATE_KEY, value)
    }

    suspend fun setGitUpdateEnabled(value: Boolean?) {
        write(KEY_GIT_UPDATE_ENABLED, value)
    }

    suspend fun setGitUpdateFileName(value: String?) {
        write(KEY_GIT_UPDATE_FILE_NAME, value)
    }

    suspend fun setAppUpdateRepoUrl(value: String?) {
        write(KEY_APP_UPDATE_REPO_URL, value)
    }

    suspend fun setAppUpdateBranch(value: String?) {
        write(KEY_APP_UPDATE_BRANCH, value)
    }

    suspend fun setHttpHeaderName(value: String?) {
        write(KEY_HTTP_HEADER_NAME, value)
    }

    suspend fun setHttpHeaderValue(value: String?) {
        write(KEY_HTTP_HEADER_VALUE, value)
    }

    fun ntfyTopic(): String? = read(KEY_TOPIC, LEGACY_NTFY_URL, appDefaults.ntfyTopic)

    fun remoteFileUrl(): String? = read(KEY_REMOTE_FILE_URL, LEGACY_FALLBACK_URL)

    fun accessKey(): String? = read(KEY_ACCESS_KEY, null)

    fun gitRepoUrl(): String? = read(
        KEY_GIT_REPO_URL,
        null,
        AppDefaultsProvider.defaults(appContext).gitRepoUrl
    )

    fun gitBranch(): String? = read(KEY_GIT_BRANCH, null)

    fun gitFilePath(): String? = read(
        KEY_GIT_FILE_PATH,
        null,
        AppDefaultsProvider.defaults(appContext).gitFilePath
    )

    fun gitPrivateKey(): String? = read(
        KEY_GIT_PRIVATE_KEY,
        null,
        AppDefaultsProvider.defaults(appContext).gitPrivateKey
    )

    fun appUpdatePrivateKey(): String? = read(
        KEY_APP_UPDATE_PRIVATE_KEY,
        null,
        AppDefaultsProvider.defaults(appContext).appUpdatePrivateKey
    )

    fun gitUpdateEnabled(defaultEnabled: Boolean = false): Boolean {
        if (securePrefs.contains(KEY_GIT_UPDATE_ENABLED)) {
            return securePrefs.getBoolean(KEY_GIT_UPDATE_ENABLED, defaultEnabled)
        }
        return defaultEnabled
    }

    fun gitUpdateFileName(): String? = read(
        KEY_GIT_UPDATE_FILE_NAME,
        null,
        AppDefaultsProvider.defaults(appContext).appUpdateFileName
    )

    fun appUpdateRepoUrl(): String? = read(
        KEY_APP_UPDATE_REPO_URL,
        null,
        AppDefaultsProvider.defaults(appContext).appUpdateRepoUrl
    )

    fun appUpdateBranch(): String? = read(
        KEY_APP_UPDATE_BRANCH,
        null,
        AppDefaultsProvider.defaults(appContext).appUpdateBranch
    )

    fun httpHeaderName(): String? = read(
        KEY_HTTP_HEADER_NAME,
        LEGACY_HTTP_HEADER_NAME,
        appDefaults.httpHeader
    )

    fun httpHeaderValue(): String? = read(
        KEY_HTTP_HEADER_VALUE,
        LEGACY_HTTP_HEADER_VALUE,
        appDefaults.httpKey
    )

    fun httpHeaderConfig(): HeaderConfig? {
        val name = httpHeaderName()?.trim().orEmpty()
        val value = httpHeaderValue()?.trim().orEmpty()
        if (name.isBlank() || value.isBlank()) return null
        return HeaderConfig(name, value)
    }

    suspend fun setSshFingerprintSha256(value: String?) {
        write(KEY_SSH_FINGERPRINT, value?.trim()?.ifEmpty { null })
    }

    fun sshFingerprintSha256(): String? = read(KEY_SSH_FINGERPRINT, null)

    fun registerChangeListener(listener: OnSharedPreferenceChangeListener) {
        securePrefs.registerOnSharedPreferenceChangeListener(listener)
    }

    fun unregisterChangeListener(listener: OnSharedPreferenceChangeListener) {
        securePrefs.unregisterOnSharedPreferenceChangeListener(listener)
    }

    private suspend fun write(key: String, value: String?) = withContext(Dispatchers.IO) {
        securePrefs.edit().apply {
            if (value.isNullOrBlank()) {
                remove(key)
            } else {
                putString(key, value.trim())
            }
        }.apply()
    }

    private suspend fun write(key: String, value: Boolean?) = withContext(Dispatchers.IO) {
        securePrefs.edit().apply {
            if (value == null) {
                remove(key)
            } else {
                putBoolean(key, value)
            }
        }.apply()
    }

    private fun read(key: String, legacyKey: String?, fallback: String? = null): String? {
        val stored = securePrefs.getString(key, null)?.takeIf { it.isNotBlank() }
        if (stored != null) {
            return stored
        }
        if (legacyKey != null) {
            val legacy = legacyPrefs.getString(legacyKey, null)?.takeIf { it.isNotBlank() }
            if (legacy != null) {
                securePrefs.edit().putString(key, legacy).apply()
                legacyPrefs.edit().remove(legacyKey).apply()
                return legacy
            }
        }
        return fallback?.takeIf { it.isNotBlank() }
    }

    private fun createEncryptedPrefs(context: Context): SharedPreferences {
        return try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                context,
                "proxy_credentials",
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (error: Throwable) {
            Log.w(TAG, "Falling back to unencrypted preferences: ${error.message}")
            context.getSharedPreferences("proxy_credentials_compat", Context.MODE_PRIVATE)
        }
    }

    companion object {
        private const val TAG = "CredentialsStore"
        private const val KEY_TOPIC = "ntfy_topic"
        private const val KEY_REMOTE_FILE_URL = "remote_endpoint_url"
        private const val KEY_ACCESS_KEY = "remote_access_key"
        private const val KEY_SSH_FINGERPRINT = "ssh_fingerprint_sha256"
        private const val KEY_GIT_REPO_URL = "git_repo_url"
        private const val KEY_GIT_BRANCH = "git_branch"
        private const val KEY_GIT_FILE_PATH = "git_file_path"
        private const val KEY_GIT_PRIVATE_KEY = "git_private_key"
        private const val KEY_APP_UPDATE_PRIVATE_KEY = "app_update_private_key"
        private const val KEY_GIT_UPDATE_ENABLED = "git_update_enabled"
        private const val KEY_GIT_UPDATE_FILE_NAME = "git_update_file_name"
        private const val KEY_APP_UPDATE_REPO_URL = "app_update_repo_url"
        private const val KEY_APP_UPDATE_BRANCH = "app_update_branch"
        const val KEY_HTTP_HEADER_NAME = "http_header_name"
        const val KEY_HTTP_HEADER_VALUE = "http_header_value"
        private const val LEGACY_PREFS = "prefs"
        private const val LEGACY_NTFY_URL = "ntfyWsUrl"
        private const val LEGACY_FALLBACK_URL = "fallbackEndpointUrl"
        private const val LEGACY_HTTP_HEADER_NAME = "httpHeaderName"
        private const val LEGACY_HTTP_HEADER_VALUE = "httpHeaderValue"

        @Volatile
        private var instance: CredentialsStore? = null

        fun getInstance(context: Context): CredentialsStore {
            return instance ?: synchronized(this) {
                instance ?: CredentialsStore(context).also { instance = it }
            }
        }
    }

    data class HeaderConfig(
        val name: String,
        val value: String,
    )
}
