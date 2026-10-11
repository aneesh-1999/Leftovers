package com.leftovers.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.ui.graphics.vector.ImageVector
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.leftovers.app.data.Recipient
import com.leftovers.app.data.Remittance
import com.leftovers.app.data.RemittanceRepository
import com.leftovers.app.data.RemittanceStatus
import com.leftovers.app.ui.AppViewModelProvider
import com.leftovers.app.ui.components.DockClearance
import com.leftovers.app.ui.components.Glass
import com.leftovers.app.ui.components.GlassScreen
import com.leftovers.app.ui.components.SectionHeader
import com.leftovers.app.ui.icons.Lucide
import com.leftovers.app.ui.theme.LocalAppColors
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

data class RemittanceHistoryUiState(
    val remittances: List<Remittance> = emptyList(),
    val recipients: List<Recipient> = emptyList(),
)

class RemittanceHistoryViewModel(
    private val repository: RemittanceRepository,
) : ViewModel() {
    val state: StateFlow<RemittanceHistoryUiState> = combine(
        repository.allRemittances,
        repository.allRecipients,
    ) { remittances, recipients ->
        RemittanceHistoryUiState(
            remittances = remittances,
            recipients = recipients,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), RemittanceHistoryUiState())
}

@Composable
fun RemittanceHistoryScreen(
    onBack: () -> Unit,
    onOpenRemittance: (Long) -> Unit,
    onOpenAdd: () -> Unit,
    initialRecipientId: Long? = null,
    viewModel: RemittanceHistoryViewModel = viewModel(factory = AppViewModelProvider.Factory),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val c = LocalAppColors.current

    var searchQuery by remember { mutableStateOf("") }
    var selectedStatus by remember { mutableStateOf<RemittanceStatus?>(null) }
    var selectedRecipientId by remember { mutableStateOf(initialRecipientId) }
    var selectedProvider by remember { mutableStateOf<String?>(null) }
    var selectedYear by remember { mutableStateOf<Int?>(null) }

    val recipientsMap = remember(state.recipients) { state.recipients.associateBy { it.id } }
    val providers = remember(state.remittances) { state.remittances.map { it.provider }.distinct().sorted() }
    val years = remember(state.remittances) { state.remittances.map { it.transferDate.year }.distinct().sortedDescending() }

    val filteredRemittances = remember(
        state.remittances,
        searchQuery,
        selectedStatus,
        selectedRecipientId,
        selectedProvider,
        selectedYear,
        recipientsMap,
    ) {
        state.remittances.filter { rem ->
            val matchesQuery = searchQuery.isBlank() ||
                rem.provider.contains(searchQuery, ignoreCase = true) ||
                (rem.referenceNumber?.contains(searchQuery, ignoreCase = true) == true) ||
                rem.notes.contains(searchQuery, ignoreCase = true) ||
                rem.recipientName.contains(searchQuery, ignoreCase = true)

            val matchesStatus = selectedStatus == null || rem.status == selectedStatus
            val matchesRecipient = selectedRecipientId == null || rem.recipientId == selectedRecipientId
            val matchesProvider = selectedProvider == null || rem.provider.equals(selectedProvider, ignoreCase = true)
            val matchesYear = selectedYear == null || rem.transferDate.year == selectedYear

            matchesQuery && matchesStatus && matchesRecipient && matchesProvider && matchesYear
        }
    }

    GlassScreen(
        title = "Remittance History",
        onBack = onBack,
        actions = {
            IconButton(onClick = onOpenAdd) {
                Icon(Lucide.Plus, contentDescription = "Add Remittance", tint = c.accent)
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 20.dp,
                end = 20.dp,
                top = padding.calculateTopPadding() + 8.dp,
                bottom = padding.calculateBottomPadding() + 16.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // Search Box
            item {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text("Search by recipient, provider, ref #...", color = c.textSecondary) },
                    leadingIcon = { Icon(Lucide.Search, contentDescription = null, tint = c.textSecondary, modifier = Modifier.size(18.dp)) },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { searchQuery = "" }) {
                                Icon(Lucide.X, contentDescription = "Clear", tint = c.textSecondary, modifier = Modifier.size(18.dp))
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    singleLine = true,
                )
            }

            // Filter Chips Carousel
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    // Status filters
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        HistoryFilterChip(
                            label = "All Statuses",
                            selected = selectedStatus == null,
                            onClick = { selectedStatus = null },
                        )
                        RemittanceStatus.entries.forEach { st ->
                            val icon = when (st) {
                                RemittanceStatus.PLANNED -> Lucide.Calendar
                                RemittanceStatus.SENT -> Lucide.Send
                                RemittanceStatus.PROCESSING -> Lucide.Clock
                                RemittanceStatus.RECEIVED -> Lucide.Check
                                RemittanceStatus.FAILED -> Lucide.CircleAlert
                                RemittanceStatus.CANCELLED -> Lucide.Ban
                                RemittanceStatus.REFUNDED -> Lucide.RotateCcw
                            }
                            HistoryFilterChip(
                                label = st.label,
                                icon = icon,
                                selected = selectedStatus == st,
                                onClick = { selectedStatus = if (selectedStatus == st) null else st },
                            )
                        }
                    }

                    // Recipient & Provider & Year filters
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (years.isNotEmpty()) {
                            HistoryFilterChip(
                                label = "All Years",
                                selected = selectedYear == null,
                                onClick = { selectedYear = null },
                            )
                            years.forEach { y ->
                                HistoryFilterChip(
                                    label = "$y",
                                    selected = selectedYear == y,
                                    onClick = { selectedYear = if (selectedYear == y) null else y },
                                )
                            }
                        }

                        if (providers.isNotEmpty()) {
                            providers.forEach { prov ->
                                HistoryFilterChip(
                                    label = prov,
                                    selected = selectedProvider.equals(prov, ignoreCase = true),
                                    onClick = {
                                        selectedProvider = if (selectedProvider.equals(prov, ignoreCase = true)) null else prov
                                    },
                                )
                            }
                        }

                        state.recipients.forEach { rec ->
                            HistoryFilterChip(
                                label = rec.name,
                                selected = selectedRecipientId == rec.id,
                                onClick = {
                                    selectedRecipientId = if (selectedRecipientId == rec.id) null else rec.id
                                },
                            )
                        }
                    }
                }
            }

            // Results count
            item {
                SectionHeader(title = "Transfers (${filteredRemittances.size})")
            }

            if (filteredRemittances.isEmpty()) {
                item {
                    Glass(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
                        Column(
                            Modifier.padding(32.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Icon(Lucide.Search, contentDescription = null, tint = c.textSecondary, modifier = Modifier.size(44.dp))
                            Spacer(Modifier.height(12.dp))
                            Text("No transfers found", style = MaterialTheme.typography.titleMedium, color = c.textPrimary)
                            Spacer(Modifier.height(4.dp))
                            Text("Try adjusting your search or active filters.", style = MaterialTheme.typography.bodyMedium, color = c.textSecondary)
                        }
                    }
                }
            } else {
                items(filteredRemittances, key = { it.id }) { rem ->
                    RemittanceItemRow(
                        remittance = rem,
                        onClick = { onOpenRemittance(rem.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun HistoryFilterChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    icon: ImageVector? = null,
) {
    val c = LocalAppColors.current
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) c.accent.copy(alpha = 0.2f) else c.glass)
            .border(
                width = if (selected) 1.5.dp else 1.dp,
                color = if (selected) c.accent else c.borderTop,
                shape = RoundedCornerShape(12.dp),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (selected) c.accent else c.textSecondary,
                modifier = Modifier.size(14.dp),
            )
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            color = if (selected) c.accent else c.textSecondary,
        )
    }
}
