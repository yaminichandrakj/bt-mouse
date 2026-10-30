package com.btmouse.app.data

import android.content.Context
import android.content.Intent
import android.content.ClipData
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.net.HttpURLConnection
import java.net.URL

data class ReleaseInfo(
    val tagName: String,
    val releaseName: String,
    val releaseNotes: String,
    val downloadUrl: String,
    val apkSize: Long = 0L,
    val sha256: String? = null
)

sealed class UpdateState {
    object Idle : UpdateState()
    object Checking : UpdateState()
    object NoUpdateAvailable : UpdateState()
    data class UpdateAvailable(val release: ReleaseInfo) : UpdateState()
    data class Downloading(val progress: Float, val downloadedMb: Float, val totalMb: Float) : UpdateState()
    data class ReadyToInstall(val apkFile: File) : UpdateState()
    data class Error(val message: String) : UpdateState()
}

class InAppUpdateManager(private val context: Context) {

    private val tag = "InAppUpdateManager"
    private var awaitingInstallPermission = false
    private val repoOwner = "yaminichandrakj"
    private val repoName = "bt-mouse"
    val currentVersion: String
        get() = try {
            val pInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(context.packageName, android.content.pm.PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(context.packageName, 0)
            }
            pInfo.versionName?.removeSuffix("-debug") ?: "1.2.0"
        } catch (e: Exception) {
            "1.2.0"
        }

