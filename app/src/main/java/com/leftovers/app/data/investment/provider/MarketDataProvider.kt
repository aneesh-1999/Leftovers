package com.leftovers.app.data.investment.provider

/**
 * Normalized market quote representing live or historical market data from any provider.
 */
data class MarketQuote(
    val symbol: String,
    val companyName: String? = null,
    val ltp: Double,
    val previousClose: Double? = null,
    val change: Double? = null,
    val percentChange: Double? = null,
    val open: Double? = null,
    val high: Double? = null,
    val low: Double? = null,
    val volume: Long? = null,
    val turnover: Double? = null,
    val trades: Int? = null,
    val marketCap: Double? = null,
    val lastUpdated: String? = null,
    val tradingDate: String? = null,
    val dataSource: String,
    val timestamp: Long = System.currentTimeMillis(),
) {
    /**
     * Derives change and percentage change if not provided directly,
     * provided previousClose is present and > 0.
     * Never derives high, low, or volume from LTP.
     */
    fun withDerivedFields(): MarketQuote {
        val prev = previousClose
        if (prev != null && prev > 0.0) {
            val calcChange = change ?: (ltp - prev)
            val calcPercent = percentChange ?: ((calcChange / prev) * 100.0)
            return copy(
                change = Math.round(calcChange * 100.0) / 100.0,
                percentChange = Math.round(calcPercent * 100.0) / 100.0,
            )
        }
        return this
    }
}

/**
 * Abstraction for stock and bullion market data feeds.
 */
interface MarketDataProvider {
    val providerId: String
    val displayName: String

    /** Fetches the latest quote for a single symbol. */
    suspend fun fetchQuote(symbol: String): Result<MarketQuote>

    /** Fetches quotes for all available symbols in bulk if supported by the provider. */
    suspend fun fetchAllQuotes(): Result<List<MarketQuote>>
}
