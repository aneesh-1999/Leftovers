package com.leftovers.app.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.leftovers.app.data.AccountWithBalance
import com.leftovers.app.data.investment.AssetUnit
import com.leftovers.app.data.investment.HoldingItem
import com.leftovers.app.data.investment.InvestmentAsset
import com.leftovers.app.data.investment.InvestmentMath
import com.leftovers.app.data.investment.InvestmentTransaction
import com.leftovers.app.data.investment.InvestmentTxType
import com.leftovers.app.data.investment.InvestmentType
import com.leftovers.app.data.investment.MarketPriceHistory
import com.leftovers.app.data.investment.MarketPriceSnapshot
import com.leftovers.app.data.investment.PriceSourceType
import com.leftovers.app.ui.components.Chip
import com.leftovers.app.ui.components.Glass
import com.leftovers.app.ui.components.MarketPriceChart
import com.leftovers.app.ui.components.PrimaryButton
import com.leftovers.app.ui.components.RoundButton
import com.leftovers.app.ui.components.RowDivider
import com.leftovers.app.ui.components.SecondaryButton
import com.leftovers.app.ui.components.SegmentedToggle
import com.leftovers.app.ui.components.StatTile
import com.leftovers.app.ui.icons.Lucide
import com.leftovers.app.ui.theme.LocalAppColors
import com.leftovers.app.util.Money
import java.time.Instant
import java.time.LocalDate

