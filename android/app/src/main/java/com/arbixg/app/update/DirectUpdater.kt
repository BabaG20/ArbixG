package com.arbixg.app.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Direct APK updater. It never installs silently: Android's package installer
 * always owns the final user confirmation.
 *
 * The public manifest endpoint is intentionally isolated here. It must point
 * to a public HTTPS release-only repository; the application source repo stays private.
 */
object DirectUpdater {
    const val MANIFEST_URL =
        "https://raw.githubusercontent.com/BabaG20/ArbixG-Updates/main/latest.json"
    private const val PACKAGE_NAME = "com.arbixg.app"
    private const val MAX_APK_BYTES = 100L * 1024L * 1024L
    private const val FILE_PROVIDER_SUFFIX = ".updates"

    data class Release(
        val versionName: String,
        val versionCode: Long,
        val minSupportedVersionCode: Long,
        val apkUrl: String,
        val sha256: String,
        val releaseNotes: String
    )

    sealed class CheckResult {
        data object Current : CheckResult()
        data class Available(val release: Release) : CheckResult()
        data class Failed(val message: String) : CheckResult()
    }

    suspend fun check(context: Context): CheckResult = withContext(Dispatchers.IO) {
        try {
            val manifest = JSONObject(readHttps(MANIFEST_URL, 256 * 1024))
            require(manifest.optString("packageName") == PACKAGE_NAME) { "Package ya update si ArbixG." }
            val release = Release(
                versionName = manifest.getString("versionName"),
                versionCode = manifest.getLong("versionCode"),
                minSupportedVersionCode = manifest.getLong("minSupportedVersionCode"),
                apkUrl = manifest.getString("apkUrl"),
                sha256 = manifest.getString("sha256").lowercase(),
                releaseNotes = manifest.optString("releaseNotes")
            )
            require(release.versionName.matches(Regex("""\\d+\\.\\d+\\.\\d+(?:[-+][0-9A-Za-z.-]+)?"""))) { "versionName si sahihi." }
            require(release.versionCode > 0 && release.minSupportedVersionCode > 0) { "versionCode si sahihi." }
            require(release.apkUrl.startsWith("https://")) { "APK lazima ipakuliwe kupitia HTTPS." }
            require(release.sha256.matches(Regex("[a-f0-9]{64}"))) { "SHA-256 si sahihi." }
            val installed = installedVersion(context)
            require(installed >= release.minSupportedVersionCode) {
                "Toleo hili la app limepitwa na kiwango cha chini; update ya moja kwa moja imezuiwa."
            }
            if (release.versionCode <= installed) CheckResult.Current else CheckResult.Available(release)
        } catch (e: Exception) {
            CheckResult.Failed(e.message ?: "Imeshindikana kuangalia update.")
        }
    }

