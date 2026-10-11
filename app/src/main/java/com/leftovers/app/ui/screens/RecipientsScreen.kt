package com.leftovers.app.ui.screens

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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.leftovers.app.data.Recipient
import com.leftovers.app.data.RecipientWithStats
import com.leftovers.app.data.RemittanceRepository
import com.leftovers.app.ui.AppViewModelProvider
import com.leftovers.app.ui.components.DockClearance
import com.leftovers.app.ui.components.Glass
import com.leftovers.app.ui.components.GlassScreen
import com.leftovers.app.ui.components.IconTile
import com.leftovers.app.ui.components.RowDivider
import com.leftovers.app.ui.components.SectionHeader
import com.leftovers.app.ui.icons.Lucide
import com.leftovers.app.ui.theme.LocalAppColors
import com.leftovers.app.util.Money
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class RecipientsUiState(
    val activeRecipients: List<RecipientWithStats> = emptyList(),
    val archivedRecipients: List<RecipientWithStats> = emptyList(),
)

class RecipientsViewModel(
    private val repository: RemittanceRepository,
) : ViewModel() {
    val state: StateFlow<RecipientsUiState> = repository.recipientsWithStats.map { all ->
        RecipientsUiState(
            activeRecipients = all.filter { !it.recipient.archived },
            archivedRecipients = all.filter { it.recipient.archived },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), RecipientsUiState())

    fun saveRecipient(recipient: Recipient) {
        viewModelScope.launch {
            repository.saveRecipient(recipient)
        }
    }

    fun setArchived(recipientId: Long, archived: Boolean) {
        viewModelScope.launch {
            repository.setRecipientArchived(recipientId, archived)
        }
    }

    fun deleteRecipient(recipient: Recipient) {
        viewModelScope.launch {
            repository.deleteRecipient(recipient)
        }
    }
}

