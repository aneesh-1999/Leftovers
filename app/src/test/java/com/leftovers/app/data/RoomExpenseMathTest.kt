package com.leftovers.app.data

import com.leftovers.app.data.roomexpense.RoomExpense
import com.leftovers.app.data.roomexpense.RoomExpenseMath
import com.leftovers.app.data.roomexpense.Roommate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.YearMonth

class RoomExpenseMathTest {

    @Test
    fun testRoomExpenseBalancesAndCalculations() {
        val month = YearMonth.of(2026, 8)
        // Timestamp for 2026-08-15
        val tsAug15 = 1786553713000L

        val expenses = listOf(
            // Ordinary 50/50 expense paid by Pawan (€20)
            RoomExpense(
                id = "doc1",
                title = "Grocery",
                amount = 20.0,
                paidBy = "Pawan",
                splitType = "50/50",
                category = "Shared Groceries",
                timestamp = tsAug15,
                splitOzric = 10.0,
                splitPawan = 10.0,
                paidOzric = 0.0,
                paidPawan = 20.0,
            ),
            // Ordinary 50/50 expense paid by Ozric (€10)
            RoomExpense(
                id = "doc2",
                title = "Cleaning supplies",
                amount = 10.0,
                paidBy = "Ozric",
                splitType = "50/50",
                category = "Household Supplies",
                timestamp = tsAug15,
                splitOzric = 5.0,
                splitPawan = 5.0,
                paidOzric = 10.0,
                paidPawan = 0.0,
            ),
            // Coin meter record (€9) - MUST BE ISOLATED from standard split calculations
            RoomExpense(
                id = "doc3",
                title = "Electricity Coin €9",
                amount = 9.0,
                paidBy = "Ozric",
                splitType = "50/50",
                category = "Electricity (Coins)",
                timestamp = tsAug15,
                splitOzric = 4.5,
                splitPawan = 4.5,
                isCoin = true,
            ),
            // Lend / Borrow entry (€15) - MUST BE ISOLATED from regular monthly spend
            RoomExpense(
                id = "doc4",
                title = "Pawan lent Ozric €15",
                amount = 15.0,
                paidBy = "Pawan",
                splitType = "custom",
                category = "Other",
                timestamp = tsAug15,
                splitOzric = 15.0,
                splitPawan = 0.0,
                paidOzric = 0.0,
                paidPawan = 15.0,
                isLendBorrow = true,
            ),
            // Settlement entry (€5) - MUST BE ISOLATED from regular monthly spend
            RoomExpense(
                id = "doc5",
                title = "Settled €5",
                amount = 5.0,
                paidBy = "Ozric",
                splitType = "custom",
                category = "Other",
                timestamp = tsAug15,
                splitOzric = 0.0,
                splitPawan = 5.0,
                paidOzric = 5.0,
                paidPawan = 0.0,
                isSettlement = true,
            ),
        )

        // Summary from Pawan's perspective
        val pawanSummary = RoomExpenseMath.calculateSummary(expenses, month, Roommate.PAWAN)

        // Total regular monthly spend excludes Coin, Lend/Borrow, and Settlement (20 + 10 = 30)
        assertEquals(30.0, pawanSummary.totalMonthlySpend, 0.001)

        // Coins are tracked separately in the Coin Meter
        assertEquals(9.0, pawanSummary.totalCoins, 0.001)
        assertEquals(9.0, pawanSummary.ozricCoinTotal, 0.001)
        assertEquals(0.0, pawanSummary.pawanCoinTotal, 0.001)

        // Net balance check:
        // doc1: Pawan paid 20, Pawan share 10 -> diff = -10 (Ozric owes Pawan 10)
        // doc2: Pawan paid 0, Pawan share 5 -> diff = +5 (Pawan owes Ozric 5)
        // doc4 (Lend): Pawan paid 15, Pawan share 0 -> diff = -15 (Ozric owes Pawan 15)
        // doc5 (Settlement): Pawan paid 0, Pawan share 5 -> diff = +5 (Pawan owes Ozric 5)
        // totalPawanOwesOzric = 5 + 5 = 10
        // totalOzricOwesPawan = 10 + 15 = 25
        // netBalance = 10 - 25 = -15 (Ozric owes Pawan 15)
        assertEquals(10.0, pawanSummary.totalPawanOwesOzric, 0.001)
        assertEquals(25.0, pawanSummary.totalOzricOwesPawan, 0.001)
        assertEquals(-15.0, pawanSummary.netBalance, 0.001)
        assertEquals(15.0, pawanSummary.perspectiveBalance, 0.001)
        assertTrue(pawanSummary.isOwed)
        assertFalse(pawanSummary.isEven)

        // Summary from Ozric's perspective
        val ozricSummary = RoomExpenseMath.calculateSummary(expenses, month, Roommate.OZRIC)
        assertEquals(15.0, ozricSummary.perspectiveBalance, 0.001)
        assertFalse(ozricSummary.isOwed) // Ozric owes Pawan
        assertFalse(ozricSummary.isEven)
    }
}
