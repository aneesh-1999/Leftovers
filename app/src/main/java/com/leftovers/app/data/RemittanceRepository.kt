package com.leftovers.app.data

import androidx.room.withTransaction
import com.leftovers.app.util.ReceiptStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import java.time.LocalDate
import java.time.YearMonth

val StandardRemittanceProviders = listOf(
    "TapTap Send",
    "Revolut",
    "Wise",
    "IME Pay",
    "Prabhu Remit",
    "Remitly",
    "Western Union",
    "Bank Transfer",
    "MoneyGram",
    "EBL Remit",
    "Himal Remit",
)

/**
 * Repository for managing remittances, recipients, transfer providers, and status transitions.
 * Coordinates with TransactionDao to keep account balances and transaction histories strictly synchronised.
 */
class RemittanceRepository(
    private val remittanceDao: RemittanceDao,
    private val recipientDao: RecipientDao,
    private val historyDao: RemittanceStatusHistoryDao,
    private val transactionDao: TransactionDao,
    private val categoryDao: CategoryDao,
    private val settingsRepository: SettingsRepository? = null,
    private val database: AppDatabase? = null,
) {
    val allRemittances: Flow<List<Remittance>> = remittanceDao.observeAll()
    val pendingRemittances: Flow<List<Remittance>> = remittanceDao.observePending()
    val activeRecipients: Flow<List<Recipient>> = recipientDao.observeActive()
    val allRecipients: Flow<List<Recipient>> = recipientDao.observeAll()

    val customProviders: Flow<List<String>> =
        settingsRepository?.customRemittanceProviders ?: flowOf(emptyList())

    val providers: Flow<List<String>> = combine(
        remittanceDao.observeProviders(),
        customProviders,
    ) { dbProviders, custom ->
        (StandardRemittanceProviders + custom + dbProviders).distinct().filter { it.isNotBlank() }
    }

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
     * When [sendingAccountId] is set, automatically deducts the sending amount (and fee if applicable)
     * by creating or updating an expense transaction in the linked account, keeping balances synchronized.
     * If the remittance is cancelled, failed, or refunded, the linked expense is removed so the balance is not debited.
     */
    suspend fun saveRemittance(
        remittance: Remittance,
        createFinanceTx: Boolean = true,
        noteForHistory: String? = null,
    ): Long {
        suspend fun executeSave(): Long {
            val existing = if (remittance.id != 0L) remittanceDao.getById(remittance.id) else null
            var linkedTxId = remittance.financeTransactionId ?: existing?.financeTransactionId

            val isDeductibleStatus = remittance.status != RemittanceStatus.CANCELLED &&
                remittance.status != RemittanceStatus.FAILED &&
                remittance.status != RemittanceStatus.REFUNDED

            if (createFinanceTx && remittance.sendingAccountId != null && isDeductibleStatus) {
                val totalCost = RemittanceCalculations.calculateTotalCostMinor(
                    sendAmountMinor = remittance.sendAmountMinor,
                    feeAmountMinor = remittance.feeAmountMinor,
                    sendCurrency = remittance.sendCurrency,
                    feeCurrency = remittance.feeCurrency,
                    feeMethod = remittance.feeMethod,
                )
                val categories = categoryDao.getAll()
                val cat = categories.find { it.name.equals("Remittance", ignoreCase = true) || it.name.equals("Transfers", ignoreCase = true) }
                    ?: categories.find { it.type == TxType.EXPENSE && it.name.equals("Other", ignoreCase = true) }
                    ?: categories.firstOrNull { it.type == TxType.EXPENSE }
                    ?: run {
                        categoryDao.upsert(Category(name = "Remittance", emoji = "💸", color = 0xFF5B8DEF, type = TxType.EXPENSE))
                        categoryDao.getAll().firstOrNull()
                    }
                val resolvedCatId = cat?.id ?: 1L

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
                    } else {
                        val newTx = Transaction(
                            amountMinor = totalCost,
                            type = TxType.EXPENSE,
                            categoryId = resolvedCatId,
                            epochDay = remittance.transferDateEpochDay,
                            note = txNote,
                            accountId = remittance.sendingAccountId,
                        )
                        linkedTxId = transactionDao.upsert(newTx)
                    }
                } else {
                    val newTx = Transaction(
                        amountMinor = totalCost,
                        type = TxType.EXPENSE,
                        categoryId = resolvedCatId,
                        epochDay = remittance.transferDateEpochDay,
                        note = txNote,
                        accountId = remittance.sendingAccountId,
                    )
                    linkedTxId = transactionDao.upsert(newTx)
                }
            } else {
                // If not deductible status (e.g. cancelled/failed/refunded) or sending account removed, remove any linked transaction
                if (linkedTxId != null) {
                    val existingTx = transactionDao.getById(linkedTxId)
                    if (existingTx != null) {
                        transactionDao.delete(existingTx)
                    }
                    linkedTxId = null
                }
            }

            val toSave = remittance.copy(
                financeTransactionId = linkedTxId,
                updatedAt = System.currentTimeMillis(),
            )
            val id = remittanceDao.upsert(toSave)
            val savedId = if (remittance.id != 0L) remittance.id else id

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

        return if (database != null) {
            database.withTransaction { executeSave() }
        } else {
            executeSave()
        }
    }

    /**
     * Updates the status of a remittance with confirmation of date & actual received amount.
     * Automatically adjusts the linked finance transaction if status is changed to or from terminal reversal states.
     */
    suspend fun updateStatus(
        remittanceId: Long,
        newStatus: RemittanceStatus,
        actualReceiveAmountMinor: Long? = null,
        receivedDateEpochDay: Long? = null,
        statusNote: String? = null,
    ) {
        suspend fun executeUpdate() {
            val existing = remittanceDao.getById(remittanceId) ?: return
            var linkedTxId = existing.financeTransactionId

            if (newStatus == RemittanceStatus.CANCELLED || newStatus == RemittanceStatus.FAILED || newStatus == RemittanceStatus.REFUNDED) {
                if (linkedTxId != null) {
                    val tx = transactionDao.getById(linkedTxId)
                    if (tx != null) transactionDao.delete(tx)
                    linkedTxId = null
                }
            } else if ((existing.status == RemittanceStatus.CANCELLED || existing.status == RemittanceStatus.FAILED || existing.status == RemittanceStatus.REFUNDED) &&
                existing.sendingAccountId != null && linkedTxId == null
            ) {
                // Reactivated: restore finance transaction deduction
                val totalCost = RemittanceCalculations.calculateTotalCostMinor(
                    sendAmountMinor = existing.sendAmountMinor,
                    feeAmountMinor = existing.feeAmountMinor,
                    sendCurrency = existing.sendCurrency,
                    feeCurrency = existing.feeCurrency,
                    feeMethod = existing.feeMethod,
                )
                val categories = categoryDao.getAll()
                val cat = categories.find { it.name.equals("Remittance", ignoreCase = true) || it.name.equals("Transfers", ignoreCase = true) }
                    ?: categories.find { it.type == TxType.EXPENSE && it.name.equals("Other", ignoreCase = true) }
                    ?: categories.firstOrNull { it.type == TxType.EXPENSE }
                val txNote = "Remittance to ${existing.recipientName} via ${existing.provider}".let {
                    if (existing.referenceNumber.isNullOrBlank()) it else "$it (${existing.referenceNumber})"
                }
                val newTx = Transaction(
                    amountMinor = totalCost,
                    type = TxType.EXPENSE,
                    categoryId = cat?.id ?: 1L,
                    epochDay = existing.transferDateEpochDay,
                    note = txNote,
                    accountId = existing.sendingAccountId,
                )
                linkedTxId = transactionDao.upsert(newTx)
            }

            val updated = existing.copy(
                status = newStatus,
                financeTransactionId = linkedTxId,
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

        if (database != null) {
            database.withTransaction { executeUpdate() }
        } else {
            executeUpdate()
        }
    }

    /**
     * Deletes a remittance and reverses any linked account deduction by default,
     * maintaining synchronised balances.
     */
    suspend fun deleteRemittance(remittance: Remittance, deleteLinkedFinanceTx: Boolean = true) {
        suspend fun executeDelete() {
            if (deleteLinkedFinanceTx && remittance.financeTransactionId != null) {
                val tx = transactionDao.getById(remittance.financeTransactionId)
                if (tx != null) transactionDao.delete(tx)
            }
            ReceiptStore.delete(remittance.receiptPath)
            historyDao.deleteForRemittance(remittance.id)
            remittanceDao.delete(remittance)
        }

        if (database != null) {
            database.withTransaction { executeDelete() }
        } else {
            executeDelete()
        }
    }

    // Custom transfer provider management
    suspend fun addCustomProvider(name: String) {
        val trimmed = name.trim()
        if (trimmed.isNotBlank()) {
            settingsRepository?.addCustomRemittanceProvider(trimmed)
        }
    }

    suspend fun updateCustomProvider(oldName: String, newName: String) {
        val trimmed = newName.trim()
        if (trimmed.isNotBlank()) {
            settingsRepository?.updateCustomRemittanceProvider(oldName, trimmed)
            remittanceDao.updateProvider(oldName, trimmed)
        }
    }

    suspend fun deleteCustomProvider(name: String): Boolean {
        val count = remittanceDao.countWithProvider(name)
        if (count > 0) return false
        settingsRepository?.deleteCustomRemittanceProvider(name)
        return true
    }

    suspend fun countRemittancesForProvider(name: String): Int = remittanceDao.countWithProvider(name)

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
