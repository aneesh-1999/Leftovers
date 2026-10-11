package com.leftovers.app.widget

import android.content.Context
import android.content.Intent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
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
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.currentState
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
import androidx.glance.state.GlanceStateDefinition
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.leftovers.app.LeftoversApp
import com.leftovers.app.MainActivity
import com.leftovers.app.data.LeaveEntry
import com.leftovers.app.data.Shift
import com.leftovers.app.data.ShiftStatus
import com.leftovers.app.data.Workplace
import com.leftovers.app.util.Money
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import kotlinx.coroutines.flow.first
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import java.util.Locale

val KEY_WEEK_OFFSET = intPreferencesKey("roster_week_offset")
val KEY_SELECTED_DAY = longPreferencesKey("roster_selected_day")
val KEY_REVISION = longPreferencesKey("roster_data_revision")

private data class RosterSnapshot(
    val shifts: List<Shift>,
    val leaves: List<LeaveEntry>,
    val workplaces: Map<Long, Workplace>,
    val currencyCode: String,
)

private data class DayWidgetInfo(
    val date: LocalDate,
    val dayName: String,
    val dayNumber: String,
    val isToday: Boolean,
    val isSelected: Boolean,
    val hasShift: Boolean,
    val shiftTag: String,
    val isCompleted: Boolean,
    val isLeave: Boolean,
)

private data class WeekRosterState(
    val weekOffset: Int,
    val rangeText: String,
    val totalHoursText: String,
    val totalShiftsText: String,
    val estimatedPayText: String?,
    val selectedDayHeader: String,
    val selectedDaySubtitle: String,
    val selectedDayShiftId: Long?,
    val selectedDayShiftIsCompleted: Boolean,
    val days: List<DayWidgetInfo>,
)

/**
 * Fully reactive home-screen widget showing the week roster (Mon to Sun) with:
 * - Live Today highlight & interactive Day selection (tap any day to see its schedule directly on the widget)
 * - Week navigation (◀ / ▶ and Today reset) without opening the app
 * - Direct shift completion toggle (✓ Complete / ✓ Done) without launching the app
 * - Dedicated "Open Roster ↗" button to launch the app only when intended
 */
class WeekRosterWidget : GlanceAppWidget() {

    override var stateDefinition: GlanceStateDefinition<*> = PreferencesGlanceStateDefinition

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val initialData = loadRosterData(context)

