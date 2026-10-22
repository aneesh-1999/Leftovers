package com.leftovers.app.ui.screens.creditcard

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.leftovers.app.data.creditcard.CreditCard
import com.leftovers.app.data.creditcard.CreditCardCurrencySummary
import com.leftovers.app.data.creditcard.CreditCardStatus
import com.leftovers.app.data.creditcard.CreditCardWithSummary
import com.leftovers.app.ui.AppViewModelProvider
import com.leftovers.app.ui.components.DockClearance
import com.leftovers.app.ui.components.EmptyState
import com.leftovers.app.ui.components.Glass
import com.leftovers.app.ui.components.GlassScreen
import com.leftovers.app.ui.components.PrimaryButton
import com.leftovers.app.ui.components.RoundButton
import com.leftovers.app.ui.components.SecondaryButton
import com.leftovers.app.ui.icons.Lucide
import com.leftovers.app.ui.theme.LocalAppColors
import com.leftovers.app.util.Money
import java.time.LocalDate
import java.time.format.DateTimeFormatter

@Composable
fun CreditCardScreen(
    onBack: () -> Unit,
    vm: CreditCardViewModel = viewModel(factory = AppViewModelProvider.Factory),
) {
    val c = LocalAppColors.current

    val cards by vm.cardsWithSummary.collectAsStateWithLifecycle()
    val currencySummaries by vm.currencySummaries.collectAsStateWithLifecycle()
    val accounts by vm.accounts.collectAsStateWithLifecycle()
    val categories by vm.categories.collectAsStateWithLifecycle()

    val cardStatements by vm.selectedCardStatements.collectAsStateWithLifecycle()
    val cardTransactions by vm.selectedCardTransactions.collectAsStateWithLifecycle()

    var showAddCardSheet by remember { mutableStateOf(false) }
    var cardToEdit by remember { mutableStateOf<CreditCard?>(null) }
    var cardForPayment by remember { mutableStateOf<Long?>(null) }
    var cardForStatement by remember { mutableStateOf<CreditCard?>(null) }
    var cardForTransaction by remember { mutableStateOf<CreditCard?>(null) }
    var cardDetailToShow by remember { mutableStateOf<CreditCardWithSummary?>(null) }

    GlassScreen(
        title = "Credit Cards",
        subtitle = "Manual personal tracking & reminders",
        onBack = onBack,
        actions = {
            RoundButton(Lucide.Plus, "Add Card", { showAddCardSheet = true })
        },
    ) { padding ->
        if (cards.isEmpty()) {
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                EmptyState(
                    icon = Lucide.CreditCard,
                    title = "No Credit Cards Added",
                    message = "Add your cards to manually track statement balances, available credit, and receive payment reminders before due dates.",
                )
                Spacer(Modifier.height(16.dp))
                PrimaryButton(
                    text = "Add Your First Card",
                    icon = Lucide.Plus,
                    onClick = { showAddCardSheet = true },
                    modifier = Modifier.fillMaxWidth(0.8f),
                )
            }
        } else {
            LazyColumn(
                Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = DockClearance),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                // Multi-Currency Summary Section
                item {
                    Text(
                        "Summary by Currency",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = c.textPrimary,
                    )
                    Spacer(Modifier.height(8.dp))
                    if (currencySummaries.size == 1) {
                        CurrencySummaryCard(summary = currencySummaries.first())
                    } else {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            items(currencySummaries) { summary ->
                                CurrencySummaryCard(summary = summary, modifier = Modifier.width(300.dp))
                            }
                        }
                    }
                }

                // Cards List Header
                item {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "My Cards (${cards.size})",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = c.textPrimary,
                        )
                    }
                }

                // List of credit cards
                items(cards, key = { it.card.id }) { cardItem ->
                    CreditCardItemCard(
                        cardWithSummary = cardItem,
                        onCardClick = {
                            vm.selectCard(cardItem.card.id)
                            cardDetailToShow = cardItem
                        },
                        onRecordPayment = {
                            vm.selectCard(cardItem.card.id)
                            cardForPayment = cardItem.card.id
                        },
                        onAddStatement = {
                            cardForStatement = cardItem.card
                        },
                        onAddTransaction = {
                            cardForTransaction = cardItem.card
                        },
                    )
                }
            }
        }
    }

    // Add / Edit Card Sheet
    if (showAddCardSheet || cardToEdit != null) {
        AddEditCreditCardSheet(
            existingCard = cardToEdit,
            onDismiss = {
                showAddCardSheet = false
                cardToEdit = null
            },
            onSave = { card ->
                vm.saveCard(card) {
                    showAddCardSheet = false
                    cardToEdit = null
                }
            },
        )
    }

    // Record Payment Sheet
    if (cardForPayment != null) {
        RecordPaymentSheet(
            cards = cards,
            preselectedCardId = cardForPayment,
            statements = cardStatements,
            accounts = accounts,
            onDismiss = { cardForPayment = null },
            onRecord = { cardId, amtMinor, curr, dateEpoch, stmtId, srcAccId, ref, notes ->
                vm.recordPayment(
                    cardId = cardId,
                    amountMinor = amtMinor,
                    currency = curr,
                    paymentEpochDay = dateEpoch,
                    statementId = stmtId,
                    sourceBankAccountId = srcAccId,
                    reference = ref,
                    notes = notes,
                ) {
                    cardForPayment = null
                }
            },
        )
    }

    // Add Statement Sheet
    if (cardForStatement != null) {
        AddCreditCardStatementSheet(
            card = cardForStatement!!,
            onDismiss = { cardForStatement = null },
            onSave = { stmt ->
                vm.saveStatement(stmt) {
                    cardForStatement = null
                }
            },
        )
    }

    // Add Transaction Sheet
    if (cardForTransaction != null) {
        AddCardTransactionSheet(
            card = cardForTransaction!!,
            categories = categories,
            onDismiss = { cardForTransaction = null },
            onSave = { tx, linkExpense, catId ->
                vm.recordCardTransaction(tx, linkExpense, catId) {
                    cardForTransaction = null
                }
            },
        )
    }

    // Card Detail Sheet
    if (cardDetailToShow != null) {
        val currentSelected = cards.find { it.card.id == cardDetailToShow!!.card.id } ?: cardDetailToShow!!
        CreditCardDetailSheet(
            cardWithSummary = currentSelected,
            statements = cardStatements,
            transactions = cardTransactions,
            onDismiss = { cardDetailToShow = null },
            onEditCard = {
                cardToEdit = currentSelected.card
                cardDetailToShow = null
            },
            onArchiveCard = {
                vm.archiveCard(currentSelected.card.id, !currentSelected.card.isArchived) {
                    cardDetailToShow = null
                }
            },
            onDeleteCard = {
                vm.deleteCard(currentSelected.card.id) {
                    cardDetailToShow = null
                }
            },
            onAddStatement = {
                cardForStatement = currentSelected.card
            },
            onAddTransaction = {
                cardForTransaction = currentSelected.card
            },
            onRecordPayment = {
                cardForPayment = currentSelected.card.id
            },
            onDeleteTransaction = { txId ->
                vm.deleteTransaction(txId)
            },
        )
    }
}