@Composable
fun AddInvestmentSheet(
    marketSnapshots: List<MarketPriceSnapshot>,
    accounts: List<AccountWithBalance>,
    initialType: InvestmentType = InvestmentType.STOCK_NEPSE,
    initialSymbol: String? = null,
    onDismiss: () -> Unit,
    onBuyStock: (symbol: String, name: String, date: LocalDate, qty: Double, price: Double, fees: Double, notes: String, accountId: Long?) -> Unit,
    onBuyBullion: (type: InvestmentType, name: String, unit: AssetUnit, purity: String, currency: String, date: LocalDate, qty: Double, price: Double, fees: Double, seller: String, notes: String, accountId: Long?) -> Unit,
) {
    val c = LocalAppColors.current
    var selectedType by remember { mutableStateOf(initialType) }

    // Common fields
    var date by remember { mutableStateOf(LocalDate.now()) }
    var qtyText by remember { mutableStateOf("") }
    val initialSnapshot = remember(initialSymbol, marketSnapshots) {
        if (!initialSymbol.isNullOrBlank()) {
            marketSnapshots.firstOrNull { it.symbol.equals(initialSymbol, ignoreCase = true) }
        } else null
    }
    var priceText by remember { mutableStateOf(initialSnapshot?.price?.toString() ?: "") }
    var feesText by remember { mutableStateOf("") }
    var notesText by remember { mutableStateOf("") }
    var selectedAccountId by remember { mutableStateOf<Long?>(null) }
    var errorMsg by remember { mutableStateOf<String?>(null) }

    // NEPSE specific
    var symbolText by remember { mutableStateOf(initialSymbol ?: "") }
    var companyNameText by remember { mutableStateOf(initialSnapshot?.companyName ?: initialSymbol ?: "") }
    var symbolSearchQuery by remember { mutableStateOf("") }

    // Bullion specific
    var bullionUnit by remember { mutableStateOf(AssetUnit.UNIT_TOLA) }
    var bullionPurity by remember { mutableStateOf("24K") }
    var bullionCurrency by remember { mutableStateOf("NPR") }
    var sellerText by remember { mutableStateOf("") }

    val filteredQuotes = remember(symbolSearchQuery, marketSnapshots) {
        if (symbolSearchQuery.isBlank()) marketSnapshots.take(6)
        else marketSnapshots.filter {
            it.symbol.contains(symbolSearchQuery, ignoreCase = true) ||
            (it.companyName?.contains(symbolSearchQuery, ignoreCase = true) == true)
        }.take(8)
    }

    GlassSheet(onDismiss = onDismiss) {
        LazyColumn(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Add Investment", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = c.textPrimary)
                    RoundButton(Lucide.X, "Close", onDismiss, size = 36.dp)
                }
            }

            // Asset Type Toggle
            item {
                SegmentedToggle(
                    options = listOf(InvestmentType.STOCK_NEPSE, InvestmentType.GOLD, InvestmentType.SILVER),
                    selected = selectedType,
                    label = {
                        when (it) {
                            InvestmentType.STOCK_NEPSE -> "NEPSE Stock"
                            InvestmentType.GOLD -> "Gold"
                            InvestmentType.SILVER -> "Silver"
                        }
                    },
                    onSelect = { selectedType = it },
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            // Conditional Inputs
            when (selectedType) {
                InvestmentType.STOCK_NEPSE -> {
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text("Stock Details", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = c.textPrimary)

                            // Quick ticker search / select from live market quotes
                            if (marketSnapshots.isNotEmpty()) {
                                InvestmentTextField(
                                    value = symbolSearchQuery,
                                    onValueChange = { symbolSearchQuery = it },
                                    placeholder = "Search ticker (e.g. NABIL, SHIVM, CHCL)",
                                )

                                LazyRow(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    items(filteredQuotes) { quote ->
                                        Chip(
                                            text = "${quote.symbol} · Rs ${quote.price}",
                                            onClick = {
                                                symbolText = quote.symbol
                                                companyNameText = quote.companyName ?: quote.symbol
                                                if (priceText.isBlank()) priceText = quote.price.toString()
                                            },
                                            selected = symbolText.equals(quote.symbol, ignoreCase = true),
                                        )
                                    }
                                }
                            }

                            InvestmentTextField(
                                value = symbolText,
                                onValueChange = { symbolText = it.uppercase() },
                                placeholder = "Ticker Symbol * (e.g. NABIL)",
                            )

                            InvestmentTextField(
                                value = companyNameText,
                                onValueChange = { companyNameText = it },
                                placeholder = "Company Name (optional)",
                            )
                        }
                    }
                }

                InvestmentType.GOLD, InvestmentType.SILVER -> {
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(
                                if (selectedType == InvestmentType.GOLD) "Gold Details" else "Silver Details",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = c.textPrimary,
                            )

                            val purities = if (selectedType == InvestmentType.GOLD) listOf("24K Fine", "22K Tejabi") else listOf("999 Fine", "925 Sterling")
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Box(Modifier.weight(1f)) {
                                    SegmentedToggle(
                                        options = listOf(AssetUnit.UNIT_TOLA, AssetUnit.UNIT_GRAM),
                                        selected = bullionUnit,
                                        label = { if (it == AssetUnit.UNIT_TOLA) "Tola" else "Gram" },
                                        onSelect = { bullionUnit = it },
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                }
                                Box(Modifier.weight(1f)) {
                                    SegmentedToggle(
                                        options = purities,
                                        selected = if (bullionPurity in purities) bullionPurity else purities.first(),
                                        label = { it },
                                        onSelect = { bullionPurity = it },
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                }
                            }

                            InvestmentTextField(
                                value = sellerText,
                                onValueChange = { sellerText = it },
                                placeholder = "Jeweler / Seller (optional)",
                            )

                            // Currency selection for Bullion
                            SegmentedToggle(
                                options = listOf("NPR", "USD", "EUR"),
                                selected = bullionCurrency,
                                label = { it },
                                onSelect = { bullionCurrency = it },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
            }

            // Common purchase details: Date, Quantity, Price, Fees
            item {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Purchase Transaction", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = c.textPrimary)

                    InvestmentTextField(
                        value = qtyText,
                        onValueChange = { qtyText = it },
                        placeholder = if (selectedType == InvestmentType.STOCK_NEPSE) "Quantity (Units/Kitta) *" else "Quantity in ${bullionUnit.label} *",
                        keyboardType = KeyboardType.Decimal,
                    )

                    InvestmentTextField(
                        value = priceText,
                        onValueChange = { priceText = it },
                        placeholder = if (selectedType == InvestmentType.STOCK_NEPSE) "Buy Price per Share (NPR) *" else "Price per ${bullionUnit.label} ($bullionCurrency) *",
                        keyboardType = KeyboardType.Decimal,
                    )

                    InvestmentTextField(
                        value = feesText,
                        onValueChange = { feesText = it },
                        placeholder = "Fees / Commission / DP charge (optional)",
                        keyboardType = KeyboardType.Decimal,
                    )

                    InvestmentTextField(
                        value = notesText,
                        onValueChange = { notesText = it },
                        placeholder = "Notes (e.g. IPO, Secondary, Dhanteras)",
                    )
                }
            }

            // Optional link to bank account
            if (accounts.isNotEmpty()) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Paid from Account (optional)", style = MaterialTheme.typography.labelMedium, color = c.textSecondary)
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            item {
                                Chip(
                                    text = "None (Cash/External)",
                                    onClick = { selectedAccountId = null },
                                    selected = selectedAccountId == null,
                                )
                            }
                            items(accounts) { acc ->
                                Chip(
                                    text = "${acc.name} (${acc.currency ?: "NPR"})",
                                    onClick = { selectedAccountId = acc.id },
                                    selected = selectedAccountId == acc.id,
                                )
                            }
                        }
                    }
                }
            }

            // Calculation preview
            item {
                val qty = qtyText.toDoubleOrNull() ?: 0.0
                val price = priceText.toDoubleOrNull() ?: 0.0
                val fees = feesText.toDoubleOrNull() ?: 0.0
                val total = (qty * price) + fees
                val curr = if (selectedType == InvestmentType.STOCK_NEPSE) "NPR" else bullionCurrency

                if (qty > 0.0 && price > 0.0) {
                    Glass(
                        modifier = Modifier.fillMaxWidth(),
                        strong = true,
                        shape = RoundedCornerShape(14.dp),
                    ) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(14.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("Total Outlay", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = c.textPrimary)
                            Text(
                                "$curr ${String.format("%.2f", total)}",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = c.accent,
                            )
                        }
                    }
                }
            }

            if (errorMsg != null) {
                item {
                    Text(errorMsg!!, color = c.negative, style = MaterialTheme.typography.bodySmall)
                }
            }

            // Action
            item {
                PrimaryButton(
                    text = "Save Investment",
                    onClick = {
                        val qty = qtyText.toDoubleOrNull()
                        val price = priceText.toDoubleOrNull()
                        val fees = feesText.toDoubleOrNull() ?: 0.0

                        if (qty == null || qty <= 0.0) {
                            errorMsg = "Please enter a valid positive quantity"
                            return@PrimaryButton
                        }
                        if (price == null || price <= 0.0) {
                            errorMsg = "Please enter a valid purchase price"
                            return@PrimaryButton
                        }

                        if (selectedType == InvestmentType.STOCK_NEPSE) {
                            val sym = symbolText.trim().uppercase()
                            if (sym.isBlank()) {
                                errorMsg = "Please enter a ticker symbol"
                                return@PrimaryButton
                            }
                            val name = companyNameText.ifBlank { sym }
                            onBuyStock(sym, name, date, qty, price, fees, notesText, selectedAccountId)
                        } else {
                            val name = when (selectedType) {
                                InvestmentType.GOLD -> "Gold ($bullionPurity)"
                                else -> "Silver ($bullionPurity)"
                            }
                            onBuyBullion(
                                selectedType,
                                name,
                                bullionUnit,
                                bullionPurity,
                                bullionCurrency,
                                date,
                                qty,
                                price,
                                fees,
                                sellerText,
                                notesText,
                                selectedAccountId,
                            )
                        }
                        onDismiss()
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(20.dp))
            }
        }
    }
}

