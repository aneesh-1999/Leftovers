package com.leftovers.app.data

import com.leftovers.app.util.ReceiptStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import java.time.LocalDate
import java.time.YearMonth

/**
 * Repository for managing remittances, recipients, and status transitions.
 * Coordinates with TransactionDao when the user chooses to link or create a finance transaction.
 */
class RemittanceRepository(
    private val remittanceDao: RemittanceDao,
    private val recipientDao: RecipientDao,
    private val historyDao: RemittanceStatusHistoryDao,
    private val transactionDao: TransactionDao,
    private val categoryDao: CategoryDao,
) {
    val allRemittances: Flow<List<Remittance>> = remittanceDao.observeAll()
    val pendingRemittances: Flow<List<Remittance>> = remittanceDao.observePending()
    val activeRecipients: Flow<List<Recipient>> = recipientDao.observeActive()
    val allRecipients: Flow<List<Recipient>> = recipientDao.observeAll()
    val providers: Flow<List<String>> = remittanceDao.observeProviders()

    fun remittancesInMonth(month: YearMonth): Flow<List<Remittance>> {
        val startDay = month.atDay(1).toEpochDay()
        val endDay = month.atEndOfMonth().toEpochDay()
        return remittanceDao.observeRange(startDay, endDay)
    }

    fun remittancesInYear(year: Int): Flow<List<Remittance>> {
        val startDay = LocalDate.of(year, 1, 1).toEpochDay()
        val endDay = LocalDate.of(year, 12, 31).toEpochDay()
        return remittanceDao.observeRange(startDay, endDay)
    }

    fun remittancesForRecipient(recipientId: Long): Flow<List<Remittance>> =
        remittanceDao.observeForRecipient(recipientId)

    val recipientsWithStats: Flow<List<RecipientWithStats>> =
        combine(recipientDao.observeAll(), remittanceDao.observeAll()) { recipients, remittances ->
            recipients.map { r ->
                val recipientTxs = remittances.filter {
                    it.recipientId == r.id || (it.recipientId == null && it.recipientName.equals(r.name.trim(), ignoreCase = true))
                }
                val completed = recipientTxs.filter { it.status == RemittanceStatus.RECEIVED }
                val totalEur = completed.filter { it.sendCurrency.equals("EUR", ignoreCase = true) }
                    .sumOf { it.sendAmountMinor }
                val totalNpr = completed.filter { it.receiveCurrency.equals("NPR", ignoreCase = true) }
                    .sumOf { it.actualReceiveAmountMinor ?: it.expectedReceiveAmountMinor }
                val lastDate = recipientTxs.maxOfOrNull { it.transferDateEpochDay }
                RecipientWithStats(
                    recipient = r,
                    transferCount = recipientTxs.count { it.status != RemittanceStatus.CANCELLED && it.status != RemittanceStatus.FAILED },
                    totalSentEurMinor = totalEur,
                    totalReceivedNprMinor = totalNpr,
                    lastTransferDateEpochDay = lastDate,
                )
            }
        }

    suspend fun getRemittanceById(id: Long): Remittance? = remittanceDao.getById(id)

    fun observeRemittance(id: Long): Flow<Remittance?> = remittanceDao.observeById(id)

    suspend fun getRecipientById(id: Long): Recipient? = recipientDao.getById(id)

    suspend fun getStatusHistory(remittanceId: Long): List<RemittanceStatusHistory> =
        historyDao.getForRemittance(remittanceId)

    fun observeStatusHistory(remittanceId: Long): Flow<List<RemittanceStatusHistory>> =
        historyDao.observeForRemittance(remittanceId)

    /**
     * Saves a remittance record.
     * If [createOrUpdateFinanceTx] is true and a [sendingAccountId] is selected,
     * creates or updates the corresponding finance transaction in the account's native currency.
     */
    suspend fun saveRemittance(
        remittance: Remittance,
        createFinanceTx: Boolean = false,
        noteForHistory: String? = null,
    ): Long {
        val existing = if (remittance.id != 0L) remittanceDao.getById(remittance.id) else null
        var linkedTxId = remittance.financeTransactionId

        // Handle optional finance transaction linking
        if (createFinanceTx && remittance.sendingAccountId != null) {
            val totalCost = RemittanceCalculations.calculateTotalCostMinor(
                sendAmountMinor = remittance.sendAmountMinor,
                feeAmountMinor = remittance.feeAmountMinor,
                sendCurrency = remittance.sendCurrency,
                feeCurrency = remittance.feeCurrency,
                feeMethod = remittance.feeMethod,
            )
            // Ensure or find a suitable category for Remittances
            val categories = categoryDao.getAll()
            val cat = categories.find { it.name.equals("Remittance", ignoreCase = true) || it.name.equals("Transfers", ignoreCase = true) }
                ?: categories.find { it.type == TxType.EXPENSE && it.name.equals("Other", ignoreCase = true) }
                ?: categories.firstOrNull { it.type == TxType.EXPENSE }

            val txNote = "Remittance to ${remittance.recipientName} via ${remittance.provider}".let {
                if (remittance.referenceNumber.isNullOrBlank()) it else "$it (${remittance.referenceNumber})"
            }

            if (linkedTxId != null) {
                val existingTx = transactionDao.getById(linkedTxId)
                if (existingTx != null) {
                    val updatedTx = existingTx.copy(
                        amountMinor = totalCost,
                        type = TxType.EXPENSE,
                        categoryId = cat?.id ?: existingTx.categoryId,
                        epochDay = remittance.transferDateEpochDay,
                        note = txNote,
                        accountId = remittance.sendingAccountId,
                    )
                    transactionDao.upsert(updatedTx)
                }
            } else {
                val newTx = Transaction(
                    amountMinor = totalCost,
                    type = TxType.EXPENSE,
                    categoryId = cat?.id ?: 1L,
                    epochDay = remittance.transferDateEpochDay,
                    note = txNote,
                    accountId = remittance.sendingAccountId,
                )
                transactionDao.upsert(newTx)
                // Retrieve the latest created transaction id for this account & day
                val txs = transactionDao.getAll()
                linkedTxId = txs.firstOrNull { it.accountId == remittance.sendingAccountId && it.epochDay == remittance.transferDateEpochDay && it.amountMinor == totalCost }?.id
            }
        }

        val toSave = remittance.copy(
            financeTransactionId = linkedTxId,
            updatedAt = System.currentTimeMillis(),
        )
        val id = remittanceDao.upsert(toSave)
        val savedId = if (remittance.id != 0L) remittance.id else id

        // Record status change in history if newly created or status changed
        if (existing == null || existing.status != remittance.status) {
            historyDao.insert(
                RemittanceStatusHistory(
                    remittanceId = savedId,
                    fromStatus = existing?.status,
                    toStatus = remittance.status,
                    changedAt = System.currentTimeMillis(),
                    note = noteForHistory ?: if (existing == null) "Transfer recorded as ${remittance.status.label}" else "Status changed to ${remittance.status.label}",
                ),
            )
        }

        return savedId
    }

    /**
     * Updates the status of a remittance with confirmation of date & actual received amount.
     */
    suspend fun updateStatus(
        remittanceId: Long,
        newStatus: RemittanceStatus,
        actualReceiveAmountMinor: Long? = null,
        receivedDateEpochDay: Long? = null,
        statusNote: String? = null,
    ) {
        val existing = remittanceDao.getById(remittanceId) ?: return
        val updated = existing.copy(
            status = newStatus,
            actualReceiveAmountMinor = if (newStatus == RemittanceStatus.RECEIVED) (actualReceiveAmountMinor ?: existing.actualReceiveAmountMinor ?: existing.expectedReceiveAmountMinor) else existing.actualReceiveAmountMinor,
            receivedDateEpochDay = if (newStatus == RemittanceStatus.RECEIVED) (receivedDateEpochDay ?: existing.receivedDateEpochDay ?: LocalDate.now().toEpochDay()) else existing.receivedDateEpochDay,
            updatedAt = System.currentTimeMillis(),
        )
        remittanceDao.upsert(updated)
        historyDao.insert(
            RemittanceStatusHistory(
                remittanceId = remittanceId,
                fromStatus = existing.status,
                toStatus = newStatus,
                changedAt = System.currentTimeMillis(),
                note = statusNote ?: "Status updated to ${newStatus.label}",
            ),
        )
    }

    suspend fun deleteRemittance(remittance: Remittance, deleteLinkedFinanceTx: Boolean = false) {
        if (deleteLinkedFinanceTx && remittance.financeTransactionId != null) {
            val tx = transactionDao.getById(remittance.financeTransactionId)
            if (tx != null) transactionDao.delete(tx)
        }
        ReceiptStore.delete(remittance.receiptPath)
        historyDao.deleteForRemittance(remittance.id)
        remittanceDao.delete(remittance)
    }

    suspend fun saveRecipient(recipient: Recipient): Long =
        recipientDao.upsert(recipient.copy(updatedAt = System.currentTimeMillis()))

    suspend fun setRecipientArchived(id: Long, archived: Boolean) =
        recipientDao.setArchived(id, archived)

    suspend fun deleteRecipient(recipient: Recipient) =
        recipientDao.delete(recipient)

    // For backup and restore
    suspend fun getAllRemittancesDirect(): List<Remittance> = remittanceDao.getAll()
    suspend fun getAllRecipientsDirect(): List<Recipient> = recipientDao.getAll()
}
