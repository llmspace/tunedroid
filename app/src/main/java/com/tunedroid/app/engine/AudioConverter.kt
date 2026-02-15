package com.tunedroid.app.engine

import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

sealed class ConversionResult {
    data class Success(val outputPath: String, val fileSize: Long) : ConversionResult()
    data class Error(val message: String) : ConversionResult()
    data object Cancelled : ConversionResult()
}

object AudioConverter {

    @Volatile
    private var cancelled = false

    suspend fun convertToMp3(
        inputPath: String,
        outputDir: String,
        targetBitrate: Int,
        title: String,
        artist: String,
        thumbnailPath: String? = null,
        durationSeconds: Long,
        onProgress: (Float) -> Unit = {},
        isCancelled: () -> Boolean = { false }
    ): ConversionResult = withContext(Dispatchers.IO) {
        cancelled = false
        try {
            val inputFile = File(inputPath)
            val baseName = inputFile.nameWithoutExtension
            val outputPath = "$outputDir/$baseName.mp3"
            val outputFile = File(outputPath)

            // Use the extraction engine's built-in post-processing for conversion
            val request = YoutubeDLRequest(inputPath)
            request.addOption("--extract-audio")
            request.addOption("--audio-format", "mp3")
            request.addOption("--audio-quality", "${targetBitrate}k")
            request.addOption("-o", outputPath)

            // Add metadata
            val safeTitle = title.replace("\"", "\\\"")
            val safeArtist = artist.replace("\"", "\\\"")
            request.addOption("--postprocessor-args", "-metadata title=\"$safeTitle\" -metadata artist=\"$safeArtist\"")

            if (thumbnailPath != null && File(thumbnailPath).exists()) {
                request.addOption("--embed-thumbnail")
            }

            val processId = "convert_${System.currentTimeMillis()}"

            YoutubeDL.getInstance().execute(request, processId) { progress, _, _ ->
                if (isCancelled() || cancelled) {
                    YoutubeDL.getInstance().destroyProcessById(processId)
                    return@execute
                }
                onProgress(progress.coerceIn(0f, 100f))
            }

            if (isCancelled() || cancelled) {
                outputFile.delete()
                return@withContext ConversionResult.Cancelled
            }

            if (outputFile.exists() && outputFile.length() > 0) {
                ConversionResult.Success(
                    outputPath = outputPath,
                    fileSize = outputFile.length()
                )
            } else {
                // Try to find the output file (engine may have named it differently)
                val dir = File(outputDir)
                val mp3File = dir.listFiles()?.firstOrNull { it.extension == "mp3" }
                if (mp3File != null && mp3File.length() > 0) {
                    ConversionResult.Success(
                        outputPath = mp3File.absolutePath,
                        fileSize = mp3File.length()
                    )
                } else {
                    ConversionResult.Error("Conversion failed: output file not created")
                }
            }
        } catch (e: Exception) {
            if (isCancelled() || cancelled) {
                ConversionResult.Cancelled
            } else {
                ConversionResult.Error(e.message ?: "Conversion failed")
            }
        }
    }

    fun cancel() {
        cancelled = true
    }
}
