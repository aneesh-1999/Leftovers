package com.leftovers.app.data

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.YearMonth

/**
 * Pure, decimal-safe domain calculations for remittances.
 * Keeps currencies distinct (never combines EUR + NPR or assumes unrecorded conversions).
 */
object RemittanceCalculations {

    /**
     * Calculates the expected receiving amount in receiving currency minor units.
     *
     * If fee is deducted from principal (and fee is in same sending currency):
     *   netPrincipal = max(0, sendAmountMinor - feeAmountMinor)
     *   expected = netPrincipal * exchangeRate
     * Otherwise:
     *   expected = sendAmountMinor * exchangeRate
     */
    fun calculateExpectedReceiveAmount(
        sendAmountMinor: Long,
        feeAmountMinor: Long,
        sendCurrency: String,
        feeCurrency: String,
        feeMethod: FeeMethod,
        exchangeRate: Double,
    ): Long {
        if (sendAmountMinor <= 0L || exchangeRate <= 0.0) return 0L
        val sameFeeCurrency = sendCurrency.equals(feeCurrency, ignoreCase = true)
        val netPrincipalMinor = if (feeMethod == FeeMethod.DEDUCTED_FROM_PRINCIPAL && sameFeeCurrency) {
            (sendAmountMinor - feeAmountMinor).coerceAtLeast(0L)
        } else {
            sendAmountMinor
        }
        val rateBd = BigDecimal.valueOf(exchangeRate)
        val principalBd = BigDecimal.valueOf(netPrincipalMinor)
        return principalBd.multiply(rateBd).setScale(0, RoundingMode.HALF_UP).toLong()
    }

    /**
     * Total cost charged to the sender in the sending currency, when fees can be compared.
     * When fee is deducted from principal, total cost is just the sendAmountMinor (since fee was part of it).
     * When fee is separate and in the same currency: sendAmountMinor + feeAmountMinor.
     * If fee is in a different currency, returns sendAmountMinor (fee is tracked separately in its own currency).
     */
    fun calculateTotalCostMinor(
        sendAmountMinor: Long,
        feeAmountMinor: Long,
        sendCurrency: String,
        feeCurrency: String,
        feeMethod: FeeMethod,
    ): Long {
        if (feeMethod == FeeMethod.DEDUCTED_FROM_PRINCIPAL) return sendAmountMinor
        val sameFeeCurrency = sendCurrency.equals(feeCurrency, ignoreCase = true)
        return if (sameFeeCurrency) sendAmountMinor + feeAmountMinor else sendAmountMinor
    }

    /**
     * Difference between actual amount received and expected amount:
     * Positive = received more than expected.
     * Negative = received less than expected.
     * Null if actual received amount is not recorded.
     */
    fun calculateReceivingDifference(expectedReceiveAmountMinor: Long, actualReceiveAmountMinor: Long?): Long? {
        if (actualReceiveAmountMinor == null) return null
        return actualReceiveAmountMinor - expectedReceiveAmountMinor
    }

    /**
     * Effective exchange rate based on the principal sent:
     * Effective Rate = Actual Receiving Amount / Principal Sent.
     * Null if transfer is not yet received or principal <= 0.
     */
    fun calculateEffectiveExchangeRate(sendAmountMinor: Long, actualReceiveAmountMinor: Long?): Double? {
        if (actualReceiveAmountMinor == null || sendAmountMinor <= 0L) return null
        val actualBd = BigDecimal.valueOf(actualReceiveAmountMinor)
        val sendBd = BigDecimal.valueOf(sendAmountMinor)
        return actualBd.divide(sendBd, 4, RoundingMode.HALF_UP).toDouble()
    }

    /**
     * Weighted average exchange rate across completed transfers with the same sending and receiving currencies:
     * Weighted Rate = Sum(Actual Receiving Minor) / Sum(Send Amount Minor).
     * Only counts transfers with status == RECEIVED and valid amounts.
     */
    fun calculateWeightedAverageExchangeRate(
        completedRemittances: List<Remittance>,
        sendCurrency: String = "EUR",
        receiveCurrency: String = "NPR",
    ): Double? {
        val valid = completedRemittances.filter {
            it.status == RemittanceStatus.RECEIVED &&
                it.sendCurrency.equals(sendCurrency, ignoreCase = true) &&
                it.receiveCurrency.equals(receiveCurrency, ignoreCase = true) &&
                it.sendAmountMinor > 0L &&
                ((it.actualReceiveAmountMinor != null && it.actualReceiveAmountMinor > 0L) ||
                 (it.expectedReceiveAmountMinor > 0L) ||
                 (it.exchangeRate > 0.0))
        }
        if (valid.isEmpty()) return null
        val totalSend = valid.sumOf { it.sendAmountMinor }
        val totalReceived = valid.sumOf {
            it.actualReceiveAmountMinor
                ?: it.expectedReceiveAmountMinor.takeIf { exp -> exp > 0L }
                ?: (BigDecimal.valueOf(it.sendAmountMinor).multiply(BigDecimal.valueOf(it.exchangeRate)).toLong())
        }
        if (totalSend <= 0L || totalReceived <= 0L) return null
        return BigDecimal.valueOf(totalReceived)
            .divide(BigDecimal.valueOf(totalSend), 4, RoundingMode.HALF_UP)
            .toDouble()
    }

