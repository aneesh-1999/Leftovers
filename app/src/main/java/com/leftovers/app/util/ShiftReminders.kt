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
import com.leftovers.app.data.ShiftStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder

/**
 * Manages shift & roster reminder notifications:
 * 1. Night-before reminder (default 8:00 PM / 20:00) alerting about tomorrow's shifts with alarm reminders for morning shifts.
 * 2. Pre-shift advance reminder (default 5 hours before shift start) for upcoming shifts.
 */
object ShiftReminders {
    const val CHANNEL_ID = "shift_reminders"
    private const val WORK_NIGHT = "shift_night_reminder"
    private const val WORK_NIGHT_ONETIME = "shift_night_reminder_onetime"
    private const val WORK_ADVANCE = "shift_advance_reminder"
    private const val WORK_POST_SHIFT = "shift_post_completion_reminder"

    private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm")

    fun createChannel(context: Context) {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Shift & Roster reminders",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "Alerts for upcoming work shifts, morning alarms, and shift completion confirmations"
            enableVibration(true)
        }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    fun schedule(
        context: Context,
        enabled: Boolean,
        nightMinutes: Int = 20 * 60,
        advanceHours: Int = 5,
    ) {
        // Primary: Exact wake-up alarms that fire even in Doze mode & long standby
        AlarmScheduler.scheduleShiftNightReminder(context, enabled, nightMinutes)
        CoroutineScope(Dispatchers.IO).launch {
            AlarmScheduler.scheduleNextShiftAdvanceReminder(context, enabled, advanceHours)
            AlarmScheduler.scheduleNextShiftPostReminder(context, enabled)
        }

        // Secondary fallback: WorkManager
        val wm = WorkManager.getInstance(context)
        if (!enabled) {
            wm.cancelUniqueWork(WORK_NIGHT)
            wm.cancelUniqueWork(WORK_NIGHT_ONETIME)
            wm.cancelUniqueWork(WORK_ADVANCE)
            wm.cancelUniqueWork(WORK_POST_SHIFT)
            return
        }

        // 1. Night-before reminder (triggers at nightMinutes, default 20:00)
        val now = LocalDateTime.now()
        val targetTime = LocalTime.of((nightMinutes / 60).coerceIn(0, 23), (nightMinutes % 60).coerceIn(0, 59))
        var nextNight = LocalDate.now().atTime(targetTime)
        val secondsDiffFromTargetToday = Duration.between(nextNight, now).seconds
        // If target time is within past 2 minutes today, treat as triggered right now so immediate setting tests work!
        val isJustNow = secondsDiffFromTargetToday in 0..120
        if (!isJustNow && !nextNight.isAfter(now)) {
            nextNight = nextNight.plusDays(1)
        }
        val delaySeconds = if (isJustNow) 0L else maxOf(0L, Duration.between(now, nextNight).seconds)

        // Exact / one-time execution for today's trigger
        val oneTimeRequest = OneTimeWorkRequestBuilder<ShiftNightReminderWorker>()
            .setInitialDelay(delaySeconds, TimeUnit.SECONDS)
            .build()
        wm.enqueueUniqueWork(WORK_NIGHT_ONETIME, ExistingWorkPolicy.REPLACE, oneTimeRequest)

        // Periodic 24-hour work for recurring daily cycle
        val nightRequest = PeriodicWorkRequestBuilder<ShiftNightReminderWorker>(24, TimeUnit.HOURS)
            .setInitialDelay(maxOf(1L, delaySeconds / 60), TimeUnit.MINUTES)
            .build()
        wm.enqueueUniquePeriodicWork(WORK_NIGHT, ExistingPeriodicWorkPolicy.UPDATE, nightRequest)

        // 2. Pre-shift advance reminder (checks regularly for upcoming shifts)
        val advanceRequest = PeriodicWorkRequestBuilder<ShiftAdvanceReminderWorker>(15, TimeUnit.MINUTES)
            .build()
        wm.enqueueUniquePeriodicWork(WORK_ADVANCE, ExistingPeriodicWorkPolicy.UPDATE, advanceRequest)

        // 3. Post-shift completion reminder (checks regularly for finished shifts 30m after end time)
        val postShiftRequest = PeriodicWorkRequestBuilder<ShiftPostCompletionReminderWorker>(15, TimeUnit.MINUTES)
            .build()
        wm.enqueueUniquePeriodicWork(WORK_POST_SHIFT, ExistingPeriodicWorkPolicy.UPDATE, postShiftRequest)
    }