@Composable
fun SellInvestmentSheet(
    holding: HoldingItem,
    accounts: List<AccountWithBalance>,
    onDismiss: () -> Unit,
    onConfirmSale: (date: LocalDate, qty: Double, price: Double, fees: Double, notes: String, accountId: Long?) -> Unit,
) {
    val c = LocalAppColors.current
    val asset = holding.asset
    val currency = holding.currency
    val unitLabel = asset.unit.label

    var date by remember { mutableStateOf(LocalDate.now()) }
    var qtyText by remember { mutableStateOf("") }
    var priceText by remember {
        mutableStateOf(holding.latestQuote?.price?.toString() ?: "")
    }
    var feesText by remember { mutableStateOf("") }
    var notesText by remember { mutableStateOf("") }
    var selectedAccountId by remember { mutableStateOf<Long?>(null) }
    var errorMsg by remember { mutableStateOf<String?>(null) }

    GlassSheet(onDismiss = onDismiss) {
        LazyColumn(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column {
                        Text("Record Sale", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = c.textPrimary)
                        Text("${asset.symbol} · ${asset.name}", style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
                    }
                    RoundButton(Lucide.X, "Close", onDismiss, size = 36.dp)
                }
            }

            // Current Holding Info
            item {
                Glass(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                ) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        StatTile("Held Balance", "${holding.remainingQuantity} $unitLabel")
                        val avgCost = holding.averageCostPerUnitMinor / 100.0
                        StatTile("Avg Cost Basis", "$currency ${String.format("%.2f", avgCost)}")
                    }
                }
            }

            // Sale inputs
            item {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        InvestmentTextField(
                            value = qtyText,
                            onValueChange = { qtyText = it },
                            placeholder = "Sale Quantity *",
                            keyboardType = KeyboardType.Decimal,
                            modifier = Modifier.weight(1f),
                        )
                        SecondaryButton(
                            text = "Sell All",
                            onClick = { qtyText = holding.remainingQuantity.toString() },
                        )
                    }

                    InvestmentTextField(
                        value = priceText,
                        onValueChange = { priceText = it },
                        placeholder = "Selling Price per $unitLabel ($currency) *",
                        keyboardType = KeyboardType.Decimal,
                    )

                    InvestmentTextField(
                        value = feesText,
                        onValueChange = { feesText = it },
                        placeholder = "Broker / SEBON / Making Fees deducted ($currency)",
                        keyboardType = KeyboardType.Decimal,
                    )

                    InvestmentTextField(
                        value = notesText,
                        onValueChange = { notesText = it },
                        placeholder = "Notes (e.g. Profit taking, Rebalancing)",
                    )
                }
            }

            // Destination account for sale proceeds
            if (accounts.isNotEmpty()) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Deposit Proceeds To", style = MaterialTheme.typography.labelMedium, color = c.textSecondary)
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            item {
                                Chip(
                                    text = "None (Cash/External)",
                                    onClick = { selectedAccountId = null },
                                    selected = selectedAccountId == null,
                                )
                            }
                            items(accounts) { acc ->
                                Chip(
                                    text = "${acc.name} (${acc.currency ?: "NPR"})",
                                    onClick = { selectedAccountId = acc.id },
                                    selected = selectedAccountId == acc.id,
                                )
                            }
                        }
                    }
                }
            }

            // Realized Profit / Loss Preview
            item {
                val qty = qtyText.toDoubleOrNull() ?: 0.0
                val price = priceText.toDoubleOrNull() ?: 0.0
                val fees = feesText.toDoubleOrNull() ?: 0.0

                if (qty > 0.0 && price > 0.0) {
                    val grossProceeds = (qty * price * 100.0).toLong()
                    val feesMinor = (fees * 100.0).toLong()
                    val netProceeds = (grossProceeds - feesMinor).coerceAtLeast(0L)
                    val costRelieved = (qty * holding.averageCostPerUnitMinor).toLong()
                    val realizedProfitLoss = netProceeds - costRelieved
                    val isProfit = realizedProfitLoss >= 0

                    Glass(
                        modifier = Modifier.fillMaxWidth(),
                        strong = true,
                        shape = RoundedCornerShape(14.dp),
                    ) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("Net Proceeds", style = MaterialTheme.typography.bodyMedium, color = c.textSecondary)
                                Text("$currency ${String.format("%.2f", netProceeds / 100.0)}", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, color = c.textPrimary)
                            }
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("Estimated Realized P/L", style = MaterialTheme.typography.bodyMedium, color = c.textSecondary)
                                Text(
                                    "${if (isProfit) "+" else ""}$currency ${String.format("%.2f", realizedProfitLoss / 100.0)}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = if (isProfit) c.positive else c.negative,
                                )
                            }
                        }
                    }
                }
            }

            if (errorMsg != null) {
                item {
                    Text(errorMsg!!, color = c.negative, style = MaterialTheme.typography.bodySmall)
                }
            }

            item {
                PrimaryButton(
                    text = "Confirm Sale",
                    onClick = {
                        val qty = qtyText.toDoubleOrNull()
                        val price = priceText.toDoubleOrNull()
                        val fees = feesText.toDoubleOrNull() ?: 0.0

                        if (qty == null || qty <= 0.0) {
                            errorMsg = "Please enter a valid quantity"
                            return@PrimaryButton
                        }
                        if (qty > holding.remainingQuantity + 0.0001) {
                            errorMsg = "Cannot sell more than held (${holding.remainingQuantity} $unitLabel)"
                            return@PrimaryButton
                        }
                        if (price == null || price <= 0.0) {
                            errorMsg = "Please enter a valid sale price"
                            return@PrimaryButton
                        }

                        onConfirmSale(date, qty, price, fees, notesText, selectedAccountId)
                        onDismiss()
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(20.dp))
            }
        }
    }
}

