package com.leftovers.app.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

private const val SELECT_ITEMS = """
    SELECT t.id, t.amountMinor, t.type, t.categoryId, t.epochDay, t.note, t.createdAt,
           c.name AS categoryName, c.emoji AS categoryEmoji, c.color AS categoryColor,
           t.accountId, t.receiptPath, c.addsToBudget,
           a.currency AS accountCurrency, a.name AS accountName,
           t.foreignMinor, t.foreignCurrency
    FROM transactions t 
    INNER JOIN categories c ON c.id = t.categoryId
    LEFT JOIN accounts a ON a.id = t.accountId
"""

private const val NEWEST_FIRST = " ORDER BY t.epochDay DESC, t.createdAt DESC"

@Dao
interface TransactionDao {
    @Query(SELECT_ITEMS + NEWEST_FIRST)
    fun observeAll(): Flow<List<TransactionItem>>

    @Query(SELECT_ITEMS + " WHERE t.epochDay BETWEEN :fromDay AND :toDay" + NEWEST_FIRST)
    fun observeRange(fromDay: Long, toDay: Long): Flow<List<TransactionItem>>

    @Query(SELECT_ITEMS + NEWEST_FIRST)
    suspend fun getAll(): List<TransactionItem>

    @Query("SELECT * FROM transactions WHERE id = :id")
    suspend fun getById(id: Long): Transaction?

    @Query("SELECT * FROM transactions WHERE note LIKE '%' || :noteSub || '%' LIMIT 1")
    suspend fun findByNoteContaining(noteSub: String): Transaction?

    @Query("SELECT * FROM transactions WHERE amountMinor = :amountMinor AND epochDay = :epochDay AND type = :type LIMIT 1")
    suspend fun findByAmountAndDay(amountMinor: Long, epochDay: Long, type: TxType): Transaction?

    @Upsert
    suspend fun upsert(transaction: Transaction)

    @Delete
    suspend fun delete(transaction: Transaction)

    @Query("DELETE FROM transactions")
    suspend fun deleteAll()

    @Query("SELECT COALESCE(SUM(amountMinor), 0) FROM transactions WHERE type = 'EXPENSE' AND epochDay BETWEEN :fromDay AND :toDay")
    suspend fun expenseTotal(fromDay: Long, toDay: Long): Long

    @Query("SELECT COALESCE(SUM(amountMinor), 0) FROM transactions WHERE type = 'EXPENSE' AND categoryId = :categoryId AND epochDay BETWEEN :fromDay AND :toDay")
    suspend fun expenseTotalForCategory(categoryId: Long, fromDay: Long, toDay: Long): Long

    @Query("SELECT COUNT(*) FROM transactions WHERE categoryId = :categoryId")
    suspend fun countForCategory(categoryId: Long): Int

    @Query("SELECT COUNT(*) FROM transactions WHERE accountId = :accountId")
    suspend fun countForAccount(accountId: Long): Int

    /** Expenses logged (by creation time) between [from] and [to], for duplicate checks. */
    @Query("SELECT amountMinor, createdAt AS at FROM transactions WHERE type = 'EXPENSE' AND createdAt BETWEEN :from AND :to")
    suspend fun expenseAmountsBetween(from: Long, to: Long): List<AmountAt>

    /** Income logged (by creation time) between [from] and [to], for duplicate checks. */
    @Query("SELECT amountMinor, createdAt AS at FROM transactions WHERE type = 'INCOME' AND createdAt BETWEEN :from AND :to")
    suspend fun incomeAmountsBetween(from: Long, to: Long): List<AmountAt>

    @Query("SELECT id FROM transactions WHERE accountId = :accountId")
    suspend fun idsForAccount(accountId: Long): List<Long>

    @Query("UPDATE transactions SET accountId = :accountId WHERE id IN (:ids)")
    suspend fun setAccount(ids: List<Long>, accountId: Long)

    @Query("SELECT receiptPath FROM transactions WHERE receiptPath IS NOT NULL")
    suspend fun receiptPaths(): List<String>
}

