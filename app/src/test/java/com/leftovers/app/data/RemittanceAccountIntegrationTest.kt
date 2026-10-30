package com.leftovers.app.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RemittanceAccountIntegrationTest {
    private lateinit var db: AppDatabase
    private lateinit var accountRepo: AccountRepository
    private lateinit var remittanceRepo: RemittanceRepository
    private lateinit var settingsRepo: SettingsRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        accountRepo = AccountRepository(db.accountDao(), db.transferDao(), db.transactionDao(), db.debtDao())
        settingsRepo = SettingsRepository(context)
        remittanceRepo = RemittanceRepository(
            db.remittanceDao(),
            db.recipientDao(),
            db.remittanceStatusHistoryDao(),
            db.transactionDao(),
            db.categoryDao(),
            settingsRepo,
            db,
        )
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun tapTapSendIsPresentInStandardProviders() {
        assertTrue(StandardRemittanceProviders.contains("TapTap Send"))
    }

    @Test
    fun remittanceDeductsFromLinkedAccountImmediately() = runBlocking {
        val accId = accountRepo.saveAccount(
            Account(name = "Revolut", icon = "wallet", color = 0xFF000000, openingMinor = 100_000, currency = "EUR")
        )

        val recipientId = remittanceRepo.saveRecipient(
            Recipient(name = "Family in Nepal", country = "Nepal")
        )

        val remId = remittanceRepo.saveRemittance(
            remittance = Remittance(
                recipientId = recipientId,
                recipientName = "Family in Nepal",
                sendAmountMinor = 20_000,
                sendCurrency = "EUR",
                expectedReceiveAmountMinor = 2_800_000,
                receiveCurrency = "NPR",
                exchangeRate = 140.0,
                feeAmountMinor = 500,
                feeCurrency = "EUR",
                feeMethod = FeeMethod.SEPARATE,
                provider = "TapTap Send",
                status = RemittanceStatus.SENT,
                sendingAccountId = accId,
                transferDateEpochDay = LocalDate.now().toEpochDay(),
            ),
            createFinanceTx = true,
        )

        val accounts = accountRepo.accounts.first()
        val revolut = accounts.first { it.id == accId }
        assertEquals(79_500L, revolut.balanceMinor)

        val savedRem = remittanceRepo.getRemittanceById(remId)
        assertNotNull(savedRem)
        val txId = savedRem!!.financeTransactionId
        assertNotNull(txId)

        val tx = db.transactionDao().getById(txId!!)
        assertNotNull(tx)
        assertEquals(20_500L, tx!!.amountMinor)
        assertEquals(accId, tx.accountId)
        assertEquals(TxType.EXPENSE, tx.type)
    }

    @Test
    fun cancellingRemittanceRestoresAccountBalance() = runBlocking {
        val accId = accountRepo.saveAccount(
            Account(name = "Cash", icon = "wallet", color = 0xFF000000, openingMinor = 50_000, currency = "EUR")
        )
        val recipientId = remittanceRepo.saveRecipient(
            Recipient(name = "Test Recipient", country = "Nepal")
        )

        val remId = remittanceRepo.saveRemittance(
            remittance = Remittance(
                recipientId = recipientId,
                recipientName = "Test Recipient",
                sendAmountMinor = 10_000,
                sendCurrency = "EUR",
                expectedReceiveAmountMinor = 1_400_000,
                receiveCurrency = "NPR",
                exchangeRate = 140.0,
                feeAmountMinor = 0,
                feeCurrency = "EUR",
                feeMethod = FeeMethod.SEPARATE,
                provider = "Wise",
                status = RemittanceStatus.SENT,
                sendingAccountId = accId,
                transferDateEpochDay = LocalDate.now().toEpochDay(),
            ),
            createFinanceTx = true,
        )

        assertEquals(40_000L, accountRepo.accounts.first().first { it.id == accId }.balanceMinor)

        remittanceRepo.updateStatus(remId, RemittanceStatus.CANCELLED)

        assertEquals(50_000L, accountRepo.accounts.first().first { it.id == accId }.balanceMinor)

        val remAfterCancel = remittanceRepo.getRemittanceById(remId)
        assertNull(remAfterCancel?.financeTransactionId)
    }

    @Test
    fun deletingRemittanceRestoresAccountBalance() = runBlocking {
        val accId = accountRepo.saveAccount(
            Account(name = "Bank", icon = "landmark", color = 0xFF000000, openingMinor = 100_000, currency = "EUR")
        )
        val recipientId = remittanceRepo.saveRecipient(
            Recipient(name = "Test Recipient", country = "Nepal")
        )

        val remId = remittanceRepo.saveRemittance(
            remittance = Remittance(
                recipientId = recipientId,
                recipientName = "Test Recipient",
                sendAmountMinor = 30_000,
                sendCurrency = "EUR",
                expectedReceiveAmountMinor = 4_200_000,
                receiveCurrency = "NPR",
                exchangeRate = 140.0,
                feeAmountMinor = 0,
                feeCurrency = "EUR",
                feeMethod = FeeMethod.SEPARATE,
                provider = "TapTap Send",
                status = RemittanceStatus.SENT,
                sendingAccountId = accId,
                transferDateEpochDay = LocalDate.now().toEpochDay(),
            ),
            createFinanceTx = true,
        )

        assertEquals(70_000L, accountRepo.accounts.first().first { it.id == accId }.balanceMinor)

        val savedRem = remittanceRepo.getRemittanceById(remId)!!
        remittanceRepo.deleteRemittance(savedRem, deleteLinkedFinanceTx = true)

        assertEquals(100_000L, accountRepo.accounts.first().first { it.id == accId }.balanceMinor)
    }

    @Test
    fun customProviderCannotBeDeletedWhenReferencedByTransactions() = runBlocking {
        val customName = "IME Pay Custom"
        remittanceRepo.addCustomProvider(customName)

        val recipientId = remittanceRepo.saveRecipient(
            Recipient(name = "Brother", country = "Nepal")
        )

        remittanceRepo.saveRemittance(
            remittance = Remittance(
                recipientId = recipientId,
                recipientName = "Brother",
                sendAmountMinor = 15_000,
                sendCurrency = "EUR",
                expectedReceiveAmountMinor = 2_100_000,
                receiveCurrency = "NPR",
                exchangeRate = 140.0,
                feeAmountMinor = 0,
                feeCurrency = "EUR",
                feeMethod = FeeMethod.SEPARATE,
                provider = customName,
                status = RemittanceStatus.SENT,
                transferDateEpochDay = LocalDate.now().toEpochDay(),
            ),
        )

        val refCount = remittanceRepo.countRemittancesForProvider(customName)
        assertEquals(1, refCount)

        val deleted = remittanceRepo.deleteCustomProvider(customName)
        assertFalse(deleted)
    }
}
