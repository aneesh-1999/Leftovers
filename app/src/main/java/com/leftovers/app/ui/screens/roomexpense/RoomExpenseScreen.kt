package com.leftovers.app.ui.screens.roomexpense

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.ui.text.input.KeyboardType
import com.leftovers.app.data.roomexpense.RoomExpense
import com.leftovers.app.data.roomexpense.RoomExpenseSummary
import com.leftovers.app.data.roomexpense.Roommate
import com.leftovers.app.ui.AppViewModelProvider
import com.leftovers.app.ui.components.Chip
import com.leftovers.app.ui.components.DockClearance
import com.leftovers.app.ui.components.EmptyState
import com.leftovers.app.ui.components.Glass
import com.leftovers.app.ui.components.GlassScreen
import com.leftovers.app.ui.components.PrimaryButton
import com.leftovers.app.ui.components.RoundButton
import com.leftovers.app.ui.components.SecondaryButton
import com.leftovers.app.ui.icons.Lucide
import com.leftovers.app.ui.screens.GlassTextField
import com.leftovers.app.ui.theme.LocalAppColors
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val MONTH_YEAR_FORMATTER = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.getDefault())
private val EXPENSE_DATE_FORMATTER = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.getDefault())

@Composable
fun RoomExpenseScreen(
    onBack: () -> Unit,
    vm: RoomExpenseViewModel = viewModel(factory = AppViewModelProvider.Factory),
) {
    val c = LocalAppColors.current
    val state by vm.uiState.collectAsStateWithLifecycle()
    var showSettingsDialog by remember { mutableStateOf(false) }
    var showAddDialog by remember { mutableStateOf(false) }

    val infiniteTransition = rememberInfiniteTransition(label = "syncSpin")
    val spinAngle by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = LinearEasing),
        ),
        label = "spin",
    )

    GlassScreen(
        title = "Room Expenses",
        subtitle = "Shared Household Ledger (Firestore)",
        onBack = onBack,
        actions = {
            RoundButton(
                icon = Lucide.Plus,
                contentDescription = "Add Expense",
                onClick = { showAddDialog = true },
            )
            Spacer(Modifier.width(8.dp))
            RoundButton(
                icon = Lucide.RotateCcw,
                contentDescription = "Sync",
                onClick = { vm.syncNow() },
                modifier = if (state.isSyncing) Modifier.rotate(spinAngle) else Modifier,
            )
            Spacer(Modifier.width(8.dp))
            RoundButton(
                icon = Lucide.Settings,
                contentDescription = "Settings",
                onClick = { showSettingsDialog = true },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // 1. Sync & Offline Status Banner
            item {
                SyncStatusBanner(
                    isSyncing = state.isSyncing,
                    syncError = state.syncError,
                    lastSyncTime = state.lastSyncTime,
                    isStale = state.isStale,
                    onRetry = { vm.syncNow() },
                    onDismissError = { vm.clearError() },
                )
            }

            // 2. Net Settlement Hero Card
            item {
                state.summary?.let { summary ->
                    NetSettlementHeroCard(
                        summary = summary,
                        activeRoommate = state.activeRoommate,
                        onSwitchRoommate = { vm.setRoommate(it) },
                    )
                }
            }

            // 3. Month Selector & Monthly Stats Strip
            item {
                MonthSelectorStrip(
                    selectedMonth = state.selectedMonth,
                    summary = state.summary,
                    onPrevious = { vm.previousMonth() },
                    onNext = { vm.nextMonth() },
                )
            }

            // 4. Coin Meter Quick Summary
            item {
                state.summary?.let { summary ->
                    CoinMeterCard(summary = summary)
                }
            }

            // 5. Search & Filter Bar
            item {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    // Search box
                    TextField(
                        value = state.searchQuery,
                        onValueChange = { vm.setSearchQuery(it) },
                        placeholder = {
                            Text(
                                "Search expenses, merchant, note, doc ID...",
                                style = MaterialTheme.typography.bodyMedium,
                                color = c.textSecondary,
                            )
                        },
                        leadingIcon = {
                            Icon(
                                Lucide.Search,
                                contentDescription = "Search",
                                modifier = Modifier.size(18.dp),
                                tint = c.textSecondary,
                            )
                        },
                        trailingIcon = {
                            if (state.searchQuery.isNotBlank()) {
                                Icon(
                                    Lucide.X,
                                    contentDescription = "Clear",
                                    modifier = Modifier
                                        .size(18.dp)
                                        .clickable { vm.setSearchQuery("") },
                                    tint = c.textSecondary,
                                )
                            }
                        },
                        singleLine = true,
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f),
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                            focusedTextColor = c.textPrimary,
                            unfocusedTextColor = c.textPrimary,
                        ),
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth(),
                    )

                    // Horizontal Filter Pills
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        RoomExpenseFilter.entries.forEach { filter ->
                            val isSelected = state.selectedFilter == filter
                            val count = when (filter) {
                                RoomExpenseFilter.ALL -> state.allExpenses.size
                                RoomExpenseFilter.EXPENSES -> state.allExpenses.count { !it.isCoin && !it.isSettlement && !it.isLendBorrow }
                                RoomExpenseFilter.COINS -> state.allExpenses.count { it.isCoin }
                                RoomExpenseFilter.SETTLEMENTS -> state.allExpenses.count { it.isSettlement }
                                RoomExpenseFilter.LEND_BORROW -> state.allExpenses.count { it.isLendBorrow }
                            }

                            FilterChip(
                                label = "${filter.label} ($count)",
                                isSelected = isSelected,
                                onClick = { vm.setFilter(filter) },
                            )
                        }
                    }
                }
            }

            // 6. Expense Items or Empty State
            if (state.filteredExpenses.isEmpty()) {
                item {
                    EmptyState(
                        icon = Lucide.ReceiptText,
                        title = "No Expenses Found",
                        message = if (state.searchQuery.isNotBlank()) {
                            "No records matching '${state.searchQuery}'."
                        } else {
                            "No entries for ${state.selectedMonth.format(MONTH_YEAR_FORMATTER)} under filter '${state.selectedFilter.label}'."
                        },
                    )
                }
            } else {
                items(state.filteredExpenses, key = { it.id }) { expense ->
                    ExpenseRecordCard(
                        expense = expense,
                        activeRoommate = state.activeRoommate,
                    )
                }
            }

            // Spacing for navigation dock
            item {
                Spacer(Modifier.height(DockClearance))
            }
        }
    }


    if (showSettingsDialog) {
        RoomExpenseSettingsDialog(
            activeRoommate = state.activeRoommate,
            includeInPersonal = state.includeInPersonal,
            onDismiss = { showSettingsDialog = false },
            onSelectRoommate = { vm.setRoommate(it) },
            onToggleIncludeInPersonal = { vm.setIncludeInPersonal(it) },
        )
    }

    if (showAddDialog) {
        AddRoomExpenseDialog(
            activeRoommate = state.activeRoommate,
            onDismiss = { showAddDialog = false },
            onAdd = { title, amount, paidBy, category, splitType, splitOzric, splitPawan, notes ->
                vm.addExpense(
                    title = title,
                    amount = amount,
                    paidBy = paidBy,
                    category = category,
                    splitType = splitType,
                    splitOzric = splitOzric,
                    splitPawan = splitPawan,
                    notes = notes,
                ) { success, _ ->
                    if (success) showAddDialog = false
                }
            },
        )
    }
}

