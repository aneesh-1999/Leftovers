package com.leftovers.app.ui.screens.cohabit

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.leftovers.app.data.cohabit.CohabitExpense
import com.leftovers.app.data.cohabit.CohabitRoommate
import com.leftovers.app.data.cohabit.CohabitShift
import com.leftovers.app.data.cohabit.CohabitShoppingItem
import com.leftovers.app.ui.AppViewModelProvider
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun CohabitSyncScreen(
    onBack: () -> Unit,
    vm: CohabitSyncViewModel = viewModel(factory = AppViewModelProvider.Factory),
) {
    val c = LocalAppColors.current
    val state by vm.uiState.collectAsStateWithLifecycle()

    var showSettingsDialog by remember { mutableStateOf(false) }
    var showAddFoodDialog by remember { mutableStateOf(false) }
    var showLogExpenseDialog by remember { mutableStateOf(false) }

    val infiniteTransition = rememberInfiniteTransition(label = "cohabitSpin")
    val spinAngle by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = LinearEasing),
        ),
        label = "spin",
    )

    GlassScreen(
        title = "Cohabit Sync",
        subtitle = "Encrypted Leftovers, Shifts & Roommate Ledger",
        onBack = onBack,
        actions = {
            RoundButton(
                icon = Lucide.RotateCcw,
                contentDescription = "Sync Now",
                onClick = { vm.syncNow() },
                modifier = if (state.isSyncing) Modifier.rotate(spinAngle) else Modifier,
            )
            Spacer(Modifier.width(8.dp))
            RoundButton(
                icon = Lucide.Settings,
                contentDescription = "API & Auth Settings",
                onClick = { showSettingsDialog = true },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // 1. Connection & Encryption Scope Card
            item {
                ConnectionStatusCard(
                    roommate = state.activeRoommate,
                    scope = state.encryptionScope,
                    lastSync = state.lastSyncTime,
                    errorMessage = state.errorMessage,
                    isSyncing = state.isSyncing,
                    onSwitchRoommate = { vm.switchRoommate(it) },
                    onRetry = { vm.syncNow() },
                    onDismissError = { vm.clearError() },
                )
            }

            // 2. Leftover Food Containers & Grocery Inventory
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column {
                        Text(
                            "Leftovers & Groceries",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = c.textPrimary,
                        )
                        Text(
                            "${state.shoppingList.size} active items / containers",
                            style = MaterialTheme.typography.bodySmall,
                            color = c.textSecondary,
                        )
                    }
                    PrimaryButton(
                        text = "+ Add Item",
                        onClick = { showAddFoodDialog = true },
                    )
                }
            }

            if (state.shoppingList.isEmpty()) {
                item {
                    Glass(
                        shape = RoundedCornerShape(20.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Icon(
                                Lucide.Utensils,
                                contentDescription = null,
                                tint = c.textTertiary,
                                modifier = Modifier.size(36.dp),
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "No active leftover meals or grocery items",
                                style = MaterialTheme.typography.bodyMedium,
                                color = c.textSecondary,
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "Tap '+ Add Item' to log a leftover container or shopping item.",
                                style = MaterialTheme.typography.bodySmall,
                                color = c.textTertiary,
                            )
                        }
                    }
                }
            } else {
                items(state.shoppingList, key = { it.id }) { item ->
                    ShoppingItemRow(
                        item = item,
                        onMarkCompleted = { vm.markItemCompleted(item.id, true) },
                        onDelete = { vm.deleteShoppingItem(item.id) },
                    )
                }
            }

            // 3. Work Shifts & Meal Prep Schedule
            item {
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column {
                        Text(
                            "Work Shifts & Meal Prep",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = c.textPrimary,
                        )
                        Text(
                            "Synced from Roster API for ${state.activeRoommate}",
                            style = MaterialTheme.typography.bodySmall,
                            color = c.textSecondary,
                        )
                    }
                }
            }

            if (state.shifts.isEmpty()) {
                item {
                    Glass(
                        shape = RoundedCornerShape(20.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(20.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text(
                                "No upcoming shifts found",
                                style = MaterialTheme.typography.bodyMedium,
                                color = c.textSecondary,
                            )
                        }
                    }
                }
            } else {
                items(state.shifts.take(5), key = { it.id }) { shift ->
                    ShiftMealPrepRow(shift = shift)
                }
            }

            // 4. Shared Roommate Ledger & Food Expenses (50/50)
            item {
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column {
                        Text(
                            "Shared Roommate Ledger",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = c.textPrimary,
                        )
                        Text(
                            state.ledger?.debtSummary ?: "50/50 Split Ledger",
                            style = MaterialTheme.typography.bodySmall,
                            color = c.accent,
                        )
                    }
                    SecondaryButton(
                        text = "+ Log Grocery",
                        onClick = { showLogExpenseDialog = true },
                    )
                }
            }

            if (state.expenses.isNotEmpty()) {
                items(state.expenses.take(4), key = { it.id }) { expense ->
                    ExpenseRow(expense = expense)
                }
            }

            // 5. Private Personal Finance Vault Card
            item {
                Spacer(Modifier.height(8.dp))
                PersonalFinanceVaultCard(
                    owner = state.activeRoommate,
                    scope = state.encryptionScope,
                    personalFinance = state.personalFinance,
                )
            }

            item {
                Spacer(Modifier.height(DockClearance))
            }
        }
    }

    // Settings Modal
    if (showSettingsDialog) {
        CohabitSettingsDialog(
            currentBaseUrl = state.baseUrl,
            currentApiKey = state.apiKey,
            currentRoommate = state.activeRoommate,
            onDismiss = { showSettingsDialog = false },
            onSave = { newUrl, newKey ->
                vm.updateCredentials(newKey, newUrl)
                showSettingsDialog = false
            },
            onSwitchRoommate = { newRoommate ->
                vm.switchRoommate(newRoommate)
                showSettingsDialog = false
            },
        )
    }

    // Add Food / Grocery Modal
    if (showAddFoodDialog) {
        AddFoodItemDialog(
            onDismiss = { showAddFoodDialog = false },
            onAdd = { title, qty, cat, prio, notes, cost ->
                vm.addShoppingItem(title, qty, cat, prio, notes, cost)
                showAddFoodDialog = false
            },
        )
    }

    // Log 50/50 Grocery Expense Modal
    if (showLogExpenseDialog) {
        LogGroceryExpenseDialog(
            onDismiss = { showLogExpenseDialog = false },
            onLog = { title, amount, cat, notes ->
                vm.logExpense(title, amount, cat, notes)
                showLogExpenseDialog = false
            },
        )
    }
}

