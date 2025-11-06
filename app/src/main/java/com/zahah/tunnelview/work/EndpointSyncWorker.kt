package com.zahah.tunnelview.work

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.zahah.tunnelview.Prefs
import com.zahah.tunnelview.data.ProxyRepository
import com.zahah.tunnelview.network.RemoteEndpointFetcher
import com.zahah.tunnelview.network.GitEndpointFetcher
import com.zahah.tunnelview.network.RemoteEndpointResult
import com.zahah.tunnelview.storage.CredentialsStore
import com.zahah.tunnelview.network.HttpClient
import java.util.concurrent.TimeUnit

class EndpointSyncWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    private val credentials = CredentialsStore.getInstance(appContext)
    private val repository = ProxyRepository.get(appContext)
    private val prefs = Prefs(appContext)
    private val client by lazy {
        HttpClient.shared(appContext)
            .newBuilder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }
    private val fetcher by lazy { RemoteEndpointFetcher(client) }
    private val gitFetcher by lazy { GitEndpointFetcher(appContext, client) }

    override suspend fun doWork(): Result {
        val gitRepo = credentials.gitRepoUrl()?.trim()?.takeIf { it.isNotEmpty() }
        if (gitRepo != null) {
            return handleGitFallback(gitRepo)
        }

        val url = credentials.remoteFileUrl()?.trim().orEmpty()
        if (url.isEmpty()) {
            Log.d(TAG, "Fallback URL not configured; skipping worker run")
            return Result.success()
        }
        val accessKey = credentials.accessKey()
        return when (val result = fetcher.fetch(url, accessKey)) {
            is RemoteEndpointResult.Success -> {
                repository.updateEndpoint(result.endpoint)
                prefs.lastSuccessfulGitSyncAtMillis = System.currentTimeMillis()
                Result.success()
            }

            is RemoteEndpointResult.AuthError -> {
                val message = "Acesso negado (${result.code}) ao arquivo de fallback"
                repository.reportAuthFailure(message)
                Result.failure(workDataOf(KEY_ERROR to message))
            }

            is RemoteEndpointResult.InvalidFormat -> {
                val message = "Payload inválido no fallback"
                repository.reportError(message)
                Result.failure(workDataOf(KEY_ERROR to message))
            }

            is RemoteEndpointResult.HttpError -> {
                val message = "Erro HTTP ${result.code} ao buscar fallback"
                repository.reportError(message)
                Result.retry()
            }

            RemoteEndpointResult.Empty -> {
                val message = "Arquivo de fallback vazio"
                repository.reportError(message)
                Result.retry()
            }

            is RemoteEndpointResult.NetworkError -> {
                repository.reportError("Erro de rede ao buscar fallback")
                Result.retry()
            }
        }
    }

    private suspend fun handleGitFallback(repoUrl: String): Result {
        val filePath = credentials.gitFilePath()?.takeIf { it.isNotBlank() } ?: run {
            val message = "Arquivo git não configurado"
            repository.reportError(message)
            return Result.failure(workDataOf(KEY_ERROR to message))
        }
        val params = GitEndpointFetcher.Params(
            repoUrl = repoUrl,
            branch = credentials.gitBranch()?.takeIf { it.isNotBlank() } ?: "main",
            filePath = filePath,
            privateKey = credentials.gitPrivateKey()
        )
        return when (val result = gitFetcher.fetch(params)) {
            is RemoteEndpointResult.Success -> {
                repository.updateEndpoint(result.endpoint)
                prefs.lastSuccessfulGitSyncAtMillis = System.currentTimeMillis()
                Result.success()
            }

            is RemoteEndpointResult.InvalidFormat -> {
                val message = "Arquivo git inválido"
                repository.reportError(message)
                Result.failure(workDataOf(KEY_ERROR to message))
            }

            is RemoteEndpointResult.HttpError,
            is RemoteEndpointResult.AuthError,
            RemoteEndpointResult.Empty -> {
                val message = "Falha ao processar repositório git"
                repository.reportError(message)
                Result.retry()
            }

            is RemoteEndpointResult.NetworkError -> {
                val causeMessage = result.throwable.message?.takeIf { it.isNotBlank() }
                val message = buildString {
                    append("Erro de rede ao clonar repositório")
                    if (causeMessage != null) {
                        append(": ")
                        append(causeMessage)
                    }
                }
                Log.w(TAG, "Git clone failed", result.throwable)
                if (shouldReportGitNetworkError(result.throwable)) {
                    repository.reportError(message)
                } else {
                    Log.i(TAG, "Suprimindo erro de rede do git: ${result.throwable.message}")
                }
                Result.retry()
            }
        }
    }

    @Suppress("UNUSED_PARAMETER")
    private fun shouldReportGitNetworkError(error: Throwable): Boolean {
        if (!prefs.hasDirectEndpointConfigured()) return true
        if (prefs.lastSuccessfulGitSyncAtMillis <= 0L) return true
        return false
    }

    companion object {
        private const val TAG = "EndpointSyncWorker"
        private const val UNIQUE_PERIODIC = "proxy-endpoint-sync-periodic"
        private const val UNIQUE_IMMEDIATE = "proxy-endpoint-sync-once"
        private const val KEY_ERROR = "error"
        private val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        fun schedulePeriodic(context: Context) {
            // Running the sync while app is in foreground keeps data fresh without background Git clones.
            val work = PeriodicWorkRequestBuilder<EndpointSyncWorker>(15, TimeUnit.MINUTES)
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 5, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(
                    UNIQUE_PERIODIC,
                    ExistingPeriodicWorkPolicy.KEEP,
                    work
                )
        }

        fun cancelPeriodic(context: Context) {
            WorkManager.getInstance(context)
                .cancelUniqueWork(UNIQUE_PERIODIC)
        }

        fun enqueueImmediate(context: Context) {
            val work = OneTimeWorkRequestBuilder<EndpointSyncWorker>()
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 2, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(
                    UNIQUE_IMMEDIATE,
                    ExistingWorkPolicy.REPLACE,
                    work
                )
        }
    }
}
