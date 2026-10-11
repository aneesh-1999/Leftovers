package com.leftovers.app.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.temporal.WeekFields
import java.util.Locale
import kotlin.math.roundToLong

enum class ShiftStatus(val label: String) {
    SCHEDULED("Scheduled"),
    COMPLETED("Completed"),
    CANCELLED("Cancelled"),
}

enum class LeaveType(val label: String, val emoji: String) {
    DAY_OFF("Day Off", "🏖️"),
    REST_DAY("Rest Day", "🛋️"),
    SICK_LEAVE("Sick Leave", "🤒"),
    ANNUAL_LEAVE("Annual Leave", "🌴"),
    UNPAID_LEAVE("Unpaid Leave", "⏳"),
    PUBLIC_HOLIDAY("Public Holiday", "🎉"),
    OTHER("Other Leave", "📋"),
}

enum class LeavePaymentStatus(val label: String) {
    PAID("Paid"),
    PARTIALLY_PAID("Partially Paid"),
    UNPAID("Unpaid"),
}

/** Formats minutes as e.g. "4h 30m", "8h 00m", "0h 00m". */
fun formatMinutesDuration(totalMinutes: Long): String {
    val m = maxOf(0L, totalMinutes)
    val hours = m / 60
    val mins = m % 60
    return "${hours}h ${String.format(Locale.US, "%02d", mins)}m"
}

/** Formats hours with decimal precision, e.g. "173.33 h" or "6.67 h". */
fun formatDecimalHours(hours: Double): String {
    return String.format(Locale.US, "%.2f h", maxOf(0.0, hours))
}

@Entity(
    tableName = "workplaces",
)
data class Workplace(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val role: String = "",
    val address: String? = null,
    val contactPerson: String? = null,
    val contactDetails: String? = null,
    val employmentType: String = "Full-time",
    val currency: String = "EUR",
    val basicHourlyRateCents: Long = 573, // €5.73 = 573 cents
    val basicMonthlyHours: Double = 173.33,
    val overtimeMultiplier: Double = 1.5,
    val paidBreaks: Boolean = false,
    val leaveCountsTowardsThreshold: Boolean = false,
    val fixedMonthlyAllowanceMinor: Long = 0,
    val monthlyDeductionMinor: Long = 0,
    val notes: String = "",
    val archived: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
) {
    val hourlyRateDisplay: String
        get() = String.format(Locale.US, "%.2f", basicHourlyRateCents / 100.0)

    val overtimeHourlyRateDisplay: String
        get() = String.format(Locale.US, "%.2f", (basicHourlyRateCents * overtimeMultiplier) / 100.0)
}

@Entity(
    tableName = "shifts",
    indices = [
        Index("startEpochMillis"),
        Index("endEpochMillis"),
        Index("status"),
        Index("workplaceId"),
    ],
)
data class Shift(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val workplaceId: Long? = null,
    val title: String,
    val workplace: String? = null,
    val startEpochMillis: Long,
    val endEpochMillis: Long,
    val breakDurationMinutes: Int = 0,
    val notes: String = "",
    val status: ShiftStatus = ShiftStatus.SCHEDULED,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
) {
    /** Total elapsed duration between start and end in minutes. */
    val totalDurationMinutes: Long
        get() = maxOf(0L, (endEpochMillis - startEpochMillis) / 60_000)

    /** Net working duration deducting unpaid break. Cancelled shifts return 0. */
    val netWorkingMinutes: Long
        get() = if (status == ShiftStatus.CANCELLED) 0L else maxOf(0L, totalDurationMinutes - breakDurationMinutes)

    /** Net working duration in decimal hours. */
    val netWorkingHours: Double
        get() = netWorkingMinutes / 60.0

    /** Start date in system default time zone. */
    val startDate: LocalDate
        get() = Instant.ofEpochMilli(startEpochMillis).atZone(ZoneId.systemDefault()).toLocalDate()

    /** End date in system default time zone. */
    val endDate: LocalDate
        get() = Instant.ofEpochMilli(endEpochMillis).atZone(ZoneId.systemDefault()).toLocalDate()

    /** True when shift finishes on a calendar day after its start day. */
    val isOvernight: Boolean
        get() = endDate.isAfter(startDate)
}

