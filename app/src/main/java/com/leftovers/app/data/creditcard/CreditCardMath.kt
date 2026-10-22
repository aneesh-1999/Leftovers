package com.leftovers.app.data.creditcard

import java.time.LocalDate
import java.time.YearMonth
import kotlin.math.max

/**
 * Pure accounting math and business logic for credit card balances, statements,
 * available credit, statuses, and cycle estimates.
 */
object CreditCardMath {

    /**
     * Outstanding balance calculation:
     * - Liability increases (what you owe): PURCHASE, FEE, INTEREST, PAYMENT_REVERSAL
     * - Liability decreases (settlements/credits): PAYMENT, REFUND
     */
    fun calculateOutstandingBalance(transactions: List<CreditCardTransaction>): Long {
        var balance = 0L
        for (tx in transactions) {
            if (tx.type.isLiabilityIncrease) {
                balance += tx.amountMinor
            } else {
                balance -= tx.amountMinor
            }
        }
        return balance
    }

    /**
     * Computes available credit:
     * Available Credit = Credit Limit - Current Outstanding Balance
     * If no credit limit is set (0), returns null.
     */
    fun calculateAvailableCredit(creditLimitMinor: Long, outstandingBalanceMinor: Long): Long? {
        if (creditLimitMinor <= 0L) return null
        return creditLimitMinor - outstandingBalanceMinor
    }

    /**
     * Evaluates a statement against recorded payment allocations.
     * Partial payments satisfy minimum payment first, but DO NOT mark statement as PAID
     * until the entire statement balance is settled.
     */
    fun evaluateStatement(
        statement: CreditCardStatement,
        allocations: List<StatementPaymentAllocation>,
        todayEpochDay: Long = LocalDate.now().toEpochDay(),
    ): StatementWithAllocations {
        val totalPaid = allocations.filter { it.statementId == statement.id }.sumOf { it.amountMinor }
        val remainingBalance = max(0L, statement.statementBalanceMinor - totalPaid)
        val remainingMinDue = max(0L, statement.minPaymentDueMinor - totalPaid)
        val isMinSatisfied = remainingMinDue == 0L
        val daysUntilDue = statement.dueEpochDay - todayEpochDay

        val status = when {
            remainingBalance == 0L -> StatementStatus.PAID
            todayEpochDay > statement.dueEpochDay -> StatementStatus.OVERDUE
            totalPaid > 0L -> StatementStatus.PARTIALLY_PAID
            else -> StatementStatus.UNPAID
        }

        return StatementWithAllocations(
            statement = statement,
            totalPaidMinor = totalPaid,
            remainingBalanceMinor = remainingBalance,
            remainingMinDueMinor = remainingMinDue,
            isMinPaymentSatisfied = isMinSatisfied,
            status = status,
            daysUntilDue = daysUntilDue,
        )
    }

    /**
     * Determines card status according to its active statements and balances.
     * Follows strict rule: Never mark a card as paid merely because of a partial payment.
     */
    fun determineCardStatus(
        currentBalanceMinor: Long,
        evaluatedStatements: List<StatementWithAllocations>,
        todayEpochDay: Long = LocalDate.now().toEpochDay(),
    ): CreditCardStatus {
        if (evaluatedStatements.isEmpty()) {
            return if (currentBalanceMinor <= 0L) CreditCardStatus.PAID else CreditCardStatus.NO_STATEMENT
        }

        val unpaidStatements = evaluatedStatements.filter { it.remainingBalanceMinor > 0L }
        if (unpaidStatements.isEmpty()) {
            return CreditCardStatus.PAID
        }

        // Check for any overdue statements
        if (unpaidStatements.any { it.status == StatementStatus.OVERDUE }) {
            return CreditCardStatus.OVERDUE
        }

        // Check for any statement due within 3 days (due soon)
        if (unpaidStatements.any { it.statement.dueEpochDay - todayEpochDay in 0..3 }) {
            return CreditCardStatus.PAYMENT_DUE_SOON
        }

        return CreditCardStatus.PAYMENT_UPCOMING
    }

