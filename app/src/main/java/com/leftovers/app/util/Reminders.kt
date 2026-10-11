package com.leftovers.app.util

import android.Manifest
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
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.leftovers.app.LeftoversApp
import com.leftovers.app.MainActivity
import com.leftovers.app.R
import kotlinx.coroutines.flow.first
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.concurrent.TimeUnit

/** Daily nudge to log the day's spending, scheduled with AlarmManager and WorkManager. */
object Reminders {
    const val CHANNEL_ID = "daily_reminder"
    private const val WORK_NAME = "daily_reminder"

    fun createChannel(context: Context) {
        val channel = NotificationChannel(CHANNEL_ID, "Daily reminder", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "A gentle evening reminder to log today's spending"
            enableVibration(true)
        }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    fun schedule(context: Context, enabled: Boolean, minutesAfterMidnight: Int) {
        // Primary: Exact alarm that wakes device even from Doze mode
        AlarmScheduler.scheduleDailyReminder(context, enabled, minutesAfterMidnight)

        // Secondary fallback: WorkManager
        val wm = WorkManager.getInstance(context)
        if (!enabled) {
            wm.cancelUniqueWork(WORK_NAME)
            return
        }
        val now = LocalDateTime.now()
        var next = LocalDate.now().atTime(LocalTime.of(minutesAfterMidnight / 60, minutesAfterMidnight % 60))
        if (!next.isAfter(now)) next = next.plusDays(1)
        val delay = Duration.between(now, next).toMinutes()
        val request = PeriodicWorkRequestBuilder<ReminderWorker>(24, TimeUnit.HOURS)
            .setInitialDelay(delay, TimeUnit.MINUTES)
            .build()
        wm.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    suspend fun deliverDailyReminder(context: Context): Boolean {
        val container = (context.applicationContext as LeftoversApp).container
        val s = container.settings.settings.first()
        if (!s.reminderEnabled) return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return false

        val today = LocalDate.now().toEpochDay()
        val alertKey = "daily_reminder_$today"
        val newlySent = container.settings.markAlertSent(alertKey)
        if (!newlySent) return false

        val loggedToday = container.repository.getAllTransactions().count { it.epochDay == today }
        val text = if (loggedToday == 0) {
            "Nothing logged today. Spent anything? It only takes a few seconds."
        } else {
            "$loggedToday ${if (loggedToday == 1) "entry" else "entries"} today. Anything else to add before bed?"
        }
        val openAdd = PendingIntent.getActivity(
            context, 7,
            Intent(context, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_OPEN_ADD, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_wallet)
            .setContentTitle("Logged everything today?")
            .setContentText(text)
            .setContentIntent(openAdd)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        NotificationManagerCompat.from(context).notify(42, notification)
        return true
    }

    fun label(minutes: Int): String = "%02d:%02d".format(minutes / 60, minutes % 60)
}

class ReminderWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        Reminders.deliverDailyReminder(applicationContext)
        return Result.success()
    }
}
