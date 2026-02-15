package com.tunedroid.app.engine

data class SelectedStream(
    val formatId: String,
    val codec: String,
    val bitrate: Int,
    val estimatedFileSize: Long,
    val extension: String
)

object StreamSelector {

    fun selectBest(streams: List<AudioStreamInfo>): SelectedStream? {
        if (streams.isEmpty()) return null

        val best = streams.maxByOrNull { it.bitrate } ?: return null

        return SelectedStream(
            formatId = best.formatId,
            codec = best.codec,
            bitrate = best.bitrate,
            estimatedFileSize = best.fileSize,
            extension = best.extension
        )
    }
}
