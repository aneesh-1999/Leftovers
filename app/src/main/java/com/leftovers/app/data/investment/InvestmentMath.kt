package com.leftovers.app.data.investment

import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.abs

/**
 * Decimal-safe arithmetic engine for portfolio valuation, cost-basis allocation,
 * and unit conversion.
 */
object InvestmentMath {

    /** Documented standard South Asian bullion conversion: 1 Tola = 11.6638 grams */
    val GRAMS_PER_TOLA = BigDecimal("11.6638")

    /**
     * Converts a quantity from one unit to another.
     * Supports:
     * - SHARES to SHARES (1:1)
     * - GRAM to TOLA (grams / 11.6638)
     * - TOLA to GRAM (tola * 11.6638)
     */
    fun convertQuantity(quantity: Double, fromUnit: AssetUnit, toUnit: AssetUnit): Double {
        if (fromUnit == toUnit) return quantity
        val qtyBd = BigDecimal.valueOf(quantity)
        return when {
            fromUnit == AssetUnit.UNIT_TOLA && toUnit == AssetUnit.UNIT_GRAM -> {
                qtyBd.multiply(GRAMS_PER_TOLA).setScale(4, RoundingMode.HALF_UP).toDouble()
            }
            fromUnit == AssetUnit.UNIT_GRAM && toUnit == AssetUnit.UNIT_TOLA -> {
                qtyBd.divide(GRAMS_PER_TOLA, 6, RoundingMode.HALF_UP).setScale(4, RoundingMode.HALF_UP).toDouble()
            }
            else -> quantity
        }
    }

    /**
     * Calculates the total cost for a BUY transaction in minor units (cents/paisa).
     * Total = (Quantity × UnitPrice) + Fees
     */
    fun calculateBuyTotalMinor(quantity: Double, unitPriceMinor: Long, feeMinor: Long): Long {
        val qtyBd = BigDecimal.valueOf(quantity)
        val priceBd = BigDecimal.valueOf(unitPriceMinor)
        val gross = qtyBd.multiply(priceBd).setScale(0, RoundingMode.HALF_UP).toLong()
        return gross + feeMinor
    }

    /**
     * Calculates the net proceeds for a SELL transaction in minor units (cents/paisa).
     * Net Proceeds = (Quantity × UnitPrice) - Fees
     */
    fun calculateSellProceedsMinor(quantity: Double, unitPriceMinor: Long, feeMinor: Long): Long {
        val qtyBd = BigDecimal.valueOf(quantity)
        val priceBd = BigDecimal.valueOf(unitPriceMinor)
        val gross = qtyBd.multiply(priceBd).setScale(0, RoundingMode.HALF_UP).toLong()
        return maxOf(0L, gross - feeMinor)
    }