@Entity(
    tableName = "leave_entries",
    indices = [
        Index("startDateEpochDay"),
        Index("endDateEpochDay"),
        Index("workplaceId"),
    ],
)
data class LeaveEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val workplaceId: Long? = null,
    val type: LeaveType = LeaveType.DAY_OFF,
    val startDateEpochDay: Long,
    val endDateEpochDay: Long = startDateEpochDay,
    val isFullDay: Boolean = true,
    val customHours: Double? = null,
    val paymentStatus: LeavePaymentStatus = LeavePaymentStatus.UNPAID,
    val paidRatePercent: Double = 100.0,
    val fixedPayAmountMinor: Long? = null,
    val reason: String = "",
    val notes: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
) {
    val startDate: LocalDate
        get() = LocalDate.ofEpochDay(startDateEpochDay)

    val endDate: LocalDate
        get() = LocalDate.ofEpochDay(endDateEpochDay)

    val dayCount: Int
        get() = maxOf(1, (endDateEpochDay - startDateEpochDay + 1).toInt())

    /** Effective hours represented by this leave record (defaulting to 8h per full day if not specified). */
    val effectiveHours: Double
        get() = if (!isFullDay && customHours != null) customHours else dayCount * 8.0

    fun containsDate(date: LocalDate): Boolean {
        val d = date.toEpochDay()
        return d in startDateEpochDay..endDateEpochDay
    }
}

@Entity(
    tableName = "workplace_payments",
    indices = [
        Index("workplaceId"),
        Index("payPeriodMonth"),
    ],
)
data class WorkplacePayment(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val workplaceId: Long,
    val payPeriodMonth: String, // e.g. "2026-10"
    val paymentEpochDay: Long,
    val amountMinor: Long,
    val currency: String = "EUR",
    val payslipReference: String = "",
    val note: String = "",
    val reviewed: Boolean = true,
    val financeTransactionId: Long? = null,
    val createdAt: Long = System.currentTimeMillis(),
) {
    val paymentDate: LocalDate
        get() = LocalDate.ofEpochDay(paymentEpochDay)
}

data class DayRosterItem(
    val date: LocalDate,
    val shifts: List<Shift> = emptyList(),
    val leaves: List<LeaveEntry> = emptyList(),
) {
    val hasShifts: Boolean get() = shifts.isNotEmpty()
    val hasLeaves: Boolean get() = leaves.isNotEmpty()
    val hasCompletedShift: Boolean get() = shifts.any { it.status == ShiftStatus.COMPLETED }
    val hasScheduledShift: Boolean get() = shifts.any { it.status == ShiftStatus.SCHEDULED }
    val hasCancelledShift: Boolean get() = shifts.any { it.status == ShiftStatus.CANCELLED }
    val hasDayOff: Boolean get() = leaves.any { it.type == LeaveType.DAY_OFF || it.type == LeaveType.REST_DAY }
    val hasSickLeave: Boolean get() = leaves.any { it.type == LeaveType.SICK_LEAVE }
    val hasAnnualLeave: Boolean get() = leaves.any { it.type == LeaveType.ANNUAL_LEAVE }
    val hasPublicHoliday: Boolean get() = leaves.any { it.type == LeaveType.PUBLIC_HOLIDAY }
    val hasUnpaidLeave: Boolean get() = leaves.any { it.type == LeaveType.UNPAID_LEAVE }

    val hasConflict: Boolean get() = hasShifts && hasLeaves
}

data class RosterDailySummary(
    val date: LocalDate,
    val totalShifts: Int,
    val scheduledShifts: Int,
    val completedShifts: Int,
    val cancelledShifts: Int,
    val totalWorkingMinutes: Long,
    val shifts: List<Shift>,
    val leaves: List<LeaveEntry> = emptyList(),
)

data class RosterWeeklySummary(
    val startOfWeek: LocalDate,
    val endOfWeek: LocalDate,
    val totalShifts: Int,
    val scheduledShifts: Int,
    val completedShifts: Int,
    val cancelledShifts: Int,
    val totalWorkingMinutes: Long,
    val leaveDays: Int = 0,
)

