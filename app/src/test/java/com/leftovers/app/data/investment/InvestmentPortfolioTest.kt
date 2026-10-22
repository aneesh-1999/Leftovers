package com.leftovers.app.data.investment

import com.leftovers.app.data.investment.provider.MarketQuote
import com.leftovers.app.data.investment.provider.ShareBazaarProvider
import com.leftovers.app.data.investment.provider.YoNepseProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.math.BigDecimal

class InvestmentPortfolioTest {

    // --- JSON PARSING TESTS ---

    @Test
    fun shareBazaarParser_parsesValidResponseCorrectly() {
        val provider = ShareBazaarProvider()
        val json = """{"id":"NABIL","symbol":"NABIL","company_name":"Nabil Bank Limited","ltp":536.2,"last_updated":"2026-10-11T19:05:51.972Z"}"""

        val quote = provider.parseQuoteJson(json, "NABIL")

        assertEquals("NABIL", quote.symbol)
        assertEquals("Nabil Bank Limited", quote.companyName)
        assertEquals(536.2, quote.ltp, 0.001)
        assertEquals("2026-10-11T19:05:51.972Z", quote.lastUpdated)
        assertEquals("sharebazaar", quote.dataSource)
        assertNull(quote.previousClose)
        assertNull(quote.change)
        assertNull(quote.percentChange)
    }

    @Test
    fun shareBazaarParser_handlesMissingOptionalFields() {
        val provider = ShareBazaarProvider()
        // Missing company_name and last_updated
        val json = """{"symbol":"GBIME","ltp":245.0}"""

        val quote = provider.parseQuoteJson(json, "GBIME")

        assertEquals("GBIME", quote.symbol)
        assertNull(quote.companyName)
        assertEquals(245.0, quote.ltp, 0.001)
        assertNull(quote.lastUpdated)
    }

    @Test(expected = IllegalArgumentException::class)
    fun shareBazaarParser_failsDefensivelyOnMissingLtp() {
        val provider = ShareBazaarProvider()
        val json = """{"symbol":"NABIL","company_name":"Nabil Bank"}"""
        provider.parseQuoteJson(json, "NABIL")
    }

    @Test(expected = Exception::class)
    fun shareBazaarParser_failsDefensivelyOnMalformedJson() {
        val provider = ShareBazaarProvider()
        provider.parseQuoteJson("{not_valid_json", "NABIL")
    }

    @Test
    fun yoNepseParser_parsesBulkArrayAndRowsCorrectly() {
        val provider = YoNepseProvider()
        val json = """
            [
                {
                    "symbol": "ACLBSL",
                    "name": "Aarambha Chautari Laghubitta",
                    "ltp": 857.0,
                    "previous_close": 873.0,
                    "change": -16.0,
                    "percent_change": -1.83,
                    "high": 865.0,
                    "low": 855.1,
                    "volume": 908,
                    "turnover": 781832.4,
                    "trades": 26,
                    "last_updated": "2026-10-09T14:57:11",
                    "market_cap": 3146.42
                },
                {
                    "symbol": "SHIVM",
                    "name": "Shivam Cements Limited",
                    "ltp": 510.0,
                    "previous_close": 500.0
                }
            ]
        """.trimIndent()

        val list = provider.parseBulkJson(json)
        assertEquals(2, list.size)

        val aclbsl = list[0]
        assertEquals("ACLBSL", aclbsl.symbol)
        assertEquals("Aarambha Chautari Laghubitta", aclbsl.companyName)
        assertEquals(857.0, aclbsl.ltp, 0.001)
        assertEquals(873.0, aclbsl.previousClose!!, 0.001)
        assertEquals(-16.0, aclbsl.change!!, 0.001)
        assertEquals(-1.83, aclbsl.percentChange!!, 0.01)
        assertEquals(865.0, aclbsl.high!!, 0.001)
        assertEquals(855.1, aclbsl.low!!, 0.001)
        assertEquals(908L, aclbsl.volume!!)
        assertEquals(781832.4, aclbsl.turnover!!, 0.001)
        assertEquals(26, aclbsl.trades!!)
        assertEquals(3146.42, aclbsl.marketCap!!, 0.001)

        val shivm = list[1]
        assertEquals("SHIVM", shivm.symbol)
        assertEquals(510.0, shivm.ltp, 0.001)
        assertEquals(500.0, shivm.previousClose!!, 0.001)
        // Derived change and percentChange
        assertEquals(10.0, shivm.change!!, 0.001)
        assertEquals(2.0, shivm.percentChange!!, 0.001)
        assertNull(shivm.high)
        assertNull(shivm.low)
        assertNull(shivm.volume)
    }