    /**
     * Calculates estimated next statement closing date and due date based on user configuration.
     * All results are estimates and clearly labeled as such.
     */
    fun estimateBillingCycleDates(
        closingDay: Int,
        dueDay: Int,
        daysAfterClosing: Int,
        today: LocalDate = LocalDate.now(),
    ): Pair<Long?, Long?> {
        if (closingDay !in 1..31) return Pair(null, null)

        val thisMonthClosing = clampDate(today.year, today.monthValue, closingDay)
        val nextClosing = if (!today.isAfter(thisMonthClosing)) {
            thisMonthClosing
        } else {
            val nextMonth = today.plusMonths(1)
            clampDate(nextMonth.year, nextMonth.monthValue, closingDay)
        }

        val nextDue: LocalDate? = when {
            daysAfterClosing > 0 -> nextClosing.plusDays(daysAfterClosing.toLong())
            dueDay in 1..31 -> {
                var candidate = clampDate(nextClosing.year, nextClosing.monthValue, dueDay)
                if (!candidate.isAfter(nextClosing)) {
                    val m = nextClosing.plusMonths(1)
                    candidate = clampDate(m.year, m.monthValue, dueDay)
                }
                candidate
            }
            else -> null
        }

        return Pair(nextClosing.toEpochDay(), nextDue?.toEpochDay())
    }

    private fun clampDate(year: Int, month: Int, day: Int): LocalDate {
        val ym = YearMonth.of(year, month)
        val clampedDay = day.coerceIn(1, ym.lengthOfMonth())
        return ym.atDay(clampedDay)
    }

    /**
     * Optional estimate of minimum payment due based on card settings:
     * e.g., max of (percentage * balance) and flat minimum amount.
     */
    fun estimateMinimumPayment(
        balanceMinor: Long,
        minPercentage: Double,
        minFlatMinor: Long,
    ): Long {
        if (balanceMinor <= 0L) return 0L
        val percentPart = if (minPercentage > 0.0) {
            (balanceMinor * (minPercentage / 100.0)).toLong()
        } else {
            0L
        }
        val estimated = max(percentPart, minFlatMinor)
        return estimated.coerceAtMost(balanceMinor)
    }

    /**
     * Computes multi-currency summaries.
     * CRITICAL RULE: NEVER combine balances from different currencies into one monetary total.
     */
    fun groupSummariesByCurrency(cards: List<CreditCardWithSummary>): List<CreditCardCurrencySummary> {
        val grouped = cards.groupBy { it.card.currency.uppercase().trim().ifEmpty { "EUR" } }

        return grouped.map { (curr, cardList) ->
            var totalOutstanding = 0L
            var totalStatementBal = 0L
            var totalRemainingStmt = 0L
            var totalMinDue = 0L
            var totalRemainingMin = 0L
            var totalLimit = 0L
            var upcomingCount = 0
            var overdueCount = 0

            for (c in cardList) {
                totalOutstanding += c.currentBalanceMinor
                totalLimit += c.card.creditLimitMinor
                val stmt = c.latestStatement
                if (stmt != null) {
                    totalStatementBal += stmt.statement.statementBalanceMinor
                    totalRemainingStmt += stmt.remainingBalanceMinor
                    totalMinDue += stmt.statement.minPaymentDueMinor
                    totalRemainingMin += stmt.remainingMinDueMinor
                }

                if (c.status == CreditCardStatus.OVERDUE) {
                    overdueCount++
                } else if (c.status == CreditCardStatus.PAYMENT_DUE_SOON || c.status == CreditCardStatus.PAYMENT_UPCOMING) {
                    upcomingCount++
                }
            }

            CreditCardCurrencySummary(
                currency = curr,
                totalOutstandingBalanceMinor = totalOutstanding,
                totalStatementBalanceMinor = totalStatementBal,
                totalRemainingStatementMinor = totalRemainingStmt,
                totalMinPaymentDueMinor = totalMinDue,
                totalRemainingMinPaymentMinor = totalRemainingMin,
                totalCreditLimitMinor = totalLimit,
                cardsWithUpcomingPayments = upcomingCount,
                overduePaymentsCount = overdueCount,
                cardCount = cardList.size,
            )
        }.sortedBy { it.currency }
    }
}
