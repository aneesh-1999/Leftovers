package com.leftovers.app.ui.screens

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.leftovers.app.data.Account
import com.leftovers.app.data.AccountRepository
import com.leftovers.app.data.AccountWithBalance
import com.leftovers.app.data.FeeMethod
import com.leftovers.app.data.Recipient
import com.leftovers.app.data.Remittance
import com.leftovers.app.data.RemittanceCalculations
import com.leftovers.app.data.RemittanceRepository
import com.leftovers.app.data.RemittanceStatus
import com.leftovers.app.data.StandardRemittanceProviders
import com.leftovers.app.ui.AppViewModelProvider
import com.leftovers.app.ui.components.CategoryIcon
import com.leftovers.app.ui.components.Chip
import com.leftovers.app.ui.components.Glass
import com.leftovers.app.ui.components.IconTile
import com.leftovers.app.ui.components.PrimaryButton
import com.leftovers.app.ui.components.RoundButton
import com.leftovers.app.ui.components.RowDivider
import com.leftovers.app.ui.components.SecondaryButton
import com.leftovers.app.ui.components.pressable
import com.leftovers.app.ui.icons.CategoryIcons
import com.leftovers.app.ui.icons.Lucide
import com.leftovers.app.ui.theme.LocalAppColors
import com.leftovers.app.util.AmountInput
import com.leftovers.app.util.LocalMoney
import com.leftovers.app.util.Money
import com.leftovers.app.util.ReceiptStore
import com.leftovers.app.util.friendlyLabel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

val CommonRelationships = listOf("Mother", "Father", "Sister", "Brother", "Spouse", "Self / My Nepal Account", "Friend", "Other")