@Dao
interface RecurringDao {
    @Query(
        """
        SELECT r.*, c.name AS categoryName, c.emoji AS categoryEmoji, c.color AS categoryColor,
               a.currency AS accountCurrency
        FROM recurring r 
        INNER JOIN categories c ON c.id = r.categoryId
        LEFT JOIN accounts a ON a.id = r.accountId
        ORDER BY r.active DESC, r.dayOfMonth
        """,
    )
    fun observeAll(): Flow<List<RecurringItem>>

    @Query("SELECT * FROM recurring WHERE active = 1")
    suspend fun getActive(): List<Recurring>

    @Upsert
    suspend fun upsert(recurring: Recurring)

    @Delete
    suspend fun delete(recurring: Recurring)

    @Query("UPDATE recurring SET lastPostedMonth = :month WHERE id = :id")
    suspend fun setLastPosted(id: Long, month: String)

    @Query("SELECT COUNT(*) FROM recurring WHERE categoryId = :categoryId")
    suspend fun countForCategory(categoryId: Long): Int
}

@Dao
interface GoalDao {
    @Query(
        """
        SELECT g.*, COALESCE((SELECT SUM(d.amountMinor) FROM goal_deposits d WHERE d.goalId = g.id), 0) AS savedMinor
        FROM goals g ORDER BY g.createdAt
        """,
    )
    fun observeAll(): Flow<List<GoalWithSaved>>

    @Query("SELECT * FROM goal_deposits WHERE goalId = :goalId ORDER BY epochDay DESC, createdAt DESC")
    fun observeDeposits(goalId: Long): Flow<List<GoalDeposit>>

    /** Returns the new row id for an insert, or -1 for an update. */
    @Upsert
    suspend fun upsert(goal: Goal): Long

    @Delete
    suspend fun delete(goal: Goal)

    @Upsert
    suspend fun upsertDeposit(deposit: GoalDeposit)

    @Delete
    suspend fun deleteDeposit(deposit: GoalDeposit)
}

@Dao
interface CategoryDao {
    @Query("SELECT * FROM categories ORDER BY id")
    fun observeAll(): Flow<List<Category>>

    @Query("SELECT * FROM categories ORDER BY id")
    suspend fun getAll(): List<Category>

    @Query("SELECT * FROM categories WHERE id = :id")
    suspend fun getById(id: Long): Category?

    @Upsert
    suspend fun upsert(category: Category)

    @Delete
    suspend fun delete(category: Category)

    @Query("UPDATE categories SET budgetMinor = :budgetMinor WHERE id = :id")
    suspend fun setBudget(id: Long, budgetMinor: Long?)

    @Query("UPDATE categories SET budgetMinor = NULL")
    suspend fun clearBudgets()
}

@Dao
interface AccountDao {
    @Query(
        """
        SELECT a.*, a.openingMinor
            + COALESCE((SELECT SUM(CASE WHEN t.type = 'INCOME' THEN t.amountMinor ELSE -t.amountMinor END)
                        FROM transactions t WHERE t.accountId = a.id), 0)
            + COALESCE((SELECT SUM(amountMinor) FROM transfers WHERE toAccountId = a.id), 0)
            - COALESCE((SELECT SUM(amountMinor) FROM transfers WHERE fromAccountId = a.id), 0)
            - COALESCE((SELECT SUM(d.amountMinor) FROM debts d WHERE d.accountId = a.id AND d.settled = 0), 0) AS balanceMinor
        FROM accounts a ORDER BY a.id
        """,
    )
    fun observeWithBalance(): Flow<List<AccountWithBalance>>

    @Query("SELECT * FROM accounts ORDER BY id")
    suspend fun getAll(): List<Account>

    @Upsert
    suspend fun upsert(account: Account): Long

    @Delete
    suspend fun delete(account: Account)

    @Query("SELECT COUNT(*) FROM transfers WHERE fromAccountId = :id OR toAccountId = :id")
    suspend fun transferCount(id: Long): Int
}

