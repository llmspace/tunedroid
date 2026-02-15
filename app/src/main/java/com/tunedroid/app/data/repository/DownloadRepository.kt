package com.tunedroid.app.data.repository

import android.content.Context
import com.tunedroid.app.data.PreferencesManager
import com.tunedroid.app.data.database.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import java.io.File

class DownloadRepository(context: Context) {

    private val dao = TuneDroidDatabase.getInstance(context).downloadDao()
    private val prefsManager = PreferencesManager(context)

    suspend fun enqueue(download: DownloadEntity): Long {
        return dao.insert(download)
    }

    suspend fun getById(id: Long): DownloadEntity? {
        return dao.getById(id)
    }

    fun getActiveDownloads(): Flow<List<DownloadEntity>> {
        return dao.getActiveDownloads()
    }

    suspend fun getActiveDownloadsList(): List<DownloadEntity> {
        return dao.getActiveDownloadsList()
    }

    fun getCompletedDownloads(): Flow<List<DownloadEntity>> {
        return dao.getCompletedDownloads()
    }

    fun getFailedDownloads(): Flow<List<DownloadEntity>> {
        return dao.getFailedDownloads()
    }

    fun getAllDownloads(): Flow<List<DownloadEntity>> {
        return dao.getAllDownloads()
    }

    suspend fun findCompletedByMediaId(mediaId: String): DownloadEntity? {
        return dao.findCompletedByMediaId(mediaId)
    }

    suspend fun updateProgress(id: Long, status: String, progress: Float) {
        dao.updateProgress(id, status, progress)
    }

    suspend fun updateStatus(id: Long, status: String) {
        dao.updateStatus(id, status)
    }

    suspend fun updateStatusWithError(id: Long, status: String, errorMessage: String?) {
        dao.updateStatusWithError(id, status, errorMessage)
    }

    suspend fun markCompleted(id: Long, filePath: String, fileSize: Long) {
        dao.markCompleted(id, DownloadStatus.COMPLETED, filePath, fileSize, System.currentTimeMillis())
    }

    suspend fun delete(download: DownloadEntity) {
        dao.delete(download)
    }

    suspend fun getNextQueued(): DownloadEntity? {
        return dao.getNextQueued()
    }

    suspend fun getActiveCount(): Int {
        return dao.getActiveCount()
    }

    suspend fun findOrphanedFiles(): List<File> {
        val storageDir = File(prefsManager.storagePath.first())
        if (!storageDir.exists()) return emptyList()

        val allDownloads = getAllDownloadsSnapshot()
        val trackedPaths = allDownloads.mapNotNull { it.filePath }.toSet()

        return storageDir.listFiles()
            ?.filter { it.isFile && it.absolutePath !in trackedPaths }
            ?: emptyList()
    }

    suspend fun findOrphanedRecords(): List<DownloadEntity> {
        val allDownloads = getAllDownloadsSnapshot()
        return allDownloads.filter { download ->
            download.filePath?.let { path ->
                !File(path).exists()
            } ?: false
        }
    }

    suspend fun cleanupOrphans() {
        val orphanedFiles = findOrphanedFiles()
        val orphanedRecords = findOrphanedRecords()

        orphanedFiles.forEach { it.delete() }
        orphanedRecords.forEach { dao.delete(it) }
    }

    private suspend fun getAllDownloadsSnapshot(): List<DownloadEntity> {
        return dao.getAllDownloads().first()
    }
}