data class RosterMonthlySummary(
    val month: YearMonth,
    val totalShifts: Int,
    val scheduledShifts: Int,
    val completedShifts: Int,
    val cancelledShifts: Int,
    val totalWorkingMinutes: Long,
    val daysWorked: Int = 0,
    val daysOff: Int = 0,
    val sickLeaveDays: Int = 0,
    val annualLeaveDays: Int = 0,
    val publicHolidayDays: Int = 0,
)

/** Earnings & Working Hours Summary for a specific Workplace and Pay Period (Month). */
data class WorkplaceEarningsSummary(
    val workplace: Workplace?,
    val month: YearMonth,
    val scheduledHours: Double = 0.0,
    val completedHours: Double = 0.0,
    val totalHours: Double = completedHours + scheduledHours,
    val basicHoursThreshold: Double = 173.33,
    val basicHoursUsed: Double = 0.0,
    val remainingBasicHours: Double = 0.0,
    val overtimeHours: Double = 0.0,
    val sickLeaveHours: Double = 0.0,
    val annualLeaveHours: Double = 0.0,
    val publicHolidayHours: Double = 0.0,
    val unpaidLeaveHours: Double = 0.0,
    val basicEarningsMinor: Long = 0,
    val overtimeEarningsMinor: Long = 0,
    val paidSickLeaveEarningsMinor: Long = 0,
    val paidAnnualLeaveEarningsMinor: Long = 0,
    val publicHolidayEarningsMinor: Long = 0,
    val fixedAllowanceMinor: Long = 0,
    val grossEstimatedEarningsMinor: Long = 0,
    val estimatedDeductionsMinor: Long = 0,
    val estimatedNetEarningsMinor: Long = 0,
    val projectedNetEarningsMinor: Long = estimatedNetEarningsMinor,
    val recordedPayments: List<WorkplacePayment> = emptyList(),
    val totalPaidActualMinor: Long = 0,
    val progressToThreshold: Float = 0f,
    val currency: String = workplace?.currency ?: "EUR",
)

/** Calculates weekly summary for a given date's week (Monday - Sunday). */
fun calculateWeeklySummary(date: LocalDate, shifts: List<Shift>, leaves: List<LeaveEntry> = emptyList()): RosterWeeklySummary {
    val weekFields = WeekFields.ISO
    val startOfWeek = date.with(weekFields.dayOfWeek(), 1L)
    val endOfWeek = startOfWeek.plusDays(6)

    val weekShifts = shifts.filter {
        val sDate = it.startDate
        !sDate.isBefore(startOfWeek) && !sDate.isAfter(endOfWeek)
    }

    val weekLeaves = leaves.filter {
        !(it.endDate.isBefore(startOfWeek) || it.startDate.isAfter(endOfWeek))
    }

    return RosterWeeklySummary(
        startOfWeek = startOfWeek,
        endOfWeek = endOfWeek,
        totalShifts = weekShifts.size,
        scheduledShifts = weekShifts.count { it.status == ShiftStatus.SCHEDULED },
        completedShifts = weekShifts.count { it.status == ShiftStatus.COMPLETED },
        cancelledShifts = weekShifts.count { it.status == ShiftStatus.CANCELLED },
        totalWorkingMinutes = weekShifts.sumOf { it.netWorkingMinutes },
        leaveDays = weekLeaves.sumOf { it.dayCount },
    )
}

/** Calculates monthly summary for a given YearMonth. */
fun calculateMonthlySummary(month: YearMonth, shifts: List<Shift>, leaves: List<LeaveEntry> = emptyList()): RosterMonthlySummary {
    val monthShifts = shifts.filter { YearMonth.from(it.startDate) == month }
    val completedDays = monthShifts.filter { it.status == ShiftStatus.COMPLETED }.map { it.startDate }.distinct().size

    val monthLeaves = leaves.filter {
        val sYm = YearMonth.from(it.startDate)
        val eYm = YearMonth.from(it.endDate)
        month in sYm..eYm
    }

    val daysOff = monthLeaves.filter { it.type == LeaveType.DAY_OFF || it.type == LeaveType.REST_DAY }.sumOf { it.dayCount }
    val sickDays = monthLeaves.filter { it.type == LeaveType.SICK_LEAVE }.sumOf { it.dayCount }
    val annualDays = monthLeaves.filter { it.type == LeaveType.ANNUAL_LEAVE }.sumOf { it.dayCount }
    val holidayDays = monthLeaves.filter { it.type == LeaveType.PUBLIC_HOLIDAY }.sumOf { it.dayCount }

    return RosterMonthlySummary(
        month = month,
        totalShifts = monthShifts.size,
        scheduledShifts = monthShifts.count { it.status == ShiftStatus.SCHEDULED },
        completedShifts = monthShifts.count { it.status == ShiftStatus.COMPLETED },
        cancelledShifts = monthShifts.count { it.status == ShiftStatus.CANCELLED },
        totalWorkingMinutes = monthShifts.sumOf { it.netWorkingMinutes },
        daysWorked = completedDays,
        daysOff = daysOff,
        sickLeaveDays = sickDays,
        annualLeaveDays = annualDays,
        publicHolidayDays = holidayDays,
    )
}

