package com.tunedroid.app.engine

import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

data class DownloadProgress(
    val percent: Float,
    val totalSize: String,
    val speed: String,
    val eta: String
)

sealed class DownloadResult {
    data class Success(val filePath: String, val fileSize: Long) : DownloadResult()
    data class Error(val message: String) : DownloadResult()
    data object Cancelled : DownloadResult()
}

object AudioDownloader {

    suspend fun download(
        url: String,
        outputDir: String,
        formatId: String = "bestaudio/best",
        onProgress: (DownloadProgress) -> Unit = {},
        isCancelled: () -> Boolean = { false }
    ): DownloadResult = withContext(Dispatchers.IO) {
        try {
            // Wait for engine to be ready AND updated (update fixes 403 errors)
            val app = com.tunedroid.app.TuneDroidApp.instance
            app.awaitEngineReady()
            app.awaitEngineUpdated()

            val outputFile = File(outputDir)
            if (!outputFile.exists()) {
                outputFile.mkdirs()
            }

            val request = YoutubeDLRequest(url)
            request.addOption("-f", formatId)
            request.addOption("-o", "$outputDir/%(title)s.%(ext)s")
            request.addOption("--no-playlist")
            request.addOption("--no-mtime")
            // Avoid 403 errors with proper headers and retries
            request.addOption("--extractor-retries", "3")
            request.addOption("--force-ipv4")
            request.addOption("--no-check-certificates")

            val processId = url.hashCode().toString()

            val response = YoutubeDL.getInstance().execute(
                request,
                processId
            ) { progress, etaInSeconds, line ->
                if (isCancelled()) {
                    YoutubeDL.getInstance().destroyProcessById(processId)
                    return@execute
                }
                onProgress(
                    DownloadProgress(
                        percent = progress,
                        totalSize = "",
                        speed = "",
                        eta = if (etaInSeconds > 0) "${etaInSeconds}s" else ""
                    )
                )
            }

            if (isCancelled()) {
                // Clean up partial files
                cleanupPartialFiles(outputDir)
                return@withContext DownloadResult.Cancelled
            }

            // Find the downloaded file
            val downloadedFile = findDownloadedFile(outputDir)
            if (downloadedFile != null) {
                DownloadResult.Success(
                    filePath = downloadedFile.absolutePath,
                    fileSize = downloadedFile.length()
                )
            } else {
                DownloadResult.Error("Download completed but file not found")
            }

        } catch (e: Exception) {
            if (isCancelled()) {
                cleanupPartialFiles(outputDir)
                DownloadResult.Cancelled
            } else {
                DownloadResult.Error(e.message ?: "Download failed")
            }
        }
    }

    fun cancel(url: String) {
        val processId = url.hashCode().toString()
        try {
            YoutubeDL.getInstance().destroyProcessById(processId)
        } catch (_: Exception) {}
    }

    private fun findDownloadedFile(dir: String): File? {
        val directory = File(dir)
        return directory.listFiles()
            ?.filter { it.isFile && !it.name.endsWith(".part") }
            ?.maxByOrNull { it.lastModified() }
    }

    private fun cleanupPartialFiles(dir: String) {
        val directory = File(dir)
        directory.listFiles()?.forEach { file ->
            if (file.name.endsWith(".part") || file.name.endsWith(".temp")) {
                file.delete()
            }
        }
    }
}
