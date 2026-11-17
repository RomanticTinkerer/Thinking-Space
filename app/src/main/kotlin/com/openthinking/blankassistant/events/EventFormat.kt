package com.openthinking.blankassistant.events

import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/** One way to be reminded about an event: what to show, and when the alarm goes off. */
data class ReminderOption(val label: String, val at: Long, val preferred: Boolean = false)

/** "Fri, 21 Nov · 6:30 PM", or "Fri, 21 Nov · All day". All-day events are UTC midnights, so they are read in UTC. */
fun CalendarEvent.whenText(zone: ZoneId = ZoneId.systemDefault(), locale: Locale = Locale.getDefault()): String {
    val day = DateTimeFormatter.ofPattern("EEE, d MMM", locale)
    if (allDay) return Instant.ofEpochMilli(start).atZone(ZoneOffset.UTC).format(day) + " \u00b7 All day"
    val t = Instant.ofEpochMilli(start).atZone(zone)
    return t.format(day) + " \u00b7 " + t.format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale))
}

/** Alarm times that are still ahead of [now]. All-day events ring at 9 AM, since they have no time of their own. */
fun CalendarEvent.reminderOptions(
    now: Long = System.currentTimeMillis(),
    zone: ZoneId = ZoneId.systemDefault(),
): List<ReminderOption> {
    val hour = 3_600_000L
    val all = if (allDay) {
        val nine = Instant.ofEpochMilli(start).atZone(ZoneOffset.UTC).toLocalDate().atTime(9, 0)
        fun at(days: Long) = nine.minusDays(days).atZone(zone).toInstant().toEpochMilli()
        listOf(ReminderOption("9 AM that day", at(0), true), ReminderOption("9 AM the day before", at(1)))
    } else {
        listOf(
            ReminderOption("At event time", start),
            ReminderOption("15 minutes before", start - hour / 4),
            ReminderOption("1 hour before", start - hour, true),
            ReminderOption("1 day before", start - 24 * hour),
        )
    }
    return all.filter { it.at > now }
}

/** The alarm a one-tap "Remind me" sets. */
fun List<ReminderOption>.default(): ReminderOption? = firstOrNull { it.preferred } ?: firstOrNull()
