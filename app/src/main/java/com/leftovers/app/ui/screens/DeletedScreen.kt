package com.leftovers.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.leftovers.app.data.DeletedEntry
import com.leftovers.app.data.DeletedFilter
import com.leftovers.app.data.DeletedItem
import com.leftovers.app.data.DeletedLeaveEntry
import com.leftovers.app.data.DeletedShift
import com.leftovers.app.data.RosterRepository
import com.leftovers.app.data.SettingsRepository
import com.leftovers.app.data.TransactionRepository
import com.leftovers.app.data.TxType
import com.leftovers.app.ui.AppViewModelProvider
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import com.leftovers.app.ui.components.CategoryIcon
import com.leftovers.app.ui.components.Chip
import com.leftovers.app.ui.components.EmptyState
import com.leftovers.app.ui.components.Glass
import com.leftovers.app.ui.components.GlassScreen
import com.leftovers.app.ui.components.IconTile
import com.leftovers.app.ui.components.RoundButton
import com.leftovers.app.ui.components.RowDivider
import com.leftovers.app.ui.components.SecondaryButton
import com.leftovers.app.ui.components.SegmentedToggle
import com.leftovers.app.ui.components.frosted
import com.leftovers.app.ui.icons.Lucide
import com.leftovers.app.ui.theme.LocalAppColors
import com.leftovers.app.util.LocalMoney
import com.leftovers.app.util.friendlyLabel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

data class DeletedUiState(
    val loaded: Boolean = false,
    val items: List<DeletedEntry> = emptyList(),
    val total: Int = 0,
    val filter: DeletedFilter = DeletedFilter.All,
    val keepDays: Int = 90,
)