@Composable
fun ManualPriceSheet(
    holding: HoldingItem,
    onDismiss: () -> Unit,
    onUpdatePrice: (newPrice: Double, currency: String) -> Unit,
) {
    val c = LocalAppColors.current
    val asset = holding.asset
    val unitLabel = asset.unit.label
    var priceText by remember {
        mutableStateOf(holding.latestQuote?.price?.toString() ?: (holding.averageCostPerUnitMinor / 100.0).toString())
    }
    var currency by remember { mutableStateOf(holding.currency) }
    var errorMsg by remember { mutableStateOf<String?>(null) }

    GlassSheet(onDismiss = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text("Update Valuation", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = c.textPrimary)
                    Text("${asset.symbol} · ${asset.name}", style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
                }
                RoundButton(Lucide.X, "Close", onDismiss, size = 36.dp)
            }

            Text(
                "Manually record current market rate per $unitLabel for this asset. Used for offline or physical bullion holdings.",
                style = MaterialTheme.typography.bodySmall,
                color = c.textSecondary,
            )

            InvestmentTextField(
                value = priceText,
                onValueChange = { priceText = it },
                placeholder = "New Price per $unitLabel ($currency)",
                keyboardType = KeyboardType.Decimal,
            )

            if (errorMsg != null) {
                Text(errorMsg!!, color = c.negative, style = MaterialTheme.typography.bodySmall)
            }

            PrimaryButton(
                text = "Save Valuation",
                onClick = {
                    val p = priceText.toDoubleOrNull()
                    if (p == null || p <= 0.0) {
                        errorMsg = "Please enter a valid positive price"
                        return@PrimaryButton
                    }
                    onUpdatePrice(p, currency)
                    onDismiss()
                },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(10.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HoldingDetailSheet(
    holding: HoldingItem,
    transactions: List<InvestmentTransaction>,
    priceHistory: List<MarketPriceHistory>,
    onDismiss: () -> Unit,
    onBuyMore: () -> Unit,
    onSell: () -> Unit,
    onUpdatePrice: () -> Unit,
    onDeleteHolding: () -> Unit,
    onDeleteTransaction: (Long) -> Unit,
) {
    val c = LocalAppColors.current
    val asset = holding.asset
    val quote = holding.latestQuote
    val currency = holding.currency
    val unitLabel = asset.unit.label
    val money = Money(currency)

    val unrealizedPL = holding.unrealizedProfitLossMinor
    val isProfit = (unrealizedPL ?: 0L) >= 0L

    GlassSheet(onDismiss = onDismiss) {
        LazyColumn(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Header
            item {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                asset.symbol,
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.Bold,
                                color = c.textPrimary,
                            )
                            Spacer(Modifier.width(8.dp))
                            Box(
                                Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background((when (asset.type) {
                                        InvestmentType.STOCK_NEPSE -> c.accent
                                        InvestmentType.GOLD -> Color(0xFFD4AF37)
                                        InvestmentType.SILVER -> Color(0xFFAAAAAA)
                                    }).copy(alpha = 0.15f))
                                    .padding(horizontal = 8.dp, vertical = 3.dp),
                            ) {
                                Text(
                                    text = when (asset.type) {
                                        InvestmentType.STOCK_NEPSE -> "NEPSE"
                                        InvestmentType.GOLD -> "Gold"
                                        InvestmentType.SILVER -> "Silver"
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = when (asset.type) {
                                        InvestmentType.STOCK_NEPSE -> c.accent
                                        InvestmentType.GOLD -> Color(0xFFD4AF37)
                                        InvestmentType.SILVER -> Color(0xFFAAAAAA)
                                    },
                                )
                            }
                        }
                        Text(
                            asset.name,
                            style = MaterialTheme.typography.bodyMedium,
                            color = c.textSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    RoundButton(Lucide.X, "Close", onDismiss, size = 36.dp)
                }
            }

            // Market Price & Status Card
            item {
                Glass(
                    modifier = Modifier.fillMaxWidth(),
                    strong = true,
                    shape = RoundedCornerShape(20.dp),
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column {
                                Text(
                                    if (asset.type == InvestmentType.STOCK_NEPSE) "Latest Traded Price (LTP)" else "Current Valuation Rate",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = c.textTertiary,
                                )
                                Row(verticalAlignment = Alignment.Bottom) {
                                    val price = quote?.price ?: 0.0
                                    Text(
                                        if (price > 0.0) money.format((price * 100.0).toLong()) else "N/A",
                                        style = MaterialTheme.typography.titleLarge,
                                        fontWeight = FontWeight.Bold,
                                        color = c.textPrimary,
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    Text("/ $unitLabel", style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
                                }
                            }

                            if (quote?.percentChange != null) {
                                val changePos = quote.percentChange >= 0.0
                                Box(
                                    Modifier
                                        .clip(RoundedCornerShape(10.dp))
                                        .background((if (changePos) c.positive else c.negative).copy(alpha = 0.15f))
                                        .padding(horizontal = 10.dp, vertical = 6.dp),
                                ) {
                                    Text(
                                        "${if (changePos) "+" else ""}${String.format("%.2f", quote.percentChange)}%",
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = if (changePos) c.positive else c.negative,
                                    )
                                }
                            }
                        }

                        // Data freshness tag
                        val sourceLabel = when (holding.priceSource) {
                            PriceSourceType.LIVE -> "Live Market (${quote?.source ?: "API"})"
                            PriceSourceType.MANUAL -> "Manual Valuation"
                            PriceSourceType.HISTORICAL -> "Closing Price"
                            PriceSourceType.CACHED_STALE -> "Cached (Stale)"
                        }
                        val timeStr = if (quote != null) formatTimeAgo(Instant.ofEpochMilli(quote.timestamp)) else "No quote"
                        Text(
                            "$sourceLabel · $timeStr",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (holding.isStale) c.warning else c.textTertiary,
                        )

                        // High, Low, Volume, Prev Close if present
                        if (quote != null && (quote.high != null || quote.low != null || quote.volume != null)) {
                            RowDivider()
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                quote.high?.let { StatSmall("High", "Rs $it") }
                                quote.low?.let { StatSmall("Low", "Rs $it") }
                                quote.volume?.let { StatSmall("Volume", "$it") }
                                quote.previousClose?.let { StatSmall("Prev Close", "Rs $it") }
                            }
                        }
                    }
                }
            }

            // Interactive Price Graph & Timeframe Range
            val chartSnapshot = quote ?: MarketPriceSnapshot(
                symbol = asset.symbol,
                price = (holding.averageCostPerUnitMinor / 100.0),
                currency = currency,
                companyName = asset.name,
                isManual = true,
            )
            item {
                MarketPriceChart(
                    snapshot = chartSnapshot,
                    history = priceHistory,
                    currency = currency,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            // Holdings Breakdown
            item {
                Glass(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Holdings Breakdown", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = c.textPrimary)

                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            StatTile("Total Held", "${holding.remainingQuantity} $unitLabel", Modifier.weight(1f))
                            Spacer(Modifier.width(8.dp))
                            StatTile("Avg Cost", money.format(holding.averageCostPerUnitMinor), Modifier.weight(1f))
                        }

                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            StatTile("Total Invested", money.format(holding.totalCostBasisMinor), Modifier.weight(1f))
                            Spacer(Modifier.width(8.dp))
                            StatTile("Current Value", holding.currentMarketValueMinor?.let { money.format(it) } ?: "N/A", Modifier.weight(1f))
                        }

                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            StatTile(
                                "Unrealized P/L",
                                unrealizedPL?.let { "${if (isProfit) "+" else ""}${money.format(it)}" } ?: "N/A",
                                Modifier.weight(1f),
                            )
                            Spacer(Modifier.width(8.dp))
                            StatTile(
                                "Total Return",
                                holding.unrealizedReturnPercent?.let { "${if (it >= 0.0) "+" else ""}${String.format("%.2f", it)}%" } ?: "N/A",
                                Modifier.weight(1f),
                            )
                        }

                        if (holding.realizedProfitLossMinor != 0L) {
                            val rProfit = holding.realizedProfitLossMinor >= 0L
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                StatTile(
                                    "Realized P/L (Sales)",
                                    "${if (rProfit) "+" else ""}${money.format(holding.realizedProfitLossMinor)}",
                                    Modifier.weight(1f),
                                )
                            }
                        }
                    }
                }
            }

            // Action Buttons
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PrimaryButton("Buy More", onBuyMore, Modifier.weight(1f), Lucide.Plus)
                    SecondaryButton("Sell", onSell, Modifier.weight(1f), Lucide.TrendingDown)
                    SecondaryButton("Value", onUpdatePrice, Modifier.weight(1f), Lucide.Pencil)
                }
            }

            // Transaction History Ledger
            item {
                Text("Transaction Ledger (${transactions.size})", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = c.textPrimary)
            }

            if (transactions.isEmpty()) {
                item {
                    Text("No transactions recorded yet.", style = MaterialTheme.typography.bodyMedium, color = c.textTertiary)
                }
            } else {
                items(transactions) { tx ->
                    Glass(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        tx.type.name,
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = when (tx.type) {
                                            InvestmentTxType.BUY -> c.accent
                                            InvestmentTxType.SELL -> c.positive
                                            InvestmentTxType.FEE -> c.warning
                                            else -> c.textSecondary
                                        },
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        LocalDate.ofEpochDay(tx.epochDay).toString(),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = c.textTertiary,
                                    )
                                }
                                Text(
                                    "${tx.quantity} $unitLabel @ ${money.format(tx.unitPriceMinor)}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = c.textSecondary,
                                )
                                if (tx.feeMinor > 0) {
                                    Text("Fees: ${money.format(tx.feeMinor)}", style = MaterialTheme.typography.labelSmall, color = c.textTertiary)
                                }
                            }

                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    money.format(tx.totalAmountMinor),
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = c.textPrimary,
                                )
                                Spacer(Modifier.width(6.dp))
                                RoundButton(Lucide.Trash2, "Delete", { onDeleteTransaction(tx.id) }, size = 28.dp, tint = c.textTertiary)
                            }
                        }
                    }
                }
            }

            item {
                Spacer(Modifier.height(8.dp))
                SecondaryButton("Delete Asset & History", onDeleteHolding, Modifier.fillMaxWidth(), Lucide.Trash2)
                Spacer(Modifier.height(20.dp))
            }
        }
    }
}

