package com.leftovers.app.data.cohabit

import java.time.LocalDate
import java.time.format.DateTimeFormatter

enum class CohabitRoommate(val displayName: String, val defaultKey: String, val scope: String) {
    OZRIC("Ozric", "leaftover-ozric-key-2026", "USER_OZRIC"),
    PAWAN("Pawan", "leaftover-pawan-key-2026", "USER_PAWAN");

    companion object {
        fun fromName(name: String?): CohabitRoommate =
            when (name?.trim()?.lowercase()) {
                "pawan" -> PAWAN
                else -> OZRIC
            }
    }
}

data class CohabitShoppingItem(
    val id: String,
    val title: String,
    val quantity: String = "1",
    val category: String = "Produce & Groceries",
    val priority: String = "Normal",
    val addedBy: String = "",
    val completed: Boolean = false,
    val notes: String = "",
    val estimatedCost: Double? = null,
    val createdAt: Long = System.currentTimeMillis(),
) {
    val isUrgent: Boolean
        get() = priority.contains("Urgent", ignoreCase = true)
}

data class CohabitShift(
    val id: String,
    val user: String,
    val date: String, // YYYY-MM-DD
    val startTime: String,
    val endTime: String,
    val title: String,
    val employerName: String = "",
    val netHours: Double = 0.0,
    val isSickLeave: Boolean = false,
    val isDayOff: Boolean = false,
    val completed: Boolean = false,
    val notes: String = "",
) {
    val needsPackedMeal: Boolean
        get() = !isDayOff && !isSickLeave && netHours > 0.0

    val parsedDate: LocalDate?
        get() = runCatching { LocalDate.parse(date, DateTimeFormatter.ISO_LOCAL_DATE) }.getOrNull()
}

data class CohabitExpense(
    val id: String,
    val title: String,
    val amount: Double,
    val paidBy: String,
    val splitType: String = "50/50",
    val category: String = "Shared Groceries",
    val notes: String = "",
    val timestamp: Long = System.currentTimeMillis(),
)

data class CohabitLedgerSummary(
    val debtSummary: String,
    val netDifference: Double,
)

data class CohabitPersonalFinance(
    val totalBalanceEur: Double,
    val accountsCount: Int,
    val transactionsCount: Int,
    val accounts: List<Map<String, Any>> = emptyList(),
    val transactions: List<Map<String, Any>> = emptyList(),
)

data class CohabitBridgeSyncResult(
    val success: Boolean,
    val authenticatedOwner: String,
    val encryptionScope: String,
    val shifts: List<CohabitShift>,
    val shoppingList: List<CohabitShoppingItem>,
    val expenses: List<CohabitExpense>,
    val personalFinance: CohabitPersonalFinance?,
    val timestamp: Long = System.currentTimeMillis(),
)

