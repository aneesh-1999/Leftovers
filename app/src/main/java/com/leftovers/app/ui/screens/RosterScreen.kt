package com.leftovers.app.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.leftovers.app.data.AccountRepository
import com.leftovers.app.data.AccountWithBalance
import com.leftovers.app.data.LeaveEntry
import com.leftovers.app.data.LeavePaymentStatus
import com.leftovers.app.util.AmountInput
import com.leftovers.app.util.TimesheetExporter
import com.leftovers.app.data.LeaveType
import com.leftovers.app.data.RosterDailySummary
import com.leftovers.app.data.RosterMonthlySummary
import com.leftovers.app.data.RosterRepository
import com.leftovers.app.data.RosterWeeklySummary
import com.leftovers.app.data.Shift
import com.leftovers.app.data.ShiftStatus
import com.leftovers.app.data.Transaction
import com.leftovers.app.data.TransactionRepository
import com.leftovers.app.data.TxType
import com.leftovers.app.data.Workplace
import com.leftovers.app.data.WorkplaceEarningsSummary
import com.leftovers.app.data.WorkplacePayment
import com.leftovers.app.data.calculateDailySummary
import com.leftovers.app.data.calculateMonthlySummary
import com.leftovers.app.data.calculateWeeklySummary
import com.leftovers.app.data.calculateWorkplaceEarnings
import com.leftovers.app.data.formatDecimalHours
import com.leftovers.app.data.formatMinutesDuration
import com.leftovers.app.ui.AppViewModelProvider
import com.leftovers.app.ui.components.DockClearance
import com.leftovers.app.ui.components.Gauge
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
import com.leftovers.app.ui.components.appear
import com.leftovers.app.ui.components.frosted
import com.leftovers.app.ui.components.glassBorder
import com.leftovers.app.ui.components.pressable
import com.leftovers.app.ui.icons.Lucide
import com.leftovers.app.ui.theme.LocalAppColors
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.WeekFields
import java.util.Locale

enum class RosterTab(val title: String) {
    CALENDAR("Calendar"),
    EARNINGS("Earnings"),
    WORKPLACES("Workplaces"),
    LEAVE("Leave"),
}

enum class RosterViewMode { MONTH, WEEK }

class RosterViewModel(
    private val repository: RosterRepository,
    private val transactionRepository: TransactionRepository,
    private val accountRepository: AccountRepository,
) : ViewModel() {
    val shifts: StateFlow<List<Shift>> =
        repository.shifts.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val workplaces: StateFlow<List<Workplace>> =
        repository.workplaces.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val activeWorkplaces: StateFlow<List<Workplace>> =
        repository.activeWorkplaces.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val leaves: StateFlow<List<LeaveEntry>> =
        repository.leaves.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val payments: StateFlow<List<WorkplacePayment>> =
        repository.payments.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val accounts: StateFlow<List<AccountWithBalance>> =
        accountRepository.accounts.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun saveShift(shift: Shift, onSaved: () -> Unit = {}) {
        viewModelScope.launch {
            repository.saveShift(shift)
            onSaved()
        }
    }

    fun deleteShift(shift: Shift) {
        viewModelScope.launch { repository.deleteShift(shift) }
    }

    fun updateShiftStatus(id: Long, status: ShiftStatus) {
        viewModelScope.launch { repository.setShiftStatus(id, status) }
    }

    fun saveWorkplace(workplace: Workplace, onSaved: () -> Unit = {}) {
        viewModelScope.launch {
            repository.saveWorkplace(workplace)
            onSaved()
        }
    }

    fun deleteWorkplace(workplace: Workplace) {
        viewModelScope.launch { repository.deleteWorkplace(workplace) }
    }

    fun setWorkplaceArchived(id: Long, archived: Boolean) {
        viewModelScope.launch { repository.setWorkplaceArchived(id, archived) }
    }

    fun saveLeave(entry: LeaveEntry, onSaved: () -> Unit = {}) {
        viewModelScope.launch {
            repository.saveLeave(entry)
            onSaved()
        }
    }

    fun deleteLeave(entry: LeaveEntry) {
        viewModelScope.launch { repository.deleteLeave(entry) }
    }

    fun savePayment(payment: WorkplacePayment, onSaved: () -> Unit = {}) {
        viewModelScope.launch {
            repository.savePayment(payment)
            onSaved()
        }
    }

    fun deletePayment(payment: WorkplacePayment) {
        viewModelScope.launch { repository.deletePayment(payment) }
    }

    fun recordPaymentToFinance(
        payment: WorkplacePayment,
        accountId: Long,
        workplaceName: String,
        onSuccess: () -> Unit,
    ) {
        viewModelScope.launch {
            if (payment.financeTransactionId != null) return@launch
            val categories = transactionRepository.categories.first()
            val salaryCategory = categories.firstOrNull { it.name.equals("Salary", ignoreCase = true) }
                ?: categories.firstOrNull { it.type == TxType.INCOME }
                ?: categories.firstOrNull()

            val categoryId = salaryCategory?.id ?: 1L
            val note = if (payment.payslipReference.isNotBlank()) {
                "Salary: $workplaceName (${payment.payslipReference})"
            } else {
                "Salary: $workplaceName (${payment.payPeriodMonth})"
            }

            val tx = Transaction(
                amountMinor = payment.amountMinor,
                type = TxType.INCOME,
                categoryId = categoryId,
                epochDay = payment.paymentEpochDay,
                note = note,
                accountId = accountId,
            )
            transactionRepository.saveTransaction(tx)
            val updatedPayment = payment.copy(reviewed = true, financeTransactionId = -1L)
            repository.savePayment(updatedPayment)
            onSuccess()
        }
    }
}

private val timeFormat = DateTimeFormatter.ofPattern("HH:mm")
private val monthHeaderFormat = DateTimeFormatter.ofPattern("MMMM yyyy")
private val dayHeaderFormat = DateTimeFormatter.ofPattern("EEEE, d MMMM")

