package com.leftovers.app

import android.content.Context
import androidx.glance.appwidget.updateAll
import com.leftovers.app.ai.AssistantSettings
import com.leftovers.app.data.AccountRepository
import com.leftovers.app.data.AppDatabase
import com.leftovers.app.data.PlanningRepository
import com.leftovers.app.data.RosterRepository
import com.leftovers.app.data.SeedCategories
import com.leftovers.app.data.SettingsRepository
import com.leftovers.app.data.SmsRepository
import com.leftovers.app.data.TransactionRepository
import com.leftovers.app.data.TxType
import com.leftovers.app.util.AutoBackup
import com.leftovers.app.util.BackupManager
import com.leftovers.app.util.BackupPassword
import com.leftovers.app.util.BillReminders
import com.leftovers.app.util.DetectionLog
import com.leftovers.app.util.EmailAccount
import com.leftovers.app.util.EmailSync
import com.leftovers.app.util.BudgetAlertManager
import com.leftovers.app.util.Reminders
import com.leftovers.app.widget.MonthBudgetWidget
import com.leftovers.app.widget.SafeToSpendWidget
import com.leftovers.app.widget.WeekRosterWidget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate

/** Simple manual dependency container shared by the whole app. */
class AppContainer(val context: Context) {
    val appContext: Context get() = context
    private val database = AppDatabase.build(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val settings = SettingsRepository(context)
    val syncRemoved = com.leftovers.app.util.SyncRemoved(File(context.filesDir, "sync-removed.json"))
    val repository = TransactionRepository(database.transactionDao(), database.categoryDao(), database.recurringDao(), database.deletedDao(), syncRemoved)
    val planning = PlanningRepository(database)
    val accounts = AccountRepository(database.accountDao(), database.transferDao(), database.transactionDao(), database.debtDao())
    val syncLogin = com.leftovers.app.util.SyncLogin(context)
    val passcode = com.leftovers.app.util.AppPasscode(context)
    val paymentApps = com.leftovers.app.util.PaymentAppPrefs(context)
    val sms = SmsRepository(database.smsDao(), database.transactionDao())
    val detectionPipeline = com.leftovers.app.data.detection.TransactionDetectionPipeline(
        context = context,
        transactionRepository = repository,
        accountRepository = accounts,
        smsRepository = sms,
        settingsRepository = settings,
    )
    val roster = RosterRepository(
        database.shiftDao(),
        database.workplaceDao(),
        database.leaveDao(),
        database.workplacePaymentDao(),
        database.deletedDao(),
    )
    val remittances = com.leftovers.app.data.RemittanceRepository(
        database.remittanceDao(),
        database.recipientDao(),
        database.remittanceStatusHistoryDao(),
        database.transactionDao(),
        database.categoryDao(),
        settings,
        database,
    )
    val investments = com.leftovers.app.data.investment.InvestmentRepository(
        database.investmentDao(),
        com.leftovers.app.data.investment.provider.CompositeMarketDataService(database.investmentDao()),
    )
    val creditCards = com.leftovers.app.data.creditcard.CreditCardRepository(
        database.creditCardDao(),
        database.accountDao(),
        database.transferDao(),
        database.transactionDao(),
        database.categoryDao(),
    )
    val roomExpenses = com.leftovers.app.data.roomexpense.RoomExpenseRepository(
        context,
        database.roomExpenseDao(),
        database.transactionDao(),
        database.categoryDao(),
        settings,
        sms,
    )
    val roomExpenseDao = database.roomExpenseDao()
    val backup = BackupManager(context, database, settings, BackupPassword(context))
    val authManager = com.leftovers.app.data.cloud.FirebaseAuthManager(context, settings, scope)
    val cloudBackup = com.leftovers.app.data.cloud.CloudBackupRepository(context, authManager, backup, settings)
    val budgetAlerts = BudgetAlertManager(context, repository, settings)
    val assistant = AssistantSettings(context)
    val emailAccount = EmailAccount(context)
    val updater = com.leftovers.app.updater.repository.AppUpdateRepository(context, settings)

    /**
     * Wipes everything back to a fresh install: all data and photos, settings, the connected AI and email,
     * and the backup password. The app then shows the welcome screen.
     */
    suspend fun resetEverything() = withContext(Dispatchers.IO) {
        AutoBackup.schedule(context, false)
        com.leftovers.app.data.cloud.CloudBackupScheduler.schedule(context, false)
        EmailSync.schedule(context, false)
        database.clearAllTables()
        SeedCategories.onCreate(database.openHelper.writableDatabase)
        File(context.filesDir, "receipts").deleteRecursively()
        emailAccount.clear()
        assistant.disconnect()
        backup.password.set(null)
        authManager.signOut()
        passcode.clear()
        paymentApps.clear()
        syncLogin.set(null)
        syncRemoved.clear()
        DetectionLog.clear(context)
        settings.clearAll()
    }

    fun start() {
        scope.launch {
            sms.scrubRawBodies()
            sms.forgetOldSeen()
        }
        // Initial widget update
        scope.launch {
            runCatching { SafeToSpendWidget().updateAll(context) }
            runCatching { MonthBudgetWidget().updateAll(context) }
            runCatching { com.leftovers.app.widget.QuickAddWidget().updateAll(context) }
            runCatching { WeekRosterWidget().updateAll(context) }
        }
        // Keep the home-screen widgets in step with the data.
        scope.launch {
            combine(repository.allTransactions, settings.settings, planning.recurring) { _, _, _ -> Unit }
                .collectLatest {
                    delay(600)
                    runCatching { SafeToSpendWidget().updateAll(context) }
                    runCatching { MonthBudgetWidget().updateAll(context) }
                    runCatching { com.leftovers.app.widget.QuickAddWidget().updateAll(context) }
                }
        }
        scope.launch {
            combine(roster.shifts, roster.leaves, roster.workplaces) { _, _, _ -> Unit }
                .collectLatest {
                    delay(600)
                    runCatching { WeekRosterWidget().updateAll(context) }
                }
        }
        // Sync between devices: every few hours, and once each time the app starts.
        scope.launch {
            settings.settings.map { it.syncDir != null || it.syncServer != null }
                .distinctUntilChanged()
                .collect { on ->
                    com.leftovers.app.util.DeviceSync.schedule(context, on)
                    if (on) com.leftovers.app.util.DeviceSync.runIfOn(context)
                }
        }
        scope.launch {
            settings.settings.map { it.updateCheck }
                .distinctUntilChanged()
                .collect { com.leftovers.app.util.UpdateCheck.schedule(context, it) }
        }
        // Clear deleted entries older than the user's chosen keep period.
        scope.launch {
            settings.settings.map { it.deletedKeepDays }
                .distinctUntilChanged()
                .collect { repository.purgeDeleted(it) }
        }
        scope.launch {
            emailAccount.connection.map { it != null }
                .distinctUntilChanged()
                .collect { EmailSync.schedule(context, it) }
        }
        scope.launch {
            settings.settings.map { (it.autoBackupDir != null) to it.autoBackupDays }
                .distinctUntilChanged()
                .collect { (on, days) -> AutoBackup.schedule(context, on, days) }
        }
        scope.launch {
            settings.settings.map { it.cloudBackupEnabled }
                .distinctUntilChanged()
                .collect { com.leftovers.app.data.cloud.CloudBackupScheduler.schedule(context, it) }
        }
        scope.launch {
            settings.settings.map { it.billReminders }
                .distinctUntilChanged()
                .collect { BillReminders.schedule(context, it) }
        }
        // Reschedule the reminder whenever its settings change.
        scope.launch {
            settings.settings.map { it.reminderEnabled to it.reminderMinutes }
                .distinctUntilChanged()
                .collect { (enabled, minutes) -> Reminders.schedule(context, enabled, minutes) }
        }
        // Reschedule shift & roster reminders whenever settings change.
        scope.launch {
            settings.settings.map { Triple(it.shiftRemindersEnabled, it.shiftReminderNightMinutes, it.shiftReminderAdvanceHours) }
                .distinctUntilChanged()
                .collect { (enabled, nightMinutes, advanceHours) ->
                    com.leftovers.app.util.ShiftReminders.schedule(context, enabled, nightMinutes, advanceHours)
                }
        }
        // Keep shift advance/post-completion alarms in sync with schedule additions/updates
        scope.launch {
            roster.shifts.collectLatest {
                val s = settings.settings.first()
                com.leftovers.app.util.AlarmScheduler.scheduleNextShiftAdvanceReminder(context, s.shiftRemindersEnabled, s.shiftReminderAdvanceHours)
                com.leftovers.app.util.AlarmScheduler.scheduleNextShiftPostReminder(context, s.shiftRemindersEnabled)
            }
        }
        // Keep money owed alarms in sync with debts
        scope.launch {
            planning.debts.collectLatest {
                com.leftovers.app.util.AlarmScheduler.scheduleDebtReminder(context, true)
            }
        }
        // Reschedule credit card bill reminders whenever settings change
        scope.launch {
            settings.settings.map { it.creditCardRemindersEnabled }
                .distinctUntilChanged()
                .collect { com.leftovers.app.data.creditcard.CreditCardReminders.schedule(context, it) }
        }
        // Keep credit card alarms in sync with cards / statements
        scope.launch {
            creditCards.activeCardsWithSummary.collectLatest {
                val enabled = settings.settings.first().creditCardRemindersEnabled
                com.leftovers.app.util.AlarmScheduler.scheduleCreditCardReminder(context, enabled)
            }
        }
        // Festival greetings and the Sunday note come with notifications; no switches of their own.
        scope.launch {
            delay(2_000L)
            com.leftovers.app.util.Greetings.schedule(context, festivals = true, weekly = true)
        }
        scope.launch {
            delay(2_000L)
            settings.settings.map { it.autoRates }
                .distinctUntilChanged()
                .collect { com.leftovers.app.util.ExchangeRates.schedule(context, it) }
        }
        // Reschedule automatic update checks whenever settings change
        scope.launch {
            settings.settings.map { it.autoUpdateChecks to it.updateCheckWifiOnly }
                .distinctUntilChanged()
                .collect { (enabled, wifiOnly) ->
                    com.leftovers.app.updater.worker.AppUpdateCheckWorker.schedulePeriodicCheck(context, enabled, wifiOnly)
                }
        }
    }

    /** Looks for a newer version on GitHub; one found goes to the bell and Notices. Returns it, or null. */
    private suspend fun lookForUpdate(): com.leftovers.app.util.UpdateCheck.Release? =
        com.leftovers.app.util.UpdateCheck.latest(com.leftovers.app.util.UpdateCheck.isDevBuild(context)).getOrNull()
            ?.takeIf { com.leftovers.app.util.UpdateCheck.isNewerThanInstalled(context, it) }

    /**
     * Home's pull to refresh: every check that can run on demand (subscriptions that have come due, the email
     * account if one is connected, Sync between devices if it's on), and a few words on what it found.
     */
    suspend fun refreshNow(): String = coroutineScope {
        val email = async {
            if (emailAccount.connection.value == null) null else com.leftovers.app.util.EmailChecker.check(context)
        }
        val sync = async { com.leftovers.app.util.DeviceSync.runIfOn(context) }
        val update = async { lookForUpdate() }
        syncRecurring()
        val parts = buildList {
            email.await()?.fold(
                { found -> if (found > 0) add("$found new ${if (found == 1) "payment" else "payments"} from email") },
                { add("Couldn't check email") },
            )
            sync.await()?.fold(
                { o ->
                    val changed = o.added + o.removed
                    if (changed > 0) add("$changed ${if (changed == 1) "change" else "changes"} from your other phones")
                },
                { add("Couldn't sync") },
            )
            update.await()?.let { add(if (com.leftovers.app.util.UpdateCheck.isDevBuild(context)) "A new Dev build is out" else "Leftovers ${it.version} is out") }
        }
        parts.joinToString(" · ").ifEmpty { "Up to date" }
    }

    /** Logs subscriptions/recurring income that have come due, then checks budgets. */
    suspend fun syncRecurring() {
        settings.ensureCarryStart()
        val account = settings.settings.first().defaultAccountId
        planning.postDueRecurring(account)
            .filter { it.type == TxType.EXPENSE }
            .forEach { budgetAlerts.checkAfterExpense(LocalDate.ofEpochDay(it.epochDay), it.categoryId) }
    }
}