@Composable
private fun ConnectionStatusCard(
    roommate: String,
    scope: String,
    lastSync: Long,
    errorMessage: String?,
    isSyncing: Boolean,
    onSwitchRoommate: (String) -> Unit,
    onRetry: () -> Unit,
    onDismissError: () -> Unit,
) {
    val c = LocalAppColors.current
    Glass(
        shape = RoundedCornerShape(24.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Roommate Selection Pills
                Row(
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
                        .padding(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    val isOzric = roommate.equals("Ozric", ignoreCase = true)
                    Box(
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(if (isOzric) c.accent else Color.Transparent)
                            .clickable { onSwitchRoommate("Ozric") }
                            .padding(horizontal = 14.dp, vertical = 6.dp),
                    ) {
                        Text(
                            "👤 Ozric",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = if (isOzric) FontWeight.Bold else FontWeight.Normal,
                            color = if (isOzric) Color.White else c.textSecondary,
                        )
                    }

                    Box(
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(if (!isOzric) c.accent else Color.Transparent)
                            .clickable { onSwitchRoommate("Pawan") }
                            .padding(horizontal = 14.dp, vertical = 6.dp),
                    ) {
                        Text(
                            "👤 Pawan",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = if (!isOzric) FontWeight.Bold else FontWeight.Normal,
                            color = if (!isOzric) Color.White else c.textSecondary,
                        )
                    }
                }

                // Scope badge
                Box(
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(c.positive.copy(alpha = 0.15f))
                        .border(1.dp, c.positive.copy(alpha = 0.35f), CircleShape)
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                ) {
                    Text(
                        "🔒 AES-256-GCM",
                        style = MaterialTheme.typography.labelSmall,
                        color = c.positive,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text(
                        "Encryption Scope: $scope",
                        style = MaterialTheme.typography.bodySmall,
                        color = c.textSecondary,
                        fontSize = 11.sp,
                    )
                    Text(
                        if (lastSync > 0) "Last synced: ${formatTimestamp(lastSync)}" else "Not synced yet",
                        style = MaterialTheme.typography.labelSmall,
                        color = c.textTertiary,
                    )
                }
            }

            AnimatedVisibility(visible = errorMessage != null) {
                errorMessage?.let { msg ->
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(c.negative.copy(alpha = 0.12f))
                            .border(1.dp, c.negative.copy(alpha = 0.3f), RoundedCornerShape(12.dp))
                            .padding(12.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                msg,
                                style = MaterialTheme.typography.bodySmall,
                                color = c.negative,
                                modifier = Modifier.weight(1f),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "Retry",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = c.accent,
                                modifier = Modifier.clickable { onRetry() },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ShoppingItemRow(
    item: CohabitShoppingItem,
    onMarkCompleted: () -> Unit,
    onDelete: () -> Unit,
) {
    val c = LocalAppColors.current
    Glass(
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        item.title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = c.textPrimary,
                    )
                    Box(
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                            .padding(horizontal = 8.dp, vertical = 2.dp),
                    ) {
                        Text(
                            item.quantity,
                            style = MaterialTheme.typography.labelSmall,
                            color = c.textSecondary,
                        )
                    }
                    if (item.isUrgent) {
                        Box(
                            modifier = Modifier
                                .clip(CircleShape)
                                .background(c.negative.copy(alpha = 0.15f))
                                .padding(horizontal = 8.dp, vertical = 2.dp),
                        ) {
                            Text(
                                "Urgent",
                                style = MaterialTheme.typography.labelSmall,
                                color = c.negative,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                }

                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        item.category,
                        style = MaterialTheme.typography.bodySmall,
                        color = c.textTertiary,
                    )
                    if (item.notes.isNotBlank()) {
                        Text(
                            "• ${item.notes}",
                            style = MaterialTheme.typography.bodySmall,
                            color = c.textSecondary,
                            maxLines = 1,
                        )
                    }
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                // 1-Click Eaten / Completed button
                Box(
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(c.positive.copy(alpha = 0.15f))
                        .clickable { onMarkCompleted() }
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                ) {
                    Text(
                        "Eaten ✓",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = c.positive,
                    )
                }

                Spacer(Modifier.width(8.dp))
                Icon(
                    Lucide.Trash2,
                    contentDescription = "Delete",
                    tint = c.textTertiary,
                    modifier = Modifier
                        .size(18.dp)
                        .clickable { onDelete() },
                )
            }
        }
    }
}

@Composable
private fun ShiftMealPrepRow(shift: CohabitShift) {
    val c = LocalAppColors.current
    Glass(
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text(
                        "${shift.date} • ${shift.title}",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = c.textPrimary,
                    )
                    Text(
                        "${shift.startTime} – ${shift.endTime} (${shift.netHours} hrs) ${if (shift.employerName.isNotBlank()) "@ " + shift.employerName else ""}",
                        style = MaterialTheme.typography.bodySmall,
                        color = c.textSecondary,
                    )
                }

                if (shift.needsPackedMeal) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(c.accent.copy(alpha = 0.15f))
                            .border(1.dp, c.accent.copy(alpha = 0.3f), RoundedCornerShape(8.dp))
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                    ) {
                        Text(
                            "🍱 Pack Meal",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = c.accent,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ExpenseRow(expense: CohabitExpense) {
    val c = LocalAppColors.current
    Glass(
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text(
                    expense.title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = c.textPrimary,
                )
                Text(
                    "Paid by ${expense.paidBy} (50/50)",
                    style = MaterialTheme.typography.bodySmall,
                    color = c.textTertiary,
                )
            }
            Text(
                "€${String.format(Locale.US, "%.2f", expense.amount)}",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = c.textPrimary,
            )
        }
    }
}

@Composable
private fun PersonalFinanceVaultCard(
    owner: String,
    scope: String,
    personalFinance: com.leftovers.app.data.cohabit.CohabitPersonalFinance?,
) {
    val c = LocalAppColors.current
    Glass(
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Personal Finance Vault",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = c.textPrimary,
                )
                Text(
                    "🔒 Encrypted Scope",
                    style = MaterialTheme.typography.labelSmall,
                    color = c.textSecondary,
                )
            }

            Text(
                "Private data strictly isolated to $owner ($scope). Cross-user requests are blocked with HTTP 403 Forbidden.",
                style = MaterialTheme.typography.bodySmall,
                color = c.textTertiary,
            )

            if (personalFinance != null) {
                Spacer(Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column {
                        Text(
                            "Total EUR Balance",
                            style = MaterialTheme.typography.labelSmall,
                            color = c.textSecondary,
                        )
                        Text(
                            "€${String.format(Locale.US, "%.2f", personalFinance.totalBalanceEur)}",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = c.accent,
                        )
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            "${personalFinance.accountsCount} Accounts",
                            style = MaterialTheme.typography.bodySmall,
                            color = c.textPrimary,
                        )
                        Text(
                            "${personalFinance.transactionsCount} Transactions",
                            style = MaterialTheme.typography.bodySmall,
                            color = c.textTertiary,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CohabitSettingsDialog(
    currentBaseUrl: String,
    currentApiKey: String,
    currentRoommate: String,
    onDismiss: () -> Unit,
    onSave: (baseUrl: String, apiKey: String) -> Unit,
    onSwitchRoommate: (String) -> Unit,
) {
    val c = LocalAppColors.current
    var url by remember { mutableStateOf(currentBaseUrl) }
    var key by remember { mutableStateOf(currentApiKey) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text("Cohabit Bridge Credentials", style = MaterialTheme.typography.titleMedium, color = c.textPrimary)
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "Choose preset user key or paste a signed Bridge Token (lft_tok_...):",
                    style = MaterialTheme.typography.bodySmall,
                    color = c.textSecondary,
                )

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SecondaryButton(
                        text = "Ozric's Key",
                        onClick = {
                            key = CohabitRoommate.OZRIC.defaultKey
                            onSwitchRoommate("Ozric")
                        },
                    )
                    SecondaryButton(
                        text = "Pawan's Key",
                        onClick = {
                            key = CohabitRoommate.PAWAN.defaultKey
                            onSwitchRoommate("Pawan")
                        },
                    )
                }

                Spacer(Modifier.height(4.dp))
                GlassTextField(
                    value = key,
                    onValueChange = { key = it },
                    placeholder = "API Key or Bridge Token",
                )

                GlassTextField(
                    value = url,
                    onValueChange = { url = it },
                    placeholder = "Base URL",
                )
            }
        },
        confirmButton = {
            PrimaryButton(text = "Save & Connect", onClick = { onSave(url, key) })
        },
        dismissButton = {
            SecondaryButton(text = "Cancel", onClick = onDismiss)
        },
    )
}

@Composable
private fun AddFoodItemDialog(
    onDismiss: () -> Unit,
    onAdd: (title: String, qty: String, cat: String, prio: String, notes: String, cost: Double?) -> Unit,
) {
    val c = LocalAppColors.current
    var title by remember { mutableStateOf("") }
    var quantity by remember { mutableStateOf("1 container") }
    var category by remember { mutableStateOf("Produce & Groceries") }
    var priority by remember { mutableStateOf("Normal") }
    var notes by remember { mutableStateOf("") }
    var costStr by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text("Add Leftover / Grocery", style = MaterialTheme.typography.titleMedium, color = c.textPrimary)
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                GlassTextField(value = title, onValueChange = { title = it }, placeholder = "Title (e.g. Leftover Curry, Milk)")
                GlassTextField(value = quantity, onValueChange = { quantity = it }, placeholder = "Quantity (e.g. 2 containers, 1L)")
                GlassTextField(value = category, onValueChange = { category = it }, placeholder = "Category")
                GlassTextField(value = priority, onValueChange = { priority = it }, placeholder = "Priority (Urgent / Normal / Low)")
                GlassTextField(value = notes, onValueChange = { notes = it }, placeholder = "Notes (Shelf, expiry, container)")
                GlassTextField(
                    value = costStr,
                    onValueChange = { costStr = it },
                    placeholder = "Estimated Cost (optional €)",
                    keyboardType = KeyboardType.Decimal,
                )
            }
        },
        confirmButton = {
            PrimaryButton(
                text = "Add Item",
                onClick = {
                    if (title.isNotBlank()) {
                        val cost = costStr.toDoubleOrNull()
                        onAdd(title, quantity, category, priority, notes, cost)
                    }
                },
            )
        },
        dismissButton = {
            SecondaryButton(text = "Cancel", onClick = onDismiss)
        },
    )
}

@Composable
private fun LogGroceryExpenseDialog(
    onDismiss: () -> Unit,
    onLog: (title: String, amount: Double, category: String, notes: String) -> Unit,
) {
    val c = LocalAppColors.current
    var title by remember { mutableStateOf("") }
    var amountStr by remember { mutableStateOf("") }
    var notes by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text("Log Shared Grocery (50/50)", style = MaterialTheme.typography.titleMedium, color = c.textPrimary)
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                GlassTextField(value = title, onValueChange = { title = it }, placeholder = "Grocery Store / Description")
                GlassTextField(
                    value = amountStr,
                    onValueChange = { amountStr = it },
                    placeholder = "Total Amount (€)",
                    keyboardType = KeyboardType.Decimal,
                )
                GlassTextField(value = notes, onValueChange = { notes = it }, placeholder = "Notes (e.g. Coles receipt, split equally)")
                Text(
                    "This expense will be recorded as split 50/50 in the Cohabit roommate ledger.",
                    style = MaterialTheme.typography.bodySmall,
                    color = c.textSecondary,
                )
            }
        },
        confirmButton = {
            PrimaryButton(
                text = "Log 50/50 Expense",
                onClick = {
                    val amount = amountStr.toDoubleOrNull()
                    if (title.isNotBlank() && amount != null && amount > 0.0) {
                        onLog(title, amount, "Shared Groceries", notes)
                    }
                },
            )
        },
        dismissButton = {
            SecondaryButton(text = "Cancel", onClick = onDismiss)
        },
    )
}

private fun formatTimestamp(millis: Long): String {
    val df = SimpleDateFormat("d MMM, HH:mm", Locale.getDefault())
    return df.format(Date(millis))
}
