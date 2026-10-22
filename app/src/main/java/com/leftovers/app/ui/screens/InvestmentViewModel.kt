package com.leftovers.app.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.leftovers.app.data.AccountRepository
import com.leftovers.app.data.AccountWithBalance
import com.leftovers.app.data.Transaction
import com.leftovers.app.data.TransactionRepository
import com.leftovers.app.data.TxType
import com.leftovers.app.data.investment.AssetUnit
import com.leftovers.app.data.investment.CurrencyPortfolioSummary
import com.leftovers.app.data.investment.HoldingItem
import com.leftovers.app.data.investment.InvestmentAsset
import com.leftovers.app.data.investment.InvestmentMath
import com.leftovers.app.data.investment.InvestmentRepository
import com.leftovers.app.data.investment.InvestmentTransaction
import com.leftovers.app.data.investment.InvestmentType
import com.leftovers.app.data.investment.MarketPriceHistory
import com.leftovers.app.data.investment.MarketPriceSnapshot
import com.leftovers.app.data.investment.WatchlistItem
import com.leftovers.app.data.investment.WatchlistItemWithQuote
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.math.BigDecimal
import java.time.LocalDate

enum class InvestmentTab(val label: String) {
    OVERVIEW("Overview"),
    NEPSE("NEPSE"),
    GOLD("Gold"),
    SILVER("Silver"),
    WATCHLIST("Watchlist"),
    MARKET("Market"),
    HISTORY("Ledger"),
}

