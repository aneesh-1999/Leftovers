package com.leftovers.app.util

import com.leftovers.app.data.Shift
import com.leftovers.app.data.ShiftStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class ShiftRemindersTest {

    private val zone = ZoneId.of("UTC")

    @Test
    fun `no shifts tomorrow returns null reminder`() {
        val reminder = ShiftReminders.buildNightReminder(emptyList(), alarmSuggestion = true, zone = zone)
        assertNull(reminder)
    }

    @Test
    fun `morning 7am shift prompts user to set alarm tonight`() {
        val start7am = ZonedDateTime.of(2026, 10, 10, 7, 0, 0, 0, zone).toInstant().toEpochMilli()
        val end3pm = ZonedDateTime.of(2026, 10, 10, 15, 0, 0, 0, zone).toInstant().toEpochMilli()

        val shift = Shift(
            id = 1,
            title = "Morning Opening",
            workplace = "Cafe Central",
            startEpochMillis = start7am,
            endEpochMillis = end3pm,
            breakDurationMinutes = 30,
            status = ShiftStatus.SCHEDULED,
        )

        val reminder = ShiftReminders.buildNightReminder(listOf(shift), alarmSuggestion = true, zone = zone)
        assertNotNull(reminder)
        val (title, text) = reminder!!

        assertTrue(title.contains("Tomorrow's shift"))
        assertTrue(title.contains("07:00"))
        assertTrue(text.contains("Starts at 07:00 at Cafe Central"))
        assertTrue(text.contains("Remember to set your alarm tonight! ⏰"))
    }

    @Test
    fun `morning shift with alarm suggestion disabled does not mention alarm`() {
        val start7am = ZonedDateTime.of(2026, 10, 10, 7, 0, 0, 0, zone).toInstant().toEpochMilli()
        val end3pm = ZonedDateTime.of(2026, 10, 10, 15, 0, 0, 0, zone).toInstant().toEpochMilli()

        val shift = Shift(
            id = 1,
            title = "Morning Shift",
            workplace = "Bakery",
            startEpochMillis = start7am,
            endEpochMillis = end3pm,
            status = ShiftStatus.SCHEDULED,
        )

        val reminder = ShiftReminders.buildNightReminder(listOf(shift), alarmSuggestion = false, zone = zone)
        assertNotNull(reminder)
        val (_, text) = reminder!!

        assertTrue(!text.contains("alarm"))
        assertTrue(text.contains("advance reminder"))
    }

    @Test
    fun `evening shift night reminder does not prompt alarm`() {
        val start6pm = ZonedDateTime.of(2026, 10, 10, 18, 0, 0, 0, zone).toInstant().toEpochMilli()
        val end11pm = ZonedDateTime.of(2026, 10, 10, 23, 0, 0, 0, zone).toInstant().toEpochMilli()

        val shift = Shift(
            id = 2,
            title = "Evening Service",
            workplace = "Bistro",
            startEpochMillis = start6pm,
            endEpochMillis = end11pm,
            status = ShiftStatus.SCHEDULED,
        )

        val reminder = ShiftReminders.buildNightReminder(listOf(shift), alarmSuggestion = true, zone = zone)
        assertNotNull(reminder)
        val (title, text) = reminder!!

        assertTrue(title.contains("Tomorrow's shift: Evening Service (18:00)"))
        assertTrue(text.contains("Starts at 18:00 at Bistro"))
        assertTrue(!text.contains("alarm"))
    }

    @Test
    fun `multiple shifts tomorrow highlights shift count and first start time`() {
        val start8am = ZonedDateTime.of(2026, 10, 10, 8, 0, 0, 0, zone).toInstant().toEpochMilli()
        val end12pm = ZonedDateTime.of(2026, 10, 10, 12, 0, 0, 0, zone).toInstant().toEpochMilli()
        val start4pm = ZonedDateTime.of(2026, 10, 10, 16, 0, 0, 0, zone).toInstant().toEpochMilli()
        val end8pm = ZonedDateTime.of(2026, 10, 10, 20, 0, 0, 0, zone).toInstant().toEpochMilli()

        val shift1 = Shift(
            id = 1,
            title = "Shift 1",
            workplace = "Store A",
            startEpochMillis = start8am,
            endEpochMillis = end12pm,
            status = ShiftStatus.SCHEDULED,
        )
        val shift2 = Shift(
            id = 2,
            title = "Shift 2",
            workplace = "Store B",
            startEpochMillis = start4pm,
            endEpochMillis = end8pm,
            status = ShiftStatus.SCHEDULED,
        )

        val reminder = ShiftReminders.buildNightReminder(listOf(shift2, shift1), alarmSuggestion = true, zone = zone)
        assertNotNull(reminder)
        val (title, text) = reminder!!

        assertEquals("⏰ 2 shifts scheduled tomorrow", title)
        assertTrue(text.contains("First shift starts at 08:00 at Store A"))
        assertTrue(text.contains("Don't forget to set your alarm tonight! ⏰"))
    }

    @Test
    fun `advance pre-shift reminder calculates hours away and formats notification`() {
        val now = ZonedDateTime.of(2026, 10, 10, 12, 0, 0, 0, zone).toInstant().toEpochMilli()
        val start5pm = ZonedDateTime.of(2026, 10, 10, 17, 0, 0, 0, zone).toInstant().toEpochMilli()
        val end10pm = ZonedDateTime.of(2026, 10, 10, 22, 0, 0, 0, zone).toInstant().toEpochMilli()

        val shift = Shift(
            id = 3,
            title = "Evening Shift",
            workplace = "Supermarket",
            startEpochMillis = start5pm,
            endEpochMillis = end10pm,
            status = ShiftStatus.SCHEDULED,
        )

        val (title, text) = ShiftReminders.buildAdvanceReminder(shift, now, zone = zone)
        assertEquals("💼 Shift in ~5 hours: Evening Shift", title)
        assertEquals("Starts at 17:00 at Supermarket. Time to get ready!", text)
    }

    @Test
    fun `advance reminder fallbacks to work if workplace is null or blank`() {
        val now = ZonedDateTime.of(2026, 10, 10, 13, 0, 0, 0, zone).toInstant().toEpochMilli()
        val start2pm = ZonedDateTime.of(2026, 10, 10, 14, 0, 0, 0, zone).toInstant().toEpochMilli()
        val end6pm = ZonedDateTime.of(2026, 10, 10, 18, 0, 0, 0, zone).toInstant().toEpochMilli()

        val shift = Shift(
            id = 4,
            title = "Afternoon Cover",
            workplace = "   ",
            startEpochMillis = start2pm,
            endEpochMillis = end6pm,
            status = ShiftStatus.SCHEDULED,
        )

        val (title, text) = ShiftReminders.buildAdvanceReminder(shift, now, zone = zone)
        assertEquals("💼 Shift in ~1 hours: Afternoon Cover", title)
        assertEquals("Starts at 14:00 at work. Time to get ready!", text)
    }

    @Test
    fun `post-shift completion reminder prompts user to complete or cancel schedule`() {
        val start7am = ZonedDateTime.of(2026, 10, 10, 7, 0, 0, 0, zone).toInstant().toEpochMilli()
        val end330pm = ZonedDateTime.of(2026, 10, 10, 15, 30, 0, 0, zone).toInstant().toEpochMilli()

        val shift = Shift(
            id = 5,
            title = "Day Shift",
            workplace = "Cafe Bar",
            startEpochMillis = start7am,
            endEpochMillis = end330pm,
            status = ShiftStatus.SCHEDULED,
        )

        val (title, text) = ShiftReminders.buildPostShiftReminder(shift, zone = zone)
        assertEquals("✅ Shift finished: Day Shift", title)
        assertTrue(text.contains("Ended at 15:30 (Cafe Bar)"))
        assertTrue(text.contains("Tap Complete or Cancel to update your schedule."))
    }
}