@Dao
interface TransferDao {
    @Query("SELECT * FROM transfers ORDER BY epochDay DESC, createdAt DESC")
    fun observeAll(): Flow<List<Transfer>>

    @Query("SELECT * FROM transfers")
    suspend fun getAll(): List<Transfer>

    @Upsert
    suspend fun upsert(transfer: Transfer)

    @Delete
    suspend fun delete(transfer: Transfer)
}

@Dao
interface SmsDao {
    @Query("SELECT * FROM sms_suggestions ORDER BY receivedAt DESC")
    fun observeAll(): Flow<List<SmsSuggestion>>

    @androidx.room.Insert
    suspend fun insert(suggestion: SmsSuggestion)

    @Query("SELECT * FROM sms_suggestions WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): SmsSuggestion?

    @Query("DELETE FROM sms_suggestions WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT accountDigits FROM sms_suggestions WHERE id = :id")
    suspend fun digitsFor(id: Long): String?

    @Query("SELECT isIncome FROM sms_suggestions WHERE id = :id")
    suspend fun isIncome(id: Long): Boolean?

    @Query("SELECT accountId FROM sms_suggestions WHERE id = :id")
    suspend fun accountIdFor(id: Long): Long?

    @Query("SELECT COUNT(*) FROM sms_suggestions WHERE body = :body")
    suspend fun countWithBody(body: String): Int

    @Query("SELECT amountMinor, receivedAt AS at FROM sms_suggestions WHERE isIncome = :income AND receivedAt BETWEEN :from AND :to")
    suspend fun amountsBetween(from: Long, to: Long, income: Boolean): List<AmountAt>

    @Query("SELECT COUNT(*) FROM seen_messages WHERE hash = :hash")
    suspend fun seenCount(hash: String): Int

    @androidx.room.Insert(onConflict = androidx.room.OnConflictStrategy.IGNORE)
    suspend fun markSeen(message: SeenMessage)

    @Query("DELETE FROM seen_messages WHERE seenAt < :before")
    suspend fun forgetSeenBefore(before: Long)

    @Query("SELECT * FROM sms_suggestions")
    suspend fun getAll(): List<SmsSuggestion>

    @Query("SELECT * FROM sms_suggestions WHERE amountMinor = :amountMinor AND epochDay = :epochDay LIMIT 1")
    suspend fun findByAmountAndDay(amountMinor: Long, epochDay: Long): SmsSuggestion?

    @Query("DELETE FROM sms_suggestions WHERE source = 'room_expense' AND receivedAt < :before")
    suspend fun deleteRoomExpenseSuggestionsBefore(before: Long): Int

    /** Older versions stored whole messages; anything that isn't a 64-char hash gets cleared. */
    @Query("UPDATE sms_suggestions SET body = '' WHERE length(body) != 64")
    suspend fun scrubRawBodies()
}
@Dao
interface DebtDao {
    @Query("SELECT * FROM debts ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<Debt>>

    @Upsert
    suspend fun upsert(debt: Debt)

    @Delete
    suspend fun delete(debt: Debt)

    @Query("SELECT * FROM debts")
    suspend fun getAll(): List<Debt>

    @Query("DELETE FROM debts")
    suspend fun deleteAll()

    @Query("UPDATE debts SET settled = 1 WHERE settled = 0 AND LOWER(TRIM(person)) = LOWER(TRIM(:person))")
    suspend fun settle(person: String)

    @Query("SELECT COUNT(*) FROM debts WHERE accountId = :accountId")
    suspend fun countForAccount(accountId: Long): Int

    @Query("UPDATE debts SET accountId = :toId WHERE accountId = :fromId")
    suspend fun moveAccount(fromId: Long, toId: Long)
}

@Dao
interface DeletedDao {
    @Query(
        """
        SELECT d.*, c.name AS categoryName, c.emoji AS categoryEmoji, c.color AS categoryColor
        FROM deleted_transactions d LEFT JOIN categories c ON c.id = d.categoryId
        ORDER BY d.deletedAt DESC
        """,
    )
    fun observeAll(): Flow<List<DeletedItem>>