@Composable
private fun SyncStatusBanner(
    isSyncing: Boolean,
    syncError: String?,
    lastSyncTime: Long,
    isStale: Boolean,
    onRetry: () -> Unit,
    onDismissError: () -> Unit,
) {
    val c = LocalAppColors.current

    if (isSyncing) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(c.accent.copy(alpha = 0.15f))
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                strokeWidth = 2.dp,
                color = c.accent,
            )
            Text(
                "Syncing from named Firestore database...",
                style = MaterialTheme.typography.bodySmall,
                color = c.textPrimary,
                fontWeight = FontWeight.Medium,
            )
        }
    } else if (syncError != null) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(Color(0xFFEF5350).copy(alpha = 0.15f))
                .border(1.dp, Color(0xFFEF5350).copy(alpha = 0.3f), RoundedCornerShape(12.dp))
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "Offline Mode (Using cached data)",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFFEF5350),
                )
                Text(
                    syncError,
                    style = MaterialTheme.typography.labelSmall,
                    color = c.textSecondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Retry",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = c.accent,
                    modifier = Modifier.clickable { onRetry() },
                )
                Text(
                    "Dismiss",
                    style = MaterialTheme.typography.labelMedium,
                    color = c.textSecondary,
                    modifier = Modifier.clickable { onDismissError() },
                )
            }
        }
    } else {
        // Subtle sync info
        val timeLabel = if (lastSyncTime > 0) {
            val instant = Instant.ofEpochMilli(lastSyncTime).atZone(ZoneId.systemDefault())
            DateTimeFormatter.ofPattern("d MMM, HH:mm").format(instant)
        } else "Never"

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(if (isStale) Color(0xFFFFB74D) else Color(0xFF66BB6A))
                )
                Text(
                    if (isStale) "Stale Cache • Last synced $timeLabel" else "Synced $timeLabel",
                    style = MaterialTheme.typography.labelSmall,
                    color = c.textSecondary,
                )
            }
            Text(
                "Read-Only",
                style = MaterialTheme.typography.labelSmall,
                color = c.textSecondary,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun NetSettlementHeroCard(
    summary: RoomExpenseSummary,
    activeRoommate: Roommate,
    onSwitchRoommate: (Roommate) -> Unit,
) {
    val c = LocalAppColors.current

    val (cardBg, cardBorder, statusColor) = when {
        summary.isEven -> Triple(Color(0xFFE8F5E9).copy(alpha = 0.2f), Color(0xFF66BB6A), Color(0xFF66BB6A))
        summary.isOwed -> Triple(Color(0xFFE8F5E9).copy(alpha = 0.2f), Color(0xFF66BB6A), Color(0xFF66BB6A))
        else -> Triple(Color(0xFFFFEBEE).copy(alpha = 0.2f), Color(0xFFEF5350), Color(0xFFEF5350))
    }

    Glass(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .border(2.dp, cardBorder.copy(alpha = 0.6f), RoundedCornerShape(24.dp))
                .background(cardBg)
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // Header Row: Tag + Roommate Switcher Pill
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "NET SETTLEMENT BALANCE",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 1.sp,
                    color = c.textSecondary,
                )

                // Profile pill switch
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                        .padding(2.dp),
                ) {
                    Roommate.entries.forEach { rm ->
                        val isSelected = rm == activeRoommate
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (isSelected) c.accent else Color.Transparent)
                                .clickable { onSwitchRoommate(rm) }
                                .padding(horizontal = 10.dp, vertical = 4.dp),
                        ) {
                            Text(
                                rm.displayName,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                color = if (isSelected) Color.White else c.textSecondary,
                            )
                        }
                    }
                }
            }

            // Big Amount
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    "€${String.format(Locale.US, "%.2f", summary.perspectiveBalance)}",
                    style = MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.Black,
                    color = c.textPrimary,
                )
            }

            // Status Explanation
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val icon = when {
                    summary.isEven -> Lucide.CircleCheck
                    summary.isOwed -> Lucide.ArrowUpRight
                    else -> Lucide.ArrowDownLeft
                }
                Icon(
                    icon,
                    contentDescription = null,
                    tint = statusColor,
                    modifier = Modifier.size(18.dp),
                )

                val statusText = when {
                    summary.isEven -> "You and ${summary.otherRoommate.displayName} are fully settled up!"
                    summary.isOwed -> "${summary.otherRoommate.displayName} owes you €${String.format(Locale.US, "%.2f", summary.perspectiveBalance)}"
                    else -> "You owe ${summary.otherRoommate.displayName} €${String.format(Locale.US, "%.2f", summary.perspectiveBalance)}"
                }

                Text(
                    statusText,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = c.textPrimary,
                )
            }

            HorizontalDivider(color = c.textSecondary.copy(alpha = 0.2f))

            // Sub-metrics: Pawan owes Ozric vs Ozric owes Pawan
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column {
                    Text("Pawan owes Ozric", style = MaterialTheme.typography.labelSmall, color = c.textSecondary)
                    Text("€${String.format(Locale.US, "%.2f", summary.totalPawanOwesOzric)}", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = c.textPrimary)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text("Ozric owes Pawan", style = MaterialTheme.typography.labelSmall, color = c.textSecondary)
                    Text("€${String.format(Locale.US, "%.2f", summary.totalOzricOwesPawan)}", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = c.textPrimary)
                }
            }
        }
    }
}