data class WatchlistWithSnapshot(
    val item: WatchlistItem,
    val quote: MarketPriceSnapshot?,
)

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class InvestmentViewModel(
    private val repository: InvestmentRepository,
    private val accountsRepository: AccountRepository,
    private val transactionRepository: TransactionRepository,
) : ViewModel() {

    val holdings: StateFlow<List<HoldingItem>> = repository.activeHoldings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val allHoldings: StateFlow<List<HoldingItem>> = repository.holdings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val currencySummaries: StateFlow<List<CurrencyPortfolioSummary>> = repository.currencySummaries
        .map { it.values.toList() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val watchlist: StateFlow<List<WatchlistItemWithQuote>> = repository.watchlistWithQuotes
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val transactions: StateFlow<List<InvestmentTransaction>> = repository.transactions
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val marketSnapshots: StateFlow<List<MarketPriceSnapshot>> = repository.snapshotsList
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val accounts: StateFlow<List<AccountWithBalance>> = accountsRepository.accounts
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _currentTab = MutableStateFlow(InvestmentTab.OVERVIEW)
    val currentTab = _currentTab.asStateFlow()

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing = _isRefreshing.asStateFlow()

    private val _statusMessage = MutableStateFlow<String?>(null)
    val statusMessage = _statusMessage.asStateFlow()

    private val _selectedAssetId = MutableStateFlow<Long?>(null)
    val selectedAssetId = _selectedAssetId.asStateFlow()

    val selectedAssetTransactions: StateFlow<List<InvestmentTransaction>> = _selectedAssetId
        .flatMapLatest { id ->
            if (id == null) flowOf(emptyList()) else repository.observeTransactionsForAsset(id)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val selectedAssetHistory: StateFlow<List<MarketPriceHistory>> = _selectedAssetId
        .flatMapLatest { id ->
            if (id == null) flowOf(emptyList()) else {
                val asset = holdings.value.firstOrNull { it.asset.id == id }?.asset
                if (asset == null) flowOf(emptyList())
                else repository.observePriceHistory(asset.symbol)
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _selectedStockSymbol = MutableStateFlow<String?>(null)
    val selectedStockSymbol = _selectedStockSymbol.asStateFlow()

    val selectedStockSnapshot: StateFlow<MarketPriceSnapshot?> = _selectedStockSymbol
        .flatMapLatest { sym ->
            if (sym == null) flowOf(null)
            else marketSnapshots.map { list -> list.firstOrNull { it.symbol == sym } }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val selectedStockHistory: StateFlow<List<MarketPriceHistory>> = _selectedStockSymbol
        .flatMapLatest { sym ->
            if (sym == null) flowOf(emptyList())
            else repository.observePriceHistory(sym)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        refreshMarketData(force = false)
    }

    fun setTab(tab: InvestmentTab) {
        _currentTab.value = tab
    }

    fun selectStockForDetail(symbol: String?) {
        _selectedStockSymbol.value = symbol?.trim()?.uppercase()
    }

    fun selectAsset(assetId: Long?) {
        _selectedAssetId.value = assetId
    }

    fun refreshMarketData(force: Boolean = true) {
        viewModelScope.launch(Dispatchers.IO) {
            _isRefreshing.value = true
            _statusMessage.value = "Updating NEPSE market data..."
            try {
                val res = repository.refreshAllMarketData()
                res.fold(
                    onSuccess = { count ->
                        _statusMessage.value = if (count > 0) "Prices updated ($count symbols)" else "Market data up to date"
                    },
                    onFailure = {
                        _statusMessage.value = "Offline: Showing cached prices"
                    },
                )
            } catch (e: Exception) {
                _statusMessage.value = "Offline: Showing cached prices"
            } finally {
                _isRefreshing.value = false
            }
        }
    }

    fun buyStock(
        symbol: String,
        companyName: String,
        date: LocalDate,
        quantity: Double,
        pricePerShare: Double,
        fees: Double = 0.0,
        notes: String = "",
        linkAccountId: Long? = null,
        onSuccess: () -> Unit = {},
        onError: (String) -> Unit = {},
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val cleanSymbol = symbol.trim().uppercase()
                if (cleanSymbol.isBlank() || quantity <= 0.0 || pricePerShare <= 0.0) {
                    onError("Invalid quantity or price")
                    return@launch
                }

                val unitPriceMinor = (pricePerShare * 100.0).toLong()
                val feeMinor = (fees * 100.0).toLong()

                if (linkAccountId != null) {
                    val totalMinor = (quantity * unitPriceMinor).toLong() + feeMinor
                    val defaultCat = transactionRepository.categories.firstOrNull()?.firstOrNull()?.id ?: 1L
                    transactionRepository.saveTransaction(
                        Transaction(
                            amountMinor = totalMinor,
                            type = TxType.EXPENSE,
                            categoryId = defaultCat,
                            epochDay = date.toEpochDay(),
                            accountId = linkAccountId,
                            note = "Buy $quantity shares of $cleanSymbol @ Rs $pricePerShare",
                        )
                    )
                }

                repository.recordBuy(
                    symbol = cleanSymbol,
                    name = companyName.ifBlank { cleanSymbol },
                    type = InvestmentType.STOCK_NEPSE,
                    quantity = quantity,
                    unitPriceMinor = unitPriceMinor,
                    feeMinor = feeMinor,
                    currency = "NPR",
                    unit = AssetUnit.UNIT_SHARES,
                    epochDay = date.toEpochDay(),
                    notes = notes,
                    linkedAccountId = linkAccountId,
                )

                repository.refreshSymbols(listOf(cleanSymbol))
                onSuccess()
            } catch (e: Exception) {
                onError(e.message ?: "Failed to record purchase")
            }
        }
    }

    fun sellStock(
        assetId: Long,
        date: LocalDate,
        quantity: Double,
        pricePerShare: Double,
        fees: Double = 0.0,
        notes: String = "",
        linkAccountId: Long? = null,
        onSuccess: () -> Unit = {},
        onError: (String) -> Unit = {},
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val holding = holdings.value.firstOrNull { it.asset.id == assetId }
                if (holding == null || quantity <= 0.0 || quantity > holding.remainingQuantity + 0.0001) {
                    onError("Sale quantity exceeds remaining holdings (${holding?.remainingQuantity ?: 0.0})")
                    return@launch
                }

                val unitPriceMinor = (pricePerShare * 100.0).toLong()
                val feeMinor = (fees * 100.0).toLong()

                if (linkAccountId != null) {
                    val grossMinor = (quantity * unitPriceMinor).toLong()
                    val netMinor = (grossMinor - feeMinor).coerceAtLeast(0L)
                    val defaultCat = transactionRepository.categories.firstOrNull()?.firstOrNull()?.id ?: 1L
                    transactionRepository.saveTransaction(
                        Transaction(
                            amountMinor = netMinor,
                            type = TxType.INCOME,
                            categoryId = defaultCat,
                            epochDay = date.toEpochDay(),
                            accountId = linkAccountId,
                            note = "Sell $quantity shares of ${holding.asset.symbol} @ Rs $pricePerShare",
                        )
                    )
                }

                repository.recordSell(
                    assetId = assetId,
                    quantity = quantity,
                    unitPriceMinor = unitPriceMinor,
                    feeMinor = feeMinor,
                    epochDay = date.toEpochDay(),
                    notes = notes,
                    linkedAccountId = linkAccountId,
                )
                onSuccess()
            } catch (e: Exception) {
                onError(e.message ?: "Failed to record sale")
            }
        }
    }

    fun buyBullion(
        type: InvestmentType,
        name: String,
        unit: AssetUnit,
        purity: String = "24K",
        currency: String = "NPR",
        date: LocalDate,
        quantity: Double,
        unitPrice: Double,
        fees: Double = 0.0,
        seller: String = "",
        notes: String = "",
        linkAccountId: Long? = null,
        onSuccess: () -> Unit = {},
        onError: (String) -> Unit = {},
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                if (quantity <= 0.0 || unitPrice <= 0.0) {
                    onError("Invalid quantity or unit price")
                    return@launch
                }
                val symbol = if (type == InvestmentType.GOLD) "GOLD-$purity" else "SILVER-999"
                val assetName = name.ifBlank { if (type == InvestmentType.GOLD) "Gold ($purity)" else "Silver" }
                val unitPriceMinor = (unitPrice * 100.0).toLong()
                val feeMinor = (fees * 100.0).toLong()

                if (linkAccountId != null) {
                    val totalMinor = (quantity * unitPriceMinor).toLong() + feeMinor
                    val defaultCat = transactionRepository.categories.firstOrNull()?.firstOrNull()?.id ?: 1L
                    transactionRepository.saveTransaction(
                        Transaction(
                            amountMinor = totalMinor,
                            type = TxType.EXPENSE,
                            categoryId = defaultCat,
                            epochDay = date.toEpochDay(),
                            accountId = linkAccountId,
                            note = "Buy $quantity ${unit.label} $assetName",
                        )
                    )
                }

                repository.recordBuy(
                    symbol = symbol,
                    name = assetName,
                    type = type,
                    quantity = quantity,
                    unitPriceMinor = unitPriceMinor,
                    feeMinor = feeMinor,
                    currency = currency.uppercase(),
                    unit = unit,
                    purity = purity,
                    epochDay = date.toEpochDay(),
                    notes = if (seller.isNotBlank()) "Seller: $seller. $notes" else notes,
                    linkedAccountId = linkAccountId,
                )

                // Save initial manual price snapshot
                repository.updateManualPrice(
                    symbol = symbol,
                    price = unitPrice,
                    currency = currency.uppercase(),
                    companyName = assetName,
                )

                onSuccess()
            } catch (e: Exception) {
                onError(e.message ?: "Failed to record bullion purchase")
            }
        }
    }

    fun sellBullion(
        assetId: Long,
        date: LocalDate,
        quantity: Double,
        unitPrice: Double,
        fees: Double = 0.0,
        notes: String = "",
        linkAccountId: Long? = null,
        onSuccess: () -> Unit = {},
        onError: (String) -> Unit = {},
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val holding = holdings.value.firstOrNull { it.asset.id == assetId }
                if (holding == null || quantity <= 0.0 || quantity > holding.remainingQuantity + 0.0001) {
                    onError("Sale quantity exceeds remaining holding (${holding?.remainingQuantity ?: 0.0})")
                    return@launch
                }

                val unitPriceMinor = (unitPrice * 100.0).toLong()
                val feeMinor = (fees * 100.0).toLong()

                if (linkAccountId != null) {
                    val grossMinor = (quantity * unitPriceMinor).toLong()
                    val netMinor = (grossMinor - feeMinor).coerceAtLeast(0L)
                    val defaultCat = transactionRepository.categories.firstOrNull()?.firstOrNull()?.id ?: 1L
                    transactionRepository.saveTransaction(
                        Transaction(
                            amountMinor = netMinor,
                            type = TxType.INCOME,
                            categoryId = defaultCat,
                            epochDay = date.toEpochDay(),
                            accountId = linkAccountId,
                            note = "Sell $quantity ${holding.asset.unit.label} ${holding.asset.name}",
                        )
                    )
                }

                repository.recordSell(
                    assetId = assetId,
                    quantity = quantity,
                    unitPriceMinor = unitPriceMinor,
                    feeMinor = feeMinor,
                    epochDay = date.toEpochDay(),
                    notes = notes,
                    linkedAccountId = linkAccountId,
                )
                onSuccess()
            } catch (e: Exception) {
                onError(e.message ?: "Failed to record bullion sale")
            }
        }
    }

    fun updateManualPrice(
        symbol: String,
        unitPrice: Double,
        currency: String = "NPR",
        companyName: String? = null,
        onSuccess: () -> Unit = {},
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.updateManualPrice(
                symbol = symbol.trim().uppercase(),
                price = unitPrice,
                currency = currency.uppercase(),
                companyName = companyName,
            )
            onSuccess()
        }
    }

    fun toggleWatchlist(symbol: String, companyName: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val sym = symbol.trim().uppercase()
            if (repository.isWatchlisted(sym)) {
                repository.removeFromWatchlist(sym)
            } else {
                repository.addToWatchlist(sym, companyName)
            }
        }
    }

    fun deleteTransaction(txId: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.deleteTransaction(txId)
        }
    }

    fun deleteAsset(asset: InvestmentAsset) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.deleteAsset(asset)
        }
    }
}
