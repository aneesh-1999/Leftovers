package com.leftovers.app.data.detection

import com.leftovers.app.data.SmsSuggestion
import com.leftovers.app.data.TxType
import java.time.LocalDate

/** High-level direction or category of the transaction candidate. */
enum class CandidateType {
    EXPENSE,
    INCOME,
    TRANSFER,
    REFUND,
    OTHER,
}

/** Processing / settlement status of the transaction candidate. */
enum class CandidateStatus {
    COMPLETED,
    PENDING,
    DECLINED,
    FAILED,
}

/** Confidence level of the transaction extraction. */
enum class DetectionConfidence {
    HIGH,
    MEDIUM,
    LOW,
}

/**
 * Normalized representation of a transaction detected from any automatic source
 * (Payment app notification, bank SMS, or financial email alert).
 */
data class TransactionCandidate(
    val amountMinor: Long,
    val currency: String? = null,
    val merchant: String = "",
    val type: CandidateType = CandidateType.EXPENSE,
    val status: CandidateStatus = CandidateStatus.COMPLETED,
    val confidence: DetectionConfidence = DetectionConfidence.HIGH,
    val epochDay: Long = LocalDate.now().toEpochDay(),
    val timestamp: Long = System.currentTimeMillis(),
    val accountDigits: String? = null,
    /** Source type: [SmsSuggestion.SOURCE_NOTIFICATION], [SmsSuggestion.SOURCE_SMS], or [SmsSuggestion.SOURCE_EMAIL]. */
    val source: String = SmsSuggestion.SOURCE_NOTIFICATION,
    /** Package name of the sending app if from a notification, e.g. "com.revolut.revolut". */
    val packageName: String? = null,
    /** Human-readable sender or app name, e.g. "Revolut", "eSewa", or SMS originator. */
    val sender: String = "",
    /** Unique reference ID from provider or bank (e.g. UPI Ref, order ID), if present. */
    val referenceId: String? = null,
    /** SHA-256 fingerprint of the raw message or notification payload for idempotency. */
    val rawFingerprint: String = "",
    /** Account matched in Leftovers, if any. */
    val accountId: Long? = null,
    /** Suggested category ID based on merchant or history, if any. */
    val categoryId: Long? = null,
) {
    val isIncome: Boolean get() = type == CandidateType.INCOME || type == CandidateType.REFUND

    /** Converts candidate into a [SmsSuggestion] entity for the Home review queue. */
    fun toSmsSuggestion(): SmsSuggestion = SmsSuggestion(
        amountMinor = amountMinor,
        merchant = merchant,
        sender = sender.ifBlank { packageName.orEmpty() },
        epochDay = epochDay,
        body = rawFingerprint,
        receivedAt = timestamp,
        source = source,
        accountDigits = accountDigits,
        isIncome = isIncome,
        accountId = accountId,
    )

    /** Converts a completed, high-confidence candidate to a [com.leftovers.app.data.Transaction] for auto-saving. */
    fun toTransaction(resolvedCategoryId: Long, resolvedAccountId: Long?): com.leftovers.app.data.Transaction =
        com.leftovers.app.data.Transaction(
            amountMinor = amountMinor,
            type = if (isIncome) TxType.INCOME else TxType.EXPENSE,
            categoryId = resolvedCategoryId,
            epochDay = epochDay,
            note = merchant.trim(),
            createdAt = timestamp,
            accountId = resolvedAccountId,
        )
}