@Composable
fun RecipientsScreen(
    onBack: () -> Unit,
    onSelectRecipient: (Long) -> Unit = {},
    viewModel: RecipientsViewModel = viewModel(factory = AppViewModelProvider.Factory),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val c = LocalAppColors.current

    var showEditorDialog by remember { mutableStateOf(false) }
    var editingRecipient by remember { mutableStateOf<Recipient?>(null) }
    var searchQuery by remember { mutableStateOf("") }
    var showArchived by remember { mutableStateOf(false) }
    var deleteCandidate by remember { mutableStateOf<Recipient?>(null) }

    val filteredActive = state.activeRecipients.filter {
        searchQuery.isBlank() ||
            it.recipient.name.contains(searchQuery, ignoreCase = true) ||
            (it.recipient.relationship?.contains(searchQuery, ignoreCase = true) == true)
    }

    val filteredArchived = state.archivedRecipients.filter {
        searchQuery.isBlank() ||
            it.recipient.name.contains(searchQuery, ignoreCase = true) ||
            (it.recipient.relationship?.contains(searchQuery, ignoreCase = true) == true)
    }

    GlassScreen(
        title = "Recipients",
        onBack = onBack,
        actions = {
            IconButton(
                onClick = {
                    editingRecipient = null
                    showEditorDialog = true
                },
            ) {
                Icon(Lucide.Plus, contentDescription = "Add Recipient", tint = c.accent)
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
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Search Input
            item {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text("Search recipients...", color = c.textSecondary) },
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

            // Active Recipients
            if (filteredActive.isNotEmpty()) {
                item {
                    SectionHeader(title = "Active Recipients (${filteredActive.size})")
                }
                items(filteredActive, key = { it.recipient.id }) { item ->
                    RecipientCard(
                        item = item,
                        onClick = { onSelectRecipient(item.recipient.id) },
                        onEdit = {
                            editingRecipient = item.recipient
                            showEditorDialog = true
                        },
                        onArchive = { viewModel.setArchived(item.recipient.id, true) },
                    )
                }
            } else if (searchQuery.isBlank() && state.activeRecipients.isEmpty()) {
                item {
                    Glass(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
                        Column(
                            Modifier.padding(32.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Icon(Lucide.Users, contentDescription = null, tint = c.accent, modifier = Modifier.size(48.dp))
                            Spacer(Modifier.height(12.dp))
                            Text("No Recipients Added", style = MaterialTheme.typography.titleMedium, color = c.textPrimary)
                            Spacer(Modifier.height(4.dp))
                            Text("Add family members or accounts in Nepal to easily record transfers.", style = MaterialTheme.typography.bodyMedium, color = c.textSecondary)
                        }
                    }
                }
            }

            // Archived Recipients toggle
            if (state.archivedRecipients.isNotEmpty()) {
                item {
                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { showArchived = !showArchived }
                            .padding(vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "Archived Recipients (${state.archivedRecipients.size})",
                            style = MaterialTheme.typography.titleSmall,
                            color = c.textSecondary,
                        )
                        Icon(
                            if (showArchived) Lucide.ChevronUp else Lucide.ChevronDown,
                            contentDescription = null,
                            tint = c.textSecondary,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }

                if (showArchived) {
                    items(filteredArchived, key = { it.recipient.id }) { item ->
                        RecipientCard(
                            item = item,
                            onClick = { onSelectRecipient(item.recipient.id) },
                            onEdit = {
                                editingRecipient = item.recipient
                                showEditorDialog = true
                            },
                            onUnarchive = { viewModel.setArchived(item.recipient.id, false) },
                            onDelete = { deleteCandidate = item.recipient },
                        )
                    }
                }
            }
        }
    }

    // Add / Edit Recipient Dialog
    if (showEditorDialog) {
        RecipientEditDialog(
            recipient = editingRecipient,
            onDismiss = { showEditorDialog = false },
            onSave = { updated ->
                viewModel.saveRecipient(updated)
                showEditorDialog = false
            },
        )
    }

    // Delete confirmation dialog
    if (deleteCandidate != null) {
        AlertDialog(
            onDismissRequest = { deleteCandidate = null },
            title = { Text("Delete Recipient?") },
            text = {
                Text(
                    "Are you sure you want to delete ${deleteCandidate?.name}? Note: Archiving is recommended instead so past transfers remain intact.",
                    color = c.textSecondary,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        deleteCandidate?.let { viewModel.deleteRecipient(it) }
                        deleteCandidate = null
                    },
                ) {
                    Text("Delete", color = c.negative, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteCandidate = null }) {
                    Text("Cancel", color = c.textSecondary)
                }
            },
        )
    }
}

@Composable
private fun RecipientCard(
    item: RecipientWithStats,
    onClick: () -> Unit,
    onEdit: () -> Unit,
    onArchive: (() -> Unit)? = null,
    onUnarchive: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
) {
    val c = LocalAppColors.current
    val r = item.recipient
    val eurMoney = remember { Money("EUR") }
    val nprMoney = remember { Money("NPR") }

    Glass(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(20.dp),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconTile(Lucide.User, c.accent, size = 44.dp)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = r.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = c.textPrimary,
                    )
                    val subParts = listOfNotNull(
                        r.relationship?.takeIf { it.isNotBlank() },
                        r.country,
                        r.bankOrWalletLabel?.takeIf { it.isNotBlank() },
                    )
                    Text(
                        text = subParts.joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = c.textSecondary,
                    )
                }
                IconButton(onClick = onEdit) {
                    Icon(Lucide.Pencil, contentDescription = "Edit", tint = c.textSecondary, modifier = Modifier.size(18.dp))
                }
            }

            Spacer(Modifier.height(12.dp))
            RowDivider()
            Spacer(Modifier.height(10.dp))

            // Stats summary row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column {
                    Text("Transfers", style = MaterialTheme.typography.labelSmall, color = c.textSecondary)
                    Text("${item.transferCount}", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = c.textPrimary)
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Total Sent", style = MaterialTheme.typography.labelSmall, color = c.textSecondary)
                    Text(eurMoney.format(item.totalSentEurMinor), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = c.textPrimary)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text("NPR Received", style = MaterialTheme.typography.labelSmall, color = c.textSecondary)
                    Text(nprMoney.format(item.totalReceivedNprMinor), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = c.positive)
                }
            }

            // Bottom actions (Archive / Unarchive / Delete)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (onArchive != null) {
                    TextButton(onClick = onArchive) {
                        Icon(Lucide.Package, contentDescription = null, tint = c.textSecondary, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Archive", style = MaterialTheme.typography.labelMedium, color = c.textSecondary)
                    }
                }
                if (onUnarchive != null) {
                    TextButton(onClick = onUnarchive) {
                        Icon(Lucide.RotateCcw, contentDescription = null, tint = c.accent, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Restore", style = MaterialTheme.typography.labelMedium, color = c.accent)
                    }
                }
                if (onDelete != null) {
                    TextButton(onClick = onDelete) {
                        Icon(Lucide.Trash2, contentDescription = null, tint = c.negative, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Delete", style = MaterialTheme.typography.labelMedium, color = c.negative)
                    }
                }
            }
        }
    }
}

