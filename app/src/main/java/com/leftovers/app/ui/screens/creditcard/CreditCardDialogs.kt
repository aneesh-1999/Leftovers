package com.leftovers.app.ui.screens.creditcard

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.leftovers.app.data.AccountWithBalance
import com.leftovers.app.data.Category
import com.leftovers.app.data.Palette
import com.leftovers.app.data.creditcard.CardNetwork
import com.leftovers.app.data.creditcard.CreditCard
import com.leftovers.app.data.creditcard.CreditCardMath
import com.leftovers.app.data.creditcard.CreditCardStatement
import com.leftovers.app.data.creditcard.CreditCardStatus
import com.leftovers.app.data.creditcard.CreditCardTransaction
import com.leftovers.app.data.creditcard.CreditCardTxType
import com.leftovers.app.data.creditcard.CreditCardWithSummary
import com.leftovers.app.data.creditcard.StatementStatus
import com.leftovers.app.data.creditcard.StatementWithAllocations
import com.leftovers.app.ui.components.Chip
import com.leftovers.app.ui.components.Glass
import com.leftovers.app.ui.components.PrimaryButton
import com.leftovers.app.ui.components.RoundButton
import com.leftovers.app.ui.components.SecondaryButton
import com.leftovers.app.ui.components.SegmentedToggle
import com.leftovers.app.ui.components.StatTile
import com.leftovers.app.ui.icons.Lucide
import com.leftovers.app.ui.screens.GlassSheet
import com.leftovers.app.ui.screens.GlassTextField
import com.leftovers.app.ui.theme.LocalAppColors
import com.leftovers.app.util.Money
import java.time.LocalDate
import java.time.format.DateTimeFormatter

private val POPULAR_CURRENCIES = listOf("EUR", "USD", "NPR", "GBP", "AUD", "CAD", "INR", "JPY")

@Composable
fun CreditCardTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String = "",
    placeholder: String = "",
    prefix: String? = null,
    keyboardType: KeyboardType = KeyboardType.Text,
    modifier: Modifier = Modifier,
    singleLine: Boolean = true,
) {
    GlassTextField(
        value = value,
        onValueChange = onValueChange,
        label = if (label.isNotBlank()) label else null,
        placeholder = placeholder,
        prefix = prefix,
        keyboardType = keyboardType,
        singleLine = singleLine,
        modifier = modifier,
    )
}

/**
 * Add / Edit Credit Card Bottom Sheet
 */
