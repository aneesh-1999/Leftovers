package com.leftovers.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class RemittanceCalculationsTest {

    @Test
    fun expectedReceiveAmount_separateFee_convertsFullPrincipal() {
        // 200 EUR at 147 NPR/EUR = 29,400 NPR
        // 200 EUR = 20,000 cents; 29,400 NPR = 2,940,000 paisa
        val expectedPaisa = RemittanceCalculations.calculateExpectedReceiveAmount(
            sendAmountMinor = 20000L,
            sendCurrency = "EUR",
            feeAmountMinor = 500L,
            feeCurrency = "EUR",
            feeMethod = FeeMethod.SEPARATE,
            exchangeRate = 147.0,
        )
        assertEquals(2940000L, expectedPaisa)
    }

    @Test
    fun expectedReceiveAmount_deductedFee_convertsNetPrincipal() {
        // 200 EUR with 5 EUR fee deducted = 195 EUR exchanged
        // 195 EUR at 147 NPR/EUR = 28,665 NPR
        val expectedPaisa = RemittanceCalculations.calculateExpectedReceiveAmount(
            sendAmountMinor = 20000L,
            sendCurrency = "EUR",
            feeAmountMinor = 500L,
            feeCurrency = "EUR",
            feeMethod = FeeMethod.DEDUCTED_FROM_PRINCIPAL,
            exchangeRate = 147.0,
        )
        assertEquals(2866500L, expectedPaisa)
    }

    @Test
    fun expectedReceiveAmount_deductedFee_differentCurrencyDoesNotDeductDirectly() {
        // Fee in USD, cannot subtract from EUR principal directly without user conversion
        val expectedPaisa = RemittanceCalculations.calculateExpectedReceiveAmount(
            sendAmountMinor = 20000L,
            sendCurrency = "EUR",
            feeAmountMinor = 500L,
            feeCurrency = "USD",
            feeMethod = FeeMethod.DEDUCTED_FROM_PRINCIPAL,
            exchangeRate = 147.0,
        )
        assertEquals(2940000L, expectedPaisa)
    }

    @Test
    fun totalCostToSender_separateFee_addsFeeToPrincipal() {
        // 200 EUR + 5 EUR fee = 205 EUR
        val cost = RemittanceCalculations.calculateTotalCostMinor(
            sendAmountMinor = 20000L,
            feeAmountMinor = 500L,
            feeMethod = FeeMethod.SEPARATE,
            feeCurrency = "EUR",
            sendCurrency = "EUR",
        )
        assertEquals(20500L, cost)
    }

    @Test
    fun totalCostToSender_deductedFee_doesNotAddFeeAgain() {
        // 200 EUR principal with fee deducted from it -> total cost is 200 EUR
        val cost = RemittanceCalculations.calculateTotalCostMinor(
            sendAmountMinor = 20000L,
            feeAmountMinor = 500L,
            feeMethod = FeeMethod.DEDUCTED_FROM_PRINCIPAL,
            feeCurrency = "EUR",
            sendCurrency = "EUR",
        )
        assertEquals(20000L, cost)
    }

    @Test
    fun totalCostToSender_differentFeeCurrency_doesNotSumMismatchingCurrencies() {
        val cost = RemittanceCalculations.calculateTotalCostMinor(
            sendAmountMinor = 20000L,
            feeAmountMinor = 500L,
            feeMethod = FeeMethod.SEPARATE,
            feeCurrency = "USD",
            sendCurrency = "EUR",
        )
        assertEquals(20000L, cost)
    }

    @Test
    fun receivingDifference_calculatesCorrectly() {
        // Expected: 29,400 NPR, Actual: 29,500 NPR (+100 NPR)
        val diffPositive = RemittanceCalculations.calculateReceivingDifference(
            expectedReceiveAmountMinor = 2940000L,
            actualReceiveAmountMinor = 2950000L,
        )
        assertEquals(10000L, diffPositive)

        // Expected: 29,400 NPR, Actual: 29,000 NPR (-400 NPR)
        val diffNegative = RemittanceCalculations.calculateReceivingDifference(
            expectedReceiveAmountMinor = 2940000L,
            actualReceiveAmountMinor = 2900000L,
        )
        assertEquals(-40000L, diffNegative)

        // Actual not recorded
        val diffNull = RemittanceCalculations.calculateReceivingDifference(
            expectedReceiveAmountMinor = 2940000L,
            actualReceiveAmountMinor = null,
        )
        assertNull(diffNull)
    }

    @Test
    fun effectiveExchangeRate_calculatesAccurately() {
        // 200 EUR principal -> 29,400 NPR actual = 147.0
        val rate = RemittanceCalculations.calculateEffectiveExchangeRate(
            sendAmountMinor = 20000L,
            actualReceiveAmountMinor = 2940000L,
        )
        assertEquals(147.0, rate?.toDouble() ?: 0.0, 0.001)

        // 200 EUR principal -> 28,665 NPR actual (e.g. fee deducted) = 143.325
        val rateDeducted = RemittanceCalculations.calculateEffectiveExchangeRate(
            sendAmountMinor = 20000L,
            actualReceiveAmountMinor = 2866500L,
        )
        assertEquals(143.325, rateDeducted?.toDouble() ?: 0.0, 0.001)
    }

    @Test
    fun weightedAverageExchangeRate_completedTransfersOnly() {
        val transfer1 = createTestRemittance(
            id = 1L,
            sendAmountMinor = 10000L, // 100 EUR
            actualReceiveMinor = 1450000L, // 14,500 NPR (rate 145)
            status = RemittanceStatus.RECEIVED,
        )
        val transfer2 = createTestRemittance(
            id = 2L,
            sendAmountMinor = 30000L, // 300 EUR
            actualReceiveMinor = 4500000L, // 45,000 NPR (rate 150)
            status = RemittanceStatus.RECEIVED,
        )
        val transferPending = createTestRemittance(
            id = 3L,
            sendAmountMinor = 20000L, // 200 EUR
            actualReceiveMinor = null,
            status = RemittanceStatus.SENT,
        )
        val transferFailed = createTestRemittance(
            id = 4L,
            sendAmountMinor = 50000L, // 500 EUR
            actualReceiveMinor = null,
            status = RemittanceStatus.FAILED,
        )

        // Total completed EUR: 400 EUR. Total completed NPR: 59,500 NPR.
        // Weighted rate: 59,500 / 400 = 148.75 NPR/EUR
        val weighted = RemittanceCalculations.calculateWeightedAverageExchangeRate(
            listOf(transfer1, transfer2, transferPending, transferFailed),
        )
        assertEquals(148.75, weighted?.toDouble() ?: 0.0, 0.01)
    }

    @Test
    fun summarize_segregatesStatusesAndCurrenciesCorrectly() {
        val completed1 = createTestRemittance(
            id = 10L,
            sendAmountMinor = 20000L, // 200 EUR
            feeAmountMinor = 500L, // 5 EUR
            feeMethod = FeeMethod.SEPARATE,
            actualReceiveMinor = 2940000L, // 29,400 NPR
            status = RemittanceStatus.RECEIVED,
        )
        val completed2 = createTestRemittance(
            id = 11L,
            sendAmountMinor = 10000L, // 100 EUR
            feeAmountMinor = 200L, // 2 EUR
            feeMethod = FeeMethod.DEDUCTED_FROM_PRINCIPAL,
            actualReceiveMinor = 1440600L, // 14,406 NPR
            status = RemittanceStatus.RECEIVED,
        )
        val pending = createTestRemittance(
            id = 12L,
            sendAmountMinor = 15000L, // 150 EUR
            feeAmountMinor = 300L, // 3 EUR
            feeMethod = FeeMethod.SEPARATE,
            actualReceiveMinor = null,
            status = RemittanceStatus.PROCESSING,
        )
        val failed = createTestRemittance(
            id = 13L,
            sendAmountMinor = 5000L, // 50 EUR
            feeAmountMinor = 100L,
            actualReceiveMinor = null,
            status = RemittanceStatus.FAILED,
        )
        val refunded = createTestRemittance(
            id = 14L,
            sendAmountMinor = 8000L, // 80 EUR
            feeAmountMinor = 150L,
            actualReceiveMinor = null,
            status = RemittanceStatus.REFUNDED,
        )

        val summary = RemittanceCalculations.summarize(
            listOf(completed1, completed2, pending, failed, refunded),
        )

        // Principal sent by active transfers (excludes failed/refunded for sent total):
        // 200 + 100 + 150 = 450 EUR = 45,000 minor
        assertEquals(45000L, summary.totalPrincipalSentEurMinor)

        // Fees for active transfers: 5 + 2 + 3 = 10 EUR = 1,000 minor
        assertEquals(1000L, summary.totalFeesEurMinor)

        // Total cost:
        // completed1: 200 + 5 = 205 EUR
        // completed2: 100 EUR (fee deducted)
        // pending: 150 + 3 = 153 EUR
        // Total cost = 205 + 100 + 153 = 458 EUR = 45,800 minor
        assertEquals(45800L, summary.totalCostEurMinor)

        // NPR actually received (from completed transfers only):
        // 29,400 + 14,406 = 43,806 NPR = 4,380,600 minor
        assertEquals(4380600L, summary.totalNprActuallyReceivedMinor)

        // Counts
        assertEquals(2, summary.completedCount)
        assertEquals(1, summary.pendingCount)
        assertEquals(2, summary.failedOrRefundedCount)
        assertEquals(5, summary.totalTransfers)
    }

    @Test
    fun weightedAverageExchangeRate_fallbackToExpectedWhenActualNull() {
        val transfer1 = createTestRemittance(
            id = 1L,
            sendAmountMinor = 10000L, // 100 EUR
            actualReceiveMinor = null, // null, expected is 14,700 NPR
            status = RemittanceStatus.RECEIVED,
            rate = 147.0,
        )
        val transfer2 = createTestRemittance(
            id = 2L,
            sendAmountMinor = 10000L, // 100 EUR
            actualReceiveMinor = 1490000L, // 14,900 NPR
            status = RemittanceStatus.RECEIVED,
            rate = 147.0,
        )

        // Total completed EUR: 200 EUR. Total completed NPR: 14,700 + 14,900 = 29,600 NPR.
        // Weighted rate = 29,600 / 200 = 148.0 NPR/EUR
        val weighted = RemittanceCalculations.calculateWeightedAverageExchangeRate(listOf(transfer1, transfer2))
        assertEquals(148.0, weighted?.toDouble() ?: 0.0, 0.01)
    }

    @Test
    fun summarize_withDifferentFeeCurrencyOrZeroFee_includesTotalCost() {
        val transferNoFee = createTestRemittance(
            id = 20L,
            sendAmountMinor = 20000L, // 200 EUR
            feeAmountMinor = 0L,
            feeCurrency = "USD",
            actualReceiveMinor = 2940000L,
            status = RemittanceStatus.RECEIVED,
        )
        val summary = RemittanceCalculations.summarize(listOf(transferNoFee))
        assertEquals(20000L, summary.totalPrincipalSentEurMinor)
        assertEquals(20000L, summary.totalCostEurMinor)
        assertEquals(0L, summary.totalFeesEurMinor)
    }

    private fun createTestRemittance(
        id: Long,
        sendAmountMinor: Long,
        feeAmountMinor: Long = 0L,
        feeCurrency: String = "EUR",
        feeMethod: FeeMethod = FeeMethod.SEPARATE,
        actualReceiveMinor: Long?,
        status: RemittanceStatus,
        sendCurrency: String = "EUR",
        receiveCurrency: String = "NPR",
        rate: Double = 147.0,
    ): Remittance {
        val expectedReceiveMinor = RemittanceCalculations.calculateExpectedReceiveAmount(
            sendAmountMinor = sendAmountMinor,
            sendCurrency = sendCurrency,
            feeAmountMinor = feeAmountMinor,
            feeCurrency = feeCurrency,
            feeMethod = feeMethod,
            exchangeRate = rate,
        )
        return Remittance(
            id = id,
            recipientName = "Mother",
            recipientCountry = "Nepal",
            transferDateEpochDay = LocalDate.of(2026, 10, 1).toEpochDay(),
            provider = "Revolut",
            sendAmountMinor = sendAmountMinor,
            sendCurrency = sendCurrency,
            feeAmountMinor = feeAmountMinor,
            feeCurrency = feeCurrency,
            feeMethod = feeMethod,
            exchangeRate = rate,
            expectedReceiveAmountMinor = expectedReceiveMinor,
            actualReceiveAmountMinor = actualReceiveMinor,
            receiveCurrency = receiveCurrency,
            receivedDateEpochDay = if (status == RemittanceStatus.RECEIVED) LocalDate.of(2026, 10, 2).toEpochDay() else null,
            status = status,
            recipientId = 1L,
        )
    }
}
