package com.tunedroid.app.updater

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.tunedroid.app.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

data class AppUpdate(
    val versionTag: String,
    val downloadUrl: String,
    val isNewer: Boolean
)

object AppUpdateChecker {

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
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(update.downloadUrl))
                context.startActivity(intent)
            }
            .setNegativeButton("Later", null)
            .show()
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