@Composable
fun RosterScreen(
    onBack: () -> Unit,
    viewModel: RosterViewModel = viewModel(factory = AppViewModelProvider.Factory),
) {
    val shifts by viewModel.shifts.collectAsStateWithLifecycle()
    val workplaces by viewModel.workplaces.collectAsStateWithLifecycle()
    val activeWorkplaces by viewModel.activeWorkplaces.collectAsStateWithLifecycle()
    val leaves by viewModel.leaves.collectAsStateWithLifecycle()
    val payments by viewModel.payments.collectAsStateWithLifecycle()
    val accounts by viewModel.accounts.collectAsStateWithLifecycle()

    val c = LocalAppColors.current
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    var activeTab by rememberSaveable { mutableStateOf(RosterTab.CALENDAR) }
    var selectedDate by rememberSaveable { mutableStateOf(LocalDate.now()) }
    var currentMonth by rememberSaveable { mutableStateOf(YearMonth.now()) }
    var viewMode by rememberSaveable { mutableStateOf(RosterViewMode.MONTH) }
    var selectedWorkplaceId by rememberSaveable { mutableStateOf<Long?>(null) }

    var editingShift by remember { mutableStateOf<Shift?>(null) }
    var viewingShift by remember { mutableStateOf<Shift?>(null) }
    var deletingShift by remember { mutableStateOf<Shift?>(null) }

    var editingLeave by remember { mutableStateOf<LeaveEntry?>(null) }
    var viewingLeave by remember { mutableStateOf<LeaveEntry?>(null) }
    var deletingLeave by remember { mutableStateOf<LeaveEntry?>(null) }

    var editingWorkplace by remember { mutableStateOf<Workplace?>(null) }
    var viewingWorkplace by remember { mutableStateOf<Workplace?>(null) }
    var deletingWorkplace by remember { mutableStateOf<Workplace?>(null) }

    var recordingPaymentForWorkplace by remember { mutableStateOf<Workplace?>(null) }
    var exportingTimesheet by remember { mutableStateOf(false) }

    val dailySummary by remember(selectedDate, shifts, leaves) {
        derivedStateOf { calculateDailySummary(selectedDate, shifts, leaves) }
    }
    val weeklySummary by remember(selectedDate, shifts, leaves) {
        derivedStateOf { calculateWeeklySummary(selectedDate, shifts, leaves) }
    }
    val monthlySummary by remember(currentMonth, shifts, leaves) {
        derivedStateOf { calculateMonthlySummary(currentMonth, shifts, leaves) }
    }

    GlassScreen(
        title = "My Roster",
        onBack = onBack,
        snackbar = snackbar,
        actions = {
            RoundButton(Lucide.Share2, "Export timesheet", { exportingTimesheet = true })
        },
        bottomBar = {
            Row(
                Modifier.frosted().navigationBarsPadding().padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                when (activeTab) {
                    RosterTab.CALENDAR -> {
                        SecondaryButton("Add Leave", {
                            editingLeave = LeaveEntry(
                                startDateEpochDay = selectedDate.toEpochDay(),
                                endDateEpochDay = selectedDate.toEpochDay(),
                                workplaceId = selectedWorkplaceId,
                            )
                        }, Modifier.weight(1f))
                        PrimaryButton("Add Shift", {
                            val zone = ZoneId.systemDefault()
                            val defaultStart = selectedDate.atTime(7, 0).atZone(zone).toInstant().toEpochMilli()
                            val defaultEnd = selectedDate.atTime(15, 30).atZone(zone).toInstant().toEpochMilli()
                            val defaultWorkplace = activeWorkplaces.firstOrNull { it.id == selectedWorkplaceId } ?: activeWorkplaces.firstOrNull()
                            editingShift = Shift(
                                title = "Morning Shift",
                                workplaceId = defaultWorkplace?.id,
                                workplace = defaultWorkplace?.name,
                                startEpochMillis = defaultStart,
                                endEpochMillis = defaultEnd,
                                breakDurationMinutes = 30,
                            )
                        }, Modifier.weight(1f))
                    }
                    RosterTab.EARNINGS -> {
                        PrimaryButton("Record Payment", {
                            val targetWorkplace = activeWorkplaces.firstOrNull { it.id == selectedWorkplaceId } ?: activeWorkplaces.firstOrNull()
                            recordingPaymentForWorkplace = targetWorkplace
                        }, Modifier.fillMaxWidth())
                    }
                    RosterTab.WORKPLACES -> {
                        PrimaryButton("Add Workplace", {
                            editingWorkplace = Workplace(name = "")
                        }, Modifier.fillMaxWidth())
                    }
                    RosterTab.LEAVE -> {
                        PrimaryButton("Log Leave", {
                            editingLeave = LeaveEntry(
                                startDateEpochDay = selectedDate.toEpochDay(),
                                endDateEpochDay = selectedDate.toEpochDay(),
                                workplaceId = selectedWorkplaceId,
                            )
                        }, Modifier.fillMaxWidth())
                    }
                }
            }
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = padding.calculateTopPadding() + 8.dp,
                bottom = DockClearance + 56.dp,
            ),
        ) {
            item {
                SegmentedToggle(
                    options = RosterTab.entries,
                    selected = activeTab,
                    label = { it.title },
                    onSelect = { activeTab = it },
                    modifier = Modifier.fillMaxWidth().appear(0),
                )
                Spacer(Modifier.height(14.dp))
            }

            when (activeTab) {
                RosterTab.CALENDAR -> {
                    calendarTabItems(
                        selectedDate = selectedDate,
                        onSelectDate = { selectedDate = it },
                        currentMonth = currentMonth,
                        onChangeMonth = { currentMonth = it },
                        viewMode = viewMode,
                        onChangeViewMode = { viewMode = it },
                        shifts = shifts,
                        leaves = leaves,
                        dailySummary = dailySummary,
                        weeklySummary = weeklySummary,
                        monthlySummary = monthlySummary,
                        onViewShift = { viewingShift = it },
                        onViewLeave = { viewingLeave = it },
                        onAddShift = {
                            val zone = ZoneId.systemDefault()
                            val defaultStart = selectedDate.atTime(7, 0).atZone(zone).toInstant().toEpochMilli()
                            val defaultEnd = selectedDate.atTime(15, 30).atZone(zone).toInstant().toEpochMilli()
                            val wp = activeWorkplaces.firstOrNull { it.id == selectedWorkplaceId } ?: activeWorkplaces.firstOrNull()
                            editingShift = Shift(
                                title = "Morning Shift",
                                workplaceId = wp?.id,
                                workplace = wp?.name,
                                startEpochMillis = defaultStart,
                                endEpochMillis = defaultEnd,
                                breakDurationMinutes = 30,
                            )
                        },
                        onAddPresetShift = { presetTitle, startH, startM, endH, endM, overnight, breakM ->
                            val zone = ZoneId.systemDefault()
                            val defaultStart = selectedDate.atTime(startH, startM).atZone(zone).toInstant().toEpochMilli()
                            val endDate = if (overnight) selectedDate.plusDays(1) else selectedDate
                            val defaultEnd = endDate.atTime(endH, endM).atZone(zone).toInstant().toEpochMilli()
                            val wp = activeWorkplaces.firstOrNull { it.id == selectedWorkplaceId } ?: activeWorkplaces.firstOrNull()
                            editingShift = Shift(
                                title = presetTitle,
                                workplaceId = wp?.id,
                                workplace = wp?.name,
                                startEpochMillis = defaultStart,
                                endEpochMillis = defaultEnd,
                                breakDurationMinutes = breakM,
                            )
                        },
                        onAddLeave = {
                            editingLeave = LeaveEntry(
                                startDateEpochDay = selectedDate.toEpochDay(),
                                endDateEpochDay = selectedDate.toEpochDay(),
                                workplaceId = selectedWorkplaceId,
                            )
                        },
                    )
                }
                RosterTab.EARNINGS -> {
                    earningsTabItems(
                        currentMonth = currentMonth,
                        onChangeMonth = { currentMonth = it },
                        workplaces = activeWorkplaces,
                        selectedWorkplaceId = selectedWorkplaceId,
                        onSelectWorkplaceId = { selectedWorkplaceId = it },
                        shifts = shifts,
                        leaves = leaves,
                        payments = payments,
                        accounts = accounts,
                        onRecordPayment = { recordingPaymentForWorkplace = it },
                        onDeletePayment = { payment ->
                            viewModel.deletePayment(payment)
                            scope.launch {
                                val res = snackbar.showSnackbar("Payment deleted", actionLabel = "Undo")
                                if (res == SnackbarResult.ActionPerformed) {
                                    viewModel.savePayment(payment) {}
                                }
                            }
                        },
                        onSyncToFinance = { payment, accountId, wpName ->
                            viewModel.recordPaymentToFinance(payment, accountId, wpName) {
                                scope.launch { snackbar.showSnackbar("Salary recorded in Finance account") }
                            }
                        },
                    )
                }
                RosterTab.WORKPLACES -> {
                    workplacesTabItems(
                        workplaces = workplaces,
                        onEdit = { editingWorkplace = it },
                        onView = { viewingWorkplace = it },
                        onAdd = { editingWorkplace = Workplace(name = "") },
                        onArchiveToggle = { wp -> viewModel.setWorkplaceArchived(wp.id, !wp.archived) },
                    )
                }
                RosterTab.LEAVE -> {
                    leaveTabItems(
                        leaves = leaves,
                        shifts = shifts,
                        workplaces = workplaces,
                        onAdd = {
                            editingLeave = LeaveEntry(
                                startDateEpochDay = selectedDate.toEpochDay(),
                                endDateEpochDay = selectedDate.toEpochDay(),
                                workplaceId = selectedWorkplaceId,
                            )
                        },
                        onView = { viewingLeave = it },
                    )
                }
            }
        }
    }

    editingShift?.let { shift ->
        ShiftEditorSheet(
            initialShift = shift,
            workplaces = activeWorkplaces,
            onDismiss = { editingShift = null },
            onSave = { updated ->
                viewModel.saveShift(updated) {
                    editingShift = null
                    scope.launch { snackbar.showSnackbar("Shift saved") }
                }
            },
        )
    }

    viewingShift?.let { shift ->
        ShiftDetailsSheet(
            shift = shift,
            onDismiss = { viewingShift = null },
            onEdit = {
                viewingShift = null
                editingShift = shift
            },
            onUpdateStatus = { status ->
                viewModel.updateShiftStatus(shift.id, status)
                viewingShift = shift.copy(status = status)
            },
            onDelete = {
                viewingShift = null
                deletingShift = shift
            },
        )
    }

    deletingShift?.let { shift ->
        GlassAlertDialog(
            onDismissRequest = { deletingShift = null },
            title = { Text("Delete shift?", style = MaterialTheme.typography.titleLarge) },
            text = { Text("Are you sure you want to delete '${shift.title}'?", color = c.textSecondary) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteShift(shift)
                    deletingShift = null
                    scope.launch {
                        val res = snackbar.showSnackbar("Shift deleted", actionLabel = "Undo")
                        if (res == SnackbarResult.ActionPerformed) {
                            viewModel.saveShift(shift) {}
                        }
                    }
                }) {
                    Text("Delete", color = c.negative)
                }
            },
            dismissButton = {
                TextButton(onClick = { deletingShift = null }) {
                    Text("Cancel", color = c.textSecondary)
                }
            },
        )
    }

    editingLeave?.let { leave ->
        LeaveEditorSheet(
            initialLeave = leave,
            workplaces = activeWorkplaces,
            shifts = shifts,
            onDismiss = { editingLeave = null },
            onSave = { updated ->
                viewModel.saveLeave(updated) {
                    editingLeave = null
                    scope.launch { snackbar.showSnackbar("Leave record saved") }
                }
            },
        )
    }

    viewingLeave?.let { leave ->
        LeaveDetailsSheet(
            leave = leave,
            workplaces = workplaces,
            onDismiss = { viewingLeave = null },
            onEdit = {
                viewingLeave = null
                editingLeave = leave
            },
            onDelete = {
                viewingLeave = null
                deletingLeave = leave
            },
        )
    }

    deletingLeave?.let { leave ->
        GlassAlertDialog(
            onDismissRequest = { deletingLeave = null },
            title = { Text("Delete leave record?", style = MaterialTheme.typography.titleLarge) },
            text = { Text("Are you sure you want to remove this ${leave.type.label} entry?", color = c.textSecondary) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteLeave(leave)
                    deletingLeave = null
                    scope.launch {
                        val res = snackbar.showSnackbar("Leave record deleted", actionLabel = "Undo")
                        if (res == SnackbarResult.ActionPerformed) {
                            viewModel.saveLeave(leave) {}
                        }
                    }
                }) {
                    Text("Delete", color = c.negative)
                }
            },
            dismissButton = {
                TextButton(onClick = { deletingLeave = null }) {
                    Text("Cancel", color = c.textSecondary)
                }
            },
        )
    }

    editingWorkplace?.let { wp ->
        WorkplaceEditorSheet(
            initialWorkplace = wp,
            onDismiss = { editingWorkplace = null },
            onSave = { updated ->
                viewModel.saveWorkplace(updated) {
                    editingWorkplace = null
                    scope.launch { snackbar.showSnackbar("Workplace saved") }
                }
            },
        )
    }

    viewingWorkplace?.let { wp ->
        WorkplaceDetailsSheet(
            workplace = wp,
            onDismiss = { viewingWorkplace = null },
            onEdit = {
                viewingWorkplace = null
                editingWorkplace = wp
            },
            onDelete = {
                viewingWorkplace = null
                deletingWorkplace = wp
            },
        )
    }

    deletingWorkplace?.let { wp ->
        GlassAlertDialog(
            onDismissRequest = { deletingWorkplace = null },
            title = { Text("Delete workplace?", style = MaterialTheme.typography.titleLarge) },
            text = { Text("Are you sure you want to delete '${wp.name}'? Existing shifts and history will remain.", color = c.textSecondary) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteWorkplace(wp)
                    deletingWorkplace = null
                    scope.launch {
                        val res = snackbar.showSnackbar("Workplace deleted", actionLabel = "Undo")
                        if (res == SnackbarResult.ActionPerformed) {
                            viewModel.saveWorkplace(wp) {}
                        }
                    }
                }) {
                    Text("Delete", color = c.negative)
                }
            },
            dismissButton = {
                TextButton(onClick = { deletingWorkplace = null }) {
                    Text("Cancel", color = c.textSecondary)
                }
            },
        )
    }

    recordingPaymentForWorkplace?.let { wp ->
        WorkplacePaymentSheet(
            workplace = wp,
            currentMonth = currentMonth,
            onDismiss = { recordingPaymentForWorkplace = null },
            onSave = { payment ->
                viewModel.savePayment(payment) {
                    recordingPaymentForWorkplace = null
                    scope.launch { snackbar.showSnackbar("Payment recorded for ${wp.name}") }
                }
            },
        )
    }

    if (exportingTimesheet) {
        TimesheetExportSheet(
            workplaces = activeWorkplaces,
            shifts = shifts,
            leaves = leaves,
            currentMonth = currentMonth,
            onDismiss = { exportingTimesheet = false },
        )
    }
}

// -----------------------------------------------------------------------------------------
// CUSTOM UI BADGES AND CHIPS
// -----------------------------------------------------------------------------------------
@Composable
private fun StatusBadge(
    text: String,
    color: Color,
    textColor: Color = LocalAppColors.current.textPrimary,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .clip(RoundedCornerShape(8.dp))
            .background(color.copy(alpha = 0.16f))
            .border(1.dp, color.copy(alpha = 0.28f), RoundedCornerShape(8.dp))
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        Text(text, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold, color = textColor)
    }
}

@Composable
private fun RosterFilterChip(
    text: String,
    onClick: () -> Unit,
    selected: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val c = LocalAppColors.current
    val bg by animateColorAsState(if (selected) c.accent else c.glass, label = "chipBg")
    val fg by animateColorAsState(if (selected) c.onAccent else c.textPrimary, label = "chipFg")
    Box(
        modifier
            .height(36.dp)
            .pressable(onClick, pressedScale = 0.94f)
            .clip(CircleShape)
            .background(bg)
            .border(1.dp, if (selected) Color.Transparent else c.textTertiary.copy(alpha = 0.25f), CircleShape)
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = MaterialTheme.typography.labelMedium, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal, color = fg, maxLines = 1)
    }
}

