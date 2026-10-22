package com.leftovers.app.util.detection

import com.leftovers.app.data.detection.TransactionCandidate

/**
 * Interface implemented by app-specific payment notification parsers
 * (e.g. Revolut, eSewa, Generic/BankSmart).
 */
interface PaymentNotificationParser {
    /** Returns true if this parser can interpret notifications from [packageName]. */
    fun canHandle(packageName: String): Boolean

    /**
     * Extracts transaction details into a normalized [TransactionCandidate].
     * Returns null if the notification does not represent a financial transaction,
     * or if the format cannot be parsed with confidence.
     */
    fun parse(
        packageName: String,
        title: String,
        text: String,
        subText: String?,
        postTime: Long,
        fingerprint: String,
    ): TransactionCandidate?
}
