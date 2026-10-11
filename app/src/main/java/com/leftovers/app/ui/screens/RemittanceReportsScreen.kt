package com.leftovers.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.leftovers.app.data.Recipient
import com.leftovers.app.data.Remittance
import com.leftovers.app.data.RemittanceCalculations
import com.leftovers.app.data.RemittanceRepository
import com.leftovers.app.data.RemittanceStatus
import com.leftovers.app.ui.AppViewModelProvider
import com.leftovers.app.ui.components.DockClearance
import com.leftovers.app.ui.components.Glass
import com.leftovers.app.ui.components.GlassScreen
import com.leftovers.app.ui.components.ProgressLine
import com.leftovers.app.ui.components.RowDivider
import com.leftovers.app.ui.components.SectionHeader
import com.leftovers.app.ui.icons.Lucide
import com.leftovers.app.ui.theme.LocalAppColors
import com.leftovers.app.util.Money
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import java.time.YearMonth
import java.time.format.DateTimeFormatter

data class RemittanceReportsUiState(
    val remittances: List<Remittance> = emptyList(),
    val recipients: List<Recipient> = emptyList(),
)

class RemittanceReportsViewModel(
    private val repository: RemittanceRepository,
) : ViewModel() {
    val state: StateFlow<RemittanceReportsUiState> = combine(
        repository.allRemittances,
        repository.allRecipients,
    ) { remittances, recipients ->
        RemittanceReportsUiState(
            remittances = remittances,
            recipients = recipients,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), RemittanceReportsUiState())
}

