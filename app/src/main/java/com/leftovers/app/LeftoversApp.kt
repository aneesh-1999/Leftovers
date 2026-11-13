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
        // A crash leaves a short report the app offers to send next time; nothing is sent by itself.
        com.leftovers.app.util.CrashReports.install(this)
        container = AppContainer(this)
        container.budgetAlerts.createChannel()
        Reminders.createChannel(this)
        BillReminders.createChannel(this)
        DebtReminders.createChannel(this)
        ShiftReminders.createChannel(this)
        com.leftovers.app.data.creditcard.CreditCardReminders.createChannel(this)
        com.leftovers.app.updater.worker.AppUpdateCheckWorker.createChannel(this)
        com.leftovers.app.util.Greetings.createChannel(this)
        com.leftovers.app.util.UpdateCheck.createChannel(this)
        DebtReminders.schedule(this, true)
        com.leftovers.app.data.creditcard.CreditCardReminders.schedule(this, true)
        AlarmScheduler.rescheduleAll(this)
        container.start()
    }
}
