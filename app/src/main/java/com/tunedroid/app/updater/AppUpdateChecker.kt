package com.tunedroid.app.updater

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.FileProvider
import com.tunedroid.app.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

data class AppUpdate(
    val versionTag: String,
    val downloadUrl: String,
    val isNewer: Boolean
)

/** Progress state for the update download UI. */
sealed class UpdateDownloadState {
    data object Idle : UpdateDownloadState()
    data class Downloading(val progress: Float) : UpdateDownloadState() // 0f..1f
    data object Installing : UpdateDownloadState()
    data object Done : UpdateDownloadState()
    data class Failed(val message: String) : UpdateDownloadState()
}

object AppUpdateChecker {

    private const val TAG = "TuneDroid"

    private const val RELEASES_API_URL =
        "https://api.github.com/repos/llmspace/tunedroid-releases/releases/latest"

    suspend fun check(context: Context): AppUpdate? = withContext(Dispatchers.IO) {
        try {
            val url = URL(RELEASES_API_URL)
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.setRequestProperty("Accept", "application/vnd.github.v3+json")
            connection.connectTimeout = 10000
            connection.readTimeout = 10000

            if (connection.responseCode != 200) return@withContext null

            val response = connection.inputStream.bufferedReader().readText()
            connection.disconnect()

            val json = com.google.gson.JsonParser.parseString(response).asJsonObject
            val tagName = json.get("tag_name")?.asString ?: return@withContext null

            // Find APK asset download URL
            val assets = json.getAsJsonArray("assets")
            var apkUrl: String? = null
            if (assets != null) {
                for (asset in assets) {
                    val assetObj = asset.asJsonObject
                    val name = assetObj.get("name")?.asString ?: ""
                    if (name.endsWith(".apk")) {
                        apkUrl = assetObj.get("browser_download_url")?.asString
                        break
                    }
                }
            }

            if (apkUrl == null) return@withContext null

            // Compare versions
            val remoteVersion = tagName.removePrefix("v")
            val currentVersion = BuildConfig.VERSION_NAME
            val isNewer = compareVersions(remoteVersion, currentVersion) > 0

            AppUpdate(
                versionTag = tagName,
                downloadUrl = apkUrl,
                isNewer = isNewer
            )

        } catch (_: Exception) {
            null
        }
    }

    fun showUpdateDialog(context: Context, update: AppUpdate) {
        if (!update.isNewer) return

        AlertDialog.Builder(context)
            .setTitle("Update Available")
            .setMessage("A new version of TuneDroid (${update.versionTag}) is available. Update now?")
            .setPositiveButton("Update") { _, _ ->
                // No-op — caller should use downloadAndInstall with progress callback instead
            }
            .setNegativeButton("Later", null)
            .show()
    }

    /**
     * Show update dialog that triggers a download with progress reporting.
     * The onStateChange callback is invoked on the main thread.
     */
    fun showUpdateDialogWithProgress(
        context: Context,
        update: AppUpdate,
        onStateChange: (UpdateDownloadState) -> Unit,
        onStartDownload: (suspend () -> Unit) -> Unit
    ) {
        if (!update.isNewer) return

        AlertDialog.Builder(context)
            .setTitle("Update Available")
            .setMessage("A new version of TuneDroid (${update.versionTag}) is available. Update now?")
            .setPositiveButton("Update") { _, _ ->
                onStartDownload {
                    downloadAndInstall(context, update.downloadUrl, onStateChange)
                }
            }
            .setNegativeButton("Later", null)
            .show()
    }

    /**
     * Download APK with progress and install.
     * Must be called from a coroutine scope — runs download on IO dispatcher.
     */
    suspend fun downloadAndInstall(
        context: Context,
        url: String,
        onStateChange: (UpdateDownloadState) -> Unit
    ) {
        try {
            withContext(Dispatchers.Main) {
                onStateChange(UpdateDownloadState.Downloading(0f))
            }

            val apkFile = withContext(Dispatchers.IO) {
                val file = File(context.externalCacheDir, "tunedroid-update.apk")
                if (file.exists()) file.delete()

                val connection = URL(url).openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.connectTimeout = 15000
                connection.readTimeout = 30000
                connection.connect()

                if (connection.responseCode != 200) {
                    throw Exception("HTTP ${connection.responseCode}")
                }

                val totalBytes = connection.contentLength.toLong()
                var downloadedBytes = 0L

                connection.inputStream.use { input ->
                    FileOutputStream(file).use { output ->
                        val buffer = ByteArray(8192)
                        var bytesRead: Int
                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            output.write(buffer, 0, bytesRead)
                            downloadedBytes += bytesRead
                            if (totalBytes > 0) {
                                val progress = (downloadedBytes.toFloat() / totalBytes).coerceIn(0f, 1f)
                                withContext(Dispatchers.Main) {
                                    onStateChange(UpdateDownloadState.Downloading(progress))
                                }
                            }
                        }
                    }
                }
                connection.disconnect()
                file
            }

            withContext(Dispatchers.Main) {
                onStateChange(UpdateDownloadState.Installing)
            }

            installApk(context, apkFile)

            withContext(Dispatchers.Main) {
                onStateChange(UpdateDownloadState.Done)
            }

        } catch (e: Exception) {
            Log.e(TAG, "Update download failed", e)
            withContext(Dispatchers.Main) {
                onStateChange(UpdateDownloadState.Failed(e.message ?: "Unknown error"))
            }
        }
    }

    private fun installApk(context: Context, apkFile: File) {
        try {
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                apkFile
            )

            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch APK installer", e)
            throw e
        }
    }

    private fun compareVersions(v1: String, v2: String): Int {
        val parts1 = v1.split(".").map { it.toIntOrNull() ?: 0 }
        val parts2 = v2.split(".").map { it.toIntOrNull() ?: 0 }
        val maxLen = maxOf(parts1.size, parts2.size)
        for (i in 0 until maxLen) {
            val p1 = parts1.getOrElse(i) { 0 }
            val p2 = parts2.getOrElse(i) { 0 }
            if (p1 != p2) return p1.compareTo(p2)
        }
        return 0
    }
}
