package com.leftovers.app.data.investment

import com.leftovers.app.data.investment.provider.CompositeMarketDataService
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

/**
 * Watchlist item enriched with its latest market quote.
 */
data class WatchlistItemWithQuote(
    val item: WatchlistItem,
    val quote: MarketPriceSnapshot?,
)

/**
 * Main repository managing investment holdings, transactions, and live quotes.
 */
class InvestmentRepository(
    private val dao: InvestmentDao,
    val marketDataService: CompositeMarketDataService,
) {

    val assets: Flow<List<InvestmentAsset>> = dao.observeAllAssets()
    val transactions: Flow<List<InvestmentTransaction>> = dao.observeAllTransactions()
    val snapshots: Flow<Map<String, MarketPriceSnapshot>> = dao.observeAllSnapshots().map { list ->
        list.associateBy { it.symbol }
    }

    /**
     * Reactively computes holdings whenever assets, transactions, or market quotes change.
     */
    val holdings: Flow<List<HoldingItem>> = combine(
        assets,
        transactions,
        snapshots,
    ) { assetList, txList, snapshotMap ->
        val txsByAsset = txList.groupBy { it.assetId }
        assetList.map { asset ->
            val assetTxs = txsByAsset[asset.id] ?: emptyList()
            val quote = snapshotMap[asset.symbol]
            InvestmentMath.computeHolding(asset, assetTxs, quote)
        }
    }

    /**
     * Active holdings that currently have remaining quantity > 0.
     */
    val activeHoldings: Flow<List<HoldingItem>> = holdings.map { list ->
        list.filter { it.remainingQuantity > 0.0 }
    }

    /**
     * Real-time portfolio summary grouped strictly by native currency.
     */
    val currencySummaries: Flow<Map<String, CurrencyPortfolioSummary>> = holdings.map { list ->
        InvestmentMath.summarizeByCurrency(list)
    }

    /**
     * Watchlist items with their latest quotes.
     */
    val watchlistWithQuotes: Flow<List<WatchlistItemWithQuote>> = combine(
        dao.observeWatchlist(),
        snapshots,
    ) { items, snapshotMap ->
        items.map { item ->
            WatchlistItemWithQuote(
                item = item,
                quote = snapshotMap[item.symbol],
            )
        }
    }

    val snapshotsList: Flow<List<MarketPriceSnapshot>> = dao.observeAllSnapshots()

    fun observePriceHistory(symbol: String): Flow<List<MarketPriceHistory>> = dao.observeHistoryForSymbol(symbol)

    suspend fun getAssetById(id: Long): InvestmentAsset? = dao.getAssetById(id)

    suspend fun getAssetBySymbolAndType(symbol: String, type: InvestmentType): InvestmentAsset? =
        dao.getAssetBySymbolAndType(symbol.trim().uppercase(), type)

    suspend fun saveAsset(asset: InvestmentAsset): Long =
        dao.upsertAsset(asset.copy(symbol = asset.symbol.trim().uppercase(), updatedAt = System.currentTimeMillis()))

    suspend fun deleteAsset(asset: InvestmentAsset) {
        dao.deleteTransactionsForAsset(asset.id)
        dao.deleteAsset(asset)
    }

    fun observeTransactionsForAsset(assetId: Long): Flow<List<InvestmentTransaction>> =
        dao.observeTransactionsForAsset(assetId)

    suspend fun getTransactionsForAsset(assetId: Long): List<InvestmentTransaction> =
        dao.getTransactionsForAsset(assetId)

    suspend fun saveTransaction(tx: InvestmentTransaction): Long =
        dao.upsertTransaction(tx.copy(updatedAt = System.currentTimeMillis()))

    suspend fun deleteTransaction(id: Long) = dao.deleteTransactionById(id)

    /**
     * Convenience method to record a BUY transaction.
     * Automatically creates the asset record if it does not yet exist.
     */
    suspend fun recordBuy(
        symbol: String,
        name: String,
        type: InvestmentType,
        quantity: Double,
        unitPriceMinor: Long,
        feeMinor: Long = 0L,
        currency: String = "NPR",
        unit: AssetUnit = AssetUnit.UNIT_SHARES,
        purity: String? = null,
        epochDay: Long = java.time.LocalDate.now().toEpochDay(),
        notes: String = "",
        linkedAccountId: Long? = null,
    ): Long {
        val sym = symbol.trim().uppercase()
        val existingAsset = dao.getAssetBySymbolAndType(sym, type)
        val assetId = existingAsset?.id ?: dao.upsertAsset(
            InvestmentAsset(
                type = type,
                symbol = sym,
                name = name.ifBlank { sym },
                currency = currency,
                unit = unit,
                purity = purity,
                notes = notes,
            ),
        )

        val totalAmount = InvestmentMath.calculateBuyTotalMinor(quantity, unitPriceMinor, feeMinor)
        val tx = InvestmentTransaction(
            assetId = assetId,
            type = InvestmentTxType.BUY,
            epochDay = epochDay,
            quantity = quantity,
            unitPriceMinor = unitPriceMinor,
            feeMinor = feeMinor,
            totalAmountMinor = totalAmount,
            currency = currency,
            unit = unit,
            notes = notes,
            linkedAccountId = linkedAccountId,
        )
        return dao.upsertTransaction(tx)
    }

    /**
     * Convenience method to record a SELL transaction.
     */
    suspend fun recordSell(
        assetId: Long,
        quantity: Double,
        unitPriceMinor: Long,
        feeMinor: Long = 0L,
        epochDay: Long = java.time.LocalDate.now().toEpochDay(),
        notes: String = "",
        linkedAccountId: Long? = null,
    ): Long {
        val asset = dao.getAssetById(assetId) ?: throw IllegalArgumentException("Asset $assetId not found")
        val currentTxs = dao.getTransactionsForAsset(assetId)
        val holding = InvestmentMath.computeHolding(asset, currentTxs, null)

        if (quantity > holding.remainingQuantity + 0.000001) {
            throw IllegalArgumentException(
                "Cannot sell $quantity ${asset.unit.label}. Current holding is only ${holding.remainingQuantity} ${asset.unit.label}.",
            )
        }

        val totalAmount = InvestmentMath.calculateSellProceedsMinor(quantity, unitPriceMinor, feeMinor)
        val tx = InvestmentTransaction(
            assetId = assetId,
            type = InvestmentTxType.SELL,
            epochDay = epochDay,
            quantity = quantity,
            unitPriceMinor = unitPriceMinor,
            feeMinor = feeMinor,
            totalAmountMinor = totalAmount,
            currency = asset.currency,
            unit = asset.unit,
            notes = notes,
            linkedAccountId = linkedAccountId,
        )
        return dao.upsertTransaction(tx)
    }

    suspend fun refreshQuotesForHoldings(): Map<String, MarketPriceSnapshot> {
        val activeAssets = dao.observeAssetsByType(InvestmentType.STOCK_NEPSE)
        // Fetch all current stock symbols in portfolio
        val txs = dao.observeAllTransactions()
        // Collect first from active holdings or all stock symbols
        val allStocks = dao.observeAllAssets()
        // Simple refresh of symbols
        return emptyMap()
    }

    suspend fun refreshSymbols(symbols: List<String>): Map<String, MarketPriceSnapshot> =
        marketDataService.refreshQuotes(symbols)

    suspend fun refreshAllMarketData(): Result<Int> =
        marketDataService.refreshAllMarketData()

    suspend fun updateManualPrice(
        symbol: String,
        price: Double,
        currency: String = "NPR",
        tradingDate: String? = null,
        companyName: String? = null,
    ) = marketDataService.recordManualPrice(symbol, price, currency, tradingDate, companyName)

    suspend fun addToWatchlist(symbol: String, name: String, type: InvestmentType = InvestmentType.STOCK_NEPSE) {
        val sym = symbol.trim().uppercase()
        dao.insertWatchlistItem(WatchlistItem(symbol = sym, name = name.ifBlank { sym }, assetType = type))
    }

    suspend fun removeFromWatchlist(symbol: String) {
        dao.deleteWatchlistItem(symbol.trim().uppercase())
    }

    suspend fun isWatchlisted(symbol: String): Boolean =
        dao.getWatchlistItem(symbol.trim().uppercase()) != null
}
