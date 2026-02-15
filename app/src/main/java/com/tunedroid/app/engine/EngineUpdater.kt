package com.tunedroid.app.engine

import android.content.Context
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDL.UpdateStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

sealed class UpdateResult {
    data class Updated(val version: String) : UpdateResult()
    data object AlreadyUpToDate : UpdateResult()
    data class Error(val message: String) : UpdateResult()
}

object EngineUpdater {

    suspend fun update(context: Context): UpdateResult = withContext(Dispatchers.IO) {
        try {
            val status = YoutubeDL.getInstance().updateYoutubeDL(context)
            when (status) {
                UpdateStatus.DONE -> UpdateResult.Updated(getEngineVersion())
                UpdateStatus.ALREADY_UP_TO_DATE -> UpdateResult.AlreadyUpToDate
                else -> UpdateResult.AlreadyUpToDate
            }
        } catch (e: Exception) {
            UpdateResult.Error(e.message ?: "Update failed")
        }
    }

    fun getEngineVersion(): String {
        return try {
            YoutubeDL.getInstance().version(null) ?: "Unknown"
        } catch (_: Exception) {
            "Unknown"
        }
    }
}
