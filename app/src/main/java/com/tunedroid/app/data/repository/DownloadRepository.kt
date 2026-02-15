package com.tunedroid.app.data.repository

import android.content.Context
import com.tunedroid.app.data.PreferencesManager
import com.tunedroid.app.data.database.*
import com.tunedroid.app.engine.FormatPreset
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

    /**
     * Scans the TuneDroid folder for existing audio files when the database is empty.
     * Creates COMPLETED entries so previously downloaded files reappear after reinstall.
     * @return number of files recovered
     */
    suspend fun recoverExistingFiles(): Int {
        if (dao.getTotalCount() > 0) return 0

        val storageDir = File(prefsManager.storagePath.first())
        if (!storageDir.exists()) return 0

        val audioExtensions = setOf("mp3", "m4a", "opus", "ogg", "webm", "aac", "wav")

        val audioFiles = storageDir.listFiles()
            ?.filter { file ->
                file.isFile &&
                file.extension.lowercase() in audioExtensions &&
                file.length() > 0 &&
                !file.name.endsWith(".part") &&
                !file.name.endsWith(".temp")
            }
            ?: return 0

        if (audioFiles.isEmpty()) return 0

        var recoveredCount = 0
        for (file in audioFiles) {
            val formatPreset = when (file.extension.lowercase()) {
                "mp3" -> FormatPreset.MP3_128.name
                else -> FormatPreset.ORIGINAL.name
            }
            val entity = DownloadEntity(
                mediaId = "recovered_${file.name.hashCode()}",
                title = file.nameWithoutExtension,
                author = "",
                url = "",
                formatPreset = formatPreset,
                filePath = file.absolutePath,
                fileSize = file.length(),
                status = DownloadStatus.COMPLETED,
                progress = 100f,
                createdAt = file.lastModified(),
                completedAt = file.lastModified()
            )
            dao.insert(entity)
            recoveredCount++
        }
        return recoveredCount
    }

    private suspend fun getAllDownloadsSnapshot(): List<DownloadEntity> {
        return dao.getAllDownloads().first()
    }
}
