package com.leftovers.app.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction as RoomTransaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface RecipientDao {
    @Query("SELECT * FROM recipients WHERE archived = 0 ORDER BY name ASC")
    fun observeActive(): Flow<List<Recipient>>

    @Query("SELECT * FROM recipients ORDER BY archived ASC, name ASC")
    fun observeAll(): Flow<List<Recipient>>

    @Query("SELECT * FROM recipients WHERE id = :id")
    suspend fun getById(id: Long): Recipient?

    @Query("SELECT * FROM recipients ORDER BY name ASC")
    suspend fun getAll(): List<Recipient>

    @Upsert
    suspend fun upsert(recipient: Recipient): Long

    @Delete
    suspend fun delete(recipient: Recipient)

    @Query("UPDATE recipients SET archived = :archived, updatedAt = :updatedAt WHERE id = :id")
    suspend fun setArchived(id: Long, archived: Boolean, updatedAt: Long = System.currentTimeMillis())

    @Query("SELECT COUNT(*) FROM recipients")
    suspend fun count(): Int
}

@Dao
interface RemittanceDao {
    @Query("SELECT * FROM remittances ORDER BY transferDateEpochDay DESC, createdAt DESC")
    fun observeAll(): Flow<List<Remittance>>

    @Query("SELECT * FROM remittances WHERE transferDateEpochDay BETWEEN :fromDay AND :toDay ORDER BY transferDateEpochDay DESC, createdAt DESC")
    fun observeRange(fromDay: Long, toDay: Long): Flow<List<Remittance>>

    @Query("SELECT * FROM remittances WHERE status IN ('SENT', 'PROCESSING') ORDER BY transferDateEpochDay DESC, createdAt DESC")
    fun observePending(): Flow<List<Remittance>>

    @Query("SELECT * FROM remittances WHERE recipientId = :recipientId ORDER BY transferDateEpochDay DESC, createdAt DESC")
    fun observeForRecipient(recipientId: Long): Flow<List<Remittance>>

    @Query("SELECT * FROM remittances WHERE id = :id")
    fun observeById(id: Long): Flow<Remittance?>

    @Query("SELECT * FROM remittances WHERE id = :id")
    suspend fun getById(id: Long): Remittance?

    @Query("SELECT * FROM remittances ORDER BY transferDateEpochDay DESC, createdAt DESC")
    suspend fun getAll(): List<Remittance>

    @Query("SELECT * FROM remittances WHERE transferDateEpochDay BETWEEN :fromDay AND :toDay ORDER BY transferDateEpochDay DESC, createdAt DESC")
    suspend fun getRange(fromDay: Long, toDay: Long): List<Remittance>

    @Query("SELECT * FROM remittances WHERE recipientId = :recipientId")
    suspend fun getForRecipient(recipientId: Long): List<Remittance>

    @Query("SELECT * FROM remittances WHERE financeTransactionId = :txId")
    suspend fun getByFinanceTransactionId(txId: Long): Remittance?

    @Upsert
    suspend fun upsert(remittance: Remittance): Long

    @Delete
    suspend fun delete(remittance: Remittance)

    @Query("DELETE FROM remittances WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("UPDATE remittances SET status = :status, actualReceiveAmountMinor = :actualAmount, receivedDateEpochDay = :receivedDate, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateStatus(id: Long, status: RemittanceStatus, actualAmount: Long?, receivedDate: Long?, updatedAt: Long = System.currentTimeMillis())

    @Query("UPDATE remittances SET financeTransactionId = :txId, updatedAt = :updatedAt WHERE id = :id")
    suspend fun setFinanceTransactionId(id: Long, txId: Long?, updatedAt: Long = System.currentTimeMillis())

    @Query("SELECT COUNT(*) FROM remittances")
    suspend fun count(): Int

    @Query("SELECT DISTINCT provider FROM remittances WHERE provider <> '' ORDER BY provider ASC")
    fun observeProviders(): Flow<List<String>>
}

@Dao
interface RemittanceStatusHistoryDao {
    @Query("SELECT * FROM remittance_status_history WHERE remittanceId = :remittanceId ORDER BY changedAt ASC")
    fun observeForRemittance(remittanceId: Long): Flow<List<RemittanceStatusHistory>>

    @Query("SELECT * FROM remittance_status_history WHERE remittanceId = :remittanceId ORDER BY changedAt ASC")
    suspend fun getForRemittance(remittanceId: Long): List<RemittanceStatusHistory>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(history: RemittanceStatusHistory): Long

    @Query("DELETE FROM remittance_status_history WHERE remittanceId = :remittanceId")
    suspend fun deleteForRemittance(remittanceId: Long)
}
