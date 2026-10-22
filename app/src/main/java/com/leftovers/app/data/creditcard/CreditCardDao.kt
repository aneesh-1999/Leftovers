package com.leftovers.app.data.creditcard

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface CreditCardDao {

    // --- Credit Cards ---
    @Query("SELECT * FROM credit_cards WHERE isArchived = 0 ORDER BY nickname ASC")
    fun observeActiveCards(): Flow<List<CreditCard>>

    @Query("SELECT * FROM credit_cards ORDER BY isArchived ASC, nickname ASC")
    fun observeAllCards(): Flow<List<CreditCard>>

    @Query("SELECT * FROM credit_cards WHERE id = :id")
    fun observeCardById(id: Long): Flow<CreditCard?>

    @Query("SELECT * FROM credit_cards WHERE id = :id")
    suspend fun getCardById(id: Long): CreditCard?

    @Query("SELECT * FROM credit_cards WHERE isArchived = 0")
    suspend fun getActiveCardsSync(): List<CreditCard>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCard(card: CreditCard): Long

    @Update
    suspend fun updateCard(card: CreditCard)

    @Delete
    suspend fun deleteCard(card: CreditCard)

    // --- Statements ---
    @Query("SELECT * FROM credit_card_statements ORDER BY dueEpochDay DESC")
    fun observeAllStatements(): Flow<List<CreditCardStatement>>

    @Query("SELECT * FROM credit_card_statements WHERE cardId = :cardId ORDER BY dueEpochDay DESC")
    fun observeStatementsForCard(cardId: Long): Flow<List<CreditCardStatement>>

    @Query("SELECT * FROM credit_card_statements WHERE id = :id")
    fun observeStatementById(id: Long): Flow<CreditCardStatement?>

    @Query("SELECT * FROM credit_card_statements WHERE id = :id")
    suspend fun getStatementById(id: Long): CreditCardStatement?

    @Query("SELECT * FROM credit_card_statements WHERE cardId = :cardId ORDER BY dueEpochDay DESC")
    suspend fun getStatementsForCardSync(cardId: Long): List<CreditCardStatement>

    @Query("SELECT * FROM credit_card_statements ORDER BY dueEpochDay ASC")
    suspend fun getAllStatementsSync(): List<CreditCardStatement>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertStatement(statement: CreditCardStatement): Long

    @Update
    suspend fun updateStatement(statement: CreditCardStatement)

    @Delete
    suspend fun deleteStatement(statement: CreditCardStatement)

    // --- Transactions ---
    @Query("SELECT * FROM credit_card_transactions ORDER BY dateEpochDay DESC, id DESC")
    fun observeAllTransactions(): Flow<List<CreditCardTransaction>>

    @Query("SELECT * FROM credit_card_transactions WHERE cardId = :cardId ORDER BY dateEpochDay DESC, id DESC")
    fun observeTransactionsForCard(cardId: Long): Flow<List<CreditCardTransaction>>

    @Query("SELECT * FROM credit_card_transactions WHERE cardId = :cardId AND statementId = :statementId ORDER BY dateEpochDay DESC, id DESC")
    fun observeTransactionsForStatement(cardId: Long, statementId: Long): Flow<List<CreditCardTransaction>>

    @Query("SELECT * FROM credit_card_transactions WHERE cardId = :cardId")
    suspend fun getTransactionsForCardSync(cardId: Long): List<CreditCardTransaction>

    @Query("SELECT * FROM credit_card_transactions WHERE id = :id")
    suspend fun getTransactionById(id: Long): CreditCardTransaction?

    @Query("SELECT * FROM credit_card_transactions ORDER BY dateEpochDay DESC, id DESC")
    suspend fun getAllTransactionsSync(): List<CreditCardTransaction>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTransaction(transaction: CreditCardTransaction): Long

    @Update
    suspend fun updateTransaction(transaction: CreditCardTransaction)

    @Delete
    suspend fun deleteTransaction(transaction: CreditCardTransaction)

    // --- Statement Payment Allocations ---
    @Query("SELECT * FROM statement_payment_allocations")
    fun observeAllAllocations(): Flow<List<StatementPaymentAllocation>>

    @Query("SELECT * FROM statement_payment_allocations WHERE statementId = :statementId")
    fun observeAllocationsForStatement(statementId: Long): Flow<List<StatementPaymentAllocation>>

    @Query("SELECT * FROM statement_payment_allocations WHERE transactionId = :transactionId")
    suspend fun getAllocationsForTransaction(transactionId: Long): List<StatementPaymentAllocation>

    @Query("SELECT * FROM statement_payment_allocations")
    suspend fun getAllAllocationsSync(): List<StatementPaymentAllocation>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAllocation(allocation: StatementPaymentAllocation): Long

    @Delete
    suspend fun deleteAllocation(allocation: StatementPaymentAllocation)

    @Query("DELETE FROM statement_payment_allocations WHERE transactionId = :transactionId")
    suspend fun deleteAllocationsForTransaction(transactionId: Long)
}
