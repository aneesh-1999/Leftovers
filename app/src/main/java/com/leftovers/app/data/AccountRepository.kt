package com.leftovers.app.data

import kotlinx.coroutines.flow.Flow

class AccountRepository(
    private val accountDao: AccountDao,
    private val transferDao: TransferDao,
    private val transactionDao: TransactionDao,
    private val debtDao: DebtDao? = null,
) {
    val accounts: Flow<List<AccountWithBalance>> = accountDao.observeWithBalance()
    val transfers: Flow<List<Transfer>> = transferDao.observeAll()

    suspend fun saveAccount(account: Account): Long = accountDao.upsert(account)

    /** Deletes the account, or returns how many entries still use it (and deletes nothing). */
    suspend fun deleteAccount(account: Account): Int {
        val used = transactionDao.countForAccount(account.id) + accountDao.transferCount(account.id) + (debtDao?.countForAccount(account.id) ?: 0)
        if (used == 0) accountDao.delete(account)
        return used
    }

    /** Re-files every entry of one account under another; returns the moved ids so it can be undone. */
    suspend fun moveEntries(fromId: Long, toId: Long): List<Long> {
        val ids = transactionDao.idsForAccount(fromId)
        setAccount(ids, toId)
        debtDao?.moveAccount(fromId, toId)
        return ids
    }

    suspend fun setAccount(ids: List<Long>, accountId: Long) {
        // SQLite caps the number of query parameters, so update in batches.
        ids.chunked(500).forEach { transactionDao.setAccount(it, accountId) }
    }

    suspend fun saveTransfer(transfer: Transfer) = transferDao.upsert(transfer)
    suspend fun deleteTransfer(transfer: Transfer) = transferDao.delete(transfer)
}

class SmsRepository(private val dao: SmsDao, private val transactionDao: TransactionDao) {
    companion object {
        /** How long a seen message is remembered; far longer than any email check looks back. */
        const val SEEN_DAYS = 30L
    }

    val suggestions: Flow<List<SmsSuggestion>> = dao.observeAll()

    /**
     * Stores a detected payment unless it's a repeat: the same message again, or the same amount already
     * suggested or logged around the same time (see [DuplicatePayments]). Returns whether it was added.
     */
    suspend fun add(suggestion: SmsSuggestion): Boolean {
        val hash = suggestion.body
        // Seen before, even if that suggestion was since added or dismissed: email checks look back two days.
        if (hash.isNotEmpty() && (dao.countWithBody(hash) > 0 || dao.seenCount(hash) > 0)) return false
        val from = suggestion.receivedAt - DuplicatePayments.WINDOW_MS
        val to = suggestion.receivedAt + DuplicatePayments.WINDOW_MS
        val logged = if (suggestion.isIncome) transactionDao.incomeAmountsBetween(from, to) else transactionDao.expenseAmountsBetween(from, to)
        val seen = dao.amountsBetween(from, to, suggestion.isIncome) + logged
        if (hash.isNotEmpty()) dao.markSeen(SeenMessage(hash, System.currentTimeMillis()))
        if (DuplicatePayments.isDuplicate(suggestion.amountMinor, suggestion.receivedAt, seen)) return false
        dao.insert(suggestion)
        return true
    }

    /** Records a message hash as seen so it will not be suggested or processed again. */
    suspend fun markSeen(hash: String) {
        if (hash.isNotEmpty()) dao.markSeen(SeenMessage(hash, System.currentTimeMillis()))
    }

    /** Inserts a candidate suggestion into the review queue after duplicate verification. */
    suspend fun insertSuggestion(suggestion: SmsSuggestion): Boolean {
        val hash = suggestion.body
        if (hash.isNotEmpty() && (dao.countWithBody(hash) > 0 || dao.seenCount(hash) > 0)) return false

        // Duplicate check against existing suggestions on the same day
        val existingWithSameAmountAndDay = dao.findByAmountAndDay(suggestion.amountMinor, suggestion.epochDay)
        if (existingWithSameAmountAndDay != null) {
            // If the incoming suggestion is from room_expense and existing is generic (e.g. Revolut/SMS), upgrade to room_expense!
            if (suggestion.source == SmsSuggestion.SOURCE_ROOM_EXPENSE && existingWithSameAmountAndDay.source != SmsSuggestion.SOURCE_ROOM_EXPENSE) {
                dao.delete(existingWithSameAmountAndDay.id)
                if (hash.isNotEmpty()) dao.markSeen(SeenMessage(hash, System.currentTimeMillis()))
                dao.insert(suggestion)
                return true
            }
            // Otherwise, it's a duplicate suggestion for the same payment on the same day
            if (hash.isNotEmpty()) dao.markSeen(SeenMessage(hash, System.currentTimeMillis()))
            return false
        }

        // Duplicate check against transactions already logged on the same day
        val matchingTx = transactionDao.findByAmountAndDay(
            amountMinor = suggestion.amountMinor,
            epochDay = suggestion.epochDay,
            type = if (suggestion.isIncome) TxType.INCOME else TxType.EXPENSE,
        )
        if (matchingTx != null) {
            // Already logged! Mark seen and skip
            if (hash.isNotEmpty()) dao.markSeen(SeenMessage(hash, System.currentTimeMillis()))
            return false
        }

        if (hash.isNotEmpty()) dao.markSeen(SeenMessage(hash, System.currentTimeMillis()))
        dao.insert(suggestion)
        return true
    }

    /** Deletes historical room expense suggestions created before the specified timestamp. */
    suspend fun deleteRoomExpenseSuggestionsBefore(before: Long): Int = dao.deleteRoomExpenseSuggestionsBefore(before)

    /** Finds an existing suggestion with the same amount and day. */
    suspend fun findSuggestionByAmountAndDay(amountMinor: Long, epochDay: Long): SmsSuggestion? =
        dao.findByAmountAndDay(amountMinor, epochDay)

    /** Forgets seen messages older than any email check looks back. */
    suspend fun forgetOldSeen() = dao.forgetSeenBefore(System.currentTimeMillis() - java.util.concurrent.TimeUnit.DAYS.toMillis(SEEN_DAYS))

    suspend fun dismiss(id: Long) = dao.delete(id)

    /** The account digits a detected payment's message named, if any. */
    suspend fun digitsFor(id: Long): String? = dao.digitsFor(id)

    /** The account a detected payment matched, if any. */
    suspend fun accountIdFor(id: Long): Long? = dao.accountIdFor(id)

    /** Whether a detected payment is money coming in. */
    suspend fun isIncome(id: Long): Boolean = dao.isIncome(id) == true

    /** Retrieves a specific suggestion by its ID. */
    suspend fun getSuggestion(id: Long): SmsSuggestion? = dao.getById(id)

    /** Returns true if a pending suggestion with this body/hash already exists. */
    suspend fun hasSuggestionWithBody(body: String): Boolean = dao.countWithBody(body) > 0

    /** Returns true if a message with this hash has already been seen. */
    suspend fun hasSeen(hash: String): Boolean = dao.seenCount(hash) > 0

    suspend fun scrubRawBodies() = dao.scrubRawBodies()
}
