package com.zahah.tunnelview.appbuilder

import android.content.ActivityNotFoundException
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import com.android.apksig.ApkSigner
import com.zahah.tunnelview.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo
import org.bouncycastle.cert.X509v3CertificateBuilder
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.bouncycastle.openssl.PEMParser
import org.bouncycastle.openssl.PEMKeyPair
import org.bouncycastle.openssl.PEMEncryptedKeyPair
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter
import org.bouncycastle.cert.X509CertificateHolder
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.io.StringReader
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.Security
import java.security.cert.X509Certificate
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.text.Charsets

class TemplateAppBuilder(private val context: Context) {

    suspend fun build(request: AppBuildRequest): AppBuildResult = withContext(Dispatchers.IO) {
        val sanitizedName = request.appName.trim()
        val sanitizedPackage = request.packageName.trim()
        val basePackage = context.packageName
        require(sanitizedName.isNotBlank()) { "Nome do app não pode ser vazio" }
        require(PACKAGE_REGEX.matches(sanitizedPackage)) { "Nome de pacote inválido" }

        val workDir = File(context.filesDir, WORK_DIR_NAME).apply { mkdirs() }
        val baseApk = File(workDir, "base-template.apk")
        extractBaseApk(baseApk)
        ensureValidZip(baseApk, stage = "Extração do template")

        val unsignedApk = File(workDir, "custom-${UUID.randomUUID()}.apk")
        val manifestBytes = ZipFile(baseApk).use { zip ->
            val manifestEntry = zip.getEntry(MANIFEST_ENTRY) ?: error("Manifest not found in template")
            zip.getInputStream(manifestEntry).use { it.readBytes() }
        }

        val patchedManifest = ManifestPatcher.patch(manifestBytes, basePackage, sanitizedPackage, sanitizedName)
        val iconAssets = prepareIconAssets(request.iconBytes, request.iconMimeType)
        val extraEntries = mutableMapOf<String, ByteArray>()
        buildAppDefaultsPayload(request)?.let { payload ->
            extraEntries[APP_DEFAULTS_ASSET_PATH] = payload
        }
        repackageApk(baseApk, unsignedApk, patchedManifest, iconAssets, extraEntries)
        ensureValidZip(unsignedApk, stage = "Reempacotamento")

        val signedApk = File(workDir, "custom-signed-${UUID.randomUUID()}.apk")
        try {
            signApk(unsignedApk, signedApk, request.customSigning, sanitizedName)
        } catch (e: Exception) {
            throw IllegalStateException("Assinatura falhou: ${e.message}", e)
        }
        unsignedApk.delete()

        val fileDisplayName = buildFileName(sanitizedName)
        val downloadUri = saveToDownloads(signedApk, fileDisplayName)
        signedApk.delete()

        AppBuildResult(downloadUri, fileDisplayName, sanitizedName, sanitizedPackage)
    }

    private fun applyArscReplacements(
        original: ByteArray,
        replacements: Map<String, String>
    ): ByteArray {
        if (replacements.isEmpty()) return original
        val buffer = original.copyOf()
        replacements.forEach { (from, to) ->
            require(from.length == to.length) { "Substituições de resources.arsc devem manter o tamanho" }
            val fromBytes = from.toByteArray(Charsets.UTF_8)
            val toBytes = to.toByteArray(Charsets.UTF_8)
            var index = 0
            var replaced = false
            while (index <= buffer.size - fromBytes.size) {
                var match = true
                for (offset in fromBytes.indices) {
                    if (buffer[index + offset] != fromBytes[offset]) {
                        match = false
                        break
                    }
                }
                if (match) {
                    for (offset in toBytes.indices) {
                        buffer[index + offset] = toBytes[offset]
                    }
                    replaced = true
                    index += fromBytes.size
                } else {
                    index++
                }
            }
            if (!replaced) {
                throw IllegalStateException("Não foi possível localizar '$from' em resources.arsc para substituição")
            }
        }
        return buffer
    }

