package com.tunedroid.app.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings

object StoragePermissionHelper {

    /**
     * Returns true if the app has full file management access.
     * On API 30+, this checks MANAGE_EXTERNAL_STORAGE.
     * On API 29 and below, legacy storage or WRITE_EXTERNAL_STORAGE suffices.
     */
    fun hasAllFilesAccess(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            true
        }
    }

    /**
     * Creates an intent to open the "All Files Access" settings page for this app.
     * Only meaningful on API 30+.
     */
    fun createAllFilesAccessIntent(context: Context): Intent {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                data = Uri.parse("package:${context.packageName}")
            }
        } else {
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", context.packageName, null)
            }
        }
    }

    /**
     * Whether the All Files Access permission is relevant on this device.
     */
    fun isAllFilesAccessRelevant(): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
    }
}
