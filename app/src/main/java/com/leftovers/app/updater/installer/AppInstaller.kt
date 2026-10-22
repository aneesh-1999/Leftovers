package com.leftovers.app.updater.installer

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File

/**
 * Dispatches APK installation safely to the Android Package Installer via FileProvider.
 * Respects Android 8.0+ Unknown App Sources permissions.
 */
object AppInstaller {

    /**
     * Checks whether the app is permitted to trigger package installs on Android 8.0+ (API 26+).
     */
    fun canRequestPackageInstalls(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else {
            true
        }
    }

    /**
     * Creates an intent directing the user to the system settings page for enabling
     * "Install unknown apps" for Leftovers.
     */
    fun createManageUnknownAppSourcesIntent(context: Context): Intent {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                data = Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        } else {
            Intent(Settings.ACTION_SECURITY_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }
    }

    /**
     * Launches the Android Package Installer with a secure FileProvider content URI.
     */
    fun installApk(context: Context, apkFile: File): Result<Unit> {
        return runCatching {
            if (!apkFile.exists()) {
                throw IllegalStateException("APK file not found on disk at ${apkFile.absolutePath}")
            }

            val authority = "${context.packageName}.fileprovider"
            val contentUri: Uri = FileProvider.getUriForFile(context, authority, apkFile)

            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(contentUri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            context.startActivity(installIntent)
        }
    }
}
