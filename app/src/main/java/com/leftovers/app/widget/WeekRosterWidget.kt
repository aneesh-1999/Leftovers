package com.leftovers.app.widget

import android.content.Context
import android.content.Intent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.leftovers.app.LeftoversApp
import com.leftovers.app.MainActivity
import com.leftovers.app.data.LeaveType
import com.leftovers.app.data.Shift
import com.leftovers.app.data.ShiftStatus
import com.leftovers.app.util.Money
import kotlinx.coroutines.flow.first
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import java.util.Locale

private data class DayWidgetInfo(
    val date: LocalDate,
    val dayName: String,
    val dayNumber: String,
    val isToday: Boolean,
    val hasShift: Boolean,
    val shiftTag: String,
    val isCompleted: Boolean,
    val isLeave: Boolean,
)

private data class WeekRosterState(
    val rangeText: String,
    val totalHoursText: String,
    val totalShiftsText: String,
    val estimatedPayText: String?,
    val todayHeader: String,
    val todaySubtitle: String,
    val todayShiftId: Long?,
    val todayShiftIsCompleted: Boolean,
    val days: List<DayWidgetInfo>,
)

/** Home-screen widget showing full current week roster (Mon to Sun) with live Today highlight and shift completion. */
class WeekRosterWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val state = load(context)
        provideContent { Content(context, state) }
    }

    private suspend fun load(context: Context): WeekRosterState {
        val container = (context.applicationContext as LeftoversApp).container
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)

        val startOfWeek = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val endOfWeek = startOfWeek.plusDays(6)

        val allShifts = container.roster.shifts.first()
        val allLeaves = container.roster.leaves.first()
        val workplaces = container.roster.workplaces.first().associateBy { it.id }
        val settings = container.settings.settings.first()
        val money = Money(settings.currencyCode)

        val timeFormat = DateTimeFormatter.ofPattern("HH:mm", Locale.US)
        val monthDayFormat = DateTimeFormatter.ofPattern("MMM d", Locale.US)
        val fullDayFormat = DateTimeFormatter.ofPattern("EEEE, MMM d", Locale.US)

        val rangeText = "${startOfWeek.format(monthDayFormat)} – ${endOfWeek.format(monthDayFormat)}"

        // Filter shifts for this current week (Monday to Sunday)
        val weekShifts = allShifts.filter { shift ->
            val shiftDate = shift.startDate
            !shiftDate.isBefore(startOfWeek) && !shiftDate.isAfter(endOfWeek) && shift.status != ShiftStatus.CANCELLED
        }

        val totalWorkingMinutes = weekShifts.sumOf { it.netWorkingMinutes }
        val hours = totalWorkingMinutes / 60
        val mins = totalWorkingMinutes % 60
        val totalHoursText = if (mins > 0) "${hours}h ${mins}m" else "${hours}h"
        val totalShiftsText = when (val count = weekShifts.size) {
            0 -> "0 shifts"
            1 -> "1 shift"
            else -> "$count shifts"
        }

        // Calculate estimated earnings for this week
        var totalPayMinor = 0L
        for (shift in weekShifts) {
            val wp = shift.workplaceId?.let { workplaces[it] }
            if (wp != null && wp.basicHourlyRateCents > 0) {
                totalPayMinor += (shift.netWorkingHours * wp.basicHourlyRateCents).toLong()
            }
        }
        val estimatedPayText = if (totalPayMinor > 0) money.formatWhole(totalPayMinor) else null

        // 2-letter labels: Mo, Tu, We, Th, Fr, Sa, Su
        val dayNames = listOf("Mo", "Tu", "We", "Th", "Fr", "Sa", "Su")

        // Build all 7 days from Monday (0) to Sunday (6)
        val dayList = (0..6).map { idx ->
            val date = startOfWeek.plusDays(idx.toLong())
            val isToday = date == today
            val dayShifts = weekShifts.filter { it.startDate == date }
            val dayLeaves = allLeaves.filter { it.containsDate(date) }

            val primaryShift = dayShifts.firstOrNull()
            val primaryLeave = dayLeaves.firstOrNull()

            val hasShift = primaryShift != null
            val isCompleted = dayShifts.any { it.status == ShiftStatus.COMPLETED }
            val isLeave = primaryLeave != null && !hasShift

            val shiftTag = when {
                isCompleted -> "✓"
                primaryShift != null -> {
                    val shiftHours = primaryShift.netWorkingMinutes / 60.0
                    if (shiftHours % 1.0 == 0.0) "${shiftHours.toInt()}h" else String.format(Locale.US, "%.1fh", shiftHours)
                }
                primaryLeave != null -> "Off"
                else -> "—"
            }

            DayWidgetInfo(
                date = date,
                dayName = dayNames.getOrElse(idx) { date.dayOfWeek.name.take(2) },
                dayNumber = date.dayOfMonth.toString(),
                isToday = isToday,
                hasShift = hasShift,
                shiftTag = shiftTag,
                isCompleted = isCompleted,
                isLeave = isLeave,
            )
        }

        // Today's schedule details
        val todayShifts = weekShifts.filter { it.startDate == today }
        val todayLeaves = allLeaves.filter { it.containsDate(today) }
        val primaryTodayShift = todayShifts.firstOrNull()

        val todayHeader = "TODAY · ${today.format(fullDayFormat).uppercase(Locale.US)}"

        val (todaySubtitle, shiftId, isCompleted) = when {
            primaryTodayShift != null -> {
                val start = Instant.ofEpochMilli(primaryTodayShift.startEpochMillis).atZone(zone).toLocalTime().format(timeFormat)
                val end = Instant.ofEpochMilli(primaryTodayShift.endEpochMillis).atZone(zone).toLocalTime().format(timeFormat)
                val wpName = primaryTodayShift.workplace ?: primaryTodayShift.workplaceId?.let { workplaces[it]?.name }
                val wpLabel = if (!wpName.isNullOrBlank()) " · $wpName" else ""
                val shiftHours = primaryTodayShift.netWorkingMinutes / 60.0
                val hoursStr = if (shiftHours % 1.0 == 0.0) "${shiftHours.toInt()}h" else String.format(Locale.US, "%.1fh", shiftHours)
                val completed = primaryTodayShift.status == ShiftStatus.COMPLETED

                Triple("$start – $end$wpLabel ($hoursStr)", primaryTodayShift.id, completed)
            }
            todayLeaves.isNotEmpty() -> {
                val l = todayLeaves.first()
                Triple("${l.type.label} ${l.type.emoji}", null, false)
            }
            else -> {
                Triple("Day Off / No shifts scheduled", null, false)
            }
        }

        return WeekRosterState(
            rangeText = rangeText,
            totalHoursText = totalHoursText,
            totalShiftsText = totalShiftsText,
            estimatedPayText = estimatedPayText,
            todayHeader = todayHeader,
            todaySubtitle = todaySubtitle,
            todayShiftId = shiftId,
            todayShiftIsCompleted = isCompleted,
            days = dayList,
        )
    }

    @androidx.compose.runtime.Composable
    private fun Content(context: Context, state: WeekRosterState) {
        val white = ColorProvider(Color(0xFFF5F5F7))
        val accent = Color(0xFFCDF46F)
        val accentDark = ColorProvider(Color(0xFF151A04))

        val openRosterIntent = Intent(context, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_OPEN_ROSTER, true)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)

        Column(
            GlanceModifier
                .fillMaxSize()
                .cornerRadius(22.dp)
                .background(Color(0xFF14141A))
                .padding(8.dp)
                .clickable(actionStartActivity(openRosterIntent)),
        ) {
            // Header: Range & Total Hours Pill
            Row(
                GlanceModifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(GlanceModifier.defaultWeight()) {
                    Text(
                        text = "WEEK ROSTER",
                        style = TextStyle(color = ColorProvider(accent), fontSize = 10.sp, fontWeight = FontWeight.Bold),
                    )
                    Spacer(GlanceModifier.height(1.dp))
                    Text(
                        text = state.rangeText,
                        style = TextStyle(color = white, fontSize = 12.sp, fontWeight = FontWeight.Bold),
                        maxLines = 1,
                    )
                }

                // Summary Pill (Total shifts & hours)
                Row(
                    GlanceModifier
                        .cornerRadius(8.dp)
                        .background(Color(0xFF22222E))
                        .padding(horizontal = 7.dp, vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "${state.totalShiftsText} · ${state.totalHoursText}",
                        style = TextStyle(
                            color = ColorProvider(accent),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                        ),
                    )
                }
            }

            Spacer(GlanceModifier.height(6.dp))

            // 7 Days Strip (Mo, Tu, We, Th, Fr, Sa, Su) - ALL 7 DAYS FIT EQUALLY (7 children in Row <= 10 limit)
            Row(
                GlanceModifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                state.days.forEach { day ->
                    DayPill(
                        modifier = GlanceModifier.defaultWeight().padding(horizontal = 1.dp),
                        day = day,
                    )
                }
            }

            Spacer(GlanceModifier.height(6.dp))

            // Bottom Today Schedule Banner with Instant Shift Complete Action
            Row(
                GlanceModifier
                    .fillMaxWidth()
                    .cornerRadius(10.dp)
                    .background(Color(0xFF1E1E28))
                    .padding(horizontal = 8.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(GlanceModifier.defaultWeight()) {
                    Text(
                        text = state.todayHeader,
                        style = TextStyle(
                            color = ColorProvider(accent),
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                        ),
                        maxLines = 1,
                    )
                    Spacer(GlanceModifier.height(1.dp))
                    Text(
                        text = state.todaySubtitle,
                        style = TextStyle(
                            color = white,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                        ),
                        maxLines = 1,
                    )
                }

                // Interactive Complete / Completed Action Button
                if (state.todayShiftId != null) {
                    Spacer(GlanceModifier.width(6.dp))
                    Box(
                        GlanceModifier
                            .cornerRadius(8.dp)
                            .background(if (state.todayShiftIsCompleted) accent else Color(0xFF323246))
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                            .clickable(
                                actionRunCallback<ToggleShiftCompleteAction>(
                                    actionParametersOf(ToggleShiftCompleteAction.KeyShiftId to state.todayShiftId),
                                ),
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = if (state.todayShiftIsCompleted) "✓ Done" else "✓ Complete",
                            style = TextStyle(
                                color = if (state.todayShiftIsCompleted) accentDark else ColorProvider(accent),
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                            ),
                        )
                    }
                }
            }
        }
    }

    @androidx.compose.runtime.Composable
    private fun DayPill(modifier: GlanceModifier, day: DayWidgetInfo) {
        val accent = Color(0xFFCDF46F)
        val accentDark = ColorProvider(Color(0xFF151A04))
        val white = ColorProvider(Color(0xFFF5F5F7))
        val muted = ColorProvider(Color(0xFF8E8E98))

        // Vibrant Lime Accent Background for TODAY!
        val bgColor = when {
            day.isToday -> accent
            day.hasShift -> Color(0xFF20202C)
            else -> Color(0xFF171720)
        }

        val textColor = when {
            day.isToday -> accentDark
            day.hasShift -> white
            else -> muted
        }

        Column(
            modifier = modifier
                .cornerRadius(6.dp)
                .background(bgColor)
                .padding(vertical = 3.dp, horizontal = 1.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Day Name (Mo, Tu, We, Th, Fr, Sa, Su)
            Text(
                text = day.dayName,
                style = TextStyle(
                    color = textColor,
                    fontSize = 9.sp,
                    fontWeight = if (day.isToday) FontWeight.Bold else FontWeight.Medium,
                ),
                maxLines = 1,
            )

            Spacer(GlanceModifier.height(1.dp))

            // Day Number (e.g. 10)
            Text(
                text = day.dayNumber,
                style = TextStyle(
                    color = textColor,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                ),
                maxLines = 1,
            )

            Spacer(GlanceModifier.height(1.dp))

            // Shift status / duration
            Text(
                text = day.shiftTag,
                style = TextStyle(
                    color = when {
                        day.isToday -> accentDark
                        day.isCompleted -> ColorProvider(accent)
                        day.hasShift -> white
                        else -> muted
                    },
                    fontSize = 8.sp,
                    fontWeight = if (day.hasShift || day.isToday) FontWeight.Bold else FontWeight.Normal,
                ),
                maxLines = 1,
            )
        }
    }
}

/** Action callback to toggle shift status between COMPLETED and SCHEDULED. */
class ToggleShiftCompleteAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val shiftId = parameters[KeyShiftId] ?: return
        val container = (context.applicationContext as LeftoversApp).container
        val shift = container.roster.getShiftById(shiftId)
        if (shift != null) {
            val newStatus = if (shift.status == ShiftStatus.COMPLETED) ShiftStatus.SCHEDULED else ShiftStatus.COMPLETED
            container.roster.saveShift(shift.copy(status = newStatus))
            WeekRosterWidget().update(context, glanceId)
        }
    }

    companion object {
        val KeyShiftId = ActionParameters.Key<Long>("shiftId")
    }
}

class WeekRosterWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = WeekRosterWidget()
}