    @Query("SELECT COUNT(*) FROM deleted_transactions")
    fun count(): Flow<Int>

    @Upsert
    suspend fun upsert(entry: DeletedTransaction)

    @Query("SELECT * FROM deleted_transactions WHERE id IN (:ids)")
    suspend fun getByIds(ids: List<Long>): List<DeletedTransaction>

    @Query("SELECT * FROM deleted_transactions WHERE deletedAt < :before")
    suspend fun olderThan(before: Long): List<DeletedTransaction>

    @Query("SELECT * FROM deleted_transactions")
    suspend fun getAll(): List<DeletedTransaction>

    @Query("DELETE FROM deleted_transactions WHERE id IN (:ids)")
    suspend fun delete(ids: List<Long>)

    // Deleted Shifts
    @Query("SELECT * FROM deleted_shifts ORDER BY deletedAt DESC")
    fun observeShifts(): Flow<List<DeletedShift>>

    @Query("SELECT COUNT(*) FROM deleted_shifts")
    fun countShifts(): Flow<Int>

    @Upsert
    suspend fun upsertShift(shift: DeletedShift)

    @Query("SELECT * FROM deleted_shifts WHERE id IN (:ids)")
    suspend fun getShiftsByIds(ids: List<Long>): List<DeletedShift>

    @Query("SELECT * FROM deleted_shifts WHERE deletedAt < :before")
    suspend fun olderShiftsThan(before: Long): List<DeletedShift>

    @Query("SELECT * FROM deleted_shifts")
    suspend fun getAllShifts(): List<DeletedShift>

    @Query("DELETE FROM deleted_shifts WHERE id IN (:ids)")
    suspend fun deleteShifts(ids: List<Long>)

    // Deleted Leaves
    @Query("SELECT * FROM deleted_leave_entries ORDER BY deletedAt DESC")
    fun observeLeaves(): Flow<List<DeletedLeaveEntry>>

    @Query("SELECT COUNT(*) FROM deleted_leave_entries")
    fun countLeaves(): Flow<Int>

    @Upsert
    suspend fun upsertLeave(leave: DeletedLeaveEntry)

    @Query("SELECT * FROM deleted_leave_entries WHERE id IN (:ids)")
    suspend fun getLeavesByIds(ids: List<Long>): List<DeletedLeaveEntry>

    @Query("SELECT * FROM deleted_leave_entries WHERE deletedAt < :before")
    suspend fun olderLeavesThan(before: Long): List<DeletedLeaveEntry>

    @Query("SELECT * FROM deleted_leave_entries")
    suspend fun getAllLeaves(): List<DeletedLeaveEntry>

    @Query("DELETE FROM deleted_leave_entries WHERE id IN (:ids)")
    suspend fun deleteLeaves(ids: List<Long>)
}

@Dao
interface ShiftDao {
    @Query("SELECT * FROM shifts ORDER BY startEpochMillis ASC")
    fun observeAll(): Flow<List<Shift>>

    @Query("SELECT * FROM shifts WHERE startEpochMillis >= :startMillis AND startEpochMillis <= :endMillis ORDER BY startEpochMillis ASC")
    fun observeBetween(startMillis: Long, endMillis: Long): Flow<List<Shift>>

    @Query("SELECT * FROM shifts WHERE id = :id")
    suspend fun getById(id: Long): Shift?

    @Upsert
    suspend fun upsert(shift: Shift): Long

    @Delete
    suspend fun delete(shift: Shift)

    @Query("DELETE FROM shifts WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("UPDATE shifts SET status = :status, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateStatus(id: Long, status: ShiftStatus, updatedAt: Long = System.currentTimeMillis())

    @Query("SELECT * FROM shifts")
    suspend fun getAll(): List<Shift>
}

@Dao
interface WorkplaceDao {
    @Query("SELECT * FROM workplaces WHERE archived = 0 ORDER BY name ASC")
    fun observeActive(): Flow<List<Workplace>>