@Composable
fun AddEditCreditCardSheet(
    existingCard: CreditCard? = null,
    onDismiss: () -> Unit,
    onSave: (CreditCard) -> Unit,
) {
    val c = LocalAppColors.current

    var nickname by remember { mutableStateOf(existingCard?.nickname.orEmpty()) }
    var bankIssuer by remember { mutableStateOf(existingCard?.bankIssuer.orEmpty()) }
    var network by remember { mutableStateOf(existingCard?.network ?: CardNetwork.VISA.label) }
    var lastFourDigits by remember { mutableStateOf(existingCard?.lastFourDigits.orEmpty()) }
    var currency by remember { mutableStateOf(existingCard?.currency ?: "EUR") }
    var limitInput by remember {
        mutableStateOf(if ((existingCard?.creditLimitMinor ?: 0L) > 0L) (existingCard!!.creditLimitMinor / 100.0).toString() else "")
    }
    var closingDayInput by remember {
        mutableStateOf(if ((existingCard?.billingCycleClosingDay ?: 0) > 0) existingCard!!.billingCycleClosingDay.toString() else "")
    }
    var dueDaysInput by remember {
        mutableStateOf(if ((existingCard?.paymentDueDaysAfterClosing ?: 0) > 0) existingCard!!.paymentDueDaysAfterClosing.toString() else "")
    }
    var minPercentInput by remember {
        mutableStateOf(if ((existingCard?.minPaymentPercentage ?: 0.0) > 0.0) existingCard!!.minPaymentPercentage.toString() else "")
    }
    var minFlatInput by remember {
        mutableStateOf(if ((existingCard?.minPaymentFlatMinor ?: 0L) > 0L) (existingCard!!.minPaymentFlatMinor / 100.0).toString() else "")
    }
    var selectedColor by remember { mutableLongStateOf(existingCard?.color ?: Palette.IRIS) }
    var notes by remember { mutableStateOf(existingCard?.notes.orEmpty()) }
    var errorText by remember { mutableStateOf<String?>(null) }

    GlassSheet(onDismiss = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp)
                .navigationBarsPadding(),
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    if (existingCard == null) "Add Credit Card" else "Edit Credit Card",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = c.textPrimary,
                )
                RoundButton(Lucide.X, "Close", onDismiss)
            }

            Spacer(Modifier.height(16.dp))

            LazyColumn(
                Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                // Live Card Preview
                item {
                    val previewCard = CreditCard(
                        id = existingCard?.id ?: 0L,
                        nickname = nickname.ifBlank { "Card Nickname" },
                        bankIssuer = bankIssuer.ifBlank { "Bank Issuer" },
                        network = network,
                        lastFourDigits = lastFourDigits,
                        color = selectedColor,
                        currency = currency,
                        creditLimitMinor = (limitInput.toDoubleOrNull()?.times(100))?.toLong() ?: 0L,
                        billingCycleClosingDay = closingDayInput.toIntOrNull() ?: 0,
                        paymentDueDaysAfterClosing = dueDaysInput.toIntOrNull() ?: 0,
                    )
                    CreditCardVisual(card = previewCard)
                }

                // Security Note
                item {
                    Glass(
                        Modifier.fillMaxWidth(),
                        tint = c.accent.copy(alpha = 0.08f),
                    ) {
                        Row(
                            Modifier.padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Lucide.Shield, contentDescription = null, tint = c.accent, modifier = Modifier.size(22.dp))
                            Spacer(Modifier.width(12.dp))
                            Text(
                                "Privacy First: We never request or store full card numbers, CVVs, or bank credentials. Personal offline tracking only.",
                                style = MaterialTheme.typography.bodySmall,
                                color = c.textSecondary,
                            )
                        }
                    }
                }

                // Nickname & Issuer
                item {
                    CreditCardTextField(
                        value = nickname,
                        onValueChange = { nickname = it },
                        label = "Card Nickname *",
                        placeholder = "e.g. Travel Sapphire, Everyday Rewards",
                    )
                }

                item {
                    CreditCardTextField(
                        value = bankIssuer,
                        onValueChange = { bankIssuer = it },
                        label = "Bank / Card Issuer *",
                        placeholder = "e.g. Chase, Nabil Bank, BNP Paribas",
                    )
                }

                // Network & Last 4 digits
                item {
                    Column(Modifier.fillMaxWidth()) {
                        Text("Card Network", style = MaterialTheme.typography.labelMedium, color = c.textSecondary)
                        Spacer(Modifier.height(6.dp))
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            items(CardNetwork.entries) { net ->
                                Chip(
                                    text = net.label,
                                    selected = network == net.label,
                                    onClick = { network = net.label },
                                )
                            }
                        }
                    }
                }

                item {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        CreditCardTextField(
                            value = lastFourDigits,
                            onValueChange = { if (it.length <= 4 && it.all { ch -> ch.isDigit() }) lastFourDigits = it },
                            label = "Last 4 digits (optional)",
                            placeholder = "4321",
                            keyboardType = KeyboardType.Number,
                            modifier = Modifier.weight(1f),
                        )

                        CreditCardTextField(
                            value = limitInput,
                            onValueChange = { limitInput = it },
                            label = "Credit Limit (optional)",
                            placeholder = "2500",
                            keyboardType = KeyboardType.Decimal,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }

                // Currency selection
                item {
                    Column {
                        Text("Card Currency *", style = MaterialTheme.typography.labelMedium, color = c.textSecondary)
                        Spacer(Modifier.height(6.dp))
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            items(POPULAR_CURRENCIES) { curr ->
                                Chip(
                                    text = curr,
                                    selected = currency.equals(curr, ignoreCase = true),
                                    onClick = { currency = curr },
                                )
                            }
                        }
                    }
                }

                // Billing Cycle Configuration
                item {
                    Text("Billing Cycle & Due Dates (Optional)", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = c.textPrimary)
                    Spacer(Modifier.height(6.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        CreditCardTextField(
                            value = closingDayInput,
                            onValueChange = { if (it.length <= 2 && it.all { ch -> ch.isDigit() }) closingDayInput = it },
                            label = "Closing Day (1-31)",
                            placeholder = "15",
                            keyboardType = KeyboardType.Number,
                            modifier = Modifier.weight(1f),
                        )

                        CreditCardTextField(
                            value = dueDaysInput,
                            onValueChange = { if (it.length <= 2 && it.all { ch -> ch.isDigit() }) dueDaysInput = it },
                            label = "Days to Pay (e.g. 20)",
                            placeholder = "20",
                            keyboardType = KeyboardType.Number,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }

                // Minimum payment estimation config
                item {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        CreditCardTextField(
                            value = minPercentInput,
                            onValueChange = { minPercentInput = it },
                            label = "Min Pay % (e.g. 5)",
                            placeholder = "5.0",
                            keyboardType = KeyboardType.Decimal,
                            modifier = Modifier.weight(1f),
                        )

                        CreditCardTextField(
                            value = minFlatInput,
                            onValueChange = { minFlatInput = it },
                            label = "Min Flat Amount",
                            placeholder = "25.00",
                            keyboardType = KeyboardType.Decimal,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }

                // Card Color
                item {
                    Column {
                        Text("Card Theme Color", style = MaterialTheme.typography.labelMedium, color = c.textSecondary)
                        Spacer(Modifier.height(8.dp))
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            items(Palette.all) { colorVal ->
                                val isSelected = selectedColor == colorVal
                                Box(
                                    Modifier
                                        .size(36.dp)
                                        .clip(CircleShape)
                                        .background(Color(colorVal))
                                        .border(if (isSelected) 3.dp else 1.dp, if (isSelected) Color.White else Color.Transparent, CircleShape)
                                        .clickable { selectedColor = colorVal },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    if (isSelected) {
                                        Icon(Lucide.Check, contentDescription = null, tint = Color.Black, modifier = Modifier.size(16.dp))
                                    }
                                }
                            }
                        }
                    }
                }

                // Notes
                item {
                    CreditCardTextField(
                        value = notes,
                        onValueChange = { notes = it },
                        label = "Notes (optional)",
                        placeholder = "Card perks, cash back categories, etc.",
                    )
                }

                if (errorText != null) {
                    item {
                        Text(errorText!!, color = c.negative, style = MaterialTheme.typography.bodySmall)
                    }
                }

                // Save button
                item {
                    Spacer(Modifier.height(10.dp))
                    PrimaryButton(
                        text = if (existingCard == null) "Create Card" else "Save Changes",
                        icon = Lucide.Check,
                        onClick = {
                            if (nickname.isBlank()) {
                                errorText = "Card nickname is required."
                                return@PrimaryButton
                            }
                            if (bankIssuer.isBlank()) {
                                errorText = "Bank or card issuer is required."
                                return@PrimaryButton
                            }

                            val limitMinor = (limitInput.toDoubleOrNull()?.times(100))?.toLong() ?: 0L
                            val closingDay = closingDayInput.toIntOrNull()?.coerceIn(1, 31) ?: 0
                            val dueDays = dueDaysInput.toIntOrNull() ?: 0
                            val minPercent = minPercentInput.toDoubleOrNull() ?: 0.0
                            val minFlatMinor = (minFlatInput.toDoubleOrNull()?.times(100))?.toLong() ?: 0L

                            val cardToSave = (existingCard ?: CreditCard(nickname = nickname, bankIssuer = bankIssuer)).copy(
                                nickname = nickname.trim(),
                                bankIssuer = bankIssuer.trim(),
                                network = network,
                                lastFourDigits = lastFourDigits.trim(),
                                currency = currency.uppercase().trim(),
                                creditLimitMinor = limitMinor,
                                billingCycleClosingDay = closingDay,
                                paymentDueDaysAfterClosing = dueDays,
                                minPaymentPercentage = minPercent,
                                minPaymentFlatMinor = minFlatMinor,
                                color = selectedColor,
                                notes = notes.trim(),
                            )
                            onSave(cardToSave)
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(16.dp))
                }
            }
        }
    }
}

