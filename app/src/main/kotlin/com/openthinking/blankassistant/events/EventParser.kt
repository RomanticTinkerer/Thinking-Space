package com.openthinking.blankassistant.events

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Locale

/** A calendar entry found in text. [start] and [end] are epoch millis (all-day events use UTC midnights, as Android calendars expect). */
data class CalendarEvent(
    val title: String,
    val start: Long,
    val end: Long,
    val allDay: Boolean,
    val location: String?,
    val notes: String,
)

/**
 * Finds events in OCR text with plain rules: no model, no network.
 *
 * It looks for dates ("Fri, Nov 14", "14th November 2026", "14/11/2026", "2026-11-14", "Nov 14 - 16"),
 * times ("7:30 PM", "7-9 PM", "19:30", "noon") and, only when the text has no real date, words like
 * "tomorrow" or "next Friday". A time belongs to the closest date (a schedule under a date heading counts). A date with several
 * times (a schedule) gives several events; a date with no time gives an all-day event.
 *
 * ponytail: English only, and a time with no date anywhere is ignored (a clock in the status bar is not an event).
 */
object EventParser {
    private const val MONTH = "(jan(?:uary)?|feb(?:ruary)?|mar(?:ch)?|apr(?:il)?|may|june?|july?|aug(?:ust)?|sep(?:t(?:ember)?)?|oct(?:ober)?|nov(?:ember)?|dec(?:ember)?)"
    private const val DAY_NAME = "(?:mon(?:day)?|tue(?:s(?:day)?)?|wed(?:nesday)?|thu(?:r(?:s(?:day)?)?)?|fri(?:day)?|sat(?:urday)?|sun(?:day)?)"
    private const val ORD = "(?:st|nd|rd|th)?"
    private const val DASH = "(?:-|\u2013|\u2014|to)"
    private val I = RegexOption.IGNORE_CASE

    // "Fri, Nov 14", "November 14th, 2026", "Nov 14 - 16", "Nov 28 - Dec 2". The range end must not be the hour of a time ("Nov 14 - 7 PM").
    private val MONTH_FIRST = Regex(
        """(?:\b$DAY_NAME\.?,?\s+)?\b$MONTH\.?\s+(\d{1,2})$ORD(?!\d)(?:\s*$DASH\s*(?:$MONTH\.?\s+)?(\d{1,2})$ORD(?!\d|\s*(?:[ap]\.?m\b|:\d)))?(?:,?\s+(\d{4})(?!\d))?""", I,
    )

    // "14 Nov 2026", "Sunday 11th of October", "14-16 Nov".
    private val DAY_FIRST = Regex(
        """(?:\b$DAY_NAME\.?,?\s+)?(?<![\d:./-])(\d{1,2})$ORD(?:\s*$DASH\s*(\d{1,2})$ORD)?\s+(?:of\s+)?$MONTH\b\.?(?:,?\s+(\d{4})(?!\d))?""", I,
    )

    private val NUMERIC = Regex("""(?<![\d/.-])(\d{1,2})([/.-])(\d{1,2})\2(\d{4}|\d{2})(?![\d/])""")
    private val ISO = Regex("""(?<![\d/.-])(\d{4})-(\d{2})-(\d{2})(?!\d)""")

    private val RELATIVE = Regex("""\b(day after tomorrow|tomorrow|today|tonight)\b""", I)
    private val WEEKDAY = Regex("""\b(?:(this|next|on)\s+)?(monday|tuesday|wednesday|thursday|friday|saturday|sunday)\b|\b(this|next|on)\s+(mon|tue|tues|wed|thu|thur|thurs|fri|sat|sun)\b""", I)

    // "7:30 PM", "7 pm", "19:30", and ranges "7-9 PM", "18:30 - 20:00", "7 PM to 9 PM".
    private val TIME = Regex(
        """(?<![\d:./-])(\d{1,2})(?:([:.])(\d{2}))?\s*([ap]\.?m\b\.?)?(?:\s*(?:-|\u2013|\u2014|to|until|till)\s*(\d{1,2})(?:([:.])(\d{2}))?\s*([ap]\.?m\b\.?)?)?(?!\d)""", I,
    )
    private val NOON = Regex("""\b(noon|midnight)\b""", I)

    private val LOCATION = Regex("""^\s*(?:(?:where|location|venue|address)\b\s*[:\-]?|@)\s*(.{3,})$""", I)
    private val GENERIC = Regex("""^(?:$DAY_NAME|at|on|from|starts?|starting|until|till|to|the|date|time|when|and|-)$""", I)
    private val EDGE = Regex("""^[\s\-\u2013\u2014,:;\u00b7\u2022|@]+|[\s\-\u2013\u2014,:;\u00b7\u2022|@]+$""")

    private class Day(val from: LocalDate, val to: LocalDate, val line: Int)
    private class Clock(val from: LocalTime, val to: LocalTime?, val line: Int)

