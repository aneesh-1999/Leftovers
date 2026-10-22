package com.leftovers.app.data.creditcard

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
import com.leftovers.app.util.Money
import com.leftovers.app.util.AlarmScheduler
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.concurrent.TimeUnit

/**
 * Handles scheduling and delivery of local notifications for credit card payment deadlines.
 * Alerts are triggered:
 * - 7 days before due date
 * - 3 days before due date
 * - 1 day before due date
 * - On the due date
 * - Daily when overdue
 *
 * Automatically suppressed once a statement is fully paid.
 */
object CreditCardReminders {
    const val CHANNEL_ID = "credit_card_reminders"
    private const val WORK_NAME = "credit_card_reminders"
    private val CHECK_AT: LocalTime = LocalTime.of(9, 15)

    fun createChannel(context: Context) {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Credit Card Payment Reminders",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "Upcoming and overdue payment due date reminders for credit cards"
            enableVibration(true)
        }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    fun schedule(context: Context, enabled: Boolean = true) {
        // Primary: Exact alarm through AlarmScheduler
        AlarmScheduler.scheduleCreditCardReminder(context, enabled)

        // Secondary fallback: WorkManager periodic work
        val wm = WorkManager.getInstance(context)
        if (!enabled) {
            wm.cancelUniqueWork(WORK_NAME)
            return
        }
        val now = LocalDateTime.now()
        var next = LocalDate.now().atTime(CHECK_AT)
        if (!next.isAfter(now)) next = next.plusDays(1)
        val request = PeriodicWorkRequestBuilder<CreditCardReminderWorker>(24, TimeUnit.HOURS)
            .setInitialDelay(Duration.between(now, next).toMinutes(), TimeUnit.MINUTES)
            .build()
        wm.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    suspend fun deliverPaymentReminders(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return false

        val app = context.applicationContext as LeftoversApp
        val repo = app.container.creditCards
        val settings = app.container.settings

        val today = LocalDate.now()
        val todayEpochDay = today.toEpochDay()

        val cards = repo.getActiveCardsSync().filter { !it.isArchived }
        if (cards.isEmpty()) return false

        val cardMap = cards.associateBy { it.id }
        val allStatements = repo.getAllStatementsSync()
        val allAllocations = repo.getAllocationsSync()

        var deliveredCount = 0

        for (stmt in allStatements) {
            val card = cardMap[stmt.cardId] ?: continue
            val evaluated = CreditCardMath.evaluateStatement(stmt, allAllocations, todayEpochDay)

            // If statement is fully paid, skip reminders
            if (evaluated.remainingBalanceMinor <= 0L) continue

            val daysUntilDue = evaluated.daysUntilDue
            val shouldNotify = when {
                daysUntilDue == 7L -> true
                daysUntilDue == 3L -> true
                daysUntilDue == 1L -> true
                daysUntilDue == 0L -> true
                daysUntilDue < 0L -> true // overdue
                else -> false
            }

            if (!shouldNotify) continue

            val alertKey = "cc_reminder_${stmt.id}_${todayEpochDay}_d${daysUntilDue}"
            val newlySent = settings.markAlertSent(alertKey)
            if (!newlySent) continue

            val money = Money(card.currency)
            val formattedRemaining = money.format(evaluated.remainingBalanceMinor)
            val formattedMinDue = money.format(evaluated.remainingMinDueMinor)

            val title: String
            val text: String

            when {
                daysUntilDue == 7L -> {
                    title = "Credit card payment due in 7 days"
                    text = "${card.nickname}: $formattedRemaining due on ${LocalDate.ofEpochDay(stmt.dueEpochDay)}"
                }
                daysUntilDue == 3L -> {
                    title = "Payment due in 3 days"
                    text = "Your $formattedRemaining statement payment for ${card.nickname} is due in 3 days."
                }
                daysUntilDue == 1L -> {
                    title = "Payment due tomorrow"
                    text = "${card.nickname}: $formattedRemaining due tomorrow. Minimum due is $formattedMinDue."
                }
                daysUntilDue == 0L -> {
                    title = "Payment due today"
                    text = "${card.nickname}: Outstanding statement balance of $formattedRemaining is due today."
                }
                else -> {
                    val daysAgo = -daysUntilDue
                    val agoText = if (daysAgo == 1L) "yesterday" else "$daysAgo days ago"
                    title = "Overdue credit card payment"
                    text = "${card.nickname}: $formattedRemaining was due $agoText. Please check your payment status."
                }
            }

            val openIntent = Intent(context, MainActivity::class.java).apply {
                putExtra("extra_open_credit_cards", true)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            val openPi = PendingIntent.getActivity(
                context,
                (7000 + stmt.id).toInt(),
                openIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )

            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_wallet)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setContentIntent(openPi)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .build()

            NotificationManagerCompat.from(context).notify((7000 + stmt.id).toInt(), notification)
            deliveredCount++
        }

        return deliveredCount > 0
    }
}

class CreditCardReminderWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        CreditCardReminders.deliverPaymentReminders(applicationContext)
        return Result.success()
    }
}
