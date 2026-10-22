package com.leftovers.app.data.creditcard

import com.leftovers.app.data.Account
import com.leftovers.app.data.AccountDao
import com.leftovers.app.data.CategoryDao
import com.leftovers.app.data.Transaction
import com.leftovers.app.data.TransactionDao
import com.leftovers.app.data.Transfer
import com.leftovers.app.data.TransferDao
import com.leftovers.app.data.TxType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import java.time.LocalDate

class CreditCardRepository(
    private val dao: CreditCardDao,
    private val accountDao: AccountDao,
    private val transferDao: TransferDao,
    private val transactionDao: TransactionDao,
    private val categoryDao: CategoryDao,
) {
    /**
     * Reactive stream of active credit cards, each with its current outstanding balance,
     * available credit, latest statement status, and cycle estimates.
     */
    val activeCardsWithSummary: Flow<List<CreditCardWithSummary>> =
        combine(
            dao.observeActiveCards(),
            dao.observeAllTransactions(),
            dao.observeAllStatements(),
            dao.observeAllAllocations(),
        ) { cards, allTxs, allStmts, allAllocations ->
            val today = LocalDate.now()
            val todayEpochDay = today.toEpochDay()

            cards.map { card ->
                val cardTxs = allTxs.filter { it.cardId == card.id }
                val balanceMinor = CreditCardMath.calculateOutstandingBalance(cardTxs)
                val availableMinor = CreditCardMath.calculateAvailableCredit(card.creditLimitMinor, balanceMinor)

                val cardStmts = allStmts.filter { it.cardId == card.id }
                val evaluatedStmts = cardStmts.map { stmt ->
                    CreditCardMath.evaluateStatement(stmt, allAllocations, todayEpochDay)
                }

                // Latest statement is the one with the highest due date
                val latestStmt = evaluatedStmts.maxByOrNull { it.statement.dueEpochDay }
                val status = CreditCardMath.determineCardStatus(balanceMinor, evaluatedStmts, todayEpochDay)

                val (estClosing, estDue) = CreditCardMath.estimateBillingCycleDates(
                    closingDay = card.billingCycleClosingDay,
                    dueDay = card.paymentDueDay,
                    daysAfterClosing = card.paymentDueDaysAfterClosing,
                    today = today,
                )

                // Current cycle purchases: purchases since latest statement closing date, or all if no statement
                val sinceClosingEpoch = latestStmt?.statement?.closingEpochDay ?: 0L
                val cyclePurchases = cardTxs
                    .filter { it.dateEpochDay > sinceClosingEpoch && it.type == CreditCardTxType.PURCHASE }
                    .sumOf { it.amountMinor }

                CreditCardWithSummary(
                    card = card,
                    currentBalanceMinor = balanceMinor,
                    availableCreditMinor = availableMinor,
                    latestStatement = latestStmt,
                    activeStatementCount = evaluatedStmts.count { it.remainingBalanceMinor > 0L },
                    status = status,
                    nextEstimatedClosingEpochDay = estClosing,
                    nextEstimatedDueEpochDay = estDue,
                    currentCyclePurchasesMinor = cyclePurchases,
                )
            }
        }

    /**
     * Multi-currency summaries grouped strictly by card currency.
     * Never aggregates across different currencies.
     */
    val currencySummaries: Flow<List<CreditCardCurrencySummary>> =
        activeCardsWithSummary.combine(dao.observeActiveCards()) { summaries, _ ->
            CreditCardMath.groupSummariesByCurrency(summaries)
        }

    // --- Card Actions ---

    fun observeCard(id: Long): Flow<CreditCard?> = dao.observeCardById(id)

    suspend fun getCardById(id: Long): CreditCard? = dao.getCardById(id)

    suspend fun saveCard(card: CreditCard): Long {
        val now = System.currentTimeMillis()
        val c = card.copy(updatedAt = now)
        return if (c.id == 0L) {
            dao.insertCard(c.copy(createdAt = now))
        } else {
            dao.updateCard(c)
            c.id
        }
    }

    suspend fun archiveCard(cardId: Long, archived: Boolean = true) {
        val card = dao.getCardById(cardId) ?: return
        dao.updateCard(card.copy(isArchived = archived, updatedAt = System.currentTimeMillis()))
    }

    suspend fun deleteCard(cardId: Long) {
        val card = dao.getCardById(cardId) ?: return
        dao.deleteCard(card)
    }

    // --- Statement Actions ---

    fun observeStatementsForCard(cardId: Long): Flow<List<CreditCardStatement>> =
        dao.observeStatementsForCard(cardId)

    suspend fun getStatementsForCard(cardId: Long): List<CreditCardStatement> =
        dao.getStatementsForCardSync(cardId)

    fun observeEvaluatedStatementsForCard(cardId: Long): Flow<List<StatementWithAllocations>> =
        combine(dao.observeStatementsForCard(cardId), dao.observeAllAllocations()) { stmts, allocations ->
            val todayEpochDay = LocalDate.now().toEpochDay()
            stmts.map { CreditCardMath.evaluateStatement(it, allocations, todayEpochDay) }
        }

    fun observeStatement(id: Long): Flow<CreditCardStatement?> = dao.observeStatementById(id)

    suspend fun getStatementById(id: Long): CreditCardStatement? = dao.getStatementById(id)

    suspend fun saveStatement(statement: CreditCardStatement): Long {
        val now = System.currentTimeMillis()
        val s = statement.copy(updatedAt = now)
        return if (s.id == 0L) {
            dao.insertStatement(s.copy(createdAt = now))
        } else {
            dao.updateStatement(s)
            s.id
        }
    }

    suspend fun deleteStatement(statementId: Long) {
        val stmt = dao.getStatementById(statementId) ?: return
        dao.deleteStatement(stmt)
    }

    // --- Transaction Actions ---

    fun observeTransactionsForCard(cardId: Long): Flow<List<CreditCardTransaction>> =
        dao.observeTransactionsForCard(cardId)

    fun observeTransactionsForStatement(cardId: Long, statementId: Long): Flow<List<CreditCardTransaction>> =
        dao.observeTransactionsForStatement(cardId, statementId)

    /**
     * Records a credit card purchase, refund, fee, or reversal.
     * Can link to an existing finance transaction or create a new finance expense transaction.
     */
    suspend fun recordCardTransaction(
        transaction: CreditCardTransaction,
        createFinanceExpenseTx: Boolean = false,
        financeCategoryId: Long? = null,
    ): Long {
        val now = System.currentTimeMillis()
        var linkedTxId = transaction.linkedFinanceTxId

        // If user explicitly requested creating a finance transaction for a PURCHASE
        if (createFinanceExpenseTx && transaction.type == CreditCardTxType.PURCHASE) {
            val categories = categoryDao.getAll()
            val catId = financeCategoryId
                ?: categories.find { it.type == TxType.EXPENSE && it.name.contains("Shopping", ignoreCase = true) }?.id
                ?: categories.firstOrNull { it.type == TxType.EXPENSE }?.id
                ?: 1L

            val card = dao.getCardById(transaction.cardId)
            val cardPrefix = card?.nickname?.let { "[$it] " } ?: ""
            val financeTx = Transaction(
                amountMinor = transaction.amountMinor,
                type = TxType.EXPENSE,
                categoryId = catId,
                epochDay = transaction.dateEpochDay,
                note = "$cardPrefix${transaction.description}".trim(),
                createdAt = now,
                accountId = transaction.linkedAccountId,
            )
            transactionDao.upsert(financeTx)
            val allTxs = transactionDao.getAll()
            linkedTxId = allTxs.firstOrNull { it.epochDay == transaction.dateEpochDay && it.amountMinor == transaction.amountMinor && it.note == financeTx.note }?.id
        }

        val tx = transaction.copy(
            linkedFinanceTxId = linkedTxId,
            createdAt = if (transaction.id == 0L) now else transaction.createdAt,
            updatedAt = now,
        )

        return if (tx.id == 0L) {
            dao.insertTransaction(tx)
        } else {
            dao.updateTransaction(tx)
            tx.id
        }
    }

    /**
     * Records a credit card payment with optional statement allocation
     * and optional atomic linking to a source bank account.
     *
     * In accordance with requirements:
     * - Reduces current card liability (recorded as PAYMENT type).
     * - Reduces source account balance if selected without creating an expense a second time.
     * - Allocates payment to statement if selected.
     */
    suspend fun recordPayment(
        cardId: Long,
        amountMinor: Long,
        currency: String,
        paymentEpochDay: Long = LocalDate.now().toEpochDay(),
        statementId: Long? = null,
        sourceBankAccountId: Long? = null,
        reference: String = "",
        notes: String = "",
    ): Long {
        val card = dao.getCardById(cardId) ?: throw IllegalArgumentException("Card not found")
        require(card.currency.equals(currency, ignoreCase = true)) {
            "Currency mismatch: Card is ${card.currency} but payment is $currency"
        }

        val now = System.currentTimeMillis()

        // 1. If a source bank account is selected, create an atomic deduction or transfer
        var linkedAccountId: Long? = null
        if (sourceBankAccountId != null) {
            val sourceAccount = accountDao.getAll().find { it.id == sourceBankAccountId }
            if (sourceAccount != null) {
                linkedAccountId = sourceBankAccountId

                // Find or create a matching account record for this credit card so a Transfer can be used
                // Transfers reduce fromAccountId without creating duplicate expense transactions
                val allAccounts = accountDao.getAll()
                var cardAccount = allAccounts.find { it.name.equals(card.nickname, ignoreCase = true) }
                if (cardAccount == null) {
                    val newCardAccId = accountDao.upsert(
                        Account(
                            name = card.nickname,
                            icon = "credit-card",
                            color = card.color,
                            openingMinor = 0L,
                            currency = card.currency,
                        ),
                    )
                    cardAccount = accountDao.getAll().find { it.id == newCardAccId }
                }

                if (cardAccount != null) {
                    val transferNote = "Payment for ${card.nickname}".let {
                        if (reference.isNotBlank()) "$it (Ref: $reference)" else it
                    }
                    transferDao.upsert(
                        Transfer(
                            fromAccountId = sourceBankAccountId,
                            toAccountId = cardAccount.id,
                            amountMinor = amountMinor,
                            epochDay = paymentEpochDay,
                            note = transferNote,
                            createdAt = now,
                        ),
                    )
                }
            }
        }

        // 2. Insert the credit card payment transaction
        val paymentTx = CreditCardTransaction(
            cardId = cardId,
            statementId = statementId,
            type = CreditCardTxType.PAYMENT,
            dateEpochDay = paymentEpochDay,
            amountMinor = amountMinor,
            currency = currency,
            description = "Payment to ${card.nickname}".let { if (reference.isNotBlank()) "$it ($reference)" else it },
            notes = notes,
            linkedAccountId = linkedAccountId,
            createdAt = now,
            updatedAt = now,
        )
        val txId = dao.insertTransaction(paymentTx)

        // 3. If a statement is specified, record the statement payment allocation
        if (statementId != null && statementId > 0L) {
            dao.insertAllocation(
                StatementPaymentAllocation(
                    statementId = statementId,
                    transactionId = txId,
                    amountMinor = amountMinor,
                    allocatedAt = now,
                ),
            )
        }

        return txId
    }

    suspend fun deleteTransaction(txId: Long) {
        val tx = dao.getTransactionById(txId) ?: return
        dao.deleteAllocationsForTransaction(txId)
        if (tx.linkedFinanceTxId != null) {
            val fTx = transactionDao.getById(tx.linkedFinanceTxId)
            if (fTx != null) {
                transactionDao.delete(fTx)
            }
        }
        dao.deleteTransaction(tx)
    }

    suspend fun getActiveCardsSync(): List<CreditCard> = dao.getActiveCardsSync()
    suspend fun getAllTransactionsSync(): List<CreditCardTransaction> = dao.getAllTransactionsSync()
    suspend fun getAllStatementsSync(): List<CreditCardStatement> = dao.getAllStatementsSync()
    suspend fun getAllocationsSync(): List<StatementPaymentAllocation> = dao.getAllAllocationsSync()
}
