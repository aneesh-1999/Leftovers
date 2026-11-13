package com.leftovers.app.ui.screens.cohabit

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.leftovers.app.data.SettingsRepository
import com.leftovers.app.data.cohabit.CohabitBridgeRepository
import com.leftovers.app.data.cohabit.CohabitExpense
import com.leftovers.app.data.cohabit.CohabitLedgerSummary
import com.leftovers.app.data.cohabit.CohabitPersonalFinance
import com.leftovers.app.data.cohabit.CohabitShift
import com.leftovers.app.data.cohabit.CohabitShoppingItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class CohabitUiState(
    val activeRoommate: String = "Ozric",
    val apiKey: String = "",
    val baseUrl: String = "",
    val encryptionScope: String = "USER_OZRIC (AES-256-GCM)",
    val shoppingList: List<CohabitShoppingItem> = emptyList(),
    val shifts: List<CohabitShift> = emptyList(),
    val expenses: List<CohabitExpense> = emptyList(),
    val ledger: CohabitLedgerSummary? = null,
    val personalFinance: CohabitPersonalFinance? = null,
    val isSyncing: Boolean = false,
    val lastSyncTime: Long = 0L,
    val errorMessage: String? = null,
)

class CohabitSyncViewModel(
    private val repository: CohabitBridgeRepository,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    private val _isSubmitting = MutableStateFlow(false)

    private data class BridgeData(
        val shopping: List<CohabitShoppingItem>,
        val shifts: List<CohabitShift>,
        val expenses: List<CohabitExpense>,
        val ledger: CohabitLedgerSummary?,
        val pf: CohabitPersonalFinance?,
    )

    private data class SyncState(
        val isSyncing: Boolean,
        val error: String?,
    )

    private val _bridgeData = combine(
        repository.shoppingList,
        repository.shifts,
        repository.expenses,
        repository.ledger,
        repository.personalFinance,
    ) { sp, sh, ex, ld, pf ->
        BridgeData(sp, sh, ex, ld, pf)
    }

    private val _syncState = combine(
        repository.isSyncing,
        repository.lastError,
        _isSubmitting,
    ) { syncing, error, submitting ->
        SyncState(syncing || submitting, error)
    }

    val uiState: StateFlow<CohabitUiState> = combine(
        settingsRepository.settings,
        _bridgeData,
        _syncState,
    ) { settings, data, sync ->
        val scope = if (settings.cohabitRoommate.equals("Pawan", ignoreCase = true)) {
            "USER_PAWAN (AES-256-GCM)"
        } else {
            "USER_OZRIC (AES-256-GCM)"
        }

        CohabitUiState(
            activeRoommate = settings.cohabitRoommate,
            apiKey = settings.cohabitApiKey,
            baseUrl = settings.cohabitBaseUrl,
            encryptionScope = scope,
            shoppingList = data.shopping,
            shifts = data.shifts,
            expenses = data.expenses,
            ledger = data.ledger,
            personalFinance = data.pf,
            isSyncing = sync.isSyncing,
            lastSyncTime = settings.cohabitLastSync,
            errorMessage = sync.error,
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        CohabitUiState()
    )

    init {
        // Initial silent sync attempt on screen open
        viewModelScope.launch {
            repository.syncAll()
        }
    }

    fun syncNow() {
        viewModelScope.launch {
            repository.syncAll()
        }
    }

    fun addShoppingItem(
        title: String,
        quantity: String,
        category: String,
        priority: String,
        notes: String,
        estimatedCost: Double?
    ) {
        viewModelScope.launch {
            _isSubmitting.value = true
            try {
                repository.addShoppingItem(title, quantity, category, priority, notes, estimatedCost)
            } finally {
                _isSubmitting.value = false
            }
        }
    }

    fun markItemCompleted(id: String, completed: Boolean) {
        viewModelScope.launch {
            repository.markShoppingItemCompleted(id, completed)
        }
    }

    fun deleteShoppingItem(id: String) {
        viewModelScope.launch {
            repository.deleteShoppingItem(id)
        }
    }

    fun logExpense(
        title: String,
        amount: Double,
        category: String = "Shared Groceries",
        notes: String = ""
    ) {
        viewModelScope.launch {
            _isSubmitting.value = true
            try {
                repository.logExpense(title, amount, category, notes)
            } finally {
                _isSubmitting.value = false
            }
        }
    }

    fun switchRoommate(roommate: String) {
        viewModelScope.launch {
            repository.switchRoommate(roommate)
            repository.syncAll()
        }
    }

    fun updateCredentials(apiKey: String, baseUrl: String) {
        viewModelScope.launch {
            repository.updateCustomCredentials(apiKey, baseUrl)
            repository.syncAll()
        }
    }

    fun clearError() {
        repository.clearError()
    }
}