    suspend fun sendTestNotification(context: Context) {
        val container = (context.applicationContext as LeftoversApp).container
        val s = container.settings.settings.first()
        val allShifts = container.roster.shifts.first()
        val tomorrow = LocalDate.now().plusDays(1)
        val nowMillis = System.currentTimeMillis()
        val next24h = nowMillis + 24 * 3600 * 1000L

        var targetShifts = allShifts.filter {
            it.startDate == tomorrow && it.status != ShiftStatus.CANCELLED
        }
        if (targetShifts.isEmpty()) {
            targetShifts = allShifts.filter {
                it.status == ShiftStatus.SCHEDULED && it.startEpochMillis > nowMillis && it.startEpochMillis <= next24h
            }
        }
        if (targetShifts.isEmpty()) {
            // Simulated 7:00 AM morning shift for instant demo/testing
            val start7am = LocalDate.now().plusDays(1).atTime(7, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
            val end3pm = LocalDate.now().plusDays(1).atTime(15, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
            targetShifts = listOf(
                com.leftovers.app.data.Shift(
                    id = 99999,
                    title = "Morning Shift",
                    workplace = "Work",
                    startEpochMillis = start7am,
                    endEpochMillis = end3pm,
                    status = ShiftStatus.SCHEDULED,
                )
            )
        }

        val reminderContent = buildNightReminder(
            tomorrowShifts = targetShifts,
            alarmSuggestion = s.shiftReminderAlarmSuggestion,
            zone = ZoneId.systemDefault(),
        ) ?: return

        val (title, text) = reminderContent

        val openRoster = PendingIntent.getActivity(
            context,
            101,
            Intent(context, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_OPEN_ROSTER, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_wallet)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openRoster)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        NotificationManagerCompat.from(context).notify(44, notification)
    }

    fun formatMinutes(minutes: Int): String = "%02d:%02d".format(minutes / 60, minutes % 60)

    fun buildNightReminder(
        tomorrowShifts: List<com.leftovers.app.data.Shift>,
        alarmSuggestion: Boolean,
        zone: ZoneId = ZoneId.systemDefault(),
    ): Pair<String, String>? {
        if (tomorrowShifts.isEmpty()) return null
        val sortedShifts = tomorrowShifts.sortedBy { it.startEpochMillis }
        val firstShift = sortedShifts.first()
        val startLocal = Instant.ofEpochMilli(firstShift.startEpochMillis).atZone(zone).toLocalDateTime()
        val startTimeStr = startLocal.format(DateTimeFormatter.ofPattern("HH:mm"))
        val isMorning = startLocal.hour < 12
        val workplaceName = firstShift.workplace?.takeIf { it.isNotBlank() } ?: "work"

        val title = if (sortedShifts.size > 1) {
            "⏰ ${sortedShifts.size} shifts scheduled tomorrow"
        } else if (isMorning) {
            "⏰ Tomorrow's shift: ${firstShift.title} ($startTimeStr)"
        } else {
            "💼 Tomorrow's shift: ${firstShift.title} ($startTimeStr)"
        }

        val text = if (isMorning && alarmSuggestion) {
            if (sortedShifts.size > 1) {
                "First shift starts at $startTimeStr at $workplaceName. Don't forget to set your alarm tonight! ⏰"
            } else {
                "Starts at $startTimeStr at $workplaceName. Remember to set your alarm tonight! ⏰"
            }
        } else {
            "Starts at $startTimeStr at $workplaceName. (We'll send an advance reminder before it starts)."
        }

        return title to text
    }

    fun buildAdvanceReminder(
        shift: com.leftovers.app.data.Shift,
        nowEpochMillis: Long,
        zone: ZoneId = ZoneId.systemDefault(),
    ): Pair<String, String> {
        val startLocal = Instant.ofEpochMilli(shift.startEpochMillis).atZone(zone).toLocalDateTime()
        val startTimeStr = startLocal.format(DateTimeFormatter.ofPattern("HH:mm"))
        val workplaceName = shift.workplace?.takeIf { it.isNotBlank() } ?: "work"
        val hoursAway = maxOf(1, Math.round((shift.startEpochMillis - nowEpochMillis) / 3600000.0).toInt())

        val title = "💼 Shift in ~$hoursAway hours: ${shift.title}"
        val text = "Starts at $startTimeStr at $workplaceName. Time to get ready!"
        return title to text
    }

    fun buildPostShiftReminder(
        shift: com.leftovers.app.data.Shift,
        zone: ZoneId = ZoneId.systemDefault(),
    ): Pair<String, String> {
        val endLocal = Instant.ofEpochMilli(shift.endEpochMillis).atZone(zone).toLocalDateTime()
        val endTimeStr = endLocal.format(DateTimeFormatter.ofPattern("HH:mm"))
        val workplaceName = shift.workplace?.takeIf { it.isNotBlank() } ?: "work"

        val title = "✅ Shift finished: ${shift.title}"
        val text = "Ended at $endTimeStr ($workplaceName). Tap Complete or Cancel to update your schedule."
        return title to text
    }

    suspend fun deliverNightReminder(context: Context): Boolean {
        val container = (context.applicationContext as LeftoversApp).container
        val s = container.settings.settings.first()
        if (!s.shiftRemindersEnabled) return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return false

        val tomorrow = LocalDate.now().plusDays(1)
        val alertKey = "shift_night_${tomorrow.toEpochDay()}"
        val newlySent = container.settings.markAlertSent(alertKey)
        if (!newlySent) return false

        val allShifts = container.roster.shifts.first()
        var tomorrowShifts = allShifts.filter {
            it.startDate == tomorrow && it.status != ShiftStatus.CANCELLED
        }
        if (tomorrowShifts.isEmpty()) {
            val nowMillis = System.currentTimeMillis()
            val next24h = nowMillis + 24 * 3600 * 1000L
            tomorrowShifts = allShifts.filter {
                it.status == ShiftStatus.SCHEDULED && it.startEpochMillis > nowMillis && it.startEpochMillis <= next24h
            }
        }

        val reminderContent = buildNightReminder(
            tomorrowShifts = tomorrowShifts,
            alarmSuggestion = s.shiftReminderAlarmSuggestion,
            zone = ZoneId.systemDefault(),
        ) ?: return false

        val (title, text) = reminderContent
        val openRoster = PendingIntent.getActivity(
            context,
            101,
            Intent(context, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_OPEN_ROSTER, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_wallet)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openRoster)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        NotificationManagerCompat.from(context).notify(44, notification)
        return true
    }

    suspend fun deliverAdvanceReminder(context: Context): Boolean {
        val container = (context.applicationContext as LeftoversApp).container
        val s = container.settings.settings.first()
        if (!s.shiftRemindersEnabled) return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return false

        val now = System.currentTimeMillis()
        val advanceMillis = s.shiftReminderAdvanceHours * 3600 * 1000L
        val maxWindow = now + advanceMillis + (45 * 60 * 1000L)

        val allShifts = container.roster.shifts.first()
        val upcomingShifts = allShifts.filter {
            it.status == ShiftStatus.SCHEDULED &&
                it.startEpochMillis > now &&
                it.startEpochMillis <= maxWindow
        }

        val zone = ZoneId.systemDefault()
        var sentAny = false
        for (shift in upcomingShifts) {
            val alertKey = "shift_advance_${shift.id}_${shift.startEpochMillis}"
            val newlySent = container.settings.markAlertSent(alertKey)
            if (!newlySent) continue

            val (title, text) = buildAdvanceReminder(shift, now, zone)
            val openRoster = PendingIntent.getActivity(
                context,
                (1000 + shift.id % 5000).toInt(),
                Intent(context, MainActivity::class.java)
                    .putExtra(MainActivity.EXTRA_OPEN_ROSTER, true)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )

            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_wallet)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setContentIntent(openRoster)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .build()

            val notificationId = 20000 + (shift.id % 10000).toInt()
            NotificationManagerCompat.from(context).notify(notificationId, notification)
            sentAny = true
        }
        return sentAny
    }

    suspend fun deliverPostShiftReminder(context: Context): Boolean {
        val container = (context.applicationContext as LeftoversApp).container
        val s = container.settings.settings.first()
        if (!s.shiftRemindersEnabled) return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return false

        val now = System.currentTimeMillis()
        val allShifts = container.roster.shifts.first()
        val zone = ZoneId.systemDefault()

        val finishedShifts = allShifts.filter {
            it.status == ShiftStatus.SCHEDULED &&
                now >= (it.endEpochMillis + 30 * 60 * 1000L) &&
                now <= (it.endEpochMillis + 24 * 3600 * 1000L)
        }

        var sentAny = false
        for (shift in finishedShifts) {
            val alertKey = "shift_post_complete_${shift.id}_${shift.endEpochMillis}"
            val newlySent = container.settings.markAlertSent(alertKey)
            if (!newlySent) continue

            val (title, text) = buildPostShiftReminder(shift, zone)
            val notificationId = 30000 + (shift.id % 10000).toInt()

            val openRosterIntent = PendingIntent.getActivity(
                context,
                (3000 + shift.id % 5000).toInt(),
                Intent(context, MainActivity::class.java)
                    .putExtra(MainActivity.EXTRA_OPEN_ROSTER, true)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )

            val completeIntent = PendingIntent.getBroadcast(
                context,
                (4000 + shift.id % 5000).toInt(),
                Intent(context, ShiftActionReceiver::class.java).apply {
                    action = ShiftActionReceiver.ACTION_COMPLETE_SHIFT
                    putExtra(ShiftActionReceiver.EXTRA_SHIFT_ID, shift.id)
                    putExtra(ShiftActionReceiver.EXTRA_NOTIFICATION_ID, notificationId)
                },
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )

            val cancelIntent = PendingIntent.getBroadcast(
                context,
                (5000 + shift.id % 5000).toInt(),
                Intent(context, ShiftActionReceiver::class.java).apply {
                    action = ShiftActionReceiver.ACTION_CANCEL_SHIFT
                    putExtra(ShiftActionReceiver.EXTRA_SHIFT_ID, shift.id)
                    putExtra(ShiftActionReceiver.EXTRA_NOTIFICATION_ID, notificationId)
                },
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )

            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_wallet)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setContentIntent(openRosterIntent)
                .addAction(0, "Complete", completeIntent)
                .addAction(0, "Cancel Shift", cancelIntent)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .build()

            NotificationManagerCompat.from(context).notify(notificationId, notification)
            sentAny = true
        }
        return sentAny
    }
}

/**
 * Worker that runs at night (default 8:00 PM) to check for tomorrow's shifts.
 */
class ShiftNightReminderWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        ShiftReminders.deliverNightReminder(applicationContext)
        return Result.success()
    }
}

/**
 * Worker that runs periodically to send advance reminders (default 5 hours before shift start).
 */
class ShiftAdvanceReminderWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        ShiftReminders.deliverAdvanceReminder(applicationContext)
        return Result.success()
    }
}

/**
 * Worker that runs periodically to check for finished shifts.
 */
class ShiftPostCompletionReminderWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        ShiftReminders.deliverPostShiftReminder(applicationContext)
        return Result.success()
    }
}

