package com.leftovers.app.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests the connection between Money Owed (debts) and user accounts:
 * - Lending money deducts from the linked account.
 * - Borrowing money adds to the linked account.
 * - Debts without a linked account do not affect account balances.
 * - Settling or deleting debts restores the account balance.
 * - Accounts with linked debts cannot be accidentally deleted.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DebtAccountIntegrationTest {
    private lateinit var db: AppDatabase
    private lateinit var accountRepo: AccountRepository
    private lateinit var planningRepo: PlanningRepository

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        accountRepo = AccountRepository(db.accountDao(), db.transferDao(), db.transactionDao(), db.debtDao())
        planningRepo = PlanningRepository(db)
    }

    @After fun tearDown() = db.close()

    @Test fun debtWithNoAccountDoesNotAffectAccountBalance() = runBlocking {
        val cashId = accountRepo.saveAccount(Account(name = "Cash", icon = "wallet", color = 0xFF000000, openingMinor = 100_000))
        planningRepo.saveDebt(Debt(person = "Alice", amountMinor = 30_000, note = "Coffee", accountId = null))

        val accounts = accountRepo.accounts.first()
        val cash = accounts.first { it.id == cashId }
        assertEquals(100_000L, cash.balanceMinor)
    }

    @Test fun lendingMoneyDeductsFromLinkedAccount() = runBlocking {
        val cashId = accountRepo.saveAccount(Account(name = "Cash", icon = "wallet", color = 0xFF000000, openingMinor = 100_000))
        val bankId = accountRepo.saveAccount(Account(name = "Bank", icon = "landmark", color = 0xFF000000, openingMinor = 200_000))

        // Lent 30,000 cents (€300.00) in Cash to Bob
        planningRepo.saveDebt(Debt(person = "Bob", amountMinor = 30_000, note = "Dinner", accountId = cashId))

        val accounts = accountRepo.accounts.first()
        assertEquals(70_000L, accounts.first { it.id == cashId }.balanceMinor)
        assertEquals(200_000L, accounts.first { it.id == bankId }.balanceMinor)
    }

    @Test fun borrowingMoneyAddsToLinkedAccount() = runBlocking {
        val bankId = accountRepo.saveAccount(Account(name = "Bank", icon = "landmark", color = 0xFF000000, openingMinor = 50_000))

        // Borrowed 20,000 cents from Charlie into Bank (amountMinor is negative when we owe them)
        planningRepo.saveDebt(Debt(person = "Charlie", amountMinor = -20_000, note = "Loan", accountId = bankId))

        val accounts = accountRepo.accounts.first()
        assertEquals(70_000L, accounts.first { it.id == bankId }.balanceMinor)
    }

    @Test fun settlingDebtRestoresAccountBalance() = runBlocking {
        val cashId = accountRepo.saveAccount(Account(name = "Cash", icon = "wallet", color = 0xFF000000, openingMinor = 100_000))
        planningRepo.saveDebt(Debt(person = "David", amountMinor = 40_000, note = "Groceries", accountId = cashId))

        assertEquals(60_000L, accountRepo.accounts.first().first { it.id == cashId }.balanceMinor)

        // Settle up with David (paid back)
        planningRepo.settle("David")

        assertEquals(100_000L, accountRepo.accounts.first().first { it.id == cashId }.balanceMinor)
    }

    @Test fun deletingDebtRestoresAccountBalance() = runBlocking {
        val cashId = accountRepo.saveAccount(Account(name = "Cash", icon = "wallet", color = 0xFF000000, openingMinor = 100_000))
        val debt = Debt(id = 1, person = "Eve", amountMinor = 25_000, note = "Tickets", accountId = cashId)
        planningRepo.saveDebt(debt)

        assertEquals(75_000L, accountRepo.accounts.first().first { it.id == cashId }.balanceMinor)

        planningRepo.deleteDebt(debt)

        assertEquals(100_000L, accountRepo.accounts.first().first { it.id == cashId }.balanceMinor)
    }

    @Test fun accountDeletionIsBlockedWhenDebtsAreLinked() = runBlocking {
        val wallet = Account(id = 1, name = "Wallet", icon = "wallet", color = 0xFF000000, openingMinor = 10_000)
        accountRepo.saveAccount(wallet)
        val debt = Debt(id = 1, person = "Frank", amountMinor = 5_000, note = "Snacks", accountId = wallet.id)
        planningRepo.saveDebt(debt)

        val usedCount = accountRepo.deleteAccount(wallet)
        assertEquals(1, usedCount)
        assertEquals(1, accountRepo.accounts.first().size)

        // Remove debt and try deleting again
        planningRepo.deleteDebt(debt)
        val usedAfter = accountRepo.deleteAccount(wallet)
        assertEquals(0, usedAfter)
        assertEquals(0, accountRepo.accounts.first().size)
    }
}
