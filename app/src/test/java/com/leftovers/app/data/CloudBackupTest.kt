package com.leftovers.app.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.leftovers.app.data.cloud.FirebaseConfig
import com.leftovers.app.util.BackupCrypto
import com.leftovers.app.util.BackupManager
import com.leftovers.app.util.BackupPassword
import com.leftovers.app.util.BackupPasswordException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CloudBackupTest {
    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var settingsRepo: SettingsRepository
    private lateinit var backupPassword: BackupPassword
    private lateinit var backupManager: BackupManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        settingsRepo = SettingsRepository(context)
        backupPassword = BackupPassword(context)
        backupManager = BackupManager(context, db, settingsRepo, backupPassword)
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun testGzipCompressionDecompressionRoundTrip() {
        val sampleJson = """{"app":"leftovers","version":1,"sample":"Hello Cloud Backup! 🔥"}"""
        val bos = ByteArrayOutputStream()
        GZIPOutputStream(bos).use { it.write(sampleJson.toByteArray(Charsets.UTF_8)) }
        val compressedB64 = Base64.getEncoder().encodeToString(bos.toByteArray())

        val decompressedBytes = Base64.getDecoder().decode(compressedB64)
        val decompressedText = GZIPInputStream(ByteArrayInputStream(decompressedBytes)).bufferedReader(Charsets.UTF_8).readText()

        assertEquals(sampleJson, decompressedText)
    }

    @Test
    fun testCreatePayloadAndRestoreRoundTrip() = runBlocking {
        // Insert sample account and transaction
        val accountDao = db.accountDao()
        val categoryDao = db.categoryDao()
        val txDao = db.transactionDao()

        categoryDao.upsert(Category(id = 1L, name = "Groceries", emoji = "🛒", color = 0xFF00FF00, type = TxType.EXPENSE))
        accountDao.upsert(Account(id = 10L, name = "Main Checking", icon = "🏦", color = 0xFF0000FF, openingMinor = 50000L))
        txDao.upsert(Transaction(id = 100L, amountMinor = 2500L, type = TxType.EXPENSE, categoryId = 1L, epochDay = 20000L, note = "Supermarket", accountId = 10L))

        // Create payload
        val payload = backupManager.createBackupPayload(encryptIfPasswordSet = false)
        assertFalse(payload.isEncrypted)
        assertTrue(payload.totalItems >= 1)
        assertTrue(payload.jsonString.contains("Groceries"))
        assertTrue(payload.jsonString.contains("Main Checking"))

        // Clear local database
        txDao.deleteAll()
        db.backupDao().clearAccounts()
        db.backupDao().clearCategories()
        assertEquals(0, txDao.getAll().size)

        // Restore from payload json text
        val result = backupManager.restoreFromJsonText(payload.jsonString)
        assertTrue(result.isSuccess)

        val restoredTx = txDao.getAll()
        assertEquals(1, restoredTx.size)
        assertEquals(2500L, restoredTx.first().amountMinor)
        assertEquals("Supermarket", restoredTx.first().note)
    }

    @Test
    fun testEncryptedPayloadRequiresPasswordToRestore() = runBlocking {
        val rawJson = """{"app":"leftovers","version":1,"categories":[{"id":2,"name":"Dining","icon":"🍕","color":4294901760,"type":"EXPENSE","budget":null,"addsToBudget":false}],"transactions":[{"id":101,"amount":1500,"type":"EXPENSE","categoryId":2,"day":20000,"note":"Pizza","createdAt":1000000,"accountId":null,"receipt":null,"foreignAmount":null,"foreignCurrency":null}]}"""
        val encryptedJson = BackupCrypto.encrypt(rawJson, "SecretPass123!").toString()

        // Clear db
        db.transactionDao().deleteAll()

        // Restore without password should fail with BackupPasswordException
        try {
            backupManager.restoreFromJsonText(encryptedJson).getOrThrow()
            fail("Expected BackupPasswordException")
        } catch (e: BackupPasswordException) {
            // Expected
        }

        // Restore with wrong password should fail
        try {
            backupManager.restoreFromJsonText(encryptedJson, "WrongPassword").getOrThrow()
            fail("Expected decryption failure")
        } catch (e: Exception) {
            // Expected
        }

        // Restore with correct password
        val restored = backupManager.restoreFromJsonText(encryptedJson, "SecretPass123!").getOrThrow()
        assertTrue(restored >= 1)
        assertEquals(1, db.transactionDao().getAll().size)
        assertEquals("Pizza", db.transactionDao().getAll().first().note)
    }

    @Test
    fun testFirebasePathsAndIsolation() {
        assertEquals("leaftovers", FirebaseConfig.PROJECT_ID)
        assertEquals("(default)", FirebaseConfig.DATABASE_ID)
        assertEquals("user_backups", FirebaseConfig.COLLECTION_USER_BACKUPS)
        assertEquals("latest", FirebaseConfig.DOC_LATEST)
    }
}