// -----------------------------------------------------------------------------------------
// CALENDAR TAB ITEMS
// -----------------------------------------------------------------------------------------
private fun androidx.compose.foundation.lazy.LazyListScope.calendarTabItems(
    selectedDate: LocalDate,
    onSelectDate: (LocalDate) -> Unit,
    currentMonth: YearMonth,
    onChangeMonth: (YearMonth) -> Unit,
    viewMode: RosterViewMode,
    onChangeViewMode: (RosterViewMode) -> Unit,
    shifts: List<Shift>,
    leaves: List<LeaveEntry>,
    dailySummary: RosterDailySummary,
    weeklySummary: RosterWeeklySummary,
    monthlySummary: RosterMonthlySummary,
    onViewShift: (Shift) -> Unit,
    onViewLeave: (LeaveEntry) -> Unit,
    onAddShift: () -> Unit,
    onAddPresetShift: (presetTitle: String, startH: Int, startM: Int, endH: Int, endM: Int, overnight: Boolean, breakM: Int) -> Unit = { _, _, _, _, _, _, _ -> },
    onAddLeave: () -> Unit,
) {
    item {
        Row(
            Modifier.fillMaxWidth().appear(0),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Schedule & Leave", style = MaterialTheme.typography.titleMedium, color = Color.Unspecified)
                Text("Tap any date to manage shifts or log leave", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
            }
            SegmentedToggle(
                options = listOf(RosterViewMode.MONTH, RosterViewMode.WEEK),
                selected = viewMode,
                label = { if (it == RosterViewMode.MONTH) "Month" else "Week" },
                onSelect = onChangeViewMode,
                modifier = Modifier.width(180.dp),
            )
        }
        Spacer(Modifier.height(14.dp))
    }

    item {
        val c = LocalAppColors.current
        Glass(Modifier.fillMaxWidth().appear(0), strong = true, shape = RoundedCornerShape(24.dp)) {
            Row(
                Modifier.padding(horizontal = 14.dp, vertical = 10.dp).fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RoundButton(
                    icon = Lucide.ChevronLeft,
                    contentDescription = "Previous",
                    onClick = {
                        if (viewMode == RosterViewMode.MONTH) {
                            val newM = currentMonth.minusMonths(1)
                            onChangeMonth(newM)
                            if (selectedDate.month != newM.month) onSelectDate(newM.atDay(1))
                        } else {
                            val newD = selectedDate.minusWeeks(1)
                            onSelectDate(newD)
                            onChangeMonth(YearMonth.from(newD))
                        }
                    },
                )
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = if (viewMode == RosterViewMode.MONTH) currentMonth.format(monthHeaderFormat)
                        else "Week of ${weeklySummary.startOfWeek.dayOfMonth} ${weeklySummary.startOfWeek.month.name.take(3)} - ${weeklySummary.endOfWeek.dayOfMonth} ${weeklySummary.endOfWeek.month.name.take(3)}",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = c.textPrimary,
                    )
                    Spacer(Modifier.height(2.dp))
                    val today = LocalDate.now()
                    val todayStr = "Today · ${today.dayOfMonth} ${today.month.name.take(3).lowercase().replaceFirstChar { it.uppercase() }}"
                    Text(
                        text = todayStr,
                        style = MaterialTheme.typography.labelSmall,
                        color = c.textSecondary,
                        modifier = Modifier.pressable(onClick = {
                            onSelectDate(today)
                            onChangeMonth(YearMonth.now())
                        }),
                    )
                }
                RoundButton(
                    icon = Lucide.ChevronRight,
                    contentDescription = "Next",
                    onClick = {
                        if (viewMode == RosterViewMode.MONTH) {
                            val newM = currentMonth.plusMonths(1)
                            onChangeMonth(newM)
                            if (selectedDate.month != newM.month) onSelectDate(newM.atDay(1))
                        } else {
                            val newD = selectedDate.plusWeeks(1)
                            onSelectDate(newD)
                            onChangeMonth(YearMonth.from(newD))
                        }
                    },
                )
            }
        }
        Spacer(Modifier.height(12.dp))
    }

    item {
        val c = LocalAppColors.current
        Glass(Modifier.fillMaxWidth().appear(1), strong = true, shape = RoundedCornerShape(22.dp)) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                if (viewMode == RosterViewMode.MONTH) {
                    MonthlyCalendarGrid(
                        month = currentMonth,
                        selectedDate = selectedDate,
                        shifts = shifts,
                        leaves = leaves,
                        onSelectDate = {
                            onSelectDate(it)
                            if (YearMonth.from(it) != currentMonth) onChangeMonth(YearMonth.from(it))
                        },
                    )
                } else {
                    WeeklyCalendarStrip(
                        selectedDate = selectedDate,
                        shifts = shifts,
                        leaves = leaves,
                        onSelectDate = {
                            onSelectDate(it)
                            onChangeMonth(YearMonth.from(it))
                        },
                    )
                }

                Spacer(Modifier.height(8.dp))
                // Clean compact color legend matching reports
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CalendarLegendItem(color = c.positive, label = "Completed")
                    CalendarLegendItem(color = Color(0xFF38BDF8), label = "Scheduled")
                    CalendarLegendItem(color = Color(0xFFA855F7), label = "Leave")
                }
            }
        }
        Spacer(Modifier.height(12.dp))
    }

    item {
        val c = LocalAppColors.current
        Glass(Modifier.fillMaxWidth().appear(2), shape = RoundedCornerShape(24.dp)) {
            Column(Modifier.padding(16.dp)) {
                Text(
                    if (viewMode == RosterViewMode.MONTH) "${currentMonth.month.name.lowercase().replaceFirstChar { it.uppercase() }} Overview" else "Weekly Overview",
                    style = MaterialTheme.typography.titleSmall,
                    color = c.textSecondary,
                )
                Spacer(Modifier.height(10.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            if (viewMode == RosterViewMode.MONTH) formatMinutesDuration(monthlySummary.totalWorkingMinutes) else formatMinutesDuration(weeklySummary.totalWorkingMinutes),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = c.textPrimary,
                        )
                        Text("Scheduled work", style = MaterialTheme.typography.bodySmall, color = c.textTertiary)
                    }
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            if (viewMode == RosterViewMode.MONTH) "${monthlySummary.completedShifts} / ${monthlySummary.totalShifts}" else "${weeklySummary.completedShifts} / ${weeklySummary.totalShifts}",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = c.positive,
                        )
                        Text("Shifts completed", style = MaterialTheme.typography.bodySmall, color = c.textTertiary)
                    }
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.End) {
                        val leaveCount = if (viewMode == RosterViewMode.MONTH) (monthlySummary.daysOff + monthlySummary.sickLeaveDays + monthlySummary.annualLeaveDays + monthlySummary.publicHolidayDays) else weeklySummary.leaveDays
                        Text(
                            "$leaveCount d",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = c.accent,
                        )
                        Text("Leave / Off", style = MaterialTheme.typography.bodySmall, color = c.textTertiary)
                    }
                }
            }
        }
        Spacer(Modifier.height(18.dp))
    }

    item {
        val c = LocalAppColors.current
        Row(
            Modifier.fillMaxWidth().appear(2),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text(selectedDate.format(dayHeaderFormat), style = MaterialTheme.typography.titleMedium, color = c.textPrimary)
                Text(
                    "${dailySummary.shifts.size} shift(s) · ${formatMinutesDuration(dailySummary.totalWorkingMinutes)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = c.textSecondary,
                )
            }
            if (dailySummary.shifts.isNotEmpty() && dailySummary.leaves.isNotEmpty()) {
                StatusBadge("⚠️ Shift & Leave Conflict", c.warning, c.textPrimary)
            }
        }
        Spacer(Modifier.height(10.dp))
    }

    item {
        val c = LocalAppColors.current
        Column(Modifier.fillMaxWidth().padding(bottom = 10.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                RosterFilterChip(
                    text = "☀️ Morning 7–15:30",
                    selected = false,
                    onClick = { onAddPresetShift("Morning Shift", 7, 0, 15, 30, false, 30) },
                    modifier = Modifier.weight(1f),
                )
                RosterFilterChip(
                    text = "🌤️ Midday 9–17:30",
                    selected = false,
                    onClick = { onAddPresetShift("Midday Shift", 9, 0, 17, 30, false, 30) },
                    modifier = Modifier.weight(1f),
                )
                RosterFilterChip(
                    text = "🌙 Evening 15–23:30",
                    selected = false,
                    onClick = { onAddPresetShift("Evening Shift", 15, 0, 23, 30, false, 30) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }

    if (dailySummary.leaves.isNotEmpty()) {
        items(dailySummary.leaves) { leave ->
            val c = LocalAppColors.current
            Glass(
                Modifier.fillMaxWidth().padding(bottom = 8.dp).pressable(onClick = { onViewLeave(leave) }),
                shape = RoundedCornerShape(20.dp),
            ) {
                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconTile(Lucide.Calendar, c.accent, size = 42.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("${leave.type.emoji} ${leave.type.label}", style = MaterialTheme.typography.titleSmall, color = c.textPrimary)
                            if (leave.paymentStatus != LeavePaymentStatus.UNPAID) {
                                StatusBadge(leave.paymentStatus.label, c.positive, c.textPrimary)
                            } else {
                                StatusBadge("Unpaid", c.textTertiary, c.textSecondary)
                            }
                        }
                        if (leave.reason.isNotBlank()) {
                            Text(leave.reason, style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
                        } else {
                            Text(if (leave.isFullDay) "Full Day" else "${leave.effectiveHours} hrs", style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
                        }
                    }
                    Icon(Lucide.ChevronRight, contentDescription = null, tint = c.textTertiary, modifier = Modifier.size(18.dp))
                }
            }
        }
    }

    if (dailySummary.shifts.isNotEmpty()) {
        items(dailySummary.shifts) { shift ->
            val c = LocalAppColors.current
            val startTime = Instant.ofEpochMilli(shift.startEpochMillis).atZone(ZoneId.systemDefault()).toLocalTime()
            val endTime = Instant.ofEpochMilli(shift.endEpochMillis).atZone(ZoneId.systemDefault()).toLocalTime()
            Glass(
                Modifier.fillMaxWidth().padding(bottom = 8.dp).pressable(onClick = { onViewShift(shift) }),
                shape = RoundedCornerShape(20.dp),
            ) {
                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconTile(if (shift.status == ShiftStatus.COMPLETED) Lucide.CircleCheck else Lucide.Clock, if (shift.status == ShiftStatus.COMPLETED) c.positive else c.textPrimary, size = 42.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(shift.title, style = MaterialTheme.typography.titleSmall, color = c.textPrimary, maxLines = 1)
                            if (shift.isOvernight) {
                                StatusBadge("Overnight", c.accent, c.textPrimary)
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "${startTime.format(timeFormat)} - ${endTime.format(timeFormat)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = c.textSecondary,
                            )
                            if (shift.workplace != null) {
                                Text("· ${shift.workplace}", style = MaterialTheme.typography.bodySmall, color = c.textTertiary, maxLines = 1)
                            }
                        }
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(formatMinutesDuration(shift.netWorkingMinutes), style = MaterialTheme.typography.titleSmall, color = c.textPrimary)
                        Text(shift.status.label, style = MaterialTheme.typography.labelSmall, color = when (shift.status) {
                            ShiftStatus.COMPLETED -> c.positive
                            ShiftStatus.SCHEDULED -> c.accent
                            ShiftStatus.CANCELLED -> c.textTertiary
                        })
                    }
                }
            }
        }
    } else if (dailySummary.leaves.isEmpty()) {
        item {
            val c = LocalAppColors.current
            Glass(Modifier.fillMaxWidth().appear(3), shape = RoundedCornerShape(24.dp)) {
                Column(
                    Modifier.padding(24.dp).fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    IconTile(Lucide.Calendar, c.textTertiary, size = 48.dp)
                    Spacer(Modifier.height(10.dp))
                    Text("No shifts or leave scheduled", style = MaterialTheme.typography.titleSmall, color = c.textPrimary)
                    Text("Add a shift or log a day off for this date", style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
                    Spacer(Modifier.height(14.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        SecondaryButton("Log Day Off", onAddLeave)
                        PrimaryButton("+ Add Shift", onAddShift)
                    }
                }
            }
        }
    }
}

// -----------------------------------------------------------------------------------------
// EARNINGS TAB
// -----------------------------------------------------------------------------------------
private fun androidx.compose.foundation.lazy.LazyListScope.earningsTabItems(
    currentMonth: YearMonth,
    onChangeMonth: (YearMonth) -> Unit,
    workplaces: List<Workplace>,
    selectedWorkplaceId: Long?,
    onSelectWorkplaceId: (Long?) -> Unit,
    shifts: List<Shift>,
    leaves: List<LeaveEntry>,
    payments: List<WorkplacePayment>,
    accounts: List<AccountWithBalance>,
    onRecordPayment: (Workplace) -> Unit,
    onDeletePayment: (WorkplacePayment) -> Unit,
    onSyncToFinance: (WorkplacePayment, Long, String) -> Unit,
) {
    item {
        LazyRow(
            Modifier.fillMaxWidth().appear(0),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                RosterFilterChip(
                    text = "All Employers",
                    selected = selectedWorkplaceId == null,
                    onClick = { onSelectWorkplaceId(null) },
                )
            }
            items(workplaces) { wp ->
                RosterFilterChip(
                    text = "${wp.name} (${wp.currency})",
                    selected = selectedWorkplaceId == wp.id,
                    onClick = { onSelectWorkplaceId(wp.id) },
                )
            }
        }
        Spacer(Modifier.height(12.dp))
    }

    item {
        Glass(Modifier.fillMaxWidth().appear(0), strong = true, shape = RoundedCornerShape(24.dp)) {
            Row(
                Modifier.padding(horizontal = 14.dp, vertical = 10.dp).fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RoundButton(
                    icon = Lucide.ChevronLeft,
                    contentDescription = "Previous",
                    onClick = { onChangeMonth(currentMonth.minusMonths(1)) },
                )
                Text(currentMonth.format(monthHeaderFormat), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                RoundButton(
                    icon = Lucide.ChevronRight,
                    contentDescription = "Next",
                    onClick = { onChangeMonth(currentMonth.plusMonths(1)) },
                )
            }
        }
        Spacer(Modifier.height(14.dp))
    }

    val activeWorkplaceList = if (selectedWorkplaceId != null) {
        workplaces.filter { it.id == selectedWorkplaceId }
    } else {
        workplaces
    }

    if (activeWorkplaceList.isEmpty()) {
        item {
            val c = LocalAppColors.current
            Glass(Modifier.fillMaxWidth().appear(1), shape = RoundedCornerShape(28.dp)) {
                Column(Modifier.padding(28.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    IconTile(Lucide.Building2, c.textTertiary, size = 52.dp)
                    Spacer(Modifier.height(12.dp))
                    Text("No Workplaces Configured", style = MaterialTheme.typography.titleMedium, color = c.textPrimary)
                    Text("Add your workplace pay rate & monthly basic threshold to calculate earnings and overtime.", style = MaterialTheme.typography.bodySmall, color = c.textSecondary, textAlign = TextAlign.Center)
                }
            }
        }
        return
    }

    activeWorkplaceList.forEach { wp ->
        val summary = calculateWorkplaceEarnings(wp, currentMonth, shifts, leaves, payments)
        item {
            WorkplaceEarningsDashboardCard(
                summary = summary,
                onRecordPayment = { onRecordPayment(wp) },
                onDeletePayment = onDeletePayment,
                accounts = accounts,
                onSyncToFinance = { payment, accId -> onSyncToFinance(payment, accId, wp.name) },
            )
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun WorkplaceEarningsDashboardCard(
    summary: WorkplaceEarningsSummary,
    onRecordPayment: () -> Unit,
    onDeletePayment: (WorkplacePayment) -> Unit,
    accounts: List<AccountWithBalance>,
    onSyncToFinance: (WorkplacePayment, Long) -> Unit,
) {
    val c = LocalAppColors.current
    val wp = summary.workplace ?: return

    Glass(Modifier.fillMaxWidth(), strong = true, shape = RoundedCornerShape(28.dp)) {
        Column(Modifier.padding(20.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column {
                    Text(wp.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = c.textPrimary)
                    Text("${wp.role.ifBlank { "Employee" }} · Base €${wp.hourlyRateDisplay}/h · OT ${wp.overtimeMultiplier}× (€${wp.overtimeHourlyRateDisplay}/h)", style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
                }
                StatusBadge(wp.currency, c.accent, c.onAccent)
            }
            Spacer(Modifier.height(16.dp))

            Gauge(summary.progressToThreshold, Modifier.size(190.dp).align(Alignment.CenterHorizontally)) {
                Column(Modifier.align(Alignment.Center).fillMaxWidth().padding(horizontal = 20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Completed Hours", style = MaterialTheme.typography.labelSmall, color = c.textSecondary)
                    RollingText(formatDecimalHours(summary.completedHours), MaterialTheme.typography.headlineMedium, c.textPrimary)
                    Text("of ${formatDecimalHours(summary.basicHoursThreshold)} threshold", style = MaterialTheme.typography.bodySmall, color = c.textTertiary)
                }
            }
            Spacer(Modifier.height(16.dp))

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Column(Modifier.weight(1f).clip(RoundedCornerShape(16.dp)).background(c.glass).padding(12.dp)) {
                    Text("Basic Hours", style = MaterialTheme.typography.labelSmall, color = c.textSecondary)
                    Text(formatDecimalHours(summary.basicHoursUsed), style = MaterialTheme.typography.titleMedium, color = c.positive)
                    Text("Left: ${formatDecimalHours(summary.remainingBasicHours)}", style = MaterialTheme.typography.bodySmall, color = c.textTertiary)
                }
                Column(Modifier.weight(1f).clip(RoundedCornerShape(16.dp)).background(c.glass).padding(12.dp)) {
                    Text("Overtime (> threshold)", style = MaterialTheme.typography.labelSmall, color = c.textSecondary)
                    Text(formatDecimalHours(summary.overtimeHours), style = MaterialTheme.typography.titleMedium, color = if (summary.overtimeHours > 0) c.accent else c.textTertiary)
                    Text("@ ${wp.overtimeMultiplier}× rate", style = MaterialTheme.typography.bodySmall, color = c.textTertiary)
                }
            }
            Spacer(Modifier.height(16.dp))

            Text("Gross Estimated Earnings Breakdown", style = MaterialTheme.typography.labelLarge, color = c.textPrimary)
            Spacer(Modifier.height(8.dp))

            EarningsRowItem("Basic Pay (${formatDecimalHours(summary.basicHoursUsed)} @ €${wp.hourlyRateDisplay})", formatMoney(summary.basicEarningsMinor, wp.currency))
            if (summary.overtimeHours > 0) {
                EarningsRowItem("Overtime Pay (${formatDecimalHours(summary.overtimeHours)} @ €${wp.overtimeHourlyRateDisplay})", "+${formatMoney(summary.overtimeEarningsMinor, wp.currency)}", c.positive)
            }
            if (summary.paidSickLeaveEarningsMinor > 0) {
                EarningsRowItem("Paid Sick Leave (${formatDecimalHours(summary.sickLeaveHours)})", "+${formatMoney(summary.paidSickLeaveEarningsMinor, wp.currency)}", c.positive)
            }
            if (summary.paidAnnualLeaveEarningsMinor > 0) {
                EarningsRowItem("Paid Annual Leave (${formatDecimalHours(summary.annualLeaveHours)})", "+${formatMoney(summary.paidAnnualLeaveEarningsMinor, wp.currency)}", c.positive)
            }
            if (summary.fixedAllowanceMinor > 0) {
                EarningsRowItem("Fixed Monthly Allowance", "+${formatMoney(summary.fixedAllowanceMinor, wp.currency)}", c.positive)
            }
            if (wp.monthlyDeductionMinor > 0) {
                EarningsRowItem("Configured Deductions", "-${formatMoney(wp.monthlyDeductionMinor, wp.currency)}", c.negative)
            }
            RowDivider(inset = 0.dp)

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Estimated Net Earnings", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = c.textPrimary)
                Text(formatMoney(summary.estimatedNetEarningsMinor, wp.currency), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = c.accent)
            }
            Text("Estimated calculation for planning; does not constitute an official payslip.", style = MaterialTheme.typography.bodySmall, color = c.textTertiary)

            Spacer(Modifier.height(16.dp))

            SectionHeader("Recorded Payments (${summary.recordedPayments.size})", action = "+ Add Payment", onAction = onRecordPayment)
            if (summary.recordedPayments.isEmpty()) {
                Text("No actual bank payments recorded for this pay period yet.", style = MaterialTheme.typography.bodySmall, color = c.textTertiary)
            } else {
                summary.recordedPayments.forEach { payment ->
                    PaymentReconciliationRow(
                        payment = payment,
                        accounts = accounts,
                        onDelete = { onDeletePayment(payment) },
                        onSyncToFinance = { accId -> onSyncToFinance(payment, accId) },
                    )
                }
            }
        }
    }
}

@Composable
private fun EarningsRowItem(title: String, value: String, color: Color = Color.Unspecified) {
    val c = LocalAppColors.current
    Row(
        Modifier.fillMaxWidth().padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(title, style = MaterialTheme.typography.bodyMedium, color = c.textSecondary)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = if (color != Color.Unspecified) color else c.textPrimary)
    }
}

@Composable
private fun PaymentReconciliationRow(
    payment: WorkplacePayment,
    accounts: List<AccountWithBalance>,
    onDelete: () -> Unit,
    onSyncToFinance: (Long) -> Unit,
) {
    val c = LocalAppColors.current
    var showAccountPicker by remember { mutableStateOf(false) }

    Glass(Modifier.fillMaxWidth().padding(vertical = 4.dp), shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.padding(12.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column {
                    Text("Payment on ${payment.paymentDate.dayOfMonth} ${payment.paymentDate.month.name.take(3)}", style = MaterialTheme.typography.titleSmall, color = c.textPrimary)
                    if (payment.payslipReference.isNotBlank()) {
                        Text("Ref: ${payment.payslipReference}", style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
                    }
                }
                Text(formatMoney(payment.amountMinor, payment.currency), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = c.positive)
            }
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                if (payment.financeTransactionId != null) {
                    StatusBadge("✓ Synced with Finance", c.positive, c.textPrimary)
                } else {
                    TextButton(onClick = { showAccountPicker = true }) {
                        Text("Record to Finance Account ↗", style = MaterialTheme.typography.labelMedium, color = c.accent)
                    }
                }
                TextButton(onClick = onDelete) {
                    Text("Delete", style = MaterialTheme.typography.labelSmall, color = c.negative)
                }
            }
        }
    }

    if (showAccountPicker) {
        GlassAlertDialog(
            onDismissRequest = { showAccountPicker = false },
            title = { Text("Select Finance Account", style = MaterialTheme.typography.titleLarge) },
            text = {
                Column {
                    Text("Choose which account received this salary payment:", color = c.textSecondary)
                    Spacer(Modifier.height(10.dp))
                    accounts.forEach { acc ->
                        TextButton(
                            onClick = {
                                showAccountPicker = false
                                onSyncToFinance(acc.id)
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("${acc.name} (${acc.currency ?: "Default"})", color = c.textPrimary)
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showAccountPicker = false }) {
                    Text("Cancel", color = c.textSecondary)
                }
            },
        )
    }
}

// -----------------------------------------------------------------------------------------
// WORKPLACES TAB
// -----------------------------------------------------------------------------------------
private fun androidx.compose.foundation.lazy.LazyListScope.workplacesTabItems(
    workplaces: List<Workplace>,
    onEdit: (Workplace) -> Unit,
    onView: (Workplace) -> Unit,
    onAdd: () -> Unit,
    onArchiveToggle: (Workplace) -> Unit,
) {
    item {
        SectionHeader("Active Employers (${workplaces.count { !it.archived }})", action = "+ Add", onAction = onAdd)
    }

    val active = workplaces.filter { !it.archived }
    if (active.isEmpty()) {
        item {
            val c = LocalAppColors.current
            Glass(Modifier.fillMaxWidth().appear(0), shape = RoundedCornerShape(24.dp)) {
                Column(Modifier.padding(24.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    IconTile(Lucide.Building2, c.textTertiary, size = 48.dp)
                    Spacer(Modifier.height(8.dp))
                    Text("No Workplaces Added", style = MaterialTheme.typography.titleMedium, color = c.textPrimary)
                    Text("Add your job profiles, pay rates and overtime thresholds", style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
                    Spacer(Modifier.height(14.dp))
                    PrimaryButton("+ Add Workplace", onAdd)
                }
            }
        }
    } else {
        items(active) { wp ->
            WorkplaceRowItem(wp, onView = { onView(wp) }, onEdit = { onEdit(wp) }, onArchive = { onArchiveToggle(wp) })
        }
    }

    val archived = workplaces.filter { it.archived }
    if (archived.isNotEmpty()) {
        item {
            Spacer(Modifier.height(16.dp))
            SectionHeader("Archived Workplaces (${archived.size})")
        }
        items(archived) { wp ->
            WorkplaceRowItem(wp, onView = { onView(wp) }, onEdit = { onEdit(wp) }, onArchive = { onArchiveToggle(wp) })
        }
    }
}

@Composable
private fun WorkplaceRowItem(
    wp: Workplace,
    onView: () -> Unit,
    onEdit: () -> Unit,
    onArchive: () -> Unit,
) {
    val c = LocalAppColors.current
    Glass(
        Modifier.fillMaxWidth().padding(bottom = 8.dp).pressable(onClick = onView),
        shape = RoundedCornerShape(20.dp),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            IconTile(Lucide.Briefcase, if (wp.archived) c.textTertiary else c.accent, size = 44.dp)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(wp.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = c.textPrimary)
                Text(
                    "${wp.role.ifBlank { wp.employmentType }} · Base €${wp.hourlyRateDisplay}/h · OT ${wp.overtimeMultiplier}×",
                    style = MaterialTheme.typography.bodySmall,
                    color = c.textSecondary,
                )
                Text("Threshold: ${wp.basicMonthlyHours} h / month", style = MaterialTheme.typography.labelSmall, color = c.textTertiary)
            }
            Icon(Lucide.ChevronRight, contentDescription = null, tint = c.textTertiary, modifier = Modifier.size(18.dp))
        }
    }
}

// -----------------------------------------------------------------------------------------
// LEAVE TAB
// -----------------------------------------------------------------------------------------
private fun androidx.compose.foundation.lazy.LazyListScope.leaveTabItems(
    leaves: List<LeaveEntry>,
    shifts: List<Shift>,
    workplaces: List<Workplace>,
    onAdd: () -> Unit,
    onView: (LeaveEntry) -> Unit,
) {
    item {
        SectionHeader("Leave & Days Off (${leaves.size})", action = "+ Log Leave", onAction = onAdd)
    }

    if (leaves.isEmpty()) {
        item {
            val c = LocalAppColors.current
            Glass(Modifier.fillMaxWidth().appear(0), shape = RoundedCornerShape(24.dp)) {
                Column(Modifier.padding(24.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    IconTile(Lucide.Calendar, c.textTertiary, size = 48.dp)
                    Spacer(Modifier.height(8.dp))
                    Text("No Leave Records", style = MaterialTheme.typography.titleMedium, color = c.textPrimary)
                    Text("Record sick leave, holidays, annual leave, or days off", style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
                    Spacer(Modifier.height(14.dp))
                    PrimaryButton("+ Log Leave", onAdd)
                }
            }
        }
    } else {
        items(leaves) { leave ->
            val c = LocalAppColors.current
            val wp = workplaces.firstOrNull { it.id == leave.workplaceId }
            Glass(
                Modifier.fillMaxWidth().padding(bottom = 8.dp).pressable(onClick = { onView(leave) }),
                shape = RoundedCornerShape(20.dp),
            ) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconTile(Lucide.Calendar, c.accent, size = 44.dp)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("${leave.type.emoji} ${leave.type.label}", style = MaterialTheme.typography.titleMedium, color = c.textPrimary)
                            if (leave.paymentStatus != LeavePaymentStatus.UNPAID) {
                                StatusBadge(leave.paymentStatus.label, c.positive, c.textPrimary)
                            }
                        }
                        Text(
                            "${leave.startDate.dayOfMonth} ${leave.startDate.month.name.take(3)} - ${leave.endDate.dayOfMonth} ${leave.endDate.month.name.take(3)} (${leave.dayCount} d)",
                            style = MaterialTheme.typography.bodySmall,
                            color = c.textSecondary,
                        )
                        if (wp != null) {
                            Text("Workplace: ${wp.name}", style = MaterialTheme.typography.labelSmall, color = c.textTertiary)
                        }
                    }
                    Icon(Lucide.ChevronRight, contentDescription = null, tint = c.textTertiary, modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}

// -----------------------------------------------------------------------------------------
// CALENDAR GRID & STRIP
// -----------------------------------------------------------------------------------------
@Composable
private fun MonthlyCalendarGrid(
    month: YearMonth,
    selectedDate: LocalDate,
    shifts: List<Shift>,
    leaves: List<LeaveEntry>,
    onSelectDate: (LocalDate) -> Unit,
) {
    val c = LocalAppColors.current
    val daysInMonth = month.lengthOfMonth()
    val firstDayOfMonth = month.atDay(1)
    // Monday is index 0, Tuesday is 1, ..., Sunday is 6, matching dayNames ["M", "T", "W", "T", "F", "S", "S"]
    val firstDayOfWeekIndex = firstDayOfMonth.dayOfWeek.value - 1
    val dayNames = listOf("M", "T", "W", "T", "F", "S", "S")

    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceAround) {
            dayNames.forEach { day ->
                Text(
                    day,
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                    fontWeight = FontWeight.SemiBold,
                    color = c.textTertiary,
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Center,
                )
            }
        }
        Spacer(Modifier.height(6.dp))

        val totalCells = firstDayOfWeekIndex + daysInMonth
        val rows = (totalCells + 6) / 7

        for (row in 0 until rows) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceAround) {
                for (col in 0 until 7) {
                    val dayIndex = row * 7 + col - firstDayOfWeekIndex + 1
                    if (dayIndex in 1..daysInMonth) {
                        val date = month.atDay(dayIndex)
                        val isSelected = date == selectedDate
                        val isToday = date == LocalDate.now()
                        val dayShifts = shifts.filter { it.startDate == date }
                        val dayLeaves = leaves.filter { it.containsDate(date) }

                        CalendarDayCell(
                            date = date,
                            isSelected = isSelected,
                            isToday = isToday,
                            shifts = dayShifts,
                            leaves = dayLeaves,
                            onSelect = { onSelectDate(date) },
                            modifier = Modifier.weight(1f),
                        )
                    } else {
                        Spacer(Modifier.weight(1f).aspectRatio(1f))
                    }
                }
            }
            if (row < rows - 1) {
                Spacer(Modifier.height(4.dp))
            }
        }
    }
}

@Composable
private fun WeeklyCalendarStrip(
    selectedDate: LocalDate,
    shifts: List<Shift>,
    leaves: List<LeaveEntry>,
    onSelectDate: (LocalDate) -> Unit,
) {
    val c = LocalAppColors.current
    val weekFields = WeekFields.ISO
    val startOfWeek = selectedDate.with(weekFields.dayOfWeek(), 1L)
    val dayNames = listOf("M", "T", "W", "T", "F", "S", "S")

    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceAround) {
            dayNames.forEach { day ->
                Text(
                    day,
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                    fontWeight = FontWeight.SemiBold,
                    color = c.textTertiary,
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Center,
                )
            }
        }
        Spacer(Modifier.height(6.dp))

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            for (i in 0 until 7) {
                val date = startOfWeek.plusDays(i.toLong())
                val isSelected = date == selectedDate
                val isToday = date == LocalDate.now()
                val dayShifts = shifts.filter { it.startDate == date }
                val dayLeaves = leaves.filter { it.containsDate(date) }

                CalendarDayCell(
                    date = date,
                    isSelected = isSelected,
                    isToday = isToday,
                    shifts = dayShifts,
                    leaves = dayLeaves,
                    onSelect = { onSelectDate(date) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun CalendarDayCell(
    date: LocalDate,
    isSelected: Boolean,
    isToday: Boolean,
    shifts: List<Shift>,
    leaves: List<LeaveEntry>,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = LocalAppColors.current
    val hasCompleted = shifts.any { it.status == ShiftStatus.COMPLETED }
    val hasScheduled = shifts.any { it.status == ShiftStatus.SCHEDULED }
    val hasLeave = leaves.isNotEmpty()
    val hasConflict = (hasCompleted || hasScheduled) && hasLeave

    val bg = when {
        isSelected -> c.accent
        isToday -> c.glassStrong
        else -> Color.Transparent
    }

    val textCol = when {
        isSelected -> c.onAccent
        isToday -> c.accent
        else -> c.textPrimary
    }

    val borderMod = when {
        isSelected -> Modifier
        isToday -> Modifier.border(1.dp, c.accent.copy(alpha = 0.55f), CircleShape)
        else -> Modifier
    }

    Box(
        modifier
            .aspectRatio(1f)
            .padding(2.dp)
            .clip(CircleShape)
            .background(bg)
            .then(borderMod)
            .pressable(onClick = onSelect),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                date.dayOfMonth.toString(),
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp),
                fontWeight = if (isSelected || isToday) FontWeight.Bold else FontWeight.Normal,
                color = textCol,
            )
            if (shifts.isNotEmpty() || leaves.isNotEmpty()) {
                Spacer(Modifier.height(2.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (hasConflict) {
                        Box(Modifier.size(4.dp).clip(CircleShape).background(if (isSelected) c.onAccent else c.warning))
                    } else {
                        if (hasCompleted) {
                            Box(Modifier.size(4.dp).clip(CircleShape).background(if (isSelected) c.onAccent else c.positive))
                        }
                        if (hasScheduled) {
                            Box(Modifier.size(4.dp).clip(CircleShape).background(if (isSelected) c.onAccent else c.accent))
                        }
                        if (hasLeave) {
                            Box(Modifier.size(4.dp).clip(CircleShape).background(if (isSelected) c.onAccent else Color(0xFFA855F7)))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CalendarLegendItem(color: Color, label: String) {
    val c = LocalAppColors.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(color),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
            color = c.textSecondary,
        )
    }
}

// -----------------------------------------------------------------------------------------
// DIALOG SHEETS
// -----------------------------------------------------------------------------------------
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ShiftEditorSheet(
    initialShift: Shift,
    workplaces: List<Workplace>,
    onDismiss: () -> Unit,
    onSave: (Shift) -> Unit,
) {
    val c = LocalAppColors.current
    var title by remember { mutableStateOf(initialShift.title) }
    val initialWp = workplaces.firstOrNull { it.id == initialShift.workplaceId } ?: workplaces.firstOrNull()
    var selectedWorkplaceId by remember { mutableStateOf(initialShift.workplaceId ?: initialWp?.id) }
    var selectedWorkplaceName by remember { mutableStateOf(initialShift.workplace ?: initialWp?.name ?: "") }

    val zone = ZoneId.systemDefault()
    val initialStartLocal = Instant.ofEpochMilli(initialShift.startEpochMillis).atZone(zone).toLocalDateTime()
    val initialEndLocal = Instant.ofEpochMilli(initialShift.endEpochMillis).atZone(zone).toLocalDateTime()

    val shiftDate by remember { mutableStateOf(initialStartLocal.toLocalDate()) }
    var startHour by remember { mutableIntStateOf(initialStartLocal.hour) }
    var startMinute by remember { mutableIntStateOf(initialStartLocal.minute) }
    var endHour by remember { mutableIntStateOf(initialEndLocal.hour) }
    var endMinute by remember { mutableIntStateOf(initialEndLocal.minute) }
    var isNextDay by remember { mutableStateOf(initialShift.isOvernight) }
    var breakMinutes by remember { mutableIntStateOf(initialShift.breakDurationMinutes) }
    var notes by remember { mutableStateOf(initialShift.notes) }
    var status by remember { mutableStateOf(initialShift.status) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    GlassSheet(onDismiss) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                if (initialShift.id == 0L) "New work shift" else "Edit shift",
                style = MaterialTheme.typography.headlineSmall,
                color = c.textPrimary,
            )

            Text("Quick shift presets", style = MaterialTheme.typography.labelMedium, color = c.textSecondary, modifier = Modifier.padding(start = 4.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val presets = listOf(
                    Triple("Morning · 07:00–15:30", "Morning Shift", Triple(7, 0, Triple(15, 30, false))),
                    Triple("Midday · 09:00–17:30", "Midday Shift", Triple(9, 0, Triple(17, 30, false))),
                    Triple("Evening · 15:00–23:30", "Evening Shift", Triple(15, 0, Triple(23, 30, false))),
                    Triple("Night · 22:00–06:00", "Night Shift", Triple(22, 0, Triple(6, 0, true))),
                    Triple("Custom", "Custom", Triple(startHour, startMinute, Triple(endHour, endMinute, isNextDay))),
                )
                presets.forEach { (label, presetTitle, timeInfo) ->
                    val isSelected = if (presetTitle == "Custom") {
                        presets.take(4).none {
                            it.second == title && it.third.first == startHour && it.third.second == startMinute && it.third.third.first == endHour && it.third.third.second == endMinute
                        }
                    } else {
                        title == presetTitle && startHour == timeInfo.first && startMinute == timeInfo.second && endHour == timeInfo.third.first && endMinute == timeInfo.third.second
                    }
                    RosterFilterChip(
                        text = label,
                        selected = isSelected,
                        onClick = {
                            if (presetTitle != "Custom") {
                                title = presetTitle
                                startHour = timeInfo.first
                                startMinute = timeInfo.second
                                endHour = timeInfo.third.first
                                endMinute = timeInfo.third.second
                                isNextDay = timeInfo.third.third
                                breakMinutes = 30
                            }
                        },
                    )
                }
            }

            GlassTextField(
                value = title,
                onValueChange = { title = it },
                placeholder = "e.g. Morning Shift",
                label = "Shift title",
            )

            if (workplaces.isNotEmpty()) {
                Text("Workplace", style = MaterialTheme.typography.labelMedium, color = c.textSecondary, modifier = Modifier.padding(start = 4.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    workplaces.forEach { wp ->
                        RosterFilterChip(
                            text = wp.name,
                            selected = selectedWorkplaceId == wp.id,
                            onClick = {
                                selectedWorkplaceId = wp.id
                                selectedWorkplaceName = wp.name
                            },
                        )
                    }
                }
            }

            Text("Working hours", style = MaterialTheme.typography.labelMedium, color = c.textSecondary, modifier = Modifier.padding(start = 4.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TimePickerBox("Start", startHour, startMinute, { h, m -> startHour = h; startMinute = m }, Modifier.weight(1f))
                TimePickerBox("End", endHour, endMinute, { h, m -> endHour = h; endMinute = m }, Modifier.weight(1f))
            }

            Glass(
                Modifier.fillMaxWidth().pressable(onClick = { isNextDay = !isNextDay }),
                shape = RoundedCornerShape(18.dp),
            ) {
                Row(
                    Modifier.padding(horizontal = 16.dp, vertical = 14.dp).fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Overnight shift", style = MaterialTheme.typography.bodyLarge, color = c.textPrimary)
                        Text("Ends on following morning", style = MaterialTheme.typography.bodySmall, color = c.textTertiary)
                    }
                    StatusBadge(
                        if (isNextDay) "Next day" else "Same day",
                        if (isNextDay) c.accent else c.glass,
                        if (isNextDay) c.onAccent else c.textSecondary,
                    )
                }
            }

            Text("Unpaid break", style = MaterialTheme.typography.labelMedium, color = c.textSecondary, modifier = Modifier.padding(start = 4.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(0, 15, 30, 45, 60).forEach { mins ->
                    RosterFilterChip(
                        text = if (mins == 0) "No break" else "${mins}m break",
                        selected = breakMinutes == mins,
                        onClick = { breakMinutes = mins },
                    )
                }
            }

            Text("Shift status", style = MaterialTheme.typography.labelMedium, color = c.textSecondary, modifier = Modifier.padding(start = 4.dp))
            SegmentedToggle(
                options = ShiftStatus.entries,
                selected = status,
                label = { it.label },
                onSelect = { status = it },
                modifier = Modifier.fillMaxWidth(),
            )

            GlassTextField(
                value = notes,
                onValueChange = { notes = it },
                placeholder = "Optional notes or tasks",
                label = "Notes",
                singleLine = false,
            )

            errorMessage?.let {
                Text(it, color = c.negative, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 6.dp))
            }

            PrimaryButton(
                if (initialShift.id == 0L) "Add shift" else "Save changes",
                {
                    if (title.isBlank()) {
                        errorMessage = "Please provide a shift title"
                        return@PrimaryButton
                    }
                    val startInstant = shiftDate.atTime(startHour, startMinute).atZone(zone).toInstant()
                    val endDate = if (isNextDay) shiftDate.plusDays(1) else shiftDate
                    val endInstant = endDate.atTime(endHour, endMinute).atZone(zone).toInstant()

                    if (!endInstant.isAfter(startInstant)) {
                        errorMessage = "End time must be after start time (enable Overnight if next day)"
                        return@PrimaryButton
                    }

                    val totalMins = (endInstant.toEpochMilli() - startInstant.toEpochMilli()) / 60_000
                    if (totalMins <= breakMinutes) {
                        errorMessage = "Break duration cannot exceed shift duration"
                        return@PrimaryButton
                    }

                    onSave(
                        initialShift.copy(
                            title = title.trim(),
                            workplaceId = selectedWorkplaceId,
                            workplace = selectedWorkplaceName.ifBlank { null },
                            startEpochMillis = startInstant.toEpochMilli(),
                            endEpochMillis = endInstant.toEpochMilli(),
                            breakDurationMinutes = breakMinutes,
                            notes = notes.trim(),
                            status = status,
                            updatedAt = System.currentTimeMillis(),
                        ),
                    )
                },
                Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun TimePickerBox(
    label: String,
    hour: Int,
    minute: Int,
    onTimePicked: (Int, Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = LocalAppColors.current
    var showDialog by remember { mutableStateOf(false) }

    Glass(
        modifier = modifier.pressable(onClick = { showDialog = true }),
        shape = RoundedCornerShape(18.dp),
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = c.textSecondary)
            Spacer(Modifier.height(4.dp))
            Text(
                String.format(Locale.US, "%02d:%02d", hour, minute),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = c.textPrimary,
            )
        }
    }

    if (showDialog) {
        var h by remember { mutableIntStateOf(hour) }
        var m by remember { mutableIntStateOf(minute) }
        GlassAlertDialog(
            onDismissRequest = { showDialog = false },
            title = { Text("Set $label time", style = MaterialTheme.typography.titleLarge) },
            text = {
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 12.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    NumberStepper("Hour", h, 0..23) { h = it }
                    Spacer(Modifier.width(20.dp))
                    Text(":", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold, color = c.textPrimary)
                    Spacer(Modifier.width(20.dp))
                    NumberStepper("Minute", m, 0..59, step = 5) { m = it }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    onTimePicked(h, m)
                    showDialog = false
                }) {
                    Text("Done", color = c.accent)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDialog = false }) {
                    Text("Cancel", color = c.textSecondary)
                }
            },
        )
    }
}

@Composable
private fun NumberStepper(label: String, value: Int, range: IntRange, step: Int = 1, onValueChange: (Int) -> Unit) {
    val c = LocalAppColors.current
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = c.textSecondary)
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            RoundButton(
                icon = Lucide.Minus,
                contentDescription = "Decrease",
                onClick = {
                    val next = value - step
                    onValueChange(if (next < range.first) range.last else next)
                },
                size = 36.dp,
            )
            Text(
                String.format(Locale.US, "%02d", value),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = c.textPrimary,
                modifier = Modifier.padding(horizontal = 12.dp),
            )
            RoundButton(
                icon = Lucide.Plus,
                contentDescription = "Increase",
                onClick = {
                    val next = value + step
                    onValueChange(if (next > range.last) range.first else next)
                },
                size = 36.dp,
            )
        }
    }
}

@Composable
private fun ShiftDetailsSheet(
    shift: Shift,
    onDismiss: () -> Unit,
    onEdit: () -> Unit,
    onUpdateStatus: (ShiftStatus) -> Unit,
    onDelete: () -> Unit,
) {
    val c = LocalAppColors.current
    val zone = ZoneId.systemDefault()
    val start = Instant.ofEpochMilli(shift.startEpochMillis).atZone(zone)
    val end = Instant.ofEpochMilli(shift.endEpochMillis).atZone(zone)

    GlassSheet(onDismiss) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(shift.title, style = MaterialTheme.typography.headlineSmall, color = c.textPrimary, modifier = Modifier.weight(1f))
                StatusBadge(shift.status.label, if (shift.status == ShiftStatus.COMPLETED) c.positive else c.accent, c.textPrimary)
            }

            Glass(Modifier.fillMaxWidth(), shape = RoundedCornerShape(22.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (shift.workplace != null) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Workplace", style = MaterialTheme.typography.bodyMedium, color = c.textSecondary)
                            Text(shift.workplace, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = c.textPrimary)
                        }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Start time", style = MaterialTheme.typography.bodyMedium, color = c.textSecondary)
                        Text(start.format(DateTimeFormatter.ofPattern("EEE, d MMM · HH:mm")), style = MaterialTheme.typography.bodyMedium, color = c.textPrimary)
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("End time", style = MaterialTheme.typography.bodyMedium, color = c.textSecondary)
                        Text(end.format(DateTimeFormatter.ofPattern("EEE, d MMM · HH:mm")), style = MaterialTheme.typography.bodyMedium, color = c.textPrimary)
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Unpaid break", style = MaterialTheme.typography.bodyMedium, color = c.textSecondary)
                        Text("${shift.breakDurationMinutes} mins", style = MaterialTheme.typography.bodyMedium, color = c.textPrimary)
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Net working hours", style = MaterialTheme.typography.bodyMedium, color = c.textSecondary)
                        Text(formatMinutesDuration(shift.netWorkingMinutes), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = c.accent)
                    }
                }
            }

            if (shift.notes.isNotBlank()) {
                Glass(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) {
                    Column(Modifier.padding(14.dp)) {
                        Text("Notes", style = MaterialTheme.typography.labelSmall, color = c.textSecondary)
                        Spacer(Modifier.height(4.dp))
                        Text(shift.notes, style = MaterialTheme.typography.bodyMedium, color = c.textPrimary)
                    }
                }
            }

            Text("Update status", style = MaterialTheme.typography.labelMedium, color = c.textSecondary, modifier = Modifier.padding(start = 4.dp))
            SegmentedToggle(
                options = ShiftStatus.entries,
                selected = shift.status,
                label = { it.label },
                onSelect = onUpdateStatus,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(4.dp))
            PrimaryButton("Edit shift", onEdit, Modifier.fillMaxWidth(), icon = Lucide.Pencil)
            SecondaryButton("Delete shift", onDelete, Modifier.fillMaxWidth(), icon = Lucide.Trash2)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LeaveEditorSheet(
    initialLeave: LeaveEntry,
    workplaces: List<Workplace>,
    shifts: List<Shift>,
    onDismiss: () -> Unit,
    onSave: (LeaveEntry) -> Unit,
) {
    val c = LocalAppColors.current
    var type by remember { mutableStateOf(initialLeave.type) }
    var selectedWorkplaceId by remember { mutableStateOf(initialLeave.workplaceId) }
    val startDay by remember { mutableStateOf(LocalDate.ofEpochDay(initialLeave.startDateEpochDay)) }
    val endDay by remember { mutableStateOf(LocalDate.ofEpochDay(initialLeave.endDateEpochDay)) }
    val isFullDay by remember { mutableStateOf(initialLeave.isFullDay) }
    var paymentStatus by remember { mutableStateOf(initialLeave.paymentStatus) }
    var reason by remember { mutableStateOf(initialLeave.reason) }
    var notes by remember { mutableStateOf(initialLeave.notes) }

    val conflictingShifts = shifts.filter {
        val s = it.startDate.toEpochDay()
        s in startDay.toEpochDay()..endDay.toEpochDay()
    }

    GlassSheet(onDismiss) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                if (initialLeave.id == 0L) "Log leave or day off" else "Edit leave record",
                style = MaterialTheme.typography.headlineSmall,
                color = c.textPrimary,
            )

            Text("Leave type", style = MaterialTheme.typography.labelMedium, color = c.textSecondary, modifier = Modifier.padding(start = 4.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                LeaveType.entries.forEach { lt ->
                    RosterFilterChip(
                        text = "${lt.emoji} ${lt.label}",
                        selected = type == lt,
                        onClick = {
                            type = lt
                            if (lt == LeaveType.SICK_LEAVE || lt == LeaveType.ANNUAL_LEAVE || lt == LeaveType.PUBLIC_HOLIDAY) {
                                paymentStatus = LeavePaymentStatus.PAID
                            }
                        },
                    )
                }
            }

            if (workplaces.isNotEmpty()) {
                Text("Workplace (optional)", style = MaterialTheme.typography.labelMedium, color = c.textSecondary, modifier = Modifier.padding(start = 4.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    RosterFilterChip(
                        text = "All / General",
                        selected = selectedWorkplaceId == null,
                        onClick = { selectedWorkplaceId = null },
                    )
                    workplaces.forEach { wp ->
                        RosterFilterChip(
                            text = wp.name,
                            selected = selectedWorkplaceId == wp.id,
                            onClick = { selectedWorkplaceId = wp.id },
                        )
                    }
                }
            }

            Text("Payment status", style = MaterialTheme.typography.labelMedium, color = c.textSecondary, modifier = Modifier.padding(start = 4.dp))
            SegmentedToggle(
                options = LeavePaymentStatus.entries,
                selected = paymentStatus,
                label = { it.label },
                onSelect = { paymentStatus = it },
                modifier = Modifier.fillMaxWidth(),
            )

            GlassTextField(
                value = reason,
                onValueChange = { reason = it },
                placeholder = "e.g. Doctor appointment, family trip",
                label = "Reason (optional)",
            )

            GlassTextField(
                value = notes,
                onValueChange = { notes = it },
                placeholder = "Optional notes or details",
                label = "Notes",
                singleLine = false,
            )

            if (conflictingShifts.isNotEmpty()) {
                Glass(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "⚠️ Warning: ${conflictingShifts.size} scheduled shift(s) overlap with this leave date.",
                            style = MaterialTheme.typography.bodySmall,
                            color = c.warning,
                        )
                    }
                }
            }

            PrimaryButton(
                if (initialLeave.id == 0L) "Save leave" else "Save changes",
                {
                    onSave(
                        initialLeave.copy(
                            type = type,
                            workplaceId = selectedWorkplaceId,
                            startDateEpochDay = startDay.toEpochDay(),
                            endDateEpochDay = endDay.toEpochDay(),
                            isFullDay = isFullDay,
                            paymentStatus = paymentStatus,
                            reason = reason.trim(),
                            notes = notes.trim(),
                            updatedAt = System.currentTimeMillis(),
                        ),
                    )
                },
                Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun LeaveDetailsSheet(
    leave: LeaveEntry,
    workplaces: List<Workplace>,
    onDismiss: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val c = LocalAppColors.current
    val wp = workplaces.firstOrNull { it.id == leave.workplaceId }

    GlassSheet(onDismiss) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("${leave.type.emoji} ${leave.type.label}", style = MaterialTheme.typography.headlineSmall, color = c.textPrimary, modifier = Modifier.weight(1f))
                StatusBadge(leave.paymentStatus.label, if (leave.paymentStatus != LeavePaymentStatus.UNPAID) c.positive else c.glass, c.textPrimary)
            }

            Glass(Modifier.fillMaxWidth(), shape = RoundedCornerShape(22.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Duration", style = MaterialTheme.typography.bodyMedium, color = c.textSecondary)
                        Text("${leave.startDate} to ${leave.endDate} (${leave.dayCount} d)", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = c.textPrimary)
                    }
                    if (wp != null) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Workplace", style = MaterialTheme.typography.bodyMedium, color = c.textSecondary)
                            Text(wp.name, style = MaterialTheme.typography.bodyMedium, color = c.textPrimary)
                        }
                    }
                    if (leave.reason.isNotBlank()) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Reason", style = MaterialTheme.typography.bodyMedium, color = c.textSecondary)
                            Text(leave.reason, style = MaterialTheme.typography.bodyMedium, color = c.textPrimary)
                        }
                    }
                }
            }

            if (leave.notes.isNotBlank()) {
                Glass(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) {
                    Column(Modifier.padding(14.dp)) {
                        Text("Notes", style = MaterialTheme.typography.labelSmall, color = c.textSecondary)
                        Spacer(Modifier.height(4.dp))
                        Text(leave.notes, style = MaterialTheme.typography.bodyMedium, color = c.textPrimary)
                    }
                }
            }

            Spacer(Modifier.height(4.dp))
            PrimaryButton("Edit leave", onEdit, Modifier.fillMaxWidth(), icon = Lucide.Pencil)
            SecondaryButton("Delete leave", onDelete, Modifier.fillMaxWidth(), icon = Lucide.Trash2)
        }
    }
}

