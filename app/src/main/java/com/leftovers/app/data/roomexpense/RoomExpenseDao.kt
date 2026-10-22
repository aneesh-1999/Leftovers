package com.leftovers.app.data.roomexpense

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface RoomExpenseDao {
    @Query("SELECT * FROM room_expenses ORDER BY timestamp DESC")
    fun observeAll(): Flow<List<RoomExpense>>

    @Query("SELECT * FROM room_expenses ORDER BY timestamp DESC")
    suspend fun getAll(): List<RoomExpense>

    @Query("SELECT * FROM room_expenses WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): RoomExpense?

    @Query("SELECT * FROM room_expenses WHERE timestamp >= :startMillis AND timestamp <= :endMillis ORDER BY timestamp DESC")
    fun observeByRange(startMillis: Long, endMillis: Long): Flow<List<RoomExpense>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(expenses: List<RoomExpense>)

    @Query("DELETE FROM room_expenses WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM room_expenses")
    suspend fun deleteAll()

    @Query("SELECT COUNT(*) FROM room_expenses")
    suspend fun getCount(): Int

    @Query("SELECT MAX(lastSyncedAt) FROM room_expenses")
    suspend fun getLastSyncTime(): Long?
}