@Composable
fun RemittanceReportsScreen(
    onBack: () -> Unit,
    viewModel: RemittanceReportsViewModel = viewModel(factory = AppViewModelProvider.Factory),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val c = LocalAppColors.current
    val eurMoney = remember { Money("EUR") }
    val nprMoney = remember { Money("NPR") }

    var isMonthly by remember { mutableStateOf(true) }
    var selectedMonth by remember { mutableStateOf(YearMonth.now()) }
    var selectedYear by remember { mutableStateOf(YearMonth.now().year) }

    val recipientsMap = remember(state.recipients) { state.recipients.associateBy { it.id } }

    val periodRemittances = remember(state.remittances, isMonthly, selectedMonth, selectedYear) {
        state.remittances.filter {
            if (isMonthly) {
                YearMonth.from(it.transferDate) == selectedMonth
            } else {
                it.transferDate.year == selectedYear
            }
        }
    }

    val summary = remember(periodRemittances) {
        RemittanceCalculations.summarize(periodRemittances)
    }

    // Recipient Breakdown
    val recipientBreakdown = remember(periodRemittances, recipientsMap) {
        periodRemittances.groupBy {
            it.recipientId?.toString() ?: "name_${it.recipientName.trim().lowercase()}"
        }.map { (_, items) ->
            val name = (items.firstOrNull()?.recipientId?.let { recipientsMap[it]?.name }) ?: items.firstOrNull()?.recipientName ?: "Unknown"
            val totalSent = items.filter { it.status != RemittanceStatus.CANCELLED && it.status != RemittanceStatus.FAILED && it.status != RemittanceStatus.REFUNDED }
                .sumOf { it.sendAmountMinor }
            val completedReceived = items.filter { it.status == RemittanceStatus.RECEIVED }
                .sumOf { it.actualReceiveAmountMinor ?: it.expectedReceiveAmountMinor }
            val count = items.count { it.status != RemittanceStatus.CANCELLED && it.status != RemittanceStatus.FAILED && it.status != RemittanceStatus.REFUNDED }
            RecipientReportItem(name, count, totalSent, completedReceived)
        }.filter { it.count > 0 || it.totalSentMinor > 0 }.sortedByDescending { it.totalSentMinor }
    }

    // Provider Breakdown
    val providerBreakdown = remember(periodRemittances) {
        periodRemittances.groupBy { it.provider.trim() }.map { (prov, items) ->
            val totalSent = items.filter { it.status != RemittanceStatus.CANCELLED && it.status != RemittanceStatus.FAILED && it.status != RemittanceStatus.REFUNDED }
                .sumOf { it.sendAmountMinor }
            val count = items.count { it.status != RemittanceStatus.CANCELLED && it.status != RemittanceStatus.FAILED && it.status != RemittanceStatus.REFUNDED }
            ProviderReportItem(prov, count, totalSent)
        }.filter { it.count > 0 || it.totalSentMinor > 0 }.sortedByDescending { it.totalSentMinor }
    }

    GlassScreen(
        title = "Remittance Reports",
        onBack = onBack,
    ) { padding ->
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
            // Period Toggle & Selector
            item {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(c.glass)
                            .padding(4.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (isMonthly) c.accent else Color.Transparent)
                                .clickable { isMonthly = true }
                                .padding(vertical = 8.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                "Monthly Report",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = if (isMonthly) FontWeight.Bold else FontWeight.Medium,
                                color = if (isMonthly) c.onAccent else c.textSecondary,
                            )
                        }
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (!isMonthly) c.accent else Color.Transparent)
                                .clickable { isMonthly = false }
                                .padding(vertical = 8.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                "Yearly Report",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = if (!isMonthly) FontWeight.Bold else FontWeight.Medium,
                                color = if (!isMonthly) c.onAccent else c.textSecondary,
                            )
                        }
                    }

                    // Prev / Next Period controls
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(
                            onClick = {
                                if (isMonthly) selectedMonth = selectedMonth.minusMonths(1)
                                else selectedYear--
                            },
                        ) {
                            Icon(Lucide.ChevronLeft, contentDescription = "Previous", tint = c.textPrimary)
                        }

                        val title = if (isMonthly) {
                            selectedMonth.format(DateTimeFormatter.ofPattern("MMMM yyyy"))
                        } else {
                            "Year $selectedYear"
                        }
                        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = c.textPrimary)

                        IconButton(
                            onClick = {
                                if (isMonthly) selectedMonth = selectedMonth.plusMonths(1)
                                else selectedYear++
                            },
                        ) {
                            Icon(Lucide.ChevronRight, contentDescription = "Next", tint = c.textPrimary)
                        }
                    }
                }
            }

            // Summary Totals Card
            item {
                SectionHeader(title = "Financial Summary")
                Glass(Modifier.fillMaxWidth(), strong = true, shape = RoundedCornerShape(24.dp)) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Column {
                                Text("Principal Sent", style = MaterialTheme.typography.labelSmall, color = c.textSecondary)
                                Text(eurMoney.format(summary.totalPrincipalSentEurMinor), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = c.textPrimary)
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                Text("Transfer Fees", style = MaterialTheme.typography.labelSmall, color = c.textSecondary)
                                Text(eurMoney.format(summary.totalFeesEurMinor), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = c.textSecondary)
                            }
                        }

                        RowDivider()

                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Column {
                                Text("Total Cost (Principal + Fees)", style = MaterialTheme.typography.labelSmall, color = c.textSecondary)
                                Text(eurMoney.format(summary.totalCostEurMinor), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = c.accent)
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                Text("NPR Received (Confirmed)", style = MaterialTheme.typography.labelSmall, color = c.textSecondary)
                                Text(nprMoney.format(summary.totalNprActuallyReceivedMinor), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = c.positive)
                            }
                        }

                        if (summary.weightedAverageExchangeRate != null) {
                            RowDivider()
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("Weighted Avg Rate (Completed)", style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
                                Text("1 EUR = ${summary.weightedAverageExchangeRate} NPR", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold, color = c.textPrimary)
                            }
                        }
                    }
                }
            }

            // Transfer Status Metrics
            item {
                SectionHeader(title = "Transfer Volumes")
                Glass(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("Completed", style = MaterialTheme.typography.labelSmall, color = c.textSecondary)
                            Text("${summary.completedCount}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = c.positive)
                        }
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("Pending", style = MaterialTheme.typography.labelSmall, color = c.textSecondary)
                            Text("${summary.pendingCount}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = c.accent)
                        }
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("Failed / Ref.", style = MaterialTheme.typography.labelSmall, color = c.textSecondary)
                            Text("${summary.failedOrRefundedCount}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = c.negative)
                        }
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("Total", style = MaterialTheme.typography.labelSmall, color = c.textSecondary)
                            Text("${summary.totalTransfers}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = c.textPrimary)
                        }
                    }
                }
            }

            // Recipient Breakdown Section
            if (recipientBreakdown.isNotEmpty()) {
                item {
                    SectionHeader(title = "Breakdown by Recipient")
                }
                item {
                    Glass(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                            val maxSent = recipientBreakdown.maxOfOrNull { it.totalSentMinor }?.takeIf { it > 0 } ?: 1L
                            recipientBreakdown.forEach { item ->
                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                        Text(item.recipientName, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = c.textPrimary)
                                        Text(eurMoney.format(item.totalSentMinor), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = c.textPrimary)
                                    }
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                        Text("${item.count} transfer(s)", style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
                                        Text("${nprMoney.format(item.completedReceivedMinor)} rec'd", style = MaterialTheme.typography.bodySmall, color = c.positive)
                                    }
                                    val fraction = item.totalSentMinor.toFloat() / maxSent
                                    ProgressLine(fraction = fraction, color = c.accent, height = 4.dp)
                                }
                            }
                        }
                    }
                }
            }

            // Provider Breakdown Section
            if (providerBreakdown.isNotEmpty()) {
                item {
                    SectionHeader(title = "Breakdown by Provider")
                }
                item {
                    Glass(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                            val maxSent = providerBreakdown.maxOfOrNull { it.totalSentMinor }?.takeIf { it > 0 } ?: 1L
                            providerBreakdown.forEach { item ->
                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                        Text(item.provider, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = c.textPrimary)
                                        Text(eurMoney.format(item.totalSentMinor), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = c.textPrimary)
                                    }
                                    Text("${item.count} transfer(s)", style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
                                    val fraction = item.totalSentMinor.toFloat() / maxSent
                                    ProgressLine(fraction = fraction, color = c.positive, height = 4.dp)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private data class RecipientReportItem(
    val recipientName: String,
    val count: Int,
    val totalSentMinor: Long,
    val completedReceivedMinor: Long,
)

private data class ProviderReportItem(
    val provider: String,
    val count: Int,
    val totalSentMinor: Long,
)
