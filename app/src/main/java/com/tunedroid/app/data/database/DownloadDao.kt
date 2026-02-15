package com.tunedroid.app.data.database

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface DownloadDao {

    @Insert
    suspend fun insert(download: DownloadEntity): Long

    @Update
    suspend fun update(download: DownloadEntity)

    @Delete
    suspend fun delete(download: DownloadEntity)

    @Query("SELECT * FROM downloads WHERE id = :id")
    suspend fun getById(id: Long): DownloadEntity?

    @Query("SELECT * FROM downloads WHERE status IN ('queued', 'downloading', 'converting', 'finalizing') ORDER BY created_at ASC")
    fun getActiveDownloads(): Flow<List<DownloadEntity>>

    @Query("SELECT * FROM downloads WHERE status IN ('queued', 'downloading', 'converting', 'finalizing') ORDER BY created_at ASC")
    suspend fun getActiveDownloadsList(): List<DownloadEntity>

    @Query("SELECT * FROM downloads WHERE status = 'completed' ORDER BY completed_at DESC")
    fun getCompletedDownloads(): Flow<List<DownloadEntity>>

    @Query("SELECT * FROM downloads WHERE status = 'failed' ORDER BY created_at DESC")
    fun getFailedDownloads(): Flow<List<DownloadEntity>>

    @Query("SELECT * FROM downloads ORDER BY created_at DESC")
    fun getAllDownloads(): Flow<List<DownloadEntity>>

    @Query("SELECT * FROM downloads WHERE media_id = :mediaId AND status = 'completed' LIMIT 1")
    suspend fun findCompletedByMediaId(mediaId: String): DownloadEntity?

    @Query("UPDATE downloads SET status = :status, progress = :progress WHERE id = :id")
    suspend fun updateProgress(id: Long, status: String, progress: Float)

    @Query("UPDATE downloads SET status = :status WHERE id = :id")
    suspend fun updateStatus(id: Long, status: String)

    @Query("UPDATE downloads SET status = :status, error_message = :errorMessage WHERE id = :id")
    suspend fun updateStatusWithError(id: Long, status: String, errorMessage: String?)

    @Query("UPDATE downloads SET status = :status, file_path = :filePath, file_size = :fileSize, completed_at = :completedAt WHERE id = :id")
    suspend fun markCompleted(id: Long, status: String, filePath: String, fileSize: Long, completedAt: Long)

    @Query("SELECT * FROM downloads WHERE status = 'queued' ORDER BY created_at ASC LIMIT 1")
    suspend fun getNextQueued(): DownloadEntity?

    @Query("SELECT COUNT(*) FROM downloads WHERE status IN ('downloading', 'converting', 'finalizing')")
    suspend fun getActiveCount(): Int

    @Query("SELECT COUNT(*) FROM downloads")
    suspend fun getTotalCount(): Int
}