@Composable
private fun MonthSelectorStrip(
    selectedMonth: java.time.YearMonth,
    summary: RoomExpenseSummary?,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
) {
    val c = LocalAppColors.current

    Glass(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Month navigation bar
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RoundButton(Lucide.ChevronLeft, "Previous month", onPrevious, size = 34.dp)

                Text(
                    selectedMonth.format(MONTH_YEAR_FORMATTER),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = c.textPrimary,
                )

                RoundButton(Lucide.ChevronRight, "Next month", onNext, size = 34.dp)
            }

            // Summary metrics for this month
            if (summary != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    MetricBox(
                        label = "Total Spent",
                        value = "€${String.format(Locale.US, "%.2f", summary.totalMonthlySpend)}",
                        caption = "Household",
                    )
                    MetricBox(
                        label = "Your Share",
                        value = "€${String.format(Locale.US, "%.2f", summary.userShareThisMonth)}",
                        caption = "Allocated",
                    )
                    MetricBox(
                        label = "You Paid",
                        value = "€${String.format(Locale.US, "%.2f", summary.userPaidThisMonth)}",
                        caption = "Out of pocket",
                    )
                }
            }
        }
    }
}

@Composable
private fun MetricBox(label: String, value: String, caption: String) {
    val c = LocalAppColors.current
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = c.textSecondary)
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = c.textPrimary)
        Text(caption, style = MaterialTheme.typography.labelSmall, color = c.textSecondary.copy(alpha = 0.7f), fontSize = 10.sp)
    }
}

