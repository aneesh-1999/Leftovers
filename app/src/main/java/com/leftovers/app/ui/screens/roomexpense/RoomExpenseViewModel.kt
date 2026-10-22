package com.leftovers.app.ui.screens.roomexpense

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.leftovers.app.data.SettingsRepository
import com.leftovers.app.data.roomexpense.RoomExpense
import com.leftovers.app.data.roomexpense.RoomExpenseMath
import com.leftovers.app.data.roomexpense.RoomExpenseRepository
import com.leftovers.app.data.roomexpense.RoomExpenseSummary
import com.leftovers.app.data.roomexpense.Roommate
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.YearMonth

enum class RoomExpenseFilter(val label: String) {
    ALL("All"),
    EXPENSES("Expenses"),
    COINS("Coins"),
    SETTLEMENTS("Settlements"),
    LEND_BORROW("Lend / Borrow"),
}

data class RoomExpenseUiState(
    val selectedMonth: YearMonth = YearMonth.now(),
    val selectedFilter: RoomExpenseFilter = RoomExpenseFilter.ALL,
    val searchQuery: String = "",
    val activeRoommate: Roommate = Roommate.OZRIC,
    val includeInPersonal: Boolean = false,
    val isSyncing: Boolean = false,
    val syncError: String? = null,
    val lastSyncTime: Long = 0L,
    val isStale: Boolean = false,
    val allExpenses: List<RoomExpense> = emptyList(),
    val filteredExpenses: List<RoomExpense> = emptyList(),
    val summary: RoomExpenseSummary? = null,
)

@OptIn(ExperimentalCoroutinesApi::class)
class RoomExpenseViewModel(
    private val repository: RoomExpenseRepository,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    private val _selectedMonth = MutableStateFlow(YearMonth.now())
    private val _selectedFilter = MutableStateFlow(RoomExpenseFilter.ALL)
    private val _searchQuery = MutableStateFlow("")
    private val _isSyncing = MutableStateFlow(false)
    private val _syncError = MutableStateFlow<String?>(null)

    private data class FilterState(
        val month: YearMonth,
        val filter: RoomExpenseFilter,
        val query: String,
    )

    private data class SyncStatus(
        val isSyncing: Boolean,
        val syncError: String?,
    )

    private val _filterState = combine(_selectedMonth, _selectedFilter, _searchQuery) { m, f, q ->
        FilterState(m, f, q)
    }

    private val _syncStatus = combine(_isSyncing, _syncError) { syncing, error ->
        SyncStatus(syncing, error)
    }

    val uiState: StateFlow<RoomExpenseUiState> = combine(
        repository.observeExpenses(),
        settingsRepository.settings,
        _filterState,
        _syncStatus,
    ) { expenses, settings, filterState, syncStatus ->
        val month = filterState.month
        val filter = filterState.filter
        val query = filterState.query
        val syncing = syncStatus.isSyncing
        val error = syncStatus.syncError

        val roommate = Roommate.fromString(settings.roomExpenseRoommate)
        val summary = RoomExpenseMath.calculateSummary(expenses, month, roommate)

        val filtered = expenses.filter { exp ->
            // Type Filter
            val matchesType = when (filter) {
                RoomExpenseFilter.ALL -> true
                RoomExpenseFilter.EXPENSES -> !exp.isCoin && !exp.isSettlement && !exp.isLendBorrow
                RoomExpenseFilter.COINS -> exp.isCoin
                RoomExpenseFilter.SETTLEMENTS -> exp.isSettlement
                RoomExpenseFilter.LEND_BORROW -> exp.isLendBorrow
            }

            // Month Filter: if a specific filter like ALL or EXPENSES is active, also consider selected month or search
            val matchesMonth = if (query.isNotBlank() || filter == RoomExpenseFilter.SETTLEMENTS || filter == RoomExpenseFilter.LEND_BORROW) {
                true // show across history when searching or viewing settlement audit trail
            } else {
                exp.yearMonth == month
            }

            // Text search
            val matchesQuery = if (query.isBlank()) true else {
                exp.title.contains(query, ignoreCase = true) ||
                    exp.notes.contains(query, ignoreCase = true) ||
                    exp.category.contains(query, ignoreCase = true) ||
                    exp.paidBy.contains(query, ignoreCase = true) ||
                    exp.id.contains(query, ignoreCase = true)
            }

            matchesType && matchesMonth && matchesQuery
        }

        val lastSync = settings.roomExpenseLastSync
        val isStale = lastSync == 0L || (System.currentTimeMillis() - lastSync) > (24 * 60 * 60 * 1000)

        RoomExpenseUiState(
            selectedMonth = month,
            selectedFilter = filter,
            searchQuery = query,
            activeRoommate = roommate,
            includeInPersonal = settings.roomExpenseIncludeInPersonal,
            isSyncing = syncing,
            syncError = error,
            lastSyncTime = lastSync,
            isStale = isStale,
            allExpenses = expenses,
            filteredExpenses = filtered,
            summary = summary,
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        RoomExpenseUiState(),
    )

    init {
        // Automatically sync on initial screen load
        syncNow()
    }

    fun syncNow() {
        if (_isSyncing.value) return
        viewModelScope.launch {
            _isSyncing.value = true
            _syncError.value = null
            val result = repository.syncFromFirebase()
            _isSyncing.value = false
            result.onFailure { error ->
                _syncError.value = error.localizedMessage ?: "Unable to connect to Firebase. Showing offline cache."
            }
        }
    }

    fun selectMonth(month: YearMonth) {
        _selectedMonth.value = month
    }

    fun nextMonth() {
        _selectedMonth.value = _selectedMonth.value.plusMonths(1)
    }

    fun previousMonth() {
        _selectedMonth.value = _selectedMonth.value.minusMonths(1)
    }

    fun setFilter(filter: RoomExpenseFilter) {
        _selectedFilter.value = filter
    }

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun setRoommate(roommate: Roommate) {
        viewModelScope.launch {
            settingsRepository.setRoomExpenseRoommate(roommate.displayName)
        }
    }

    fun setIncludeInPersonal(include: Boolean) {
        viewModelScope.launch {
            settingsRepository.setRoomExpenseIncludeInPersonal(include)
            if (include) {
                // Re-sync to apply personal import
                repository.syncFromFirebase()
            }
        }
    }

    fun clearError() {
        _syncError.value = null
    }

    fun addExpense(
        title: String,
        amount: Double,
        paidBy: String,
        category: String,
        splitType: String,
        splitOzric: Double,
        splitPawan: Double,
        notes: String = "",
        onComplete: (Boolean, String?) -> Unit,
    ) {
        viewModelScope.launch {
            val result = repository.createExpense(
                title = title,
                amount = amount,
                paidBy = paidBy,
                category = category,
                splitType = splitType,
                splitOzric = splitOzric,
                splitPawan = splitPawan,
                notes = notes,
            )
            result.fold(
                onSuccess = { onComplete(true, null) },
                onFailure = { onComplete(false, it.localizedMessage) },
            )
        }
    }
}
