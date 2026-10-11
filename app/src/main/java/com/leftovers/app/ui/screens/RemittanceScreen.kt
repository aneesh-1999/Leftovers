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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.leftovers.app.data.FeeMethod
import com.leftovers.app.data.Recipient
import com.leftovers.app.data.Remittance
import com.leftovers.app.data.RemittanceCalculations
import com.leftovers.app.data.RemittanceRepository
import com.leftovers.app.data.RemittanceStatus
import com.leftovers.app.data.RemittanceSummary
import com.leftovers.app.ui.AppViewModelProvider
import com.leftovers.app.ui.components.Chip
import com.leftovers.app.ui.components.DockClearance
import com.leftovers.app.ui.components.EmptyState
import com.leftovers.app.ui.components.Glass
import com.leftovers.app.ui.components.GlassScreen
import com.leftovers.app.ui.components.IconTile
import com.leftovers.app.ui.components.PrimaryButton
import com.leftovers.app.ui.components.RollingText
import com.leftovers.app.ui.components.RoundButton
import com.leftovers.app.ui.components.RowDivider
import com.leftovers.app.ui.components.SecondaryButton
import com.leftovers.app.ui.components.SectionHeader
import com.leftovers.app.ui.components.SegmentedToggle
import com.leftovers.app.ui.components.StatTile
import com.leftovers.app.ui.components.appear
import com.leftovers.app.ui.components.frosted
import com.leftovers.app.ui.components.pressable
import com.leftovers.app.ui.icons.Lucide
import com.leftovers.app.ui.theme.LocalAppColors
import com.leftovers.app.util.AmountInput
import com.leftovers.app.util.Money
import com.leftovers.app.util.label
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.YearMonth

enum class RemittancePeriodMode { MONTHLY, YEARLY }

private data class FilterBundle(
    val mode: RemittancePeriodMode,
    val month: YearMonth,
    val year: Int,
    val recipientId: Long?,
    val provider: String?,
)

data class RemittanceDashboardUiState(
    val loaded: Boolean = false,
    val periodMode: RemittancePeriodMode = RemittancePeriodMode.MONTHLY,
    val selectedMonth: YearMonth = YearMonth.now(),
    val selectedYear: Int = LocalDate.now().year,
    val selectedRecipientId: Long? = null,
    val selectedProvider: String? = null,
    val summary: RemittanceSummary = RemittanceSummary(),
    val allRemittances: List<Remittance> = emptyList(),
    val periodRemittances: List<Remittance> = emptyList(),
    val pendingRemittances: List<Remittance> = emptyList(),
    val recipients: List<Recipient> = emptyList(),
    val providers: List<String> = emptyList(),
)