@Composable
private fun CoinMeterCard(summary: RoomExpenseSummary) {
    val c = LocalAppColors.current

    Glass(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xFFFFB74D).copy(alpha = 0.2f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Lucide.Zap,
                        contentDescription = "Electricity Coin Meter",
                        tint = Color(0xFFFFB74D),
                        modifier = Modifier.size(22.dp),
                    )
                }
                Column {
                    Text("Electricity Coin Meter", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, color = c.textPrimary)
                    Text("Total Coins Inserted: €${String.format(Locale.US, "%.2f", summary.totalCoins)}", style = MaterialTheme.typography.labelSmall, color = c.textSecondary)
                }
            }

            Column(horizontalAlignment = Alignment.End) {
                Text("Pawan: €${String.format(Locale.US, "%.2f", summary.pawanCoinTotal)}", style = MaterialTheme.typography.labelSmall, color = c.textSecondary)
                Text("Ozric: €${String.format(Locale.US, "%.2f", summary.ozricCoinTotal)}", style = MaterialTheme.typography.labelSmall, color = c.textSecondary)
            }
        }
    }
}

@Composable
private fun FilterChip(label: String, isSelected: Boolean, onClick: () -> Unit) {
    val c = LocalAppColors.current
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .background(if (isSelected) c.accent else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
            .border(
                1.dp,
                if (isSelected) c.accent else c.textSecondary.copy(alpha = 0.2f),
                RoundedCornerShape(14.dp),
            )
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 7.dp),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
            color = if (isSelected) Color.White else c.textPrimary,
        )
    }
}