/** Calculates daily summary for a selected date. */
fun calculateDailySummary(date: LocalDate, shifts: List<Shift>, leaves: List<LeaveEntry> = emptyList()): RosterDailySummary {
    val dayShifts = shifts.filter { it.startDate == date }.sortedBy { it.startEpochMillis }
    val dayLeaves = leaves.filter { it.containsDate(date) }

    return RosterDailySummary(
        date = date,
        totalShifts = dayShifts.size,
        scheduledShifts = dayShifts.count { it.status == ShiftStatus.SCHEDULED },
        completedShifts = dayShifts.count { it.status == ShiftStatus.COMPLETED },
        cancelledShifts = dayShifts.count { it.status == ShiftStatus.CANCELLED },
        totalWorkingMinutes = dayShifts.sumOf { it.netWorkingMinutes },
        shifts = dayShifts,
        leaves = dayLeaves,
    )
}

/**
 * Basic Hours & Overtime Engine.
 * Calculates exact basic hours, overtime hours (1.5x), leave pay, allowances, deductions and earnings.
 */
fun calculateWorkplaceEarnings(
    workplace: Workplace,
    month: YearMonth,
    shifts: List<Shift>,
    leaves: List<LeaveEntry>,
    payments: List<WorkplacePayment> = emptyList(),
): WorkplaceEarningsSummary {
    val monthShifts = shifts.filter {
        (it.workplaceId == workplace.id ||
            it.workplace.equals(workplace.name, ignoreCase = true) ||
            (it.workplaceId == null && (it.workplace.isNullOrBlank() || it.workplace.equals("Work", ignoreCase = true)))) &&
            (YearMonth.from(it.startDate) == month || YearMonth.from(it.endDate) == month)
    }

    val monthLeaves = leaves.filter {
        (it.workplaceId == null || it.workplaceId == workplace.id) &&
            YearMonth.from(it.startDate) <= month && YearMonth.from(it.endDate) >= month
    }

    val monthPayments = payments.filter {
        it.workplaceId == workplace.id && it.payPeriodMonth == month.toString()
    }

    val scheduledMinutes = monthShifts.filter { it.status == ShiftStatus.SCHEDULED }.sumOf { it.netWorkingMinutes }
    val completedMinutes = monthShifts.filter { it.status == ShiftStatus.COMPLETED }.sumOf { it.netWorkingMinutes }

    val completedHours = completedMinutes / 60.0
    val scheduledHours = scheduledMinutes / 60.0
    val totalHours = completedHours + scheduledHours

    // Threshold & Basic vs Overtime calculation based on Completed Hours
    val threshold = workplace.basicMonthlyHours
    val basicHoursUsed = minOf(completedHours, threshold)
    val remainingBasicHours = maxOf(0.0, threshold - completedHours)
    val overtimeHours = maxOf(0.0, completedHours - threshold)

    // Basic pay calculation (in minor currency units e.g. cents)
    val basicEarningsMinor = (basicHoursUsed * workplace.basicHourlyRateCents).roundToLong()

    // Overtime pay: overtimeHours * basicHourlyRateCents * multiplier
    val overtimeRate = workplace.basicHourlyRateCents * workplace.overtimeMultiplier
    val overtimeEarningsMinor = (overtimeHours * overtimeRate).roundToLong()

    // Projected (Completed + Scheduled) calculations
    val projectedBasicHoursUsed = minOf(totalHours, threshold)
    val projectedOvertimeHours = maxOf(0.0, totalHours - threshold)
    val projectedBasicEarningsMinor = (projectedBasicHoursUsed * workplace.basicHourlyRateCents).roundToLong()
    val projectedOvertimeEarningsMinor = (projectedOvertimeHours * overtimeRate).roundToLong()

    // Leave Hours & Pay
    var sickHours = 0.0
    var annualHours = 0.0
    var holidayHours = 0.0
    var unpaidHours = 0.0

    var sickPayMinor = 0L
    var annualPayMinor = 0L
    var holidayPayMinor = 0L

    monthLeaves.forEach { l ->
        val effHours = l.effectiveHours
        when (l.type) {
            LeaveType.SICK_LEAVE -> {
                sickHours += effHours
                if (l.paymentStatus != LeavePaymentStatus.UNPAID) {
                    val pay = if (l.fixedPayAmountMinor != null) {
                        l.fixedPayAmountMinor
                    } else {
                        (effHours * workplace.basicHourlyRateCents * (l.paidRatePercent / 100.0)).roundToLong()
                    }
                    sickPayMinor += pay
                }
            }
            LeaveType.ANNUAL_LEAVE -> {
                annualHours += effHours
                if (l.paymentStatus != LeavePaymentStatus.UNPAID) {
                    val pay = if (l.fixedPayAmountMinor != null) {
                        l.fixedPayAmountMinor
                    } else {
                        (effHours * workplace.basicHourlyRateCents * (l.paidRatePercent / 100.0)).roundToLong()
                    }
                    annualPayMinor += pay
                }
            }
            LeaveType.PUBLIC_HOLIDAY -> {
                holidayHours += effHours
                if (l.paymentStatus != LeavePaymentStatus.UNPAID) {
                    val pay = if (l.fixedPayAmountMinor != null) {
                        l.fixedPayAmountMinor
                    } else {
                        (effHours * workplace.basicHourlyRateCents * (l.paidRatePercent / 100.0)).roundToLong()
                    }
                    holidayPayMinor += pay
                }
            }
            LeaveType.UNPAID_LEAVE, LeaveType.DAY_OFF, LeaveType.REST_DAY, LeaveType.OTHER -> {
                unpaidHours += effHours
            }
        }
    }

    val gross = basicEarningsMinor + overtimeEarningsMinor + sickPayMinor + annualPayMinor + holidayPayMinor + workplace.fixedMonthlyAllowanceMinor
    val net = maxOf(0L, gross - workplace.monthlyDeductionMinor)

    val projectedGross = projectedBasicEarningsMinor + projectedOvertimeEarningsMinor + sickPayMinor + annualPayMinor + holidayPayMinor + workplace.fixedMonthlyAllowanceMinor
    val projectedNet = maxOf(0L, projectedGross - workplace.monthlyDeductionMinor)

    val totalPaidActual = monthPayments.sumOf { it.amountMinor }
    val progress = if (threshold > 0) (completedHours / threshold).toFloat().coerceIn(0f, 1f) else 1f

    return WorkplaceEarningsSummary(
        workplace = workplace,
        month = month,
        scheduledHours = scheduledHours,
        completedHours = completedHours,
        totalHours = totalHours,
        basicHoursThreshold = threshold,
        basicHoursUsed = basicHoursUsed,
        remainingBasicHours = remainingBasicHours,
        overtimeHours = overtimeHours,
        sickLeaveHours = sickHours,
        annualLeaveHours = annualHours,
        publicHolidayHours = holidayHours,
        unpaidLeaveHours = unpaidHours,
        basicEarningsMinor = basicEarningsMinor,
        overtimeEarningsMinor = overtimeEarningsMinor,
        paidSickLeaveEarningsMinor = sickPayMinor,
        paidAnnualLeaveEarningsMinor = annualPayMinor,
        publicHolidayEarningsMinor = holidayPayMinor,
        fixedAllowanceMinor = workplace.fixedMonthlyAllowanceMinor,
        grossEstimatedEarningsMinor = gross,
        estimatedDeductionsMinor = workplace.monthlyDeductionMinor,
        estimatedNetEarningsMinor = net,
        projectedNetEarningsMinor = projectedNet,
        recordedPayments = monthPayments,
        totalPaidActualMinor = totalPaidActual,
        progressToThreshold = progress,
        currency = workplace.currency,
    )
}