    fun parse(
        text: String,
        now: Long = System.currentTimeMillis(),
        zone: ZoneId = ZoneId.systemDefault(),
        dayFirst: Boolean = Locale.getDefault().country != "US",
    ): List<CalendarEvent> {
        val lines = text.lines()
        val today = java.time.Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val spans = List(lines.size) { mutableListOf<IntRange>() }
        val days = ArrayList<Day>()
        val clocks = ArrayList<Clock>()

        lines.forEachIndexed { i, line ->
            for (m in MONTH_FIRST.findAll(line)) {
                val g = m.groupValues
                val first = month(g[1]) ?: continue
                val d1 = g[2].toInt()
                val last = if (g[4].isNotEmpty()) g[4].toInt() else null
                val secondMonth = if (g[3].isNotEmpty()) month(g[3]) else null
                add(days, spans[i], m.range, i, today, first, d1, secondMonth ?: first, last, g[5])
            }
            for (m in DAY_FIRST.findAll(line)) {
                if (spans[i].any { it.overlaps(m.range) }) continue
                val g = m.groupValues
                val mo = month(g[3]) ?: continue
                add(days, spans[i], m.range, i, today, mo, g[1].toInt(), mo, if (g[2].isNotEmpty()) g[2].toInt() else null, g[4])
            }
            for (m in NUMERIC.findAll(line)) {
                if (spans[i].any { it.overlaps(m.range) }) continue
                val a = m.groupValues[1].toInt()
                val b = m.groupValues[3].toInt()
                val yearText = m.groupValues[4]
                if (m.groupValues[2] == "." && yearText.length == 2) continue // "10.30.45" is not a date
                val dm = when {
                    a > 12 -> a to b
                    b > 12 -> b to a
                    dayFirst -> a to b
                    else -> b to a
                }
                val year = if (yearText.length == 2) 2000 + yearText.toInt() else yearText.toInt()
                val d = date(year, dm.second, dm.first) ?: continue
                days += Day(d, d, i); spans[i] += m.range
            }
            for (m in ISO.findAll(line)) {
                if (spans[i].any { it.overlaps(m.range) }) continue
                val d = date(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt()) ?: continue
                days += Day(d, d, i); spans[i] += m.range
            }
        }

        // Words like "tomorrow" only count when the text has no real date.
        if (days.isEmpty()) lines.forEachIndexed { i, line ->
            for (m in RELATIVE.findAll(line)) {
                val d = when (m.value.lowercase()) {
                    "tomorrow" -> today.plusDays(1)
                    "day after tomorrow" -> today.plusDays(2)
                    else -> today
                }
                days += Day(d, d, i); spans[i] += m.range
            }
            for (m in WEEKDAY.findAll(line)) {
                if (spans[i].any { it.overlaps(m.range) }) continue
                val word = (m.groupValues[2].ifEmpty { m.groupValues[4] }).lowercase()
                val next = m.groupValues[1].equals("next", true) || m.groupValues[3].equals("next", true)
                val target = DayOfWeek.entries.first { it.name.lowercase().startsWith(word.take(3)) }
                var d = today
                while (d.dayOfWeek != target || (next && d == today)) d = d.plusDays(1)
                days += Day(d, d, i); spans[i] += m.range
            }
        }
        if (days.isEmpty()) return emptyList()

        lines.forEachIndexed { i, line ->
            for (m in TIME.findAll(line)) {
                if (spans[i].any { it.overlaps(m.range) }) continue
                clock(m.groupValues)?.let { clocks += Clock(it.first, it.second, i); spans[i] += m.range }
            }
            for (m in NOON.findAll(line)) {
                if (spans[i].any { it.overlaps(m.range) }) continue
                clocks += Clock(if (m.value.equals("noon", true)) LocalTime.NOON else LocalTime.MIDNIGHT, null, i)
                spans[i] += m.range
            }
        }

        // Each time goes to the nearest date: up to 8 lines above it (a schedule under a date heading) or 2 below. A tie goes to the one above.
        val timesOf = days.associateWith { mutableListOf<Clock>() }
        for (c in clocks) {
            val day = days.filter { if (it.line <= c.line) c.line - it.line <= 8 else it.line - c.line <= 2 }
                .minWithOrNull(compareBy<Day> { Math.abs(it.line - c.line) }.thenBy { if (it.line <= c.line) 0 else 1 }) ?: continue
            timesOf.getValue(day) += c
        }

        val notes = text.trim().take(1000)
        val out = LinkedHashMap<String, CalendarEvent>()
        for (day in days) {
            val times = timesOf.getValue(day)
            if (times.isEmpty()) {
                val start = day.from.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
                val end = day.to.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
                out.putIfAbsent("$start-$end", CalendarEvent(title(lines, spans, day.line, day.line), start, end, true, where(lines, day.line, day.line), notes))
                continue
            }
            for (c in times.sortedBy { it.line }) {
                val start = day.from.atTime(c.from).atZone(zone).toInstant().toEpochMilli()
                var end = start + 60 * 60 * 1000L
                c.to?.let { to ->
                    var e = day.from.atTime(to).atZone(zone)
                    if (!e.toInstant().isAfter(java.time.Instant.ofEpochMilli(start))) e = e.plusDays(1) // "10 PM - 1 AM"
                    end = e.toInstant().toEpochMilli()
                }
                out.putIfAbsent("$start-$end", CalendarEvent(title(lines, spans, c.line, day.line), start, end, false, where(lines, day.line, c.line), notes))
            }
        }
        return out.values.sortedBy { it.start }.take(10)
    }