/**
 * Record a Credit Card Statement Manually
 */
@Composable
fun AddCreditCardStatementSheet(
    card: CreditCard,
    onDismiss: () -> Unit,
    onSave: (CreditCardStatement) -> Unit,
) {
    val c = LocalAppColors.current
    val today = LocalDate.now()

    var closingDate by remember { mutableStateOf(today.minusDays(10)) }
    var dueDate by remember { mutableStateOf(today.plusDays(15)) }
    var balanceInput by remember { mutableStateOf("") }
    var minDueInput by remember { mutableStateOf("") }
    var reference by remember { mutableStateOf("") }
    var notes by remember { mutableStateOf("") }
    var errorText by remember { mutableStateOf<String?>(null) }

    GlassSheet(onDismiss = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp)
                .navigationBarsPadding(),
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text("Add Statement", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = c.textPrimary)
                    Text(card.nickname, style = MaterialTheme.typography.bodySmall, color = c.accent)
                }
                RoundButton(Lucide.X, "Close", onDismiss)
            }

            Spacer(Modifier.height(16.dp))

            LazyColumn(
                Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                // Statement Balance
                item {
                    CreditCardTextField(
                        value = balanceInput,
                        onValueChange = {
                            balanceInput = it
                            val balMinor = (it.toDoubleOrNull()?.times(100))?.toLong() ?: 0L
                            if (balMinor > 0L && minDueInput.isBlank()) {
                                val est = CreditCardMath.estimateMinimumPayment(
                                    balanceMinor = balMinor,
                                    minPercentage = card.minPaymentPercentage,
                                    minFlatMinor = card.minPaymentFlatMinor,
                                )
                                if (est > 0L) {
                                    minDueInput = String.format("%.2f", est / 100.0)
                                }
                            }
                        },
                        label = "Statement Balance (${card.currency}) *",
                        placeholder = "0.00",
                        prefix = Money(card.currency).symbol,
                        keyboardType = KeyboardType.Decimal,
                    )
                }

                // Minimum Payment Due
                item {
                    CreditCardTextField(
                        value = minDueInput,
                        onValueChange = { minDueInput = it },
                        label = "Minimum Payment Due (${card.currency}) *",
                        placeholder = "0.00",
                        prefix = Money(card.currency).symbol,
                        keyboardType = KeyboardType.Decimal,
                    )
                }

                // Dates
                item {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text("Closing Date", style = MaterialTheme.typography.labelSmall, color = c.textSecondary)
                            Spacer(Modifier.height(4.dp))
                            Glass(Modifier.fillMaxWidth().clickable { closingDate = closingDate.minusDays(1) }) {
                                Text(
                                    closingDate.format(DateTimeFormatter.ofPattern("MMM dd, yyyy")),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = c.textPrimary,
                                    modifier = Modifier.padding(12.dp),
                                )
                            }
                        }

                        Column(Modifier.weight(1f)) {
                            Text("Payment Due Date", style = MaterialTheme.typography.labelSmall, color = c.textSecondary)
                            Spacer(Modifier.height(4.dp))
                            Glass(Modifier.fillMaxWidth().clickable { dueDate = dueDate.plusDays(1) }) {
                                Text(
                                    dueDate.format(DateTimeFormatter.ofPattern("MMM dd, yyyy")),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = c.accent,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.padding(12.dp),
                                )
                            }
                        }
                    }
                }

                // Reference / notes
                item {
                    CreditCardTextField(
                        value = reference,
                        onValueChange = { reference = it },
                        label = "Statement Ref / Period (optional)",
                        placeholder = "e.g. STMT-2026-04",
                    )
                }

                item {
                    CreditCardTextField(
                        value = notes,
                        onValueChange = { notes = it },
                        label = "Notes (optional)",
                    )
                }

                if (errorText != null) {
                    item {
                        Text(errorText!!, color = c.negative, style = MaterialTheme.typography.bodySmall)
                    }
                }

                item {
                    Spacer(Modifier.height(10.dp))
                    PrimaryButton(
                        text = "Save Statement",
                        icon = Lucide.Check,
                        onClick = {
                            val bal = balanceInput.toDoubleOrNull()
                            if (bal == null || bal <= 0.0) {
                                errorText = "Please enter a valid statement balance."
                                return@PrimaryButton
                            }
                            val minDue = minDueInput.toDoubleOrNull() ?: 0.0
                            val balMinor = (bal * 100).toLong()
                            val minMinor = (minDue * 100).toLong().coerceAtMost(balMinor)

                            onSave(
                                CreditCardStatement(
                                    cardId = card.id,
                                    closingEpochDay = closingDate.toEpochDay(),
                                    dueEpochDay = dueDate.toEpochDay(),
                                    statementBalanceMinor = balMinor,
                                    minPaymentDueMinor = minMinor,
                                    currency = card.currency,
                                    reference = reference.trim(),
                                    notes = notes.trim(),
                                ),
                            )
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(16.dp))
                }
            }
        }
    }
}