    private fun prepareIconAssets(iconBytes: ByteArray?, iconMimeType: String?): IconAssets? {
        val bytes = iconBytes ?: return null
        if (bytes.isEmpty()) return null
        val normalizedMime = iconMimeType?.lowercase(Locale.ROOT)
        if (normalizedMime != null && normalizedMime !in SUPPORTED_ICON_MIME_TYPES && !normalizedMime.startsWith("image/")) {
            throw IllegalArgumentException("Formato de ícone não suportado: $iconMimeType")
        }
        val sourceBitmap = decodeIconBitmap(bytes)
        try {
            val processedBitmap = renderIconBitmap(sourceBitmap)
            val pngBytes = processedBitmap.toPngByteArray()
            processedBitmap.recycle()
            return IconAssets(
                entriesToRemove = setOf(IC_LAUNCHER_FOREGROUND_XML_PATH),
                entriesToAdd = mapOf(IC_LAUNCHER_FOREGROUND_PNG_PATH to pngBytes),
                arscStringReplacements = mapOf(
                    IC_LAUNCHER_FOREGROUND_XML_PATH to IC_LAUNCHER_FOREGROUND_PNG_PATH
                )
            )
        } finally {
            sourceBitmap.recycle()
        }
    }

    private fun decodeIconBitmap(bytes: ByteArray): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            throw IllegalArgumentException("Imagem de ícone inválida")
        }
        val maxDimension = maxOf(bounds.outWidth, bounds.outHeight)
        var sampleSize = 1
        while (maxDimension / sampleSize > MAX_ICON_DECODE_PX) {
            sampleSize = sampleSize shl 1
        }
        val decodeOptions = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inSampleSize = sampleSize
        }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOptions)
            ?: throw IllegalArgumentException("Falha ao processar a imagem de ícone selecionada")
    }

    private fun renderIconBitmap(source: Bitmap): Bitmap {
        val target = Bitmap.createBitmap(ICON_OUTPUT_PX, ICON_OUTPUT_PX, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(target)
        canvas.drawARGB(0, 0, 0, 0)
        val scale = min(
            ICON_OUTPUT_PX.toFloat() / source.width,
            ICON_OUTPUT_PX.toFloat() / source.height
        )
        val scaledWidth = maxOf(1, (source.width * scale).roundToInt())
        val scaledHeight = maxOf(1, (source.height * scale).roundToInt())
        val left = (ICON_OUTPUT_PX - scaledWidth) / 2
        val top = (ICON_OUTPUT_PX - scaledHeight) / 2
        val dstRect = Rect(left, top, left + scaledWidth, top + scaledHeight)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        canvas.drawBitmap(source, null, dstRect, paint)
        return target
    }

    private fun Bitmap.toPngByteArray(): ByteArray {
        val output = ByteArrayOutputStream()
        if (!compress(Bitmap.CompressFormat.PNG, 100, output)) {
            throw IllegalStateException("Falha ao converter o ícone para PNG")
        }
        return output.toByteArray()
    }

    private fun buildAppDefaultsPayload(request: AppBuildRequest): ByteArray? {
        val fallbackHost = BuildConfig.DEFAULT_INTERNAL_HOST.orEmpty()
        val fallbackPort = BuildConfig.DEFAULT_INTERNAL_PORT.orEmpty()
        val fallbackSshUser = BuildConfig.DEFAULT_SSH_USER.orEmpty()
        val fallbackGitRepo = BuildConfig.DEFAULT_GIT_REPO_URL.orEmpty()
        val fallbackGitFile = BuildConfig.DEFAULT_GIT_FILE_PATH.orEmpty()
        val fallbackSshKey = BuildConfig.DEFAULT_SSH_PRIVATE_KEY.orEmpty()
        val fallbackGitKey = BuildConfig.DEFAULT_GIT_PRIVATE_KEY.orEmpty()
        val host = request.defaultInternalHost
        val port = request.defaultInternalPort
        val sshUser = request.defaultSshUser
        val gitRepo = request.defaultGitRepoUrl
        val gitFile = request.defaultGitFilePath
        val sshKey = request.defaultSshPrivateKey
        val gitKey = request.defaultGitPrivateKey
        if (
            host == fallbackHost &&
            port == fallbackPort &&
            sshUser == fallbackSshUser &&
            gitRepo == fallbackGitRepo &&
            gitFile == fallbackGitFile &&
            sshKey == fallbackSshKey &&
            gitKey == fallbackGitKey
        ) {
            return null
        }
        val json = JSONObject().apply {
            put("internalHost", host)
            put("internalPort", port)
            put("sshUser", sshUser)
            put("gitRepoUrl", gitRepo)
            put("gitFilePath", gitFile)
            put("sshPrivateKey", sshKey)
            put("gitPrivateKey", gitKey)
        }
        return json.toString().toByteArray(Charsets.UTF_8)
    }

    private data class IconAssets(
        val entriesToRemove: Set<String>,
        val entriesToAdd: Map<String, ByteArray>,
        val arscStringReplacements: Map<String, String>
    )

    private fun ZipOutputStream.copyEntry(entry: ZipEntry, source: ZipFile, buffer: ByteArray) {
        val newEntry = ZipEntry(entry.name).apply {
            time = entry.time
            comment = entry.comment
            extra = entry.extra
            method = entry.method
            if (entry.method == ZipEntry.STORED) {
                size = entry.size
                compressedSize = entry.compressedSize
                crc = entry.crc
            }
        }
        putNextEntry(newEntry)
        source.getInputStream(entry).use { input ->
            input.copyTo(this, buffer.size)
        }
        closeEntry()
    }

    private fun ZipOutputStream.writeEntryWithData(
        original: ZipEntry,
        data: ByteArray,
        method: Int
    ) {
        val newEntry = ZipEntry(original.name).apply {
            time = original.time
            comment = original.comment
            extra = original.extra
            this.method = method
            if (method == ZipEntry.STORED) {
                val crcValue = CRC32().apply { update(data) }.value
                size = data.size.toLong()
                compressedSize = data.size.toLong()
                crc = crcValue
            }
        }
        putNextEntry(newEntry)
        write(data)
        closeEntry()
    }

    fun install(apkUri: Uri) {
        val installIntent = buildInstallIntent(apkUri)
            ?: throw IllegalStateException("Nenhum instalador compatível encontrado no dispositivo")
        grantUriPermissionToInstallers(apkUri, installIntent)
        try {
            context.startActivity(installIntent)
        } catch (activityError: ActivityNotFoundException) {
            throw IllegalStateException("Falha ao abrir o instalador do Android", activityError)
        }
    }

    private fun buildInstallIntent(apkUri: Uri): Intent? {
        val baseFlags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
        val viewerIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(apkUri, APK_MIME_TYPE)
            addFlags(baseFlags)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                addFlags(Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
            }
            putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true)
            putExtra(Intent.EXTRA_INSTALLER_PACKAGE_NAME, context.packageName)
            putExtra(Intent.EXTRA_RETURN_RESULT, true)
            addCategory(Intent.CATEGORY_DEFAULT)
        }
        return viewerIntent.takeIf { canResolveIntent(it) }
    }

    private fun canResolveIntent(intent: Intent): Boolean {
        return context.packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY) != null
    }

    private fun extractBaseApk(target: File) {
        if (target.exists()) target.delete()
        context.assets.open(BASE_TEMPLATE_TAR).use { rawStream ->
            TarArchiveInputStream(BufferedInputStream(rawStream)).use { tar ->
                var entry: TarArchiveEntry? = tar.nextEntry
                while (entry != null) {
                    val tarEntry = entry
                    val cleanName = tarEntry.name.substringAfterLast('/')
                    if (!tarEntry.isDirectory && cleanName.endsWith(".apk") && !cleanName.startsWith("._")) {
                        BufferedOutputStream(FileOutputStream(target)).use { output ->
                            tar.copyTo(output, STREAM_BUFFER_SIZE)
                            output.flush()
                        }
                        break
                    }
                    entry = tar.nextEntry
                }
            }
        }
        if (!target.exists() || target.length() == 0L) {
            throw IllegalStateException("Template APK não encontrado dentro do pacote base")
        }
    }

    private fun repackageApk(
        baseApk: File,
        outputApk: File,
        manifestBytes: ByteArray,
        iconAssets: IconAssets?,
        additionalEntries: Map<String, ByteArray>
    ) {
        if (outputApk.exists()) outputApk.delete()
        val entriesToRemove = iconAssets?.entriesToRemove ?: emptySet()
        val entriesToAdd = buildMap {
            iconAssets?.entriesToAdd?.let { putAll(it) }
            if (additionalEntries.isNotEmpty()) {
                putAll(additionalEntries)
            }
        }
        val arscReplacements = iconAssets?.arscStringReplacements ?: emptyMap()
        ZipFile(baseApk).use { zip ->
            ZipOutputStream(BufferedOutputStream(FileOutputStream(outputApk))).use { out ->
                val buffer = ByteArray(STREAM_BUFFER_SIZE)
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    if (entry.name.startsWith("META-INF/") || entriesToRemove.contains(entry.name)) {
                        continue
                    }
                    when (entry.name) {
                        MANIFEST_ENTRY -> {
                            out.writeEntryWithData(entry, manifestBytes, entry.method)
                        }
                        RESOURCES_ARSC_ENTRY -> {
                            if (arscReplacements.isEmpty()) {
                                out.copyEntry(entry, zip, buffer)
                            } else {
                                val patched = zip.getInputStream(entry).use { input ->
                                    val original = input.readBytes()
                                    applyArscReplacements(original, arscReplacements)
                                }
                                out.writeEntryWithData(entry, patched, ZipEntry.STORED)
                            }
                        }
                        else -> out.copyEntry(entry, zip, buffer)
                    }
                }
                if (entriesToAdd.isNotEmpty()) {
                    entriesToAdd.forEach { (path, data) ->
                        val entry = ZipEntry(path).apply {
                            time = System.currentTimeMillis()
                            method = ZipEntry.DEFLATED
                        }
                        out.putNextEntry(entry)
                        out.write(data)
                        out.closeEntry()
                    }
                }
            }
        }
    }


    private fun signApk(
        inputApk: File,
        outputApk: File,
        customSigning: CustomSigningConfig?,
        appName: String
    ) {
        if (outputApk.exists()) outputApk.delete()
        ensureProvider()
        val signerConfig = createSignerConfig(customSigning, appName)
        ApkSigner.Builder(listOf(signerConfig))
            .setInputApk(inputApk)
            .setOutputApk(outputApk)
            .setV1SigningEnabled(true)
            .setV2SigningEnabled(true)
            .setV3SigningEnabled(true)
            .build()
            .sign()
    }

    private fun ensureProvider() {
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(BouncyCastleProvider())
        }
    }

    private fun createSignerConfig(customSigning: CustomSigningConfig?, appName: String): ApkSigner.SignerConfig {
        return if (customSigning == null) {
            val keyPair = generateKeyPair()
            val certificate = generateCertificate(keyPair, appName)
            ApkSigner.SignerConfig.Builder(DEFAULT_KEY_ALIAS, keyPair.private, listOf(certificate)).build()
        } else {
            val privateKey = parsePrivateKey(customSigning.privateKeyPem)
            val certificate = parseCertificate(customSigning.certificatePem)
            ApkSigner.SignerConfig.Builder(customSigning.alias.ifBlank { DEFAULT_KEY_ALIAS }, privateKey, listOf(certificate)).build()
        }
    }

    private fun generateKeyPair(): KeyPair {
        val generator = KeyPairGenerator.getInstance("RSA")
        generator.initialize(2048)
        return generator.generateKeyPair()
    }

    private fun generateCertificate(keyPair: KeyPair, appName: String): X509Certificate {
        val now = Date()
        val notBefore = Date(now.time - CERT_VALIDITY_OFFSET_MS)
        val notAfter = Date(now.time + CERT_VALIDITY_MS)
        val commonName = appName.ifBlank { "Android Debug" }
        val subject = X500Name("CN=$commonName,O=Android,C=US")
        val builder = X509v3CertificateBuilder(
            subject,
            now.time.toBigInteger(),
            notBefore,
            notAfter,
            subject,
            SubjectPublicKeyInfo.getInstance(keyPair.public.encoded)
        )
        val signer = JcaContentSignerBuilder("SHA256withRSA")
            .setProvider(BouncyCastleProvider())
            .build(keyPair.private)
        val holder = builder.build(signer)
        return JcaX509CertificateConverter()
            .setProvider(BouncyCastleProvider())
            .getCertificate(holder)
    }

    private fun parsePrivateKey(pem: String): PrivateKey {
        require(pem.isNotBlank()) { "Chave privada não informada" }
        PEMParser(StringReader(pem)).use { parser ->
            val obj = parser.readObject() ?: error("Chave privada inválida")
            val converter = JcaPEMKeyConverter().setProvider(BouncyCastleProvider())
            return when (obj) {
                is PEMKeyPair -> converter.getKeyPair(obj).private
                is org.bouncycastle.asn1.pkcs.PrivateKeyInfo -> converter.getPrivateKey(obj)
                is org.bouncycastle.openssl.PEMEncryptedKeyPair -> error("Chave privada criptografada não suportada")
                else -> error("Formato de chave privada não suportado")
            }
        }
    }

    private fun parseCertificate(pem: String): X509Certificate {
        require(pem.isNotBlank()) { "Certificado não informado" }
        PEMParser(StringReader(pem)).use { parser ->
            val obj = parser.readObject() ?: error("Certificado inválido")
            val holder = when (obj) {
                is X509CertificateHolder -> obj
                else -> error("Formato de certificado não suportado")
            }
            return JcaX509CertificateConverter()
                .setProvider(BouncyCastleProvider())
                .getCertificate(holder)
        }
    }

    private fun ensureValidZip(file: File, stage: String) {
        if (!file.exists() || file.length() == 0L) {
            throw IllegalStateException("$stage falhou: arquivo ${file.absolutePath} ausente ou vazio")
        }
        try {
            ZipFile(file).use { /* validation only */ }
        } catch (zipError: Exception) {
            throw IllegalStateException("$stage falhou: ${zipError.message}", zipError)
        }
    }

    private fun saveToDownloads(source: File, fileName: String): Uri {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, APK_MIME_TYPE)
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("Não foi possível acessar a pasta Downloads")
            resolver.openOutputStream(uri)?.use { output ->
                source.inputStream().use { input -> input.copyTo(output) }
            } ?: throw IllegalStateException("Falha ao gravar APK em Downloads")
            ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
                .also { resolver.update(uri, it, null, null) }
            uri
        } else {
            val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            if (!downloadsDir.exists()) downloadsDir.mkdirs()
            val target = File(downloadsDir, fileName)
            source.inputStream().use { input ->
                FileOutputStream(target).use { output -> input.copyTo(output) }
            }
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", target)
        }
    }

    private fun buildFileName(appName: String): String {
        val sanitized = appName.lowercase(Locale.getDefault())
            .map { ch ->
                when {
                    ch.isLetterOrDigit() -> ch
                    ch == '-' -> '-'
                    else -> '_'
                }
            }
            .joinToString("")
            .trim('_')
            .ifBlank { "custom_app" }
        return "$sanitized.apk"
    }

    private fun grantUriPermissionToInstallers(uri: Uri, intent: Intent) {
        val resolveInfos = context.packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
        resolveInfos.forEach { info ->
            val packageName = info.activityInfo?.packageName ?: return@forEach
            try {
                context.grantUriPermission(packageName, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: SecurityException) {
            }
        }
    }

    companion object {
        private const val BASE_TEMPLATE_TAR = "base_template_apk.tar"
        private const val MANIFEST_ENTRY = "AndroidManifest.xml"
        private const val RESOURCES_ARSC_ENTRY = "resources.arsc"
        private const val WORK_DIR_NAME = "app_builder"
        private const val APK_MIME_TYPE = "application/vnd.android.package-archive"
        private const val DEFAULT_KEY_ALIAS = "custom"
        private const val CERT_VALIDITY_MS = 365L * 24L * 60L * 60L * 1000L
        private const val CERT_VALIDITY_OFFSET_MS = 24L * 60L * 60L * 1000L
        private const val STREAM_BUFFER_SIZE = 64 * 1024
        private const val ICON_OUTPUT_PX = 432
        private const val MAX_ICON_DECODE_PX = 2048
        private const val IC_LAUNCHER_FOREGROUND_XML_PATH = "res/drawable/ic_launcher_foreground.xml"
        private const val IC_LAUNCHER_FOREGROUND_PNG_PATH = "res/drawable/ic_launcher_foreground.png"
        private const val APP_DEFAULTS_ASSET_PATH = "assets/app_defaults.json"
        private val SUPPORTED_ICON_MIME_TYPES = setOf(
            "image/png",
            "image/jpeg",
            "image/jpg"
        )
        private val PACKAGE_REGEX = Regex("^[a-zA-Z][A-Za-z0-9_]*(\\.[a-zA-Z][A-Za-z0-9_]*)+")

        private fun Long.toBigInteger() = java.math.BigInteger.valueOf(this)
    }
}