@Composable
private fun ExpenseRecordCard(
    expense: RoomExpense,
    activeRoommate: Roommate,
) {
    val c = LocalAppColors.current
    var expandedNotes by remember { mutableStateOf(false) }

    Glass(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Top Row: Title + Amount
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        expense.title,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Bold,
                        color = c.textPrimary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        "${expense.date.format(EXPENSE_DATE_FORMATTER)} • ${expense.category}",
                        style = MaterialTheme.typography.labelSmall,
                        color = c.textSecondary,
                    )
                }

                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        "€${String.format(Locale.US, "%.2f", expense.amount)}",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Black,
                        color = c.textPrimary,
                    )
                    Text(
                        "Paid by ${expense.paidBy}",
                        style = MaterialTheme.typography.labelSmall,
                        color = c.accent,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }

            // Badges Row (Coin, Settlement, Lend/Borrow, Pending)
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (expense.isCoin) {
                    TypeBadge("⚡ Coin Meter", Color(0xFFFFB74D))
                }
                if (expense.isSettlement) {
                    TypeBadge("🤝 Settlement", Color(0xFF66BB6A))
                }
                if (expense.isLendBorrow) {
                    TypeBadge("🔄 Lend/Borrow", Color(0xFFAB47BC))
                }
                if (expense.isPending) {
                    TypeBadge("⏳ Pending Approval", Color(0xFFFFA726))
                }
                if (expense.paymentMethod != null) {
                    TypeBadge(expense.paymentMethod, c.textSecondary)
                }
            }

            // Split breakdown details
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f))
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Split (${expense.splitType}):",
                    style = MaterialTheme.typography.labelSmall,
                    color = c.textSecondary,
                )
                Text(
                    "Pawan: €${String.format(Locale.US, "%.2f", expense.splitPawan)} • Ozric: €${String.format(Locale.US, "%.2f", expense.splitOzric)}",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = c.textPrimary,
                )
            }

            // Notes / OCR section if present
            if (expense.notes.isNotBlank()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { expandedNotes = !expandedNotes },
                ) {
                    Text(
                        expense.notes,
                        style = MaterialTheme.typography.labelSmall,
                        color = c.textSecondary,
                        maxLines = if (expandedNotes) Int.MAX_VALUE else 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            // Monospace Firestore Doc ID footer (Preserves source record ID)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    "Doc: ${expense.id}",
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace, fontSize = 9.sp),
                    color = c.textSecondary.copy(alpha = 0.6f),
                )
                if (expense.createdBy != null) {
                    Text(
                        "Created by ${expense.createdBy}",
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                        color = c.textSecondary.copy(alpha = 0.6f),
                    )
                }
            }
        }
    }
}

@Composable
private fun TypeBadge(label: String, color: Color) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(color.copy(alpha = 0.15f))
            .border(1.dp, color.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = color,
            fontSize = 10.sp,
        )
    }
}

@Composable
private fun RoomExpenseSettingsDialog(
    activeRoommate: Roommate,
    includeInPersonal: Boolean,
    onDismiss: () -> Unit,
    onSelectRoommate: (Roommate) -> Unit,
    onToggleIncludeInPersonal: (Boolean) -> Unit,
) {
    val c = LocalAppColors.current

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text("Room Expense Settings", fontWeight = FontWeight.Bold)
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                // Roommate identity
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        "Active Profile (Your Identity)",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = c.textPrimary,
                    )
                    Text(
                        "Select which roommate you are on this device to calculate your personal balance perspective.",
                        style = MaterialTheme.typography.bodySmall,
                        color = c.textSecondary,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        Roommate.entries.forEach { rm ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.clickable { onSelectRoommate(rm) },
                            ) {
                                RadioButton(
                                    selected = activeRoommate == rm,
                                    onClick = { onSelectRoommate(rm) },
                                )
                                Text(rm.displayName, style = MaterialTheme.typography.bodyMedium, color = c.textPrimary)
                            }
                        }
                    }
                }

                HorizontalDivider()

                // Personal Finance Inclusion Toggle
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "Include My Share in Personal Finance",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = c.textPrimary,
                        )
                        Text(
                            "Automatically import only YOUR allocated split amount into personal expenses with stable references to prevent duplicates. Full household total is never imported.",
                            style = MaterialTheme.typography.bodySmall,
                            color = c.textSecondary,
                        )
                    }
                    Switch(
                        checked = includeInPersonal,
                        onCheckedChange = onToggleIncludeInPersonal,
                    )
                }

                HorizontalDivider()

                // Database Connection Info
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Connection Details", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = c.textSecondary)
                    Text("Status: ${if (com.leftovers.app.data.roomexpense.RoomExpenseRepository.isConfigured()) "Configured" else "Not configured"}", style = MaterialTheme.typography.labelSmall, color = c.textSecondary)
                    Text("Access: Read-Only Client SDK", style = MaterialTheme.typography.labelSmall, color = c.accent)
                }
            }
        },
        confirmButton = {
            PrimaryButton("Done", onClick = onDismiss)
        },
    )
}