    private fun IntRange.overlaps(o: IntRange) = first <= o.last && o.first <= last

    private fun month(name: String): Int? {
        val i = listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec").indexOf(name.take(3).lowercase())
        return if (i < 0) null else i + 1
    }

    private fun date(year: Int, month: Int, day: Int): LocalDate? = try {
        LocalDate.of(year, month, day)
    } catch (_: Exception) {
        null
    }

    /** Adds a "Nov 14" style date; with no year it means the coming one (or a recent one, up to 60 days back). */
    private fun add(
        days: MutableList<Day>, spans: MutableList<IntRange>, range: IntRange, line: Int, today: LocalDate,
        month: Int, day: Int, endMonth: Int, endDay: Int?, year: String,
    ) {
        var from = date(year.toIntOrNull() ?: today.year, month, day) ?: return
        if (year.isEmpty() && from.isBefore(today.minusDays(60))) from = from.plusYears(1)
        var to = from
        if (endDay != null) {
            val e = date(from.year, endMonth, endDay)
            to = when {
                e == null -> from
                !e.isBefore(from) -> e
                endMonth != month -> e.plusYears(1) // "Dec 30 - Jan 2"
                else -> from
            }
        }
        days += Day(from, to, line)
        spans += range
    }

    private fun hour(h: Int, ap: String): Int? {
        if (ap.isEmpty()) return if (h <= 23) h else null
        if (h !in 1..12) return null
        return h % 12 + if (ap[0].equals('p', true)) 12 else 0
    }

    /** Groups: 1 hour, 2 separator, 3 minutes, 4 am/pm, then the same four for the end of a range. Null if it is not a clock time. */
    private fun clock(g: List<String>): Pair<LocalTime, LocalTime?>? {
        val m1 = g[3].ifEmpty { "0" }.toInt()
        val m2 = g[7].ifEmpty { "0" }.toInt()
        if (m1 > 59 || m2 > 59) return null
        val single = g[4].isNotEmpty() || g[2] == ":"
        val ranged = g[5].isNotEmpty() && (g[4].isNotEmpty() || g[8].isNotEmpty() || (g[2] == ":" && g[6] == ":"))
        if (ranged) {
            val ap2 = g[8]
            val ap1 = g[4].ifEmpty { ap2 }
            val endH = hour(g[5].toInt(), g[8].ifEmpty { ap1 }) ?: return null
            var startH = hour(g[1].toInt(), ap1) ?: return null
            if (g[4].isEmpty() && startH > endH && startH >= 12) startH -= 12 // "11-1 PM" means 11 AM
            return LocalTime.of(startH, m1) to LocalTime.of(endH, m2)
        }
        if (!single) return null
        // "7.30 pm" is a time but a bare "7.30" is not.
        if (g[2] == "." && g[4].isEmpty()) return null
        return LocalTime.of(hour(g[1].toInt(), g[4]) ?: return null, m1) to null
    }

    /** The line with its dates and times cut out, or the nearest line that still says something. */
    private fun title(lines: List<String>, spans: List<List<IntRange>>, timeLine: Int, dayLine: Int): String {
        val order = listOf(timeLine, dayLine, dayLine - 1, timeLine - 1, dayLine + 1, timeLine + 1, 0).distinct()
        for (i in order) {
            if (i !in lines.indices) continue
            var s = lines[i]
            for (r in spans[i].sortedByDescending { it.first }) s = s.removeRange(r.first, minOf(r.last + 1, s.length))
            repeat(2) {
                s = s.replace(EDGE, "")
                s = s.replace(Regex("""^(?:at|on|from|starts?|starting|until|till|to)\b""", I), "")
                s = s.replace(Regex("""\b(?:at|on|from|starts?|starting|until|till|to)$""", I), "")
            }
            s = s.replace(EDGE, "").replace(Regex("""\s+"""), " ")
            if (s.count(Char::isLetter) >= 3 && !GENERIC.matches(s) && !LOCATION.matches(s)) return if (s.length > 80) s.take(79) + "\u2026" else s
        }
        return "Event"
    }

    /** A line like "Where: Room 12" or "@ Central Park" close to the event. */
    private fun where(lines: List<String>, dayLine: Int, timeLine: Int): String? {
        for (i in (minOf(dayLine, timeLine) - 1)..(maxOf(dayLine, timeLine) + 4)) {
            lines.getOrNull(i)?.let { l -> LOCATION.matchEntire(l)?.let { return it.groupValues[1].trim() } }
        }
        return null
    }
}
