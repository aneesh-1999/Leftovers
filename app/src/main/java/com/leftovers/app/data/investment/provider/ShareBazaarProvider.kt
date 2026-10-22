package com.leftovers.app.data.investment.provider

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Provider implementation for the Sharebazaar NEPSE API:
 * https://sharebazaar.vercel.app/api?symbol={SYMBOL}
 */
class ShareBazaarProvider(
    private val baseUrl: String = "https://sharebazaar.vercel.app/api",
    private val connectTimeoutMs: Int = 8000,
    private val readTimeoutMs: Int = 10000,
) : MarketDataProvider {

    override val providerId: String = "sharebazaar"
    override val displayName: String = "ShareBazaar NEPSE API"

    override suspend fun fetchQuote(symbol: String): Result<MarketQuote> = withContext(Dispatchers.IO) {
        runCatching {
            val encodedSymbol = URLEncoder.encode(symbol.trim().uppercase(), "UTF-8")
            val urlString = "$baseUrl?symbol=$encodedSymbol"
            val url = URL(urlString)
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
                    throw IllegalStateException("HTTP $code from ShareBazaar API for symbol $symbol")
                }

                val body = BufferedReader(InputStreamReader(conn.inputStream)).use { it.readText() }
                parseQuoteJson(body, symbol)
            } finally {
                conn.disconnect()
            }
        }
    }

    override suspend fun fetchAllQuotes(): Result<List<MarketQuote>> {
        // ShareBazaar does not support bulk quotes on this endpoint
        return Result.failure(UnsupportedOperationException("ShareBazaar provider only supports single symbol queries"))
    }

    /**
     * Defensively parses JSON from ShareBazaar.
     * Response shape: {"id":"NABIL","symbol":"NABIL","company_name":"Nabil Bank Limited","ltp":536.2,"last_updated":"2026-10-11T19:05:51.972Z"}
     */
    fun parseQuoteJson(jsonString: String, requestedSymbol: String): MarketQuote {
        val root = JSONObject(jsonString)

        val rawSymbol = root.optString("symbol", "").ifBlank {
            root.optString("id", requestedSymbol)
        }.trim().uppercase()

        val companyName = root.optString("company_name", "").ifBlank {
            root.optString("name", "").ifBlank { null }
        }

        // Defensive LTP extraction
        val ltp = when {
            root.has("ltp") && !root.isNull("ltp") -> root.optDouble("ltp", Double.NaN)
            root.has("price") && !root.isNull("price") -> root.optDouble("price", Double.NaN)
            else -> Double.NaN
        }

        if (ltp.isNaN() || ltp < 0.0) {
            throw IllegalArgumentException("Valid LTP price not found in response for $requestedSymbol: $jsonString")
        }

        val lastUpdated = root.optString("last_updated", "").ifBlank { null }

        val prevClose = if (root.has("previous_close") && !root.isNull("previous_close")) {
            root.optDouble("previous_close").takeIf { !it.isNaN() && it > 0.0 }
        } else null

        val change = if (root.has("change") && !root.isNull("change")) {
            root.optDouble("change").takeIf { !it.isNaN() }
        } else null

        val percentChange = if (root.has("percent_change") && !root.isNull("percent_change")) {
            root.optDouble("percent_change").takeIf { !it.isNaN() }
        } else null

        val high = if (root.has("high") && !root.isNull("high")) root.optDouble("high").takeIf { !it.isNaN() } else null
        val low = if (root.has("low") && !root.isNull("low")) root.optDouble("low").takeIf { !it.isNaN() } else null
        val volume = if (root.has("volume") && !root.isNull("volume")) root.optLong("volume").takeIf { it >= 0 } else null

        return MarketQuote(
            symbol = rawSymbol.ifBlank { requestedSymbol.uppercase() },
            companyName = companyName,
            ltp = ltp,
            previousClose = prevClose,
            change = change,
            percentChange = percentChange,
            high = high,
            low = low,
            volume = volume,
            lastUpdated = lastUpdated,
            dataSource = providerId,
        ).withDerivedFields()
    }
}
