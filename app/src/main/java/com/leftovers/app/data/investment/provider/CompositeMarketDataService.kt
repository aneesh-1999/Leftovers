package com.leftovers.app.data.investment.provider

import com.leftovers.app.data.investment.InvestmentDao
import com.leftovers.app.data.investment.MarketPriceSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Orchestrates market data retrieval across multiple providers with local persistence,
 * caching, and stale price protection.
 */
class CompositeMarketDataService(
    private val dao: InvestmentDao,
    private val shareBazaar: ShareBazaarProvider = ShareBazaarProvider(),
    private val yoNepse: YoNepseProvider = YoNepseProvider(),
) {

    /**
     * Refreshes market quotes for a specific list of symbols.
     * Strategy:
     * 1. If multiple symbols are requested, try bulk provider (YoNEPSE) first to minimize requests.
     * 2. For any symbol that fails or is missing, fall back to single provider (ShareBazaar).
     * 3. Save successful results to Room `market_price_snapshots`.
     * 4. If all fail, keep existing cached Room snapshots intact (marked stale).
     */
    suspend fun refreshQuotes(symbols: List<String>): Map<String, MarketPriceSnapshot> = withContext(Dispatchers.IO) {
        val uppercaseSymbols = symbols.map { it.trim().uppercase() }.filter { it.isNotBlank() }.distinct()
        if (uppercaseSymbols.isEmpty()) return@withContext emptyMap()

        val results = mutableMapOf<String, MarketPriceSnapshot>()

        // 1. If 2 or more symbols, attempt YoNEPSE bulk fetch
        if (uppercaseSymbols.size >= 2) {
            val bulkResult = yoNepse.fetchAllQuotes()
            bulkResult.onSuccess { allQuotes ->
                val quoteMap = allQuotes.associateBy { it.symbol }
                for (sym in uppercaseSymbols) {
                    val q = quoteMap[sym]
                    if (q != null) {
                        val snapshot = q.toSnapshot()
                        dao.upsertSnapshot(snapshot)
                        results[sym] = snapshot
                    }
                }
            }
        }

        // 2. For any symbols not yet resolved, query ShareBazaar or YoNepse single
        for (sym in uppercaseSymbols) {
            if (!results.containsKey(sym)) {
                val sbResult = shareBazaar.fetchQuote(sym)
                if (sbResult.isSuccess) {
                    val q = sbResult.getOrThrow()
                    val snapshot = q.toSnapshot()
                    dao.upsertSnapshot(snapshot)
                    results[sym] = snapshot
                } else {
                    // Fall back to YoNepse single
                    val ynResult = yoNepse.fetchQuote(sym)
                    if (ynResult.isSuccess) {
                        val q = ynResult.getOrThrow()
                        val snapshot = q.toSnapshot()
                        dao.upsertSnapshot(snapshot)
                        results[sym] = snapshot
                    }
                }
            }
        }

        // 3. For any symbols that completely failed network refresh, retrieve existing cached snapshot from DB
        for (sym in uppercaseSymbols) {
            if (!results.containsKey(sym)) {
                val cached = dao.getSnapshot(sym)
                if (cached != null) {
                    results[sym] = cached
                }
            }
        }

        results
    }

    /**
     * Refreshes the entire NEPSE market catalog using the bulk provider.
     */
    suspend fun refreshAllMarketData(): Result<Int> = withContext(Dispatchers.IO) {
        val bulkResult = yoNepse.fetchAllQuotes()
        bulkResult.mapCatching { list ->
            val snapshots = list.map { it.toSnapshot() }
            dao.upsertSnapshots(snapshots)
            snapshots.size
        }
    }

    /**
     * Records a manual price entry for an asset (e.g. for Gold, Silver, or unlisted shares).
     */
    suspend fun recordManualPrice(
        symbol: String,
        price: Double,
        currency: String = "NPR",
        tradingDate: String? = null,
        companyName: String? = null,
    ): MarketPriceSnapshot = withContext(Dispatchers.IO) {
        val sym = symbol.trim().uppercase()
        val existing = dao.getSnapshot(sym)
        val snapshot = MarketPriceSnapshot(
            symbol = sym,
            price = price,
            previousClose = existing?.price, // Previous price becomes previous close for manual entries
            change = existing?.let { price - it.price },
            percentChange = existing?.let { if (it.price > 0.0) ((price - it.price) / it.price) * 100.0 else null },
            high = maxOf(price, existing?.high ?: price),
            low = minOf(price, existing?.low ?: price),
            companyName = companyName ?: existing?.companyName,
            source = "manual",
            isManual = true,
            timestamp = System.currentTimeMillis(),
            tradingDate = tradingDate,
            currency = currency,
        )
        dao.upsertSnapshot(snapshot)
        snapshot
    }

    private fun MarketQuote.toSnapshot(): MarketPriceSnapshot {
        return MarketPriceSnapshot(
            symbol = symbol,
            price = ltp,
            previousClose = previousClose,
            change = change,
            percentChange = percentChange,
            high = high,
            low = low,
            volume = volume,
            turnover = turnover,
            trades = trades,
            marketCap = marketCap,
            companyName = companyName,
            source = dataSource,
            isManual = false,
            timestamp = timestamp,
            tradingDate = tradingDate,
            currency = "NPR",
        )
    }
}
