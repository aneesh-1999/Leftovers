package com.leftovers.app.data.cohabit

import android.content.Context
import android.util.Log
import com.leftovers.app.data.SettingsRepository
import com.leftovers.app.data.roomexpense.RoomExpense
import com.leftovers.app.data.roomexpense.RoomExpenseDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.util.UUID

class CohabitBridgeRepository(
    private val context: Context,
    private val service: CohabitBridgeService,
    private val settingsRepository: SettingsRepository,
    private val roomExpenseDao: RoomExpenseDao?,
) {
    companion object {
        private const val TAG = "CohabitBridgeRepo"
    }

    private val _shoppingList = MutableStateFlow<List<CohabitShoppingItem>>(emptyList())
    val shoppingList: Flow<List<CohabitShoppingItem>> = _shoppingList.asStateFlow()

    private val _shifts = MutableStateFlow<List<CohabitShift>>(emptyList())
    val shifts: Flow<List<CohabitShift>> = _shifts.asStateFlow()

    private val _expenses = MutableStateFlow<List<CohabitExpense>>(emptyList())
    val expenses: Flow<List<CohabitExpense>> = _expenses.asStateFlow()

    private val _ledger = MutableStateFlow<CohabitLedgerSummary?>(null)
    val ledger: Flow<CohabitLedgerSummary?> = _ledger.asStateFlow()

    private val _personalFinance = MutableStateFlow<CohabitPersonalFinance?>(null)
    val personalFinance: Flow<CohabitPersonalFinance?> = _personalFinance.asStateFlow()

    private val _isSyncing = MutableStateFlow(false)
    val isSyncing: Flow<Boolean> = _isSyncing.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: Flow<String?> = _lastError.asStateFlow()

    suspend fun syncAll(): Result<CohabitBridgeSyncResult> = withContext(Dispatchers.IO) {
        _isSyncing.value = true
        _lastError.value = null
        try {
            val settings = settingsRepository.settings.first()
            val baseUrl = settings.cohabitBaseUrl
            val apiKey = settings.cohabitApiKey

            // Try master bridge sync first
            val masterResult = service.getMasterSync(baseUrl, apiKey)
            if (masterResult.isSuccess) {
                val data = masterResult.getOrThrow()
                _shoppingList.value = data.shoppingList
                _shifts.value = data.shifts
                _expenses.value = data.expenses
                _personalFinance.value = data.personalFinance
                settingsRepository.setCohabitLastSync(System.currentTimeMillis())
                return@withContext Result.success(data)
            }

            // Fallback: Individual endpoint sync if master sync is not supported or 404
            Log.d(TAG, "Master sync returned failure, trying individual endpoints: ${masterResult.exceptionOrNull()?.message}")
            val shoppingResult = service.getShoppingList(baseUrl, apiKey, completed = false)
            val shiftsResult = service.getShifts(baseUrl, apiKey)
            val expensesResult = service.getExpenses(baseUrl, apiKey)
            val pfResult = service.getPersonalFinance(baseUrl, apiKey)

            val shopping = shoppingResult.getOrDefault(emptyList())
            val shiftsList = shiftsResult.getOrDefault(emptyList())
            val (expensesList, ledgerSummary) = expensesResult.getOrDefault(Pair(emptyList(), CohabitLedgerSummary("Balanced", 0.0)))
            val pf = pfResult.getOrNull()

            _shoppingList.value = shopping
            _shifts.value = shiftsList
            _expenses.value = expensesList
            _ledger.value = ledgerSummary
            _personalFinance.value = pf

            val activeRoommate = settings.cohabitRoommate
            val scope = if (activeRoommate.equals("Pawan", ignoreCase = true)) "USER_PAWAN" else "USER_OZRIC"

            val compositeResult = CohabitBridgeSyncResult(
                success = shoppingResult.isSuccess || shiftsResult.isSuccess || expensesResult.isSuccess,
                authenticatedOwner = activeRoommate,
                encryptionScope = scope,
                shifts = shiftsList,
                shoppingList = shopping,
                expenses = expensesList,
                personalFinance = pf,
            )

            if (!compositeResult.success) {
                val errorMsg = masterResult.exceptionOrNull()?.message 
                    ?: shoppingResult.exceptionOrNull()?.message 
                    ?: "Could not reach Cohabit Bridge API."
                _lastError.value = errorMsg
                return@withContext Result.failure(Exception(errorMsg))
            }

            settingsRepository.setCohabitLastSync(System.currentTimeMillis())
            Result.success(compositeResult)
        } catch (e: Exception) {
            Log.e(TAG, "Sync failed with exception", e)
            _lastError.value = e.message ?: "Sync failed"
            Result.failure(e)
        } finally {
            _isSyncing.value = false
        }
    }

    suspend fun addShoppingItem(
        title: String,
        quantity: String,
        category: String,
        priority: String,
        notes: String,
        estimatedCost: Double?
    ): Result<CohabitShoppingItem> = withContext(Dispatchers.IO) {
        val settings = settingsRepository.settings.first()
        val result = service.addShoppingItem(
            baseUrl = settings.cohabitBaseUrl,
            apiKey = settings.cohabitApiKey,
            title = title,
            quantity = quantity,
            category = category,
            priority = priority,
            notes = notes,
            estimatedCost = estimatedCost,
        )

        // Optimistically update or prepend local state on success
        result.onSuccess { item ->
            _shoppingList.value = listOf(item) + _shoppingList.value
        }
        result
    }

    suspend fun markShoppingItemCompleted(id: String, completed: Boolean): Result<CohabitShoppingItem> = withContext(Dispatchers.IO) {
        val settings = settingsRepository.settings.first()
        val result = service.updateShoppingItem(
            baseUrl = settings.cohabitBaseUrl,
            apiKey = settings.cohabitApiKey,
            id = id,
            completed = completed,
        )

        result.onSuccess { updated ->
            _shoppingList.value = _shoppingList.value.map { if (it.id == id) updated else it }
                .filter { !it.completed } // Keep active items in the active view
        }
        result
    }

    suspend fun deleteShoppingItem(id: String): Result<Boolean> = withContext(Dispatchers.IO) {
        val settings = settingsRepository.settings.first()
        val result = service.deleteShoppingItem(
            baseUrl = settings.cohabitBaseUrl,
            apiKey = settings.cohabitApiKey,
            id = id,
        )

        result.onSuccess {
            _shoppingList.value = _shoppingList.value.filter { it.id != id }
        }
        result
    }

    suspend fun logExpense(
        title: String,
        amount: Double,
        category: String = "Shared Groceries",
        notes: String = ""
    ): Result<CohabitExpense> = withContext(Dispatchers.IO) {
        val settings = settingsRepository.settings.first()
        val result = service.logExpense(
            baseUrl = settings.cohabitBaseUrl,
            apiKey = settings.cohabitApiKey,
            title = title,
            amount = amount,
            category = category,
            notes = notes,
        )

        result.onSuccess { expense ->
            _expenses.value = listOf(expense) + _expenses.value

            // Also mirror to local Room Expense DB if available
            roomExpenseDao?.let { dao ->
                val half = amount / 2.0
                val paidBy = settings.cohabitRoommate
                val localExpense = RoomExpense(
                    id = "cohabit_${UUID.randomUUID()}",
                    title = title,
                    amount = amount,
                    paidBy = paidBy,
                    splitType = "50/50",
                    category = category,
                    timestamp = System.currentTimeMillis(),
                    splitOzric = half,
                    splitPawan = half,
                    paidOzric = if (paidBy.equals("Ozric", ignoreCase = true)) amount else 0.0,
                    paidPawan = if (paidBy.equals("Pawan", ignoreCase = true)) amount else 0.0,
                    notes = notes.ifBlank { "Via Cohabit Encrypted Bridge" },
                    status = "confirmed",
                )
                dao.upsertAll(listOf(localExpense))
            }
        }
        result
    }

    suspend fun switchRoommate(roommate: String) {
        val defaultKey = if (roommate.equals("Pawan", ignoreCase = true)) {
            CohabitRoommate.PAWAN.defaultKey
        } else {
            CohabitRoommate.OZRIC.defaultKey
        }
        settingsRepository.setCohabitRoommate(roommate)
        settingsRepository.setCohabitApiKey(defaultKey)
    }

    suspend fun updateCustomCredentials(apiKey: String, baseUrl: String) {
        settingsRepository.setCohabitApiKey(apiKey)
        settingsRepository.setCohabitBaseUrl(baseUrl)
    }

    fun clearError() {
        _lastError.value = null
    }
}
