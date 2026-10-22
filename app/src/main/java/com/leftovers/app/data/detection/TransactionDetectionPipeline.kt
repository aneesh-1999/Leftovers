package com.leftovers.app.data.detection

import android.content.Context
import com.leftovers.app.data.AccountRepository
import com.leftovers.app.data.AccountWithBalance
import com.leftovers.app.data.DuplicatePayments
import com.leftovers.app.data.SeenMessage
import com.leftovers.app.data.SettingsRepository
import com.leftovers.app.data.SmsRepository
import com.leftovers.app.data.SmsSuggestion
import com.leftovers.app.data.Transaction
import com.leftovers.app.data.TransactionItem
import com.leftovers.app.data.TransactionRepository
import com.leftovers.app.data.matchFor
import com.leftovers.app.util.DetectionLog
import com.leftovers.app.util.Money
import kotlinx.coroutines.flow.first

/** Outcome of processing a transaction candidate through the detection pipeline. */
sealed class DetectionResult {
    data class AutoSaved(val transactionId: Long?, val message: String) : DetectionResult()
    data class QueuedForReview(val suggestionAdded: Boolean, val message: String) : DetectionResult()
    data class IgnoredDuplicate(val reason: String) : DetectionResult()
    data class Rejected(val reason: String) : DetectionResult()
}

/**
 * Shared transaction detection and normalization pipeline used across:
 * - Payment app notifications (Revolut, eSewa, etc.)
 * - Bank SMS messages
 * - Email transaction alerts
 */
class TransactionDetectionPipeline(
    private val context: Context,
    private val transactionRepository: TransactionRepository,
    private val accountRepository: AccountRepository,
    private val smsRepository: SmsRepository,
    private val settingsRepository: SettingsRepository,
) {

    suspend fun process(candidate: TransactionCandidate, autoSaveEnabled: Boolean = false): DetectionResult {
        // 1. Basic validation
        if (candidate.amountMinor <= 0) {
            DetectionLog.add(context, candidate.source.uppercase(), candidate.sender, "Rejected: non-positive amount (${candidate.amountMinor})")
            return DetectionResult.Rejected("Invalid amount")
        }

        if (candidate.status == CandidateStatus.DECLINED || candidate.status == CandidateStatus.FAILED) {
            DetectionLog.add(context, candidate.source.uppercase(), candidate.sender, "Ignored: payment ${candidate.status.name.lowercase()}")
            return DetectionResult.Rejected("Payment ${candidate.status.name.lowercase()}")
        }

        // Stale notification guard (older than 15 minutes)
        val maxAgeMs = java.util.concurrent.TimeUnit.MINUTES.toMillis(15)
        if (candidate.source == SmsSuggestion.SOURCE_NOTIFICATION && (System.currentTimeMillis() - candidate.timestamp) > maxAgeMs) {
            DetectionLog.add(context, candidate.source.uppercase(), candidate.sender, "Ignored: stale notification from ${java.util.Date(candidate.timestamp)}")
            return DetectionResult.Rejected("Stale notification")
        }

        // 2. Resolve Account
        val accounts = accountRepository.accounts.first()
        val resolvedAccountId = candidate.accountId ?: resolveAccount(candidate, accounts)

        // 3. Category Suggestion
        val allCategories = transactionRepository.categories.first()
        val defaultCategory = if (candidate.isIncome) {
            allCategories.firstOrNull { it.type == com.leftovers.app.data.TxType.INCOME }
        } else {
            allCategories.firstOrNull { it.type == com.leftovers.app.data.TxType.EXPENSE }
        }
        val resolvedCategoryId = candidate.categoryId ?: defaultCategory?.id ?: 1L

        val normalizedCandidate = candidate.copy(
            accountId = resolvedAccountId,
            categoryId = resolvedCategoryId,
        )

        // 4. Enhanced Duplicate Detection
        val fromWindow = candidate.timestamp - DuplicatePayments.WINDOW_MS
        val toWindow = candidate.timestamp + DuplicatePayments.WINDOW_MS
        val candidateDay = candidate.epochDay
        val recentTxs = transactionRepository.allTransactions.first()
            .filter { it.createdAt in fromWindow..toWindow || it.epochDay == candidateDay }
            .map { it.toTransaction() }
        val pendingSuggestions = smsRepository.suggestions.first()

        val dupEvaluation = EnhancedDuplicateDetector.evaluate(
            candidate = normalizedCandidate,
            existingTransactions = recentTxs,
            pendingSuggestions = pendingSuggestions,
        )

        when (dupEvaluation) {
            is DuplicateEvaluation.ConfirmedDuplicate -> {
                DetectionLog.add(context, candidate.source.uppercase(), candidate.sender, "Duplicate: ${dupEvaluation.reason}")
                return DetectionResult.IgnoredDuplicate(dupEvaluation.reason)
            }
            is DuplicateEvaluation.AmbiguousMatch,
            is DuplicateEvaluation.Unique -> {
                // Continue processing. Ambiguous matches will be forced to review queue.
            }
        }

        val settings = settingsRepository.settings.first()
        val formattedAmount = (if (normalizedCandidate.isIncome) "+" else "") +
            Money(settings.currencyCode).format(normalizedCandidate.amountMinor)

        // 5. Automatic Saving vs Review Decision
        val canAutoSave = autoSaveEnabled &&
            dupEvaluation is DuplicateEvaluation.Unique &&
            normalizedCandidate.confidence == DetectionConfidence.HIGH &&
            normalizedCandidate.status == CandidateStatus.COMPLETED &&
            (normalizedCandidate.type == CandidateType.EXPENSE || normalizedCandidate.type == CandidateType.INCOME)

        if (canAutoSave) {
            val tx = normalizedCandidate.toTransaction(
                resolvedCategoryId = resolvedCategoryId,
                resolvedAccountId = resolvedAccountId,
            )
            transactionRepository.saveTransaction(tx)
            if (candidate.rawFingerprint.isNotBlank()) {
                smsRepository.markSeen(candidate.rawFingerprint)
            }
            val logMessage = "Auto-saved $formattedAmount at ${normalizedCandidate.merchant.ifBlank { "merchant" }}"
            DetectionLog.add(context, candidate.source.uppercase(), candidate.sender, logMessage)
            return DetectionResult.AutoSaved(if (tx.id != 0L) tx.id else null, logMessage)
        } else {
            // Queue for Review
            val suggestion = normalizedCandidate.toSmsSuggestion()
            val added = smsRepository.insertSuggestion(suggestion)
            val logMessage = if (added) {
                "Suggested $formattedAmount (${normalizedCandidate.merchant.ifBlank { candidate.sender }})"
            } else {
                "$formattedAmount was already logged or suggested"
            }
            DetectionLog.add(context, candidate.source.uppercase(), candidate.sender, logMessage)
            return DetectionResult.QueuedForReview(added, logMessage)
        }
    }

    private fun resolveAccount(candidate: TransactionCandidate, accounts: List<AccountWithBalance>): Long? {
        if (!candidate.accountDigits.isNullOrBlank()) {
            accounts.firstOrNull { it.matchesDigits(candidate.accountDigits) }?.let { return it.id }
        }
        if (candidate.merchant.isNotBlank()) {
            accounts.matchFor(candidate.merchant)?.let { return it.id }
        }
        if (candidate.sender.isNotBlank()) {
            accounts.firstOrNull { it.name.contains(candidate.sender, ignoreCase = true) }?.let { return it.id }
        }
        return null
    }
}