/**
 * Currency Summary Card
 * Shows totals for a specific currency without ever mixing with other currencies.
 */
@Composable
fun CurrencySummaryCard(
    summary: CreditCardCurrencySummary,
    modifier: Modifier = Modifier,
) {
    val c = LocalAppColors.current
    val money = Money(summary.currency)

    Glass(
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .clip(CircleShape)
                            .background(c.accent.copy(alpha = 0.15f))
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                    ) {
                        Text(summary.currency, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = c.accent)
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "${summary.cardCount} ${if (summary.cardCount == 1) "Card" else "Cards"}",
                        style = MaterialTheme.typography.bodySmall,
                        color = c.textSecondary,
                    )
                }

                if (summary.overduePaymentsCount > 0) {
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(c.negative.copy(alpha = 0.15f))
                            .padding(horizontal = 8.dp, vertical = 2.dp),
                    ) {
                        Text("${summary.overduePaymentsCount} OVERDUE", color = c.negative, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                    }
                } else if (summary.cardsWithUpcomingPayments > 0) {
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(c.accent.copy(alpha = 0.15f))
                            .padding(horizontal = 8.dp, vertical = 2.dp),
                    ) {
                        Text("${summary.cardsWithUpcomingPayments} UPCOMING", color = c.accent, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                    }
                }
            }

            Spacer(Modifier.height(14.dp))

            Text("Total Outstanding", style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
            Text(
                money.format(summary.totalOutstandingBalanceMinor),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = if (summary.totalOutstandingBalanceMinor > 0) c.textPrimary else c.positive,
            )

            Spacer(Modifier.height(12.dp))

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column {
                    Text("Statement Balance", style = MaterialTheme.typography.labelSmall, color = c.textTertiary)
                    Text(money.format(summary.totalStatementBalanceMinor), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = c.textPrimary)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text("Remaining Min Due", style = MaterialTheme.typography.labelSmall, color = c.textTertiary)
                    Text(
                        money.format(summary.totalRemainingMinPaymentMinor),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = if (summary.totalRemainingMinPaymentMinor > 0) c.accent else c.positive,
                    )
                }
            }
        }
    }
}

/**
 * Single Credit Card Item Tile in Dashboard
 */
