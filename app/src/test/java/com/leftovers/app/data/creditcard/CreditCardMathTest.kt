package com.leftovers.app.data.creditcard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class CreditCardMathTest {

    @Test
    fun testOutstandingBalanceCalculation() {
        val txs = listOf(
            CreditCardTransaction(
                cardId = 1,
                type = CreditCardTxType.PURCHASE,
                dateEpochDay = 20000,
                amountMinor = 15000L, // +150.00
                description = "Groceries",
            ),
            CreditCardTransaction(
                cardId = 1,
                type = CreditCardTxType.PURCHASE,
                dateEpochDay = 20001,
                amountMinor = 5000L, // +50.00
                description = "Fuel",
            ),
            CreditCardTransaction(
                cardId = 1,
                type = CreditCardTxType.FEE,
                dateEpochDay = 20002,
                amountMinor = 1200L, // +12.00
                description = "Annual Fee",
            ),
            CreditCardTransaction(
                cardId = 1,
                type = CreditCardTxType.INTEREST,
                dateEpochDay = 20003,
                amountMinor = 800L, // +8.00
                description = "Monthly Interest",
            ),
            CreditCardTransaction(
                cardId = 1,
                type = CreditCardTxType.REFUND,
                dateEpochDay = 20004,
                amountMinor = 2000L, // -20.00
                description = "Item return",
            ),
            CreditCardTransaction(
                cardId = 1,
                type = CreditCardTxType.PAYMENT,
                dateEpochDay = 20005,
                amountMinor = 10000L, // -100.00
                description = "Bank transfer repayment",
            ),
        )

        // 15000 + 5000 + 1200 + 800 - 2000 - 10000 = 10000
        val balance = CreditCardMath.calculateOutstandingBalance(txs)
        assertEquals(10000L, balance)
    }

    @Test
    fun testAvailableCreditCalculation() {
        // Credit limit €2,000, balance €600 -> available €1,400
        val available = CreditCardMath.calculateAvailableCredit(200000L, 60000L)
        assertEquals(140000L, available)

        // No credit limit configured (0L) -> null
        val noLimit = CreditCardMath.calculateAvailableCredit(0L, 60000L)
        assertNull(noLimit)

        // Over-limit: credit limit €1,000, balance €1,100 -> available -€100
        val overLimit = CreditCardMath.calculateAvailableCredit(100000L, 110000L)
        assertEquals(-10000L, overLimit)
    }

    @Test
    fun testStatementEvaluationUnpaid() {
        val stmt = CreditCardStatement(
            id = 101,
            cardId = 1,
            closingEpochDay = 20000,
            dueEpochDay = 20025,
            statementBalanceMinor = 50000L, // €500.00
            minPaymentDueMinor = 2500L, // €25.00
        )
        val allocations = emptyList<StatementPaymentAllocation>()
        val evaluated = CreditCardMath.evaluateStatement(stmt, allocations, todayEpochDay = 20010)

        assertEquals(0L, evaluated.totalPaidMinor)
        assertEquals(50000L, evaluated.remainingBalanceMinor)
        assertEquals(2500L, evaluated.remainingMinDueMinor)
        assertFalse(evaluated.isMinPaymentSatisfied)
        assertEquals(StatementStatus.UNPAID, evaluated.status)
        assertEquals(15L, evaluated.daysUntilDue)
    }

    @Test
    fun testStatementEvaluationPartialPaymentSatisfiesMinPaymentButNotPaid() {
        val stmt = CreditCardStatement(
            id = 101,
            cardId = 1,
            closingEpochDay = 20000,
            dueEpochDay = 20025,
            statementBalanceMinor = 50000L, // €500.00
            minPaymentDueMinor = 2500L, // €25.00
        )
        // User pays €50.00
        val allocations = listOf(
            StatementPaymentAllocation(
                statementId = 101,
                transactionId = 55,
                amountMinor = 5000L,
            ),
        )
        val evaluated = CreditCardMath.evaluateStatement(stmt, allocations, todayEpochDay = 20015)

        assertEquals(5000L, evaluated.totalPaidMinor)
        assertEquals(45000L, evaluated.remainingBalanceMinor) // Still owes €450.00
        assertEquals(0L, evaluated.remainingMinDueMinor) // Min payment satisfied
        assertTrue(evaluated.isMinPaymentSatisfied)
        // Status must be PARTIALLY_PAID, NEVER PAID
        assertEquals(StatementStatus.PARTIALLY_PAID, evaluated.status)
    }

    @Test
    fun testStatementEvaluationFullPayment() {
        val stmt = CreditCardStatement(
            id = 101,
            cardId = 1,
            closingEpochDay = 20000,
            dueEpochDay = 20025,
            statementBalanceMinor = 50000L,
            minPaymentDueMinor = 2500L,
        )
        val allocations = listOf(
            StatementPaymentAllocation(
                statementId = 101,
                transactionId = 55,
                amountMinor = 50000L,
            ),
        )
        val evaluated = CreditCardMath.evaluateStatement(stmt, allocations, todayEpochDay = 20015)

        assertEquals(50000L, evaluated.totalPaidMinor)
        assertEquals(0L, evaluated.remainingBalanceMinor)
        assertEquals(0L, evaluated.remainingMinDueMinor)
        assertTrue(evaluated.isMinPaymentSatisfied)
        assertEquals(StatementStatus.PAID, evaluated.status)
    }

    @Test
    fun testStatementEvaluationOverdue() {
        val stmt = CreditCardStatement(
            id = 101,
            cardId = 1,
            closingEpochDay = 20000,
            dueEpochDay = 20025,
            statementBalanceMinor = 50000L,
            minPaymentDueMinor = 2500L,
        )
        val allocations = emptyList<StatementPaymentAllocation>()
        // Today is past the dueEpochDay (20026 > 20025)
        val evaluated = CreditCardMath.evaluateStatement(stmt, allocations, todayEpochDay = 20026)

        assertEquals(StatementStatus.OVERDUE, evaluated.status)
        assertEquals(-1L, evaluated.daysUntilDue)
    }

    @Test
    fun testCardStatusDetermination() {
        val stmt = CreditCardStatement(
            id = 1,
            cardId = 1,
            closingEpochDay = 20000,
            dueEpochDay = 20025,
            statementBalanceMinor = 30000L,
            minPaymentDueMinor = 1500L,
        )

        // 1. Partial payment: card is NOT paid!
        val partialAllocations = listOf(StatementPaymentAllocation(statementId = 1, transactionId = 1, amountMinor = 5000L))
        val evalPartial = CreditCardMath.evaluateStatement(stmt, partialAllocations, todayEpochDay = 20015)
        val statusPartial = CreditCardMath.determineCardStatus(25000L, listOf(evalPartial), todayEpochDay = 20015)
        assertEquals(CreditCardStatus.PAYMENT_UPCOMING, statusPartial)

        // 2. Due soon (within 3 days)
        val evalDueSoon = CreditCardMath.evaluateStatement(stmt, emptyList(), todayEpochDay = 20023)
        val statusDueSoon = CreditCardMath.determineCardStatus(30000L, listOf(evalDueSoon), todayEpochDay = 20023)
        assertEquals(CreditCardStatus.PAYMENT_DUE_SOON, statusDueSoon)

        // 3. Overdue
        val evalOverdue = CreditCardMath.evaluateStatement(stmt, emptyList(), todayEpochDay = 20030)
        val statusOverdue = CreditCardMath.determineCardStatus(30000L, listOf(evalOverdue), todayEpochDay = 20030)
        assertEquals(CreditCardStatus.OVERDUE, statusOverdue)

        // 4. Fully paid
        val fullAllocations = listOf(StatementPaymentAllocation(statementId = 1, transactionId = 1, amountMinor = 30000L))
        val evalPaid = CreditCardMath.evaluateStatement(stmt, fullAllocations, todayEpochDay = 20015)
        val statusPaid = CreditCardMath.determineCardStatus(0L, listOf(evalPaid), todayEpochDay = 20015)
        assertEquals(CreditCardStatus.PAID, statusPaid)

        // 5. No statement
        val statusNoStmt = CreditCardMath.determineCardStatus(10000L, emptyList(), todayEpochDay = 20015)
        assertEquals(CreditCardStatus.NO_STATEMENT, statusNoStmt)
    }

    @Test
    fun testCurrencySummariesNeverCombineCurrencies() {
        val cardEur = CreditCard(
            id = 1,
            nickname = "Euro Card",
            bankIssuer = "BNP",
            currency = "EUR",
            creditLimitMinor = 300000L,
        )
        val cardNpr = CreditCard(
            id = 2,
            nickname = "NPR Card",
            bankIssuer = "Nabil",
            currency = "NPR",
            creditLimitMinor = 10000000L,
        )
        val cardUsd = CreditCard(
            id = 3,
            nickname = "USD Card",
            bankIssuer = "Chase",
            currency = "USD",
            creditLimitMinor = 500000L,
        )

        val summaries = listOf(
            CreditCardWithSummary(
                card = cardEur,
                currentBalanceMinor = 50000L, // €500.00
                availableCreditMinor = 250000L,
                latestStatement = null,
                activeStatementCount = 0,
                status = CreditCardStatus.NO_STATEMENT,
                nextEstimatedClosingEpochDay = null,
                nextEstimatedDueEpochDay = null,
                currentCyclePurchasesMinor = 50000L,
            ),
            CreditCardWithSummary(
                card = cardNpr,
                currentBalanceMinor = 2000000L, // NPR 20,000.00
                availableCreditMinor = 8000000L,
                latestStatement = null,
                activeStatementCount = 0,
                status = CreditCardStatus.NO_STATEMENT,
                nextEstimatedClosingEpochDay = null,
                nextEstimatedDueEpochDay = null,
                currentCyclePurchasesMinor = 2000000L,
            ),
            CreditCardWithSummary(
                card = cardUsd,
                currentBalanceMinor = 120000L, // $1,200.00
                availableCreditMinor = 380000L,
                latestStatement = null,
                activeStatementCount = 0,
                status = CreditCardStatus.NO_STATEMENT,
                nextEstimatedClosingEpochDay = null,
                nextEstimatedDueEpochDay = null,
                currentCyclePurchasesMinor = 120000L,
            ),
        )

        val grouped = CreditCardMath.groupSummariesByCurrency(summaries)
        assertEquals(3, grouped.size)

        val eurSummary = grouped.first { it.currency == "EUR" }
        assertEquals(50000L, eurSummary.totalOutstandingBalanceMinor)
        assertEquals(300000L, eurSummary.totalCreditLimitMinor)

        val nprSummary = grouped.first { it.currency == "NPR" }
        assertEquals(2000000L, nprSummary.totalOutstandingBalanceMinor)
        assertEquals(10000000L, nprSummary.totalCreditLimitMinor)

        val usdSummary = grouped.first { it.currency == "USD" }
        assertEquals(120000L, usdSummary.totalOutstandingBalanceMinor)
        assertEquals(500000L, usdSummary.totalCreditLimitMinor)
    }

    @Test
    fun testBillingCycleDateEstimation() {
        val today = LocalDate.of(2026, 4, 10)
        // Closing day 15, due 20 days after closing
        val (closingEpoch, dueEpoch) = CreditCardMath.estimateBillingCycleDates(
            closingDay = 15,
            dueDay = 0,
            daysAfterClosing = 20,
            today = today,
        )

        val expectedClosing = LocalDate.of(2026, 4, 15).toEpochDay()
        val expectedDue = LocalDate.of(2026, 5, 5).toEpochDay()

        assertEquals(expectedClosing, closingEpoch)
        assertEquals(expectedDue, dueEpoch)
    }

    @Test
    fun testMinPaymentEstimation() {
        // 5% of €1,000 is €50. Flat min is €25 -> should be €50
        val est1 = CreditCardMath.estimateMinimumPayment(100000L, 5.0, 2500L)
        assertEquals(5000L, est1)

        // 5% of €200 is €10. Flat min is €25 -> should be €25
        val est2 = CreditCardMath.estimateMinimumPayment(20000L, 5.0, 2500L)
        assertEquals(2500L, est2)

        // Balance €15 is less than flat min €25 -> capped at balance (€15)
        val est3 = CreditCardMath.estimateMinimumPayment(1500L, 5.0, 2500L)
        assertEquals(1500L, est3)
    }

    @Test
    fun testPaymentReversalIncreasesLiability() {
        val txs = listOf(
            CreditCardTransaction(cardId = 1, type = CreditCardTxType.PURCHASE, dateEpochDay = 1, amountMinor = 10000L, description = "Store"),
            CreditCardTransaction(cardId = 1, type = CreditCardTxType.PAYMENT, dateEpochDay = 2, amountMinor = 10000L, description = "Cheque"),
            CreditCardTransaction(cardId = 1, type = CreditCardTxType.PAYMENT_REVERSAL, dateEpochDay = 3, amountMinor = 10000L, description = "Bounced cheque"),
        )
        val balance = CreditCardMath.calculateOutstandingBalance(txs)
        assertEquals(10000L, balance)
    }

    @Test
    fun testMultiplePaymentAllocationsAgainstSingleStatement() {
        val stmt = CreditCardStatement(
            id = 50,
            cardId = 1,
            closingEpochDay = 20000,
            dueEpochDay = 20025,
            statementBalanceMinor = 60000L, // €600
            minPaymentDueMinor = 3000L, // €30
        )
        val allocs = listOf(
            StatementPaymentAllocation(statementId = 50, transactionId = 1, amountMinor = 20000L),
            StatementPaymentAllocation(statementId = 50, transactionId = 2, amountMinor = 40000L),
        )
        val eval = CreditCardMath.evaluateStatement(stmt, allocs, todayEpochDay = 20015)
        assertEquals(60000L, eval.totalPaidMinor)
        assertEquals(0L, eval.remainingBalanceMinor)
        assertEquals(0L, eval.remainingMinDueMinor)
        assertEquals(StatementStatus.PAID, eval.status)
        assertTrue(eval.isMinPaymentSatisfied)
    }

    @Test
    fun testPaymentAllocationsAcrossMultipleStatements() {
        val stmt1 = CreditCardStatement(id = 1, cardId = 1, closingEpochDay = 20000, dueEpochDay = 20025, statementBalanceMinor = 30000L, minPaymentDueMinor = 1500L)
        val stmt2 = CreditCardStatement(id = 2, cardId = 1, closingEpochDay = 20030, dueEpochDay = 20055, statementBalanceMinor = 45000L, minPaymentDueMinor = 2250L)

        val allocs = listOf(
            StatementPaymentAllocation(statementId = 1, transactionId = 10, amountMinor = 30000L),
            StatementPaymentAllocation(statementId = 2, transactionId = 11, amountMinor = 15000L),
        )

        val eval1 = CreditCardMath.evaluateStatement(stmt1, allocs, todayEpochDay = 20035)
        val eval2 = CreditCardMath.evaluateStatement(stmt2, allocs, todayEpochDay = 20035)

        assertEquals(StatementStatus.PAID, eval1.status)
        assertEquals(0L, eval1.remainingBalanceMinor)

        assertEquals(StatementStatus.PARTIALLY_PAID, eval2.status)
        assertEquals(30000L, eval2.remainingBalanceMinor)
        assertEquals(0L, eval2.remainingMinDueMinor)
    }

    @Test
    fun testNegativeBalanceWhenOverpaid() {
        val txs = listOf(
            CreditCardTransaction(cardId = 1, type = CreditCardTxType.PURCHASE, dateEpochDay = 1, amountMinor = 10000L, description = "Shoes"),
            CreditCardTransaction(cardId = 1, type = CreditCardTxType.PAYMENT, dateEpochDay = 2, amountMinor = 15000L, description = "Overpayment"),
        )
        // User owes -50.00 (credit balance on card)
        val balance = CreditCardMath.calculateOutstandingBalance(txs)
        assertEquals(-5000L, balance)

        // Available credit = limit - balance = 100000 - (-5000) = 105000
        val available = CreditCardMath.calculateAvailableCredit(100000L, balance)
        assertEquals(105000L, available)
    }
}
