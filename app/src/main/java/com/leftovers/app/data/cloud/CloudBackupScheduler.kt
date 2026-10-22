package com.leftovers.app.data.cloud

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.leftovers.app.LeftoversApp
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

object CloudBackupScheduler {
    private const val WORK_NAME = "leftovers_cloud_backup"
    private const val TAG = "CloudBackupScheduler"

    fun schedule(context: Context, enabled: Boolean) {
        val wm = WorkManager.getInstance(context)
        if (!enabled) {
            wm.cancelUniqueWork(WORK_NAME)
            Log.d(TAG, "Cancelled periodic cloud backup work")
            return
        }

        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .setRequiresBatteryNotLow(true)
            .build()

        val request = PeriodicWorkRequestBuilder<CloudBackupWorker>(24, TimeUnit.HOURS)
            .setConstraints(constraints)
            .build()

        wm.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        Log.d(TAG, "Scheduled 24h periodic cloud backup work")
    }
}

class CloudBackupWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as? LeftoversApp ?: return Result.success()
        val settings = app.container.settings.settings.first()
        if (!settings.cloudBackupEnabled) {
            return Result.success()
        }

        val authUser = app.container.authManager.auth.currentUser
        if (authUser == null) {
            return Result.success()
        }

        // Avoid duplicate backups if one happened within the last 12 hours
        val twelveHours = TimeUnit.HOURS.toMillis(12)
        if (System.currentTimeMillis() - settings.cloudBackupLast < twelveHours) {
            return Result.success()
        }

        return try {
            app.container.cloudBackup.backupNow().fold(
                onSuccess = { Result.success() },
                onFailure = {
                    if (runAttemptCount < 3) Result.retry() else Result.failure()
                }
            )
        } catch (e: Exception) {
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
    }
}