class DeletedViewModel(
    private val repository: TransactionRepository,
    private val roster: RosterRepository,
    private val settings: SettingsRepository,
) : ViewModel() {
    private val filter = MutableStateFlow<DeletedFilter>(DeletedFilter.All)

    val state: StateFlow<DeletedUiState> = combine(
        repository.deleted,
        roster.deletedShifts,
        roster.deletedLeaves,
        filter,
        settings.settings,
    ) { txs, shifts, leaves, f, s ->
        val today = LocalDate.now()
        val all: List<DeletedEntry> = (
            txs.map { DeletedEntry.TransactionRecord(it) } +
            shifts.map { DeletedEntry.ShiftRecord(it) } +
            leaves.map { DeletedEntry.LeaveRecord(it) }
        ).sortedByDescending { it.deletedAt }

        DeletedUiState(
            loaded = true,
            items = all.filter { f.matches(it.deletedOn(), today) },
            total = all.size,
            filter = f,
            keepDays = s.deletedKeepDays,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DeletedUiState())

    fun setFilter(value: DeletedFilter) { filter.value = value }

    fun restore(entries: List<DeletedEntry>) = viewModelScope.launch {
        val txIds = entries.filterIsInstance<DeletedEntry.TransactionRecord>().map { it.id }
        val shiftIds = entries.filterIsInstance<DeletedEntry.ShiftRecord>().map { it.id }
        val leaveIds = entries.filterIsInstance<DeletedEntry.LeaveRecord>().map { it.id }

        if (txIds.isNotEmpty()) repository.restoreDeleted(txIds)
        if (shiftIds.isNotEmpty()) roster.restoreDeletedShifts(shiftIds)
        if (leaveIds.isNotEmpty()) roster.restoreDeletedLeaves(leaveIds)
    }

    fun deleteForever(entries: List<DeletedEntry>) = viewModelScope.launch {
        val txIds = entries.filterIsInstance<DeletedEntry.TransactionRecord>().map { it.id }
        val shiftIds = entries.filterIsInstance<DeletedEntry.ShiftRecord>().map { it.id }
        val leaveIds = entries.filterIsInstance<DeletedEntry.LeaveRecord>().map { it.id }

        if (txIds.isNotEmpty()) repository.deleteForever(txIds)
        if (shiftIds.isNotEmpty()) roster.deleteShiftsForever(shiftIds)
        if (leaveIds.isNotEmpty()) roster.deleteLeavesForever(leaveIds)
    }

    fun setKeepDays(days: Int) = viewModelScope.launch {
        settings.setDeletedKeepDays(days)
        repository.purgeDeleted(days)
        roster.purgeDeletedRoster(days)
    }
}

private val keepOptions = listOf(30, 90, 365, 0)
private fun keepLabel(days: Int) = when (days) {
    0 -> "Forever"
    365 -> "1 year"
    else -> "$days days"
}

private val shortDate = DateTimeFormatter.ofPattern("d MMM")

/** Everything the user deleted, to restore or remove for good, filtered by when it was deleted. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun DeletedScreen(onBack: () -> Unit, viewModel: DeletedViewModel = viewModel(factory = AppViewModelProvider.Factory)) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val c = LocalAppColors.current
    val money = LocalMoney.current
    var pickDates by remember { mutableStateOf(false) }
    var confirmDeleteAll by remember { mutableStateOf(false) }
    var confirmDeleteOne by remember { mutableStateOf<DeletedEntry?>(null) }
    val shownEntries = state.items

    GlassScreen(
        title = "Deleted entries",
        subtitle = "Restore transactions, shifts & leaves you deleted",
        onBack = onBack,
        bottomBar = {
            if (state.items.isNotEmpty()) {
                Row(
                    Modifier.frosted().navigationBarsPadding().padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    SecondaryButton("Restore ${if (state.filter == DeletedFilter.All) "all" else "these"}", { viewModel.restore(shownEntries) }, Modifier.weight(1f), icon = Lucide.RotateCcw)
                    SecondaryButton("Delete ${if (state.filter == DeletedFilter.All) "all" else "these"}", { confirmDeleteAll = true }, Modifier.weight(1f), icon = Lucide.Trash2)
                }
            }
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding() + 12.dp, bottom = padding.calculateBottomPadding() + 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item(key = "filters") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Chip("All", { viewModel.setFilter(DeletedFilter.All) }, selected = state.filter == DeletedFilter.All)
                    Chip("Today", { viewModel.setFilter(DeletedFilter.Today) }, selected = state.filter == DeletedFilter.Today)
                    Chip("Last 7 days", { viewModel.setFilter(DeletedFilter.Week) }, selected = state.filter == DeletedFilter.Week)
                    Chip("Last 30 days", { viewModel.setFilter(DeletedFilter.Month) }, selected = state.filter == DeletedFilter.Month)
                    val range = state.filter as? DeletedFilter.Range
                    Chip(
                        range?.let { "${it.from.format(shortDate)} – ${it.to.format(shortDate)}" } ?: "Pick dates",
                        { pickDates = true },
                        icon = Lucide.Calendar,
                        selected = range != null,
                    )
                }
            }

            if (state.loaded && state.items.isEmpty()) {
                item(key = "empty") {
                    Glass(Modifier.fillMaxWidth()) {
                        if (state.total == 0) {
                            EmptyState(Lucide.Trash2, "Nothing deleted", "Entries, work shifts and leave you delete wait here, so you can bring them back.")
                        } else {
                            EmptyState(Lucide.Calendar, "Nothing deleted then", "Try another date range.")
                        }
                    }
                }
            } else if (state.items.isNotEmpty()) {
                item(key = "summary") {
                    val txItems = state.items.filterIsInstance<DeletedEntry.TransactionRecord>()
                    val shiftItems = state.items.filterIsInstance<DeletedEntry.ShiftRecord>()
                    val leaveItems = state.items.filterIsInstance<DeletedEntry.LeaveRecord>()
                    val spent = txItems.filter { it.item.entry.type == TxType.EXPENSE }.sumOf { it.item.entry.amountMinor }

                    val countParts = mutableListOf<String>()
                    if (txItems.isNotEmpty()) countParts.add("${txItems.size} transaction${if (txItems.size == 1) "" else "s"}")
                    if (shiftItems.isNotEmpty()) countParts.add("${shiftItems.size} shift${if (shiftItems.size == 1) "" else "s"}")
                    if (leaveItems.isNotEmpty()) countParts.add("${leaveItems.size} leave record${if (leaveItems.size == 1) "" else "s"}")

                    Text(
                        countParts.joinToString(" · ") + if (spent > 0) " · ${money.format(spent)} spent" else "",
                        style = MaterialTheme.typography.labelLarge,
                        color = c.textSecondary,
                        modifier = Modifier.padding(start = 6.dp, top = 4.dp),
                    )
                }
                state.items.groupBy { it.deletedOn() }.forEach { (day, items) ->
                    item(key = "day-$day") {
                        Text(
                            "Deleted ${day.friendlyLabel().replaceFirstChar { it.lowercase() }}",
                            style = MaterialTheme.typography.labelLarge,
                            color = c.textSecondary,
                            modifier = Modifier.animateItem().padding(start = 6.dp, top = 12.dp),
                        )
                    }
                    item(key = "group-$day") {
                        Glass(Modifier.fillMaxWidth().animateItem()) {
                            Column(Modifier.padding(vertical = 4.dp)) {
                                items.forEachIndexed { i, item ->
                                    if (i > 0) RowDivider()
                                    when (item) {
                                        is DeletedEntry.TransactionRecord -> {
                                            DeletedTransactionRow(
                                                item = item.item,
                                                onRestore = { viewModel.restore(listOf(item)) },
                                                onDelete = { confirmDeleteOne = item },
                                            )
                                        }
                                        is DeletedEntry.ShiftRecord -> {
                                            DeletedShiftRow(
                                                shift = item.shift,
                                                onRestore = { viewModel.restore(listOf(item)) },
                                                onDelete = { confirmDeleteOne = item },
                                            )
                                        }
                                        is DeletedEntry.LeaveRecord -> {
                                            DeletedLeaveRow(
                                                leave = item.leave,
                                                onRestore = { viewModel.restore(listOf(item)) },
                                                onDelete = { confirmDeleteOne = item },
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            item(key = "keep") {
                Column(Modifier.padding(top = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Keep deleted entries for", style = MaterialTheme.typography.labelLarge, color = c.textSecondary, modifier = Modifier.padding(start = 6.dp))
                    SegmentedToggle(keepOptions, state.keepDays, ::keepLabel, { viewModel.setKeepDays(it) }, Modifier.fillMaxWidth())
                    Text(
                        if (state.keepDays == 0) "They stay until you delete them here." else "After that they're removed for good, with any receipt photos.",
                        style = MaterialTheme.typography.bodySmall,
                        color = c.textTertiary,
                        modifier = Modifier.padding(start = 6.dp),
                    )
                }
            }
        }
    }

    if (pickDates) {
        val picker = rememberDateRangePickerState()
        GlassDatePickerDialog(
            onDismissRequest = { pickDates = false },
            confirmButton = {
                TextButton(
                    enabled = picker.selectedStartDateMillis != null,
                    onClick = {
                        val from = picker.selectedStartDateMillis!!.toUtcDate()
                        val to = picker.selectedEndDateMillis?.toUtcDate() ?: from
                        viewModel.setFilter(DeletedFilter.Range(from, to))
                        pickDates = false
                    },
                ) { Text("Show", color = c.textPrimary) }
            },
            dismissButton = { TextButton(onClick = { pickDates = false }) { Text("Cancel", color = c.textSecondary) } },
        ) {
            DateRangePicker(picker, title = { Text("Deleted between", modifier = Modifier.padding(start = 24.dp, top = 16.dp)) }, modifier = Modifier.height(480.dp))
        }
    }

    if (confirmDeleteAll) {
        DeleteForeverDialog(
            title = "Delete ${state.items.size} ${if (state.items.size == 1) "entry" else "entries"} for good?",
            onConfirm = {
                viewModel.deleteForever(shownEntries)
                confirmDeleteAll = false
            },
            onDismiss = { confirmDeleteAll = false },
        )
    }
    confirmDeleteOne?.let { item ->
        val title = when (item) {
            is DeletedEntry.TransactionRecord -> "Delete entry for good?"
            is DeletedEntry.ShiftRecord -> "Delete shift '${item.shift.title}' for good?"
            is DeletedEntry.LeaveRecord -> "Delete ${item.leave.type.label} record for good?"
        }
        DeleteForeverDialog(
            title = title,
            onConfirm = {
                viewModel.deleteForever(listOf(item))
                confirmDeleteOne = null
            },
            onDismiss = { confirmDeleteOne = null },
        )
    }
}

@Composable
private fun DeletedTransactionRow(item: DeletedItem, onRestore: () -> Unit, onDelete: () -> Unit) {
    val c = LocalAppColors.current
    val money = LocalMoney.current
    val e = item.entry
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 10.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        CategoryIcon(item.categoryEmoji ?: "package", item.categoryColor ?: 0xFF8A8A99, size = 40.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                e.note.ifBlank { item.categoryName ?: "Entry" },
                style = MaterialTheme.typography.titleSmall,
                color = c.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                listOfNotNull(item.date.friendlyLabel(), item.categoryName?.takeIf { e.note.isNotBlank() }).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = c.textSecondary,
                maxLines = 1,
            )
        }
        Text(
            (if (e.type == TxType.INCOME) "+" else "−") + money.format(e.amountMinor),
            style = MaterialTheme.typography.titleSmall,
            color = if (e.type == TxType.INCOME) c.positive else c.textPrimary,
        )
        Spacer(Modifier.width(8.dp))
        RoundButton(Lucide.RotateCcw, "Restore", onRestore, size = 36.dp, tint = c.accent)
        Spacer(Modifier.width(6.dp))
        RoundButton(Lucide.Trash2, "Delete for good", onDelete, size = 36.dp, tint = c.negative)
    }
}

@Composable
private fun DeletedShiftRow(shift: DeletedShift, onRestore: () -> Unit, onDelete: () -> Unit) {
    val c = LocalAppColors.current
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 10.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        IconTile(Lucide.CalendarClock, c.accent, size = 40.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                shift.title,
                style = MaterialTheme.typography.titleSmall,
                color = c.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                listOfNotNull(
                    shift.startDate.friendlyLabel(),
                    shift.workplace,
                    shift.formattedTimeRange,
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = c.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        DeletedStatusBadge("Shift", c.glass, c.textSecondary)
        Spacer(Modifier.width(8.dp))
        RoundButton(Lucide.RotateCcw, "Restore", onRestore, size = 36.dp, tint = c.accent)
        Spacer(Modifier.width(6.dp))
        RoundButton(Lucide.Trash2, "Delete for good", onDelete, size = 36.dp, tint = c.negative)
    }
}

@Composable
private fun DeletedLeaveRow(leave: DeletedLeaveEntry, onRestore: () -> Unit, onDelete: () -> Unit) {
    val c = LocalAppColors.current
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 10.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        IconTile(Lucide.Calendar, c.warning, size = 40.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                "${leave.type.emoji} ${leave.type.label}",
                style = MaterialTheme.typography.titleSmall,
                color = c.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                listOfNotNull(
                    if (leave.dayCount == 1) leave.startDate.friendlyLabel() else "${leave.startDate.format(shortDate)} – ${leave.endDate.format(shortDate)} (${leave.dayCount} d)",
                    leave.reason.ifBlank { null },
                    leave.paymentStatus.label,
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = c.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        DeletedStatusBadge(leave.type.label, c.glass, c.textSecondary)
        Spacer(Modifier.width(8.dp))
        RoundButton(Lucide.RotateCcw, "Restore", onRestore, size = 36.dp, tint = c.accent)
        Spacer(Modifier.width(6.dp))
        RoundButton(Lucide.Trash2, "Delete for good", onDelete, size = 36.dp, tint = c.negative)
    }
}

@Composable
private fun DeletedStatusBadge(text: String, color: androidx.compose.ui.graphics.Color, textColor: androidx.compose.ui.graphics.Color) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = textColor,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier
            .clip(CircleShape)
            .background(color)
            .padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

@Composable
private fun DeleteForeverDialog(title: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val c = LocalAppColors.current
    GlassAlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(30.dp),
        title = { Text(title) },
        text = { Text("They can't be restored after this.", color = c.textSecondary) },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Delete", color = c.negative) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = c.textSecondary) } },
    )
}

/** Date pickers report midnight UTC for the chosen day. */
private fun Long.toUtcDate(): LocalDate = Instant.ofEpochMilli(this).atZone(ZoneOffset.UTC).toLocalDate()
