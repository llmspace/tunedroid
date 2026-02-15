package com.tunedroid.app.data.database

import androidx.room.*

@Entity(tableName = "downloads")
data class DownloadEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    @ColumnInfo(name = "media_id")
    val mediaId: String,
    val title: String,
    val author: String,
    val url: String,
    @ColumnInfo(name = "format_preset")
    val formatPreset: String,
    @ColumnInfo(name = "file_path")
    val filePath: String? = null,
    @ColumnInfo(name = "file_size")
    val fileSize: Long = 0,
    val status: String = DownloadStatus.QUEUED,
    val progress: Float = 0f,
    @ColumnInfo(name = "error_message")
    val errorMessage: String? = null,
    @ColumnInfo(name = "thumbnail_url")
    val thumbnailUrl: String? = null,
    val duration: Long = 0, // seconds
    @ColumnInfo(name = "source_bitrate")
    val sourceBitrate: Int = 0,
    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "completed_at")
    val completedAt: Long? = null
)

object DownloadStatus {
    const val QUEUED = "queued"
    const val DOWNLOADING = "downloading"
    const val CONVERTING = "converting"
    const val FINALIZING = "finalizing"
    const val COMPLETED = "completed"
    const val FAILED = "failed"
    const val CANCELLED = "cancelled"
}