    /**
     * Generates a monthly summary breakdown.
     */
    fun summarize(remittances: List<Remittance>, sendCurrency: String = "EUR", receiveCurrency: String = "NPR"): RemittanceSummary {
        val relevant = remittances.filter {
            it.sendCurrency.equals(sendCurrency, ignoreCase = true)
        }

        var totalPrincipalSentMinor = 0L
        var totalFeesSentCurrencyMinor = 0L
        var totalCostSentCurrencyMinor = 0L
        var otherCurrencyFees = mutableMapOf<String, Long>()

        var totalActualReceivedMinor = 0L
        var totalExpectedPendingMinor = 0L

        var completedCount = 0
        var pendingCount = 0
        var plannedCount = 0
        var failedOrRefundedCount = 0

        relevant.forEach { r ->
            when (r.status) {
                RemittanceStatus.RECEIVED -> {
                    completedCount++
                    totalPrincipalSentMinor += r.sendAmountMinor
                    totalCostSentCurrencyMinor += calculateTotalCostMinor(r.sendAmountMinor, r.feeAmountMinor, r.sendCurrency, r.feeCurrency, r.feeMethod)
                    if (r.feeCurrency.equals(sendCurrency, ignoreCase = true)) {
                        totalFeesSentCurrencyMinor += r.feeAmountMinor
                    } else if (r.feeAmountMinor > 0L) {
                        otherCurrencyFees[r.feeCurrency] = (otherCurrencyFees[r.feeCurrency] ?: 0L) + r.feeAmountMinor
                    }
                    if (r.receiveCurrency.equals(receiveCurrency, ignoreCase = true)) {
                        totalActualReceivedMinor += r.actualReceiveAmountMinor ?: r.expectedReceiveAmountMinor
                    }
                }
                RemittanceStatus.SENT, RemittanceStatus.PROCESSING -> {
                    pendingCount++
                    totalPrincipalSentMinor += r.sendAmountMinor
                    totalCostSentCurrencyMinor += calculateTotalCostMinor(r.sendAmountMinor, r.feeAmountMinor, r.sendCurrency, r.feeCurrency, r.feeMethod)
                    if (r.feeCurrency.equals(sendCurrency, ignoreCase = true)) {
                        totalFeesSentCurrencyMinor += r.feeAmountMinor
                    } else if (r.feeAmountMinor > 0L) {
                        otherCurrencyFees[r.feeCurrency] = (otherCurrencyFees[r.feeCurrency] ?: 0L) + r.feeAmountMinor
                    }
                    if (r.receiveCurrency.equals(receiveCurrency, ignoreCase = true)) {
                        totalExpectedPendingMinor += r.expectedReceiveAmountMinor
                    }
                }
                RemittanceStatus.PLANNED -> {
                    plannedCount++
                }
                RemittanceStatus.FAILED, RemittanceStatus.CANCELLED, RemittanceStatus.REFUNDED -> {
                    failedOrRefundedCount++
                }
            }
        }

        val weightedRate = calculateWeightedAverageExchangeRate(relevant, sendCurrency, receiveCurrency)

        return RemittanceSummary(
            sendCurrency = sendCurrency,
            receiveCurrency = receiveCurrency,
            totalPrincipalSentMinor = totalPrincipalSentMinor,
            totalFeesSentCurrencyMinor = totalFeesSentCurrencyMinor,
            totalCostSentCurrencyMinor = totalCostSentCurrencyMinor,
            totalActualReceivedMinor = totalActualReceivedMinor,
            totalExpectedPendingMinor = totalExpectedPendingMinor,
            completedCount = completedCount,
            pendingCount = pendingCount,
            plannedCount = plannedCount,
            failedOrRefundedCount = failedOrRefundedCount,
            weightedAverageRate = weightedRate,
            otherCurrencyFees = otherCurrencyFees,
        )
    }
}

data class RemittanceSummary(
    val sendCurrency: String = "EUR",
    val receiveCurrency: String = "NPR",
    /** Total EUR principal sent (completed + pending). */
    val totalPrincipalSentMinor: Long = 0L,
    /** Total EUR transfer fees. */
    val totalFeesSentCurrencyMinor: Long = 0L,
    /** Total EUR cost to sender (principal + separate fees). */
    val totalCostSentCurrencyMinor: Long = 0L,
    /** Total confirmed NPR received (completed transfers only). */
    val totalActualReceivedMinor: Long = 0L,
    /** Total expected NPR for pending/processing transfers. */
    val totalExpectedPendingMinor: Long = 0L,
    val completedCount: Int = 0,
    val pendingCount: Int = 0,
    val plannedCount: Int = 0,
    val failedOrRefundedCount: Int = 0,
    val weightedAverageRate: Double? = null,
    val otherCurrencyFees: Map<String, Long> = emptyMap(),
) {
    val totalTransfers: Int get() = completedCount + pendingCount + plannedCount + failedOrRefundedCount
    val totalPrincipalSentEurMinor: Long get() = totalPrincipalSentMinor
    val totalFeesEurMinor: Long get() = totalFeesSentCurrencyMinor
    val totalCostEurMinor: Long get() = totalCostSentCurrencyMinor
    val totalNprActuallyReceivedMinor: Long get() = totalActualReceivedMinor
    val weightedAverageExchangeRate: Double? get() = weightedAverageRate
}