    @Query("SELECT * FROM workplaces ORDER BY archived ASC, name ASC")
    fun observeAll(): Flow<List<Workplace>>

    @Query("SELECT * FROM workplaces WHERE id = :id")
    suspend fun getById(id: Long): Workplace?

    @Upsert
    suspend fun upsert(workplace: Workplace): Long

    @Delete
    suspend fun delete(workplace: Workplace)

    @Query("UPDATE workplaces SET archived = :archived, updatedAt = :updatedAt WHERE id = :id")
    suspend fun setArchived(id: Long, archived: Boolean, updatedAt: Long = System.currentTimeMillis())

    @Query("SELECT * FROM workplaces")
    suspend fun getAll(): List<Workplace>
}

@Dao
interface LeaveDao {
    @Query("SELECT * FROM leave_entries ORDER BY startDateEpochDay ASC")
    fun observeAll(): Flow<List<LeaveEntry>>

    @Query("SELECT * FROM leave_entries WHERE startDateEpochDay <= :endEpochDay AND endDateEpochDay >= :startEpochDay ORDER BY startDateEpochDay ASC")
    fun observeBetween(startEpochDay: Long, endEpochDay: Long): Flow<List<LeaveEntry>>

    @Query("SELECT * FROM leave_entries WHERE id = :id")
    suspend fun getById(id: Long): LeaveEntry?

    @Upsert
    suspend fun upsert(entry: LeaveEntry): Long

    @Delete
    suspend fun delete(entry: LeaveEntry)

    @Query("DELETE FROM leave_entries WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("SELECT * FROM leave_entries")
    suspend fun getAll(): List<LeaveEntry>
}

@Dao
interface WorkplacePaymentDao {
    @Query("SELECT * FROM workplace_payments ORDER BY paymentEpochDay DESC")
    fun observeAll(): Flow<List<WorkplacePayment>>

    @Query("SELECT * FROM workplace_payments WHERE workplaceId = :workplaceId ORDER BY paymentEpochDay DESC")
    fun observeForWorkplace(workplaceId: Long): Flow<List<WorkplacePayment>>

    @Query("SELECT * FROM workplace_payments WHERE workplaceId = :workplaceId AND payPeriodMonth = :month ORDER BY paymentEpochDay DESC")
    fun observeForPeriod(workplaceId: Long, month: String): Flow<List<WorkplacePayment>>

    @Query("SELECT * FROM workplace_payments WHERE id = :id")
    suspend fun getById(id: Long): WorkplacePayment?

    @Upsert
    suspend fun upsert(payment: WorkplacePayment): Long

    @Delete
    suspend fun delete(payment: WorkplacePayment)

    @Query("SELECT * FROM workplace_payments")
    suspend fun getAll(): List<WorkplacePayment>
}

/** Raw table access for backup and restore. */
@Dao
interface BackupDao {
    @Query("SELECT * FROM categories") suspend fun categories(): List<Category>
    @Query("SELECT * FROM transactions") suspend fun transactions(): List<Transaction>
    @Query("SELECT * FROM recurring") suspend fun recurring(): List<Recurring>
    @Query("SELECT * FROM goals") suspend fun goals(): List<Goal>
    @Query("SELECT * FROM goal_deposits") suspend fun deposits(): List<GoalDeposit>
    @Query("SELECT * FROM accounts") suspend fun accounts(): List<Account>
    @Query("SELECT * FROM transfers") suspend fun transfers(): List<Transfer>
    @Query("SELECT * FROM debts") suspend fun debts(): List<Debt>
    @Query("SELECT * FROM shifts") suspend fun shifts(): List<Shift>
    @Query("SELECT * FROM workplaces") suspend fun workplaces(): List<Workplace>
    @Query("SELECT * FROM leave_entries") suspend fun leaveEntries(): List<LeaveEntry>
    @Query("SELECT * FROM workplace_payments") suspend fun workplacePayments(): List<WorkplacePayment>
    @Query("SELECT * FROM recipients") suspend fun recipients(): List<Recipient>
    @Query("SELECT * FROM remittances") suspend fun remittances(): List<Remittance>
    @Query("SELECT * FROM remittance_status_history") suspend fun remittanceStatusHistory(): List<RemittanceStatusHistory>

