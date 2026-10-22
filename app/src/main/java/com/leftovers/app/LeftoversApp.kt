package com.leftovers.app

import android.app.Application
import androidx.work.Configuration
import com.leftovers.app.util.AlarmScheduler
import com.leftovers.app.util.BillReminders
import com.leftovers.app.util.DebtReminders
import com.leftovers.app.util.Reminders
import com.leftovers.app.util.ShiftReminders

class LeftoversApp : Application(), Configuration.Provider {
    lateinit var container: AppContainer
        private set

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setMinimumLoggingLevel(android.util.Log.INFO)
            .build()

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.budgetAlerts.createChannel()
        Reminders.createChannel(this)
        BillReminders.createChannel(this)
        DebtReminders.createChannel(this)
        ShiftReminders.createChannel(this)
        com.leftovers.app.data.creditcard.CreditCardReminders.createChannel(this)
        com.leftovers.app.updater.worker.AppUpdateCheckWorker.createChannel(this)
        DebtReminders.schedule(this, true)
        com.leftovers.app.data.creditcard.CreditCardReminders.schedule(this, true)
        AlarmScheduler.rescheduleAll(this)
        container.start()
    }
}