class RemittanceEditorViewModel(
    private val repository: RemittanceRepository,
    private val accountRepository: AccountRepository,
) : ViewModel() {
    val recipients: StateFlow<List<Recipient>> = repository.activeRecipients
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val accounts: StateFlow<List<AccountWithBalance>> = accountRepository.accounts
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val providers: StateFlow<List<String>> = repository.providers
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), StandardRemittanceProviders)

    val customProviders: StateFlow<List<String>> = repository.customProviders
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    suspend fun saveRecipient(recipient: Recipient): Long = repository.saveRecipient(recipient)

    suspend fun saveRemittance(remittance: Remittance, createFinanceTx: Boolean): Long =
        repository.saveRemittance(remittance, createFinanceTx = createFinanceTx)

    suspend fun addCustomProvider(name: String) = repository.addCustomProvider(name)

    suspend fun updateCustomProvider(oldName: String, newName: String) = repository.updateCustomProvider(oldName, newName)

    suspend fun deleteCustomProvider(name: String): Boolean = repository.deleteCustomProvider(name)

    suspend fun countRemittancesForProvider(name: String): Int = repository.countRemittancesForProvider(name)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RemittanceEditorSheet(
    initialRemittance: Remittance? = null,
    onDismiss: () -> Unit,
    onSaved: (String) -> Unit,
    viewModel: RemittanceEditorViewModel = viewModel(factory = AppViewModelProvider.Factory),
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val c = LocalAppColors.current
    val recipients by viewModel.recipients.collectAsStateWithLifecycle()
    val accounts by viewModel.accounts.collectAsStateWithLifecycle()
    val allProviders by viewModel.providers.collectAsStateWithLifecycle()
    val customProviders by viewModel.customProviders.collectAsStateWithLifecycle()

    var transferDate by rememberSaveable { mutableStateOf(initialRemittance?.transferDate ?: LocalDate.now()) }
    var showDatePicker by rememberSaveable { mutableStateOf(false) }

    var provider by rememberSaveable { mutableStateOf(initialRemittance?.provider ?: "TapTap Send") }
    var showProviderPicker by rememberSaveable { mutableStateOf(false) }

    var showAddProviderDialog by rememberSaveable { mutableStateOf(false) }
    var newProviderName by rememberSaveable { mutableStateOf("") }
    var editingCustomProvider by rememberSaveable { mutableStateOf<String?>(null) }
    var editedProviderName by rememberSaveable { mutableStateOf("") }
    var deletingCustomProvider by rememberSaveable { mutableStateOf<String?>(null) }
    var deletingProviderInUseCount by rememberSaveable { mutableStateOf(0) }

    var sendAmountText by rememberSaveable {
        mutableStateOf(if (initialRemittance != null && initialRemittance.sendAmountMinor > 0) AmountInput.fromMinor(initialRemittance.sendAmountMinor) else "")
    }
    var sendCurrency by rememberSaveable { mutableStateOf(initialRemittance?.sendCurrency ?: "EUR") }

    var feeAmountText by rememberSaveable {
        mutableStateOf(if (initialRemittance != null && initialRemittance.feeAmountMinor > 0) AmountInput.fromMinor(initialRemittance.feeAmountMinor) else "")
    }
    var feeCurrency by rememberSaveable { mutableStateOf(initialRemittance?.feeCurrency ?: "EUR") }
    var feeMethod by rememberSaveable { mutableStateOf(initialRemittance?.feeMethod ?: FeeMethod.SEPARATE) }

    var selectedRecipient by remember { mutableStateOf<Recipient?>(null) }
    var showNewRecipientDialog by rememberSaveable { mutableStateOf(false) }
    var showRecipientPicker by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(recipients, initialRemittance) {
        if (selectedRecipient == null) {
            selectedRecipient = if (initialRemittance?.recipientId != null) {
                recipients.find { it.id == initialRemittance.recipientId }
            } else {
                recipients.firstOrNull()
            }
        }
    }

    var recipientName by rememberSaveable { mutableStateOf(initialRemittance?.recipientName ?: "") }
    var recipientCountry by rememberSaveable { mutableStateOf(initialRemittance?.recipientCountry ?: "Nepal") }
    var recipientBankOrWallet by rememberSaveable { mutableStateOf(initialRemittance?.recipientBankOrWallet ?: "") }
    var receiveCurrency by rememberSaveable { mutableStateOf(initialRemittance?.receiveCurrency ?: "NPR") }

    var exchangeRateText by rememberSaveable {
        mutableStateOf(if (initialRemittance != null && initialRemittance.exchangeRate > 0.0) initialRemittance.exchangeRate.toString() else "147.00")
    }

    var actualReceiveAmountText by rememberSaveable {
        mutableStateOf(if (initialRemittance != null && initialRemittance.actualReceiveAmountMinor != null) AmountInput.fromMinor(initialRemittance.actualReceiveAmountMinor) else "")
    }

    var status by rememberSaveable { mutableStateOf(initialRemittance?.status ?: RemittanceStatus.SENT) }
    var receivedDate by rememberSaveable { mutableStateOf(initialRemittance?.receivedDate ?: LocalDate.now()) }

    var selectedAccountId by rememberSaveable { mutableStateOf(initialRemittance?.sendingAccountId) }
    var createFinanceTx by rememberSaveable { mutableStateOf(false) }

    var referenceNumber by rememberSaveable { mutableStateOf(initialRemittance?.referenceNumber ?: "") }
    var notes by rememberSaveable { mutableStateOf(initialRemittance?.notes ?: "") }
    var receiptPath by rememberSaveable { mutableStateOf(initialRemittance?.receiptPath) }

    // Live calculation of expected NPR
    val sendAmountMinor = AmountInput.toMinor(sendAmountText) ?: 0L
    val feeAmountMinor = AmountInput.toMinor(feeAmountText) ?: 0L
    val rate = exchangeRateText.replace(',', '.').trim().toDoubleOrNull() ?: 0.0

    val calculatedExpectedNprMinor = remember(sendAmountMinor, feeAmountMinor, sendCurrency, feeCurrency, feeMethod, rate) {
        RemittanceCalculations.calculateExpectedReceiveAmount(
            sendAmountMinor = sendAmountMinor,
            feeAmountMinor = feeAmountMinor,
            sendCurrency = sendCurrency,
            feeCurrency = feeCurrency,
            feeMethod = feeMethod,
            exchangeRate = rate,
        )
    }

    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            scope.launch {
                val savedPath = ReceiptStore.import(context, uri)
                if (savedPath != null) receiptPath = savedPath
            }
        }
    }

    GlassSheet(onDismiss) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // Header
            Row(verticalAlignment = Alignment.CenterVertically) {
                CategoryIcon("plane", c.accent.value.toLong(), size = 48.dp)
                Spacer(Modifier.width(14.dp))
                Column {
                    Text(
                        if (initialRemittance == null) "Record Remittance" else "Edit Remittance",
                        style = MaterialTheme.typography.headlineSmall,
                        color = c.textPrimary,
                    )
                    Text("Manual money transfer to Nepal", style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
                }
            }

            // Section 1: Transfer Basics
            Text("Transfer Details", style = MaterialTheme.typography.labelLarge, color = c.textPrimary, fontWeight = FontWeight.Bold)

            // Transfer Date
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .background(c.glass)
                    .clickable { showDatePicker = true }
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Lucide.Calendar, contentDescription = null, tint = c.textSecondary, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("Transfer Date", style = MaterialTheme.typography.labelSmall, color = c.textTertiary)
                    Text(transferDate.friendlyLabel(), style = MaterialTheme.typography.bodyLarge, color = c.textPrimary)
                }
                Icon(Lucide.ChevronRight, contentDescription = null, tint = c.textTertiary, modifier = Modifier.size(16.dp))
            }

            // Provider Selector
            Box {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(18.dp))
                        .background(c.glass)
                        .clickable { showProviderPicker = true }
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Lucide.Building2, contentDescription = null, tint = c.textSecondary, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Transfer Provider", style = MaterialTheme.typography.labelSmall, color = c.textTertiary)
                        Text(provider.ifBlank { "Select provider" }, style = MaterialTheme.typography.bodyLarge, color = c.textPrimary)
                    }
                    Icon(Lucide.ChevronDown, contentDescription = null, tint = c.textTertiary, modifier = Modifier.size(16.dp))
                }
                DropdownMenu(
                    expanded = showProviderPicker,
                    onDismissRequest = { showProviderPicker = false },
                    shape = RoundedCornerShape(20.dp),
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                ) {
                    StandardRemittanceProviders.forEach { p ->
                        DropdownMenuItem(
                            text = { Text(p, color = c.textPrimary) },
                            onClick = {
                                provider = p
                                showProviderPicker = false
                            },
                        )
                    }

                    if (customProviders.isNotEmpty()) {
                        RowDivider()
                        DropdownMenuItem(
                            text = {
                                Text(
                                    "Custom Providers",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = c.textTertiary,
                                    fontWeight = FontWeight.Bold,
                                )
                            },
                            enabled = false,
                            onClick = {},
                        )
                        customProviders.forEach { cp ->
                            DropdownMenuItem(
                                text = {
                                    Row(
                                        Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text(cp, color = c.textPrimary, modifier = Modifier.weight(1f))
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            IconButton(
                                                onClick = {
                                                    editingCustomProvider = cp
                                                    editedProviderName = cp
                                                    showProviderPicker = false
                                                },
                                                modifier = Modifier.size(28.dp),
                                            ) {
                                                Icon(Lucide.Pencil, contentDescription = "Edit", tint = c.textSecondary, modifier = Modifier.size(15.dp))
                                            }
                                            IconButton(
                                                onClick = {
                                                    scope.launch {
                                                        deletingProviderInUseCount = viewModel.countRemittancesForProvider(cp)
                                                        deletingCustomProvider = cp
                                                        showProviderPicker = false
                                                    }
                                                },
                                                modifier = Modifier.size(28.dp),
                                            ) {
                                                Icon(Lucide.Trash2, contentDescription = "Delete", tint = c.negative, modifier = Modifier.size(15.dp))
                                            }
                                        }
                                    }
                                },
                                onClick = {
                                    provider = cp
                                    showProviderPicker = false
                                },
                            )
                        }
                    }

                    RowDivider()
                    DropdownMenuItem(
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Lucide.Plus, contentDescription = null, tint = c.accent, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("Add Custom Provider", color = c.accent, fontWeight = FontWeight.SemiBold)
                            }
                        },
                        onClick = {
                            newProviderName = ""
                            showAddProviderDialog = true
                            showProviderPicker = false
                        },
                    )
                }
            }

            // Section 2: Recipient
            Text("Recipient", style = MaterialTheme.typography.labelLarge, color = c.textPrimary, fontWeight = FontWeight.Bold)

            // Recipient Selector Card with inline Add
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .background(c.glass)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Lucide.User, contentDescription = null, tint = c.accent, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(12.dp))
                Column(
                    Modifier
                        .weight(1f)
                        .clickable { showRecipientPicker = true },
                ) {
                    Text("Send To", style = MaterialTheme.typography.labelSmall, color = c.textTertiary)
                    Text(
                        selectedRecipient?.name ?: recipientName.ifBlank { "Choose or add recipient" },
                        style = MaterialTheme.typography.bodyLarge,
                        color = c.textPrimary,
                        fontWeight = FontWeight.SemiBold,
                    )
                    selectedRecipient?.let {
                        Text(
                            listOfNotNull(it.relationship, it.bankOrWalletLabel).joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall,
                            color = c.textSecondary,
                        )
                    }
                }
                RoundButton(Lucide.Plus, "New recipient", { showNewRecipientDialog = true }, size = 36.dp)
            }

            // Quick Recipient Dropdown if clicked
            DropdownMenu(
                expanded = showRecipientPicker,
                onDismissRequest = { showRecipientPicker = false },
                shape = RoundedCornerShape(20.dp),
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            ) {
                recipients.forEach { r ->
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(r.name, color = c.textPrimary, fontWeight = FontWeight.SemiBold)
                                Text(listOfNotNull(r.relationship, r.bankOrWalletLabel, r.country).joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
                            }
                        },
                        onClick = {
                            selectedRecipient = r
                            recipientName = r.name
                            recipientCountry = r.country
                            recipientBankOrWallet = r.bankOrWalletLabel.orEmpty()
                            receiveCurrency = r.preferredCurrency
                            r.preferredProvider?.let { if (it.isNotBlank()) provider = it }
                            showRecipientPicker = false
                        },
                    )
                }
            }

            // Section 3: Amount Sent & Fees
            Text("Amounts & Exchange Rate", style = MaterialTheme.typography.labelLarge, color = c.textPrimary, fontWeight = FontWeight.Bold)

            MoneyField(
                value = sendAmountText,
                onValueChange = { sendAmountText = it },
                label = "Amount Sent ($sendCurrency)",
            )

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.weight(1f)) {
                    MoneyField(
                        value = feeAmountText,
                        onValueChange = { feeAmountText = it },
                        label = "Transfer Fee ($feeCurrency)",
                    )
                }
                Box(Modifier.weight(1f)) {
                    GlassTextField(
                        value = exchangeRateText,
                        onValueChange = { exchangeRateText = it },
                        placeholder = "147.00",
                        label = "Rate (1 $sendCurrency = X $receiveCurrency)",
                        keyboardType = KeyboardType.Decimal,
                    )
                }
            }

            // Fee Treatment
            Text("Fee Method", style = MaterialTheme.typography.labelSmall, color = c.textSecondary)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip(
                    text = "Charged Separately",
                    selected = feeMethod == FeeMethod.SEPARATE,
                    onClick = { feeMethod = FeeMethod.SEPARATE },
                    modifier = Modifier.weight(1f),
                )
                Chip(
                    text = "Deducted from Principal",
                    selected = feeMethod == FeeMethod.DEDUCTED_FROM_PRINCIPAL,
                    onClick = { feeMethod = FeeMethod.DEDUCTED_FROM_PRINCIPAL },
                    modifier = Modifier.weight(1f),
                )
            }

            // Calculated Expected NPR Box
            Glass(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) {
                Column(Modifier.padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Expected Receiving Amount:", style = MaterialTheme.typography.bodySmall, color = c.textSecondary, modifier = Modifier.weight(1f))
                        Text(
                            Money(receiveCurrency).format(calculatedExpectedNprMinor),
                            style = MaterialTheme.typography.titleMedium,
                            color = c.accent,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    if (feeMethod == FeeMethod.DEDUCTED_FROM_PRINCIPAL && feeAmountMinor > 0) {
                        Text(
                            "Net converted: ${Money(sendCurrency).format((sendAmountMinor - feeAmountMinor).coerceAtLeast(0))} @ $rate",
                            style = MaterialTheme.typography.labelSmall,
                            color = c.textTertiary,
                        )
                    }
                }
            }

            // Status & Actual Amount (if Received)
            Text("Transfer Status", style = MaterialTheme.typography.labelLarge, color = c.textPrimary, fontWeight = FontWeight.Bold)

            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(RemittanceStatus.entries, key = { it.name }) { s ->
                    val icon = when (s) {
                        RemittanceStatus.PLANNED -> Lucide.Calendar
                        RemittanceStatus.SENT -> Lucide.Send
                        RemittanceStatus.PROCESSING -> Lucide.Clock
                        RemittanceStatus.RECEIVED -> Lucide.Check
                        RemittanceStatus.FAILED -> Lucide.CircleAlert
                        RemittanceStatus.CANCELLED -> Lucide.Ban
                        RemittanceStatus.REFUNDED -> Lucide.RotateCcw
                    }
                    Chip(
                        text = s.label,
                        icon = icon,
                        selected = status == s,
                        onClick = { status = s },
                    )
                }
            }

            AnimatedVisibility(status == RemittanceStatus.RECEIVED) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    MoneyField(
                        value = actualReceiveAmountText,
                        onValueChange = { actualReceiveAmountText = it },
                        label = "Actual Amount Received ($receiveCurrency)",
                    )
                    val actualMinor = AmountInput.toMinor(actualReceiveAmountText)
                    if (actualMinor != null) {
                        val diff = actualMinor - calculatedExpectedNprMinor
                        val effRate = RemittanceCalculations.calculateEffectiveExchangeRate(sendAmountMinor, actualMinor)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(
                                "Diff: ${if (diff >= 0) "+${Money(receiveCurrency).format(diff)}" else Money(receiveCurrency).format(diff)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (diff >= 0) c.positive else c.negative,
                            )
                            if (effRate != null) {
                                Text("Effective: 1€ = $effRate $receiveCurrency", style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
                            }
                        }
                    }
                }
            }

            // Section 4: Account & Extra Details
            Text("Account & Finance Link", style = MaterialTheme.typography.labelLarge, color = c.textPrimary, fontWeight = FontWeight.Bold)

            if (accounts.isNotEmpty()) {
                val acc = accounts.find { it.id == selectedAccountId }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(18.dp))
                        .background(c.glass)
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Lucide.Wallet, contentDescription = null, tint = c.textSecondary, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Sending Account (Optional)", style = MaterialTheme.typography.labelSmall, color = c.textTertiary)
                        Text(acc?.let { "${it.name} (${Money(it.currency ?: "EUR").format(it.balanceMinor)})" } ?: "None selected", style = MaterialTheme.typography.bodyLarge, color = c.textPrimary)
                    }
                    var openAccPicker by remember { mutableStateOf(false) }
                    Box {
                        TextButton(onClick = { openAccPicker = true }) { Text("Change") }
                        DropdownMenu(
                            expanded = openAccPicker,
                            onDismissRequest = { openAccPicker = false },
                            shape = RoundedCornerShape(20.dp),
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        ) {
                            DropdownMenuItem(text = { Text("None (Don't link account)") }, onClick = { selectedAccountId = null; openAccPicker = false })
                            accounts.forEach { a ->
                                DropdownMenuItem(
                                    text = { Text("${a.name} (${Money(a.currency ?: "EUR").format(a.balanceMinor)})") },
                                    onClick = { selectedAccountId = a.id; openAccPicker = false },
                                )
                            }
                        }
                    }
                }

                if (selectedAccountId != null) {
                    val totalCost = RemittanceCalculations.calculateTotalCostMinor(sendAmountMinor, feeAmountMinor, sendCurrency, feeCurrency, feeMethod)
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(c.glassStrong)
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Lucide.CircleCheck, contentDescription = null, tint = c.accent, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Automatic Balance Deduction", style = MaterialTheme.typography.labelMedium, color = c.textPrimary, fontWeight = FontWeight.SemiBold)
                            Text(
                                "Deducts ${Money(sendCurrency).format(totalCost)} from ${acc?.name ?: "account"}. Account balance & transaction history stay synchronised.",
                                style = MaterialTheme.typography.bodySmall,
                                color = c.textSecondary,
                            )
                        }
                    }
                }
            }

            GlassTextField(
                value = referenceNumber,
                onValueChange = { referenceNumber = it },
                placeholder = "e.g. REV-12345678, WISE-9876",
                label = "Transfer Reference Number (Optional)",
            )

            GlassTextField(
                value = notes,
                onValueChange = { notes = it },
                placeholder = "e.g. Festival money, Family support",
                label = "Notes (Optional)",
            )

            // Receipt attachment
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .background(c.glass)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Lucide.Receipt, contentDescription = null, tint = c.textSecondary, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("Transfer Receipt", style = MaterialTheme.typography.labelSmall, color = c.textTertiary)
                    Text(if (receiptPath != null) "Receipt attached" else "No receipt attached", style = MaterialTheme.typography.bodyMedium, color = c.textPrimary)
                }
                if (receiptPath != null) {
                    TextButton(onClick = { receiptPath = null }) { Text("Remove", color = c.negative) }
                } else {
                    TextButton(onClick = { photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) {
                        Text("Attach")
                    }
                }
            }

            // Save Button
            val canSave = sendAmountMinor > 0L && (selectedRecipient != null || recipientName.isNotBlank())
            PrimaryButton(
                text = if (initialRemittance == null) "Save Remittance" else "Update Remittance",
                onClick = {
                    val finalRecipientName = selectedRecipient?.name ?: recipientName.trim()
                    val actualMinor = if (status == RemittanceStatus.RECEIVED) (AmountInput.toMinor(actualReceiveAmountText) ?: calculatedExpectedNprMinor) else null

                    val toSave = (initialRemittance ?: Remittance(
                        recipientId = selectedRecipient?.id,
                        recipientName = finalRecipientName,
                        recipientCountry = selectedRecipient?.country ?: recipientCountry,
                        recipientBankOrWallet = selectedRecipient?.bankOrWalletLabel ?: recipientBankOrWallet,
                        transferDateEpochDay = transferDate.toEpochDay(),
                        provider = provider.trim().ifBlank { "Other" },
                        sendAmountMinor = sendAmountMinor,
                        sendCurrency = sendCurrency,
                        feeAmountMinor = feeAmountMinor,
                        feeCurrency = feeCurrency,
                        feeMethod = feeMethod,
                        exchangeRate = rate,
                        expectedReceiveAmountMinor = calculatedExpectedNprMinor,
                        actualReceiveAmountMinor = actualMinor,
                        receiveCurrency = receiveCurrency,
                        status = status,
                        receivedDateEpochDay = if (status == RemittanceStatus.RECEIVED) receivedDate.toEpochDay() else null,
                        sendingAccountId = selectedAccountId,
                        referenceNumber = referenceNumber.trim().ifBlank { null },
                        notes = notes.trim(),
                        receiptPath = receiptPath,
                    )).copy(
                        recipientId = selectedRecipient?.id,
                        recipientName = finalRecipientName,
                        recipientCountry = selectedRecipient?.country ?: recipientCountry,
                        recipientBankOrWallet = selectedRecipient?.bankOrWalletLabel ?: recipientBankOrWallet,
                        transferDateEpochDay = transferDate.toEpochDay(),
                        provider = provider.trim().ifBlank { "Other" },
                        sendAmountMinor = sendAmountMinor,
                        sendCurrency = sendCurrency,
                        feeAmountMinor = feeAmountMinor,
                        feeCurrency = feeCurrency,
                        feeMethod = feeMethod,
                        exchangeRate = rate,
                        expectedReceiveAmountMinor = calculatedExpectedNprMinor,
                        actualReceiveAmountMinor = actualMinor,
                        receiveCurrency = receiveCurrency,
                        status = status,
                        receivedDateEpochDay = if (status == RemittanceStatus.RECEIVED) receivedDate.toEpochDay() else null,
                        sendingAccountId = selectedAccountId,
                        referenceNumber = referenceNumber.trim().ifBlank { null },
                        notes = notes.trim(),
                        receiptPath = receiptPath,
                    )

                    scope.launch {
                        viewModel.saveRemittance(toSave, createFinanceTx = (selectedAccountId != null))
                        onSaved(if (initialRemittance == null) "Remittance recorded" else "Remittance updated")
                        onDismiss()
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = canSave,
            )
        }
    }

    // Inline Date Picker
    if (showDatePicker) {
        val dateState = rememberDatePickerState(initialSelectedDateMillis = transferDate.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli())
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    dateState.selectedDateMillis?.let {
                        transferDate = Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalDate()
                    }
                    showDatePicker = false
                }) { Text("OK") }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) { Text("Cancel") }
            },
        ) {
            DatePicker(state = dateState)
        }
    }

    // Inline Quick Add Recipient Dialog
    if (showNewRecipientDialog) {
        QuickAddRecipientDialog(
            onDismiss = { showNewRecipientDialog = false },
            onSave = { newRec ->
                scope.launch {
                    val id = viewModel.saveRecipient(newRec)
                    selectedRecipient = newRec.copy(id = id)
                    recipientName = newRec.name
                    recipientCountry = newRec.country
                    recipientBankOrWallet = newRec.bankOrWalletLabel.orEmpty()
                    receiveCurrency = newRec.preferredCurrency
                    newRec.preferredProvider?.let { if (it.isNotBlank()) provider = it }
                    showNewRecipientDialog = false
                }
            },
        )
    }

    // Dialog: Add Custom Provider
    if (showAddProviderDialog) {
        AlertDialog(
            onDismissRequest = { showAddProviderDialog = false },
            title = { Text("Add Custom Provider", color = c.textPrimary) },
            text = {
                Column {
                    Text(
                        "Enter the name of your money transfer provider:",
                        style = MaterialTheme.typography.bodySmall,
                        color = c.textSecondary,
                    )
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = newProviderName,
                        onValueChange = { newProviderName = it },
                        placeholder = { Text("e.g. City Express, IME") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val name = newProviderName.trim()
                        if (name.isNotBlank()) {
                            scope.launch {
                                viewModel.addCustomProvider(name)
                                provider = name
                                showAddProviderDialog = false
                            }
                        }
                    },
                    enabled = newProviderName.isNotBlank(),
                ) {
                    Text("Save Provider", color = c.accent)
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddProviderDialog = false }) { Text("Cancel") }
            },
        )
    }

    // Dialog: Edit Custom Provider
    editingCustomProvider?.let { oldName ->
        AlertDialog(
            onDismissRequest = { editingCustomProvider = null },
            title = { Text("Edit Custom Provider", color = c.textPrimary) },
            text = {
                Column {
                    Text("Update provider name:", style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = editedProviderName,
                        onValueChange = { editedProviderName = it },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val newName = editedProviderName.trim()
                        if (newName.isNotBlank()) {
                            scope.launch {
                                viewModel.updateCustomProvider(oldName, newName)
                                if (provider == oldName) provider = newName
                                editingCustomProvider = null
                            }
                        }
                    },
                    enabled = editedProviderName.isNotBlank(),
                ) {
                    Text("Update", color = c.accent)
                }
            },
            dismissButton = {
                TextButton(onClick = { editingCustomProvider = null }) { Text("Cancel") }
            },
        )
    }

    // Dialog: Delete Custom Provider with Reference Protection
    deletingCustomProvider?.let { name ->
        if (deletingProviderInUseCount > 0) {
            AlertDialog(
                onDismissRequest = { deletingCustomProvider = null },
                title = { Text("Cannot Delete Provider", color = c.warning) },
                text = {
                    Text(
                        "'$name' cannot be deleted because it is currently used by $deletingProviderInUseCount existing remittance transaction(s). To keep financial records consistent, please reassign those remittances to another provider first.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = c.textPrimary,
                    )
                },
                confirmButton = {
                    TextButton(onClick = { deletingCustomProvider = null }) {
                        Text("OK", color = c.accent)
                    }
                },
            )
        } else {
            AlertDialog(
                onDismissRequest = { deletingCustomProvider = null },
                title = { Text("Delete Custom Provider", color = c.textPrimary) },
                text = {
                    Text(
                        "Are you sure you want to delete '$name'? It will no longer appear in future provider choices.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = c.textSecondary,
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            scope.launch {
                                viewModel.deleteCustomProvider(name)
                                if (provider == name) provider = "TapTap Send"
                                deletingCustomProvider = null
                            }
                        },
                    ) {
                        Text("Delete", color = c.negative)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { deletingCustomProvider = null }) { Text("Cancel") }
                },
            )
        }
    }
}