        provideContent {
            val prefs = currentState<Preferences>()
            val weekOffset = prefs[KEY_WEEK_OFFSET] ?: 0
            val selectedEpochDay = prefs[KEY_SELECTED_DAY]
            val revision = prefs[KEY_REVISION] ?: 0L

            val snapshot by produceState(initialValue = initialData, key1 = revision) {
                value = loadRosterData(context)
            }

            val state = computeState(snapshot, weekOffset, selectedEpochDay)
            Content(context, state)
        }
    }

    private suspend fun loadRosterData(context: Context): RosterSnapshot {
        val container = (context.applicationContext as LeftoversApp).container
        val shifts = container.roster.shifts.first()
        val leaves = container.roster.leaves.first()
        val workplaces = container.roster.workplaces.first().associateBy { it.id }
        val settings = container.settings.settings.first()
        return RosterSnapshot(shifts, leaves, workplaces, settings.currencyCode)
    }

    private fun computeState(
        snapshot: RosterSnapshot,
        weekOffset: Int,
        selectedEpochDay: Long?,
    ): WeekRosterState {
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)

        val baseDate = today.plusWeeks(weekOffset.toLong())
        val startOfWeek = baseDate.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val endOfWeek = startOfWeek.plusDays(6)

        // Selected day calculation:
        val selectedDate = if (selectedEpochDay != null) {
            val d = LocalDate.ofEpochDay(selectedEpochDay)
            if (d.isBefore(startOfWeek) || d.isAfter(endOfWeek)) {
                if (weekOffset == 0) today else startOfWeek
            } else {
                d
            }
        } else {
            if (weekOffset == 0) today else startOfWeek
        }

        val allShifts = snapshot.shifts
        val allLeaves = snapshot.leaves
        val workplaces = snapshot.workplaces
        val money = Money(snapshot.currencyCode)

        val timeFormat = DateTimeFormatter.ofPattern("HH:mm", Locale.US)
        val monthDayFormat = DateTimeFormatter.ofPattern("MMM d", Locale.US)
        val fullDayFormat = DateTimeFormatter.ofPattern("EEEE, MMM d", Locale.US)

        val rangeText = "${startOfWeek.format(monthDayFormat)} – ${endOfWeek.format(monthDayFormat)}"

        // Filter shifts for the displayed week (Monday to Sunday)
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
            val isSelected = date == selectedDate
            val dayShifts = weekShifts.filter { it.startDate == date }
            val dayLeaves = allLeaves.filter { it.containsDate(date) }

            val primaryShift = dayShifts.firstOrNull()
            val primaryLeave = dayLeaves.firstOrNull()

            val hasShift = primaryShift != null
            val isCompleted = dayShifts.any { it.status == ShiftStatus.COMPLETED }
            val isLeave = primaryLeave != null && !hasShift

            val shiftTag = when {
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
                isSelected = isSelected,
                hasShift = hasShift,
                shiftTag = shiftTag,
                isCompleted = isCompleted,
                isLeave = isLeave,
            )
        }

        // Schedule details for the currently selected day
        val selectedShifts = weekShifts.filter { it.startDate == selectedDate }
        val selectedLeaves = allLeaves.filter { it.containsDate(selectedDate) }
        val primarySelectedShift = selectedShifts.firstOrNull()

        val statusSuffix = when {
            primarySelectedShift == null -> ""
            primarySelectedShift.status == ShiftStatus.COMPLETED -> " · DONE ✓"
            else -> " · SCHEDULED"
        }

        val selectedDayHeader = when {
            selectedDate == today -> "TODAY · ${selectedDate.format(fullDayFormat).uppercase(Locale.US)}$statusSuffix"
            selectedDate == today.plusDays(1) -> "TOMORROW · ${selectedDate.format(fullDayFormat).uppercase(Locale.US)}$statusSuffix"
            selectedDate == today.minusDays(1) -> "YESTERDAY · ${selectedDate.format(fullDayFormat).uppercase(Locale.US)}$statusSuffix"
            else -> "${selectedDate.format(fullDayFormat).uppercase(Locale.US)}$statusSuffix"
        }

        val (selectedDaySubtitle, shiftId, isCompleted) = when {
            primarySelectedShift != null -> {
                val start = Instant.ofEpochMilli(primarySelectedShift.startEpochMillis).atZone(zone).toLocalTime().format(timeFormat)
                val end = Instant.ofEpochMilli(primarySelectedShift.endEpochMillis).atZone(zone).toLocalTime().format(timeFormat)
                val wpName = primarySelectedShift.workplace ?: primarySelectedShift.workplaceId?.let { workplaces[it]?.name }
                val wpLabel = if (!wpName.isNullOrBlank()) " · $wpName" else ""
                val shiftHours = primarySelectedShift.netWorkingMinutes / 60.0
                val hoursStr = if (shiftHours % 1.0 == 0.0) "${shiftHours.toInt()}h" else String.format(Locale.US, "%.1fh", shiftHours)
                val completed = primarySelectedShift.status == ShiftStatus.COMPLETED

                Triple("$start – $end$wpLabel ($hoursStr)", primarySelectedShift.id, completed)
            }
            selectedLeaves.isNotEmpty() -> {
                val l = selectedLeaves.first()
                Triple("${l.type.label} ${l.type.emoji}", null, false)
            }
            else -> {
                Triple("Day Off / No shift scheduled", null, false)
            }
        }

        return WeekRosterState(
            weekOffset = weekOffset,
            rangeText = rangeText,
            totalHoursText = totalHoursText,
            totalShiftsText = totalShiftsText,
            estimatedPayText = estimatedPayText,
            selectedDayHeader = selectedDayHeader,
            selectedDaySubtitle = selectedDaySubtitle,
            selectedDayShiftId = shiftId,
            selectedDayShiftIsCompleted = isCompleted,
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
                .cornerRadius(20.dp)
                .background(Color(0xFF13131A))
                .padding(8.dp),
        ) {
            // Header Row: Title & Week Range on left (tap opens app), Navigation & Summary on right
            Row(
                GlanceModifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Title and Range (tapping explicitly opens the Roster screen in the app)
                Column(
                    modifier = GlanceModifier
                        .defaultWeight()
                        .clickable(actionStartActivity(openRosterIntent)),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "WEEK ROSTER",
                            style = TextStyle(color = ColorProvider(accent), fontSize = 10.sp, fontWeight = FontWeight.Bold),
                        )
                        Spacer(GlanceModifier.width(3.dp))
                        Text(
                            text = "↗",
                            style = TextStyle(color = ColorProvider(accent), fontSize = 10.sp, fontWeight = FontWeight.Bold),
                        )
                    }
                    Spacer(GlanceModifier.height(1.dp))
                    Text(
                        text = state.rangeText,
                        style = TextStyle(color = white, fontSize = 12.sp, fontWeight = FontWeight.Bold),
                        maxLines = 1,
                    )
                }

                // Interactive Navigation (◀ / ▶ / Today) & Summary Pill
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Reset to Today button (visible when navigating away from current week)
                    if (state.weekOffset != 0) {
                        Box(
                            modifier = GlanceModifier
                                .cornerRadius(6.dp)
                                .background(Color(0xFF28283A))
                                .padding(horizontal = 6.dp, vertical = 3.dp)
                                .clickable(actionRunCallback<ResetToTodayAction>()),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = "Today",
                                style = TextStyle(color = ColorProvider(accent), fontSize = 9.sp, fontWeight = FontWeight.Bold),
                            )
                        }
                        Spacer(GlanceModifier.width(4.dp))
                    }

                    // Previous Week button ◀
                    Box(
                        modifier = GlanceModifier
                            .cornerRadius(6.dp)
                            .background(Color(0xFF20202C))
                            .padding(horizontal = 6.dp, vertical = 3.dp)
                            .clickable(
                                actionRunCallback<ShiftWeekOffsetAction>(
                                    actionParametersOf(ShiftWeekOffsetAction.KeyDelta to -1),
                                ),
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "◀",
                            style = TextStyle(color = white, fontSize = 10.sp, fontWeight = FontWeight.Bold),
                        )
                    }

                    Spacer(GlanceModifier.width(4.dp))

                    // Next Week button ▶
                    Box(
                        modifier = GlanceModifier
                            .cornerRadius(6.dp)
                            .background(Color(0xFF20202C))
                            .padding(horizontal = 6.dp, vertical = 3.dp)
                            .clickable(
                                actionRunCallback<ShiftWeekOffsetAction>(
                                    actionParametersOf(ShiftWeekOffsetAction.KeyDelta to 1),
                                ),
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "▶",
                            style = TextStyle(color = white, fontSize = 10.sp, fontWeight = FontWeight.Bold),
                        )
                    }

                    Spacer(GlanceModifier.width(6.dp))

                    // Summary Pill (Total shifts & hours)
                    Row(
                        GlanceModifier
                            .cornerRadius(8.dp)
                            .background(Color(0xFF20202C))
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
            }

            Spacer(GlanceModifier.height(6.dp))

            // 7 Days Strip (Mo, Tu, We, Th, Fr, Sa, Su) - EXACTLY 7 children in Row
            // Each day pill is individually interactive: tap to select and view that day without opening the app!
            Row(
                GlanceModifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                state.days.forEach { day ->
                    DayPill(
                        modifier = GlanceModifier
                            .defaultWeight()
                            .padding(horizontal = 1.dp)
                            .clickable(
                                actionRunCallback<SelectDayAction>(
                                    actionParametersOf(SelectDayAction.KeyEpochDay to day.date.toEpochDay()),
                                ),
                            ),
                        day = day,
                    )
                }
            }

            Spacer(GlanceModifier.height(6.dp))

            // Bottom Selected Day Schedule Banner with Direct Shift Complete Action
            Row(
                GlanceModifier
                    .fillMaxWidth()
                    .cornerRadius(10.dp)
                    .background(Color(0xFF1C1C26))
                    .padding(horizontal = 8.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(
                    modifier = GlanceModifier
                        .defaultWeight()
                        .clickable(actionStartActivity(openRosterIntent)),
                ) {
                    Text(
                        text = state.selectedDayHeader,
                        style = TextStyle(
                            color = ColorProvider(accent),
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                        ),
                        maxLines = 1,
                    )
                    Spacer(GlanceModifier.height(1.dp))
                    Text(
                        text = state.selectedDaySubtitle,
                        style = TextStyle(
                            color = white,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                        ),
                        maxLines = 1,
                    )
                }

                // Interactive Complete / Completed Action Button (Acts directly without opening app!)
                if (state.selectedDayShiftId != null) {
                    Spacer(GlanceModifier.width(6.dp))
                    Box(
                        GlanceModifier
                            .width(74.dp)
                            .height(28.dp)
                            .cornerRadius(8.dp)
                            .background(if (state.selectedDayShiftIsCompleted) accent else Color(0xFF28283A))
                            .clickable(
                                actionRunCallback<ToggleShiftCompleteAction>(
                                    actionParametersOf(ToggleShiftCompleteAction.KeyShiftId to state.selectedDayShiftId),
                                ),
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = if (state.selectedDayShiftIsCompleted) "✓ Done" else "Mark Done",
                            style = TextStyle(
                                color = if (state.selectedDayShiftIsCompleted) accentDark else ColorProvider(accent),
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

        // Vibrant Lime Accent Background for TODAY or Selected Day
        val bgColor = when {
            day.isToday -> accent
            day.isSelected -> Color(0xFF2E3048)
            day.hasShift -> Color(0xFF20202C)
            else -> Color(0xFF16161F)
        }

        val textColor = when {
            day.isToday -> accentDark
            day.isSelected -> ColorProvider(accent)
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
                    fontWeight = if (day.isToday || day.isSelected) FontWeight.Bold else FontWeight.Medium,
                ),
                maxLines = 1,
            )

            Spacer(GlanceModifier.height(1.dp))

            // Day Number (e.g. 11)
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

            // Shift status / duration tag
            Text(
                text = day.shiftTag,
                style = TextStyle(
                    color = when {
                        day.isToday -> accentDark
                        day.isCompleted -> ColorProvider(accent)
                        day.isSelected -> ColorProvider(accent)
                        day.hasShift -> white
                        else -> muted
                    },
                    fontSize = 8.sp,
                    fontWeight = if (day.hasShift || day.isToday || day.isSelected) FontWeight.Bold else FontWeight.Normal,
                ),
                maxLines = 1,
            )
        }
    }
}

/** Action callback to select a specific day in the widget without opening the app. */
class SelectDayAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val epochDay = parameters[KeyEpochDay] ?: return
        updateAppWidgetState(context, PreferencesGlanceStateDefinition, glanceId) { prefs ->
            prefs.toMutablePreferences().apply {
                this[KEY_SELECTED_DAY] = epochDay
            }
        }
        WeekRosterWidget().update(context, glanceId)
    }

    companion object {
        val KeyEpochDay = ActionParameters.Key<Long>("roster_epoch_day")
    }
}

