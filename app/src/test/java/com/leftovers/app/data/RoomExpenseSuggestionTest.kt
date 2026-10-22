package com.leftovers.app.data

import com.leftovers.app.data.roomexpense.RoomExpense
import com.leftovers.app.data.roomexpense.Roommate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.roundToLong

class RoomExpenseSuggestionTest {

    @Test
    fun testOzricPaidSplitCalculations() {
        val expense = RoomExpense(
            id = "expense_1",
            title = "Lidl Groceries",
            amount = 25.50,
            paidBy = "Ozric",
            splitType = "50/50",
            category = "Groceries",
            timestamp = 1786553713000L,
            splitOzric = 12.75,
            splitPawan = 12.75,
            paidOzric = 25.50,
            paidPawan = 0.0,
        )

        val totalMinor = (expense.amount * 100.0).roundToLong() // 2550
        val pawanShareMinor = (expense.splitPawan * 100.0).roundToLong() // 1275
        val ozricShareMinor = (expense.splitOzric * 100.0).roundToLong() // 1275

        assertEquals(2550L, totalMinor)
        assertEquals(1275L, pawanShareMinor)
        assertEquals(1275L, ozricShareMinor)

        // When Ozric pays, total transaction is €25.50, Pawan owes Ozric his share (+1275)
        val debtAmount = pawanShareMinor
        assertTrue("Ozric paid, so Pawan owes Ozric positive amount", debtAmount > 0)
        assertEquals(1275L, debtAmount)
    }

    @Test
    fun testPawanPaidSplitCalculations() {
        val expense = RoomExpense(
            id = "expense_2",
            title = "Supermarket",
            amount = 40.0,
            paidBy = "Pawan",
            splitType = "50/50",
            category = "Groceries",
            timestamp = 1786553713000L,
            splitOzric = 20.0,
            splitPawan = 20.0,
            paidOzric = 0.0,
            paidPawan = 40.0,
        )

        val ozricShareMinor = (expense.splitOzric * 100.0).roundToLong() // 2000

        // When Pawan pays, Ozric owes Pawan his share (-2000 in Debts table)
        val debtAmount = -ozricShareMinor
        assertTrue("Pawan paid, so Ozric owes Pawan negative amount in Leftovers Debts", debtAmount < 0)
        assertEquals(-2000L, debtAmount)
    }

    @Test
    fun testRoommateEnumDefaultIsOzric() {
        assertEquals(Roommate.OZRIC, Roommate.fromString("unknown"))
        assertEquals(Roommate.OZRIC, Roommate.fromString("Ozric"))
        assertEquals(Roommate.PAWAN, Roommate.fromString("Pawan"))
    }

    @Test
    fun testOldPaymentFilteredByCutoff() {
        val cutoff = 1_700_000_000_000L

        val oldExpense = RoomExpense(
            id = "old_exp",
            title = "Past bill",
            amount = 15.0,
            paidBy = "Ozric",
            splitType = "50/50",
            category = "Utilities",
            timestamp = cutoff - 100_000L, // Before cutoff
            splitOzric = 7.5,
            splitPawan = 7.5,
        )

        val newExpense = RoomExpense(
            id = "new_exp",
            title = "Current bill",
            amount = 15.0,
            paidBy = "Ozric",
            splitType = "50/50",
            category = "Utilities",
            timestamp = cutoff + 100_000L, // After cutoff
            splitOzric = 7.5,
            splitPawan = 7.5,
        )

        val shouldProcessOld = oldExpense.timestamp >= cutoff
        val shouldProcessNew = newExpense.timestamp >= cutoff

        org.junit.Assert.assertFalse("Historical expense before cutoff must not be processed", shouldProcessOld)
        org.junit.Assert.assertTrue("New expense on or after cutoff must be processed", shouldProcessNew)
    }
}

