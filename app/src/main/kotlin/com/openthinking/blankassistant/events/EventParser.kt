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
    private val BARE_AT = Regex("""(?:\bat|@)\s*(\d{1,2})(?::(\d{2}))?(?:\s*o'?\s?clock)?(?!\d|\s*(?:[ap]\.?m\b|:))|\b(\d{1,2})\s*o'?\s?clock\b""", I)
    private val DIGITISH_TIME = Regex("""(?<![\w:.])[0-9OolI]{1,2}[:.][0-9OolI]{2}(?![\w:.])""")
    private val DIGITISH_DAY = Regex("""\b($MONTH\.?\s+)([0-9][OolI]|[OolI][0-9])(?!\w)""", I)
    private val FILLER = setOf("in", "of", "our", "the", "a", "an", "your", "my", "advance", "to", "for", "and", "that", "is", "are", "by", "at", "on", "their")
    private val AM_PM = Regex("""\b[ap]\.?m\b""", I)
    private val NIGHT = Regex("""\b(tonight|night|evening|dinner|party|drinks)\b""", I)
    private val NOON = Regex("""\b(noon|midnight)\b""", I)

    // Words that say "this is about something happening". Needed when the date is only "tomorrow", "Friday" or "in 2 hours".
    private val CUE = Regex(
        """\b(meet(?:ing|s)?|party|lunch|dinner|breakfast|brunch|call|class|exam|test|quiz|deadline|due|submit|flight|train|bus|appointment|appt|interview|concert|show|movie|match|game|event|reminder|remind|come|coming|see you|let'?s|lets|tonight|starts?|starting|begins?|leave|leaving|pick|catch|join|webinar|session|lecture|workshop|practice|rehearsal|trip|visit|birthday|wedding|ceremony|conference|hackathon|contest|stream|live|launch|reservation|booking|tickets?|doors|rsvp|expires?|closes?|opens?|ends?|arriv\w*|reach|there)\b""", I,
    )
    private val DURATION = Regex(
        """\b(?:(?:in|within|after)|(?:starting|starts?|begins?)\s+is)\s+~?\s*(\d{1,3}|an?|one|two|three|four|five|six|half an?)\s*(hours?|hrs?|h|minutes?|mins?|days?|weeks?)\b""", I,
    )
    private val ORDINAL_DAY = Regex("""\b(?:on\s+)?(?:the\s+)?(\d{1,2})(?:st|nd|rd|th)\b""", I)
    // A lone "Today" / "Monday" line is a chat's day divider, not a plan.
    private val DIVIDER = Regex("""^\W*(?:today|yesterday|tomorrow|$DAY_NAME)\W*$""", I)
    // Lines about when something was posted, not when it happens.
    private val META = Regex("""\b(?:updated|posted|published|edited|uploaded|modified|created|joined|last seen|member since)\b|\u00a9""", I)
    private val CHAT_UI = Regex("""\breplied to\b|\bunread\b|\btyping\b|\bonline\b|^\s*message\W*$|^\s*reply\W*$""", setOf(I, RegexOption.MULTILINE))
    private val STATUS_BAR = Regex("""^\s*\d{1,2}:\d{2}\b""")
    private val BEFORE_REAL_TIME = Regex("""(?:\b(?:at|from|by|until|till|to|before|after|around)|[@~:\-\u2013\u2014])\s*$""", I)
    private val NUMBER_WORD = mapOf("a" to 1, "an" to 1, "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6, "half a" to 0, "half an" to 0)

    private val LOCATION = Regex("""^\s*(?:(?:where|location|venue|address)\b\s*[:\-]?|@)\s*(.{3,})$""", I)
    private val GENERIC = Regex("""^(?:$DAY_NAME|at|on|from|starts?|starting|until|till|to|the|date|time|when|and|-)$""", I)
    private val EDGE = Regex("""^[\s\-\u2013\u2014,:;.!?\u00b7\u2022|@]+|[\s\-\u2013\u2014,:;.!?\u00b7\u2022|@]+$""")

    private class Day(val from: LocalDate, val to: LocalDate, val line: Int)
    // [stamp]: a time that ends a line after some words ("Sam 6:53 PM"): when a message was sent, not when something happens.
    private class Clock(val from: LocalTime, val to: LocalTime?, val line: Int, val stamp: Boolean = false)

    fun parse(
        text: String,
        now: Long = System.currentTimeMillis(),
        zone: ZoneId = ZoneId.systemDefault(),
        dayFirst: Boolean = Locale.getDefault().country != "US",
    ): List<CalendarEvent> {
        // The phone's clock and the dates of "last updated" lines are not events: blank them out (line numbers stay).
        val fixed = text.lines().map(::fixDigits)
        val firstTwo = fixed.withIndex().filter { it.value.isNotBlank() }.take(2).map { it.index }
        val lines = fixed.mapIndexed { i, l ->
            if ((i in firstTwo && isStatusBar(l)) || META.containsMatchIn(l)) "" else l
        }
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

        // "Tomorrow", "Friday" and "the 14th" only count when the text has no real date, and only next to a word that
        // says something is happening (or a time), so chat dividers and idle talk stay out.
        fun cueNear(i: Int) = (i - 1..i + 1).any { CUE.containsMatchIn(lines.getOrNull(it).orEmpty()) } ||
            TIME.findAll(lines[i]).any { clock(it.groupValues) != null }
        if (days.isEmpty()) lines.forEachIndexed { i, line ->
            if (DIVIDER.matches(line) || !cueNear(i)) return@forEachIndexed
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
        if (days.isEmpty()) lines.forEachIndexed { i, line ->
            if (!cueNear(i)) return@forEachIndexed
            for (m in ORDINAL_DAY.findAll(line)) {
                if (spans[i].any { it.overlaps(m.range) }) continue
                val n = m.groupValues[1].toInt()
                var month = today.withDayOfMonth(1)
                if (n < today.dayOfMonth) month = month.plusMonths(1)
                val d = date(month.year, month.monthValue, n) ?: continue
                days += Day(d, d, i); spans[i] += m.range
            }
        }

        lines.forEachIndexed { i, line ->
            for (m in TIME.findAll(line)) {
                if (spans[i].any { it.overlaps(m.range) }) continue
                clock(m.groupValues)?.let {
                    val before = line.substring(0, m.range.first).trimEnd()
                    val stamp = before.any(Char::isLetter) && !BEFORE_REAL_TIME.containsMatchIn(before) &&
                        line.substring(m.range.last + 1).none(Char::isLetterOrDigit)
                    clocks += Clock(it.first, it.second, i, stamp); spans[i] += m.range
                }
            }
            // "at 5" and "7 o'clock" have no am/pm: 1-7 is taken as the afternoon or evening, 8-11 as morning unless the text says night.
            for (m in BARE_AT.findAll(line)) {
                if (spans[i].any { it.overlaps(m.range) }) continue
                val h = (m.groupValues[1].ifEmpty { m.groupValues[3] }).toInt()
                val minutes = m.groupValues[2].ifEmpty { "0" }.toInt()
                if (h !in 1..12 || minutes > 59) continue
                val evening = h in 1..7 || (h in 8..11 && NIGHT.containsMatchIn(text))
                clocks += Clock(LocalTime.of(if (h == 12) 12 else if (evening) h + 12 else h, minutes), null, i)
                spans[i] += m.range
            }
            for (m in NOON.findAll(line)) {
                if (spans[i].any { it.overlaps(m.range) }) continue
                clocks += Clock(if (m.value.equals("noon", true)) LocalTime.NOON else LocalTime.MIDNIGHT, null, i)
                spans[i] += m.range
            }
        }

        // "Starting in 30 minutes": counted from the time the message was sent (a stamp just above it), else from now.
        // A chat repeats itself as the time gets closer, so only the last such message counts.
        val soonEvents = ArrayList<CalendarEvent>()
        lines.forEachIndexed { i, line ->
            val m = DURATION.find(line) ?: return@forEachIndexed
            if (!cueNear(i)) return@forEachIndexed
            val word = m.groupValues[1].lowercase()
            val n = word.toIntOrNull() ?: NUMBER_WORD[word] ?: return@forEachIndexed
            val unit = m.groupValues[2].lowercase()
            val said = line.removeRange(m.range).replace(Regex("""@\w+"""), "").replace(EDGE, "")
            val title = if (said.count(Char::isLetter) >= 3) said.replaceFirstChar { it.uppercase() } else "Starting soon"
            if (unit.startsWith("d") || unit.startsWith("w")) {
                val day = today.plusDays(n.toLong() * if (unit.startsWith("w")) 7 else 1)
                val start = day.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
                soonEvents += CalendarEvent(title, start, start + 86_400_000L, true, null, text.trim().take(1000))
            } else {
                val anchor = clocks.filter { it.line in (i - 3)..i }.lastOrNull()
                val from = anchor?.let { today.atTime(it.from).atZone(zone).toInstant().toEpochMilli() } ?: now
                val millis = if (word.startsWith("half")) 1_800_000L else n * if (unit.startsWith("h")) 3_600_000L else 60_000L
                val start = from + millis
                if (start >= now - 3_600_000L) soonEvents += CalendarEvent(title, start, start + 3_600_000L, false, null, text.trim().take(1000))
            }
        }
        val soon = listOfNotNull(soonEvents.lastOrNull { !it.allDay }, soonEvents.lastOrNull { it.allDay })
        if (days.isEmpty()) return soon

        // A chat's message times ("Sam 6:53 PM") are not event times. Any real time in the text, many of them, or chat words around give them away.
        val stamps = clocks.count { it.stamp }
        if (stamps >= 3 || clocks.any { !it.stamp } || (stamps >= 1 && CHAT_UI.containsMatchIn(text))) clocks.removeAll { it.stamp }

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
        return (out.values + soon).sortedBy { it.start }.take(10)
    }

    /** "9:41 5G 100", "7:33 Mon, 9 Nov": a clock and a few short words or numbers, nothing like "7:30 PM". */
    private fun isStatusBar(line: String) = STATUS_BAR.containsMatchIn(line) && !AM_PM.containsMatchIn(line) &&
        line.split(Regex("\\W+")).none { it.count(Char::isLetter) >= 4 }

    /** The reader mixes up 0/O and 1/l/I inside numbers: "1O:3O" and "Nov l4" become "10:30" and "Nov 14". */
    private fun fixDigits(line: String): String {
        fun fix(t: String) = t.replace('O', '0').replace('o', '0').replace('l', '1').replace('I', '1')
        return line
            .replace(DIGITISH_TIME) { if (it.value.any(Char::isDigit)) fix(it.value) else it.value }
            .replace(DIGITISH_DAY) { it.groupValues[1] + fix(it.groupValues[3]) }
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
            s = s.replace(Regex("""@\w+"""), "") // "@everyone"
            repeat(3) {
                s = s.replace(EDGE, "")
                s = s.replace(Regex("""^(?:at|on|from|starts?|starting|until|till|to)\b""", I), "")
                s = s.replace(Regex("""\b(?:at|on|from|starts?|starting|until|till|to|of|by|for|is|are|in|before|the|a|an|and)$""", I), "")
            }
            s = s.replace(EDGE, "").replace(Regex("""\s+"""), " ")
            // A sentence that happens to hold a date ("...our final deadline of Jan 6"): the words just before the date say it best.
            var words = s.split(' ')
            if (words.size > 6) {
                words = words.takeLast(4)
                while (words.size > 2 && words.first().lowercase() in FILLER) words = words.drop(1)
                s = words.joinToString(" ")
            }
            if (s.count(Char::isLetter) >= 3 && !GENERIC.matches(s) && !LOCATION.matches(s)) {
                s = s.replaceFirstChar { it.uppercase() }
                return if (s.length > 80) s.take(79) + "\u2026" else s
            }
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
