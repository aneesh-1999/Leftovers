package com.leftovers.app.util

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationManagerCompat
import com.leftovers.app.LeftoversApp
import com.leftovers.app.data.ShiftStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Handles action buttons from shift reminder notifications
 * (e.g. marking a shift complete or cancelling a shift directly from the notification).
 */
class ShiftActionReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION_COMPLETE_SHIFT = "com.leftovers.app.ACTION_COMPLETE_SHIFT"
        const val ACTION_CANCEL_SHIFT = "com.leftovers.app.ACTION_CANCEL_SHIFT"
        const val EXTRA_SHIFT_ID = "extra_shift_id"
        const val EXTRA_NOTIFICATION_ID = "extra_notification_id"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val shiftId = intent.getLongExtra(EXTRA_SHIFT_ID, -1L)
        val notifId = intent.getIntExtra(EXTRA_NOTIFICATION_ID, -1)
        if (shiftId == -1L) return

        if (notifId != -1) {
            NotificationManagerCompat.from(context).cancel(notifId)
        }

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val container = (context.applicationContext as LeftoversApp).container
                val allShifts = container.roster.shifts.first()
                val shift = allShifts.find { it.id == shiftId }
                if (shift != null) {
                    when (intent.action) {
                        ACTION_COMPLETE_SHIFT -> {
                            container.roster.saveShift(shift.copy(status = ShiftStatus.COMPLETED))
                        }
                        ACTION_CANCEL_SHIFT -> {
                            container.roster.saveShift(shift.copy(status = ShiftStatus.CANCELLED))
                        }
                    }
                }
            } finally {
                pendingResult.finish()
            }
        }
    }
}
