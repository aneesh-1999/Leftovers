package com.leftovers.app.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.leftovers.app.data.investment.AssetUnit
import com.leftovers.app.data.investment.CurrencyPortfolioSummary
import com.leftovers.app.data.investment.HoldingItem
import com.leftovers.app.data.investment.InvestmentMath
import com.leftovers.app.data.investment.InvestmentTransaction
import com.leftovers.app.data.investment.InvestmentTxType
import com.leftovers.app.data.investment.InvestmentType
import com.leftovers.app.data.investment.MarketPriceSnapshot
import com.leftovers.app.data.investment.PriceSourceType
import com.leftovers.app.data.investment.WatchlistItemWithQuote
import com.leftovers.app.ui.AppViewModelProvider
import com.leftovers.app.ui.components.Chip
import com.leftovers.app.ui.components.DockClearance
import com.leftovers.app.ui.components.EmptyState
import com.leftovers.app.ui.components.Glass
import com.leftovers.app.ui.components.GlassScreen
import com.leftovers.app.ui.components.IconTile
import com.leftovers.app.ui.components.PrimaryButton
import com.leftovers.app.ui.components.RoundButton
import com.leftovers.app.ui.components.RowDivider
import com.leftovers.app.ui.components.SectionHeader
import com.leftovers.app.ui.icons.Lucide
import com.leftovers.app.ui.theme.LocalAppColors
import com.leftovers.app.util.Money
import java.time.LocalDate

enum class WatchlistSortMode { SYMBOL, CHANGE_PCT, PRICE }
enum class MarketFilterMode(val label: String) {
    ALL("All"),
    GAINERS("Top Gainers"),
    LOSERS("Top Losers"),
    VOLUME("High Volume"),
    TURNOVER("Turnover"),
}

@Composable
fun InvestmentBadge(
    text: String,
    color: Color = LocalAppColors.current.accent,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .clip(RoundedCornerShape(8.dp))
            .background(color.copy(alpha = 0.15f))
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = color,
        )
    }
}

