package com.zahah.tunnelview.ssh

import android.content.Context
import android.os.SystemClock
import com.zahah.tunnelview.R
import com.zahah.tunnelview.logging.ConnEvent
import com.zahah.tunnelview.logging.ConnLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.schmizz.sshj.DefaultConfig
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.connection.channel.direct.LocalPortForwarder
import net.schmizz.sshj.connection.channel.direct.Parameters
import net.schmizz.sshj.transport.verification.FingerprintVerifier
import net.schmizz.sshj.transport.verification.HostKeyVerifier
import net.schmizz.sshj.transport.verification.PromiscuousVerifier
import net.schmizz.sshj.userauth.UserAuthException
import net.schmizz.sshj.userauth.keyprovider.KeyProvider
import net.schmizz.sshj.common.SecurityUtils
import org.spongycastle.jce.provider.BouncyCastleProvider
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStreamReader
import java.net.Inet4Address
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URLEncoder
import java.net.UnknownHostException
import java.security.Security
import javax.net.ssl.HttpsURLConnection
import java.net.URL
import org.json.JSONObject

/**
 * Thin wrapper around SSHJ responsible for building and configuring [SSHClient] instances,
 * enforcing cipher/KEX preferences, host key verification, and providing an [ActiveTunnel]
 * abstraction once the forwarding is configured.
 */
