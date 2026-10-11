package com.leftovers.app.data

import android.content.Context
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.leftovers.app.util.BackupManager
import com.leftovers.app.util.BackupPassword
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RosterBackupTest {
    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var settingsRepo: SettingsRepository
    private lateinit var backupManager: BackupManager
    private lateinit var backupFile: File

    @Before
    fun setUp() = runBlocking {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        settingsRepo = SettingsRepository(context)
        backupManager = BackupManager(context, db, settingsRepo, BackupPassword(context))
        backupFile = File(context.cacheDir, "test_backup.json")
    }

    @After
    fun tearDown() {
        db.close()
        if (backupFile.exists()) backupFile.delete()
    }

    @Test
    fun testRosterDataBackupAndRestoreRoundTrip() = runBlocking {
        val workplaceDao = db.workplaceDao()
        val shiftDao = db.shiftDao()
        val leaveDao = db.leaveDao()
        val paymentDao = db.workplacePaymentDao()

        // 1. Insert Workplace
        val wpId = workplaceDao.upsert(
            Workplace(
                id = 1L,
                name = "Central Hospital",
                role = "Senior Nurse",
                address = "123 Health Ave",
                contactPerson = "Dr. Smith",
                contactDetails = "nurse@hospital.org",
                employmentType = "Full-time",
                currency = "EUR",
                basicHourlyRateCents = 650,
                basicMonthlyHours = 160.0,
                overtimeMultiplier = 1.5,
                paidBreaks = true,
                leaveCountsTowardsThreshold = true,
                fixedMonthlyAllowanceMinor = 5000,
                monthlyDeductionMinor = 1000,
                notes = "Day ward",
                archived = false,
            )
        )

        // 2. Insert Shift
        val sId = shiftDao.upsert(
            Shift(
                id = 10L,
                workplaceId = wpId,
                title = "Morning Shift",
                workplace = "Central Hospital",
                startEpochMillis = 1775000000000L,
                endEpochMillis = 1775028800000L,
                breakDurationMinutes = 30,
                notes = "Room 302",
                status = ShiftStatus.COMPLETED,
            )
        )

        // 3. Insert Leave
        val lId = leaveDao.upsert(
            LeaveEntry(
                id = 20L,
                workplaceId = wpId,
                type = LeaveType.ANNUAL_LEAVE,
                startDateEpochDay = LocalDate.of(2026, 10, 10).toEpochDay(),
                endDateEpochDay = LocalDate.of(2026, 10, 15).toEpochDay(),
                isFullDay = true,
                paymentStatus = LeavePaymentStatus.PAID,
                paidRatePercent = 100.0,
                reason = "Vacation",
                notes = "Approved by HR",
            )
        )

        // 4. Insert Payment
        val pId = paymentDao.upsert(
            WorkplacePayment(
                id = 30L,
                workplaceId = wpId,
                payPeriodMonth = "2026-10",
                paymentEpochDay = LocalDate.of(2026, 10, 25).toEpochDay(),
                amountMinor = 245000,
                currency = "EUR",
                payslipReference = "PAY-2026-10-001",
                note = "October Salary",
                reviewed = true,
            )
        )

        val uri = Uri.fromFile(backupFile)
        val exportResult = backupManager.export(uri)
        assertTrue("Export should succeed", exportResult.isSuccess)
        val exportedCount = exportResult.getOrThrow()
        assertTrue("Exported count should be at least 4", exportedCount >= 4)

        // Clear the database
        db.backupDao().clearWorkplacePayments()
        db.backupDao().clearLeaveEntries()
        db.backupDao().clearShifts()
        db.backupDao().clearWorkplaces()

        assertTrue(workplaceDao.getAll().isEmpty())
        assertTrue(shiftDao.getAll().isEmpty())
        assertTrue(leaveDao.getAll().isEmpty())
        assertTrue(paymentDao.getAll().isEmpty())

        // Restore from backup
        val importResult = backupManager.import(uri)
        assertTrue("Import should succeed", importResult.isSuccess)
        val restoredCount = importResult.getOrThrow()
        assertTrue("Restored count should be at least 4", restoredCount >= 4)

        // Verify Workplace
        val workplaces = workplaceDao.getAll()
        assertEquals(1, workplaces.size)
        val restoredWp = workplaces.first()
        assertEquals("Central Hospital", restoredWp.name)
        assertEquals("Senior Nurse", restoredWp.role)
        assertEquals(650L, restoredWp.basicHourlyRateCents)
        assertEquals(160.0, restoredWp.basicMonthlyHours, 0.01)
        assertTrue(restoredWp.paidBreaks)

        // Verify Shift
        val shifts = shiftDao.getAll()
        assertEquals(1, shifts.size)
        val restoredShift = shifts.first()
        assertEquals("Morning Shift", restoredShift.title)
        assertEquals(wpId, restoredShift.workplaceId)
        assertEquals(ShiftStatus.COMPLETED, restoredShift.status)
        assertEquals(30, restoredShift.breakDurationMinutes)

        // Verify Leave
        val leaves = leaveDao.getAll()
        assertEquals(1, leaves.size)
        val restoredLeave = leaves.first()
        assertEquals(LeaveType.ANNUAL_LEAVE, restoredLeave.type)
        assertEquals(wpId, restoredLeave.workplaceId)
        assertEquals(LeavePaymentStatus.PAID, restoredLeave.paymentStatus)
        assertEquals("Vacation", restoredLeave.reason)

        // Verify Payment
        val payments = paymentDao.getAll()
        assertEquals(1, payments.size)
        val restoredPayment = payments.first()
        assertEquals(wpId, restoredPayment.workplaceId)
        assertEquals("2026-10", restoredPayment.payPeriodMonth)
        assertEquals(245000L, restoredPayment.amountMinor)
        assertEquals("PAY-2026-10-001", restoredPayment.payslipReference)
    }

    @Test
    fun testLegacyBackupWithoutRosterImportsGracefully() = runBlocking {
        val legacyJson = """
            {
              "app": "leftovers",
              "version": 1,
              "exportedAt": 1775000000000,
              "categories": [],
              "transactions": []
            }
        """.trimIndent()
        backupFile.writeText(legacyJson)

        val uri = Uri.fromFile(backupFile)
        val importResult = backupManager.import(uri)
        assertTrue("Legacy backup without roster fields should import cleanly", importResult.isSuccess)
    }
}