@Composable
fun InvestmentScreen(
    onBack: () -> Unit,
    viewModel: InvestmentViewModel = viewModel(factory = AppViewModelProvider.Factory),
) {
    val c = LocalAppColors.current
    val holdings by viewModel.holdings.collectAsStateWithLifecycle()
    val allHoldings by viewModel.allHoldings.collectAsStateWithLifecycle()
    val currencySummaries by viewModel.currencySummaries.collectAsStateWithLifecycle()
    val watchlist by viewModel.watchlist.collectAsStateWithLifecycle()
    val transactions by viewModel.transactions.collectAsStateWithLifecycle()
    val marketSnapshots by viewModel.marketSnapshots.collectAsStateWithLifecycle()
    val accounts by viewModel.accounts.collectAsStateWithLifecycle()
    val currentTab by viewModel.currentTab.collectAsStateWithLifecycle()
    val isRefreshing by viewModel.isRefreshing.collectAsStateWithLifecycle()
    val statusMessage by viewModel.statusMessage.collectAsStateWithLifecycle()

    val selectedAssetHistory by viewModel.selectedAssetHistory.collectAsStateWithLifecycle()
    val selectedAssetTransactions by viewModel.selectedAssetTransactions.collectAsStateWithLifecycle()
    val selectedStockSnapshot by viewModel.selectedStockSnapshot.collectAsStateWithLifecycle()
    val selectedStockHistory by viewModel.selectedStockHistory.collectAsStateWithLifecycle()

    var showAddSheet by remember { mutableStateOf(false) }
    var addSheetInitialType by remember { mutableStateOf(InvestmentType.STOCK_NEPSE) }
    var addSheetInitialSymbol by remember { mutableStateOf<String?>(null) }
    var selectedHoldingForDetail by remember { mutableStateOf<HoldingItem?>(null) }
    var selectedHoldingForSell by remember { mutableStateOf<HoldingItem?>(null) }
    var selectedHoldingForPriceUpdate by remember { mutableStateOf<HoldingItem?>(null) }
    var selectedStockForDetail by remember { mutableStateOf<MarketPriceSnapshot?>(null) }

    var marketSearchQuery by remember { mutableStateOf("") }
    var marketFilterMode by remember { mutableStateOf(MarketFilterMode.ALL) }
    var watchlistSort by remember { mutableStateOf(WatchlistSortMode.CHANGE_PCT) }

    // Pre-calculate sorted watchlist and filtered market quotes outside LazyColumn
    val sortedWatchlist = remember(watchlist, watchlistSort) {
        when (watchlistSort) {
            WatchlistSortMode.SYMBOL -> watchlist.sortedBy { it.item.symbol }
            WatchlistSortMode.CHANGE_PCT -> watchlist.sortedByDescending { it.quote?.percentChange ?: -999.0 }
            WatchlistSortMode.PRICE -> watchlist.sortedByDescending { it.quote?.price ?: 0.0 }
        }
    }

    val filteredMarketQuotes = remember(marketSnapshots, marketSearchQuery, marketFilterMode) {
        val baseList = if (marketSearchQuery.isBlank()) marketSnapshots else {
            marketSnapshots.filter {
                it.symbol.contains(marketSearchQuery, ignoreCase = true) ||
                (it.companyName?.contains(marketSearchQuery, ignoreCase = true) == true)
            }
        }
        when (marketFilterMode) {
            MarketFilterMode.ALL -> baseList.take(60)
            MarketFilterMode.GAINERS -> baseList.filter { (it.percentChange ?: 0.0) > 0.0 }.sortedByDescending { it.percentChange ?: 0.0 }.take(60)
            MarketFilterMode.LOSERS -> baseList.filter { (it.percentChange ?: 0.0) < 0.0 }.sortedBy { it.percentChange ?: 0.0 }.take(60)
            MarketFilterMode.VOLUME -> baseList.sortedByDescending { it.volume ?: 0L }.take(60)
            MarketFilterMode.TURNOVER -> baseList.sortedByDescending { it.turnover ?: 0.0 }.take(60)
        }
    }

    val marketAdvancingCount = remember(marketSnapshots) { marketSnapshots.count { (it.percentChange ?: 0.0) > 0.0 } }
    val marketDecliningCount = remember(marketSnapshots) { marketSnapshots.count { (it.percentChange ?: 0.0) < 0.0 } }
    val marketUnchangedCount = remember(marketSnapshots) { marketSnapshots.count { (it.percentChange ?: 0.0) == 0.0 } }
    val totalMarketTurnover = remember(marketSnapshots) { marketSnapshots.mapNotNull { it.turnover }.sum() }

    GlassScreen(
        title = "Investments",
        subtitle = "NEPSE · Gold · Silver",
        onBack = onBack,
        actions = {
            if (isRefreshing) {
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    strokeWidth = 2.dp,
                    color = c.accent,
                )
            } else {
                RoundButton(
                    icon = Lucide.RotateCcw,
                    contentDescription = "Refresh live prices",
                    onClick = { viewModel.refreshMarketData(force = true) },
                    size = 38.dp,
                )
            }
            RoundButton(
                icon = Lucide.Plus,
                contentDescription = "Add investment",
                onClick = {
                    addSheetInitialType = when (currentTab) {
                        InvestmentTab.GOLD -> InvestmentType.GOLD
                        InvestmentTab.SILVER -> InvestmentType.SILVER
                        else -> InvestmentType.STOCK_NEPSE
                    }
                    showAddSheet = true
                },
                size = 38.dp,
                tint = c.accent,
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = padding.calculateTopPadding() + 8.dp,
                bottom = DockClearance,
            ),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Status or Warning Banner
            if (statusMessage != null) {
                item {
                    val isOffline = statusMessage?.contains("Offline", ignoreCase = true) == true
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (isOffline) c.warning.copy(alpha = 0.12f) else c.accent.copy(alpha = 0.12f))
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                if (isOffline) Lucide.CircleAlert else Lucide.Info,
                                contentDescription = null,
                                tint = if (isOffline) c.warning else c.accent,
                                modifier = Modifier.size(16.dp),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                statusMessage!!,
                                style = MaterialTheme.typography.bodySmall,
                                color = if (isOffline) c.warning else c.accent,
                            )
                        }
                    }
                }
            }

            // Currency Portfolio Carousel (Isolated by currency, never summed!)
            if (currencySummaries.isNotEmpty()) {
                item {
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        items(currencySummaries) { summary ->
                            PortfolioHeroCard(summary)
                        }
                    }
                }
            } else if (holdings.isEmpty()) {
                item {
                    EmptyPortfolioHero(
                        onAdd = {
                            addSheetInitialType = InvestmentType.STOCK_NEPSE
                            showAddSheet = true
                        },
                    )
                }
            }

            // Navigation Tabs / Sections
            item {
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    items(InvestmentTab.values()) { tab ->
                        val isSelected = currentTab == tab
                        Glass(
                            modifier = Modifier.clip(RoundedCornerShape(20.dp)),
                            tint = if (isSelected) c.accent.copy(alpha = 0.22f) else null,
                            onClick = { viewModel.setTab(tab) },
                        ) {
                            Text(
                                tab.label,
                                Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                color = if (isSelected) c.accent else c.textSecondary,
                            )
                        }
                    }
                }
            }

            // Tab Content
            when (currentTab) {
                InvestmentTab.OVERVIEW -> {
                    val nepseHoldings = holdings.filter { it.asset.type == InvestmentType.STOCK_NEPSE }
                    val goldHoldings = holdings.filter { it.asset.type == InvestmentType.GOLD }
                    val silverHoldings = holdings.filter { it.asset.type == InvestmentType.SILVER }

                    if (nepseHoldings.isNotEmpty()) {
                        item {
                            SectionHeader(
                                title = "NEPSE Shares (${nepseHoldings.size})",
                                action = "Buy",
                                onAction = {
                                    addSheetInitialType = InvestmentType.STOCK_NEPSE
                                    showAddSheet = true
                                },
                            )
                        }
                        items(nepseHoldings) { holding ->
                            HoldingRow(
                                holding = holding,
                                onClick = {
                                    viewModel.selectAsset(holding.asset.id)
                                    selectedHoldingForDetail = holding
                                },
                            )
                        }
                    }

                    if (goldHoldings.isNotEmpty()) {
                        item {
                            SectionHeader(
                                title = "Gold Portfolio",
                                action = "Add Gold",
                                onAction = {
                                    addSheetInitialType = InvestmentType.GOLD
                                    showAddSheet = true
                                },
                            )
                        }
                        items(goldHoldings) { holding ->
                            HoldingRow(
                                holding = holding,
                                onClick = {
                                    viewModel.selectAsset(holding.asset.id)
                                    selectedHoldingForDetail = holding
                                },
                            )
                        }
                    }

                    if (silverHoldings.isNotEmpty()) {
                        item {
                            SectionHeader(
                                title = "Silver Portfolio",
                                action = "Add Silver",
                                onAction = {
                                    addSheetInitialType = InvestmentType.SILVER
                                    showAddSheet = true
                                },
                            )
                        }
                        items(silverHoldings) { holding ->
                            HoldingRow(
                                holding = holding,
                                onClick = {
                                    viewModel.selectAsset(holding.asset.id)
                                    selectedHoldingForDetail = holding
                                },
                            )
                        }
                    }

                    if (holdings.isEmpty()) {
                        item {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                EmptyState(
                                    icon = Lucide.TrendingUp,
                                    title = "No investments yet",
                                    message = "Start building your portfolio by recording your NEPSE shares, gold, or silver.",
                                )
                                Spacer(Modifier.height(12.dp))
                                PrimaryButton(
                                    text = "Add Investment",
                                    onClick = {
                                        addSheetInitialType = InvestmentType.STOCK_NEPSE
                                        showAddSheet = true
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        }
                    }
                }

                InvestmentTab.NEPSE -> {
                    val nepseHoldings = holdings.filter { it.asset.type == InvestmentType.STOCK_NEPSE }
                    if (nepseHoldings.isEmpty()) {
                        item {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                EmptyState(
                                    icon = Lucide.Landmark,
                                    title = "No NEPSE shares",
                                    message = "Track your NEPSE share purchases, LTP, and profit & loss.",
                                )
                                Spacer(Modifier.height(12.dp))
                                PrimaryButton(
                                    text = "Buy NEPSE Share",
                                    onClick = {
                                        addSheetInitialType = InvestmentType.STOCK_NEPSE
                                        showAddSheet = true
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        }
                    } else {
                        items(nepseHoldings) { holding ->
                            HoldingRow(
                                holding = holding,
                                onClick = {
                                    viewModel.selectAsset(holding.asset.id)
                                    selectedHoldingForDetail = holding
                                },
                            )
                        }
                    }
                }

                InvestmentTab.GOLD -> {
                    val goldHoldings = holdings.filter { it.asset.type == InvestmentType.GOLD }
                    if (goldHoldings.isEmpty()) {
                        item {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                EmptyState(
                                    icon = Lucide.Coins,
                                    title = "No gold holdings",
                                    message = "Track physical or digital gold in tola or grams with live or manual valuation.",
                                )
                                Spacer(Modifier.height(12.dp))
                                PrimaryButton(
                                    text = "Add Gold",
                                    onClick = {
                                        addSheetInitialType = InvestmentType.GOLD
                                        showAddSheet = true
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        }
                    } else {
                        items(goldHoldings) { holding ->
                            HoldingRow(
                                holding = holding,
                                onClick = {
                                    viewModel.selectAsset(holding.asset.id)
                                    selectedHoldingForDetail = holding
                                },
                            )
                        }
                    }
                }

                InvestmentTab.SILVER -> {
                    val silverHoldings = holdings.filter { it.asset.type == InvestmentType.SILVER }
                    if (silverHoldings.isEmpty()) {
                        item {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                EmptyState(
                                    icon = Lucide.Coins,
                                    title = "No silver holdings",
                                    message = "Track silver bullion and coins with purity and current rates.",
                                )
                                Spacer(Modifier.height(12.dp))
                                PrimaryButton(
                                    text = "Add Silver",
                                    onClick = {
                                        addSheetInitialType = InvestmentType.SILVER
                                        showAddSheet = true
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        }
                    } else {
                        items(silverHoldings) { holding ->
                            HoldingRow(
                                holding = holding,
                                onClick = {
                                    viewModel.selectAsset(holding.asset.id)
                                    selectedHoldingForDetail = holding
                                },
                            )
                        }
                    }
                }

                InvestmentTab.WATCHLIST -> {
                    item {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                "Watched Symbols (${watchlist.size})",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = c.textPrimary,
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                WatchlistSortMode.values().forEach { mode ->
                                    val isSelected = watchlistSort == mode
                                    Glass(
                                        modifier = Modifier.clip(RoundedCornerShape(10.dp)),
                                        tint = if (isSelected) c.accent.copy(alpha = 0.2f) else null,
                                        onClick = { watchlistSort = mode },
                                    ) {
                                        Text(
                                            when (mode) {
                                                WatchlistSortMode.SYMBOL -> "Symbol"
                                                WatchlistSortMode.CHANGE_PCT -> "% Change"
                                                WatchlistSortMode.PRICE -> "LTP"
                                            },
                                            Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = if (isSelected) c.accent else c.textTertiary,
                                        )
                                    }
                                }
                            }
                        }
                    }

                    if (watchlist.isEmpty()) {
                        item {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                EmptyState(
                                    icon = Lucide.Search,
                                    title = "Watchlist is empty",
                                    message = "Browse the NEPSE Market tab to add symbols to your watchlist.",
                                )
                                Spacer(Modifier.height(12.dp))
                                PrimaryButton(
                                    text = "Browse Market",
                                    onClick = { viewModel.setTab(InvestmentTab.MARKET) },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        }
                    } else {
                        items(sortedWatchlist) { item ->
                            WatchlistRow(
                                item = item,
                                onRemove = { viewModel.toggleWatchlist(item.item.symbol, item.item.name) },
                                onClick = {
                                    val q = item.quote ?: MarketPriceSnapshot(
                                        symbol = item.item.symbol,
                                        price = 0.0,
                                        companyName = item.item.name,
                                    )
                                    selectedStockForDetail = q
                                    viewModel.selectStockForDetail(item.item.symbol)
                                },
                            )
                        }
                    }
                }

                InvestmentTab.MARKET -> {
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            InvestmentTextField(
                                value = marketSearchQuery,
                                onValueChange = { marketSearchQuery = it },
                                placeholder = "Search ${marketSnapshots.size} NEPSE stocks...",
                            )

                            // Quick Filter Chips
                            LazyRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                items(MarketFilterMode.values()) { mode ->
                                    val isSelected = marketFilterMode == mode
                                    Chip(
                                        text = mode.label,
                                        onClick = { marketFilterMode = mode },
                                        selected = isSelected,
                                    )
                                }
                            }
                        }
                    }

                    // Market Breadth / Overview Summary
                    if (marketSnapshots.isNotEmpty()) {
                        item {
                            Glass(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(16.dp),
                            ) {
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .padding(14.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Text("Market Breadth", style = MaterialTheme.typography.labelMedium, color = c.textSecondary)
                                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                            InvestmentBadge("▲ $marketAdvancingCount", c.positive)
                                            InvestmentBadge("▼ $marketDecliningCount", c.negative)
                                            if (marketUnchangedCount > 0) {
                                                InvestmentBadge("— $marketUnchangedCount", c.textTertiary)
                                            }
                                        }
                                    }

                                    if (totalMarketTurnover > 0.0) {
                                        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                            Text("Total Turnover", style = MaterialTheme.typography.labelSmall, color = c.textTertiary)
                                            Text(
                                                "Rs ${formatCompactNumber(totalMarketTurnover)}",
                                                style = MaterialTheme.typography.titleMedium,
                                                fontWeight = FontWeight.Bold,
                                                color = c.textPrimary,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    if (filteredMarketQuotes.isEmpty()) {
                        item {
                            EmptyState(
                                icon = Lucide.Search,
                                title = "No stocks match filter",
                                message = "Try searching by another ticker or selecting another category.",
                            )
                        }
                    } else {
                        items(filteredMarketQuotes) { quote ->
                            MarketQuoteRow(
                                quote = quote,
                                isWatched = watchlist.any { it.item.symbol == quote.symbol },
                                onToggleWatch = { viewModel.toggleWatchlist(quote.symbol, quote.companyName ?: quote.symbol) },
                                onClick = {
                                    selectedStockForDetail = quote
                                    viewModel.selectStockForDetail(quote.symbol)
                                },
                            )
                        }
                    }
                }

                InvestmentTab.HISTORY -> {
                    if (transactions.isEmpty()) {
                        item {
                            EmptyState(
                                icon = Lucide.History,
                                title = "No transactions logged",
                                message = "All your buys, sales, fees, and valuation adjustments will appear here.",
                            )
                        }
                    } else {
                        items(transactions) { tx ->
                            val money = Money(tx.currency)
                            Glass(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(14.dp),
                            ) {
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .padding(14.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            InvestmentBadge(
                                                text = tx.type.name,
                                                color = when (tx.type) {
                                                    InvestmentTxType.BUY -> c.accent
                                                    InvestmentTxType.SELL -> c.positive
                                                    InvestmentTxType.FEE -> c.warning
                                                    else -> c.textSecondary
                                                },
                                            )
                                            Spacer(Modifier.width(8.dp))
                                            Text(
                                                LocalDate.ofEpochDay(tx.epochDay).toString(),
                                                style = MaterialTheme.typography.bodySmall,
                                                color = c.textTertiary,
                                            )
                                        }
                                        Text(
                                            "${tx.quantity} ${tx.unit.label} @ ${money.format(tx.unitPriceMinor)}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = c.textSecondary,
                                        )
                                        if (tx.notes.isNotBlank()) {
                                            Text(tx.notes, style = MaterialTheme.typography.labelSmall, color = c.textTertiary)
                                        }
                                    }

                                    Column(horizontalAlignment = Alignment.End) {
                                        Text(
                                            money.format(tx.totalAmountMinor),
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.Bold,
                                            color = c.textPrimary,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // Sheets & Dialogs
    if (showAddSheet) {
        AddInvestmentSheet(
            marketSnapshots = marketSnapshots,
            accounts = accounts,
            initialType = addSheetInitialType,
            initialSymbol = addSheetInitialSymbol,
            onDismiss = {
                showAddSheet = false
                addSheetInitialSymbol = null
            },
            onBuyStock = { sym, name, date, qty, price, fees, notes, accId ->
                viewModel.buyStock(sym, name, date, qty, price, fees, notes, accId)
            },
            onBuyBullion = { type, name, unit, purity, curr, date, qty, price, fees, seller, notes, accId ->
                viewModel.buyBullion(type, name, unit, purity, curr, date, qty, price, fees, seller, notes, accId)
            },
        )
    }

    if (selectedStockForDetail != null) {
        val snapshotToDisplay = selectedStockSnapshot ?: selectedStockForDetail!!
        val userHolding = allHoldings.firstOrNull { it.asset.symbol.equals(snapshotToDisplay.symbol, ignoreCase = true) }
        StockMarketDetailSheet(
            snapshot = snapshotToDisplay,
            history = selectedStockHistory,
            holding = userHolding,
            isWatched = watchlist.any { it.item.symbol.equals(snapshotToDisplay.symbol, ignoreCase = true) },
            onDismiss = {
                selectedStockForDetail = null
                viewModel.selectStockForDetail(null)
            },
            onBuy = {
                val sym = snapshotToDisplay.symbol
                selectedStockForDetail = null
                viewModel.selectStockForDetail(null)
                addSheetInitialType = InvestmentType.STOCK_NEPSE
                addSheetInitialSymbol = sym
                showAddSheet = true
            },
            onSell = userHolding?.let { h ->
                {
                    selectedStockForDetail = null
                    viewModel.selectStockForDetail(null)
                    selectedHoldingForSell = h
                }
            },
            onToggleWatch = {
                viewModel.toggleWatchlist(snapshotToDisplay.symbol, snapshotToDisplay.companyName ?: snapshotToDisplay.symbol)
            },
        )
    }

    if (selectedHoldingForDetail != null) {
        HoldingDetailSheet(
            holding = selectedHoldingForDetail!!,
            transactions = selectedAssetTransactions,
            priceHistory = selectedAssetHistory,
            onDismiss = {
                selectedHoldingForDetail = null
                viewModel.selectAsset(null)
            },
            onBuyMore = {
                val h = selectedHoldingForDetail!!
                selectedHoldingForDetail = null
                addSheetInitialType = h.asset.type
                showAddSheet = true
            },
            onSell = {
                val h = selectedHoldingForDetail!!
                selectedHoldingForDetail = null
                selectedHoldingForSell = h
            },
            onUpdatePrice = {
                val h = selectedHoldingForDetail!!
                selectedHoldingForDetail = null
                selectedHoldingForPriceUpdate = h
            },
            onDeleteHolding = {
                val h = selectedHoldingForDetail!!
                viewModel.deleteAsset(h.asset)
                selectedHoldingForDetail = null
                viewModel.selectAsset(null)
            },
            onDeleteTransaction = { txId ->
                viewModel.deleteTransaction(txId)
            },
        )
    }

    if (selectedHoldingForSell != null) {
        SellInvestmentSheet(
            holding = selectedHoldingForSell!!,
            accounts = accounts,
            onDismiss = { selectedHoldingForSell = null },
            onConfirmSale = { date, qty, price, fees, notes, accId ->
                val h = selectedHoldingForSell!!
                if (h.asset.type == InvestmentType.STOCK_NEPSE) {
                    viewModel.sellStock(h.asset.id, date, qty, price, fees, notes, accId)
                } else {
                    viewModel.sellBullion(h.asset.id, date, qty, price, fees, notes, accId)
                }
            },
        )
    }

    if (selectedHoldingForPriceUpdate != null) {
        ManualPriceSheet(
            holding = selectedHoldingForPriceUpdate!!,
            onDismiss = { selectedHoldingForPriceUpdate = null },
            onUpdatePrice = { newPrice, currency ->
                val h = selectedHoldingForPriceUpdate!!
                viewModel.updateManualPrice(h.asset.symbol, newPrice, currency, h.asset.name)
            },
        )
    }
}

@Composable
private fun PortfolioHeroCard(summary: CurrencyPortfolioSummary) {
    val c = LocalAppColors.current
    val curr = summary.currency
    val money = Money(curr)
    val isProfit = summary.unrealizedProfitLossMinor >= 0L

    Glass(
        modifier = Modifier
            .width(320.dp)
            .clip(RoundedCornerShape(24.dp)),
        strong = true,
    ) {
        Column(
            Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                InvestmentBadge(text = curr, color = c.accent)
                if (summary.unrealizedReturnPercent != null) {
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background((if (isProfit) c.positive else c.negative).copy(alpha = 0.15f))
                            .padding(horizontal = 10.dp, vertical = 4.dp),
                    ) {
                        Text(
                            "${if (isProfit) "+" else ""}${String.format("%.2f", summary.unrealizedReturnPercent)}%",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = if (isProfit) c.positive else c.negative,
                        )
                    }
                }
            }

            Column {
                Text("Current Market Value", style = MaterialTheme.typography.labelSmall, color = c.textTertiary)
                Text(
                    money.format(summary.currentMarketValueMinor),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = c.textPrimary,
                )
            }

            RowDivider()

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column {
                    Text("Invested Cost", style = MaterialTheme.typography.labelSmall, color = c.textTertiary)
                    Text(
                        money.format(summary.totalInvestedCostMinor),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = c.textSecondary,
                    )
                }

                Column(horizontalAlignment = Alignment.End) {
                    Text("Unrealized P/L", style = MaterialTheme.typography.labelSmall, color = c.textTertiary)
                    Text(
                        "${if (isProfit) "+" else ""}${money.format(summary.unrealizedProfitLossMinor)}",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        color = if (isProfit) c.positive else c.negative,
                    )
                }
            }

            if (summary.realizedProfitLossMinor != 0L) {
                val realProfit = summary.realizedProfitLossMinor >= 0L
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Realized Gain/Loss", style = MaterialTheme.typography.labelSmall, color = c.textTertiary)
                    Text(
                        "${if (realProfit) "+" else ""}${money.format(summary.realizedProfitLossMinor)}",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = if (realProfit) c.positive else c.negative,
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyPortfolioHero(onAdd: () -> Unit) {
    val c = LocalAppColors.current
    Glass(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp)),
        strong = true,
    ) {
        Column(
            Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            IconTile(Lucide.TrendingUp, c.accent, size = 52.dp)
            Text(
                "Track Your Wealth",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = c.textPrimary,
            )
            Text(
                "Monitor NEPSE shares with live LTP updates, track Gold & Silver in tola or grams, and view unrealized returns safely offline.",
                style = MaterialTheme.typography.bodyMedium,
                color = c.textSecondary,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(4.dp))
            PrimaryButton("Add First Investment", onAdd, Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun HoldingRow(
    holding: HoldingItem,
    onClick: () -> Unit,
) {
    val c = LocalAppColors.current
    val asset = holding.asset
    val quote = holding.latestQuote
    val curr = holding.currency
    val money = Money(curr)
    val unrealizedPL = holding.unrealizedProfitLossMinor
    val isProfit = (unrealizedPL ?: 0L) >= 0L

    Glass(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp)),
        onClick = onClick,
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1.2f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        asset.symbol,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = c.textPrimary,
                    )
                    if (asset.type != InvestmentType.STOCK_NEPSE && asset.purity != null) {
                        Spacer(Modifier.width(6.dp))
                        Text(
                            asset.purity,
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFFD4AF37),
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
                Text(
                    asset.name,
                    style = MaterialTheme.typography.bodySmall,
                    color = c.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "${holding.remainingQuantity} ${asset.unit.label} · Avg ${money.format(holding.averageCostPerUnitMinor)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = c.textTertiary,
                )
            }

            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    holding.currentMarketValueMinor?.let { money.format(it) } ?: "Pending",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = c.textPrimary,
                )
                if (unrealizedPL != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "${if (isProfit) "+" else ""}${money.format(unrealizedPL)}",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold,
                            color = if (isProfit) c.positive else c.negative,
                        )
                        if (holding.unrealizedReturnPercent != null) {
                            Spacer(Modifier.width(4.dp))
                            Text(
                                "(${if (isProfit) "+" else ""}${String.format("%.1f", holding.unrealizedReturnPercent)}%)",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (isProfit) c.positive else c.negative,
                            )
                        }
                    }
                }
                if (holding.isStale) {
                    Text("Cached", style = MaterialTheme.typography.labelSmall, color = c.warning)
                }
            }
        }
    }
}

@Composable
private fun WatchlistRow(
    item: WatchlistItemWithQuote,
    onRemove: () -> Unit,
    onClick: () -> Unit,
) {
    val c = LocalAppColors.current
    val quote = item.quote

    Glass(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp)),
        onClick = onClick,
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    item.item.symbol,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = c.textPrimary,
                )
                Text(
                    item.item.name,
                    style = MaterialTheme.typography.bodySmall,
                    color = c.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            if (quote != null && quote.price > 0.0) {
                val isPositive = (quote.percentChange ?: 0.0) >= 0.0
                Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        "Rs ${quote.price}",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = c.textPrimary,
                    )
                    quote.percentChange?.let { pct ->
                        Box(
                            Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background((if (isPositive) c.positive else c.negative).copy(alpha = 0.15f))
                                .padding(horizontal = 8.dp, vertical = 2.dp),
                        ) {
                            Text(
                                "${if (isPositive) "+" else ""}${String.format("%.2f", pct)}%",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = if (isPositive) c.positive else c.negative,
                            )
                        }
                    }
                }
            } else {
                Text("Price N/A", style = MaterialTheme.typography.labelMedium, color = c.textTertiary)
            }

            Spacer(Modifier.width(8.dp))
            RoundButton(Lucide.Trash2, "Remove", onRemove, size = 32.dp, tint = c.textTertiary)
        }
    }
}

@Composable
private fun MarketQuoteRow(
    quote: MarketPriceSnapshot,
    isWatched: Boolean,
    onToggleWatch: () -> Unit,
    onClick: () -> Unit,
) {
    val c = LocalAppColors.current
    val changePos = (quote.percentChange ?: 0.0) >= 0.0

    Glass(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp)),
        onClick = onClick,
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(quote.symbol, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = c.textPrimary)
                Text(quote.companyName ?: quote.symbol, style = MaterialTheme.typography.bodySmall, color = c.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (quote.volume != null) {
                    Text("Vol: ${quote.volume}", style = MaterialTheme.typography.labelSmall, color = c.textTertiary)
                }
            }

            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Rs ${quote.price}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = c.textPrimary)
                if (quote.percentChange != null) {
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background((if (changePos) c.positive else c.negative).copy(alpha = 0.15f))
                            .padding(horizontal = 8.dp, vertical = 2.dp),
                    ) {
                        Text(
                            "${if (changePos) "+" else ""}${String.format("%.2f", quote.percentChange)}%",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = if (changePos) c.positive else c.negative,
                        )
                    }
                }
            }

            Spacer(Modifier.width(10.dp))
            RoundButton(
                icon = if (isWatched) Lucide.Check else Lucide.Plus,
                contentDescription = if (isWatched) "Watched" else "Add to Watchlist",
                onClick = onToggleWatch,
                size = 32.dp,
                tint = if (isWatched) c.accent else c.textTertiary,
            )
        }
    }
}

private fun formatCompactNumber(value: Double): String {
    return when {
        value >= 10_000_000 -> String.format("%.2f Cr", value / 10_000_000.0)
        value >= 100_000 -> String.format("%.2f L", value / 100_000.0)
        value >= 1_000 -> String.format("%.1f K", value / 1_000.0)
        else -> String.format("%.0f", value)
    }
}
