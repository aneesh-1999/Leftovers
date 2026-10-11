package com.leftovers.app.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.leftovers.app.data.LeaveEntry
import com.leftovers.app.data.RosterMonthlySummary
import com.leftovers.app.data.Shift
import com.leftovers.app.data.Workplace
import com.leftovers.app.data.WorkplaceEarningsSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Generates professional timesheets & shift reports for employers and payroll in CSV and Text formats.
 */
object TimesheetExporter {

    private val timeFormat = DateTimeFormatter.ofPattern("HH:mm")
    private val dateFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd")
    private val dayOfWeekFormat = DateTimeFormatter.ofPattern("EEE", Locale.US)
    private val monthFormat = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.US)

    suspend fun writeTimesheetCsv(
        context: Context,
        workplace: Workplace?,
        shifts: List<Shift>,
        leaves: List<LeaveEntry>,
        monthlySummary: RosterMonthlySummary,
        earningsSummary: WorkplaceEarningsSummary?,
        period: YearMonth,
    ): Uri = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        val wpSlug = workplace?.name?.replace(Regex("[^a-zA-Z0-9_-]"), "_") ?: "all_workplaces"
        val file = File(dir, "Timesheet_${wpSlug}_${period}.csv")

        val zone = ZoneId.systemDefault()

        file.bufferedWriter().use { out ->
            // --- HEADER METADATA ---
            out.appendLine(listOf("TIMESHEET REPORT", period.format(monthFormat)).joinToString(",") { escape(it) })
            out.appendLine(listOf("Employer / Workplace", workplace?.name ?: "All Workplaces").joinToString(",") { escape(it) })
            if (workplace != null && workplace.role.isNotBlank()) {
                out.appendLine(listOf("Job Title / Role", workplace.role).joinToString(",") { escape(it) })
            }
            out.appendLine(listOf("Generated On", LocalDate.now().toString()).joinToString(",") { escape(it) })
            out.appendLine("")

            // --- SUMMARY SECTION ---
            out.appendLine("--- MONTHLY SUMMARY ---")
            out.appendLine(listOf("Total Shifts", "${monthlySummary.totalShifts}").joinToString(",") { escape(it) })
            out.appendLine(listOf("Completed Shifts", "${monthlySummary.completedShifts}").joinToString(",") { escape(it) })
            out.appendLine(listOf("Total Working Hours", String.format(Locale.US, "%.2f hrs", monthlySummary.totalWorkingMinutes / 60.0)).joinToString(",") { escape(it) })
            out.appendLine(listOf("Days Worked", "${monthlySummary.daysWorked}").joinToString(",") { escape(it) })
            out.appendLine(listOf("Days Off / Leave", "${monthlySummary.daysOff + monthlySummary.sickLeaveDays + monthlySummary.annualLeaveDays}").joinToString(",") { escape(it) })

            if (earningsSummary != null) {
                out.appendLine(listOf("Regular Hours", String.format(Locale.US, "%.2f hrs", earningsSummary.basicHoursUsed)).joinToString(",") { escape(it) })
                out.appendLine(listOf("Overtime Hours", String.format(Locale.US, "%.2f hrs", earningsSummary.overtimeHours)).joinToString(",") { escape(it) })
                val cur = earningsSummary.currency
                out.appendLine(listOf("Basic Hourly Rate", "$cur ${workplace?.hourlyRateDisplay ?: "0.00"}/h").joinToString(",") { escape(it) })
                out.appendLine(listOf("Estimated Gross Pay", "$cur ${String.format(Locale.US, "%.2f", earningsSummary.grossEstimatedEarningsMinor / 100.0)}").joinToString(",") { escape(it) })
                if (earningsSummary.fixedAllowanceMinor > 0) {
                    out.appendLine(listOf("Monthly Allowance", "$cur ${String.format(Locale.US, "%.2f", earningsSummary.fixedAllowanceMinor / 100.0)}").joinToString(",") { escape(it) })
                }
                if (earningsSummary.estimatedDeductionsMinor > 0) {
                    out.appendLine(listOf("Monthly Deductions", "-$cur ${String.format(Locale.US, "%.2f", earningsSummary.estimatedDeductionsMinor / 100.0)}").joinToString(",") { escape(it) })
                }
                out.appendLine(listOf("Estimated Net Payout", "$cur ${String.format(Locale.US, "%.2f", earningsSummary.estimatedNetEarningsMinor / 100.0)}").joinToString(",") { escape(it) })
            }
            out.appendLine("")

            // --- DETAILED SHIFTS TABLE ---
            out.appendLine("--- SHIFT DETAILS ---")
            out.appendLine("Date,Day,Workplace,Shift Title,Start Time,End Time,Overnight,Break (min),Net Hours,Status,Notes")
            shifts.sortedBy { it.startEpochMillis }.forEach { shift ->
                val startZoned = Instant.ofEpochMilli(shift.startEpochMillis).atZone(zone)
                val endZoned = Instant.ofEpochMilli(shift.endEpochMillis).atZone(zone)
                val netHours = String.format(Locale.US, "%.2f", shift.netWorkingMinutes / 60.0)

                out.appendLine(
                    listOf(
                        startZoned.toLocalDate().format(dateFormat),
                        startZoned.toLocalDate().format(dayOfWeekFormat),
                        shift.workplace ?: workplace?.name ?: "-",
                        shift.title,
                        startZoned.format(timeFormat),
                        endZoned.format(timeFormat),
                        if (shift.isOvernight) "Yes" else "No",
                        shift.breakDurationMinutes.toString(),
                        netHours,
                        shift.status.label,
                        shift.notes,
                    ).joinToString(",") { escape(it) },
                )
            }
            out.appendLine("")

            // --- LEAVE & ABSENCES TABLE ---
            if (leaves.isNotEmpty()) {
                out.appendLine("--- LEAVE & TIME OFF ---")
                out.appendLine("Start Date,End Date,Days,Type,Payment Status,Reason,Notes")
                leaves.sortedBy { it.startDateEpochDay }.forEach { leave ->
                    out.appendLine(
                        listOf(
                            leave.startDate.format(dateFormat),
                            leave.endDate.format(dateFormat),
                            leave.dayCount.toString(),
                            leave.type.label,
                            leave.paymentStatus.label,
                            leave.reason,
                            leave.notes,
                        ).joinToString(",") { escape(it) },
                    )
                }
            }
        }

        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }

    fun formatTimesheetText(
        workplace: Workplace?,
        shifts: List<Shift>,
        leaves: List<LeaveEntry>,
        monthlySummary: RosterMonthlySummary,
        earningsSummary: WorkplaceEarningsSummary?,
        period: YearMonth,
    ): String {
        val zone = ZoneId.systemDefault()
        val sb = StringBuilder()

        sb.appendLine("📋 TIMESHEET REPORT — ${period.format(monthFormat)}")
        sb.appendLine("Employer: ${workplace?.name ?: "All Workplaces"}")
        if (workplace != null && workplace.role.isNotBlank()) {
            sb.appendLine("Role: ${workplace.role}")
        }
        sb.appendLine("----------------------------------------")

        val totalHours = String.format(Locale.US, "%.2f", monthlySummary.totalWorkingMinutes / 60.0)
        sb.appendLine("• Total Shifts: ${monthlySummary.totalShifts} (${monthlySummary.completedShifts} completed)")
        sb.appendLine("• Total Hours: $totalHours hrs (${monthlySummary.daysWorked} days worked)")

        if (earningsSummary != null) {
            val regHours = String.format(Locale.US, "%.2f", earningsSummary.basicHoursUsed)
            val otHours = String.format(Locale.US, "%.2f", earningsSummary.overtimeHours)
            sb.appendLine("• Regular: $regHours hrs | Overtime: $otHours hrs")
            val gross = String.format(Locale.US, "%.2f", earningsSummary.grossEstimatedEarningsMinor / 100.0)
            val net = String.format(Locale.US, "%.2f", earningsSummary.estimatedNetEarningsMinor / 100.0)
            sb.appendLine("• Estimated Pay: ${earningsSummary.currency} $net ($gross gross)")
        }
        sb.appendLine("----------------------------------------")

        sb.appendLine("SHIFTS:")
        if (shifts.isEmpty()) {
            sb.appendLine("No shifts logged for this period.")
        } else {
            shifts.sortedBy { it.startEpochMillis }.forEach { s ->
                val start = Instant.ofEpochMilli(s.startEpochMillis).atZone(zone)
                val end = Instant.ofEpochMilli(s.endEpochMillis).atZone(zone)
                val duration = String.format(Locale.US, "%.1f h", s.netWorkingMinutes / 60.0)
                val overnightTag = if (s.isOvernight) " (+1d)" else ""
                val notesTag = if (s.notes.isNotBlank()) " [${s.notes}]" else ""
                sb.appendLine("• ${start.format(DateTimeFormatter.ofPattern("EEE, dd MMM"))}: ${start.format(timeFormat)} – ${end.format(timeFormat)}$overnightTag ($duration) [${s.status.label}]$notesTag")
            }
        }

        if (leaves.isNotEmpty()) {
            sb.appendLine("\nLEAVE & ABSENCES:")
            leaves.sortedBy { it.startDateEpochDay }.forEach { l ->
                val reasonTag = if (l.reason.isNotBlank()) " - ${l.reason}" else ""
                sb.appendLine("• ${l.startDate} to ${l.endDate} (${l.dayCount} d): ${l.type.emoji} ${l.type.label} (${l.paymentStatus.label})$reasonTag")
            }
        }

        return sb.toString()
    }

    fun shareCsvIntent(uri: Uri, workplaceName: String?, period: YearMonth): Intent {
        val name = workplaceName ?: "All Workplaces"
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "Timesheet - $name - ${period.format(monthFormat)}")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(send, "Export timesheet CSV")
    }

    fun shareTextIntent(text: String, workplaceName: String?, period: YearMonth): Intent {
        val name = workplaceName ?: "All Workplaces"
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
            putExtra(Intent.EXTRA_SUBJECT, "Timesheet Summary - $name - ${period.format(monthFormat)}")
        }
        return Intent.createChooser(send, "Share timesheet report")
    }

    private fun escape(raw: String): String {
        val formulaStarts = setOf('=', '+', '-', '@', '\t', '\r')
        val value = if (raw.firstOrNull() in formulaStarts) "'$raw" else raw
        return if (value.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) {
            "\"" + value.replace("\"", "\"\"") + "\""
        } else {
            value
        }
    }
}