data class AppBuildRequest(
    val appName: String,
    val packageName: String,
    val defaultInternalHost: String = BuildConfig.DEFAULT_INTERNAL_HOST.orEmpty(),
    val defaultInternalPort: String = BuildConfig.DEFAULT_INTERNAL_PORT.orEmpty(),
    val defaultSshUser: String = BuildConfig.DEFAULT_SSH_USER.orEmpty(),
    val defaultGitRepoUrl: String = BuildConfig.DEFAULT_GIT_REPO_URL.orEmpty(),
    val defaultGitFilePath: String = BuildConfig.DEFAULT_GIT_FILE_PATH.orEmpty(),
    val defaultSshPrivateKey: String = BuildConfig.DEFAULT_SSH_PRIVATE_KEY.orEmpty(),
    val defaultGitPrivateKey: String = BuildConfig.DEFAULT_GIT_PRIVATE_KEY.orEmpty(),
    val iconBytes: ByteArray? = null,
    val iconMimeType: String? = null,
    val customSigning: CustomSigningConfig? = null
)

data class AppBuildResult(
    val downloadUri: Uri,
    val fileName: String,
    val appName: String,
    val packageName: String
)

data class CustomSigningConfig(
    val alias: String,
    val privateKeyPem: String,
    val certificatePem: String
)
