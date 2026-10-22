package com.leftovers.app.data.investment

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Types of investment assets supported in the portfolio.
 */
enum class InvestmentType(val label: String) {
    STOCK_NEPSE("NEPSE Share"),
    GOLD("Gold"),
    SILVER("Silver"),
}

/**
 * Measurement units for asset quantities.
 */
enum class AssetUnit(val symbol: String, val label: String) {
    UNIT_SHARES("shares", "Shares"),
    UNIT_GRAM("g", "Grams"),
    UNIT_TOLA("tola", "Tola");

    companion object {
        /** Documented conversion factor: 1 tola = 11.6638 grams */
        const val GRAMS_PER_TOLA = 11.6638
    }
}

/**
 * Ledger transaction types.
 */
enum class InvestmentTxType(val label: String) {
    BUY("Buy"),
    SELL("Sell"),
    FEE("Fee / Charge"),
    DIVIDEND_CASH("Cash Dividend"),
    DIVIDEND_BONUS("Bonus Shares"),
    VALUATION_UPDATE("Valuation Update"),
}

/**
 * Indicates origin and reliability of the current valuation price.
 */
enum class PriceSourceType(val label: String) {
    LIVE("Live Market"),
    CACHED_STALE("Cached (Stale)"),
    MANUAL("Manual Entry"),
    HISTORICAL("Historical Close"),
}

/**
 * Master asset entity representing a distinct holding or security.
 */
@Entity(
    tableName = "investment_assets",
    indices = [
        Index(value = ["symbol", "type"], unique = true),
        Index(value = ["type"]),
    ],
)
data class InvestmentAsset(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val type: InvestmentType,
    /** Stock ticker symbol (e.g. "NABIL") or metal code (e.g. "GOLD-24K", "SILVER-999") */
    val symbol: String,
    /** Company name or asset description (e.g. "Nabil Bank Limited", "24K Physical Gold") */
    val name: String,
    /** Pricing/settlement currency code (e.g. "NPR", "USD") */
    val currency: String = "NPR",
    /** Unit of measure */
    val unit: AssetUnit = AssetUnit.UNIT_SHARES,
    /** Metal purity if applicable (e.g. "24K", "22K", "999") */
    val purity: String? = null,
    /** Sector or asset subcategory (e.g. "Commercial Banks", "Physical", "Digital") */
    val category: String? = null,
    val notes: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)

/**
 * Immutable transaction record tracking every buy, sell, fee, or dividend event.
 */
@Entity(
    tableName = "investment_transactions",
    indices = [
        Index(value = ["assetId"]),
        Index(value = ["epochDay"]),
    ],
)
data class InvestmentTransaction(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val assetId: Long,
    val type: InvestmentTxType,
    /** Date of transaction in epoch days */
    val epochDay: Long,
    /** Quantity bought or sold (positive number, up to 4 decimal places for metals) */
    val quantity: Double,
    /** Price per unit in minor units (e.g. paisa/cents = price * 100) */
    val unitPriceMinor: Long,
    /** Brokerage commissions, SEBON fees, DP charge, or making charges in minor units */
    val feeMinor: Long = 0L,
    /** Total cash outlay for BUY (qty * price + fees) or net proceeds for SELL (qty * price - fees) */
    val totalAmountMinor: Long,
    val currency: String = "NPR",
    val unit: AssetUnit = AssetUnit.UNIT_SHARES,
    val notes: String = "",
    /** Optional link to a regular Leftovers cash/bank account */
    val linkedAccountId: Long? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)

/**
 * Local cache of latest market quote for an asset.
 */
@Entity(
    tableName = "market_price_snapshots",
    indices = [
        Index(value = ["symbol"], unique = true),
        Index(value = ["timestamp"]),
    ],
)
data class MarketPriceSnapshot(
    @PrimaryKey
    val symbol: String,
    /** Last Traded Price */
    val price: Double,
    val previousClose: Double? = null,
    val change: Double? = null,
    val percentChange: Double? = null,
    val high: Double? = null,
    val low: Double? = null,
    val volume: Long? = null,
    val turnover: Double? = null,
    val trades: Int? = null,
    val marketCap: Double? = null,
    val companyName: String? = null,
    val source: String = "manual",
    val isManual: Boolean = false,
    /** Epoch timestamp in milliseconds when this price was fetched or entered */
    val timestamp: Long = System.currentTimeMillis(),
    val tradingDate: String? = null,
    val currency: String = "NPR",
) {
    /** True if older than 15 minutes during trading day or older than 24 hours generally */
    fun isStale(currentTimeMillis: Long = System.currentTimeMillis(), maxAgeMillis: Long = 15 * 60 * 1000L): Boolean {
        return (currentTimeMillis - timestamp) > maxAgeMillis
    }
}

/**
 * Historical daily price point for charts and performance tracking.
 */
@Entity(
    tableName = "market_price_history",
    indices = [
        Index(value = ["symbol", "tradingDate"], unique = true),
        Index(value = ["symbol", "timestamp"]),
    ],
)
data class MarketPriceHistory(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val symbol: String,
    val price: Double,
    val tradingDate: String,
    val timestamp: Long,
    val source: String,
    val isClosing: Boolean = true,
)

/**
 * User watchlist for tracking securities without holding them.
 */
@Entity(
    tableName = "watchlist_items",
    indices = [
        Index(value = ["symbol"], unique = true),
    ],
)
data class WatchlistItem(
    @PrimaryKey
    val symbol: String,
    val name: String,
    val assetType: InvestmentType = InvestmentType.STOCK_NEPSE,
    val addedAt: Long = System.currentTimeMillis(),
)

/**
 * Computed valuation of a specific holding for display.
 */
data class HoldingItem(
    val asset: InvestmentAsset,
    val remainingQuantity: Double,
    val totalCostBasisMinor: Long,
    val averageCostPerUnitMinor: Long,
    val latestQuote: MarketPriceSnapshot?,
    val currentMarketValueMinor: Long?,
    val unrealizedProfitLossMinor: Long?,
    val unrealizedReturnPercent: Double?,
    val realizedProfitLossMinor: Long,
    val currency: String,
    val isStale: Boolean,
    val priceSource: PriceSourceType,
)

/**
 * Portfolio summary grouped by currency.
 */
data class CurrencyPortfolioSummary(
    val currency: String,
    val totalInvestedCostMinor: Long,
    val currentMarketValueMinor: Long,
    val unrealizedProfitLossMinor: Long,
    val unrealizedReturnPercent: Double?,
    val realizedProfitLossMinor: Long,
    val hasUnvaluedHoldings: Boolean = false,
    val holdingCount: Int = 0,
)