/**
 * Record Payment Bottom Sheet
 */
@Composable
fun RecordPaymentSheet(
    cards: List<CreditCardWithSummary>,
    preselectedCardId: Long?,
    statements: List<StatementWithAllocations>,
    accounts: List<AccountWithBalance>,
    onDismiss: () -> Unit,
    onRecord: (
        cardId: Long,
        amountMinor: Long,
        currency: String,
        paymentEpochDay: Long,
        statementId: Long?,
        sourceBankAccountId: Long?,
        reference: String,
        notes: String,
    ) -> Unit,
) {
    val c = LocalAppColors.current
    var selectedCard by remember {
        mutableStateOf(cards.find { it.card.id == preselectedCardId } ?: cards.firstOrNull())
    }
    var selectedStatementId by remember {
        mutableStateOf(statements.firstOrNull { it.remainingBalanceMinor > 0L }?.statement?.id)
    }
    var amountInput by remember {
        val defaultAmt = statements.firstOrNull { it.remainingBalanceMinor > 0L }?.remainingBalanceMinor ?: 0L
        mutableStateOf(if (defaultAmt > 0L) String.format("%.2f", defaultAmt / 100.0) else "")
    }
    var paymentDate by remember { mutableStateOf(LocalDate.now()) }
    var selectedAccountId by remember { mutableStateOf<Long?>(null) }
    var reference by remember { mutableStateOf("") }
    var notes by remember { mutableStateOf("") }
    var errorText by remember { mutableStateOf<String?>(null) }

    GlassSheet(onDismiss = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp)
                .navigationBarsPadding(),
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text("Record Payment", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = c.textPrimary)
                    Text("Reduces card debt without double counting expenses", style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
                }
                RoundButton(Lucide.X, "Close", onDismiss)
            }

            Spacer(Modifier.height(16.dp))

            LazyColumn(
                Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                // Card summary banner
                item {
                    selectedCard?.let { sc ->
                        val cardMoney = Money(sc.card.currency)
                        Glass(
                            Modifier.fillMaxWidth(),
                            tint = Color(sc.card.color).copy(alpha = 0.16f),
                        ) {
                            Row(
                                Modifier.padding(14.dp).fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column {
                                    Text(sc.card.nickname, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = c.textPrimary)
                                    Text(sc.card.bankIssuer, style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
                                }
                                Column(horizontalAlignment = Alignment.End) {
                                    Text("Total Owed", style = MaterialTheme.typography.labelSmall, color = c.textSecondary)
                                    Text(cardMoney.format(sc.currentBalanceMinor), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = c.accent)
                                }
                            }
                        }
                    }
                }

                if (cards.size > 1) {
                    item {
                        Column {
                            Text("Select Credit Card", style = MaterialTheme.typography.labelMedium, color = c.textSecondary)
                            Spacer(Modifier.height(6.dp))
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                items(cards) { cardItem ->
                                    Chip(
                                        text = "${cardItem.card.nickname} (${cardItem.card.currency})",
                                        selected = selectedCard?.card?.id == cardItem.card.id,
                                        onClick = {
                                            selectedCard = cardItem
                                            selectedStatementId = null
                                        },
                                    )
                                }
                            }
                        }
                    }
                }

                item {
                    val activeStmts = statements.filter { it.remainingBalanceMinor > 0L }
                    Column {
                        Text("Apply to Statement", style = MaterialTheme.typography.labelMedium, color = c.textSecondary)
                        Spacer(Modifier.height(6.dp))
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            item {
                                Chip(
                                    text = "Unallocated",
                                    selected = selectedStatementId == null,
                                    onClick = { selectedStatementId = null },
                                )
                            }
                            items(activeStmts) { stmtItem ->
                                val cardMoney = Money(selectedCard?.card?.currency ?: "EUR")
                                val isSelected = selectedStatementId == stmtItem.statement.id
                                Chip(
                                    text = "Due ${LocalDate.ofEpochDay(stmtItem.statement.dueEpochDay)} (${cardMoney.format(stmtItem.remainingBalanceMinor)} left)",
                                    selected = isSelected,
                                    onClick = {
                                        selectedStatementId = stmtItem.statement.id
                                        amountInput = String.format("%.2f", stmtItem.remainingBalanceMinor / 100.0)
                                    },
                                )
                            }
                        }
                    }
                }

                // Payment Amount with Quick Presets
                item {
                    val card = selectedCard?.card
                    val cardMoney = Money(card?.currency ?: "EUR")
                    val activeStmt = statements.find { it.statement.id == selectedStatementId }
                    val stmtBal = activeStmt?.remainingBalanceMinor ?: 0L
                    val minDue = activeStmt?.remainingMinDueMinor ?: 0L
                    val totalBal = selectedCard?.currentBalanceMinor ?: 0L

                    Column {
                        CreditCardTextField(
                            value = amountInput,
                            onValueChange = { amountInput = it },
                            label = "Payment Amount (${card?.currency ?: "EUR"}) *",
                            placeholder = "0.00",
                            prefix = cardMoney.symbol,
                            keyboardType = KeyboardType.Decimal,
                        )

                        // Quick Presets
                        if (stmtBal > 0L || minDue > 0L || totalBal > 0L) {
                            Spacer(Modifier.height(8.dp))
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                if (stmtBal > 0L) {
                                    item {
                                        val targetStr = String.format("%.2f", stmtBal / 100.0)
                                        Chip(
                                            text = "Statement (${cardMoney.format(stmtBal)})",
                                            selected = amountInput == targetStr,
                                            onClick = { amountInput = targetStr },
                                        )
                                    }
                                }
                                if (minDue > 0L && minDue != stmtBal) {
                                    item {
                                        val targetStr = String.format("%.2f", minDue / 100.0)
                                        Chip(
                                            text = "Min Due (${cardMoney.format(minDue)})",
                                            selected = amountInput == targetStr,
                                            onClick = { amountInput = targetStr },
                                        )
                                    }
                                }
                                if (totalBal > 0L && totalBal != stmtBal) {
                                    item {
                                        val targetStr = String.format("%.2f", totalBal / 100.0)
                                        Chip(
                                            text = "Full (${cardMoney.format(totalBal)})",
                                            selected = amountInput == targetStr,
                                            onClick = { amountInput = targetStr },
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                item {
                    Column {
                        Text("Pay from Bank / Cash Account (Optional)", style = MaterialTheme.typography.labelMedium, color = c.textSecondary)
                        Text(
                            "Atomically reduces bank balance. Will not create duplicate expense transactions.",
                            style = MaterialTheme.typography.bodySmall,
                            color = c.textTertiary,
                        )
                        Spacer(Modifier.height(6.dp))
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            item {
                                Chip(
                                    text = "None (Manual tracking only)",
                                    selected = selectedAccountId == null,
                                    onClick = { selectedAccountId = null },
                                )
                            }
                            items(accounts) { acc ->
                                val isSelected = selectedAccountId == acc.id
                                val money = Money(acc.currency ?: "EUR")
                                Chip(
                                    text = "${acc.name} (${money.format(acc.balanceMinor)})",
                                    selected = isSelected,
                                    onClick = { selectedAccountId = acc.id },
                                )
                            }
                        }
                    }
                }

                item {
                    CreditCardTextField(
                        value = reference,
                        onValueChange = { reference = it },
                        label = "Payment Reference (optional)",
                        placeholder = "e.g. Bank confirmation #",
                    )
                }

                item {
                    CreditCardTextField(
                        value = notes,
                        onValueChange = { notes = it },
                        label = "Notes (optional)",
                    )
                }

                if (errorText != null) {
                    item {
                        Text(errorText!!, color = c.negative, style = MaterialTheme.typography.bodySmall)
                    }
                }

                item {
                    Spacer(Modifier.height(10.dp))
                    PrimaryButton(
                        text = "Confirm Payment",
                        icon = Lucide.Check,
                        onClick = {
                            val card = selectedCard?.card
                            if (card == null) {
                                errorText = "Please select a credit card."
                                return@PrimaryButton
                            }
                            val amt = amountInput.toDoubleOrNull()
                            if (amt == null || amt <= 0.0) {
                                errorText = "Please enter a valid payment amount."
                                return@PrimaryButton
                            }
                            val amtMinor = (amt * 100).toLong()

                            onRecord(
                                card.id,
                                amtMinor,
                                card.currency,
                                paymentDate.toEpochDay(),
                                selectedStatementId,
                                selectedAccountId,
                                reference.trim(),
                                notes.trim(),
                            )
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(16.dp))
                }
            }
        }
    }
}

/**
 * Add Transaction Bottom Sheet (Purchase, Refund, Fee, Interest)
 */
@Composable
fun AddCardTransactionSheet(
    card: CreditCard,
    categories: List<Category>,
    onDismiss: () -> Unit,
    onSave: (CreditCardTransaction, createFinanceTx: Boolean, financeCatId: Long?) -> Unit,
) {
    val c = LocalAppColors.current
    var selectedType by remember { mutableStateOf(CreditCardTxType.PURCHASE) }
    var description by remember { mutableStateOf("") }
    var amountInput by remember { mutableStateOf("") }
    var selectedCategory by remember { mutableStateOf(categories.firstOrNull { it.name.contains("Shopping", ignoreCase = true) } ?: categories.firstOrNull()) }
    var createFinanceExpense by remember { mutableStateOf(true) }
    var notes by remember { mutableStateOf("") }
    var errorText by remember { mutableStateOf<String?>(null) }

    GlassSheet(onDismiss = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp)
                .navigationBarsPadding(),
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text("Add Card Entry", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = c.textPrimary)
                    Text(card.nickname, style = MaterialTheme.typography.bodySmall, color = c.accent)
                }
                RoundButton(Lucide.X, "Close", onDismiss)
            }

            Spacer(Modifier.height(16.dp))

            LazyColumn(
                Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                item {
                    SegmentedToggle(
                        options = listOf(CreditCardTxType.PURCHASE, CreditCardTxType.REFUND, CreditCardTxType.FEE, CreditCardTxType.INTEREST),
                        selected = selectedType,
                        label = { it.label },
                        onSelect = { selectedType = it },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                item {
                    CreditCardTextField(
                        value = description,
                        onValueChange = { description = it },
                        label = "Description *",
                        placeholder = if (selectedType == CreditCardTxType.PURCHASE) "e.g. Airline tickets, Grocery run" else "e.g. Annual card fee",
                    )
                }

                item {
                    CreditCardTextField(
                        value = amountInput,
                        onValueChange = { amountInput = it },
                        label = "Amount (${card.currency}) *",
                        placeholder = "0.00",
                        prefix = Money(card.currency).symbol,
                        keyboardType = KeyboardType.Decimal,
                    )
                }

                item {
                    Column {
                        Text("Category", style = MaterialTheme.typography.labelMedium, color = c.textSecondary)
                        Spacer(Modifier.height(6.dp))
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            items(categories) { cat ->
                                Chip(
                                    text = cat.name,
                                    selected = selectedCategory?.id == cat.id,
                                    onClick = { selectedCategory = cat },
                                )
                            }
                        }
                    }
                }

                if (selectedType == CreditCardTxType.PURCHASE) {
                    item {
                        Glass(Modifier.fillMaxWidth()) {
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(14.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text("Link to Finance Expenses", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = c.textPrimary)
                                    Text("Records expense in budget immediately so spending is tracked", style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
                                }
                                Switch(
                                    checked = createFinanceExpense,
                                    onCheckedChange = { createFinanceExpense = it },
                                    colors = SwitchDefaults.colors(checkedThumbColor = c.accent),
                                )
                            }
                        }
                    }
                }

                item {
                    CreditCardTextField(
                        value = notes,
                        onValueChange = { notes = it },
                        label = "Notes (optional)",
                    )
                }

                if (errorText != null) {
                    item {
                        Text(errorText!!, color = c.negative, style = MaterialTheme.typography.bodySmall)
                    }
                }

                item {
                    Spacer(Modifier.height(10.dp))
                    PrimaryButton(
                        text = "Save Entry",
                        icon = Lucide.Check,
                        onClick = {
                            if (description.isBlank()) {
                                errorText = "Description is required."
                                return@PrimaryButton
                            }
                            val amt = amountInput.toDoubleOrNull()
                            if (amt == null || amt <= 0.0) {
                                errorText = "Please enter a valid amount."
                                return@PrimaryButton
                            }
                            val amtMinor = (amt * 100).toLong()

                            val tx = CreditCardTransaction(
                                cardId = card.id,
                                type = selectedType,
                                dateEpochDay = LocalDate.now().toEpochDay(),
                                amountMinor = amtMinor,
                                currency = card.currency,
                                description = description.trim(),
                                category = selectedCategory?.name.orEmpty(),
                                notes = notes.trim(),
                            )

                            onSave(
                                tx,
                                createFinanceExpense && selectedType == CreditCardTxType.PURCHASE,
                                selectedCategory?.id,
                            )
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(16.dp))
                }
            }
        }
    }
}

