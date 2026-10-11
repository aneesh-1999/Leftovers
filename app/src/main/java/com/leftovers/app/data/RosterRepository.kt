package com.leftovers.app.data

import kotlinx.coroutines.flow.Flow
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.util.concurrent.TimeUnit

class RosterRepository(
    private val shiftDao: ShiftDao,
    private val workplaceDao: WorkplaceDao,
    private val leaveDao: LeaveDao,
    private val paymentDao: WorkplacePaymentDao,
    private val deletedDao: DeletedDao,
) {
    // Shifts
    val shifts: Flow<List<Shift>> = shiftDao.observeAll()

    fun observeShiftsForMonth(month: YearMonth): Flow<List<Shift>> {
        val zone = ZoneId.systemDefault()
        // Window slightly wider to catch overnight shifts starting around month boundary
        val startMillis = month.atDay(1).minusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val endMillis = month.atEndOfMonth().plusDays(2).atStartOfDay(zone).toInstant().toEpochMilli() - 1
        return shiftDao.observeBetween(startMillis, endMillis)
    }

    suspend fun getShiftById(id: Long): Shift? = shiftDao.getById(id)

    /** Saves a shift. If restoring from Deleted entries, removes it from deleted_shifts. */
    suspend fun saveShift(shift: Shift): Long {
        val id = shiftDao.upsert(shift)
        val targetId = if (shift.id == 0L) id else shift.id
        deletedDao.deleteShifts(listOf(targetId))
        return id
    }

    /** Deletes a shift and keeps a copy in deleted_shifts for restoration from Deleted entries. */
    suspend fun deleteShift(shift: Shift) {
        deletedDao.upsertShift(DeletedShift.from(shift))
        shiftDao.delete(shift)
    }

    suspend fun deleteShiftById(id: Long) {
        val shift = shiftDao.getById(id)
        if (shift != null) deleteShift(shift) else shiftDao.deleteById(id)
    }

    suspend fun setShiftStatus(id: Long, status: ShiftStatus) = shiftDao.updateStatus(id, status)

    // Workplaces
    val workplaces: Flow<List<Workplace>> = workplaceDao.observeAll()
    val activeWorkplaces: Flow<List<Workplace>> = workplaceDao.observeActive()

    suspend fun getWorkplaceById(id: Long): Workplace? = workplaceDao.getById(id)
    suspend fun saveWorkplace(workplace: Workplace): Long = workplaceDao.upsert(workplace)
    suspend fun deleteWorkplace(workplace: Workplace) = workplaceDao.delete(workplace)
    suspend fun setWorkplaceArchived(id: Long, archived: Boolean) = workplaceDao.setArchived(id, archived)

    // Leave entries
    val leaves: Flow<List<LeaveEntry>> = leaveDao.observeAll()

    fun observeLeavesForMonth(month: YearMonth): Flow<List<LeaveEntry>> {
        val startEpochDay = month.atDay(1).minusDays(7).toEpochDay()
        val endEpochDay = month.atEndOfMonth().plusDays(7).toEpochDay()
        return leaveDao.observeBetween(startEpochDay, endEpochDay)
    }

    suspend fun getLeaveById(id: Long): LeaveEntry? = leaveDao.getById(id)

    /** Saves a leave entry. If restoring from Deleted entries, removes it from deleted_leave_entries. */
    suspend fun saveLeave(entry: LeaveEntry): Long {
        val id = leaveDao.upsert(entry)
        val targetId = if (entry.id == 0L) id else entry.id
        deletedDao.deleteLeaves(listOf(targetId))
        return id
    }

    /** Deletes a leave entry and keeps a copy in deleted_leave_entries for restoration from Deleted entries. */
    suspend fun deleteLeave(entry: LeaveEntry) {
        deletedDao.upsertLeave(DeletedLeaveEntry.from(entry))
        leaveDao.delete(entry)
    }

    suspend fun deleteLeaveById(id: Long) {
        val entry = leaveDao.getById(id)
        if (entry != null) deleteLeave(entry) else leaveDao.deleteById(id)
    }

    // Payments & Reconciliation
    val payments: Flow<List<WorkplacePayment>> = paymentDao.observeAll()

    fun observePaymentsForWorkplace(workplaceId: Long): Flow<List<WorkplacePayment>> =
        paymentDao.observeForWorkplace(workplaceId)

    fun observePaymentsForPeriod(workplaceId: Long, month: String): Flow<List<WorkplacePayment>> =
        paymentDao.observeForPeriod(workplaceId, month)

    suspend fun savePayment(payment: WorkplacePayment): Long = paymentDao.upsert(payment)
    suspend fun deletePayment(payment: WorkplacePayment) = paymentDao.delete(payment)

    // Deleted entries recovery
    val deletedShifts: Flow<List<DeletedShift>> = deletedDao.observeShifts()
    val deletedLeaves: Flow<List<DeletedLeaveEntry>> = deletedDao.observeLeaves()

    suspend fun restoreDeletedShifts(ids: List<Long>) {
        val entries = deletedDao.getShiftsByIds(ids)
        if (entries.isEmpty()) return
        entries.forEach { shiftDao.upsert(it.toShift()) }
        deletedDao.deleteShifts(ids)
    }

    suspend fun deleteShiftsForever(ids: List<Long>) {
        deletedDao.deleteShifts(ids)
    }

    suspend fun restoreDeletedLeaves(ids: List<Long>) {
        val entries = deletedDao.getLeavesByIds(ids)
        if (entries.isEmpty()) return
        entries.forEach { leaveDao.upsert(it.toLeaveEntry()) }
        deletedDao.deleteLeaves(ids)
    }

    suspend fun deleteLeavesForever(ids: List<Long>) {
        deletedDao.deleteLeaves(ids)
    }

    suspend fun purgeDeletedRoster(keepDays: Int, now: Long = System.currentTimeMillis()) {
        if (keepDays <= 0) return
        val threshold = now - TimeUnit.DAYS.toMillis(keepDays.toLong())
        deletedDao.deleteShifts(deletedDao.olderShiftsThan(threshold).map { it.id })
        deletedDao.deleteLeaves(deletedDao.olderLeavesThan(threshold).map { it.id })
    }
}