    fun openDownloadInBrowser(url: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(tag, "Failed to open browser", e)
        }
    }

    private val _updateState = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val updateState: StateFlow<UpdateState> = _updateState.asStateFlow()

    suspend fun checkForUpdates(): UpdateState = withContext(Dispatchers.IO) {
        _updateState.value = UpdateState.Checking
        try {
            val apiUrl = "https://api.github.com/repos/$repoOwner/$repoName/releases/latest"
            val url = URL(apiUrl)
            val connection = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 8000
                readTimeout = 8000
                setRequestProperty("Accept", "application/vnd.github.v3+json")
                setRequestProperty("User-Agent", "BT-Mouse-Android-App")
            }

            if (connection.responseCode != 200) {
                val errorMsg = "GitHub API response: ${connection.responseCode}"
                _updateState.value = UpdateState.Error(errorMsg)
                return@withContext _updateState.value
            }

            val jsonText = connection.inputStream.bufferedReader().use { it.readText() }
            val gson = Gson()
            val root = gson.fromJson(jsonText, JsonObject::class.java)

            val tagName = root.get("tag_name")?.asString ?: ""
            val releaseName = root.get("name")?.asString ?: tagName
            val releaseNotes = root.get("body")?.asString ?: ""

            // Find APK asset
            var apkUrl: String? = null
            var apkSize = 0L
            var apkSha256: String? = null
            val assets = root.getAsJsonArray("assets")
            if (assets != null) {
                for (element in assets) {
                    val asset = element.asJsonObject
                    val name = asset.get("name")?.asString ?: ""
                    if (name.endsWith(".apk", ignoreCase = true)) {
                        apkUrl = asset.get("browser_download_url")?.asString
                        apkSize = asset.get("size")?.asLong ?: 0L
                        apkSha256 = asset.get("digest")?.asString
                            ?.takeIf { it.startsWith("sha256:", ignoreCase = true) }
                            ?.substringAfter(":")
                        break
                    }
                }
            }

            if (apkUrl.isNullOrEmpty()) {
                _updateState.value = UpdateState.NoUpdateAvailable
                return@withContext _updateState.value
            }

            val isNewer = isVersionNewer(tagName, currentVersion)
            if (isNewer) {
                val releaseInfo = ReleaseInfo(
                    tagName = tagName,
                    releaseName = releaseName,
                    releaseNotes = releaseNotes,
                    downloadUrl = apkUrl,
                    apkSize = apkSize,
                    sha256 = apkSha256
                )
                _updateState.value = UpdateState.UpdateAvailable(releaseInfo)
            } else {
                _updateState.value = UpdateState.NoUpdateAvailable
            }

            _updateState.value
        } catch (e: Exception) {
            Log.e(tag, "Error checking for updates", e)
            _updateState.value = UpdateState.Error(e.message ?: "Failed to check for updates")
            _updateState.value
        }
    }

    suspend fun downloadAndInstall(release: ReleaseInfo) = withContext(Dispatchers.IO) {
        try {
            val updatesDir = File(context.cacheDir, "updates").apply { mkdirs() }
            val apkFile = File(updatesDir, "BT-Mouse-${release.tagName}.apk")
            val partialFile = File(updatesDir, "BT-Mouse-${release.tagName}.apk.part")

            val url = URL(release.downloadUrl)
            val connection = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 15000
                readTimeout = 15000
                instanceFollowRedirects = true
            }

            try {
                val responseCode = connection.responseCode
                if (responseCode !in 200..299) {
                    throw IllegalStateException("APK download failed (HTTP $responseCode)")
                }
                val totalBytes = if (connection.contentLengthLong > 0) connection.contentLengthLong else release.apkSize
                var downloadedBytes = 0L
                val digest = MessageDigest.getInstance("SHA-256")
                connection.inputStream.use { input ->
                    FileOutputStream(partialFile).use { output ->
                        val buffer = ByteArray(32 * 1024)
                        while (true) {
                            val bytesRead = input.read(buffer)
                            if (bytesRead < 0) break
                            output.write(buffer, 0, bytesRead)
                            digest.update(buffer, 0, bytesRead)
                            downloadedBytes += bytesRead

                            if (totalBytes > 0) {
                                val progress = downloadedBytes.toFloat() / totalBytes
                                val downloadedMb = downloadedBytes / (1024f * 1024f)
                                val totalMb = totalBytes / (1024f * 1024f)
                                _updateState.value = UpdateState.Downloading(progress.coerceIn(0f, 1f), downloadedMb, totalMb)
                            }
                        }
                        output.fd.sync()
                    }
                }
                if (downloadedBytes == 0L || (totalBytes > 0 && downloadedBytes != totalBytes)) {
                    throw IllegalStateException("The APK download was incomplete. Please retry.")
                }
                if (release.sha256 != null && !digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
                        .equals(release.sha256, ignoreCase = true)) {
                    throw IllegalStateException("The downloaded APK failed its SHA-256 integrity check. Please retry.")
                }
                validateDownloadedApk(partialFile, release)
                if (apkFile.exists() && !apkFile.delete()) {
                    throw IllegalStateException("Could not replace the previous downloaded update.")
                }
                if (!partialFile.renameTo(apkFile)) {
                    throw IllegalStateException("Could not finalize the downloaded update file.")
                }

                _updateState.value = UpdateState.ReadyToInstall(apkFile)

                withContext(Dispatchers.Main) {
                    launchApkInstaller(apkFile)
                }
            } finally {
                connection.disconnect()
            }
        } catch (e: Exception) {
            Log.e(tag, "Download failed", e)
            _updateState.value = UpdateState.Error("Download failed: ${e.message}")
        }
    }

    fun launchApkInstaller(apkFile: File) {
        try {
            if (!apkFile.isFile || apkFile.length() == 0L) {
                _updateState.value = UpdateState.Error("The downloaded APK is missing or incomplete. Download it again.")
                return
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                !context.packageManager.canRequestPackageInstalls()) {
                awaitingInstallPermission = true
                val permissionIntent = Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:${context.packageName}")
                ).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK }
                context.startActivity(permissionIntent)
                // Keep the APK available; the settings screen retries on return.
                _updateState.value = UpdateState.ReadyToInstall(apkFile)
                return
            }

            val apkUri: Uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                apkFile
            )

            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(apkUri, "application/vnd.android.package-archive")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
                clipData = ClipData.newRawUri("Lanyard update", apkUri)
            }

            context.startActivity(installIntent)
            awaitingInstallPermission = false
        } catch (e: Exception) {
            Log.e(tag, "Install launch error", e)
            _updateState.value = UpdateState.Error("Please install downloaded APK from Downloads or Browser: ${e.message}")
        }
    }

    /** Resume the installer after Android returns from per-source install permission settings. */
    fun onHostResumed() {
        if (!awaitingInstallPermission || Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        if (context.packageManager.canRequestPackageInstalls()) {
            awaitingInstallPermission = false
            val apkFile = (_updateState.value as? UpdateState.ReadyToInstall)?.apkFile ?: return
            launchApkInstaller(apkFile)
        }
    }

    private fun validateDownloadedApk(apkFile: File, release: ReleaseInfo) {
        val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.getPackageArchiveInfo(
                apkFile.absolutePath,
                android.content.pm.PackageManager.PackageInfoFlags.of(0)
            )
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageArchiveInfo(apkFile.absolutePath, 0)
        } ?: throw IllegalStateException("The downloaded file is not a readable Android APK.")

        if (packageInfo.packageName != context.packageName) {
            throw IllegalStateException("This release APK belongs to a different app and cannot update Lanyard.")
        }
        val apkVersion = packageInfo.versionName?.removeSuffix("-debug")
            ?: throw IllegalStateException("The APK does not contain a version name.")
        if (!apkVersion.equals(release.tagName.removePrefix("v"), ignoreCase = true)) {
            throw IllegalStateException(
                "Release version ${release.tagName} does not match APK version $apkVersion. Please report this release mismatch."
            )
        }
    }

    private fun isVersionNewer(remoteTag: String, localVersion: String): Boolean {
        val cleanRemote = remoteTag.removePrefix("v").trim()
        val cleanLocal = localVersion.removePrefix("v").trim()

        if (cleanRemote == cleanLocal) return false

        val remoteParts = cleanRemote.split(".").mapNotNull { it.toIntOrNull() }
        val localParts = cleanLocal.split(".").mapNotNull { it.toIntOrNull() }

        val length = maxOf(remoteParts.size, localParts.size)
        for (i in 0 until length) {
            val r = remoteParts.getOrElse(i) { 0 }
            val l = localParts.getOrElse(i) { 0 }
            if (r > l) return true
            if (r < l) return false
        }
        return false
    }
}