@Composable
private fun WorkplaceEditorSheet(
    initialWorkplace: Workplace,
    onDismiss: () -> Unit,
    onSave: (Workplace) -> Unit,
) {
    val c = LocalAppColors.current
    var name by remember { mutableStateOf(initialWorkplace.name) }
    var role by remember { mutableStateOf(initialWorkplace.role) }
    val employmentType by remember { mutableStateOf(initialWorkplace.employmentType) }
    var currency by remember { mutableStateOf(initialWorkplace.currency) }
    var hourlyRateStr by remember { mutableStateOf(initialWorkplace.hourlyRateDisplay) }
    var monthlyThresholdStr by remember { mutableStateOf(initialWorkplace.basicMonthlyHours.toString()) }
    var overtimeMultiplierStr by remember { mutableStateOf(initialWorkplace.overtimeMultiplier.toString()) }
    var allowanceStr by remember { mutableStateOf(if (initialWorkplace.fixedMonthlyAllowanceMinor > 0) (initialWorkplace.fixedMonthlyAllowanceMinor / 100.0).toString() else "") }
    var deductionStr by remember { mutableStateOf(if (initialWorkplace.monthlyDeductionMinor > 0) (initialWorkplace.monthlyDeductionMinor / 100.0).toString() else "") }
    var notes by remember { mutableStateOf(initialWorkplace.notes) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    GlassSheet(onDismiss) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                if (initialWorkplace.id == 0L) "New workplace" else "Edit workplace",
                style = MaterialTheme.typography.headlineSmall,
                color = c.textPrimary,
            )

            GlassTextField(
                value = name,
                onValueChange = { name = it },
                placeholder = "e.g. City Hospital, Cafe Europa",
                label = "Workplace / Employer name",
            )

            GlassTextField(
                value = role,
                onValueChange = { role = it },
                placeholder = "e.g. Staff Nurse, Barista",
                label = "Job title / position",
            )

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                GlassTextField(
                    value = hourlyRateStr,
                    onValueChange = { hourlyRateStr = it },
                    placeholder = "5.73",
                    label = "Basic rate ($currency/h)",
                    keyboardType = KeyboardType.Decimal,
                    modifier = Modifier.weight(1f),
                )
                GlassTextField(
                    value = monthlyThresholdStr,
                    onValueChange = { monthlyThresholdStr = it },
                    placeholder = "173.33",
                    label = "Threshold (h/mo)",
                    keyboardType = KeyboardType.Decimal,
                    modifier = Modifier.weight(1f),
                )
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                GlassTextField(
                    value = overtimeMultiplierStr,
                    onValueChange = { overtimeMultiplierStr = it },
                    placeholder = "1.5",
                    label = "OT multiplier",
                    keyboardType = KeyboardType.Decimal,
                    modifier = Modifier.weight(1f),
                )
                GlassTextField(
                    value = currency,
                    onValueChange = { currency = it.uppercase() },
                    placeholder = "EUR",
                    label = "Currency code",
                    modifier = Modifier.weight(1f),
                )
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                GlassTextField(
                    value = allowanceStr,
                    onValueChange = { allowanceStr = it },
                    placeholder = "0.00",
                    label = "Monthly allowance",
                    keyboardType = KeyboardType.Decimal,
                    modifier = Modifier.weight(1f),
                )
                GlassTextField(
                    value = deductionStr,
                    onValueChange = { deductionStr = it },
                    placeholder = "0.00",
                    label = "Monthly deduction",
                    keyboardType = KeyboardType.Decimal,
                    modifier = Modifier.weight(1f),
                )
            }

            GlassTextField(
                value = notes,
                onValueChange = { notes = it },
                placeholder = "Agreement details, address, contact person...",
                label = "Notes & terms",
                singleLine = false,
            )

            errorMessage?.let {
                Text(it, color = c.negative, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 6.dp))
            }

            PrimaryButton(
                if (initialWorkplace.id == 0L) "Save workplace" else "Save changes",
                {
                    if (name.isBlank()) {
                        errorMessage = "Please enter a workplace name"
                        return@PrimaryButton
                    }
                    val rate = (hourlyRateStr.toDoubleOrNull() ?: 5.73) * 100.0
                    val threshold = monthlyThresholdStr.toDoubleOrNull() ?: 173.33
                    val multiplier = overtimeMultiplierStr.toDoubleOrNull() ?: 1.5
                    val allowance = ((allowanceStr.toDoubleOrNull() ?: 0.0) * 100.0).toLong()
                    val deduction = ((deductionStr.toDoubleOrNull() ?: 0.0) * 100.0).toLong()

                    onSave(
                        initialWorkplace.copy(
                            name = name.trim(),
                            role = role.trim(),
                            employmentType = employmentType,
                            currency = currency.trim().ifBlank { "EUR" },
                            basicHourlyRateCents = rate.toLong(),
                            basicMonthlyHours = threshold,
                            overtimeMultiplier = multiplier,
                            fixedMonthlyAllowanceMinor = allowance,
                            monthlyDeductionMinor = deduction,
                            notes = notes.trim(),
                            updatedAt = System.currentTimeMillis(),
                        ),
                    )
                },
                Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun WorkplaceDetailsSheet(
    workplace: Workplace,
    onDismiss: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val c = LocalAppColors.current

    GlassSheet(onDismiss) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(workplace.name, style = MaterialTheme.typography.headlineSmall, color = c.textPrimary)

            Glass(Modifier.fillMaxWidth(), shape = RoundedCornerShape(22.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Position", style = MaterialTheme.typography.bodyMedium, color = c.textSecondary)
                        Text("${workplace.role.ifBlank { "Employee" }} (${workplace.employmentType})", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = c.textPrimary)
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Basic rate", style = MaterialTheme.typography.bodyMedium, color = c.textSecondary)
                        Text("${workplace.currency} ${workplace.hourlyRateDisplay} / h", style = MaterialTheme.typography.bodyMedium, color = c.textPrimary)
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Monthly threshold", style = MaterialTheme.typography.bodyMedium, color = c.textSecondary)
                        Text("${workplace.basicMonthlyHours} hours", style = MaterialTheme.typography.bodyMedium, color = c.textPrimary)
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Overtime rate", style = MaterialTheme.typography.bodyMedium, color = c.textSecondary)
                        Text("${workplace.overtimeMultiplier}× (${workplace.currency} ${workplace.overtimeHourlyRateDisplay} / h)", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = c.accent)
                    }
                    if (workplace.fixedMonthlyAllowanceMinor > 0) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Allowance", style = MaterialTheme.typography.bodyMedium, color = c.textSecondary)
                            Text(formatMoney(workplace.fixedMonthlyAllowanceMinor, workplace.currency), style = MaterialTheme.typography.bodyMedium, color = c.positive)
                        }
                    }
                }
            }

            if (workplace.notes.isNotBlank()) {
                Glass(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) {
                    Column(Modifier.padding(14.dp)) {
                        Text("Notes & Agreement", style = MaterialTheme.typography.labelSmall, color = c.textSecondary)
                        Spacer(Modifier.height(4.dp))
                        Text(workplace.notes, style = MaterialTheme.typography.bodyMedium, color = c.textPrimary)
                    }
                }
            }

            Spacer(Modifier.height(4.dp))
            PrimaryButton("Edit workplace", onEdit, Modifier.fillMaxWidth(), icon = Lucide.Pencil)
            SecondaryButton("Delete workplace", onDelete, Modifier.fillMaxWidth(), icon = Lucide.Trash2)
        }
    }
}