    @Query("DELETE FROM goal_deposits") suspend fun clearDeposits()
    @Query("DELETE FROM debts") suspend fun clearDebts()
    @Query("DELETE FROM deleted_transactions") suspend fun clearDeleted()
    @Query("DELETE FROM deleted_shifts") suspend fun clearDeletedShifts()
    @Query("DELETE FROM deleted_leave_entries") suspend fun clearDeletedLeaves()
    @Query("DELETE FROM goals") suspend fun clearGoals()
    @Query("DELETE FROM transfers") suspend fun clearTransfers()
    @Query("DELETE FROM transactions") suspend fun clearTransactions()
    @Query("DELETE FROM recurring") suspend fun clearRecurring()
    @Query("DELETE FROM accounts") suspend fun clearAccounts()
    @Query("DELETE FROM categories") suspend fun clearCategories()
    @Query("DELETE FROM shifts") suspend fun clearShifts()
    @Query("DELETE FROM workplaces") suspend fun clearWorkplaces()
    @Query("DELETE FROM leave_entries") suspend fun clearLeaveEntries()
    @Query("DELETE FROM workplace_payments") suspend fun clearWorkplacePayments()
    @Query("DELETE FROM remittance_status_history") suspend fun clearRemittanceStatusHistory()
    @Query("DELETE FROM remittances") suspend fun clearRemittances()
    @Query("DELETE FROM recipients") suspend fun clearRecipients()

    @androidx.room.Insert(onConflict = androidx.room.OnConflictStrategy.REPLACE) suspend fun insertCategories(items: List<Category>)
    @androidx.room.Insert(onConflict = androidx.room.OnConflictStrategy.REPLACE) suspend fun insertTransactions(items: List<Transaction>)
    @androidx.room.Insert(onConflict = androidx.room.OnConflictStrategy.REPLACE) suspend fun insertRecurring(items: List<Recurring>)
    @androidx.room.Insert(onConflict = androidx.room.OnConflictStrategy.REPLACE) suspend fun insertGoals(items: List<Goal>)
    @androidx.room.Insert(onConflict = androidx.room.OnConflictStrategy.REPLACE) suspend fun insertDeposits(items: List<GoalDeposit>)
    @androidx.room.Insert(onConflict = androidx.room.OnConflictStrategy.REPLACE) suspend fun insertAccounts(items: List<Account>)
    @androidx.room.Insert(onConflict = androidx.room.OnConflictStrategy.REPLACE) suspend fun insertTransfers(items: List<Transfer>)
    @androidx.room.Insert(onConflict = androidx.room.OnConflictStrategy.REPLACE) suspend fun insertDebts(items: List<Debt>)
    @androidx.room.Insert(onConflict = androidx.room.OnConflictStrategy.REPLACE) suspend fun insertShifts(items: List<Shift>)
    @androidx.room.Insert(onConflict = androidx.room.OnConflictStrategy.REPLACE) suspend fun insertWorkplaces(items: List<Workplace>)
    @androidx.room.Insert(onConflict = androidx.room.OnConflictStrategy.REPLACE) suspend fun insertLeaveEntries(items: List<LeaveEntry>)
    @androidx.room.Insert(onConflict = androidx.room.OnConflictStrategy.REPLACE) suspend fun insertWorkplacePayments(items: List<WorkplacePayment>)
    @androidx.room.Insert(onConflict = androidx.room.OnConflictStrategy.REPLACE) suspend fun insertRecipients(items: List<Recipient>)
    @androidx.room.Insert(onConflict = androidx.room.OnConflictStrategy.REPLACE) suspend fun insertRemittances(items: List<Remittance>)
    @androidx.room.Insert(onConflict = androidx.room.OnConflictStrategy.REPLACE) suspend fun insertRemittanceStatusHistory(items: List<RemittanceStatusHistory>)
}