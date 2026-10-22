package com.leftovers.app.data.investment.provider

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * Provider implementation for the YoNepse bulk market data feed:
 * https://shubhamnpk.github.io/yonepse/data/nepse_data.json
 *
 * Provides complete market data rows for ~300+ NEPSE tickers with previous close,
 * high, low, volume, turnover, trades, and market cap.
 */
class YoNepseProvider(
    private val dataUrl: String = "https://shubhamnpk.github.io/yonepse/data/nepse_data.json",
    private val connectTimeoutMs: Int = 10000,
    private val readTimeoutMs: Int = 15000,
    private val cacheTtlMillis: Long = 5 * 60 * 1000L, // 5 minute in-memory cache
) : MarketDataProvider {

    override val providerId: String = "yonepse"
    override val displayName: String = "YoNEPSE Bulk Data Feed"

    private val mutex = Mutex()
    private var cachedQuotes: Map<String, MarketQuote> = emptyMap()
    private var lastFetchTime: Long = 0L

    override suspend fun fetchQuote(symbol: String): Result<MarketQuote> = withContext(Dispatchers.IO) {
        val sym = symbol.trim().uppercase()
        val allResult = fetchAllQuotes()
        allResult.mapCatching { list ->
            list.find { it.symbol.equals(sym, ignoreCase = true) }
                ?: throw NoSuchElementException("Symbol $sym not found in YoNEPSE dataset")
        }
    }

    override suspend fun fetchAllQuotes(): Result<List<MarketQuote>> = withContext(Dispatchers.IO) {
        mutex.withLock {
            val now = System.currentTimeMillis()
            if (cachedQuotes.isNotEmpty() && (now - lastFetchTime) < cacheTtlMillis) {
                return@withContext Result.success(cachedQuotes.values.toList())
            }

            runCatching {
                val url = URL(dataUrl)
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = connectTimeoutMs
                    readTimeout = readTimeoutMs
                    setRequestProperty("Accept", "application/json")
                    setRequestProperty("User-Agent", "Leftovers-App/1.0")
                }

                try {
                    val code = conn.responseCode
                    if (code !in 200..299) {
                        throw IllegalStateException("HTTP $code from YoNEPSE endpoint")
                    }

                    val body = BufferedReader(InputStreamReader(conn.inputStream)).use { it.readText() }
                    val quotes = parseBulkJson(body)
                    cachedQuotes = quotes.associateBy { it.symbol }
                    lastFetchTime = now
                    quotes
                } finally {
                    conn.disconnect()
                }
            }.recoverCatching { err ->
                // If network fetch fails but we have stale in-memory cache, fall back to it
                if (cachedQuotes.isNotEmpty()) {
                    cachedQuotes.values.toList()
                } else {
                    throw err
                }
            }
        }
    }

    /**
     * Defensively parses the bulk array JSON from YoNEPSE.
     */
    fun parseBulkJson(jsonString: String): List<MarketQuote> {
        val array = JSONArray(jsonString)
        val list = ArrayList<MarketQuote>(array.length())

        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            val quote = parseSingleRow(obj)
            if (quote != null) {
                list.add(quote)
            }
        }

        return list
    }

    private fun parseSingleRow(obj: JSONObject): MarketQuote? {
        val symbol = obj.optString("symbol", "").trim().uppercase()
        if (symbol.isBlank()) return null

        val ltp = if (obj.has("ltp") && !obj.isNull("ltp")) {
            obj.optDouble("ltp", Double.NaN)
        } else Double.NaN

        if (ltp.isNaN() || ltp < 0.0) return null

        val name = obj.optString("name", "").ifBlank { null }
        val prevClose = if (obj.has("previous_close") && !obj.isNull("previous_close")) {
            obj.optDouble("previous_close").takeIf { !it.isNaN() && it > 0.0 }
        } else null

        val change = if (obj.has("change") && !obj.isNull("change")) {
            obj.optDouble("change").takeIf { !it.isNaN() }
        } else null

        val percentChange = if (obj.has("percent_change") && !obj.isNull("percent_change")) {
            obj.optDouble("percent_change").takeIf { !it.isNaN() }
        } else null

        val high = if (obj.has("high") && !obj.isNull("high")) obj.optDouble("high").takeIf { !it.isNaN() } else null
        val low = if (obj.has("low") && !obj.isNull("low")) obj.optDouble("low").takeIf { !it.isNaN() } else null
        val volume = if (obj.has("volume") && !obj.isNull("volume")) obj.optLong("volume").takeIf { it >= 0 } else null
        val turnover = if (obj.has("turnover") && !obj.isNull("turnover")) obj.optDouble("turnover").takeIf { !it.isNaN() } else null
        val trades = if (obj.has("trades") && !obj.isNull("trades")) obj.optInt("trades").takeIf { it >= 0 } else null
        val marketCap = if (obj.has("market_cap") && !obj.isNull("market_cap")) obj.optDouble("market_cap").takeIf { !it.isNaN() } else null
        val lastUpdated = obj.optString("last_updated", "").ifBlank { null }

        return MarketQuote(
            symbol = symbol,
            companyName = name,
            ltp = ltp,
            previousClose = prevClose,
            change = change,
            percentChange = percentChange,
            high = high,
            low = low,
            volume = volume,
            turnover = turnover,
            trades = trades,
            marketCap = marketCap,
            lastUpdated = lastUpdated,
            dataSource = providerId,
        ).withDerivedFields()
    }
}
