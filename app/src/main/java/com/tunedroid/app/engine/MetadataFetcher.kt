package com.tunedroid.app.engine

import android.util.Log
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class AudioStreamInfo(
    val formatId: String,
    val codec: String,
    val bitrate: Int, // kbps
    val fileSize: Long, // bytes, estimated
    val extension: String
)

data class MediaInfo(
    val id: String,
    val title: String,
    val author: String,
    val duration: Long, // seconds
    val thumbnailUrl: String?,
    val audioStreams: List<AudioStreamInfo>,
    val url: String
)

sealed class MetadataResult {
    data class Success(val mediaInfo: MediaInfo) : MetadataResult()
    data class Error(val code: ErrorCode, val message: String) : MetadataResult()
}

enum class ErrorCode {
    UNAVAILABLE,
    GEO_BLOCKED,
    RESTRICTED,
    NETWORK_ERROR,
    INVALID_URL,
    EXTRACTION_ERROR,
    UNKNOWN
}

object MetadataFetcher {

    suspend fun fetch(url: String): MetadataResult = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Fetching metadata for URL: $url")

            // Wait for the extraction engine to be ready
            val app = com.tunedroid.app.TuneDroidApp.instance
            Log.d(TAG, "Waiting for engine to be ready...")
            val ready = app.awaitEngineReady()
            Log.d(TAG, "Engine ready result: $ready")

            if (!ready) {
                val errorMsg = app.engineInitError ?: "Extraction engine failed to initialize. Please restart the app."
                Log.e(TAG, "Engine not ready: $errorMsg")
                return@withContext MetadataResult.Error(
                    ErrorCode.EXTRACTION_ERROR,
                    errorMsg
                )
            }

            val request = YoutubeDLRequest(url)
            request.addOption("--dump-json")
            request.addOption("--no-playlist")
            request.addOption("-f", "bestaudio/best")
            request.addOption("--extractor-retries", "3")
            request.addOption("--force-ipv4")
            request.addOption("--no-check-certificates")

            Log.d(TAG, "Executing metadata request...")
            val response = YoutubeDL.getInstance().execute(request)
            val json = response.out
            Log.d(TAG, "Response received, json length: ${json?.length ?: 0}")

            if (response.err?.isNotBlank() == true) {
                Log.w(TAG, "Stderr output: ${response.err}")
            }

            if (json.isNullOrBlank()) {
                val stderrMsg = response.err ?: "No metadata received"
                Log.e(TAG, "Empty JSON response. Stderr: $stderrMsg")
                return@withContext MetadataResult.Error(
                    ErrorCode.EXTRACTION_ERROR,
                    "No metadata received. ${stderrMsg.take(200)}"
                )
            }

            val parsed = parseMetadataJson(json, url)
            Log.d(TAG, "Metadata parsed successfully: ${parsed.title}")
            MetadataResult.Success(parsed)

        } catch (e: Exception) {
            val errorMsg = e.message ?: "Unknown error"
            Log.e(TAG, "Metadata fetch failed: $errorMsg", e)
            val code = classifyError(errorMsg)
            MetadataResult.Error(code, mapErrorMessage(code, errorMsg))
        }
    }

    private const val TAG = "TuneDroid"

    private fun parseMetadataJson(json: String, originalUrl: String): MediaInfo {
        val obj = com.google.gson.JsonParser.parseString(json).asJsonObject

        val id = obj.get("id")?.asString ?: ""
        val title = obj.get("title")?.asString ?: "Unknown Title"
        val author = obj.get("uploader")?.asString
            ?: obj.get("channel")?.asString
            ?: obj.get("creator")?.asString
            ?: ""
        val duration = obj.get("duration")?.asLong ?: 0L
        val thumbnailUrl = obj.get("thumbnail")?.asString

        val audioStreams = mutableListOf<AudioStreamInfo>()
        val formats = obj.getAsJsonArray("formats")

        if (formats != null) {
            for (format in formats) {
                val fmt = format.asJsonObject
                val vcodec = fmt.get("vcodec")?.asString ?: "none"
                val acodec = fmt.get("acodec")?.asString ?: "none"

                // Filter to audio-only streams
                if ((vcodec == "none" || vcodec == "null") && acodec != "none" && acodec != "null") {
                    val abr = fmt.get("abr")?.asInt
                        ?: fmt.get("tbr")?.asInt
                        ?: 0
                    val fmtId = fmt.get("format_id")?.asString ?: continue
                    val ext = fmt.get("ext")?.asString ?: "webm"
                    val filesize = fmt.get("filesize")?.asLong
                        ?: fmt.get("filesize_approx")?.asLong
                        ?: estimateFileSize(abr, duration)

                    audioStreams.add(
                        AudioStreamInfo(
                            formatId = fmtId,
                            codec = acodec,
                            bitrate = abr,
                            fileSize = filesize,
                            extension = ext
                        )
                    )
                }
            }
        }

        // If no audio-only streams found, add a fallback from the main format
        if (audioStreams.isEmpty()) {
            val abr = obj.get("abr")?.asInt ?: obj.get("tbr")?.asInt ?: 128
            val ext = obj.get("ext")?.asString ?: "webm"
            audioStreams.add(
                AudioStreamInfo(
                    formatId = "best",
                    codec = obj.get("acodec")?.asString ?: "unknown",
                    bitrate = abr,
                    fileSize = estimateFileSize(abr, duration),
                    extension = ext
                )
            )
        }

        return MediaInfo(
            id = id,
            title = title,
            author = author,
            duration = duration,
            thumbnailUrl = thumbnailUrl,
            audioStreams = audioStreams,
            url = originalUrl
        )
    }

    private fun estimateFileSize(bitrateKbps: Int, durationSeconds: Long): Long {
        return (bitrateKbps.toLong() * 1000 / 8) * durationSeconds
    }

    private fun classifyError(message: String): ErrorCode {
        val lower = message.lowercase()
        return when {
            "unavailable" in lower || "removed" in lower || "not exist" in lower -> ErrorCode.UNAVAILABLE
            "geo" in lower || "country" in lower || "region" in lower -> ErrorCode.GEO_BLOCKED
            "private" in lower || "restricted" in lower || "age" in lower || "sign in" in lower -> ErrorCode.RESTRICTED
            "network" in lower || "connect" in lower || "timeout" in lower || "resolve" in lower -> ErrorCode.NETWORK_ERROR
            "url" in lower || "unsupported" in lower -> ErrorCode.INVALID_URL
            else -> ErrorCode.UNKNOWN
        }
    }

    private fun mapErrorMessage(code: ErrorCode, raw: String): String {
        return when (code) {
            ErrorCode.UNAVAILABLE -> "This content is unavailable or has been removed."
            ErrorCode.GEO_BLOCKED -> "This content is not available in your region."
            ErrorCode.RESTRICTED -> "This content is restricted and cannot be accessed."
            ErrorCode.NETWORK_ERROR -> "Network error. Please check your connection and try again."
            ErrorCode.INVALID_URL -> "Invalid or unsupported URL."
            ErrorCode.EXTRACTION_ERROR -> "Failed to extract media information."
            ErrorCode.UNKNOWN -> "An error occurred: $raw"
        }
    }
}
