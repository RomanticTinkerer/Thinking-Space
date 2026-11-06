package com.openthinking.blankassistant.events

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class EventParserTest {
    private val zone = ZoneId.of("Asia/Kolkata")
    private val now = LocalDateTime.of(2026, 11, 7, 12, 0).atZone(zone).toInstant().toEpochMilli()
    private fun parse(text: String) = EventParser.parse(text, now, zone, dayFirst = true)
    private fun CalendarEvent.startTime() = java.time.Instant.ofEpochMilli(start).atZone(zone).toLocalDateTime()

    @Test fun dateTimeAndLocation() {
        val e = parse("Birthday Party at Priya's\nFri, Nov 14 \u00b7 7:30 PM\nWhere: 12 Park Street").single()
        assertEquals("Birthday Party at Priya's", e.title)
        assertEquals(LocalDateTime.of(2026, 11, 14, 19, 30), e.startTime())
        assertEquals("12 Park Street", e.location)
    }

    @Test fun timeRangeInheritsPm() {
        val e = parse("Tech Talk\nSaturday, 22 November 2026\n7-9 PM").single()
        assertEquals(2 * 60 * 60 * 1000L, e.end - e.start)
    }

    @Test fun scheduleGivesOneEventPerTime() {
        assertEquals(3, parse("Day 1\nDec 3, 2026\n9:00 AM Registration\n10:00 AM Keynote\n12:30 PM Lunch").size)
    }

    @Test fun dateWithoutTimeIsAllDay() = assertTrue(parse("Hackathon\nNov 14 - 16").single().allDay)

    @Test fun lonelyTimeIsNotAnEvent() = assertTrue(parse("call the bank 5:30 PM").isEmpty())
}