    // --- DERIVED FIELDS AND PRICE STALENESS ---

    @Test
    fun derivedFields_computesChangeAndPercentageCorrectly() {
        val quote = MarketQuote(
            symbol = "NABIL",
            ltp = 550.0,
            previousClose = 500.0,
            dataSource = "test",
        ).withDerivedFields()

        assertEquals(50.0, quote.change!!, 0.001)
        assertEquals(10.0, quote.percentChange!!, 0.001)
        assertNull(quote.high) // High/low must NEVER be derived
        assertNull(quote.low)
        assertNull(quote.volume)
    }

    @Test
    fun stalePriceDetection_identifiesOldSnapshotsCorrectly() {
        val now = System.currentTimeMillis()
        val freshSnapshot = MarketPriceSnapshot(
            symbol = "NABIL",
            price = 536.0,
            timestamp = now - (5 * 60 * 1000L), // 5 minutes old
        )
        assertFalse(freshSnapshot.isStale(now, maxAgeMillis = 15 * 60 * 1000L))

        val staleSnapshot = MarketPriceSnapshot(
            symbol = "NABIL",
            price = 536.0,
            timestamp = now - (30 * 60 * 1000L), // 30 minutes old
        )
        assertTrue(staleSnapshot.isStale(now, maxAgeMillis = 15 * 60 * 1000L))
    }

    // --- WEIGHTED-AVERAGE COST BASIS & HOLDING CALCULATIONS ---

