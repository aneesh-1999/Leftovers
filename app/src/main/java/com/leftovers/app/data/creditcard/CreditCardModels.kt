package com.leftovers.app.data.creditcard

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Types of credit card ledger transactions.
 * Accounting sign convention:
 * - Increases liability (debt): PURCHASE, FEE, INTEREST, PAYMENT_REVERSAL
 * - Decreases liability (debt): PAYMENT, REFUND
 */
enum class CreditCardTxType(val label: String, val isLiabilityIncrease: Boolean) {
    PURCHASE("Purchase", true),
    REFUND("Refund", false),
    FEE("Fee & Charges", true),
    INTEREST("Interest", true),
    PAYMENT("Payment", false),
    PAYMENT_REVERSAL("Payment Reversal", true),
}

enum class CardNetwork(val label: String) {
    VISA("Visa"),
    MASTERCARD("Mastercard"),
    AMEX("American Express"),
    RUPAY("RuPay"),
    DISCOVER("Discover"),
    DINERS("Diners Club"),
    UNIONPAY("UnionPay"),
    FONEPAY("Fonepay"),
    OTHER("Other"),
}

enum class CreditCardStatus(val label: String) {
    PAID("Paid"),
    PAYMENT_UPCOMING("Payment Upcoming"),
    PAYMENT_DUE_SOON("Payment Due Soon"),
    OVERDUE("Overdue"),
    NO_STATEMENT("No Statement"),
}

enum class StatementStatus(val label: String) {
    OPEN("Open"),
    UNPAID("Unpaid"),
    PARTIALLY_PAID("Partially Paid"),
    PAID("Paid"),
    OVERDUE("Overdue"),
}

/**
 * Credit Card Profile entity.
 * Security Note:
 * NEVER store the full card number, CVV, PIN, or banking passwords.
 * Only optional last 4 digits are stored for personal identification.
 */
@Entity(
    tableName = "credit_cards",
    indices = [
        Index("isArchived"),
    ],
)
data class CreditCard(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val nickname: String,
    val bankIssuer: String,
    val network: String = "",
    val lastFourDigits: String = "",
    val currency: String = "EUR",
    val creditLimitMinor: Long = 0L,
    val billingCycleClosingDay: Int = 0, // 1-31, 0 if not configured
    val paymentDueDay: Int = 0, // 1-31, 0 if not configured
    val paymentDueDaysAfterClosing: Int = 0, // e.g. 20 or 25 days after cycle closing
    val minPaymentPercentage: Double = 0.0, // e.g. 5.0 for 5%
    val minPaymentFlatMinor: Long = 0L, // e.g. 2500 for €25 min flat
    val notes: String = "",
    val isArchived: Boolean = false,
    val color: Long = 0L,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)

/**
 * Credit Card Statement entity representing a confirmed monthly/periodic billing statement.
 */
@Entity(
    tableName = "credit_card_statements",
    foreignKeys = [
        ForeignKey(
            entity = CreditCard::class,
            parentColumns = ["id"],
            childColumns = ["cardId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index("cardId"),
        Index("dueEpochDay"),
        Index("closingEpochDay"),
    ],
)
data class CreditCardStatement(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val cardId: Long,
    val closingEpochDay: Long,
    val dueEpochDay: Long,
    val statementBalanceMinor: Long,
    val minPaymentDueMinor: Long,
    val currency: String = "EUR",
    val reference: String = "",
    val notes: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)

/**
 * Credit card transaction entry.
 * Can optionally be linked to a statement allocation and/or finance transaction.
 */
@Entity(
    tableName = "credit_card_transactions",
    foreignKeys = [
        ForeignKey(
            entity = CreditCard::class,
            parentColumns = ["id"],
            childColumns = ["cardId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = CreditCardStatement::class,
            parentColumns = ["id"],
            childColumns = ["statementId"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [
        Index("cardId"),
        Index("statementId"),
        Index("dateEpochDay"),
        Index("type"),
    ],
)
data class CreditCardTransaction(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val cardId: Long,
    val statementId: Long? = null,
    val type: CreditCardTxType,
    val dateEpochDay: Long,
    val amountMinor: Long, // positive integer in minor units
    val currency: String = "EUR",
    val description: String,
    val category: String = "",
    val notes: String = "",
    val linkedFinanceTxId: Long? = null,
    val linkedAccountId: Long? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)

/**
 * Explicit allocation of a payment transaction to a specific statement.
 */
@Entity(
    tableName = "statement_payment_allocations",
    foreignKeys = [
        ForeignKey(
            entity = CreditCardStatement::class,
            parentColumns = ["id"],
            childColumns = ["statementId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = CreditCardTransaction::class,
            parentColumns = ["id"],
            childColumns = ["transactionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index("statementId"),
        Index("transactionId"),
    ],
)
data class StatementPaymentAllocation(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val statementId: Long,
    val transactionId: Long,
    val amountMinor: Long,
    val allocatedAt: Long = System.currentTimeMillis(),
)

/**
 * Valuation and status summary for a credit card statement.
 */
data class StatementWithAllocations(
    val statement: CreditCardStatement,
    val totalPaidMinor: Long,
    val remainingBalanceMinor: Long,
    val remainingMinDueMinor: Long,
    val isMinPaymentSatisfied: Boolean,
    val status: StatementStatus,
    val daysUntilDue: Long,
)

/**
 * Computed card valuation including balance, available credit, latest statement, and status.
 */
data class CreditCardWithSummary(
    val card: CreditCard,
    val currentBalanceMinor: Long, // Total outstanding debt
    val availableCreditMinor: Long?, // Null if no limit set
    val latestStatement: StatementWithAllocations?,
    val activeStatementCount: Int,
    val status: CreditCardStatus,
    val nextEstimatedClosingEpochDay: Long?,
    val nextEstimatedDueEpochDay: Long?,
    val currentCyclePurchasesMinor: Long,
)

/**
 * Summary grouped strictly by currency (never combine multiple currencies!).
 */
data class CreditCardCurrencySummary(
    val currency: String,
    val totalOutstandingBalanceMinor: Long,
    val totalStatementBalanceMinor: Long,
    val totalRemainingStatementMinor: Long,
    val totalMinPaymentDueMinor: Long,
    val totalRemainingMinPaymentMinor: Long,
    val totalCreditLimitMinor: Long,
    val cardsWithUpcomingPayments: Int,
    val overduePaymentsCount: Int,
    val cardCount: Int,
)