class RemittanceViewModel(
    private val repository: RemittanceRepository,
) : ViewModel() {
    private val periodMode = MutableStateFlow(RemittancePeriodMode.MONTHLY)
    private val selectedMonth = MutableStateFlow(YearMonth.now())
    private val selectedYear = MutableStateFlow(LocalDate.now().year)
    private val selectedRecipientId = MutableStateFlow<Long?>(null)
    private val selectedProvider = MutableStateFlow<String?>(null)

    private val filtersFlow = combine(
        periodMode,
        selectedMonth,
        selectedYear,
        selectedRecipientId,
        selectedProvider,
    ) { mode, month, year, recipientId, provider ->
        FilterBundle(mode, month, year, recipientId, provider)
    }

    val state: StateFlow<RemittanceDashboardUiState> = combine(
        repository.allRemittances,
        repository.activeRecipients,
        repository.providers,
        filtersFlow,
    ) { allTxs, recipients, providers, filters ->
        val periodFiltered = allTxs.filter { r ->
            val date = r.transferDate
            val matchesPeriod = if (filters.mode == RemittancePeriodMode.MONTHLY) {
                YearMonth.from(date) == filters.month
            } else {
                date.year == filters.year
            }
            val matchesRecipient = filters.recipientId == null || r.recipientId == filters.recipientId
            val matchesProvider = filters.provider == null || r.provider.equals(filters.provider, ignoreCase = true)
            matchesPeriod && matchesRecipient && matchesProvider
        }

        val summary = RemittanceCalculations.summarize(periodFiltered)
        val pending = allTxs.filter { it.status.isPending }.sortedByDescending { it.transferDateEpochDay }

        RemittanceDashboardUiState(
            loaded = true,
            periodMode = filters.mode,
            selectedMonth = filters.month,
            selectedYear = filters.year,
            selectedRecipientId = filters.recipientId,
            selectedProvider = filters.provider,
            summary = summary,
            allRemittances = allTxs,
            periodRemittances = periodFiltered,
            pendingRemittances = pending,
            recipients = recipients,
            providers = providers,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RemittanceDashboardUiState())

    fun setPeriodMode(mode: RemittancePeriodMode) { periodMode.value = mode }
    fun setMonth(m: YearMonth) { selectedMonth.value = m }
    fun setYear(y: Int) { selectedYear.value = y }
    fun setRecipientFilter(id: Long?) { selectedRecipientId.value = id }
    fun setProviderFilter(p: String?) { selectedProvider.value = p }

    fun markAsReceived(remittanceId: Long, actualReceivedMinor: Long, date: LocalDate, note: String? = null) {
        viewModelScope.launch {
            repository.updateStatus(
                remittanceId = remittanceId,
                newStatus = RemittanceStatus.RECEIVED,
                actualReceiveAmountMinor = actualReceivedMinor,
                receivedDateEpochDay = date.toEpochDay(),
                statusNote = note ?: "Confirmed arrival in Nepal",
            )
        }
    }

    fun saveRemittance(remittance: Remittance, createFinanceTx: Boolean) {
        viewModelScope.launch {
            repository.saveRemittance(remittance, createFinanceTx = createFinanceTx)
        }
    }

    fun saveRecipient(recipient: Recipient) {
        viewModelScope.launch {
            repository.saveRecipient(recipient)
        }
    }
}

@Composable
fun RemittanceScreen(
    onBack: () -> Unit,
    onOpenRemittance: (Long) -> Unit,
    onOpenRecipients: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenReports: () -> Unit,
    onOpenAdd: (() -> Unit)? = null,
    viewModel: RemittanceViewModel = viewModel(factory = AppViewModelProvider.Factory),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val c = LocalAppColors.current
    val eurMoney = remember { Money("EUR") }
    val nprMoney = remember { Money("NPR") }

    var confirmingReceivedRemittance by remember { mutableStateOf<Remittance?>(null) }
    var showAddSheet by remember { mutableStateOf(false) }

    val handleOpenAdd = onOpenAdd ?: { showAddSheet = true }

    GlassScreen(
        title = "Remittances",
        onBack = onBack,
        actions = {
            RoundButton(Lucide.Users, "Recipients", onOpenRecipients)
            RoundButton(Lucide.ChartPie, "Reports", onOpenReports)
            RoundButton(Lucide.History, "History", onOpenHistory)
        },
        bottomBar = {
            Row(
                Modifier.frosted().padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                SecondaryButton("Recipients", onOpenRecipients, Modifier.weight(1f), icon = Lucide.Users)
                PrimaryButton("Record transfer", handleOpenAdd, Modifier.weight(1.3f), icon = Lucide.Plus)
            }
        },
    ) { padding ->
        if (!state.loaded) return@GlassScreen

        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = padding.calculateTopPadding() + 8.dp,
                bottom = padding.calculateBottomPadding() + 16.dp,
            ),
        ) {
            // Period Toggle & Month/Year Picker
            item {
                Row(
                    Modifier.fillMaxWidth().padding(bottom = 12.dp).appear(0),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    SegmentedToggle(
                        options = RemittancePeriodMode.entries,
                        selected = state.periodMode,
                        label = { it.name.lowercase().replaceFirstChar(Char::titlecase) },
                        onSelect = viewModel::setPeriodMode,
                        modifier = Modifier.width(200.dp),
                    )

                    if (state.periodMode == RemittancePeriodMode.MONTHLY) {
                        MonthSelector(state.selectedMonth, viewModel::setMonth)
                    } else {
                        YearSelector(state.selectedYear, viewModel::setYear)
                    }
                }
            }

            // Optional Filter Pills (Recipient & Provider)
            if (state.recipients.isNotEmpty() || state.providers.isNotEmpty()) {
                item {
                    LazyRow(
                        Modifier.padding(bottom = 14.dp).appear(1),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        item {
                            Chip(
                                text = "All Recipients",
                                selected = state.selectedRecipientId == null,
                                onClick = { viewModel.setRecipientFilter(null) },
                            )
                        }
                        items(state.recipients, key = { "rec-${it.id}" }) { r ->
                            Chip(
                                text = r.name,
                                selected = state.selectedRecipientId == r.id,
                                onClick = {
                                    viewModel.setRecipientFilter(if (state.selectedRecipientId == r.id) null else r.id)
                                },
                            )
                        }
                        if (state.providers.isNotEmpty()) {
                            item {
                                Chip(
                                    text = state.selectedProvider ?: "All Providers",
                                    selected = state.selectedProvider != null,
                                    onClick = {
                                        if (state.selectedProvider != null) {
                                            viewModel.setProviderFilter(null)
                                        } else if (state.providers.isNotEmpty()) {
                                            viewModel.setProviderFilter(state.providers.first())
                                        }
                                    },
                                    icon = Lucide.ListFilter,
                                )
                            }
                        }
                    }
                }
            }

            // Summary Hero Card
            item {
                RemittanceSummaryCard(
                    summary = state.summary,
                    periodTitle = if (state.periodMode == RemittancePeriodMode.MONTHLY) state.selectedMonth.label() else state.selectedYear.toString(),
                    eurMoney = eurMoney,
                    nprMoney = nprMoney,
                    modifier = Modifier.appear(2),
                )
            }

            // Pending Transfers Section (if any exist)
            if (state.pendingRemittances.isNotEmpty()) {
                item {
                    SectionHeader(
                        title = "Pending transfers (${state.pendingRemittances.size})",
                        modifier = Modifier.appear(3),
                    )
                }

                item {
                    Glass(
                        Modifier.fillMaxWidth().appear(3),
                        shape = RoundedCornerShape(24.dp),
                    ) {
                        Column {
                            state.pendingRemittances.forEachIndexed { i, rem ->
                                if (i > 0) RowDivider()
                                PendingRemittanceRow(
                                    item = rem,
                                    eurMoney = eurMoney,
                                    nprMoney = nprMoney,
                                    onClick = { onOpenRemittance(rem.id) },
                                    onConfirmReceived = { confirmingReceivedRemittance = rem },
                                )
                            }
                        }
                    }
                }
            }

            // Period Remittances Header
            item {
                SectionHeader(
                    title = "Transfers in period (${state.periodRemittances.size})",
                    action = "Full history",
                    onAction = onOpenHistory,
                    modifier = Modifier.appear(4),
                )
            }

            // Period Remittances List or Empty State
            if (state.periodRemittances.isEmpty()) {
                item {
                    Glass(
                        Modifier.fillMaxWidth().appear(5),
                        shape = RoundedCornerShape(24.dp),
                    ) {
                        EmptyState(
                            icon = Lucide.Send,
                            title = "No transfers recorded",
                            message = if (state.allRemittances.isEmpty()) {
                                "Record money transfers sent home to Nepal with exact exchange rates and fees."
                            } else {
                                "No transfers found for the selected period and filters."
                            },
                        )
                    }
                }
            } else {
                item {
                    Glass(
                        Modifier.fillMaxWidth().appear(5),
                        shape = RoundedCornerShape(24.dp),
                    ) {
                        Column {
                            state.periodRemittances.forEachIndexed { i, item ->
                                if (i > 0) RowDivider()
                                RemittanceItemRow(
                                    remittance = item,
                                    onClick = { onOpenRemittance(item.id) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // Modal to confirm receipt of a pending transfer
    confirmingReceivedRemittance?.let { rem ->
        ConfirmReceivedDialog(
            remittance = rem,
            nprMoney = nprMoney,
            onDismiss = { confirmingReceivedRemittance = null },
            onConfirm = { actualNprMinor, date, note ->
                viewModel.markAsReceived(rem.id, actualNprMinor, date, note)
                confirmingReceivedRemittance = null
            },
        )
    }

    // Add Remittance Sheet
    if (showAddSheet) {
        RemittanceEditorSheet(
            initialRemittance = null,
            onDismiss = { showAddSheet = false },
            onSaved = { showAddSheet = false },
        )
    }
}

@Composable
private fun RemittanceSummaryCard(
    summary: RemittanceSummary,
    periodTitle: String,
    eurMoney: Money,
    nprMoney: Money,
    modifier: Modifier = Modifier,
) {
    val c = LocalAppColors.current

    Glass(
        modifier = modifier.fillMaxWidth(),
        strong = true,
        shape = RoundedCornerShape(32.dp),
    ) {
        Column(Modifier.padding(22.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    "Total Principal Sent",
                    style = MaterialTheme.typography.labelLarge,
                    color = c.textSecondary,
                )
                Text(
                    periodTitle,
                    style = MaterialTheme.typography.labelMedium,
                    color = c.textSecondary,
                )
            }

            Spacer(Modifier.height(4.dp))

            // EUR Principal Sent (Large Rolling Text)
            RollingText(
                text = eurMoney.format(summary.totalPrincipalSentEurMinor),
                style = MaterialTheme.typography.headlineLarge.copy(fontWeight = FontWeight.Bold),
                color = c.textPrimary,
            )

            Spacer(Modifier.height(14.dp))
            RowDivider()
            Spacer(Modifier.height(14.dp))

            // Summary grid
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatTile(
                    label = "Total EUR cost",
                    value = eurMoney.format(summary.totalCostEurMinor),
                    footnote = if (summary.totalFeesEurMinor > 0) "+ ${eurMoney.format(summary.totalFeesEurMinor)} fees" else "No fees",
                    modifier = Modifier.weight(1f),
                )
                StatTile(
                    label = "NPR received",
                    value = nprMoney.format(summary.totalNprActuallyReceivedMinor),
                    footnote = if (summary.weightedAverageExchangeRate != null) {
                        "Avg rate: ${summary.weightedAverageExchangeRate}"
                    } else "No completed transfers",
                    modifier = Modifier.weight(1f),
                )
            }

            // Transfer status pills
            if (summary.totalTransfers > 0) {
                Spacer(Modifier.height(16.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (summary.completedCount > 0) {
                        StatusCountPill(
                            text = "${summary.completedCount} received",
                            bg = c.positive.copy(alpha = 0.16f),
                            textColor = c.positive,
                        )
                    }
                    if (summary.pendingCount > 0) {
                        StatusCountPill(
                            text = "${summary.pendingCount} pending",
                            bg = c.accent.copy(alpha = 0.16f),
                            textColor = c.accent,
                        )
                    }
                    if (summary.failedOrRefundedCount > 0) {
                        StatusCountPill(
                            text = "${summary.failedOrRefundedCount} failed/refunded",
                            bg = c.negative.copy(alpha = 0.14f),
                            textColor = c.negative,
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun RemittanceItemRow(
    remittance: Remittance,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = LocalAppColors.current
    val sendMoney = remember(remittance.sendCurrency) { Money(remittance.sendCurrency) }
    val receiveMoney = remember(remittance.receiveCurrency) { Money(remittance.receiveCurrency) }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .pressable(onClick, pressedScale = 0.98f)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconTile(
            icon = when (remittance.status) {
                RemittanceStatus.RECEIVED -> Lucide.Check
                RemittanceStatus.FAILED -> Lucide.CircleAlert
                RemittanceStatus.REFUNDED -> Lucide.RotateCcw
                RemittanceStatus.CANCELLED -> Lucide.Ban
                else -> Lucide.Send
            },
            tint = when (remittance.status) {
                RemittanceStatus.RECEIVED -> c.positive
                RemittanceStatus.FAILED, RemittanceStatus.CANCELLED -> c.negative
                RemittanceStatus.REFUNDED -> c.accent
                else -> c.textPrimary
            },
            size = 44.dp,
        )

        Spacer(Modifier.width(14.dp))

        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    remittance.recipientName,
                    style = MaterialTheme.typography.titleSmall,
                    color = c.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.width(6.dp))
                RemittanceStatusPill(remittance.status)
            }
            Spacer(Modifier.height(2.dp))
            val dateStr = remittance.transferDate.toString()
            val providerStr = remittance.provider
            Text(
                "$dateStr · $providerStr",
                style = MaterialTheme.typography.bodySmall,
                color = c.textSecondary,
                maxLines = 1,
            )
        }

        Column(horizontalAlignment = Alignment.End) {
            Text(
                sendMoney.format(remittance.sendAmountMinor),
                style = MaterialTheme.typography.titleSmall,
                color = c.textPrimary,
                fontWeight = FontWeight.SemiBold,
            )
            val receiveDisplay = if (remittance.status == RemittanceStatus.RECEIVED && remittance.actualReceiveAmountMinor != null) {
                receiveMoney.format(remittance.actualReceiveAmountMinor)
            } else {
                "≈ ${receiveMoney.format(remittance.expectedReceiveAmountMinor)}"
            }
            Text(
                receiveDisplay,
                style = MaterialTheme.typography.bodySmall,
                color = if (remittance.status == RemittanceStatus.RECEIVED) c.positive else c.textSecondary,
            )
        }
    }
}

@Composable
private fun PendingRemittanceRow(
    item: Remittance,
    eurMoney: Money,
    nprMoney: Money,
    onClick: () -> Unit,
    onConfirmReceived: () -> Unit,
) {
    val c = LocalAppColors.current

    Row(
        Modifier
            .fillMaxWidth()
            .pressable(onClick, pressedScale = 0.98f)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconTile(Lucide.Clock, c.accent, size = 42.dp)
        Spacer(Modifier.width(12.dp))

        Column(Modifier.weight(1f)) {
            Text(
                item.recipientName,
                style = MaterialTheme.typography.titleSmall,
                color = c.textPrimary,
            )
            Text(
                "${item.provider} · ${item.transferDate}",
                style = MaterialTheme.typography.bodySmall,
                color = c.textSecondary,
            )
        }

        Column(horizontalAlignment = Alignment.End) {
            Text(
                eurMoney.format(item.sendAmountMinor),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = c.textPrimary,
            )
            Text(
                "→ ${nprMoney.format(item.expectedReceiveAmountMinor)}",
                style = MaterialTheme.typography.bodySmall,
                color = c.textSecondary,
            )
        }

        Spacer(Modifier.width(8.dp))

        Box(
            Modifier
                .clip(CircleShape)
                .background(c.positive.copy(alpha = 0.16f))
                .clickable(onClick = onConfirmReceived)
                .padding(8.dp),
        ) {
            Icon(Lucide.Check, contentDescription = "Mark as received", tint = c.positive, modifier = Modifier.size(16.dp))
        }
    }
}

@Composable
fun RemittanceStatusPill(status: RemittanceStatus) {
    val c = LocalAppColors.current
    val (bg, textColor) = when (status) {
        RemittanceStatus.RECEIVED -> c.positive.copy(alpha = 0.16f) to c.positive
        RemittanceStatus.SENT, RemittanceStatus.PROCESSING -> c.accent.copy(alpha = 0.16f) to c.accent
        RemittanceStatus.PLANNED -> c.textSecondary.copy(alpha = 0.12f) to c.textSecondary
        RemittanceStatus.FAILED -> c.negative.copy(alpha = 0.16f) to c.negative
        RemittanceStatus.CANCELLED -> c.textTertiary.copy(alpha = 0.14f) to c.textTertiary
        RemittanceStatus.REFUNDED -> c.accent.copy(alpha = 0.16f) to c.accent
    }

    val icon = when (status) {
        RemittanceStatus.PLANNED -> Lucide.Calendar
        RemittanceStatus.SENT -> Lucide.Send
        RemittanceStatus.PROCESSING -> Lucide.Clock
        RemittanceStatus.RECEIVED -> Lucide.Check
        RemittanceStatus.FAILED -> Lucide.CircleAlert
        RemittanceStatus.CANCELLED -> Lucide.Ban
        RemittanceStatus.REFUNDED -> Lucide.RotateCcw
    }

    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(bg)
            .padding(horizontal = 6.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = textColor,
            modifier = Modifier.size(11.dp),
        )
        Text(
            text = status.label,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
            color = textColor,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun StatusCountPill(text: String, bg: Color, textColor: Color) {
    Box(
        Modifier
            .clip(CircleShape)
            .background(bg)
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            color = textColor,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun MonthSelector(month: YearMonth, onSelect: (YearMonth) -> Unit) {
    val c = LocalAppColors.current
    var expanded by remember { mutableStateOf(false) }

    Box {
        Row(
            Modifier
                .clip(CircleShape)
                .background(c.glass)
                .clickable { expanded = true }
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(month.label(), style = MaterialTheme.typography.labelLarge, color = c.textPrimary)
            Spacer(Modifier.width(4.dp))
            Icon(Lucide.ChevronDown, contentDescription = null, tint = c.textSecondary, modifier = Modifier.size(16.dp))
        }

        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            val current = YearMonth.now()
            for (i in 0..12) {
                val m = current.minusMonths(i.toLong())
                DropdownMenuItem(
                    text = { Text(m.label()) },
                    onClick = {
                        onSelect(m)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
private fun YearSelector(year: Int, onSelect: (Int) -> Unit) {
    val c = LocalAppColors.current
    var expanded by remember { mutableStateOf(false) }

    Box {
        Row(
            Modifier
                .clip(CircleShape)
                .background(c.glass)
                .clickable { expanded = true }
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(year.toString(), style = MaterialTheme.typography.labelLarge, color = c.textPrimary)
            Spacer(Modifier.width(4.dp))
            Icon(Lucide.ChevronDown, contentDescription = null, tint = c.textSecondary, modifier = Modifier.size(16.dp))
        }

        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            val currentYear = LocalDate.now().year
            for (y in currentYear downTo currentYear - 5) {
                DropdownMenuItem(
                    text = { Text(y.toString()) },
                    onClick = {
                        onSelect(y)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
private fun ConfirmReceivedDialog(
    remittance: Remittance,
    nprMoney: Money,
    onDismiss: () -> Unit,
    onConfirm: (actualNprMinor: Long, receivedDate: LocalDate, note: String?) -> Unit,
) {
    val c = LocalAppColors.current
    var actualNprInput by remember {
        mutableStateOf(
            AmountInput.fromMinor(remittance.actualReceiveAmountMinor ?: remittance.expectedReceiveAmountMinor),
        )
    }
    var note by remember { mutableStateOf("") }
    val receiveDate = remember { LocalDate.now() }

    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Confirm Transfer Received") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "Confirming arrival of remittance to ${remittance.recipientName} via ${remittance.provider}.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = c.textSecondary,
                )

                androidx.compose.material3.OutlinedTextField(
                    value = actualNprInput,
                    onValueChange = { actualNprInput = it },
                    label = { Text("Actual NPR Received") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                androidx.compose.material3.OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text("Confirmation Note (optional)") },
                    placeholder = { Text("e.g. Prabhu Bank confirmed") },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(
                onClick = {
                    val minor = AmountInput.toMinor(actualNprInput) ?: remittance.expectedReceiveAmountMinor
                    onConfirm(minor, receiveDate, note.ifBlank { null })
                },
            ) {
                Text("Confirm Received", color = c.positive, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) {
                Text("Cancel", color = c.textSecondary)
            }
        },
    )
}
