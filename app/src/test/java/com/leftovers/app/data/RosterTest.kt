package com.leftovers.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneId

class RosterTest {

    private fun toMillis(date: LocalDate, hour: Int, minute: Int): Long =
        LocalDateTime.of(date, LocalTime.of(hour, minute))
            .atZone(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()

    @Test
    fun standardDayShiftDurationCalculatedCorrectly() {
        val date = LocalDate.of(2026, 10, 15)
        val shift = Shift(
            title = "Morning Shift",
            workplace = "Cafe Central",
            startEpochMillis = toMillis(date, 9, 0),
            endEpochMillis = toMillis(date, 17, 0),
            breakDurationMinutes = 30,
            notes = "Opening barista",
            status = ShiftStatus.SCHEDULED,
        )

        assertEquals(480L, shift.totalDurationMinutes) // 8 hours = 480 mins
        assertEquals(450L, shift.netWorkingMinutes) // 480 - 30 = 450 mins (7h 30m)
        assertFalse(shift.isOvernight)
        assertEquals("7h 30m", formatMinutesDuration(shift.netWorkingMinutes))
    }

    @Test
    fun overnightShiftDurationCalculatedCorrectly() {
        val startDay = LocalDate.of(2026, 10, 20)
        val endDay = startDay.plusDays(1)
        val shift = Shift(
            title = "Night Shift",
            workplace = "General Hospital",
            startEpochMillis = toMillis(startDay, 22, 0),
            endEpochMillis = toMillis(endDay, 6, 0),
            breakDurationMinutes = 30,
            status = ShiftStatus.SCHEDULED,
        )

        assertTrue(shift.isOvernight)
        assertEquals(480L, shift.totalDurationMinutes) // 8 hours = 480 mins
        assertEquals(450L, shift.netWorkingMinutes) // 7h 30m
        assertEquals("7h 30m", formatMinutesDuration(shift.netWorkingMinutes))
    }

    @Test
    fun cancelledShiftsExcludedFromWorkingHours() {
        val date = LocalDate.of(2026, 10, 12)
        val scheduled = Shift(
            title = "Day",
            startEpochMillis = toMillis(date, 9, 0),
            endEpochMillis = toMillis(date, 17, 0),
            breakDurationMinutes = 60,
            status = ShiftStatus.SCHEDULED,
        )
        val completed = Shift(
            title = "Evening",
            startEpochMillis = toMillis(date, 17, 0),
            endEpochMillis = toMillis(date, 21, 0),
            breakDurationMinutes = 0,
            status = ShiftStatus.COMPLETED,
        )
        val cancelled = Shift(
            title = "Extra",
            startEpochMillis = toMillis(date, 21, 0),
            endEpochMillis = toMillis(date, 23, 0),
            breakDurationMinutes = 0,
            status = ShiftStatus.CANCELLED,
        )

        val shifts = listOf(scheduled, completed, cancelled)
        val totalMinutes = shifts.sumOf { it.netWorkingMinutes }

        assertEquals(660L, totalMinutes)
        assertEquals("11h 00m", formatMinutesDuration(totalMinutes))
    }

    @Test
    fun basicHoursAndOvertimeEngineDetailedExample() {
        // Spec Example:
        // Basic hourly rate: €5.73 (573 cents)
        // Monthly threshold: 173.33 hours
        // Overtime multiplier: 1.5x (5.73 * 1.5 = 8.595)
        // Completed eligible hours: 180.00 hours (10,800 minutes)
        // Expected:
        // Basic hours: 173.33 -> 173.33 * 573 cents = 99318 cents = €993.18
        // Overtime hours: 6.67 -> 6.67 * 573 * 1.5 = 5734 cents = €57.34
        // Gross: €1,050.52 (105052 cents)
        val workplace = Workplace(
            id = 1L,
            name = "Main Hotel",
            basicHourlyRateCents = 573,
            basicMonthlyHours = 173.33,
            overtimeMultiplier = 1.5,
            currency = "EUR",
        )

        val ym = YearMonth.of(2026, 10)
        // Create completed shifts totaling exactly 180 hours
        // 20 shifts of 9 hours each
        val shifts = (1..20).map { day ->
            val date = LocalDate.of(2026, 10, day)
            Shift(
                id = day.toLong(),
                workplaceId = 1L,
                title = "Shift $day",
                startEpochMillis = toMillis(date, 8, 0),
                endEpochMillis = toMillis(date, 17, 0), // 9 hours
                breakDurationMinutes = 0,
                status = ShiftStatus.COMPLETED,
            )
        }

        val summary = calculateWorkplaceEarnings(workplace, ym, shifts, emptyList())

        assertEquals(180.0, summary.completedHours, 0.01)
        assertEquals(173.33, summary.basicHoursUsed, 0.01)
        assertEquals(0.0, summary.remainingBasicHours, 0.01)
        assertEquals(6.67, summary.overtimeHours, 0.01)

        assertEquals(99318L, summary.basicEarningsMinor) // €993.18
        assertEquals(5733L, summary.overtimeEarningsMinor) // €57.33 (6.67h * €8.595/h)
        assertEquals(105051L, summary.grossEstimatedEarningsMinor) // €1,050.51
        assertEquals(105051L, summary.estimatedNetEarningsMinor)
        assertEquals(1f, summary.progressToThreshold, 0.001f)
    }

    @Test
    fun underThresholdEarningsCalculation() {
        val workplace = Workplace(
            id = 1L,
            name = "Cafe",
            basicHourlyRateCents = 1000, // €10.00 / h
            basicMonthlyHours = 160.0,
            overtimeMultiplier = 1.5,
            currency = "EUR",
        )

        val ym = YearMonth.of(2026, 10)
        // 10 shifts of 8 hours = 80 hours completed
        val shifts = (1..10).map { day ->
            val date = LocalDate.of(2026, 10, day)
            Shift(
                id = day.toLong(),
                workplaceId = 1L,
                title = "Shift",
                startEpochMillis = toMillis(date, 9, 0),
                endEpochMillis = toMillis(date, 17, 0),
                status = ShiftStatus.COMPLETED,
            )
        }

        val summary = calculateWorkplaceEarnings(workplace, ym, shifts, emptyList())

        assertEquals(80.0, summary.completedHours, 0.01)
        assertEquals(80.0, summary.basicHoursUsed, 0.01)
        assertEquals(80.0, summary.remainingBasicHours, 0.01)
        assertEquals(0.0, summary.overtimeHours, 0.01)
        assertEquals(80000L, summary.basicEarningsMinor) // 80 * 1000 = €800.00
        assertEquals(0L, summary.overtimeEarningsMinor)
        assertEquals(0.5f, summary.progressToThreshold, 0.01f)
    }

    @Test
    fun paidAndUnpaidLeaveSeparatedFromWorkedHours() {
        val workplace = Workplace(
            id = 1L,
            name = "Office",
            basicHourlyRateCents = 1200, // €12.00 / h
            basicMonthlyHours = 160.0,
        )
        val ym = YearMonth.of(2026, 10)

        // 1 day worked (8h)
        val shift = Shift(
            workplaceId = 1L,
            title = "Office Day",
            startEpochMillis = toMillis(LocalDate.of(2026, 10, 5), 9, 0),
            endEpochMillis = toMillis(LocalDate.of(2026, 10, 5), 17, 0),
            status = ShiftStatus.COMPLETED,
        )

        // 2 days paid sick leave (16h @ 100% pay)
        val sickLeave = LeaveEntry(
            workplaceId = 1L,
            type = LeaveType.SICK_LEAVE,
            startDateEpochDay = LocalDate.of(2026, 10, 6).toEpochDay(),
            endDateEpochDay = LocalDate.of(2026, 10, 7).toEpochDay(),
            paymentStatus = LeavePaymentStatus.PAID,
            paidRatePercent = 100.0,
        )

        // 1 day unpaid leave
        val unpaidLeave = LeaveEntry(
            workplaceId = 1L,
            type = LeaveType.UNPAID_LEAVE,
            startDateEpochDay = LocalDate.of(2026, 10, 8).toEpochDay(),
            endDateEpochDay = LocalDate.of(2026, 10, 8).toEpochDay(),
            paymentStatus = LeavePaymentStatus.UNPAID,
        )

        val summary = calculateWorkplaceEarnings(workplace, ym, listOf(shift), listOf(sickLeave, unpaidLeave))

        // Physical worked hours is ONLY 8h
        assertEquals(8.0, summary.completedHours, 0.01)
        assertEquals(9600L, summary.basicEarningsMinor) // 8 * 1200 = €96.00

        // Sick leave is 16h paid = 16 * 1200 = €192.00 (19200 cents)
        assertEquals(16.0, summary.sickLeaveHours, 0.01)
        assertEquals(19200L, summary.paidSickLeaveEarningsMinor)

        // Unpaid leave is 8h with 0 pay
        assertEquals(8.0, summary.unpaidLeaveHours, 0.01)

        // Gross: 9600 + 19200 = 28800 cents = €288.00
        assertEquals(28800L, summary.grossEstimatedEarningsMinor)
    }

    @Test
    fun multipleWorkplacesCalculatedIndependently() {
        val wp1 = Workplace(id = 1L, name = "Job A", basicHourlyRateCents = 1000, basicMonthlyHours = 100.0, currency = "EUR")
        val wp2 = Workplace(id = 2L, name = "Job B", basicHourlyRateCents = 50000, basicMonthlyHours = 80.0, currency = "NPR") // NPR 500 / hr

        val ym = YearMonth.of(2026, 10)
        val shift1 = Shift(workplaceId = 1L, title = "A", startEpochMillis = toMillis(LocalDate.of(2026, 10, 1), 9, 0), endEpochMillis = toMillis(LocalDate.of(2026, 10, 1), 17, 0), status = ShiftStatus.COMPLETED)
        val shift2 = Shift(workplaceId = 2L, title = "B", startEpochMillis = toMillis(LocalDate.of(2026, 10, 2), 9, 0), endEpochMillis = toMillis(LocalDate.of(2026, 10, 2), 17, 0), status = ShiftStatus.COMPLETED)

        val summaryA = calculateWorkplaceEarnings(wp1, ym, listOf(shift1, shift2), emptyList())
        val summaryB = calculateWorkplaceEarnings(wp2, ym, listOf(shift1, shift2), emptyList())

        assertEquals(8.0, summaryA.completedHours, 0.01)
        assertEquals(8000L, summaryA.basicEarningsMinor) // €80.00
        assertEquals("EUR", summaryA.currency)

        assertEquals(8.0, summaryB.completedHours, 0.01)
        assertEquals(400000L, summaryB.basicEarningsMinor) // 8 * 50000 = NPR 4,000.00
        assertEquals("NPR", summaryB.currency)
    }

    @Test
    fun leapYearAndMonthLengths() {
        val feb2028 = YearMonth.of(2028, 2)
        assertEquals(29, feb2028.lengthOfMonth())

        val feb2026 = YearMonth.of(2026, 2)
        assertEquals(28, feb2026.lengthOfMonth())

        assertEquals(30, YearMonth.of(2026, 4).lengthOfMonth())
        assertEquals(31, YearMonth.of(2026, 10).lengthOfMonth())
    }

    @Test
    fun fridayOctober92026IsCorrectlyAligned() {
        val oct9 = LocalDate.of(2026, 10, 9)
        assertEquals(java.time.DayOfWeek.FRIDAY, oct9.dayOfWeek)
        assertEquals(5, oct9.dayOfWeek.value) // Mon=1, Tue=2, Wed=3, Thu=4, Fri=5

        val oct1 = LocalDate.of(2026, 10, 1)
        assertEquals(java.time.DayOfWeek.THURSDAY, oct1.dayOfWeek)
        val firstDayOffset = oct1.dayOfWeek.value - 1
        assertEquals(3, firstDayOffset) // Index 3 is Thursday (0=M, 1=T, 2=W, 3=T, 4=F, 5=S, 6=S)

        val summary = calculateWeeklySummary(oct9, emptyList(), emptyList())
        assertEquals(LocalDate.of(2026, 10, 5), summary.startOfWeek) // Monday
        assertEquals(LocalDate.of(2026, 10, 11), summary.endOfWeek) // Sunday
    }

    @Test
    fun sevenDaysOfEightHoursCompletedCalculatesFiftySixHours() {
        val wp = Workplace(id = 1L, name = "Logistics", basicHourlyRateCents = 1000, basicMonthlyHours = 160.0)
        val ym = YearMonth.of(2026, 10)

        val shifts = (1..7).map { day ->
            val date = LocalDate.of(2026, 10, day)
            Shift(
                id = day.toLong(),
                workplaceId = 1L,
                title = "Shift $day",
                startEpochMillis = toMillis(date, 8, 0),
                endEpochMillis = toMillis(date, 16, 0), // 8 hours net (0 break)
                breakDurationMinutes = 0,
                status = ShiftStatus.COMPLETED,
            )
        }

        val summary = calculateWorkplaceEarnings(wp, ym, shifts, emptyList())
        assertEquals(56.0, summary.completedHours, 0.01)
        assertEquals(56.0, summary.totalHours, 0.01)
        assertEquals(0.0, summary.scheduledHours, 0.01)
        assertEquals(56000L, summary.basicEarningsMinor) // 56 * 10.00 = €560.00
        assertEquals(56000L, summary.estimatedNetEarningsMinor)
    }

    @Test
    fun sevenDaysWithFiveCompletedAndTwoScheduledCalculatesFortyCompletedAndFiftySixTotal() {
        val wp = Workplace(id = 1L, name = "Logistics", basicHourlyRateCents = 1000, basicMonthlyHours = 160.0)
        val ym = YearMonth.of(2026, 10)

        // 5 completed (40h) and 2 scheduled (16h)
        val shifts = (1..7).map { day ->
            val date = LocalDate.of(2026, 10, day)
            Shift(
                id = day.toLong(),
                workplaceId = 1L,
                title = "Shift $day",
                startEpochMillis = toMillis(date, 8, 0),
                endEpochMillis = toMillis(date, 16, 0),
                breakDurationMinutes = 0,
                status = if (day <= 5) ShiftStatus.COMPLETED else ShiftStatus.SCHEDULED,
            )
        }

        val summary = calculateWorkplaceEarnings(wp, ym, shifts, emptyList())
        assertEquals(40.0, summary.completedHours, 0.01)
        assertEquals(16.0, summary.scheduledHours, 0.01)
        assertEquals(56.0, summary.totalHours, 0.01)
        assertEquals(40000L, summary.estimatedNetEarningsMinor) // 40h * €10 = €400 earned to date
        assertEquals(56000L, summary.projectedNetEarningsMinor) // 56h * €10 = €560 projected month total
    }

    @Test
    fun unassignedWorkplaceShiftsMatchDefaultWorkplace() {
        val wp = Workplace(id = 1L, name = "Default Store", basicHourlyRateCents = 800, basicMonthlyHours = 160.0)
        val ym = YearMonth.of(2026, 10)

        val shift1 = Shift(id = 1L, title = "Store Shift", workplaceId = null, workplace = null, startEpochMillis = toMillis(LocalDate.of(2026, 10, 1), 9, 0), endEpochMillis = toMillis(LocalDate.of(2026, 10, 1), 17, 0), status = ShiftStatus.COMPLETED)
        val shift2 = Shift(id = 2L, title = "Store Shift", workplaceId = null, workplace = "Work", startEpochMillis = toMillis(LocalDate.of(2026, 10, 2), 9, 0), endEpochMillis = toMillis(LocalDate.of(2026, 10, 2), 17, 0), status = ShiftStatus.COMPLETED)

        val summary = calculateWorkplaceEarnings(wp, ym, listOf(shift1, shift2), emptyList())
        assertEquals(16.0, summary.completedHours, 0.01)
        assertEquals(12800L, summary.basicEarningsMinor) // 16 * 800 = €128.00
    }
}
