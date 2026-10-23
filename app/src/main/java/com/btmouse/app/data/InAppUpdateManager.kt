package com.btmouse.app.data

import android.content.Context
import android.content.Intent
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
import java.net.HttpURLConnection
import java.net.URL

data class ReleaseInfo(
    val tagName: String,
    val releaseName: String,
    val releaseNotes: String,
    val downloadUrl: String,
    val apkSize: Long = 0L
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
            pInfo.versionName?.removeSuffix("-debug") ?: "1.1.2"
        } catch (e: Exception) {
            "1.1.2"
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
            val assets = root.getAsJsonArray("assets")
            if (assets != null) {
                for (element in assets) {
                    val asset = element.asJsonObject
                    val name = asset.get("name")?.asString ?: ""
                    if (name.endsWith(".apk", ignoreCase = true)) {
                        apkUrl = asset.get("browser_download_url")?.asString
                        apkSize = asset.get("size")?.asLong ?: 0L
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
                    apkSize = apkSize
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

            val url = URL(release.downloadUrl)
            val connection = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 15000
                readTimeout = 15000
                instanceFollowRedirects = true
            }

            val totalBytes = if (connection.contentLengthLong > 0) connection.contentLengthLong else release.apkSize
            val input = connection.inputStream
            val output = FileOutputStream(apkFile)

            val buffer = ByteArray(8192)
            var bytesRead: Int
            var downloadedBytes = 0L

            while (input.read(buffer).also { bytesRead = it } != -1) {
                output.write(buffer, 0, bytesRead)
                downloadedBytes += bytesRead

                if (totalBytes > 0) {
                    val progress = downloadedBytes.toFloat() / totalBytes
                    val downloadedMb = downloadedBytes / (1024f * 1024f)
                    val totalMb = totalBytes / (1024f * 1024f)
                    _updateState.value = UpdateState.Downloading(progress, downloadedMb, totalMb)
                }
            }

            output.flush()
            output.close()
            input.close()

            _updateState.value = UpdateState.ReadyToInstall(apkFile)

            withContext(Dispatchers.Main) {
                launchApkInstaller(apkFile)
            }
        } catch (e: Exception) {
            Log.e(tag, "Download failed", e)
            _updateState.value = UpdateState.Error("Download failed: ${e.message}")
        }
    }

    fun launchApkInstaller(apkFile: File) {
        try {
            val apkUri: Uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                apkFile
            )

            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(apkUri, "application/vnd.android.package-archive")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
            }

            context.startActivity(installIntent)
        } catch (e: Exception) {
            Log.e(tag, "Install launch error", e)
            _updateState.value = UpdateState.Error("Please install downloaded APK from Downloads or Browser: ${e.message}")
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