class SshClient(
    private val context: Context,
    private val logger: ConnLogger,
) {

    data class TunnelParams(
        val sshHost: String,
        val sshPort: Int,
        val sshUser: String,
        val localPort: Int,
        val remoteHost: String,
        val remotePort: Int,
        val connectTimeoutMillis: Int,
        val socketTimeoutMillis: Int,
        val keepAliveIntervalSeconds: Int,
        val fingerprintSha256: String?,
        val usePassword: Boolean,
        val password: String?,
        val privateKeyPem: String?,
        val forceIpv4: Boolean,
        val strictHostKey: Boolean,
        val attempt: Int,
    )

    suspend fun openTunnel(params: TunnelParams): ActiveTunnel =
        withContext(Dispatchers.IO) {
            ensureSecurityProviders()
            val config = buildConfig()
            val sshClient = SSHClient(config)
            val verifier = buildHostKeyVerifier(params)
            sshClient.addHostKeyVerifier(verifier)
            sshClient.connectTimeout = params.connectTimeoutMillis
            sshClient.timeout = params.socketTimeoutMillis

            val resolvedHost = resolveHost(params.sshHost, params.forceIpv4)
            val hostUsed = resolvedHost?.first ?: params.sshHost
            if (resolvedHost == null && params.forceIpv4) {
                throw TunnelConnectException(
                    ConnEvent.Phase.DNS,
                    IllegalStateException("No IPv4 records available for ${params.sshHost}")
                )
            }

            resolvedHost?.let { (resolvedAddress, durationMillis) ->
                logger.log(
                    ConnEvent(
                        timestampMillis = System.currentTimeMillis(),
                        level = ConnEvent.Level.DEBUG,
                        phase = ConnEvent.Phase.DNS,
                        message = "Resolved ${params.sshHost} -> $resolvedAddress",
                        durationMillis = durationMillis,
                        attempt = params.attempt,
                    )
                )
            }

            var serverSocket: ServerSocket? = null
            var forwarder: LocalPortForwarder? = null
            try {
                runCatching {
                    val startTcp = SystemClock.elapsedRealtime()
                    sshClient.connect(hostUsed, params.sshPort)
                    val tcpDuration = SystemClock.elapsedRealtime() - startTcp
                    logger.log(
                        ConnEvent(
                            timestampMillis = System.currentTimeMillis(),
                            level = ConnEvent.Level.INFO,
                            phase = ConnEvent.Phase.TCP,
                            message = "Connected to $hostUsed:${params.sshPort}",
                            durationMillis = tcpDuration,
                            attempt = params.attempt,
                        )
                    )
                }.onFailure { error ->
                    val phase = when (error) {
                        is UnknownHostException -> ConnEvent.Phase.DNS
                        is IOException -> ConnEvent.Phase.TCP
                        else -> ConnEvent.Phase.SSH_HANDSHAKE
                    }
                    throw TunnelConnectException(phase, error)
                }

                try {
                    configureKeepAlive(sshClient, params.keepAliveIntervalSeconds)
                } catch (error: Throwable) {
                    logger.log(
                        ConnEvent(
                            timestampMillis = System.currentTimeMillis(),
                            level = ConnEvent.Level.WARN,
                            phase = ConnEvent.Phase.KEEPALIVE,
                            message = "Failed to configure keepalive: ${error.message}",
                            attempt = params.attempt,
                            throwableClass = error::class.java.name,
                            throwableMessage = error.message,
                        )
                    )
                }

                authenticate(sshClient, params)

                serverSocket = ServerSocket(params.localPort, 0, InetAddress.getByName("127.0.0.1")).apply {
                    reuseAddress = true
                }

                val forwardingParams = Parameters(
                    "127.0.0.1",
                    params.localPort,
                    params.remoteHost,
                    params.remotePort
                )
                forwarder = sshClient.newLocalPortForwarder(forwardingParams, serverSocket)

                logger.log(
                    ConnEvent(
                        timestampMillis = System.currentTimeMillis(),
                        level = ConnEvent.Level.INFO,
                        phase = ConnEvent.Phase.FORWARD,
                        message = "Forwarding 127.0.0.1:${params.localPort} -> ${params.remoteHost}:${params.remotePort}",
                        attempt = params.attempt,
                    )
                )

                ActiveTunnel(sshClient, forwarder, serverSocket)
            } catch (error: Throwable) {
                forwarder?.let { runCatching { it.close() } }
                serverSocket?.let { runCatching { it.close() } }
                runCatching { sshClient.disconnect() }
                runCatching { sshClient.close() }
                when (error) {
                    is TunnelConnectException -> throw error
                    else -> throw TunnelConnectException(ConnEvent.Phase.OTHER, error)
                }
            }
        }

    suspend fun testHandshake(params: TunnelParams) {
        val tunnel = openTunnel(params)
        tunnel.close()
    }

    private fun buildHostKeyVerifier(params: TunnelParams): HostKeyVerifier {
        val fingerprint = params.fingerprintSha256?.trim().orEmpty()
        return if (fingerprint.isNotEmpty()) {
            val normalized = if (fingerprint.startsWith("SHA256:", ignoreCase = true)) {
                fingerprint
            } else {
                "SHA256:$fingerprint"
            }
            FingerprintVerifier.getInstance(normalized)
        } else if (!params.strictHostKey) {
            PromiscuousVerifier()
        } else {
            throw TunnelConnectException(
                ConnEvent.Phase.SSH_HANDSHAKE,
                IllegalStateException("Host key fingerprint required in strict mode")
            )
        }
    }

    private suspend fun authenticate(client: SSHClient, params: TunnelParams) {
        val start = SystemClock.elapsedRealtime()
        try {
            if (params.usePassword) {
                val password = params.password.orEmpty()
                if (password.isEmpty()) {
                    throw TunnelConnectException(
                        ConnEvent.Phase.AUTH,
                        IllegalStateException("Password authentication selected but password is empty")
                    )
                }
                client.authPassword(params.sshUser, password)
            } else {
                val keyFile = preparePrivateKey(params.privateKeyPem)
                val keyProvider: KeyProvider = client.loadKeys(keyFile.absolutePath, null as CharArray?)
                client.authPublickey(params.sshUser, keyProvider)
            }
            val duration = SystemClock.elapsedRealtime() - start
            logger.log(
                ConnEvent(
                    timestampMillis = System.currentTimeMillis(),
                    level = ConnEvent.Level.INFO,
                    phase = ConnEvent.Phase.AUTH,
                    message = "Authenticated as ${params.sshUser}",
                    durationMillis = duration,
                    attempt = params.attempt,
                )
            )
        } catch (error: Throwable) {
            val phase = if (error is UserAuthException) ConnEvent.Phase.AUTH else ConnEvent.Phase.SSH_HANDSHAKE
            throw TunnelConnectException(phase, error)
        }
    }

    private suspend fun preparePrivateKey(overridePem: String?): File = withContext(Dispatchers.IO) {
        val keyFile = File(context.filesDir, "id_ed25519")
        val pem = overridePem?.takeIf { it.isNotBlank() }?.normalizePem()
            ?: loadBundledSshKeyPem()
            ?: throw TunnelConnectException(
                ConnEvent.Phase.AUTH,
                IllegalStateException("Nenhuma chave privada SSH configurada")
            )

        if (!pem.contains("BEGIN") || !pem.contains("END")) {
            throw TunnelConnectException(
                ConnEvent.Phase.AUTH,
                IllegalStateException("Formato de chave SSH inválido")
            )
        }

        val desiredBytes = pem.toByteArray()
        val currentBytes = runCatching { keyFile.readBytes() }.getOrNull()
        if (currentBytes == null || !currentBytes.contentEquals(desiredBytes)) {
            keyFile.parentFile?.mkdirs()
            FileOutputStream(keyFile, false).use { it.write(desiredBytes) }
            keyFile.setReadable(false, false)
            keyFile.setWritable(false, false)
            keyFile.setExecutable(false, false)
            keyFile.setReadable(true, true)
            keyFile.setWritable(true, true)
        }

        if (!keyFile.exists()) {
            throw TunnelConnectException(
                ConnEvent.Phase.AUTH,
                IllegalStateException("SSH private key material missing at ${keyFile.absolutePath}")
            )
        }
        keyFile
    }

    private fun loadBundledSshKeyPem(): String? {
        return runCatching {
            context.resources.openRawResource(R.raw.id_ed25519)
                .bufferedReader()
                .use { it.readText() }
        }.getOrNull()?.takeIf { it.isNotBlank() }?.normalizePem()
    }

    private fun String.normalizePem(): String {
        val trimmed = trim()
        val withNewlines = trimmed.replace("\r\n", "\n")
        return if (withNewlines.endsWith("\n")) withNewlines else "$withNewlines\n"
    }

    private fun buildConfig(): DefaultConfig {
        return DefaultConfig().apply {
            keyExchangeFactories = listOf(
                net.schmizz.sshj.transport.kex.Curve25519SHA256.Factory(),
                net.schmizz.sshj.transport.kex.ECDHNistP.Factory256(),
                net.schmizz.sshj.transport.kex.ECDHNistP.Factory384(),
                net.schmizz.sshj.transport.kex.ECDHNistP.Factory521(),
                com.hierynomus.sshj.transport.kex.DHGroups.Group14SHA256(),
                com.hierynomus.sshj.transport.kex.DHGroups.Group14SHA1(),
            )
            cipherFactories = listOf(
                com.hierynomus.sshj.transport.cipher.ChachaPolyCiphers.CHACHA_POLY_OPENSSH(),
                com.hierynomus.sshj.transport.cipher.GcmCiphers.AES128GCM(),
                com.hierynomus.sshj.transport.cipher.GcmCiphers.AES256GCM(),
                com.hierynomus.sshj.transport.cipher.BlockCiphers.AES128CTR(),
            )
            macFactories = listOf(
                com.hierynomus.sshj.transport.mac.Macs.HMACSHA2256Etm(),
                com.hierynomus.sshj.transport.mac.Macs.HMACSHA2512Etm(),
                com.hierynomus.sshj.transport.mac.Macs.HMACSHA1Etm(),
            )
        }
    }

    private fun configureKeepAlive(client: SSHClient, intervalSeconds: Int) {
        val keepAlive = client.connection.keepAlive
        keepAlive.setKeepAliveInterval(intervalSeconds)
    }

    private fun resolveHost(host: String, forceIpv4: Boolean): Pair<String, Long>? {
        val start = SystemClock.elapsedRealtime()
        val addresses = runCatching { InetAddress.getAllByName(host) }.getOrNull()
        val chosen = addresses
            ?.let { addrs ->
                if (forceIpv4) {
                    addrs.firstOrNull { it is Inet4Address }?.hostAddress
                } else {
                    addrs.firstOrNull()?.hostAddress
                }
            }
        if (chosen != null) {
            return chosen to (SystemClock.elapsedRealtime() - start)
        }

        val dohResolved = runCatching { resolveHostViaDnsOverHttps(host, forceIpv4) }.getOrNull()
        if (dohResolved != null) {
            return dohResolved to (SystemClock.elapsedRealtime() - start)
        }

        if (forceIpv4) {
            throw TunnelConnectException(
                ConnEvent.Phase.DNS,
                UnknownHostException("Unable to resolve IPv4 address for $host")
            )
        }
        return null
    }

    private fun resolveHostViaDnsOverHttps(host: String, forceIpv4: Boolean): String? {
        val recordTypes = if (forceIpv4) listOf("A") else listOf("A", "AAAA")
        for (type in recordTypes) {
            val result = queryDnsRecord(host, type)
            if (result != null) return result
        }
        return null
    }

    private fun queryDnsRecord(host: String, type: String): String? {
        return try {
            val encodedHost = URLEncoder.encode(host, Charsets.UTF_8.name())
            val url = URL("https://dns.google/resolve?name=$encodedHost&type=$type")
            val connection = (url.openConnection() as HttpsURLConnection).apply {
                connectTimeout = DNS_TIMEOUT_MS
                readTimeout = DNS_TIMEOUT_MS
                setRequestProperty("Accept", "application/dns-json")
            }
            connection.inputStream.use { stream ->
                val body = BufferedReader(InputStreamReader(stream)).use { it.readText() }
                val json = JSONObject(body)
                val status = json.optInt("Status", -1)
                if (status != 0) return null
                val answers = json.optJSONArray("Answer") ?: return null
                for (i in 0 until answers.length()) {
                    val answer = answers.optJSONObject(i) ?: continue
                    val answerType = answer.optInt("type", -1)
                    val data = answer.optString("data", "").trim()
                    if (data.isEmpty()) continue
                    val isIpv4 = answerType == 1 && type == "A"
                    val isIpv6 = answerType == 28 && type == "AAAA"
                    if (isIpv4 || isIpv6) {
                        return data
                    }
                }
                null
            }.also { connection.disconnect() }
        } catch (_: Throwable) {
            null
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun ensureSecurityProviders() {
        try {
            SecurityUtils.setRegisterBouncyCastle(false)
        } catch (_: Throwable) {
        }

        val providerName = SecurityUtils.SPONGY_CASTLE
        if (Security.getProvider(providerName) == null) {
            runCatching {
                Security.addProvider(BouncyCastleProvider())
            }
        }
        runCatching {
            SecurityUtils.setSecurityProvider(providerName)
            SecurityUtils.registerSecurityProvider(providerName)
        }
    }

    class ActiveTunnel(
        private val sshClient: SSHClient,
        private val forwarder: LocalPortForwarder,
        private val serverSocket: ServerSocket,
    ) {
        fun listenBlocking() {
            forwarder.listen()
        }

        fun close() {
            runCatching { forwarder.close() }
            runCatching { serverSocket.close() }
            runCatching { sshClient.disconnect() }
            runCatching { sshClient.close() }
        }

        fun isBound(): Boolean = serverSocket.isBound
    }

    class TunnelConnectException(
        val phase: ConnEvent.Phase,
        cause: Throwable,
    ) : Exception(cause)

    companion object {
        private const val DNS_TIMEOUT_MS = 5_000
    }
}