    suspend fun downloadAndOpenInstaller(context: Context, release: Release): String =
        withContext(Dispatchers.IO) {
            val apk = File(context.cacheDir, "arbixg-update.apk")
            try {
                downloadHttps(release.apkUrl, apk)
                val actualHash = sha256(apk)
                require(MessageDigest.isEqual(
                    actualHash.toByteArray(Charsets.US_ASCII),
                    release.sha256.toByteArray(Charsets.US_ASCII)
                )) { "APK imekataliwa: SHA-256 haifanani." }

                val archive = archiveInfo(context, apk)
                    ?: error("Android haikuweza kusoma APK iliyopakuliwa.")
                require(archive.packageName == PACKAGE_NAME) { "APK si ya ArbixG." }
                val archiveVersion = if (Build.VERSION.SDK_INT >= 28) {
                    archive.longVersionCode
                } else {
                    @Suppress("DEPRECATION")
                    archive.versionCode.toLong()
                }
                require(archiveVersion == release.versionCode) { "versionCode ya APK haifanani na manifest." }
                require(signerDigests(context, archive) == signerDigests(context, installedInfo(context))) {
                    "Sahihi ya APK ni tofauti; update imezuiwa ili kulinda app na data zako."
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                    !context.packageManager.canRequestPackageInstalls()
                ) {
                    val settings = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                        data = Uri.parse("package:$PACKAGE_NAME")
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(settings)
                    return@withContext "Ruhusu ArbixG kusakinisha updates kwenye Settings, kisha bonyeza Install tena."
                }

                val uri = FileProvider.getUriForFile(
                    context,
                    PACKAGE_NAME + FILE_PROVIDER_SUFFIX,
                    apk
                )
                val install = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "application/vnd.android.package-archive")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(install)
                "APK imethibitishwa. Kamilisha uthibitisho kwenye Android installer."
            } catch (e: Exception) {
                apk.delete()
                e.message ?: "Imeshindikana kuandaa update."
            }
        }

    private fun readHttps(address: String, maxBytes: Int): String {
        val connection = openHttps(address)
        try {
            require(connection.contentLengthLong <= maxBytes || connection.contentLengthLong < 0) {
                "Manifest imezidi ukubwa unaoruhusiwa."
            }
            return connection.inputStream.bufferedReader().use { it.readText() }
                .also { require(it.toByteArray().size <= maxBytes) { "Manifest imezidi ukubwa unaoruhusiwa." } }
        } finally { connection.disconnect() }
    }

    private fun downloadHttps(address: String, target: File) {
        val connection = openHttps(address)
        try {
            require(connection.contentLengthLong <= MAX_APK_BYTES || connection.contentLengthLong < 0) {
                "APK imezidi ukubwa unaoruhusiwa."
            }
            connection.inputStream.use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        require(total <= MAX_APK_BYTES) { "APK imezidi ukubwa unaoruhusiwa." }
                        output.write(buffer, 0, count)
                    }
                }
            }
        } finally { connection.disconnect() }
    }

    private fun openHttps(address: String): HttpURLConnection {
        var current = URL(address)
        repeat(5) {
            require(current.protocol == "https") { "HTTPS pekee inaruhusiwa." }
            val connection = current.openConnection() as HttpURLConnection
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("User-Agent", "ArbixG-Android-Updater")
            val code = connection.responseCode
            if (code in 300..399) {
                val next = connection.getHeaderField("Location") ?: error("Redirect haina Location.")
                connection.disconnect()
                current = URL(current, next)
                require(current.protocol == "https") { "Redirect isiyo salama imekataliwa." }
            } else {
                require(code in 200..299) { "Server imerudisha HTTP $code." }
                return connection
            }
        }
        error("Redirect nyingi kupita kiasi.")
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun installedVersion(context: Context): Long {
        val info = installedInfo(context)
        return if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else {
            @Suppress("DEPRECATION") info.versionCode.toLong()
        }
    }

    private fun installedInfo(context: Context): PackageInfo =
        if (Build.VERSION.SDK_INT >= 33) {
            context.packageManager.getPackageInfo(PACKAGE_NAME, PackageManager.PackageInfoFlags.of(
                (if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES).toLong()
            ))
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(PACKAGE_NAME, if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES)
        }

    private fun archiveInfo(context: Context, file: File): PackageInfo? =
        if (Build.VERSION.SDK_INT >= 33) {
            context.packageManager.getPackageArchiveInfo(file.absolutePath, PackageManager.PackageInfoFlags.of(
                (if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES).toLong()
            ))
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageArchiveInfo(file.absolutePath, if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES)
        }

    private fun signerDigests(context: Context, info: PackageInfo): Set<String> {
        val signatures = if (Build.VERSION.SDK_INT >= 28) {
            info.signingInfo?.apkContentsSigners
        } else {
            @Suppress("DEPRECATION") info.signatures
        } ?: error("APK haina taarifa za signing certificate.")
        return signatures.map { signature ->
            MessageDigest.getInstance("SHA-256").digest(signature.toByteArray())
                .joinToString("") { "%02x".format(it) }
        }.toSet().also { require(it.isNotEmpty()) { "Signing certificate haipo." } }
    }
}