@Composable
fun QuickAddRecipientDialog(
    onDismiss: () -> Unit,
    onSave: (Recipient) -> Unit,
) {
    val c = LocalAppColors.current
    var name by rememberSaveable { mutableStateOf("") }
    var relationship by rememberSaveable { mutableStateOf("Mother") }
    var country by rememberSaveable { mutableStateOf("Nepal") }
    var bankOrWallet by rememberSaveable { mutableStateOf("") }
    var preferredProvider by rememberSaveable { mutableStateOf("Revolut") }
    var notes by rememberSaveable { mutableStateOf("") }

    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        title = { Text("Add Recipient", style = MaterialTheme.typography.headlineSmall, color = c.textPrimary) },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                GlassTextField(name, { name = it }, placeholder = "e.g. Sita Sharma (Mother)", label = "Recipient Name *")

                Text("Relationship", style = MaterialTheme.typography.labelSmall, color = c.textSecondary)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(CommonRelationships, key = { it }) { rel ->
                        Chip(text = rel, selected = relationship == rel, onClick = { relationship = rel })
                    }
                }

                GlassTextField(bankOrWallet, { bankOrWallet = it }, placeholder = "e.g. Nabil Bank, eSewa, Global IME", label = "Bank or Wallet Label (Optional)")
                GlassTextField(country, { country = it }, placeholder = "Nepal", label = "Destination Country")
                GlassTextField(notes, { notes = it }, placeholder = "Optional notes", label = "Notes")
            }
        },
        confirmButton = {
            PrimaryButton(
                "Save Recipient",
                onClick = {
                    if (name.isNotBlank()) {
                        onSave(
                            Recipient(
                                name = name.trim(),
                                relationship = relationship.ifBlank { null },
                                country = country.trim().ifBlank { "Nepal" },
                                preferredCurrency = "NPR",
                                preferredProvider = preferredProvider.ifBlank { null },
                                bankOrWalletLabel = bankOrWallet.trim().ifBlank { null },
                                notes = notes.trim(),
                            ),
                        )
                    }
                },
                enabled = name.isNotBlank(),
            )
        },
        dismissButton = { SecondaryButton("Cancel", onClick = onDismiss) },
    )
}
