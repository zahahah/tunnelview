package com.zahah.tunnelview.network

import android.content.Context
import java.nio.file.Path
import com.zahah.tunnelview.R
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.TransportConfigCallback
import org.eclipse.jgit.transport.SshTransport
import org.eclipse.jgit.transport.Transport
import org.eclipse.jgit.transport.ssh.jsch.JschConfigSessionFactory
import org.eclipse.jgit.transport.ssh.jsch.OpenSshConfig
import org.eclipse.jgit.util.FS
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import android.util.Log

class GitEndpointFetcher(
    private val context: Context,
    private val httpClient: OkHttpClient,
) {

    suspend fun fetch(params: Params): RemoteEndpointResult = withContext(Dispatchers.IO) {
        val rawAttempt = runCatching { fetchViaRawGithub(params) }
        val rawResult = rawAttempt.getOrNull()
        if (rawResult != null) return@withContext rawResult
        val rawFailure = rawAttempt.exceptionOrNull()
        if (rawFailure is RemoteFormatException) {
            Log.w(TAG, "Git raw fetch invalid payload", rawFailure)
            return@withContext RemoteEndpointResult.InvalidFormat(rawFailure.payload)
        }

        runCatching { cloneAndRead(params) }.getOrElse { error ->
            Log.w(TAG, "Git fetch failed: ${error.message}", error)
            when (error) {
                is RemoteFormatException -> RemoteEndpointResult.InvalidFormat(error.payload)
                is PrivateKeyFormatException -> RemoteEndpointResult.AuthError(401)
                else -> RemoteEndpointResult.NetworkError(error)
            }
        }
    }

    private fun fetchViaRawGithub(params: Params): RemoteEndpointResult? {
        val rawUrl = buildRawGithubUrl(params) ?: return null
        val request = Request.Builder()
            .url(rawUrl)
            .get()
            .build()
        return runCatching { httpClient.newCall(request).execute() }
            .fold(
                onSuccess = { response ->
                    response.use { resp ->
                        if (!resp.isSuccessful) return null
                        val body = resp.body?.string()?.trim()
                            ?.takeIf { it.isNotEmpty() }
                            ?: return null
                        val firstLine = body.lineSequence().first().trim()
                            .takeIf { it.isNotEmpty() }
                            ?: return null
                        val endpoint = com.zahah.tunnelview.data.ProxyEndpoint.parseFlexible(
                            firstLine,
                            com.zahah.tunnelview.data.ProxyEndpointSource.FALLBACK
                        ) ?: throw RemoteFormatException(firstLine)
                        Log.d(TAG, "Fetched endpoint via raw.githubusercontent.com")
                        RemoteEndpointResult.Success(endpoint)
                    }
                },
                onFailure = { null }
            )
    }

    private fun buildRawGithubUrl(params: Params): String? {
        val repoUri = params.repoUrl.trim()
        val branch = (params.branch ?: "main")
            .removePrefix("refs/heads/")
            .ifBlank { "main" }
        val filePath = params.filePath.trim().removePrefix("/")
        if (filePath.isEmpty()) return null

        val scpMatch = SCP_REGEX.matchEntire(repoUri)
        if (scpMatch != null) {
            val host = scpMatch.groupValues[1].lowercase()
            if (host != "github.com") return null
            val path = scpMatch.groupValues[2]
            val (owner, repo) = splitOwnerRepo(path) ?: return null
            return "https://raw.githubusercontent.com/$owner/$repo/$branch/$filePath"
        }

        val httpsMatch = HTTPS_REGEX.matchEntire(repoUri)
        if (httpsMatch != null) {
            val host = httpsMatch.groupValues[1].lowercase()
            if (host != "github.com") return null
            val path = httpsMatch.groupValues[2]
            val (owner, repo) = splitOwnerRepo(path) ?: return null
            return "https://raw.githubusercontent.com/$owner/$repo/$branch/$filePath"
        }
        return null
    }

    private fun splitOwnerRepo(path: String): Pair<String, String>? {
        val clean = path.removeSuffix(".git").trim().trim('/')
        val parts = clean.split('/', limit = 2)
        if (parts.size != 2) return null
        val owner = parts[0]
        val repo = parts[1]
        if (owner.isBlank() || repo.isBlank()) return null
        return owner to repo
    }

    private fun cloneAndRead(params: Params): RemoteEndpointResult {
        val repoDir = prepareRepoDir()

        val keyFile = ensurePrivateKey(params)

        val transportCallback = SshTransportCallback(keyPath = keyFile.toPath())
        try {
            Log.d(TAG, "Cloning repoUrl=${params.repoUrl} branch=${params.branch} file=${params.filePath}")
            Git.cloneRepository()
                .setURI(params.repoUrl)
                .setDirectory(repoDir)
                .setCloneAllBranches(false)
                .setTimeout(30)
                .apply {
                    val branch = params.branch?.takeIf { it.isNotBlank() }
                    if (branch != null) {
                        val ref = if (branch.startsWith("refs/")) branch else "refs/heads/$branch"
                        setBranch(ref)
                        setBranchesToClone(listOf(ref))
                    }
                    setTransportConfigCallback(transportCallback)
                }
                .call().use { }

            val target = resolveTargetFile(repoDir, params.filePath)
            if (!target.exists() || !target.isFile) {
                throw RemoteFormatException("Arquivo inexistente: ${params.filePath}")
            }
            val firstLine = target.readText(Charsets.UTF_8)
                .lineSequence()
                .firstOrNull()?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?: throw RemoteFormatException("Arquivo vazio")

            val endpoint = com.zahah.tunnelview.data.ProxyEndpoint.parseFlexible(
                firstLine,
                com.zahah.tunnelview.data.ProxyEndpointSource.FALLBACK
            ) ?: throw RemoteFormatException(firstLine)
            return RemoteEndpointResult.Success(endpoint)
        } finally {
            repoDir.deleteRecursively()
        }
    }

    private fun ensurePrivateKey(params: Params): File {
        val keyData = params.privateKey?.takeIf { it.isNotBlank() }
            ?: loadBundledPrivateKey()
            ?: throw PrivateKeyFormatException()
        val normalized = normalizePem(keyData)
        if (!normalized.contains("BEGIN") || !normalized.contains("END")) {
            throw PrivateKeyFormatException()
        }
        val file = File(context.filesDir, "git_id_key").canonicalFile
        if (!file.exists() || file.readText() != normalized) {
            file.writeText(normalized)
            file.setReadable(false, false)
            file.setWritable(false, false)
            file.setExecutable(false, false)
            file.setReadable(true, true)
            file.setWritable(true, true)
        }
        return file
    }

    private fun loadBundledPrivateKey(): String? {
        return runCatching {
            context.resources.openRawResource(R.raw.id_ed25519_git)
                .bufferedReader()
                .use { it.readText() }
        }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    private fun normalizePem(pem: String): String {
        val trimmed = pem.trim().replace("\r\n", "\n")
        return if (trimmed.endsWith("\n")) trimmed else "$trimmed\n"
    }

    private fun prepareRepoDir(): File {
        val parent = File(context.cacheDir, "git_fallback_cache").apply {
            if (!exists() && !mkdirs() && !exists()) {
                throw IllegalStateException("Não foi possível criar diretório cache para git")
            }
        }
        val tempDir = File.createTempFile("repo_", "", parent)
        if (!tempDir.delete()) {
            throw IllegalStateException("Falha ao preparar diretório temporário do git")
        }
        if (!tempDir.mkdirs()) {
            throw IllegalStateException("Falha ao criar diretório temporário do git")
        }
        return tempDir
    }

    private fun resolveTargetFile(repoDir: File, path: String): File {
        val direct = File(repoDir, path)
        if (direct.exists() && direct.isFile) {
            return direct
        }

        val normalizedTarget = File(path).name.replace('-', '_').lowercase()
        val alternative = repoDir.walkTopDown()
            .maxDepth(32)
            .firstOrNull { file ->
                file.isFile && file.name.replace('-', '_').lowercase() == normalizedTarget
            }

        alternative?.let {
            if (it.absolutePath != direct.absolutePath) {
                Log.d(TAG, "Using fallback file path candidate=${it.name} for requested=$path")
            }
            return it
        }

        return direct
    }

    data class Params(
        val repoUrl: String,
        val branch: String?,
        val filePath: String,
        val privateKey: String?,
    )

    companion object {
        private const val TAG = "GitEndpointFetcher"
        private val SCP_REGEX = Regex("^git@([^:]+):(.+)\$")
        private val HTTPS_REGEX = Regex("^https?://([^/]+)/(.+)\$")
    }

    private class SshTransportCallback(
        private val keyPath: Path
    ) : TransportConfigCallback {
        private val factory: JschConfigSessionFactory by lazy {
            object : JschConfigSessionFactory() {
                override fun configure(host: OpenSshConfig.Host?, session: Session) {
                    session.setConfig("StrictHostKeyChecking", "no")
                    session.setConfig("IdentitiesOnly", "yes")
                    session.setConfig("PreferredAuthentications", "publickey")
                    session.setConfig(
                        "PubkeyAcceptedAlgorithms",
                        "ssh-ed25519,ssh-ed25519-cert-v01@openssh.com,ecdsa-sha2-nistp256,ecdsa-sha2-nistp384,ecdsa-sha2-nistp521,ssh-rsa"
                    )
                    session.setConfig(
                        "HostKeyAlgorithms",
                        "ssh-ed25519,ecdsa-sha2-nistp256,ecdsa-sha2-nistp384,ecdsa-sha2-nistp521,ssh-rsa"
                    )
                }

                override fun createDefaultJSch(fs: FS?): JSch {
                    return super.createDefaultJSch(fs).apply {
                        removeAllIdentity()
                        addIdentity(keyPath.toAbsolutePath().normalize().toString())
                    }
                }
            }
        }

        override fun configure(transport: Transport) {
            val sshTransport = transport as? SshTransport ?: return
            sshTransport.sshSessionFactory = factory
        }
    }

    private class RemoteFormatException(val payload: String) : RuntimeException()
    private class PrivateKeyFormatException : RuntimeException()
}