    @Test
    fun weightedAverageCostBasis_calculatesMultipleBuysAndPartialSale() {
        val asset = InvestmentAsset(
            id = 1L,
            type = InvestmentType.STOCK_NEPSE,
            symbol = "NABIL",
            name = "Nabil Bank Limited",
            currency = "NPR",
            unit = AssetUnit.UNIT_SHARES,
        )

        // Buy 1: 100 shares @ 500 NPR with 25 NPR fee
        // Total cost = 100 * 50,000 + 2,500 = 5,002,500 minor (50,025 NPR)
        val buy1 = InvestmentTransaction(
            id = 1L,
            assetId = 1L,
            type = InvestmentTxType.BUY,
            epochDay = 100L,
            quantity = 100.0,
            unitPriceMinor = 50000L, // 500 NPR
            feeMinor = 2500L, // 25 NPR
            totalAmountMinor = InvestmentMath.calculateBuyTotalMinor(100.0, 50000L, 2500L),
        )
        assertEquals(5002500L, buy1.totalAmountMinor)

        // Buy 2: 100 shares @ 600 NPR with 25 NPR fee
        // Total cost = 100 * 60,000 + 2,500 = 6,002,500 minor (60,025 NPR)
        val buy2 = InvestmentTransaction(
            id = 2L,
            assetId = 1L,
            type = InvestmentTxType.BUY,
            epochDay = 110L,
            quantity = 100.0,
            unitPriceMinor = 60000L,
            feeMinor = 2500L,
            totalAmountMinor = InvestmentMath.calculateBuyTotalMinor(100.0, 60000L, 2500L),
        )

        // Total holding before sale: 200 shares, total cost = 11,005,000 minor (110,050 NPR)
        // Average cost per share = 550.25 NPR
        val holdingBeforeSale = InvestmentMath.computeHolding(asset, listOf(buy1, buy2), null)
        assertEquals(200.0, holdingBeforeSale.remainingQuantity, 0.001)
        assertEquals(11005000L, holdingBeforeSale.totalCostBasisMinor)
        assertEquals(55025L, holdingBeforeSale.averageCostPerUnitMinor)

        // Sell 50 shares @ 700 NPR with 20 NPR fee
        // Gross proceeds = 50 * 70,000 = 3,500,000 minor (35,000 NPR)
        // Net proceeds = 3,500,000 - 2,000 = 3,498,000 minor (34,980 NPR)
        val sellProceeds = InvestmentMath.calculateSellProceedsMinor(50.0, 70000L, 2000L)
        assertEquals(3498000L, sellProceeds)

        val sell1 = InvestmentTransaction(
            id = 3L,
            assetId = 1L,
            type = InvestmentTxType.SELL,
            epochDay = 120L,
            quantity = 50.0,
            unitPriceMinor = 70000L,
            feeMinor = 2000L,
            totalAmountMinor = sellProceeds,
        )

        // Allocated cost basis for 50 shares = 50 / 200 * 11,005,000 = 2,751,250 minor
        // Realized P/L = 3,498,000 - 2,751,250 = +746,750 minor (+7,467.50 NPR)
        // Remaining quantity = 150 shares
        // Remaining cost basis = 11,005,000 - 2,751,250 = 8,253,750 minor (82,537.50 NPR)
        val holdingAfterSale = InvestmentMath.computeHolding(asset, listOf(buy1, buy2, sell1), null)
        assertEquals(150.0, holdingAfterSale.remainingQuantity, 0.001)
        assertEquals(8253750L, holdingAfterSale.totalCostBasisMinor)
        assertEquals(746750L, holdingAfterSale.realizedProfitLossMinor)

        // Now value holding at market LTP = 750 NPR (75,000 minor)
        val quote = MarketPriceSnapshot(
            symbol = "NABIL",
            price = 750.0,
            timestamp = System.currentTimeMillis(),
        )
        val valuedHolding = InvestmentMath.computeHolding(asset, listOf(buy1, buy2, sell1), quote)

        // Market value = 150 * 75,000 = 11,250,000 minor (112,500 NPR)
        // Unrealized P/L = 11,250,000 - 8,253,750 = +2,996,250 minor (+29,962.50 NPR)
        // Unrealized Return % = 2,996,250 / 8,253,750 * 100 = 36.30%
        assertEquals(11250000L, valuedHolding.currentMarketValueMinor!!)
        assertEquals(2996250L, valuedHolding.unrealizedProfitLossMinor!!)
        assertEquals(36.30, valuedHolding.unrealizedReturnPercent!!, 0.05)
        assertEquals(746750L, valuedHolding.realizedProfitLossMinor)
    }

    @Test
    fun fullSale_resetsCostBasisAndPreservesRealizedGain() {
        val asset = InvestmentAsset(
            id = 1L,
            type = InvestmentType.STOCK_NEPSE,
            symbol = "NABIL",
            name = "Nabil Bank",
        )
        val buy = InvestmentTransaction(
            id = 1L,
            assetId = 1L,
            type = InvestmentTxType.BUY,
            epochDay = 100L,
            quantity = 10.0,
            unitPriceMinor = 10000L, // 100 NPR
            feeMinor = 0L,
            totalAmountMinor = 100000L, // 1,000 NPR
        )
        val sell = InvestmentTransaction(
            id = 2L,
            assetId = 1L,
            type = InvestmentTxType.SELL,
            epochDay = 110L,
            quantity = 10.0,
            unitPriceMinor = 12000L, // 120 NPR
            feeMinor = 0L,
            totalAmountMinor = 120000L, // 1,200 NPR
        )

        val holding = InvestmentMath.computeHolding(asset, listOf(buy, sell), null)

        assertEquals(0.0, holding.remainingQuantity, 0.0001)
        assertEquals(0L, holding.totalCostBasisMinor)
        assertEquals(20000L, holding.realizedProfitLossMinor) // +200 NPR gain
        assertNull(holding.currentMarketValueMinor)
        assertNull(holding.unrealizedProfitLossMinor)
    }