@Composable
private fun WorkplacePaymentSheet(
    workplace: Workplace,
    currentMonth: YearMonth,
    onDismiss: () -> Unit,
    onSave: (WorkplacePayment) -> Unit,
) {
    val c = LocalAppColors.current
    var amountStr by remember { mutableStateOf("") }
    var reference by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    val paymentDate by remember { mutableStateOf(LocalDate.now()) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    GlassSheet(onDismiss) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("Record payment", style = MaterialTheme.typography.headlineSmall, color = c.textPrimary)
            Text("Employer: ${workplace.name} · Period: ${currentMonth.format(monthHeaderFormat)}", style = MaterialTheme.typography.bodySmall, color = c.accent)

            MoneyField(
                value = amountStr,
                onValueChange = { amountStr = it },
                label = "Actual payment received (${workplace.currency})",
                large = true,
            )

            GlassTextField(
                value = reference,
                onValueChange = { reference = it },
                placeholder = "e.g. OCT-2026-PAYSLIP",
                label = "Payslip ref / Invoice # (optional)",
            )

            GlassTextField(
                value = note,
                onValueChange = { note = it },
                placeholder = "Optional notes",
                label = "Notes",
                singleLine = false,
            )

            errorMessage?.let {
                Text(it, color = c.negative, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 6.dp))
            }

            PrimaryButton(
                "Save payment",
                {
                    val amt = AmountInput.toMinor(amountStr)
                    if (amt == null || amt <= 0) {
                        errorMessage = "Please enter a valid received amount"
                        return@PrimaryButton
                    }
                    onSave(
                        WorkplacePayment(
                            workplaceId = workplace.id,
                            payPeriodMonth = currentMonth.toString(),
                            paymentEpochDay = paymentDate.toEpochDay(),
                            amountMinor = amt,
                            currency = workplace.currency,
                            payslipReference = reference.trim(),
                            note = note.trim(),
                        ),
                    )
                },
                Modifier.fillMaxWidth(),
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TimesheetExportSheet(
    workplaces: List<Workplace>,
    shifts: List<Shift>,
    leaves: List<LeaveEntry>,
    currentMonth: YearMonth,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val c = LocalAppColors.current
    var selectedMonth by remember { mutableStateOf(currentMonth) }
    var selectedWpId by remember { mutableStateOf<Long?>(null) }
    var isExporting by remember { mutableStateOf(false) }

    val filteredShifts = remember(selectedMonth, selectedWpId, shifts) {
        val startMilli = selectedMonth.atDay(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val endMilli = selectedMonth.atEndOfMonth().plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        shifts.filter {
            it.startEpochMillis in startMilli until endMilli &&
                (selectedWpId == null || it.workplaceId == selectedWpId)
        }
    }

    val filteredLeaves = remember(selectedMonth, selectedWpId, leaves) {
        val startDay = selectedMonth.atDay(1).toEpochDay()
        val endDay = selectedMonth.atEndOfMonth().toEpochDay()
        leaves.filter {
            (it.startDateEpochDay <= endDay && it.endDateEpochDay >= startDay) &&
                (selectedWpId == null || it.workplaceId == null || it.workplaceId == selectedWpId)
        }
    }

    val selectedWp = workplaces.firstOrNull { it.id == selectedWpId }
    val monthlySummary = remember(selectedMonth, filteredShifts, filteredLeaves) {
        calculateMonthlySummary(selectedMonth, filteredShifts, filteredLeaves)
    }
    val earningsSummary = remember(selectedWp, selectedMonth, filteredShifts, filteredLeaves) {
        selectedWp?.let { calculateWorkplaceEarnings(it, selectedMonth, filteredShifts, filteredLeaves, emptyList()) }
    }

    GlassSheet(onDismiss) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("Export Timesheet", style = MaterialTheme.typography.headlineSmall, color = c.textPrimary)
            Text(
                "Generate timesheet reports & spreadsheets for your employer or payroll.",
                style = MaterialTheme.typography.bodySmall,
                color = c.textSecondary,
            )

            // Month Selector
            Glass(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) {
                Row(
                    Modifier.padding(horizontal = 14.dp, vertical = 8.dp).fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RoundButton(Lucide.ChevronLeft, "Previous month", { selectedMonth = selectedMonth.minusMonths(1) })
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(selectedMonth.format(monthHeaderFormat), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = c.textPrimary)
                        Text("${filteredShifts.size} shifts · ${filteredLeaves.size} leave records", style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
                    }
                    RoundButton(Lucide.ChevronRight, "Next month", { selectedMonth = selectedMonth.plusMonths(1) })
                }
            }

            // Workplace Filter
            if (workplaces.isNotEmpty()) {
                Text("Employer / Workplace", style = MaterialTheme.typography.labelMedium, color = c.textSecondary, modifier = Modifier.padding(start = 4.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    RosterFilterChip(
                        text = "All Workplaces",
                        selected = selectedWpId == null,
                        onClick = { selectedWpId = null },
                    )
                    workplaces.forEach { wp ->
                        RosterFilterChip(
                            text = wp.name,
                            selected = selectedWpId == wp.id,
                            onClick = { selectedWpId = wp.id },
                        )
                    }
                }
            }

            // Summary preview card
            Glass(Modifier.fillMaxWidth(), shape = RoundedCornerShape(22.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Total Working Hours", style = MaterialTheme.typography.bodyMedium, color = c.textSecondary)
                        Text(formatMinutesDuration(monthlySummary.totalWorkingMinutes), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = c.accent)
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Completed Shifts", style = MaterialTheme.typography.bodyMedium, color = c.textSecondary)
                        Text("${monthlySummary.completedShifts} / ${monthlySummary.totalShifts}", style = MaterialTheme.typography.bodyMedium, color = c.textPrimary)
                    }
                    if (earningsSummary != null && earningsSummary.overtimeHours > 0) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Overtime Hours", style = MaterialTheme.typography.bodyMedium, color = c.textSecondary)
                            Text(formatDecimalHours(earningsSummary.overtimeHours), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = c.positive)
                        }
                    }
                    if (earningsSummary != null) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Estimated Net Pay", style = MaterialTheme.typography.bodyMedium, color = c.textSecondary)
                            Text(formatMoney(earningsSummary.estimatedNetEarningsMinor, earningsSummary.currency), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = c.positive)
                        }
                    }
                }
            }

            Spacer(Modifier.height(4.dp))

            PrimaryButton(
                "Export CSV Spreadsheet (.csv)",
                onClick = {
                    scope.launch {
                        isExporting = true
                        try {
                            val uri = TimesheetExporter.writeTimesheetCsv(
                                context = context,
                                workplace = selectedWp,
                                shifts = filteredShifts,
                                leaves = filteredLeaves,
                                monthlySummary = monthlySummary,
                                earningsSummary = earningsSummary,
                                period = selectedMonth,
                            )
                            context.startActivity(TimesheetExporter.shareCsvIntent(uri, selectedWp?.name, selectedMonth))
                        } catch (e: Exception) {
                            e.printStackTrace()
                        } finally {
                            isExporting = false
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                icon = Lucide.Download,
            )

            SecondaryButton(
                "Share Timesheet Report Text",
                onClick = {
                    val text = TimesheetExporter.formatTimesheetText(
                        workplace = selectedWp,
                        shifts = filteredShifts,
                        leaves = filteredLeaves,
                        monthlySummary = monthlySummary,
                        earningsSummary = earningsSummary,
                        period = selectedMonth,
                    )
                    context.startActivity(TimesheetExporter.shareTextIntent(text, selectedWp?.name, selectedMonth))
                },
                modifier = Modifier.fillMaxWidth(),
                icon = Lucide.Share2,
            )
        }
    }
}

private fun formatMoney(minor: Long, currency: String): String {
    val major = minor / 100.0
    val symbol = when (currency.uppercase()) {
        "EUR" -> "€"
        "USD" -> "$"
        "GBP" -> "£"
        "NPR" -> "Rs "
        else -> "$currency "
    }
    return "$symbol${String.format(Locale.US, "%.2f", major)}"
}
