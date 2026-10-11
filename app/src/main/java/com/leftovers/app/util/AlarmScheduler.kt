package com.leftovers.app.util

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.leftovers.app.LeftoversApp
import com.leftovers.app.data.ShiftStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/**
 * Schedules exact and reliable wake-up alarms using Android's AlarmManager.
 * Unlike WorkManager which can be deferred indefinitely by Doze mode and App Standby,
 * AlarmManager with RTC_WAKEUP and setExactAndAllowWhileIdle guarantees that
 * notifications fire at the exact scheduled time even if the app hasn't been opened for days.
 */
object AlarmScheduler {
    const val ACTION_DAILY_REMINDER = "com.leftovers.app.ALARM_DAILY_REMINDER"
    const val ACTION_SHIFT_NIGHT = "com.leftovers.app.ALARM_SHIFT_NIGHT"
    const val ACTION_SHIFT_ADVANCE = "com.leftovers.app.ALARM_SHIFT_ADVANCE"
    const val ACTION_SHIFT_POST = "com.leftovers.app.ALARM_SHIFT_POST"
    const val ACTION_BILLS = "com.leftovers.app.ALARM_BILLS"
    const val ACTION_DEBTS = "com.leftovers.app.ALARM_DEBTS"

    private const val RC_DAILY = 201
    private const val RC_SHIFT_NIGHT = 202
    private const val RC_SHIFT_ADVANCE = 203
    private const val RC_SHIFT_POST = 204
    private const val RC_BILLS = 205
    private const val RC_DEBTS = 206