    /**
     * Computes the holding status for an asset from its chronological transaction history
     * using the standard weighted-average cost basis method.
     *
     * @param transactions Historical transactions for the asset, ordered chronologically (epochDay asc).
     * @param latestQuote Latest market price snapshot, if available.
     * @return Complete [HoldingItem] with remaining quantity, cost basis, realized and unrealized P/L.
     */
    fun computeHolding(
        asset: InvestmentAsset,
        transactions: List<InvestmentTransaction>,
        latestQuote: MarketPriceSnapshot?,
    ): HoldingItem {
        var currentQty = BigDecimal.ZERO
        var currentCostBasis = BigDecimal.ZERO
        var totalRealizedPL = BigDecimal.ZERO

        // Sort chronologically by date and id
        val sortedTxs = transactions.sortedWith(compareBy({ it.epochDay }, { it.id }))

        for (tx in sortedTxs) {
            when (tx.type) {
                InvestmentTxType.BUY, InvestmentTxType.DIVIDEND_BONUS -> {
                    val txQty = BigDecimal.valueOf(tx.quantity)
                    val txCost = BigDecimal.valueOf(tx.totalAmountMinor)
                    currentQty = currentQty.add(txQty)
                    currentCostBasis = currentCostBasis.add(txCost)
                }
                InvestmentTxType.SELL -> {
                    val sellQty = BigDecimal.valueOf(tx.quantity)
                    if (currentQty > BigDecimal.ZERO && sellQty > BigDecimal.ZERO) {
                        // Weighted-average cost basis allocation:
                        // allocatedCost = (sellQty / currentQty) * currentCostBasis
                        val fraction = sellQty.divide(currentQty, 12, RoundingMode.HALF_UP)
                        val allocatedCost = currentCostBasis.multiply(fraction).setScale(0, RoundingMode.HALF_UP)

                        val netProceeds = BigDecimal.valueOf(tx.totalAmountMinor)
                        val pl = netProceeds.subtract(allocatedCost)
                        totalRealizedPL = totalRealizedPL.add(pl)

                        currentQty = currentQty.subtract(sellQty)
                        currentCostBasis = currentCostBasis.subtract(allocatedCost)

                        // Avoid rounding residue if completely sold out
                        if (currentQty.compareTo(BigDecimal.ZERO) <= 0 || currentQty < BigDecimal("0.000001")) {
                            currentQty = BigDecimal.ZERO
                            currentCostBasis = BigDecimal.ZERO
                        }
                    }
                }
                InvestmentTxType.FEE -> {
                    // Direct holding expense (e.g. custodian or holding charges)
                    currentCostBasis = currentCostBasis.add(BigDecimal.valueOf(tx.totalAmountMinor))
                }
                InvestmentTxType.DIVIDEND_CASH -> {
                    // Cash dividends count directly towards realized gains
                    totalRealizedPL = totalRealizedPL.add(BigDecimal.valueOf(tx.totalAmountMinor))
                }
                InvestmentTxType.VALUATION_UPDATE -> {
                    // Manual book valuation update
                }
            }
        }

        val remainingQtyDouble = currentQty.setScale(4, RoundingMode.HALF_UP).toDouble()
        val costBasisMinor = maxOf(0L, currentCostBasis.setScale(0, RoundingMode.HALF_UP).toLong())
        val realizedPLMinor = totalRealizedPL.setScale(0, RoundingMode.HALF_UP).toLong()

        val avgCostPerUnitMinor: Long = if (remainingQtyDouble > 0.0) {
            BigDecimal.valueOf(costBasisMinor)
                .divide(currentQty, 0, RoundingMode.HALF_UP)
                .toLong()
        } else 0L

        // Current market value and unrealized profit/loss calculation
        var marketValueMinor: Long? = null
        var unrealizedPLMinor: Long? = null
        var returnPercent: Double? = null
        var isStale = false
        var priceSource = PriceSourceType.MANUAL

        if (latestQuote != null && remainingQtyDouble > 0.0) {
            isStale = latestQuote.isStale()
            priceSource = when {
                latestQuote.isManual -> PriceSourceType.MANUAL
                isStale -> PriceSourceType.CACHED_STALE
                else -> PriceSourceType.LIVE
            }

            // Market price per unit in minor units (price is in main currency, multiply by 100)
            val ltpMinor = BigDecimal.valueOf(latestQuote.price)
                .multiply(BigDecimal.valueOf(100))

            // For metals, handle unit alignment between asset holding unit and quote unit if needed
            val mv = currentQty.multiply(ltpMinor).setScale(0, RoundingMode.HALF_UP).toLong()
            marketValueMinor = mv

            val upl = mv - costBasisMinor
            unrealizedPLMinor = upl

            if (costBasisMinor > 0L) {
                returnPercent = BigDecimal.valueOf(upl)
                    .multiply(BigDecimal.valueOf(100))
                    .divide(BigDecimal.valueOf(costBasisMinor), 2, RoundingMode.HALF_UP)
                    .toDouble()
            }
        }

        return HoldingItem(
            asset = asset,
            remainingQuantity = remainingQtyDouble,
            totalCostBasisMinor = costBasisMinor,
            averageCostPerUnitMinor = avgCostPerUnitMinor,
            latestQuote = latestQuote,
            currentMarketValueMinor = marketValueMinor,
            unrealizedProfitLossMinor = unrealizedPLMinor,
            unrealizedReturnPercent = returnPercent,
            realizedProfitLossMinor = realizedPLMinor,
            currency = asset.currency,
            isStale = isStale,
            priceSource = priceSource,
        )
    }

    /**
     * Groups holdings strictly by their native currency.
     * NEVER merges NPR, EUR, USD or others.
     */
    fun summarizeByCurrency(holdings: List<HoldingItem>): Map<String, CurrencyPortfolioSummary> {
        val byCurrency = holdings.groupBy { it.currency }
        return byCurrency.mapValues { (curr, items) ->
            var totalCost = 0L
            var totalMarketValue = 0L
            var totalRealized = 0L
            var unvalued = false
            var activeHoldingCount = 0

            for (h in items) {
                if (h.remainingQuantity > 0.0) {
                    activeHoldingCount++
                    totalCost += h.totalCostBasisMinor
                    if (h.currentMarketValueMinor != null) {
                        totalMarketValue += h.currentMarketValueMinor
                    } else {
                        unvalued = true
                    }
                }
                totalRealized += h.realizedProfitLossMinor
            }

            val unrealizedPL = totalMarketValue - totalCost
            val returnPct: Double? = if (totalCost > 0L && !unvalued) {
                BigDecimal.valueOf(unrealizedPL)
                    .multiply(BigDecimal.valueOf(100))
                    .divide(BigDecimal.valueOf(totalCost), 2, RoundingMode.HALF_UP)
                    .toDouble()
            } else null

            CurrencyPortfolioSummary(
                currency = curr,
                totalInvestedCostMinor = totalCost,
                currentMarketValueMinor = totalMarketValue,
                unrealizedProfitLossMinor = unrealizedPL,
                unrealizedReturnPercent = returnPct,
                realizedProfitLossMinor = totalRealized,
                hasUnvaluedHoldings = unvalued,
                holdingCount = activeHoldingCount,
            )
        }
    }
}
