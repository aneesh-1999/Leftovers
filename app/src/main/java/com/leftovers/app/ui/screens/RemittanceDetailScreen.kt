package com.leftovers.app.ui.screens

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.leftovers.app.data.Account
import com.leftovers.app.data.AccountRepository
import com.leftovers.app.data.FeeMethod
import com.leftovers.app.data.Recipient
import com.leftovers.app.data.Remittance
import com.leftovers.app.data.RemittanceCalculations
import com.leftovers.app.data.RemittanceRepository
import com.leftovers.app.data.RemittanceStatus
import com.leftovers.app.data.RemittanceStatusHistory
import com.leftovers.app.data.Transaction
import com.leftovers.app.data.TransactionRepository
import com.leftovers.app.ui.AppViewModelProvider
import com.leftovers.app.ui.components.DockClearance
import com.leftovers.app.ui.components.Glass
import com.leftovers.app.ui.components.GlassScreen
import com.leftovers.app.ui.components.IconTile
import com.leftovers.app.ui.components.ListRow
import com.leftovers.app.ui.components.RowDivider
import com.leftovers.app.ui.components.SectionHeader
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import com.leftovers.app.data.AccountWithBalance
import com.leftovers.app.data.TransactionItem
import com.leftovers.app.ui.icons.Lucide
import com.leftovers.app.ui.theme.LocalAppColors
import com.leftovers.app.util.AmountInput
import com.leftovers.app.util.Money
import com.leftovers.app.util.ReceiptStore
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

data class RemittanceDetailUiState(
    val remittance: Remittance? = null,
    val recipient: Recipient? = null,
    val sendingAccount: AccountWithBalance? = null,
    val linkedTransaction: TransactionItem? = null,
    val statusHistory: List<RemittanceStatusHistory> = emptyList(),
    val allRecipients: List<Recipient> = emptyList(),
    val allAccounts: List<AccountWithBalance> = emptyList(),
)

