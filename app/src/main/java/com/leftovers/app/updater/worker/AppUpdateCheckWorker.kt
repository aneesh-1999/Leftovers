package com.leftovers.app.updater.worker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.pm.PackageInfoCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.leftovers.app.LeftoversApp
import com.leftovers.app.MainActivity
import com.leftovers.app.R
import com.leftovers.app.updater.model.GitHubRelease
import com.leftovers.app.updater.model.UpdateCheckStatus
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

/**
 * Background WorkManager task checking periodically for new stable GitHub releases.
 * Respects network constraints (Wi-Fi only, battery not low) and deduplicates notifications.
 */
class AppUpdateCheckWorker(
    private val appContext: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val app = appContext.applicationContext as? LeftoversApp ?: return Result.success()
        val container = app.container
        val settings = container.settings.settings.first()

        if (!settings.autoUpdateChecks) {
            return Result.success()
        }

        val now = System.currentTimeMillis()
        if (now < settings.updateRateLimitResetTime) {
            // Respect active rate-limit deadline: skip background network check
            return Result.success()
        }

        val packageInfo = runCatching {
            appContext.packageManager.getPackageInfo(appContext.packageName, 0)
        }.getOrNull() ?: return Result.failure()

        val versionName = packageInfo.versionName ?: "1.7.2"
        val versionCode = PackageInfoCompat.getLongVersionCode(packageInfo)

        val checkStatus = container.updater.checkForUpdates(versionName, versionCode)
        container.settings.setLastUpdateCheckTime(System.currentTimeMillis())

        if (checkStatus is UpdateCheckStatus.UpdateAvailable) {
            val release = checkStatus.release
            // Deduplicate: Don't repeatedly notify user for the same release tag
            if (settings.lastNotifiedUpdateTag != release.tagName) {
                showUpdateNotification(appContext, release)
                container.settings.setLastNotifiedUpdateTag(release.tagName)
            }
        }

        return Result.success()
    }

    companion object {
        const val WORK_NAME = "app_update_periodic_check"
        const val CHANNEL_ID = "app_updates"
        const val NOTIFICATION_ID = 202610

        fun createChannel(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    "App updates",
                    NotificationManager.IMPORTANCE_DEFAULT,
                ).apply {
                    description = "Notifications when a new version of Leftovers is published"
                    enableLights(true)
                }
                context.getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
            }
        }

        fun schedulePeriodicCheck(context: Context, enabled: Boolean, wifiOnly: Boolean) {
            val workManager = WorkManager.getInstance(context)

            if (!enabled) {
                workManager.cancelUniqueWork(WORK_NAME)
                return
            }

            val networkType = if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(networkType)
                .setRequiresBatteryNotLow(true)
                .build()

            val request = PeriodicWorkRequestBuilder<AppUpdateCheckWorker>(24, TimeUnit.HOURS)
                .setConstraints(constraints)
                .build()

            workManager.enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request,
            )
        }

        fun showUpdateNotification(context: Context, release: GitHubRelease) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val hasPermission = ContextCompat.checkSelfPermission(
                    context,
                    android.Manifest.permission.POST_NOTIFICATIONS,
                ) == PackageManager.PERMISSION_GRANTED
                if (!hasPermission) return
            }

            createChannel(context)

            val openIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra("destination", "settings")
            }

            val pendingIntent = PendingIntent.getActivity(
                context,
                0,
                openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_wallet)
                .setContentTitle("Update Available: Leftovers ${release.displayVersion}")
                .setContentText("A new version of Leftovers is ready to install from GitHub.")
                .setStyle(NotificationCompat.BigTextStyle().bigText("Version ${release.displayVersion} is now available.\nTap to review release notes and install."))
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .build()

            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
        }
    }
}
