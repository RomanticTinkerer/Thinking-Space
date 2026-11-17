package com.openthinking.blankassistant.events

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Locale

class EventFormatTest {
    private val zone = ZoneId.of("Asia/Kolkata")
    private val now = LocalDateTime.of(2026, 11, 7, 12, 0).atZone(zone).toInstant().toEpochMilli()
    private fun parse(text: String) = EventParser.parse(text, now, zone, dayFirst = true).single()

    @Test fun timedEventKeepsOnlyFutureAlarms() {
        val e = parse("Party\nFri, Nov 7 \u00b7 12:30 PM") // half an hour from now
        assertEquals(listOf("At event time", "15 minutes before"), e.reminderOptions(now, zone).map { it.label })
        assertEquals("At event time", e.reminderOptions(now, zone).default()?.label)
    }

    @Test fun timedEventDefaultsToOneHourBefore() {
        assertEquals("1 hour before", parse("Party\nFri, Nov 14 \u00b7 7:30 PM").reminderOptions(now, zone).default()?.label)
    }

    @Test fun allDayEventRingsAtNine() {
        val o = parse("Diwali\nNov 14, 2026").reminderOptions(now, zone).default()!!
        assertEquals(LocalDateTime.of(2026, 11, 14, 9, 0), java.time.Instant.ofEpochMilli(o.at).atZone(zone).toLocalDateTime())
    }

    @Test fun pastEventHasNoAlarm() {
        assertTrue(parse("Old\nNov 1, 2026\n6 PM").reminderOptions(now, zone).isEmpty())
    }

    @Test fun whenText() {
        assertEquals("Sat, 14 Nov \u00b7 All day", parse("Diwali\nNov 14, 2026").whenText(zone, Locale.ENGLISH))
    }
}