class RemittanceDetailViewModel(
    savedStateHandle: SavedStateHandle,
    private val remittanceRepo: RemittanceRepository,
    private val transactionRepo: TransactionRepository,
    private val accountRepo: AccountRepository,
) : ViewModel() {
    private val remittanceId: Long =
        savedStateHandle.get<Long>("id")
            ?: savedStateHandle.get<String>("id")?.toLongOrNull()
            ?: 0L

    val state: StateFlow<RemittanceDetailUiState> = combine(
        remittanceRepo.observeRemittance(remittanceId),
        remittanceRepo.allRecipients,
        accountRepo.accounts,
        remittanceRepo.observeStatusHistory(remittanceId),
        transactionRepo.allTransactions,
    ) { remittance, recipients, accounts, history, txs ->
        val recipient = recipients.find { it.id == remittance?.recipientId }
        val account = accounts.find { it.id == remittance?.sendingAccountId }
        val linkedTx = txs.find { it.id == remittance?.financeTransactionId }
        RemittanceDetailUiState(
            remittance = remittance,
            recipient = recipient,
            sendingAccount = account,
            linkedTransaction = linkedTx,
            statusHistory = history,
            allRecipients = recipients,
            allAccounts = accounts,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), RemittanceDetailUiState())

    fun updateStatus(
        newStatus: RemittanceStatus,
        actualReceiveAmountMinor: Long? = null,
        receivedDateEpochDay: Long? = null,
        statusNote: String? = null,
    ) {
        viewModelScope.launch {
            remittanceRepo.updateStatus(
                remittanceId = remittanceId,
                newStatus = newStatus,
                actualReceiveAmountMinor = actualReceiveAmountMinor,
                receivedDateEpochDay = receivedDateEpochDay,
                statusNote = statusNote,
            )
        }
    }

    fun saveRemittance(updated: Remittance, createExpense: Boolean) {
        viewModelScope.launch {
            remittanceRepo.saveRemittance(updated, createFinanceTx = createExpense)
        }
    }

    fun deleteRemittance(onDeleted: () -> Unit) {
        val rem = state.value.remittance ?: return
        viewModelScope.launch {
            remittanceRepo.deleteRemittance(rem)
            onDeleted()
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RemittanceDetailScreen(
    onBack: () -> Unit,
    onEdit: (Long) -> Unit = {},
    viewModel: RemittanceDetailViewModel = viewModel(factory = AppViewModelProvider.Factory),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val c = LocalAppColors.current
    val rem = state.remittance

    var showStatusDialog by remember { mutableStateOf(false) }
    var targetStatus by remember { mutableStateOf<RemittanceStatus?>(null) }
    var statusActualAmountInput by remember { mutableStateOf("") }
    var statusNote by remember { mutableStateOf("") }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var viewReceiptPath by remember { mutableStateOf<String?>(null) }
    var showEditSheet by remember { mutableStateOf(false) }

    val sendMoney = remember(rem?.sendCurrency) { Money(rem?.sendCurrency ?: "EUR") }
    val receiveMoney = remember(rem?.receiveCurrency) { Money(rem?.receiveCurrency ?: "NPR") }
    val feeMoney = remember(rem?.feeCurrency) { Money(rem?.feeCurrency ?: "EUR") }

    GlassScreen(
        title = "Remittance Details",
        onBack = onBack,
        actions = {
            if (rem != null) {
                IconButton(onClick = { showEditSheet = true }) {
                    Icon(Lucide.Pencil, contentDescription = "Edit", tint = c.textPrimary)
                }
                IconButton(onClick = { showDeleteConfirm = true }) {
                    Icon(Lucide.Trash2, contentDescription = "Delete", tint = c.negative)
                }
            }
        },
    ) { padding ->
        if (rem == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Remittance record not found", color = c.textSecondary)
            }
            return@GlassScreen
        }

        val totalCost = RemittanceCalculations.calculateTotalCostMinor(
            sendAmountMinor = rem.sendAmountMinor,
            feeAmountMinor = rem.feeAmountMinor,
            sendCurrency = rem.sendCurrency,
            feeCurrency = rem.feeCurrency,
            feeMethod = rem.feeMethod,
        )

        val diff = RemittanceCalculations.calculateReceivingDifference(
            expectedReceiveAmountMinor = rem.expectedReceiveAmountMinor,
            actualReceiveAmountMinor = rem.actualReceiveAmountMinor,
        )

        val effectiveRate = rem.actualReceiveAmountMinor?.let { actual ->
            RemittanceCalculations.calculateEffectiveExchangeRate(
                sendAmountMinor = rem.sendAmountMinor,
                actualReceiveAmountMinor = actual,
            )
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 20.dp,
                end = 20.dp,
                top = padding.calculateTopPadding() + 8.dp,
                bottom = padding.calculateBottomPadding() + 16.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Header card with Status & Key Amounts
            item {
                Glass(Modifier.fillMaxWidth(), strong = true, shape = RoundedCornerShape(24.dp)) {
                    Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        RemittanceStatusPill(rem.status)
                        Spacer(Modifier.height(14.dp))
                        Text(
                            text = sendMoney.format(rem.sendAmountMinor),
                            style = MaterialTheme.typography.headlineLarge,
                            fontWeight = FontWeight.Bold,
                            color = c.textPrimary,
                        )
                        Spacer(Modifier.height(4.dp))
                        val recDisplay = rem.actualReceiveAmountMinor?.let {
                            "${receiveMoney.format(it)} (Actual)"
                        } ?: "${receiveMoney.format(rem.expectedReceiveAmountMinor)} (Expected)"
                        Text(
                            text = "→ $recDisplay",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = c.positive,
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "Sent to ${rem.recipientName} on ${rem.transferDate}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = c.textSecondary,
                        )
                    }
                }
            }

            // Quick Status Transition Actions
            item {
                SectionHeader(title = "Change Status")
                Glass(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) {
                    Column(Modifier.padding(16.dp)) {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            RemittanceStatus.entries.forEach { st ->
                                val selected = st == rem.status
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(if (selected) c.accent.copy(alpha = 0.25f) else c.glass)
                                        .border(
                                            width = if (selected) 1.5.dp else 1.dp,
                                            color = if (selected) c.accent else c.borderTop,
                                            shape = RoundedCornerShape(12.dp),
                                        )
                                        .clickable {
                                            if (st != rem.status) {
                                                targetStatus = st
                                                statusActualAmountInput = AmountInput.fromMinor(rem.actualReceiveAmountMinor ?: rem.expectedReceiveAmountMinor)
                                                statusNote = ""
                                                showStatusDialog = true
                                            }
                                        }
                                        .padding(horizontal = 12.dp, vertical = 8.dp),
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    ) {
                                        val icon = when (st) {
                                            RemittanceStatus.PLANNED -> Lucide.Calendar
                                            RemittanceStatus.SENT -> Lucide.Send
                                            RemittanceStatus.PROCESSING -> Lucide.Clock
                                            RemittanceStatus.RECEIVED -> Lucide.Check
                                            RemittanceStatus.FAILED -> Lucide.CircleAlert
                                            RemittanceStatus.CANCELLED -> Lucide.Ban
                                            RemittanceStatus.REFUNDED -> Lucide.RotateCcw
                                        }
                                        Icon(
                                            imageVector = icon,
                                            contentDescription = null,
                                            tint = if (selected) c.accent else c.textSecondary,
                                            modifier = Modifier.size(14.dp),
                                        )
                                        Text(
                                            text = st.label,
                                            style = MaterialTheme.typography.labelMedium,
                                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                            color = if (selected) c.accent else c.textSecondary,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Transfer Summary Breakdown
            item {
                SectionHeader(title = "Transfer Breakdown")
                Glass(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
                    Column(Modifier.padding(16.dp)) {
                        DetailRow("Provider", rem.provider)
                        RowDivider()
                        DetailRow("Sending Account", state.sendingAccount?.name ?: "None (Unlinked)")
                        RowDivider()
                        DetailRow("Principal Sent", sendMoney.format(rem.sendAmountMinor))
                        RowDivider()
                        DetailRow(
                            "Transfer Fee",
                            "${feeMoney.format(rem.feeAmountMinor)} (${rem.feeMethod.label})",
                        )
                        RowDivider()
                        DetailRow("Total Cost to Sender", sendMoney.format(totalCost))
                        if (!rem.referenceNumber.isNullOrBlank()) {
                            RowDivider()
                            DetailRow("Reference Number", rem.referenceNumber)
                        }
                    }
                }
            }

            // Exchange & Receiving Details
            item {
                SectionHeader(title = "Exchange & Receiving")
                Glass(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
                    Column(Modifier.padding(16.dp)) {
                        DetailRow("Exchange Rate", "1 ${rem.sendCurrency} = ${rem.exchangeRate} ${rem.receiveCurrency}")
                        RowDivider()
                        DetailRow("Expected Receiving Amount", receiveMoney.format(rem.expectedReceiveAmountMinor))
                        RowDivider()
                        DetailRow(
                            "Actual Receiving Amount",
                            rem.actualReceiveAmountMinor?.let { receiveMoney.format(it) } ?: "Not yet recorded",
                        )
                        if (rem.receivedDate != null) {
                            RowDivider()
                            DetailRow("Date Received", rem.receivedDate.toString())
                        }
                        if (diff != null && diff != 0L) {
                            RowDivider()
                            val diffText = if (diff > 0) {
                                "+${receiveMoney.format(diff)} (More received)"
                            } else {
                                "-${receiveMoney.format(-diff)} (Less received)"
                            }
                            DetailRow("Receiving Discrepancy", diffText, valueColor = if (diff > 0) c.positive else c.negative)
                        }
                        if (effectiveRate != null) {
                            RowDivider()
                            DetailRow("Effective Rate", "1 ${rem.sendCurrency} = ${"%.2f".format(effectiveRate)} ${rem.receiveCurrency}")
                        }
                    }
                }
            }

            // Recipient Information
            item {
                SectionHeader(title = "Recipient Information")
                Glass(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
                    Column(Modifier.padding(16.dp)) {
                        DetailRow("Recipient Name", rem.recipientName)
                        state.recipient?.relationship?.takeIf { it.isNotBlank() }?.let {
                            RowDivider()
                            DetailRow("Relationship", it)
                        }
                        DetailRow("Destination Country", rem.recipientCountry)
                        if (!rem.recipientBankOrWallet.isNullOrBlank()) {
                            RowDivider()
                            DetailRow("Receiving Bank / Wallet", rem.recipientBankOrWallet)
                        }
                    }
                }
            }

            // Finance Transaction & Notes
            if (state.linkedTransaction != null || rem.notes.isNotBlank() || rem.receiptPath != null) {
                item {
                    SectionHeader(title = "Additional Info")
                    Glass(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
                        Column(Modifier.padding(16.dp)) {
                            if (state.linkedTransaction != null) {
                                DetailRow("Finance Transaction", "Linked expense recorded in accounts", valueColor = c.accent)
                                RowDivider()
                            }
                            if (rem.notes.isNotBlank()) {
                                DetailRow("Notes", rem.notes)
                                RowDivider()
                            }
                            if (rem.receiptPath != null) {
                                ListRow(
                                    title = "Transfer Receipt Document",
                                    subtitle = "Tap to view confirmation receipt",
                                    leading = { IconTile(Lucide.ReceiptText, c.accent, size = 36.dp) },
                                    trailing = { Icon(Lucide.ChevronRight, contentDescription = null, tint = c.textSecondary, modifier = Modifier.size(18.dp)) },
                                    onClick = { viewReceiptPath = rem.receiptPath },
                                )
                            }
                        }
                    }
                }
            }

            // Status Audit History
            if (state.statusHistory.isNotEmpty()) {
                item {
                    SectionHeader(title = "Status History")
                    Glass(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            state.statusHistory.forEach { h ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(10.dp)
                                            .clip(CircleShape)
                                            .background(if (h.toStatus == RemittanceStatus.RECEIVED) c.positive else if (h.toStatus == RemittanceStatus.FAILED || h.toStatus == RemittanceStatus.CANCELLED) c.negative else c.accent),
                                    )
                                    Spacer(Modifier.width(12.dp))
                                    Column(Modifier.weight(1f)) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                                        ) {
                                            val statusIcon = when (h.toStatus) {
                                                RemittanceStatus.PLANNED -> Lucide.Calendar
                                                RemittanceStatus.SENT -> Lucide.Send
                                                RemittanceStatus.PROCESSING -> Lucide.Clock
                                                RemittanceStatus.RECEIVED -> Lucide.Check
                                                RemittanceStatus.FAILED -> Lucide.CircleAlert
                                                RemittanceStatus.CANCELLED -> Lucide.Ban
                                                RemittanceStatus.REFUNDED -> Lucide.RotateCcw
                                            }
                                            Icon(
                                                imageVector = statusIcon,
                                                contentDescription = null,
                                                tint = if (h.toStatus == RemittanceStatus.RECEIVED) c.positive else if (h.toStatus == RemittanceStatus.FAILED || h.toStatus == RemittanceStatus.CANCELLED) c.negative else c.accent,
                                                modifier = Modifier.size(14.dp),
                                            )
                                            Text(
                                                text = h.toStatus.label,
                                                style = MaterialTheme.typography.titleSmall,
                                                color = c.textPrimary,
                                            )
                                        }
                                        val timeStr = formatEpochMillis(h.changedAt)
                                        Text(
                                            text = timeStr + (if (!h.note.isNullOrBlank()) " · ${h.note}" else ""),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = c.textSecondary,
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

    // Status Change Confirmation Dialog
    if (showStatusDialog && targetStatus != null) {
        val target = targetStatus!!
        AlertDialog(
            onDismissRequest = { showStatusDialog = false },
            title = { Text("Update to ${target.label}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = when (target) {
                            RemittanceStatus.RECEIVED -> "Confirming that the transfer has been received in Nepal. You can record the actual received amount below."
                            RemittanceStatus.FAILED -> "Marking as failed. Money has not been received."
                            RemittanceStatus.CANCELLED -> "Marking as cancelled."
                            RemittanceStatus.REFUNDED -> "Marking as refunded back to sender."
                            else -> "Updating remittance status to ${target.label}."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = c.textSecondary,
                    )

                    if (target == RemittanceStatus.RECEIVED) {
                        OutlinedTextField(
                            value = statusActualAmountInput,
                            onValueChange = { statusActualAmountInput = it },
                            label = { Text("Actual Amount Received (${rem?.receiveCurrency ?: "NPR"})") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                        )
                    }

                    OutlinedTextField(
                        value = statusNote,
                        onValueChange = { statusNote = it },
                        label = { Text("Status Note (Optional)") },
                        placeholder = { Text("e.g. Received via Prabhu Bank") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val actualMinor = if (target == RemittanceStatus.RECEIVED && statusActualAmountInput.isNotBlank()) {
                            AmountInput.toMinor(statusActualAmountInput)
                        } else null

                        viewModel.updateStatus(
                            newStatus = target,
                            actualReceiveAmountMinor = actualMinor,
                            receivedDateEpochDay = if (target == RemittanceStatus.RECEIVED) LocalDate.now().toEpochDay() else null,
                            statusNote = statusNote.trim().ifEmpty { null },
                        )
                        showStatusDialog = false
                    },
                ) {
                    Text("Confirm Update", color = c.accent, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showStatusDialog = false }) {
                    Text("Cancel", color = c.textSecondary)
                }
            },
        )
    }

    // Delete Confirmation Dialog
    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Delete Remittance?") },
            text = { Text("Are you sure you want to delete this remittance record? This cannot be undone.", color = c.textSecondary) },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteRemittance(onDeleted = onBack)
                        showDeleteConfirm = false
                    },
                ) {
                    Text("Delete", color = c.negative, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text("Cancel", color = c.textSecondary)
                }
            },
        )
    }

    // Receipt Viewer
    if (viewReceiptPath != null) {
        ReceiptDialog(
            path = viewReceiptPath!!,
            onDismiss = { viewReceiptPath = null },
        )
    }

    // Edit Remittance Sheet
    if (showEditSheet && rem != null) {
        RemittanceEditorSheet(
            initialRemittance = rem,
            onDismiss = { showEditSheet = false },
            onSaved = { showEditSheet = false },
        )
    }
}

@Composable
private fun ReceiptDialog(
    path: String,
    onDismiss: () -> Unit,
) {
    val c = LocalAppColors.current
    var bitmap by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    androidx.compose.runtime.LaunchedEffect(path) {
        bitmap = ReceiptStore.loadPreview(path, maxSide = 1200)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Transfer Receipt", color = c.textPrimary) },
        text = {
            Box(Modifier.fillMaxWidth().height(320.dp), contentAlignment = Alignment.Center) {
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap!!.asImageBitmap(),
                        contentDescription = "Transfer receipt document",
                        modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(16.dp)),
                        contentScale = ContentScale.Fit,
                    )
                } else {
                    Text("Loading receipt preview...", color = c.textSecondary)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close", color = c.accent, fontWeight = FontWeight.Bold)
            }
        },
    )
}

@Composable
private fun DetailRow(label: String, value: String, valueColor: Color? = null) {
    val c = LocalAppColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = c.textSecondary)
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = valueColor ?: c.textPrimary,
        )
    }
}

private fun formatEpochMillis(epochMillis: Long): String {
    val instant = Instant.ofEpochMilli(epochMillis)
    val formatter = DateTimeFormatter.ofPattern("MMM d, yyyy HH:mm")
    return formatter.format(instant.atZone(ZoneId.systemDefault()))
}
