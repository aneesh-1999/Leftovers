package com.leftovers.app.data.roomexpense

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import kotlin.math.abs

enum class Roommate(val displayName: String) {
    OZRIC("Ozric"),
    PAWAN("Pawan");

    val other: Roommate
        get() = if (this == OZRIC) PAWAN else OZRIC

    companion object {
        fun fromString(name: String?): Roommate =
            when (name?.trim()?.lowercase()) {
                "pawan" -> PAWAN
                else -> OZRIC
            }
    }
}

/**
 * Local Room Entity and domain model for shared household room expenses.
 * Represents documents from the 'expenses' collection in the named Firestore database.
 */
@Entity(
    tableName = "room_expenses",
    indices = [
        Index("timestamp"),
        Index("isSettlement"),
        Index("isCoin"),
        Index("isLendBorrow"),
        Index("status"),
    ],
)
data class RoomExpense(
    @PrimaryKey val id: String,
    val title: String,
    val amount: Double,
    val paidBy: String,
    val splitType: String,
    val category: String,
    val timestamp: Long,
    val splitOzric: Double = 0.0,
    val splitPawan: Double = 0.0,
    val paidOzric: Double = 0.0,
    val paidPawan: Double = 0.0,
    val isCoin: Boolean = false,
    val isSettlement: Boolean = false,
    val isLendBorrow: Boolean = false,
    val paymentMethod: String? = null,
    val itemizedDetails: String? = null,
    val notes: String = "",
    val status: String = "confirmed",
    val createdBy: String? = null,
    val deleteRequested: Boolean = false,
    val deleteRequestedBy: String? = null,
    val lastSyncedAt: Long = System.currentTimeMillis(),
) {
    val date: LocalDate
        get() = Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault()).toLocalDate()

    val yearMonth: YearMonth
        get() = YearMonth.from(date)

    val isPending: Boolean
        get() = status.equals("pending", ignoreCase = true)

    val isConfirmed: Boolean
        get() = !isPending

    fun userShare(roommate: Roommate): Double =
        if (roommate == Roommate.OZRIC) splitOzric else splitPawan

    fun userPaid(roommate: Roommate): Double =
        if (roommate == Roommate.OZRIC) paidOzric else paidPawan
}

/**
 * Ported mathematical summary matching ROOM-EXPANCE Dashboard.tsx logic.
 */
data class RoomExpenseSummary(
    val month: YearMonth,
    val totalMonthlySpend: Double,
    val userPaidThisMonth: Double,
    val userShareThisMonth: Double,
    val netBalance: Double, // positive means Pawan owes Ozric, negative means Ozric owes Pawan
    val totalPawanOwesOzric: Double,
    val totalOzricOwesPawan: Double,
    val perspectiveBalance: Double,
    val isOwed: Boolean,
    val isEven: Boolean,
    val otherRoommate: Roommate,
    val ozricCoinTotal: Double,
    val pawanCoinTotal: Double,
    val totalCoins: Double,
    val pendingCount: Int,
    val totalCount: Int,
)

object RoomExpenseMath {
    /**
     * Computes the net roommate settlement balances and monthly spend metrics.
     * Accurately reproduces the exact math from ROOM-EXPANCE Dashboard.tsx.
     */
    fun calculateSummary(
        expenses: List<RoomExpense>,
        selectedMonth: YearMonth,
        currentUser: Roommate,
    ): RoomExpenseSummary {
        var totalPawanOwesOzric = 0.0
        var totalOzricOwesPawan = 0.0

        // 1. Overall Ledger Balances
        for (expense in expenses) {
            if (expense.isPending) continue
            // Coin Meter contributions are NOT standard split expenses
            if (expense.isCoin) continue

            val payOzric = if (expense.paidOzric > 0.0 || expense.paidPawan > 0.0) {
                expense.paidOzric
            } else if (expense.paidBy.equals("Ozric", ignoreCase = true)) {
                expense.amount
            } else {
                0.0
            }

            val payPawan = if (expense.paidOzric > 0.0 || expense.paidPawan > 0.0) {
                expense.paidPawan
            } else if (expense.paidBy.equals("Pawan", ignoreCase = true)) {
                expense.amount
            } else {
                0.0
            }

            val shareOzric = expense.splitOzric
            val sharePawan = expense.splitPawan

            val pawanOwedDiff = sharePawan - payPawan
            if (pawanOwedDiff > 0.0001) {
                totalPawanOwesOzric += pawanOwedDiff
            } else if (pawanOwedDiff < -0.0001) {
                totalOzricOwesPawan += abs(pawanOwedDiff)
            }
        }

        val netBalance = totalPawanOwesOzric - totalOzricOwesPawan

        var perspectiveBalance = 0.0
        var isOwed = false
        var isEven = false

        if (abs(netBalance) < 0.01) {
            isEven = true
            perspectiveBalance = 0.0
        } else if (currentUser == Roommate.OZRIC) {
            if (netBalance > 0.0) {
                perspectiveBalance = netBalance
                isOwed = true // Pawan owes Ozric
            } else {
                perspectiveBalance = abs(netBalance)
                isOwed = false // Ozric owes Pawan
            }
        } else {
            // Pawan is current user
            if (netBalance < 0.0) {
                perspectiveBalance = abs(netBalance)
                isOwed = true // Ozric owes Pawan
            } else {
                perspectiveBalance = netBalance
                isOwed = false // Pawan owes Ozric
            }
        }

        // 2. Monthly active stats for ordinary shared expenses in the selected month
        val currentMonthExpenses = expenses.filter { exp ->
            if (exp.isSettlement || exp.isLendBorrow || exp.isCoin || exp.isPending) false
            else exp.yearMonth == selectedMonth
        }


        val totalMonthlySpend = currentMonthExpenses.sumOf { it.amount }
        val userPaidThisMonth = currentMonthExpenses.sumOf { it.userPaid(currentUser) }
        val userShareThisMonth = currentMonthExpenses.sumOf { it.userShare(currentUser) }

        // 3. Coin Meter stats
        val confirmedCoins = expenses.filter { it.isCoin && !it.isPending }
        val ozricCoinTotal = confirmedCoins.filter { it.paidBy.equals("Ozric", ignoreCase = true) }.sumOf { it.amount }
        val pawanCoinTotal = confirmedCoins.filter { it.paidBy.equals("Pawan", ignoreCase = true) }.sumOf { it.amount }
        val totalCoins = ozricCoinTotal + pawanCoinTotal

        val pendingCount = expenses.count { it.isPending }

        return RoomExpenseSummary(
            month = selectedMonth,
            totalMonthlySpend = totalMonthlySpend,
            userPaidThisMonth = userPaidThisMonth,
            userShareThisMonth = userShareThisMonth,
            netBalance = netBalance,
            totalPawanOwesOzric = totalPawanOwesOzric,
            totalOzricOwesPawan = totalOzricOwesPawan,
            perspectiveBalance = perspectiveBalance,
            isOwed = isOwed,
            isEven = isEven,
            otherRoommate = currentUser.other,
            ozricCoinTotal = ozricCoinTotal,
            pawanCoinTotal = pawanCoinTotal,
            totalCoins = totalCoins,
            pendingCount = pendingCount,
            totalCount = expenses.size,
        )
    }
}