@Composable
private fun AddRoomExpenseDialog(
    activeRoommate: Roommate,
    onDismiss: () -> Unit,
    onAdd: (title: String, amount: Double, paidBy: String, category: String, splitType: String, splitOzric: Double, splitPawan: Double, notes: String) -> Unit,
) {
    val c = LocalAppColors.current
    var title by remember { mutableStateOf("") }
    var amountText by remember { mutableStateOf("") }
    var paidBy by remember { mutableStateOf(activeRoommate.displayName) }
    var selectedCategory by remember { mutableStateOf("Groceries") }
    var splitEqual by remember { mutableStateOf(true) }
    var ozricShareText by remember { mutableStateOf("") }
    var pawanShareText by remember { mutableStateOf("") }
    var notes by remember { mutableStateOf("") }

    val totalAmount = amountText.toDoubleOrNull() ?: 0.0
    val categories = listOf("Groceries", "Household", "Supplies", "Dining", "Bills", "Other")

    val splitOzric = if (splitEqual) totalAmount / 2.0 else (ozricShareText.toDoubleOrNull() ?: 0.0)
    val splitPawan = if (splitEqual) totalAmount / 2.0 else (pawanShareText.toDoubleOrNull() ?: 0.0)

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        title = {
            Text(
                "Add Shared Room Expense",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = c.textPrimary,
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                GlassTextField(
                    value = title,
                    onValueChange = { title = it },
                    placeholder = "e.g. Albert Heijn groceries",
                    label = "Expense Title",
                )

                GlassTextField(
                    value = amountText,
                    onValueChange = { amountText = it },
                    placeholder = "0.00",
                    label = "Total Amount (€)",
                    keyboardType = KeyboardType.Decimal,
                )

                // Paid By selector
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Paid By", style = MaterialTheme.typography.labelSmall, color = c.textSecondary)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Roommate.entries.forEach { rm ->
                            Chip(
                                text = if (rm == activeRoommate) "${rm.displayName} (You)" else rm.displayName,
                                onClick = { paidBy = rm.displayName },
                                selected = paidBy == rm.displayName,
                            )
                        }
                    }
                }

                // Category Chips
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Category", style = MaterialTheme.typography.labelSmall, color = c.textSecondary)
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        categories.forEach { cat ->
                            Chip(
                                text = cat,
                                onClick = { selectedCategory = cat },
                                selected = selectedCategory == cat,
                            )
                        }
                    }
                }

                // Split method
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Split", style = MaterialTheme.typography.labelSmall, color = c.textSecondary)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Chip(
                            text = "50/50 Split",
                            onClick = { splitEqual = true },
                            selected = splitEqual,
                        )
                        Chip(
                            text = "Custom",
                            onClick = { splitEqual = false },
                            selected = !splitEqual,
                        )
                    }
                    if (splitEqual) {
                        Text(
                            "Each pays €${String.format(Locale.US, "%.2f", totalAmount / 2.0)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = c.accent,
                        )
                    } else {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Column(Modifier.weight(1f)) {
                                GlassTextField(
                                    value = ozricShareText,
                                    onValueChange = { ozricShareText = it },
                                    placeholder = "0.00",
                                    label = "Ozric's share",
                                    keyboardType = KeyboardType.Decimal,
                                )
                            }
                            Column(Modifier.weight(1f)) {
                                GlassTextField(
                                    value = pawanShareText,
                                    onValueChange = { pawanShareText = it },
                                    placeholder = "0.00",
                                    label = "Pawan's share",
                                    keyboardType = KeyboardType.Decimal,
                                )
                            }
                        }
                    }
                }

                GlassTextField(
                    value = notes,
                    onValueChange = { notes = it },
                    placeholder = "Optional details",
                    label = "Notes",
                )
            }
        },
        confirmButton = {
            PrimaryButton(
                text = "Save & Sync",
                enabled = title.isNotBlank() && totalAmount > 0.0,
                onClick = {
                    onAdd(
                        title,
                        totalAmount,
                        paidBy,
                        selectedCategory,
                        if (splitEqual) "50/50" else "custom",
                        splitOzric,
                        splitPawan,
                        notes,
                    )
                },
            )
        },
        dismissButton = {
            SecondaryButton("Cancel", onClick = onDismiss)
        },
    )
}