@Composable
fun CreditCardItemCard(
    cardWithSummary: CreditCardWithSummary,
    onCardClick: () -> Unit,
    onRecordPayment: () -> Unit,
    onAddStatement: () -> Unit,
    onAddTransaction: () -> Unit,
) {
    val c = LocalAppColors.current
    val card = cardWithSummary.card
    val money = Money(card.currency)

    Glass(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(26.dp)),
        shape = RoundedCornerShape(26.dp),
        tint = if (cardWithSummary.status == CreditCardStatus.OVERDUE) c.negative.copy(alpha = 0.08f) else null,
    ) {
        Column(Modifier.padding(14.dp)) {
            // 1. Realistic Physical Credit Card Face (Tappable to view full details)
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(22.dp))
                    .clickable(onClick = onCardClick),
            ) {
                CreditCardVisual(
                    card = card,
                    status = cardWithSummary.status,
                )
            }

            Spacer(Modifier.height(14.dp))

            // 2. Financial Metrics: Outstanding Balance & Available Credit
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text("Total Outstanding", style = MaterialTheme.typography.labelSmall, color = c.textSecondary)
                    Spacer(Modifier.height(2.dp))
                    Text(
                        money.format(cardWithSummary.currentBalanceMinor),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = if (cardWithSummary.currentBalanceMinor > 0) c.textPrimary else c.positive,
                    )
                }

                Column(horizontalAlignment = Alignment.End) {
                    Text("Available Credit", style = MaterialTheme.typography.labelSmall, color = c.textSecondary)
                    Spacer(Modifier.height(2.dp))
                    Text(
                        cardWithSummary.availableCreditMinor?.let { money.format(it) } ?: "No Limit",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = c.accent,
                    )
                }
            }

            // 3. Credit Limit Utilization Bar (if creditLimitMinor > 0)
            if (card.creditLimitMinor > 0L) {
                Spacer(Modifier.height(10.dp))
                val ratio = (cardWithSummary.currentBalanceMinor.toFloat() / card.creditLimitMinor.toFloat()).coerceIn(0f, 1f)
                val percentInt = (ratio * 100).toInt()
                val barColor = when {
                    ratio > 0.85f -> c.negative
                    ratio > 0.50f -> Color(0xFFFFA000)
                    else -> c.positive
                }

                Column(Modifier.padding(horizontal = 4.dp)) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(5.dp)
                            .clip(CircleShape)
                            .background(c.glassStrong),
                    ) {
                        Box(
                            Modifier
                                .fillMaxWidth(ratio)
                                .height(5.dp)
                                .clip(CircleShape)
                                .background(barColor),
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            "$percentInt% limit used",
                            style = MaterialTheme.typography.labelSmall,
                            color = barColor,
                            fontWeight = FontWeight.Medium,
                        )
                        Text(
                            "Limit: ${money.format(card.creditLimitMinor)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = c.textTertiary,
                        )
                    }
                }
            }

            // 4. Statement Due Banner (if statement is active and has balance)
            val stmt = cardWithSummary.latestStatement
            if (stmt != null && stmt.remainingBalanceMinor > 0L) {
                Spacer(Modifier.height(10.dp))
                val isOverdue = stmt.status == com.leftovers.app.data.creditcard.StatementStatus.OVERDUE
                val bannerTint = if (isOverdue) c.negative.copy(alpha = 0.12f) else c.accent.copy(alpha = 0.10f)
                val borderColor = if (isOverdue) c.negative.copy(alpha = 0.35f) else c.accent.copy(alpha = 0.25f)

                Box(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(bannerTint)
                        .border(1.dp, borderColor, RoundedCornerShape(14.dp))
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                ) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Lucide.Clock,
                                    contentDescription = null,
                                    tint = if (isOverdue) c.negative else c.accent,
                                    modifier = Modifier.size(14.dp),
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    "Due ${LocalDate.ofEpochDay(stmt.statement.dueEpochDay).format(DateTimeFormatter.ofPattern("MMM dd"))}",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = if (isOverdue) c.negative else c.textPrimary,
                                )
                            }
                            Spacer(Modifier.height(2.dp))
                            Text(
                                "Remaining: ${money.format(stmt.remainingBalanceMinor)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = c.textSecondary,
                            )
                        }

                        if (stmt.remainingMinDueMinor > 0L) {
                            Box(
                                Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(c.accent.copy(alpha = 0.18f))
                                    .padding(horizontal = 8.dp, vertical = 4.dp),
                            ) {
                                Text(
                                    "Min: ${money.format(stmt.remainingMinDueMinor)}",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = c.accent,
                                )
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(14.dp))

            // 5. Clean Action Buttons: Spacious Record Payment + 2 Round Actions
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PrimaryButton(
                    text = "Record Payment",
                    icon = Lucide.Check,
                    onClick = onRecordPayment,
                    modifier = Modifier.weight(1f),
                )
                RoundButton(
                    icon = Lucide.Plus,
                    contentDescription = "Add Expense Entry",
                    onClick = onAddTransaction,
                    size = 56.dp,
                )
                RoundButton(
                    icon = Lucide.ReceiptText,
                    contentDescription = "Add Statement",
                    onClick = onAddStatement,
                    size = 56.dp,
                )
            }
        }
    }
}