/**
 * Detailed sheet for exploring any market stock (quotes, statistics, interactive graph, position, and buy/sell actions).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StockMarketDetailSheet(
    snapshot: MarketPriceSnapshot,
    history: List<MarketPriceHistory>,
    holding: HoldingItem? = null,
    isWatched: Boolean,
    onDismiss: () -> Unit,
    onBuy: () -> Unit,
    onSell: (() -> Unit)? = null,
    onToggleWatch: () -> Unit,
) {
    val c = LocalAppColors.current
    val changePos = (snapshot.percentChange ?: 0.0) >= 0.0
    val money = remember(snapshot.currency) { Money(snapshot.currency) }

    GlassSheet(onDismiss = onDismiss) {
        LazyColumn(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Header: Symbol, Company Name, Badges, Close Button
            item {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                snapshot.symbol,
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.Bold,
                                color = c.textPrimary,
                            )
                            Spacer(Modifier.width(8.dp))
                            Box(
                                Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(c.accent.copy(alpha = 0.15f))
                                    .padding(horizontal = 8.dp, vertical = 3.dp),
                            ) {
                                Text(
                                    text = "NEPSE",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = c.accent,
                                )
                            }
                        }
                        Text(
                            snapshot.companyName ?: snapshot.symbol,
                            style = MaterialTheme.typography.bodyMedium,
                            color = c.textSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        RoundButton(
                            icon = if (isWatched) Lucide.Check else Lucide.Plus,
                            contentDescription = if (isWatched) "Watched" else "Add to Watchlist",
                            onClick = onToggleWatch,
                            size = 36.dp,
                            tint = if (isWatched) c.accent else c.textTertiary,
                        )
                        RoundButton(Lucide.X, "Close", onDismiss, size = 36.dp)
                    }
                }
            }

            // Price Hero Card
            item {
                Glass(
                    modifier = Modifier.fillMaxWidth(),
                    strong = true,
                    shape = RoundedCornerShape(20.dp),
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            "Last Traded Price (LTP)",
                            style = MaterialTheme.typography.labelMedium,
                            color = c.textTertiary,
                        )
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                "Rs ${String.format("%.2f", snapshot.price)}",
                                style = MaterialTheme.typography.headlineMedium,
                                fontWeight = FontWeight.Bold,
                                color = c.textPrimary,
                            )
                            if (snapshot.percentChange != null) {
                                Box(
                                    Modifier
                                        .clip(RoundedCornerShape(10.dp))
                                        .background((if (changePos) c.positive else c.negative).copy(alpha = 0.15f))
                                        .padding(horizontal = 10.dp, vertical = 6.dp),
                                ) {
                                    Text(
                                        "${if (changePos) "+" else ""}${String.format("%.2f", snapshot.percentChange)}%" +
                                            (snapshot.change?.let { " (${if (it >= 0) "+" else ""}${String.format("%.2f", it)})" } ?: ""),
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = if (changePos) c.positive else c.negative,
                                    )
                                }
                            }
                        }

                        val timeAgo = formatTimeAgo(Instant.ofEpochMilli(snapshot.timestamp))
                        Text(
                            "Live Quote · $timeAgo · Source: ${snapshot.source.uppercase()}",
                            style = MaterialTheme.typography.labelSmall,
                            color = c.textTertiary,
                        )
                    }
                }
            }

            // Interactive Graph with Timeframe selector & Scrubber
            item {
                MarketPriceChart(
                    snapshot = snapshot,
                    history = history,
                    currency = snapshot.currency,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            // Key Statistics Card
            item {
                Glass(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("Market Statistics", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = c.textPrimary)

                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            StatTile("Open", snapshot.previousClose?.let { "Rs $it" } ?: "N/A", Modifier.weight(1f))
                            Spacer(Modifier.width(8.dp))
                            StatTile("Prev Close", snapshot.previousClose?.let { "Rs $it" } ?: "N/A", Modifier.weight(1f))
                        }

                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            StatTile("Day High", snapshot.high?.let { "Rs $it" } ?: "N/A", Modifier.weight(1f))
                            Spacer(Modifier.width(8.dp))
                            StatTile("Day Low", snapshot.low?.let { "Rs $it" } ?: "N/A", Modifier.weight(1f))
                        }

                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            StatTile("Volume", snapshot.volume?.let { "$it" } ?: "N/A", Modifier.weight(1f))
                            Spacer(Modifier.width(8.dp))
                            StatTile("Turnover", snapshot.turnover?.let { "Rs ${formatCompactNumber(it)}" } ?: "N/A", Modifier.weight(1f))
                        }

                        if (snapshot.trades != null || snapshot.marketCap != null) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                StatTile("Total Trades", snapshot.trades?.toString() ?: "N/A", Modifier.weight(1f))
                                Spacer(Modifier.width(8.dp))
                                StatTile("Market Cap", snapshot.marketCap?.let { "Rs ${formatCompactNumber(it)}" } ?: "N/A", Modifier.weight(1f))
                            }
                        }
                    }
                }
            }

            // User Holding Position Card (if user already holds this asset)
            if (holding != null) {
                item {
                    val holdingPL = holding.unrealizedProfitLossMinor ?: 0L
                    val holdingPLPos = holdingPL >= 0L
                    Glass(
                        modifier = Modifier.fillMaxWidth(),
                        strong = true,
                        shape = RoundedCornerShape(20.dp),
                    ) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text("Your Position", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = c.textPrimary)
                                Box(
                                    Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .background((if (holdingPLPos) c.positive else c.negative).copy(alpha = 0.15f))
                                        .padding(horizontal = 8.dp, vertical = 3.dp),
                                ) {
                                    Text(
                                        "${if (holdingPLPos) "+" else ""}${String.format("%.2f", holding.unrealizedReturnPercent ?: 0.0)}%",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = if (holdingPLPos) c.positive else c.negative,
                                    )
                                }
                            }

                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                StatTile("Quantity", "${holding.remainingQuantity} shares", Modifier.weight(1f))
                                Spacer(Modifier.width(8.dp))
                                StatTile("Avg Cost", money.format(holding.averageCostPerUnitMinor), Modifier.weight(1f))
                            }

                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                StatTile("Invested", money.format(holding.totalCostBasisMinor), Modifier.weight(1f))
                                Spacer(Modifier.width(8.dp))
                                StatTile("Current Value", holding.currentMarketValueMinor?.let { money.format(it) } ?: "N/A", Modifier.weight(1f))
                            }
                        }
                    }
                }
            }

            // Action Buttons
            item {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (holding != null && onSell != null) {
                        SecondaryButton("Sell Shares", onSell, Modifier.weight(1f), Lucide.TrendingDown)
                    }
                    PrimaryButton("Buy / Add Shares", onBuy, Modifier.weight(1f), Lucide.Plus)
                }
                Spacer(Modifier.height(20.dp))
            }
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

@Composable
private fun StatSmall(label: String, value: String) {
    val c = LocalAppColors.current
    Column {
        Text(label, style = MaterialTheme.typography.labelSmall, color = c.textTertiary)
        Text(value, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = c.textPrimary)
    }
}

@Composable
fun InvestmentTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String = "",
    keyboardType: KeyboardType = KeyboardType.Text,
    modifier: Modifier = Modifier,
) {
    val c = LocalAppColors.current
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        placeholder = { Text(placeholder, color = c.textTertiary) },
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        singleLine = true,
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = c.accent,
            unfocusedBorderColor = c.borderTop,
            focusedTextColor = c.textPrimary,
            unfocusedTextColor = c.textPrimary,
            cursorColor = c.accent,
        ),
        shape = RoundedCornerShape(12.dp),
        modifier = modifier.fillMaxWidth(),
    )
}

private fun formatTimeAgo(instant: Instant): String {
    val now = Instant.now()
    val diffSec = (now.epochSecond - instant.epochSecond).coerceAtLeast(0)
    return when {
        diffSec < 60 -> "just now"
        diffSec < 3600 -> "${diffSec / 60}m ago"
        diffSec < 86400 -> "${diffSec / 3600}h ago"
        else -> "${diffSec / 86400}d ago"
    }
}
