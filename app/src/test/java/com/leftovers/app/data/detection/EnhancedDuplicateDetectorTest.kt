package com.leftovers.app.data.detection

import com.leftovers.app.data.DuplicatePayments
import com.leftovers.app.data.SmsSuggestion
import com.leftovers.app.data.Transaction
import com.leftovers.app.data.TxType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EnhancedDuplicateDetectorTest {

    private val baseTime = 1_700_000_000_000L

    @Test
    fun exactFingerprintDuplicate() {
        val candidate = TransactionCandidate(
            amountMinor = 1450L,
            merchant = "Starbucks",
            rawFingerprint = "fingerprint-abc-123",
            timestamp = baseTime,
        )
        val result = EnhancedDuplicateDetector.evaluate(
            candidate = candidate,
            existingTransactions = emptyList(),
            pendingSuggestions = emptyList(),
            seenHashes = setOf("fingerprint-abc-123"),
        )
        assertTrue(result is DuplicateEvaluation.ConfirmedDuplicate)
    }

    @Test
    fun referenceIdDuplicate() {
        val candidate = TransactionCandidate(
            amountMinor = 150_000L,
            merchant = "Bhatbhateni",
            referenceId = "TXN-998877",
            timestamp = baseTime,
        )
        val loggedTx = Transaction(
            id = 1L,
            amountMinor = 150_000L,
            type = TxType.EXPENSE,
            categoryId = 1L,
            epochDay = 20000L,
            note = "Bhatbhateni (Ref: TXN-998877)",
            createdAt = baseTime - 1000L,
        )
        val result = EnhancedDuplicateDetector.evaluate(
            candidate = candidate,
            existingTransactions = listOf(loggedTx),
            pendingSuggestions = emptyList(),
        )
        assertTrue(result is DuplicateEvaluation.ConfirmedDuplicate)
    }

    @Test
    fun sameAmountAndSameMerchantInWindowIsConfirmedDuplicate() {
        val candidate = TransactionCandidate(
            amountMinor = 2500L,
            merchant = "Starbucks Cafe",
            timestamp = baseTime,
        )
        val loggedTx = Transaction(
            id = 2L,
            amountMinor = 2500L,
            type = TxType.EXPENSE,
            categoryId = 1L,
            epochDay = 20000L,
            note = "Starbucks",
            createdAt = baseTime - 15 * 60 * 1000L, // 15 mins prior
        )
        val result = EnhancedDuplicateDetector.evaluate(
            candidate = candidate,
            existingTransactions = listOf(loggedTx),
            pendingSuggestions = emptyList(),
        )
        assertTrue(result is DuplicateEvaluation.ConfirmedDuplicate)
    }

    @Test
    fun sameAmountWithDistinctMerchantsInWindowIsUnique() {
        // User purchases coffee at Starbucks for €3.50, and 30 minutes later at Pret A Manger for €3.50
        val candidate = TransactionCandidate(
            amountMinor = 350L,
            merchant = "Pret A Manger",
            timestamp = baseTime,
        )
        val loggedTx = Transaction(
            id = 3L,
            amountMinor = 350L,
            type = TxType.EXPENSE,
            categoryId = 1L,
            epochDay = 20000L,
            note = "Starbucks",
            createdAt = baseTime - 30 * 60 * 1000L,
        )
        val result = EnhancedDuplicateDetector.evaluate(
            candidate = candidate,
            existingTransactions = listOf(loggedTx),
            pendingSuggestions = emptyList(),
        )
        // Must NOT be dropped as duplicate!
        assertEquals(DuplicateEvaluation.Unique, result)
    }

    @Test
    fun sameAmountWithGenericOrMissingMerchantIsAmbiguousMatch() {
        // Same amount within window, but merchant is generic like "Payment" or blank
        val candidate = TransactionCandidate(
            amountMinor = 1200L,
            merchant = "",
            timestamp = baseTime,
        )
        val loggedTx = Transaction(
            id = 4L,
            amountMinor = 1200L,
            type = TxType.EXPENSE,
            categoryId = 1L,
            epochDay = 20000L,
            note = "Bank payment",
            createdAt = baseTime - 10 * 60 * 1000L,
        )
        val result = EnhancedDuplicateDetector.evaluate(
            candidate = candidate,
            existingTransactions = listOf(loggedTx),
            pendingSuggestions = emptyList(),
        )
        // Must be AmbiguousMatch so it is kept in review queue instead of dropped or auto-saved
        assertTrue(result is DuplicateEvaluation.AmbiguousMatch)
    }

    @Test
    fun outsideTwoHourWindowIsUnique() {
        val candidate = TransactionCandidate(
            amountMinor = 2500L,
            merchant = "Starbucks",
            timestamp = baseTime,
        )
        val loggedTx = Transaction(
            id = 5L,
            amountMinor = 2500L,
            type = TxType.EXPENSE,
            categoryId = 1L,
            epochDay = 20000L,
            note = "Starbucks",
            createdAt = baseTime - DuplicatePayments.WINDOW_MS - 1000L, // Over 2 hours ago
        )
        val result = EnhancedDuplicateDetector.evaluate(
            candidate = candidate,
            existingTransactions = listOf(loggedTx),
            pendingSuggestions = emptyList(),
        )
        assertEquals(DuplicateEvaluation.Unique, result)
    }

    @Test
    fun pendingSuggestionMatchIsConfirmedDuplicate() {
        val candidate = TransactionCandidate(
            amountMinor = 4500L,
            merchant = "Foodmandu",
            timestamp = baseTime,
        )
        val pendingSuggestion = SmsSuggestion(
            id = 10L,
            amountMinor = 4500L,
            merchant = "Foodmandu",
            sender = "eSewa",
            epochDay = 20000L,
            body = "pending-fp",
            receivedAt = baseTime - 5 * 60 * 1000L,
            source = SmsSuggestion.SOURCE_NOTIFICATION,
        )
        val result = EnhancedDuplicateDetector.evaluate(
            candidate = candidate,
            existingTransactions = emptyList(),
            pendingSuggestions = listOf(pendingSuggestion),
        )
        assertTrue(result is DuplicateEvaluation.ConfirmedDuplicate)
    }
}
