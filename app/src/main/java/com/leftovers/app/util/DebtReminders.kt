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
import kotlin.math.abs

/** Morning reminder for money owed due dates and overdue debts. */
object DebtReminders {
    const val CHANNEL_ID = "debt_reminders"
    private const val WORK_NAME = "debt_reminders"
    private val CHECK_AT: LocalTime = LocalTime.of(9, 0)

    fun createChannel(context: Context) {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Money Owed Reminders",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "Reminders for expected return dates of lent and borrowed money"
            enableVibration(true)
        }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    fun schedule(context: Context, enabled: Boolean = true) {
        // Primary: Exact alarm that wakes device even from Doze mode
        AlarmScheduler.scheduleDebtReminder(context, enabled)

        // Secondary fallback: WorkManager
        val wm = WorkManager.getInstance(context)
        if (!enabled) {
            wm.cancelUniqueWork(WORK_NAME)
            return
        }
        val now = LocalDateTime.now()
        var next = LocalDate.now().atTime(CHECK_AT)
        if (!next.isAfter(now)) next = next.plusDays(1)
        val request = PeriodicWorkRequestBuilder<DebtReminderWorker>(24, TimeUnit.HOURS)
            .setInitialDelay(Duration.between(now, next).toMinutes(), TimeUnit.MINUTES)
            .build()
        wm.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    suspend fun deliverDebtReminder(context: Context): Boolean {
        val container = (context.applicationContext as LeftoversApp).container
        val s = container.settings.settings.first()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return false

        val today = LocalDate.now()
        val todayEpochDay = today.toEpochDay()
        val allDebts = container.planning.debts.first()
        val activeReminders = allDebts.filter { !it.settled && it.reminderEnabled && it.dueDateEpochDay != null }
        if (activeReminders.isEmpty()) return false

        // Debts due today or overdue
        val dueTodayOrOverdue = activeReminders.filter { it.dueDateEpochDay!! <= todayEpochDay }
        if (dueTodayOrOverdue.isEmpty()) return false

        val alertKey = "debt_reminder_$todayEpochDay"
        val newlySent = container.settings.markAlertSent(alertKey)
        if (!newlySent) return false

        val money = Money(s.currencyCode)
        val openIntent = Intent(context, MainActivity::class.java).apply {
            putExtra(MainActivity.EXTRA_OPEN_DEBTS, true)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val open = PendingIntent.getActivity(
            context,
            44,
            openIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val title: String
        val text: String
        if (dueTodayOrOverdue.size == 1) {
            val d = dueTodayOrOverdue[0]
            val isOverdue = d.dueDateEpochDay!! < todayEpochDay
            val isLent = d.amountMinor > 0
            val formattedAmount = money.format(abs(d.amountMinor))
            if (isOverdue) {
                title = if (isLent) "Overdue: ${d.person} owes you" else "Overdue: You owe ${d.person}"
                val daysAgo = todayEpochDay - d.dueDateEpochDay
                val agoText = if (daysAgo == 1L) "yesterday" else "$daysAgo days ago"
                text = "$formattedAmount · was expected $agoText"
            } else {
                title = "Return reminder: ${d.person}"
                text = if (isLent) {
                    "${d.person} is expected to return $formattedAmount today"
                } else {
                    "Due to return $formattedAmount to ${d.person} today"
                }
            }
        } else {
            val dueCount = dueTodayOrOverdue.count { it.dueDateEpochDay == todayEpochDay }
            val overdueCount = dueTodayOrOverdue.count { it.dueDateEpochDay!! < todayEpochDay }
            title = when {
                overdueCount > 0 && dueCount > 0 -> "$overdueCount overdue, $dueCount due today"
                overdueCount > 0 -> "$overdueCount overdue return reminders"
                else -> "$dueCount return reminders today"
            }
            text = dueTodayOrOverdue.joinToString(" · ") { d ->
                val prefix = if (d.amountMinor > 0) d.person else "To ${d.person}"
                "$prefix: ${money.format(abs(d.amountMinor))}"
            }
        }

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_wallet)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        NotificationManagerCompat.from(context).notify(44, notification)
        return true
    }
}

class DebtReminderWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        DebtReminders.deliverDebtReminder(applicationContext)
        return Result.success()
    }
}
