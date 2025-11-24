package com.zahah.tunnelview.update

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.pm.PackageInfoCompat
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import com.zahah.tunnelview.AppDefaultsProvider
import com.zahah.tunnelview.Prefs
import com.zahah.tunnelview.appbuilder.TemplateAppBuilder
import com.zahah.tunnelview.network.HttpClient
import com.zahah.tunnelview.storage.CredentialsStore
import java.io.File
import java.nio.file.Path
import java.util.concurrent.TimeUnit
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
import kotlin.math.max

class GitUpdateChecker(
    context: Context,
    private val httpClient: OkHttpClient = HttpClient.shared(context)
) {

    private val appContext = context.applicationContext ?: context
    private val credentialsStore = CredentialsStore.getInstance(appContext)
    private val prefs = Prefs(appContext)
    private val appDefaults = AppDefaultsProvider.defaults(appContext)
    private val updateDir: File by lazy {
        File(appContext.filesDir, "${WORK_DIR}/updates").apply { mkdirs() }
    }

    data class UpdateCandidate(
        val file: File,
        val versionCode: Long,
        val versionName: String?,
    )

    private data class RemoteCandidate(
        val fileName: String,
        val filePath: String,
        val versionTokens: List<Int>?,
        val repoDir: File? = null,
        val sourceFile: File? = null,
    ) {
        val label: String = versionTokens?.joinToString(".") ?: fileName
        fun cleanup() {
            repoDir?.deleteRecursively()
        }
    }

    suspend fun checkForUpdates(force: Boolean = false): UpdateCandidate? = withContext(Dispatchers.IO) {
        val startedAt = System.currentTimeMillis()
        runCatching {
            val updateConfig = resolveUpdateConfig()
            val updatesEnabled = credentialsStore.gitUpdateEnabled(updateConfig.filePath.isNotBlank())
            if (!updatesEnabled) {
                prefs.lastGitUpdateCheckAtMillis = startedAt
                recordStatus("Disabled or missing update filename")
                return@withContext null
            }

            if (updateConfig.repoUrl.isEmpty()) {
                prefs.lastGitUpdateCheckAtMillis = startedAt
                recordStatus("Missing repo URL")
                return@withContext null
            }

            val privateKey = updateConfig.privateKey ?: run {
                prefs.lastGitUpdateCheckAtMillis = startedAt
                recordStatus("Missing Git SSH key")
                return@withContext null
            }

            if (!force && shouldThrottle(startedAt)) {
                prefs.lastGitUpdateCheckAtMillis = startedAt
                recordStatus("Check skipped: recently checked")
                return@withContext null
            }

            val params = FetchParams(
                repoUrl = updateConfig.repoUrl,
                branch = updateConfig.branch,
                filePath = updateConfig.filePath,
                privateKey = privateKey
            )
            val candidate = findRemoteCandidate(params)
                ?: run {
                    prefs.lastGitUpdateCheckAtMillis = startedAt
                    recordStatus("Update APK not found (${params.filePath})")
                    return@withContext null
                }
            try {
                prefs.lastGitUpdateCheckAtMillis = startedAt
                val installed = installedVersionSignature()
                val candidateApprox = approximateVersion(candidate.versionTokens)
                val installedApprox = installed.approxVersion
                if (!force &&
                    candidateApprox != null &&
                    installedApprox != null &&
                    candidateApprox <= installedApprox
                ) {
                    recordStatus(
                        "No newer build (current=${installed.label}, candidate=${candidate.label})"
                    )
                    return@withContext null
                }

                val downloaded = runCatching { downloadCandidate(params, candidate) }.getOrElse { error ->
                    recordStatus("Download failed: ${error.message ?: error::class.java.simpleName}")
                    return@withContext null
                } ?: run {
                    recordStatus("Update APK not found (${params.filePath})")
                    return@withContext null
                }

                val apkInfo = parseApk(downloaded) ?: run {
                    downloaded.delete()
                    recordStatus("Invalid APK or package mismatch")
                    return@withContext null
                }
                val installedVersion = currentVersionCode()
                val storedInstalledVersion = prefs.latestInstalledGitUpdateVersion
                val normalizedInstalledVersion = when {
                    storedInstalledVersion > installedVersion -> {
                        prefs.latestInstalledGitUpdateVersion = installedVersion
                        installedVersion
                    }
                    installedVersion > storedInstalledVersion -> {
                        prefs.latestInstalledGitUpdateVersion = installedVersion
                        installedVersion
                    }
                    else -> storedInstalledVersion
                }
                val knownInstalledVersion = normalizedInstalledVersion
                if (apkInfo.versionCode <= knownInstalledVersion) {
                    downloaded.delete()
                    recordStatus("No newer build (current=$knownInstalledVersion, candidate=${apkInfo.versionCode})")
                    return@withContext null
                }
                if (!force && shouldSnooze(apkInfo.versionCode, startedAt)) {
                    downloaded.delete()
                    recordStatus("Update snoozed for version ${apkInfo.versionCode}")
                    return@withContext null
                }
                recordStatus("Update found v${apkInfo.versionName ?: apkInfo.versionCode} (code ${apkInfo.versionCode})")
                UpdateCandidate(downloaded, apkInfo.versionCode, apkInfo.versionName)
            } finally {
                candidate.cleanup()
            }
        }.getOrElse { error ->
            prefs.lastGitUpdateCheckAtMillis = startedAt
            recordStatus("Check failed: ${error.message ?: error::class.java.simpleName}")
            null
        }
    }

    fun remindLater(versionCode: Long) {
        prefs.snoozedGitUpdateVersionCode = versionCode
        prefs.snoozedGitUpdateAtMillis = System.currentTimeMillis()
    }

    fun install(candidate: UpdateCandidate) {
        if (!candidate.file.exists()) return
        TemplateAppBuilder(appContext).install(candidate.file)
    }

    private data class VersionSignature(
        val label: String,
        val approxVersion: Long?,
    )

    private fun installedVersionSignature(): VersionSignature {
        val packageInfo = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            appContext.packageManager.getPackageInfo(
                appContext.packageName,
                PackageManager.PackageInfoFlags.of(0)
            )
        } else {
            @Suppress("DEPRECATION")
            appContext.packageManager.getPackageInfo(appContext.packageName, 0)
        }
        val versionCode = PackageInfoCompat.getLongVersionCode(packageInfo)
        val versionName = packageInfo.versionName.orEmpty()
        val tokens = extractVersionTokens(versionName)
        val approx = approximateVersion(tokens) ?: versionCode
        val label = versionName.ifBlank { versionCode.toString() }
        return VersionSignature(label, approx)
    }

    private fun approximateVersion(tokens: List<Int>?): Long? {
        if (tokens.isNullOrEmpty()) return null
        var result = 0L
        tokens.forEach { token ->
            result = (result * 1_000L) + token.coerceAtLeast(0).coerceAtMost(999)
        }
        return result
    }

    private fun findRemoteCandidate(params: FetchParams): RemoteCandidate? {
        val wildcard = params.filePath.contains('*')
        if (!wildcard) {
            val name = File(params.filePath).name
            return RemoteCandidate(
                fileName = name,
                filePath = params.filePath,
                versionTokens = extractVersionTokens(name)
            )
        }
        val repoDir = runCatching { cloneRepo(params) }.getOrElse {
            Log.w(TAG, "Git update clone failed: ${it.message}")
            return null
        }
        val source = resolveTargetFile(repoDir, params.filePath)
        if (source == null || !source.exists() || !source.isFile) {
            repoDir.deleteRecursively()
            return null
        }
        return RemoteCandidate(
            fileName = source.name,
            filePath = source.name,
            versionTokens = extractVersionTokens(source.name),
            repoDir = repoDir,
            sourceFile = source
        )
    }

    private fun downloadCandidate(params: FetchParams, candidate: RemoteCandidate): File? {
        cleanupOldDownloads()
        candidate.sourceFile?.let { source ->
            val target = prepareDownloadTarget(source.name)
            source.copyTo(target, overwrite = true)
            return target.takeIf { it.length() > 0L }
        }
        fetchViaRawGithub(params)?.let { return it }
        val repoDir = runCatching { cloneRepo(params) }.getOrElse {
            Log.w(TAG, "Git update clone failed: ${it.message}")
            return null
        }
        return try {
            val source = resolveTargetFile(repoDir, params.filePath) ?: return null
            val target = prepareDownloadTarget(source.name)
            source.copyTo(target, overwrite = true)
            target.takeIf { it.length() > 0L }
        } finally {
            repoDir.deleteRecursively()
        }
    }

    private fun cloneRepo(params: FetchParams): File {
        val repoDir = prepareRepoDir()
        val keyFile = ensurePrivateKey(params.privateKey)
        val transportCallback = SshTransportCallback(keyFile.toPath())
        try {
            Git.cloneRepository()
                .setURI(params.repoUrl)
                .setDirectory(repoDir)
                .setCloneAllBranches(false)
                .setTimeout(30)
                .setDepth(1)
                .apply {
                    val branchRef = params.branch.takeIf { it.isNotBlank() }
                    if (branchRef != null) {
                        val ref = if (branchRef.startsWith("refs/")) branchRef else "refs/heads/$branchRef"
                        setBranch(ref)
                        setBranchesToClone(listOf(ref))
                    }
                    setTransportConfigCallback(transportCallback)
                }
                .call()
                .use { }
            return repoDir
        } catch (t: Throwable) {
            repoDir.deleteRecursively()
            throw t
        }
    }

    private fun resolveUpdateConfig(): UpdateConfig {
        val repoUrl = credentialsStore.appUpdateRepoUrl()?.trim()
            ?.ifBlank { null }
            ?: appDefaults.appUpdateRepoUrl.trim()
        val branch = credentialsStore.appUpdateBranch()?.trim()
            ?.ifBlank { null }
            ?: appDefaults.appUpdateBranch.ifBlank { DEFAULT_BRANCH }
        val configuredFile = credentialsStore.gitUpdateFileName()?.trim().orEmpty()
        val updateFilePath = configuredFile
            .ifBlank { appDefaults.appUpdateFileName }
            .ifBlank { DEFAULT_APK_FILE }
            .removePrefix("/")
        val privateKey = credentialsStore.appUpdatePrivateKey()?.takeIf { it.isNotBlank() }
            ?: appDefaults.appUpdatePrivateKey.takeIf { it.isNotBlank() }
        return UpdateConfig(repoUrl, branch, updateFilePath, privateKey)
    }

    private fun shouldThrottle(now: Long): Boolean {
        val lastCheck = prefs.lastGitUpdateCheckAtMillis
        if (lastCheck <= 0L) return false
        return now - lastCheck < CHECK_COOLDOWN_MS
    }

    private fun shouldSnooze(versionCode: Long, now: Long): Boolean {
        val snoozedVersion = prefs.snoozedGitUpdateVersionCode
        if (snoozedVersion <= 0L || snoozedVersion != versionCode) return false
        val snoozedAt = prefs.snoozedGitUpdateAtMillis
        return snoozedAt > 0L && now - snoozedAt < REMIND_COOLDOWN_MS
    }

    private fun currentVersionCode(): Long {
        val packageInfo = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            appContext.packageManager.getPackageInfo(
                appContext.packageName,
                PackageManager.PackageInfoFlags.of(0)
            )
        } else {
            @Suppress("DEPRECATION")
            appContext.packageManager.getPackageInfo(appContext.packageName, 0)
        }
        return PackageInfoCompat.getLongVersionCode(packageInfo)
    }

    private fun fetchViaRawGithub(params: FetchParams): File? {
        if (params.filePath.contains('*')) {
            return null
        }
        val rawUrl = buildRawGithubUrl(params.repoUrl, params.branch, params.filePath) ?: return null
        val request = Request.Builder()
            .url(rawUrl)
            .get()
            .build()
        return httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val body = response.body ?: return null
            val target = prepareDownloadTarget(params.filePath)
            body.byteStream().use { input ->
                target.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            if (target.length() <= 0L) {
                target.delete()
                return null
            }
            target
        }
    }

    private fun ensurePrivateKey(pem: String): File {
        val normalized = normalizePem(pem)
        val file = File(appContext.filesDir, "git_update_id_key").canonicalFile
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

    private fun normalizePem(pem: String): String {
        val trimmed = pem.trim().replace("\r\n", "\n")
        return if (trimmed.endsWith("\n")) trimmed else "$trimmed\n"
    }

    private fun prepareRepoDir(): File {
        val parent = File(appContext.cacheDir, "git_update_cache").apply {
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

    private fun prepareDownloadTarget(pathOrName: String): File {
        val safeName = File(pathOrName).name.takeIf { it.isNotBlank() } ?: DEFAULT_APK_FILE
        val file = File(updateDir, safeName)
        if (file.exists()) {
            file.delete()
        }
        return file
    }

    private fun cleanupOldDownloads() {
        updateDir.listFiles()?.forEach { file ->
            if (System.currentTimeMillis() - file.lastModified() > TimeUnit.DAYS.toMillis(3)) {
                runCatching { file.delete() }
            }
        }
    }

    private fun resolveTargetFile(repoDir: File, path: String): File? {
        val normalizedPath = path.removePrefix("/")
        if (!path.contains('*')) {
            val direct = File(repoDir, normalizedPath)
            if (direct.exists() && direct.isFile) {
                return direct
            }
            val normalizedTarget = File(normalizedPath).name.replace('-', '_').lowercase()
            val alternative = repoDir.walkTopDown()
                .maxDepth(32)
                .firstOrNull { file ->
                    file.isFile && file.name.replace('-', '_').lowercase() == normalizedTarget
                }
            return alternative ?: direct
        }
        val regex = globToRegex(normalizedPath)
        val candidates = repoDir.walkTopDown()
            .maxDepth(32)
            .filter { file ->
                if (!file.isFile) return@filter false
                val relativePath = runCatching { file.relativeTo(repoDir).invariantSeparatorsPath }
                    .getOrDefault(file.name)
                regex.matches(relativePath) || regex.matches(file.name)
            }
            .toList()
        if (candidates.isEmpty()) {
            val base = normalizedPath.substringBefore('*')
            if (base.isNotBlank()) {
                val relaxed = repoDir.walkTopDown()
                    .maxDepth(32)
                    .filter { file ->
                        file.isFile && file.name.contains(base, ignoreCase = true)
                    }
                    .toList()
                return pickBestCandidate(relaxed)
            }
        }
        return pickBestCandidate(candidates)
    }

    private fun parseApk(file: File): ApkMeta? {
        if (!file.exists() || file.length() <= 0L) return null
        val packageInfo = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            appContext.packageManager.getPackageArchiveInfo(
                file.absolutePath,
                PackageManager.PackageInfoFlags.of(0)
            )
        } else {
            @Suppress("DEPRECATION")
            appContext.packageManager.getPackageArchiveInfo(file.absolutePath, 0)
        } ?: return null
        packageInfo.applicationInfo?.apply {
            sourceDir = file.absolutePath
            publicSourceDir = file.absolutePath
        }
        if (packageInfo.packageName != appContext.packageName) {
            return null
        }
        val versionCode = PackageInfoCompat.getLongVersionCode(packageInfo)
        val versionName = packageInfo.versionName
        if (versionCode <= 0L) return null
        return ApkMeta(versionCode, versionName)
    }

    data class ApkMeta(
        val versionCode: Long,
        val versionName: String?
    )

    data class UpdateConfig(
        val repoUrl: String,
        val branch: String,
        val filePath: String,
        val privateKey: String?,
    )

    data class FetchParams(
        val repoUrl: String,
        val branch: String,
        val filePath: String,
        val privateKey: String,
    )

    companion object {
        private const val TAG = "GitUpdateChecker"
        private const val WORK_DIR = "app_builder"
        private const val DEFAULT_BRANCH = "main"
        private const val DEFAULT_APK_FILE = "tunnelview-update.apk"
        private val CHECK_COOLDOWN_MS = TimeUnit.HOURS.toMillis(3)
        private val REMIND_COOLDOWN_MS = TimeUnit.HOURS.toMillis(24)
        private val SCP_REGEX = Regex("^git@([^:]+):(.+)\$")
        private val HTTPS_REGEX = Regex("^https?://([^/]+)/(.+)\$")
        private val VERSION_REGEX = Regex("(\\d+(?:[._]\\d+)*)")

        internal fun buildRawGithubUrl(repoUrl: String, branch: String, filePath: String): String? {
            val repo = repoUrl.trim()
            val branchName = branch
                .removePrefix("refs/heads/")
                .ifBlank { DEFAULT_BRANCH }
            val normalizedPath = filePath.trim().removePrefix("/")
            if (normalizedPath.isEmpty()) return null
            if (normalizedPath.contains('*')) return null

            val scpMatch = SCP_REGEX.matchEntire(repo)
            if (scpMatch != null) {
                val host = scpMatch.groupValues[1].lowercase()
                if (host != "github.com") return null
                val path = scpMatch.groupValues[2]
                val (owner, project) = splitOwnerRepo(path) ?: return null
                return "https://raw.githubusercontent.com/$owner/$project/$branchName/$normalizedPath"
            }

            val httpsMatch = HTTPS_REGEX.matchEntire(repo)
            if (httpsMatch != null) {
                val host = httpsMatch.groupValues[1].lowercase()
                if (host != "github.com") return null
                val path = httpsMatch.groupValues[2]
                val (owner, project) = splitOwnerRepo(path) ?: return null
                return "https://raw.githubusercontent.com/$owner/$project/$branchName/$normalizedPath"
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

        private fun globToRegex(glob: String): Regex {
            val builder = StringBuilder()
            glob.forEach { ch ->
                when (ch) {
                    '*' -> builder.append(".*")
                    '-', '_' -> builder.append("[-_]")
                    else -> builder.append(Regex.escape(ch.toString()))
                }
            }
            return Regex("^${builder}$", RegexOption.IGNORE_CASE)
        }

        private fun pickBestCandidate(candidates: List<File>): File? {
            if (candidates.isEmpty()) return null
            return candidates.maxWithOrNull { first, second ->
                val firstVersion = extractVersionTokens(first.name)
                val secondVersion = extractVersionTokens(second.name)
                when (val compare = compareVersions(firstVersion, secondVersion)) {
                    0 -> first.lastModified().compareTo(second.lastModified())
                    else -> compare
                }
            }
        }

        private fun extractVersionTokens(name: String): List<Int>? {
            val baseName = name.substringBeforeLast('.', missingDelimiterValue = name)
            val match = VERSION_REGEX.findAll(baseName).lastOrNull() ?: return null
            val tokens = match.value.split('.', '_').mapNotNull { it.toIntOrNull() }
            return tokens.takeIf { it.isNotEmpty() }
        }

        private fun compareVersions(first: List<Int>?, second: List<Int>?): Int {
            if (first == null && second == null) return 0
            if (first == null) return -1
            if (second == null) return 1
            val maxSize = max(first.size, second.size)
            for (i in 0 until maxSize) {
                val a = first.getOrNull(i) ?: 0
                val b = second.getOrNull(i) ?: 0
                if (a != b) return a.compareTo(b)
            }
            return 0
        }
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

    private fun recordStatus(message: String) {
        prefs.lastGitUpdateStatus = message
        prefs.lastGitUpdateStatusAt = System.currentTimeMillis()
    }
}