@Composable
private fun RecipientEditDialog(
    recipient: Recipient?,
    onDismiss: () -> Unit,
    onSave: (Recipient) -> Unit,
) {
    val c = LocalAppColors.current

    var name by remember { mutableStateOf(recipient?.name ?: "") }
    var relationship by remember { mutableStateOf(recipient?.relationship ?: "") }
    var country by remember { mutableStateOf(recipient?.country ?: "Nepal") }
    var currency by remember { mutableStateOf(recipient?.preferredCurrency ?: "NPR") }
    var provider by remember { mutableStateOf(recipient?.preferredProvider ?: "") }
    var bankOrWallet by remember { mutableStateOf(recipient?.bankOrWalletLabel ?: "") }
    var notes by remember { mutableStateOf(recipient?.notes ?: "") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (recipient == null) "Add Recipient" else "Edit Recipient") },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                item {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("Display Name *") },
                        placeholder = { Text("e.g. Mother, Anil Personal Nepal") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                }
                item {
                    OutlinedTextField(
                        value = relationship,
                        onValueChange = { relationship = it },
                        label = { Text("Relationship (Optional)") },
                        placeholder = { Text("e.g. Mother, Self, Sister") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                }
                item {
                    OutlinedTextField(
                        value = country,
                        onValueChange = { country = it },
                        label = { Text("Country") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                }
                item {
                    OutlinedTextField(
                        value = currency,
                        onValueChange = { currency = it.uppercase() },
                        label = { Text("Preferred Currency") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                }
                item {
                    OutlinedTextField(
                        value = provider,
                        onValueChange = { provider = it },
                        label = { Text("Preferred Provider (Optional)") },
                        placeholder = { Text("e.g. Revolut, Wise") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                }
                item {
                    OutlinedTextField(
                        value = bankOrWallet,
                        onValueChange = { bankOrWallet = it },
                        label = { Text("Bank / Wallet / Account Ending (Optional)") },
                        placeholder = { Text("e.g. Nabil Bank ····1234, eSewa") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                }
                item {
                    OutlinedTextField(
                        value = notes,
                        onValueChange = { notes = it },
                        label = { Text("Notes (Optional)") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (name.isNotBlank()) {
                        val item = recipient?.copy(
                            name = name.trim(),
                            relationship = relationship.trim().ifEmpty { null },
                            country = country.trim(),
                            preferredCurrency = currency.trim(),
                            preferredProvider = provider.trim().ifEmpty { null },
                            bankOrWalletLabel = bankOrWallet.trim().ifEmpty { null },
                            notes = notes.trim(),
                            updatedAt = System.currentTimeMillis(),
                        ) ?: Recipient(
                            name = name.trim(),
                            relationship = relationship.trim().ifEmpty { null },
                            country = country.trim(),
                            preferredCurrency = currency.trim(),
                            preferredProvider = provider.trim().ifEmpty { null },
                            bankOrWalletLabel = bankOrWallet.trim().ifEmpty { null },
                            notes = notes.trim(),
                        )
                        onSave(item)
                    }
                },
                enabled = name.isNotBlank(),
            ) {
                Text("Save", color = c.accent, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = c.textSecondary)
            }
        },
    )
}