/** Action callback to navigate weeks forward or backward on the widget. */
class ShiftWeekOffsetAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val delta = parameters[KeyDelta] ?: return
        updateAppWidgetState(context, PreferencesGlanceStateDefinition, glanceId) { prefs ->
            val current = prefs[KEY_WEEK_OFFSET] ?: 0
            prefs.toMutablePreferences().apply {
                this[KEY_WEEK_OFFSET] = current + delta
                this.remove(KEY_SELECTED_DAY)
            }
        }
        WeekRosterWidget().update(context, glanceId)
    }

    companion object {
        val KeyDelta = ActionParameters.Key<Int>("roster_week_delta")
    }
}

/** Action callback to reset view back to current week and today. */
class ResetToTodayAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        updateAppWidgetState(context, PreferencesGlanceStateDefinition, glanceId) { prefs ->
            prefs.toMutablePreferences().apply {
                this[KEY_WEEK_OFFSET] = 0
                this[KEY_SELECTED_DAY] = LocalDate.now().toEpochDay()
            }
        }
        WeekRosterWidget().update(context, glanceId)
    }
}

/** Action callback to toggle shift status between COMPLETED and SCHEDULED directly from widget. */
class ToggleShiftCompleteAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val shiftId = parameters[KeyShiftId] ?: return
        val container = (context.applicationContext as LeftoversApp).container
        val shift = container.roster.getShiftById(shiftId)
        if (shift != null) {
            val newStatus = if (shift.status == ShiftStatus.COMPLETED) ShiftStatus.SCHEDULED else ShiftStatus.COMPLETED
            container.roster.setShiftStatus(shiftId, newStatus)
            updateAppWidgetState(context, PreferencesGlanceStateDefinition, glanceId) { prefs ->
                prefs.toMutablePreferences().apply {
                    this[KEY_REVISION] = System.currentTimeMillis()
                }
            }
            WeekRosterWidget().update(context, glanceId)
        }
    }

    companion object {
        val KeyShiftId = ActionParameters.Key<Long>("roster_shift_id")
    }
}

class WeekRosterWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = WeekRosterWidget()
}
