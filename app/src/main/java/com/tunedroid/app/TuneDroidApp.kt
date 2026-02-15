package com.tunedroid.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.util.Log
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.ffmpeg.FFmpeg
import com.tunedroid.app.engine.EngineUpdater
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class TuneDroidApp : Application() {

    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val engineDeferred = CompletableDeferred<Boolean>()
    private val engineUpdateDeferred = CompletableDeferred<Boolean>()

    var isEngineReady = false
        private set

    var engineInitError: String? = null
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannels()
        initializeEngine()
    }

    private fun createNotificationChannels() {
        val channel = NotificationChannel(
            CHANNEL_DOWNLOADS,
            getString(R.string.notification_channel_download),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.notification_channel_download_desc)
            setShowBadge(false)
        }
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.createNotificationChannel(channel)
    }

    private fun initializeEngine() {
        applicationScope.launch(Dispatchers.IO) {
            try {
                Log.d(TAG, "Starting engine initialization...")
                YoutubeDL.getInstance().init(this@TuneDroidApp)
                Log.d(TAG, "Engine core initialized successfully")
                FFmpeg.getInstance().init(this@TuneDroidApp)
                Log.d(TAG, "FFmpeg initialized successfully")
                isEngineReady = true
                engineDeferred.complete(true)
                Log.d(TAG, "Engine fully ready")

                // Auto-update the extraction engine (only if enabled)
                val prefsManager = com.tunedroid.app.data.PreferencesManager(this@TuneDroidApp)
                val shouldAutoUpdate = prefsManager.autoUpdateEngine.first()

                if (shouldAutoUpdate) {
                    Log.d(TAG, "Starting automatic engine update...")
                    try {
                        val updateResult = EngineUpdater.update(this@TuneDroidApp)
                        when (updateResult) {
                            is com.tunedroid.app.engine.UpdateResult.Updated ->
                                Log.d(TAG, "Engine updated to: ${updateResult.version}")
                            is com.tunedroid.app.engine.UpdateResult.AlreadyUpToDate ->
                                Log.d(TAG, "Engine already up to date")
                            is com.tunedroid.app.engine.UpdateResult.Error ->
                                Log.w(TAG, "Engine update failed (non-critical): ${updateResult.message}")
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Engine update failed (non-critical): ${e.message}")
                    } finally {
                        engineUpdateDeferred.complete(true)
                    }
                } else {
                    Log.d(TAG, "Auto-update engine disabled, skipping")
                    engineUpdateDeferred.complete(true)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Engine initialization FAILED", e)
                Log.e(TAG, "Error class: ${e.javaClass.name}")
                Log.e(TAG, "Error message: ${e.message}")
                Log.e(TAG, "Stacktrace:", e)
                engineInitError = "${e.javaClass.simpleName}: ${e.message}"
                engineDeferred.complete(false)
            }
        }
    }

    /** Suspends until the engine is initialized. Returns true if successful. */
    suspend fun awaitEngineReady(): Boolean {
        return engineDeferred.await()
    }

    /** Suspends until the engine update check has completed (whether it succeeded or failed). */
    suspend fun awaitEngineUpdated(): Boolean {
        return engineUpdateDeferred.await()
    }

    companion object {
        private const val TAG = "TuneDroid"
        const val CHANNEL_DOWNLOADS = "downloads"
        lateinit var instance: TuneDroidApp
            private set
    }
}