/**
 * Full Card Details Sheet showing Card hero, Limits, Cycle Estimates, Statements and Transactions
 */
@Composable
fun CreditCardDetailSheet(
    cardWithSummary: CreditCardWithSummary,
    statements: List<StatementWithAllocations>,
    transactions: List<CreditCardTransaction>,
    onDismiss: () -> Unit,
    onEditCard: () -> Unit,
    onArchiveCard: () -> Unit,
    onDeleteCard: () -> Unit,
    onAddStatement: () -> Unit,
    onAddTransaction: () -> Unit,
    onRecordPayment: () -> Unit,
    onDeleteTransaction: (Long) -> Unit,
) {
    val c = LocalAppColors.current
    val card = cardWithSummary.card
    val money = Money(card.currency)

    var currentTab by remember { mutableStateOf("Statements") }

    GlassSheet(onDismiss = onDismiss) {
        LazyColumn(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding(),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Header
            item {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f, fill = false)) {
                        Text(
                            card.nickname,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = c.textPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            "${card.bankIssuer} · ${card.network}",
                            style = MaterialTheme.typography.bodySmall,
                            color = c.textSecondary,
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        RoundButton(Lucide.Pencil, "Edit", onEditCard)
                        RoundButton(Lucide.X, "Close", onDismiss)
                    }
                }
            }

            // Card Visual Graphic
            item {
                CreditCardVisual(
                    card = card,
                    status = cardWithSummary.status,
                )
            }

            // Stat Tiles: Available Credit, Limit
            item {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        StatTile(
                            label = "Available Credit*",
                            value = cardWithSummary.availableCreditMinor?.let { money.format(it) } ?: "No Limit",
                            modifier = Modifier.weight(1f),
                        )
                        StatTile(
                            label = "Credit Limit",
                            value = if (card.creditLimitMinor > 0) money.format(card.creditLimitMinor) else "Unset",
                            modifier = Modifier.weight(1f),
                        )
                    }
                    Text(
                        "* Available credit is an estimate based on recorded transactions and limit.",
                        style = MaterialTheme.typography.bodySmall,
                        color = c.textTertiary,
                        fontSize = 11.sp,
                    )
                }
            }

            // Quick actions
            item {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    PrimaryButton(
                        text = "Record Payment",
                        icon = Lucide.Check,
                        onClick = onRecordPayment,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        SecondaryButton(
                            text = "Add Entry",
                            icon = Lucide.Plus,
                            onClick = onAddTransaction,
                            modifier = Modifier.weight(1f),
                        )
                        SecondaryButton(
                            text = "Add Statement",
                            icon = Lucide.ReceiptText,
                            onClick = onAddStatement,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }

            // Payment Reminders & Cycle Schedule section
            item {
                PaymentRemindersCard(
                    cardWithSummary = cardWithSummary,
                    money = money,
                )
            }

            // Tab switch between Statements and Transactions
            item {
                SegmentedToggle(
                    options = listOf("Statements", "Transactions"),
                    selected = currentTab,
                    label = { it },
                    onSelect = { currentTab = it },
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            // List items for current tab
            if (currentTab == "Statements") {
                if (statements.isEmpty()) {
                    item {
                        Glass(Modifier.fillMaxWidth()) {
                            Column(
                                Modifier.padding(24.dp).fillMaxWidth(),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                Icon(Lucide.ReceiptText, contentDescription = null, tint = c.textTertiary, modifier = Modifier.size(32.dp))
                                Spacer(Modifier.height(8.dp))
                                Text("No statements recorded yet", style = MaterialTheme.typography.bodyMedium, color = c.textSecondary)
                                Text("Add your monthly statement to track payment due dates and remaining balances.", style = MaterialTheme.typography.bodySmall, color = c.textTertiary, textAlign = TextAlign.Center)
                            }
                        }
                    }
                } else {
                    items(statements) { stmtItem ->
                        val stmt = stmtItem.statement
                        val statusColor = when (stmtItem.status) {
                            StatementStatus.PAID -> c.positive
                            StatementStatus.OVERDUE -> c.negative
                            StatementStatus.PARTIALLY_PAID -> c.accent
                            else -> c.textPrimary
                        }

                        Glass(Modifier.fillMaxWidth()) {
                            Row(
                                Modifier.padding(14.dp).fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            "Due: ${LocalDate.ofEpochDay(stmt.dueEpochDay).format(DateTimeFormatter.ofPattern("MMM dd, yyyy"))}",
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.SemiBold,
                                            color = c.textPrimary,
                                        )
                                        Spacer(Modifier.width(8.dp))
                                        Box(
                                            Modifier
                                                .clip(RoundedCornerShape(6.dp))
                                                .background(statusColor.copy(alpha = 0.15f))
                                                .padding(horizontal = 6.dp, vertical = 2.dp),
                                        ) {
                                            Text(stmtItem.status.label, color = statusColor, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                                        }
                                    }
                                    Spacer(Modifier.height(4.dp))
                                    Text(
                                        "Statement: ${money.format(stmt.statementBalanceMinor)} · Remaining: ${money.format(stmtItem.remainingBalanceMinor)}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = c.textSecondary,
                                    )
                                    if (stmtItem.remainingMinDueMinor > 0L) {
                                        Text(
                                            "Min payment remaining: ${money.format(stmtItem.remainingMinDueMinor)}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = c.accent,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            } else {
                if (transactions.isEmpty()) {
                    item {
                        Glass(Modifier.fillMaxWidth()) {
                            Column(
                                Modifier.padding(24.dp).fillMaxWidth(),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                Icon(Lucide.ReceiptText, contentDescription = null, tint = c.textTertiary, modifier = Modifier.size(32.dp))
                                Spacer(Modifier.height(8.dp))
                                Text("No card transactions recorded", style = MaterialTheme.typography.bodyMedium, color = c.textSecondary)
                            }
                        }
                    }
                } else {
                    items(transactions) { tx ->
                        val isCharge = tx.type.isLiabilityIncrease
                        val sign = if (isCharge) "+" else "-"
                        val amountColor = if (isCharge) c.textPrimary else c.positive

                        Glass(Modifier.fillMaxWidth()) {
                            Row(
                                Modifier.padding(12.dp).fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(tx.description, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, color = c.textPrimary)
                                    Text(
                                        "${LocalDate.ofEpochDay(tx.dateEpochDay)} · ${tx.type.label} ${if (tx.category.isNotBlank()) "· " + tx.category else ""}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = c.textTertiary,
                                    )
                                }
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        "$sign${money.format(tx.amountMinor)}",
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = amountColor,
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    RoundButton(Lucide.Trash2, "Delete", { onDeleteTransaction(tx.id) }, size = 30.dp)
                                }
                            }
                        }
                    }
                }
            }

            // Card management options at bottom of sheet
            item {
                Spacer(Modifier.height(4.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    SecondaryButton(
                        text = if (card.isArchived) "Unarchive" else "Archive Card",
                        onClick = onArchiveCard,
                        modifier = Modifier.weight(1f),
                    )
                    SecondaryButton(
                        text = "Delete Card",
                        onClick = onDeleteCard,
                        modifier = Modifier.weight(1f),
                    )
                }
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

/**
 * Dedicated Payment Reminders and Billing Schedule Card
 */
@Composable
private fun PaymentRemindersCard(
    cardWithSummary: CreditCardWithSummary,
    money: Money,
) {
    val c = LocalAppColors.current
    val card = cardWithSummary.card
    val latestStmt = cardWithSummary.latestStatement

    Glass(
        Modifier.fillMaxWidth(),
        tint = c.accent.copy(alpha = 0.06f),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(32.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(c.accent.copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Lucide.Bell, contentDescription = null, tint = c.accent, modifier = Modifier.size(17.dp))
                    }
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text(
                            "Payment Reminders",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = c.textPrimary,
                        )
                        Text(
                            "Auto-scheduled notifications",
                            style = MaterialTheme.typography.bodySmall,
                            color = c.textSecondary,
                            fontSize = 11.sp,
                        )
                    }
                }

                // Active Badge
                Box(
                    Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(c.positive.copy(alpha = 0.15f))
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier
                                .size(5.dp)
                                .clip(CircleShape)
                                .background(c.positive),
                        )
                        Spacer(Modifier.width(5.dp))
                        Text(
                            "SCHEDULED",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = c.positive,
                            fontSize = 10.sp,
                        )
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            // Cycle Schedule: Closing day & Due day
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // Closing day
                Box(
                    Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .background(c.textPrimary.copy(alpha = 0.05f))
                        .padding(10.dp),
                ) {
                    Column {
                        Text("CLOSING DAY", style = MaterialTheme.typography.labelSmall, color = c.textTertiary, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(2.dp))
                        Text(
                            if (card.billingCycleClosingDay in 1..31) "Day ${card.billingCycleClosingDay}" else "Not set",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = c.textPrimary,
                        )
                    }
                }

                // Due day
                Box(
                    Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .background(c.textPrimary.copy(alpha = 0.05f))
                        .padding(10.dp),
                ) {
                    Column {
                        Text("DUE DAY", style = MaterialTheme.typography.labelSmall, color = c.textTertiary, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(2.dp))
                        val dueText = when {
                            card.paymentDueDay in 1..31 -> "Day ${card.paymentDueDay}"
                            card.paymentDueDaysAfterClosing in 1..90 -> "+${card.paymentDueDaysAfterClosing} days"
                            else -> "On statement"
                        }
                        Text(
                            dueText,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = c.textPrimary,
                        )
                    }
                }
            }

            // Next Due / Upcoming alert highlight
            if (latestStmt != null && latestStmt.remainingBalanceMinor > 0L) {
                Spacer(Modifier.height(10.dp))
                val isOverdue = latestStmt.daysUntilDue < 0
                val dueColor = if (isOverdue) c.negative else if (latestStmt.daysUntilDue <= 3) c.accent else c.positive
                Box(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(dueColor.copy(alpha = 0.10f))
                        .border(1.dp, dueColor.copy(alpha = 0.30f), RoundedCornerShape(8.dp))
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                ) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column {
                            val dueFormatted = LocalDate.ofEpochDay(latestStmt.statement.dueEpochDay).format(DateTimeFormatter.ofPattern("MMM dd, yyyy"))
                            val countdown = when {
                                latestStmt.daysUntilDue < 0 -> "${-latestStmt.daysUntilDue} days overdue"
                                latestStmt.daysUntilDue == 0L -> "Due today!"
                                latestStmt.daysUntilDue == 1L -> "Due tomorrow"
                                else -> "Due in ${latestStmt.daysUntilDue} days"
                            }
                            Text(
                                "Next payment: $countdown",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = dueColor,
                            )
                            Text(
                                "$dueFormatted · ${money.format(latestStmt.remainingBalanceMinor)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = c.textPrimary,
                            )
                        }
                        if (latestStmt.remainingMinDueMinor > 0L) {
                            Column(horizontalAlignment = Alignment.End) {
                                Text("Min Due", style = MaterialTheme.typography.labelSmall, color = c.textTertiary, fontSize = 9.sp)
                                Text(money.format(latestStmt.remainingMinDueMinor), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold, color = c.accent)
                            }
                        }
                    }
                }
            } else if (cardWithSummary.nextEstimatedClosingEpochDay != null) {
                Spacer(Modifier.height(8.dp))
                val nextClosing = LocalDate.ofEpochDay(cardWithSummary.nextEstimatedClosingEpochDay).format(DateTimeFormatter.ofPattern("MMM dd, yyyy"))
                Text(
                    "Next cycle closes on $nextClosing. No unpaid statement due.",
                    style = MaterialTheme.typography.bodySmall,
                    color = c.textTertiary,
                    fontSize = 11.5.sp,
                )
            }

            Spacer(Modifier.height(8.dp))
            Text(
                "Reminders trigger automatically at 09:15: 7 days, 3 days, 1 day before due date, on due date, and daily if overdue. Cleared once paid.",
                style = MaterialTheme.typography.bodySmall,
                color = c.textTertiary,
                fontSize = 10.5.sp,
            )
        }
    }
}
