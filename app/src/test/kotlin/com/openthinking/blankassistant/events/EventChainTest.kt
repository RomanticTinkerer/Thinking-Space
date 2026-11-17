package com.openthinking.blankassistant.events

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

/** Messy real-world text: chats, status bars, plans said in passing. "Now" is Tue 17 Nov 2026, 9 PM. */
class EventChainTest {
    private val zone = ZoneId.of("Asia/Kolkata")
    private val now = LocalDateTime.of(2026, 11, 17, 21, 0).atZone(zone).toInstant().toEpochMilli()
    private fun parse(text: String) = EventParser.parse(text, now, zone, dayFirst = true)
    private fun CalendarEvent.at() = java.time.Instant.ofEpochMilli(start).atZone(zone).toLocalDateTime()

    @Test fun chatPlanIgnoresMessageTimes() {
        val e = parse("Sam 6:53 PM\nlet's meet tomorrow at 5\nSure\nAnu 6:55 PM\nok").single()
        assertEquals(LocalDateTime.of(2026, 11, 18, 17, 0), e.at())
        assertEquals("Let's meet", e.title)
    }

    @Test fun dayDividersAndTimestampsAreNotEvents() {
        assertTrue(parse("Monday\nsend it pls 4:07 pm\nTuesday\nok will do 7:11 pm\nToday\nthanks 4:19 pm").isEmpty())
    }

    @Test fun statusBarAndLastUpdatedAreIgnored() {
        assertTrue(parse("7:33 Mon, 9 Nov\nAir Quality\nLast Updated: 2026-11-09 12:01 (Local Time)").isEmpty())
    }

    @Test fun statusBarClockDoesNotBecomeTheTime() {
        val e = parse("9:41 5G 100\nTech talk\nNov 20 5 PM").single()
        assertEquals(LocalDateTime.of(2026, 11, 20, 17, 0), e.at())
    }

    @Test fun startingInCountsFromNow() {
        assertEquals(LocalDateTime.of(2026, 11, 17, 23, 0), parse("Meeting in 2 hours\nbring the laptop").single().at())
    }

    @Test fun lastMessageOfAChatWins() {
        val e = parse("Sam 7:38 PM\n@everyone Starting in 2 hours.\nSam 9:01 PM\n@everyone Starting is 30 minutes!!").single()
        assertEquals(LocalDateTime.of(2026, 11, 17, 21, 31), e.at())
    }

    @Test fun ordinalDayWithBareHour() {
        assertEquals(LocalDateTime.of(2026, 11, 20, 20, 0), parse("dinner on the 20th at 8").single().at())
    }

    @Test fun proseDeadlineGetsAShortTitle() {
        val e = parse("We recommend that you contact your school official in advance of our final deadline of January 6, 2027.").single()
        assertTrue(e.allDay)
        assertEquals("Final deadline", e.title)
    }

    @Test fun misreadDigitsAreFixed() {
        assertEquals(LocalDateTime.of(2026, 11, 14, 19, 30), parse("Party\nNov l4\n7:3O PM").single().at())
    }

    @Test fun idleTalkIsNotAnEvent() {
        assertTrue(parse("Tomorrow I will be fine lol\nToday was bad").isEmpty())
    }
}
