package com.leftovers.app.data

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.PrimaryKey
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** An entry the user deleted, kept so it can be restored. Same id as it had before. */
@Entity(tableName = "deleted_transactions")
data class DeletedTransaction(
    @PrimaryKey val id: Long,
    val amountMinor: Long,
    val type: TxType,
    val categoryId: Long,
    val epochDay: Long,
    val note: String,
    val createdAt: Long,
    val accountId: Long?,
    val receiptPath: String?,
    val deletedAt: Long,
) {
    fun toTransaction() = Transaction(id, amountMinor, type, categoryId, epochDay, note, createdAt, accountId, receiptPath)

    companion object {
        fun from(t: Transaction, deletedAt: Long = System.currentTimeMillis()) =
            DeletedTransaction(t.id, t.amountMinor, t.type, t.categoryId, t.epochDay, t.note, t.createdAt, t.accountId, t.receiptPath, deletedAt)
    }
}

/** A deleted work shift kept so it can be restored from Deleted entries. */
@Entity(tableName = "deleted_shifts")
data class DeletedShift(
    @PrimaryKey val id: Long,
    val workplaceId: Long?,
    val title: String,
    val workplace: String?,
    val startEpochMillis: Long,
    val endEpochMillis: Long,
    val breakDurationMinutes: Int,
    val notes: String,
    val status: ShiftStatus,
    val createdAt: Long,
    val updatedAt: Long,
    val deletedAt: Long,
) {
    val startDate: LocalDate
        get() = Instant.ofEpochMilli(startEpochMillis).atZone(ZoneId.systemDefault()).toLocalDate()

    val formattedTimeRange: String
        get() {
            val zone = ZoneId.systemDefault()
            val start = Instant.ofEpochMilli(startEpochMillis).atZone(zone)
            val end = Instant.ofEpochMilli(endEpochMillis).atZone(zone)
            val timeFmt = DateTimeFormatter.ofPattern("HH:mm")
            val overnight = if (end.toLocalDate().isAfter(start.toLocalDate())) " (+1d)" else ""
            val netMins = maxOf(0L, (endEpochMillis - startEpochMillis) / 60_000 - breakDurationMinutes)
            val netHours = String.format(Locale.US, "%.1f h", netMins / 60.0)
            return "${start.format(timeFmt)} – ${end.format(timeFmt)}$overnight · $netHours"
        }

    fun toShift() = Shift(
        id = id,
        workplaceId = workplaceId,
        title = title,
        workplace = workplace,
        startEpochMillis = startEpochMillis,
        endEpochMillis = endEpochMillis,
        breakDurationMinutes = breakDurationMinutes,
        notes = notes,
        status = status,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )

    companion object {
        fun from(s: Shift, deletedAt: Long = System.currentTimeMillis()) =
            DeletedShift(s.id, s.workplaceId, s.title, s.workplace, s.startEpochMillis, s.endEpochMillis, s.breakDurationMinutes, s.notes, s.status, s.createdAt, s.updatedAt, deletedAt)
    }
}

/** A deleted leave or day-off entry. */
@Entity(tableName = "deleted_leave_entries")
data class DeletedLeaveEntry(
    @PrimaryKey val id: Long,
    val workplaceId: Long?,
    val type: LeaveType,
    val startDateEpochDay: Long,
    val endDateEpochDay: Long,
    val isFullDay: Boolean,
    val customHours: Double?,
    val paymentStatus: LeavePaymentStatus,
    val paidRatePercent: Double,
    val fixedPayAmountMinor: Long?,
    val reason: String,
    val notes: String,
    val createdAt: Long,
    val updatedAt: Long,
    val deletedAt: Long,
) {
    val startDate: LocalDate get() = LocalDate.ofEpochDay(startDateEpochDay)
    val endDate: LocalDate get() = LocalDate.ofEpochDay(endDateEpochDay)
    val dayCount: Int get() = maxOf(1, (endDateEpochDay - startDateEpochDay + 1).toInt())

    fun toLeaveEntry() = LeaveEntry(
        id = id,
        workplaceId = workplaceId,
        type = type,
        startDateEpochDay = startDateEpochDay,
        endDateEpochDay = endDateEpochDay,
        isFullDay = isFullDay,
        customHours = customHours,
        paymentStatus = paymentStatus,
        paidRatePercent = paidRatePercent,
        fixedPayAmountMinor = fixedPayAmountMinor,
        reason = reason,
        notes = notes,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )

    companion object {
        fun from(l: LeaveEntry, deletedAt: Long = System.currentTimeMillis()) =
            DeletedLeaveEntry(
                l.id, l.workplaceId, l.type, l.startDateEpochDay, l.endDateEpochDay,
                l.isFullDay, l.customHours, l.paymentStatus, l.paidRatePercent,
                l.fixedPayAmountMinor, l.reason, l.notes, l.createdAt, l.updatedAt, deletedAt,
            )
    }
}

/** Unified deleted entry representation across Transactions, Shifts, and Leaves. */
sealed interface DeletedEntry {
    val id: Long
    val deletedAt: Long
    val date: LocalDate
    fun deletedOn(zone: ZoneId = ZoneId.systemDefault()): LocalDate =
        Instant.ofEpochMilli(deletedAt).atZone(zone).toLocalDate()

    data class TransactionRecord(val item: DeletedItem) : DeletedEntry {
        override val id: Long get() = item.entry.id
        override val deletedAt: Long get() = item.entry.deletedAt
        override val date: LocalDate get() = item.date
    }

    data class ShiftRecord(val shift: DeletedShift) : DeletedEntry {
        override val id: Long get() = shift.id
        override val deletedAt: Long get() = shift.deletedAt
        override val date: LocalDate get() = shift.startDate
    }

    data class LeaveRecord(val leave: DeletedLeaveEntry) : DeletedEntry {
        override val id: Long get() = leave.id
        override val deletedAt: Long get() = leave.deletedAt
        override val date: LocalDate get() = leave.startDate
    }
}

/** A deleted entry with its category, if that still exists. */
data class DeletedItem(
    @Embedded val entry: DeletedTransaction,
    val categoryName: String?,
    val categoryEmoji: String?,
    val categoryColor: Long?,
) {
    val date: LocalDate get() = LocalDate.ofEpochDay(entry.epochDay)
    fun deletedOn(zone: ZoneId = ZoneId.systemDefault()): LocalDate = Instant.ofEpochMilli(entry.deletedAt).atZone(zone).toLocalDate()
}

/** Which deletions to show, by the day they were deleted. */
sealed interface DeletedFilter {
    data object All : DeletedFilter
    data object Today : DeletedFilter
    data object Week : DeletedFilter
    data object Month : DeletedFilter
    data class Range(val from: LocalDate, val to: LocalDate) : DeletedFilter

    fun matches(deletedOn: LocalDate, today: LocalDate = LocalDate.now()): Boolean = when (this) {
        All -> true
        Today -> deletedOn == today
        Week -> deletedOn > today.minusDays(7)
        Month -> deletedOn > today.minusDays(30)
        is Range -> deletedOn in from..to
    }
}
