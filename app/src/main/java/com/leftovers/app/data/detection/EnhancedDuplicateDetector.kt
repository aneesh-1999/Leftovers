package com.leftovers.app.data.detection

import com.leftovers.app.data.DuplicatePayments
import com.leftovers.app.data.SmsSuggestion
import com.leftovers.app.data.Transaction
import java.util.Locale
import kotlin.math.abs

/** Result of an enhanced duplicate evaluation. */
sealed class DuplicateEvaluation {
    /** Distinct transaction; safe to proceed. */
    data object Unique : DuplicateEvaluation()

    /** Definite duplicate; should be dropped to avoid double-counting. */
    data class ConfirmedDuplicate(val reason: String) : DuplicateEvaluation()

    /**
     * Ambiguous match (e.g. same amount in time window but merchant is missing or unverified).
     * Must be routed to the review queue with a warning rather than auto-saved or dropped.
     */
    data class AmbiguousMatch(val reason: String) : DuplicateEvaluation()
}

/**
 * Cross-source, multi-field duplicate detection comparing:
 * - Exact SHA-256 message/notification payload fingerprint.
 * - Provider / bank transaction reference IDs.
 * - Amount, currency, transaction direction (income/expense), counterparty/merchant,
 *   and timestamp within the 2-hour window.
 */
object EnhancedDuplicateDetector {

    /**
     * Evaluates whether [candidate] is a duplicate against existing transactions and suggestions.
     *
     * @param candidate The newly parsed candidate.
     * @param existingTransactions Transactions already logged in the database.
     * @param pendingSuggestions Suggestions currently awaiting user review.
     * @param seenHashes Known SHA-256 message hashes.
     */
    fun evaluate(
        candidate: TransactionCandidate,
        existingTransactions: List<Transaction>,
        pendingSuggestions: List<SmsSuggestion>,
        seenHashes: Set<String> = emptySet(),
    ): DuplicateEvaluation {
        // 1. Exact raw payload / fingerprint match
        if (candidate.rawFingerprint.isNotBlank()) {
            if (candidate.rawFingerprint in seenHashes) {
                return DuplicateEvaluation.ConfirmedDuplicate("Identical notification or SMS payload already processed")
            }
            if (pendingSuggestions.any { it.body == candidate.rawFingerprint }) {
                return DuplicateEvaluation.ConfirmedDuplicate("Identical suggestion already pending in review queue")
            }
        }

        // 2. Reference ID match (e.g. "REV123456", "TXN987654")
        if (!candidate.referenceId.isNullOrBlank()) {
            val ref = candidate.referenceId.trim().lowercase(Locale.ROOT)
            val matchedTx = existingTransactions.firstOrNull { it.note.contains(ref, ignoreCase = true) }
            if (matchedTx != null) {
                return DuplicateEvaluation.ConfirmedDuplicate("Transaction reference $ref already recorded in an entry")
            }
        }

        // 3. Multi-field comparison against existing logged transactions within window
        for (tx in existingTransactions) {
            val isSameWindow = abs(tx.createdAt - candidate.timestamp) <= DuplicatePayments.WINDOW_MS
            if (!isSameWindow) continue

            val isSameAmount = tx.amountMinor == candidate.amountMinor
            val isSameDirection = (tx.type == com.leftovers.app.data.TxType.INCOME) == candidate.isIncome
            if (!isSameAmount || !isSameDirection) continue

            // Account check: If both have accounts, and they differ, they may be separate accounts
            if (tx.accountId != null && candidate.accountId != null && tx.accountId != candidate.accountId) {
                continue
            }

            // Compare merchants
            val merchantMatch = compareMerchants(candidate.merchant, tx.note)
            when (merchantMatch) {
                MerchantComparison.MATCH -> return DuplicateEvaluation.ConfirmedDuplicate("Duplicate of logged payment '${tx.note}'")
                MerchantComparison.DIFFERENT -> continue // Distinct purchases with the same amount
                MerchantComparison.AMBIGUOUS -> return DuplicateEvaluation.AmbiguousMatch("Same amount (${candidate.amountMinor}) within 2 hours of '${tx.note}'")
            }
        }

        // 4. Multi-field comparison against pending suggestions in review queue
        for (s in pendingSuggestions) {
            val isSameWindow = abs(s.receivedAt - candidate.timestamp) <= DuplicatePayments.WINDOW_MS
            if (!isSameWindow) continue

            val isSameAmount = s.amountMinor == candidate.amountMinor
            val isSameDirection = s.isIncome == candidate.isIncome
            if (!isSameAmount || !isSameDirection) continue

            val merchantMatch = compareMerchants(candidate.merchant, s.merchant)
            when (merchantMatch) {
                MerchantComparison.MATCH -> return DuplicateEvaluation.ConfirmedDuplicate("Duplicate of pending suggestion for '${s.merchant}'")
                MerchantComparison.DIFFERENT -> continue
                MerchantComparison.AMBIGUOUS -> return DuplicateEvaluation.AmbiguousMatch("Same amount (${candidate.amountMinor}) within 2 hours of pending suggestion '${s.merchant}'")
            }
        }

        return DuplicateEvaluation.Unique
    }

    private enum class MerchantComparison { MATCH, DIFFERENT, AMBIGUOUS }

    /** Compares two merchant or note strings for similarity. */
    private fun compareMerchants(nameA: String, nameB: String): MerchantComparison {
        val a = cleanMerchant(nameA)
        val b = cleanMerchant(nameB)

        // If either is empty or purely generic, we cannot be certain they are identical
        if (a.isBlank() || b.isBlank() || isGeneric(a) || isGeneric(b)) {
            return MerchantComparison.AMBIGUOUS
        }

        if (a == b || a.contains(b) || b.contains(a)) {
            return MerchantComparison.MATCH
        }

        // Check significant token overlap (e.g. "Starbucks Cafe" vs "Starbucks Airport")
        val tokensA = a.split(Regex("""\s+""")).filter { it.length >= 3 }.toSet()
        val tokensB = b.split(Regex("""\s+""")).filter { it.length >= 3 }.toSet()
        if (tokensA.isNotEmpty() && tokensB.isNotEmpty()) {
            val overlap = tokensA.intersect(tokensB)
            if (overlap.isNotEmpty()) return MerchantComparison.MATCH
        }

        return MerchantComparison.DIFFERENT
    }

    private fun cleanMerchant(raw: String): String =
        raw.trim().lowercase(Locale.ROOT)
            .replace(Regex("""^(paid to|spent at|to|at|vpa|upi)\s+""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""[.,;:\-_/'"()\[\]]"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()

    private fun isGeneric(name: String): Boolean =
        name in setOf("bank payment", "money in", "payment", "card payment", "transfer", "expense", "pos purchase", "room expense", "shared expense")
}
