package com.leftovers.app.util

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.leftovers.app.LeftoversApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * BroadcastReceiver triggered by AlarmManager even when the app is in background,
 * device is asleep, or app has not been opened for days.
 */
class ReminderAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val app = context.applicationContext as LeftoversApp
                val container = app.container
                val s = container.settings.settings.first()

                when (action) {
                    AlarmScheduler.ACTION_DAILY_REMINDER -> {
                        Reminders.deliverDailyReminder(context)
                        AlarmScheduler.scheduleDailyReminder(context, s.reminderEnabled, s.reminderMinutes)
                    }
                    AlarmScheduler.ACTION_SHIFT_NIGHT -> {
                        ShiftReminders.deliverNightReminder(context)
                        AlarmScheduler.scheduleShiftNightReminder(context, s.shiftRemindersEnabled, s.shiftReminderNightMinutes)
                    }
                    AlarmScheduler.ACTION_SHIFT_ADVANCE -> {
                        ShiftReminders.deliverAdvanceReminder(context)
                        AlarmScheduler.scheduleNextShiftAdvanceReminder(context, s.shiftRemindersEnabled, s.shiftReminderAdvanceHours)
                    }
                    AlarmScheduler.ACTION_SHIFT_POST -> {
                        ShiftReminders.deliverPostShiftReminder(context)
                        AlarmScheduler.scheduleNextShiftPostReminder(context, s.shiftRemindersEnabled)
                    }
                    AlarmScheduler.ACTION_BILLS -> {
                        BillReminders.deliverBillReminder(context)
                        AlarmScheduler.scheduleBillReminder(context, s.billReminders)
                    }
                    AlarmScheduler.ACTION_DEBTS -> {
                        DebtReminders.deliverDebtReminder(context)
                        AlarmScheduler.scheduleDebtReminder(context, true)
                    }
                }
            } catch (_: Exception) {
            } finally {
                pendingResult.finish()
            }
        }
    }
}
