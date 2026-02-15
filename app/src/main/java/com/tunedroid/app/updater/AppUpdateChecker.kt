package com.tunedroid.app.updater

import android.app.AlertDialog
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.util.Log
import android.widget.Toast
import androidx.core.content.FileProvider
import com.tunedroid.app.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

data class AppUpdate(
    val versionTag: String,
    val downloadUrl: String,
    val isNewer: Boolean
)

object AppUpdateChecker {

    private const val TAG = "TuneDroid"

    private const val RELEASES_API_URL =
        "https://api.github.com/repos/llmspace/tunedroid-releases/releases/latest"

    private var currentDownloadId: Long = -1L

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
                downloadAndInstallApk(context, update.downloadUrl, update.versionTag)
            }
            .setNegativeButton("Later", null)
            .show()
    }

    private fun downloadAndInstallApk(context: Context, url: String, versionTag: String) {
        try {
            val apkFile = File(context.externalCacheDir, "tunedroid-update.apk")
            if (apkFile.exists()) apkFile.delete()

            val request = DownloadManager.Request(Uri.parse(url)).apply {
                setTitle("TuneDroid Update")
                setDescription("Downloading $versionTag...")
                setDestinationUri(Uri.fromFile(apkFile))
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setMimeType("application/vnd.android.package-archive")
            }

            val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            currentDownloadId = dm.enqueue(request)

            Toast.makeText(context, "Downloading update...", Toast.LENGTH_SHORT).show()

            val receiver = object : BroadcastReceiver() {
                override fun onReceive(ctx: Context, intent: Intent) {
                    val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
                    if (id != currentDownloadId) return

                    try {
                        ctx.applicationContext.unregisterReceiver(this)
                    } catch (_: Exception) { }
                    handleDownloadComplete(ctx, dm, id, apkFile)
                }
            }

            val filter = IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.applicationContext.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                context.applicationContext.registerReceiver(receiver, filter)
            }

        } catch (e: Exception) {
            Log.e(TAG, "Failed to start APK download", e)
            Toast.makeText(context, "Download failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun handleDownloadComplete(
        context: Context,
        dm: DownloadManager,
        downloadId: Long,
        apkFile: File
    ) {
        val query = DownloadManager.Query().setFilterById(downloadId)
        val cursor = dm.query(query)

        if (cursor != null && cursor.moveToFirst()) {
            val statusIndex = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)
            val status = cursor.getInt(statusIndex)

            if (status == DownloadManager.STATUS_SUCCESSFUL) {
                installApk(context, apkFile)
            } else {
                val reasonIndex = cursor.getColumnIndex(DownloadManager.COLUMN_REASON)
                val reason = cursor.getInt(reasonIndex)
                Log.e(TAG, "APK download failed. Status: $status, Reason: $reason")
                Toast.makeText(context, "Download failed. Please try again.", Toast.LENGTH_LONG).show()
            }
            cursor.close()
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
            Toast.makeText(context, "Could not open installer: ${e.message}", Toast.LENGTH_LONG).show()
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