    private fun getAlarmManager(context: Context): AlarmManager =
        context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    private fun setAlarm(context: Context, triggerAtMillis: Long, pendingIntent: PendingIntent) {
        val am = getAlarmManager(context)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (am.canScheduleExactAlarms()) {
                    am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
                } else {
                    am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
                }
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
            } else {
                am.setExact(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
            }
        } catch (_: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
        }
    }

    private fun cancelAlarm(context: Context, action: String, requestCode: Int) {
        val intent = Intent(context, ReminderAlarmReceiver::class.java).apply { this.action = action }
        val pi = PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
        )
        if (pi != null) {
            getAlarmManager(context).cancel(pi)
            pi.cancel()
        }
    }

    fun scheduleDailyReminder(context: Context, enabled: Boolean, minutesAfterMidnight: Int) {
        if (!enabled) {
            cancelAlarm(context, ACTION_DAILY_REMINDER, RC_DAILY)
            return
        }
        val targetTime = LocalTime.of((minutesAfterMidnight / 60).coerceIn(0, 23), (minutesAfterMidnight % 60).coerceIn(0, 59))
        val now = LocalDateTime.now()
        var next = LocalDate.now().atTime(targetTime)
        if (!next.isAfter(now)) {
            next = next.plusDays(1)
        }
        val triggerMillis = next.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val intent = Intent(context, ReminderAlarmReceiver::class.java).apply { action = ACTION_DAILY_REMINDER }
        val pi = PendingIntent.getBroadcast(
            context,
            RC_DAILY,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        setAlarm(context, triggerMillis, pi)
    }

    fun scheduleShiftNightReminder(context: Context, enabled: Boolean, nightMinutes: Int = 20 * 60) {
        if (!enabled) {
            cancelAlarm(context, ACTION_SHIFT_NIGHT, RC_SHIFT_NIGHT)
            return
        }
        val targetTime = LocalTime.of((nightMinutes / 60).coerceIn(0, 23), (nightMinutes % 60).coerceIn(0, 59))
        val now = LocalDateTime.now()
        var next = LocalDate.now().atTime(targetTime)
        if (!next.isAfter(now)) {
            next = next.plusDays(1)
        }
        val triggerMillis = next.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val intent = Intent(context, ReminderAlarmReceiver::class.java).apply { action = ACTION_SHIFT_NIGHT }
        val pi = PendingIntent.getBroadcast(
            context,
            RC_SHIFT_NIGHT,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        setAlarm(context, triggerMillis, pi)
    }

    suspend fun scheduleNextShiftAdvanceReminder(context: Context, enabled: Boolean, advanceHours: Int = 5) {
        if (!enabled) {
            cancelAlarm(context, ACTION_SHIFT_ADVANCE, RC_SHIFT_ADVANCE)
            return
        }
        val container = (context.applicationContext as LeftoversApp).container
        val allShifts = container.roster.shifts.first()
        val now = System.currentTimeMillis()
        val advanceMillis = advanceHours * 3600 * 1000L

        // Look for scheduled shifts whose advance reminder trigger is in the future
        val nextShift = allShifts
            .filter { it.status == ShiftStatus.SCHEDULED && (it.startEpochMillis - advanceMillis) > now }
            .minByOrNull { it.startEpochMillis }

        if (nextShift == null) {
            cancelAlarm(context, ACTION_SHIFT_ADVANCE, RC_SHIFT_ADVANCE)
            return
        }

        val triggerMillis = nextShift.startEpochMillis - advanceMillis
        val intent = Intent(context, ReminderAlarmReceiver::class.java).apply { action = ACTION_SHIFT_ADVANCE }
        val pi = PendingIntent.getBroadcast(
            context,
            RC_SHIFT_ADVANCE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        setAlarm(context, triggerMillis, pi)
    }

    suspend fun scheduleNextShiftPostReminder(context: Context, enabled: Boolean) {
        if (!enabled) {
            cancelAlarm(context, ACTION_SHIFT_POST, RC_SHIFT_POST)
            return
        }
        val container = (context.applicationContext as LeftoversApp).container
        val allShifts = container.roster.shifts.first()
        val now = System.currentTimeMillis()
        val postDelay = 30 * 60 * 1000L

        // Look for scheduled shifts whose post-completion reminder trigger is in the future
        val nextShift = allShifts
            .filter { it.status == ShiftStatus.SCHEDULED && (it.endEpochMillis + postDelay) > now }
            .minByOrNull { it.endEpochMillis }

        if (nextShift == null) {
            cancelAlarm(context, ACTION_SHIFT_POST, RC_SHIFT_POST)
            return
        }

        val triggerMillis = nextShift.endEpochMillis + postDelay
        val intent = Intent(context, ReminderAlarmReceiver::class.java).apply { action = ACTION_SHIFT_POST }
        val pi = PendingIntent.getBroadcast(
            context,
            RC_SHIFT_POST,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        setAlarm(context, triggerMillis, pi)
    }

    fun scheduleBillReminder(context: Context, enabled: Boolean) {
        if (!enabled) {
            cancelAlarm(context, ACTION_BILLS, RC_BILLS)
            return
        }
        val now = LocalDateTime.now()
        var next = LocalDate.now().atTime(9, 0)
        if (!next.isAfter(now)) {
            next = next.plusDays(1)
        }
        val triggerMillis = next.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val intent = Intent(context, ReminderAlarmReceiver::class.java).apply { action = ACTION_BILLS }
        val pi = PendingIntent.getBroadcast(
            context,
            RC_BILLS,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        setAlarm(context, triggerMillis, pi)
    }

    fun scheduleDebtReminder(context: Context, enabled: Boolean = true) {
        if (!enabled) {
            cancelAlarm(context, ACTION_DEBTS, RC_DEBTS)
            return
        }
        val now = LocalDateTime.now()
        var next = LocalDate.now().atTime(9, 0)
        if (!next.isAfter(now)) {
            next = next.plusDays(1)
        }
        val triggerMillis = next.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val intent = Intent(context, ReminderAlarmReceiver::class.java).apply { action = ACTION_DEBTS }
        val pi = PendingIntent.getBroadcast(
            context,
            RC_DEBTS,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        setAlarm(context, triggerMillis, pi)
    }

    fun rescheduleAll(context: Context) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val container = (context.applicationContext as LeftoversApp).container
                val s = container.settings.settings.first()
                scheduleDailyReminder(context, s.reminderEnabled, s.reminderMinutes)
                scheduleShiftNightReminder(context, s.shiftRemindersEnabled, s.shiftReminderNightMinutes)
                scheduleNextShiftAdvanceReminder(context, s.shiftRemindersEnabled, s.shiftReminderAdvanceHours)
                scheduleNextShiftPostReminder(context, s.shiftRemindersEnabled)
                scheduleBillReminder(context, s.billReminders)
                scheduleDebtReminder(context, true)
            } catch (_: Exception) {
            }
        }
    }
}