    // --- GOLD & SILVER BULLION CONVERSION ---

    @Test
    fun goldTolaToGramConversion_usesDocumentedConstant() {
        // 1 tola = 11.6638 grams
        val grams = InvestmentMath.convertQuantity(1.0, AssetUnit.UNIT_TOLA, AssetUnit.UNIT_GRAM)
        assertEquals(11.6638, grams, 0.0001)

        val tola = InvestmentMath.convertQuantity(11.6638, AssetUnit.UNIT_GRAM, AssetUnit.UNIT_TOLA)
        assertEquals(1.0, tola, 0.0001)

        // 5 tolas = 58.3190 grams
        val fiveTolasInGrams = InvestmentMath.convertQuantity(5.0, AssetUnit.UNIT_TOLA, AssetUnit.UNIT_GRAM)
        assertEquals(58.319, fiveTolasInGrams, 0.001)
    }

    // --- CURRENCY SEPARATION ---

    @Test
    fun currencySeparation_neverMergesDifferentCurrencies() {
        val nprAsset = InvestmentAsset(id = 1L, type = InvestmentType.STOCK_NEPSE, symbol = "NABIL", name = "Nabil", currency = "NPR")
        val usdAsset = InvestmentAsset(id = 2L, type = InvestmentType.GOLD, symbol = "GOLD-USD", name = "Gold ETF", currency = "USD")

        val nprBuy = InvestmentTransaction(id = 1L, assetId = 1L, type = InvestmentTxType.BUY, epochDay = 1L, quantity = 10.0, unitPriceMinor = 50000L, totalAmountMinor = 500000L, currency = "NPR")
        val usdBuy = InvestmentTransaction(id = 2L, assetId = 2L, type = InvestmentTxType.BUY, epochDay = 1L, quantity = 1.0, unitPriceMinor = 250000L, totalAmountMinor = 250000L, currency = "USD")

        val nprHolding = InvestmentMath.computeHolding(nprAsset, listOf(nprBuy), MarketPriceSnapshot(symbol = "NABIL", price = 600.0))
        val usdHolding = InvestmentMath.computeHolding(usdAsset, listOf(usdBuy), MarketPriceSnapshot(symbol = "GOLD-USD", price = 2700.0, currency = "USD"))

        val summaries = InvestmentMath.summarizeByCurrency(listOf(nprHolding, usdHolding))

        assertEquals(2, summaries.size)
        assertTrue(summaries.containsKey("NPR"))
        assertTrue(summaries.containsKey("USD"))

        val nprSummary = summaries["NPR"]!!
        assertEquals("NPR", nprSummary.currency)
        assertEquals(500000L, nprSummary.totalInvestedCostMinor) // 5,000 NPR
        assertEquals(600000L, nprSummary.currentMarketValueMinor) // 6,000 NPR
        assertEquals(100000L, nprSummary.unrealizedProfitLossMinor) // +1,000 NPR

        val usdSummary = summaries["USD"]!!
        assertEquals("USD", usdSummary.currency)
        assertEquals(250000L, usdSummary.totalInvestedCostMinor) // $2,500
        assertEquals(270000L, usdSummary.currentMarketValueMinor) // $2,700
        assertEquals(20000L, usdSummary.unrealizedProfitLossMinor) // +$200

        // Check that currencies are completely isolated
        assertEquals(1, nprSummary.holdingCount)
        assertEquals(1, usdSummary.holdingCount)
    }
}
