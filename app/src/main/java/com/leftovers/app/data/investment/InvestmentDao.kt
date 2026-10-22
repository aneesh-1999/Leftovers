package com.leftovers.app.data.investment

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface InvestmentDao {

    // --- ASSETS ---
    @Query("SELECT * FROM investment_assets ORDER BY name ASC")
    fun observeAllAssets(): Flow<List<InvestmentAsset>>

    @Query("SELECT * FROM investment_assets WHERE type = :type ORDER BY name ASC")
    fun observeAssetsByType(type: InvestmentType): Flow<List<InvestmentAsset>>

    @Query("SELECT * FROM investment_assets WHERE id = :id")
    suspend fun getAssetById(id: Long): InvestmentAsset?

    @Query("SELECT * FROM investment_assets WHERE symbol = :symbol AND type = :type LIMIT 1")
    suspend fun getAssetBySymbolAndType(symbol: String, type: InvestmentType): InvestmentAsset?

    @Upsert
    suspend fun upsertAsset(asset: InvestmentAsset): Long

    @Delete
    suspend fun deleteAsset(asset: InvestmentAsset)

    @Query("DELETE FROM investment_assets WHERE id = :id")
    suspend fun deleteAssetById(id: Long)

    // --- TRANSACTIONS ---
    @Query("SELECT * FROM investment_transactions ORDER BY epochDay DESC, id DESC")
    fun observeAllTransactions(): Flow<List<InvestmentTransaction>>

    @Query("SELECT * FROM investment_transactions WHERE assetId = :assetId ORDER BY epochDay ASC, id ASC")
    fun observeTransactionsForAsset(assetId: Long): Flow<List<InvestmentTransaction>>

    @Query("SELECT * FROM investment_transactions WHERE assetId = :assetId ORDER BY epochDay ASC, id ASC")
    suspend fun getTransactionsForAsset(assetId: Long): List<InvestmentTransaction>

    @Query("SELECT * FROM investment_transactions WHERE id = :id")
    suspend fun getTransactionById(id: Long): InvestmentTransaction?

    @Upsert
    suspend fun upsertTransaction(tx: InvestmentTransaction): Long

    @Delete
    suspend fun deleteTransaction(tx: InvestmentTransaction)

    @Query("DELETE FROM investment_transactions WHERE id = :id")
    suspend fun deleteTransactionById(id: Long)

    @Query("DELETE FROM investment_transactions WHERE assetId = :assetId")
    suspend fun deleteTransactionsForAsset(assetId: Long)

    // --- MARKET PRICE SNAPSHOTS ---
    @Query("SELECT * FROM market_price_snapshots")
    fun observeAllSnapshots(): Flow<List<MarketPriceSnapshot>>

    @Query("SELECT * FROM market_price_snapshots WHERE symbol = :symbol LIMIT 1")
    suspend fun getSnapshot(symbol: String): MarketPriceSnapshot?

    @Query("SELECT * FROM market_price_snapshots WHERE symbol = :symbol LIMIT 1")
    fun observeSnapshot(symbol: String): Flow<MarketPriceSnapshot?>

    @Upsert
    suspend fun upsertSnapshot(snapshot: MarketPriceSnapshot)

    @Upsert
    suspend fun upsertSnapshots(snapshots: List<MarketPriceSnapshot>)

    // --- MARKET PRICE HISTORY ---
    @Query("SELECT * FROM market_price_history WHERE symbol = :symbol ORDER BY timestamp ASC")
    fun observeHistoryForSymbol(symbol: String): Flow<List<MarketPriceHistory>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertHistory(item: MarketPriceHistory)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertHistoryBatch(items: List<MarketPriceHistory>)

    // --- WATCHLIST ---
    @Query("SELECT * FROM watchlist_items ORDER BY addedAt DESC")
    fun observeWatchlist(): Flow<List<WatchlistItem>>

    @Query("SELECT * FROM watchlist_items WHERE symbol = :symbol LIMIT 1")
    suspend fun getWatchlistItem(symbol: String): WatchlistItem?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertWatchlistItem(item: WatchlistItem)

    @Query("DELETE FROM watchlist_items WHERE symbol = :symbol")
    suspend fun deleteWatchlistItem(symbol: String)
}
