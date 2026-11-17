package com.openthinking.blankassistant.events

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.CalendarContract
import android.provider.CalendarContract.Calendars
import android.provider.CalendarContract.Events
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import com.openthinking.blankassistant.R
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.time.ZoneId
import kotlin.concurrent.thread

/**
 * Adds events straight to every calendar on the phone that can take them (Google, local, anything synced).
 * Invisible: it asks for calendar access the first time, writes, says how many calendars got it, and closes.
 * If access is refused it opens the calendar app with the first event filled in instead.
 * ponytail: no check for an event that is already there, so adding twice makes two.
 */
class AddEventActivity : ComponentActivity() {
    private var events: List<CalendarEvent> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        events = intent.getStringExtra(EXTRA_EVENTS)?.let(::fromJson).orEmpty()
        if (events.isEmpty()) return finish()
        val ask = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
            if (granted.values.all { it }) insert() else fallback()
        }
        if (PERMISSIONS.all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }) insert() else ask.launch(PERMISSIONS)
    }

    private fun insert() {
        thread {
            val added = runCatching { addToAllCalendars() }.onFailure { Timber.e(it, "Could not add events") }.getOrDefault(0)
            runOnUiThread {
                if (added > 0) {
                    Toast.makeText(this, resources.getQuantityString(R.plurals.msg_event_added, added, added), Toast.LENGTH_SHORT).show()
                    finish()
                } else {
                    fallback()
                }
            }
        }
    }

    /** Returns how many calendars took at least one event. */
    private fun addToAllCalendars(): Int {
        val ids = mutableListOf<Long>()
        contentResolver.query(
            Calendars.CONTENT_URI,
            arrayOf(Calendars._ID),
            "${Calendars.VISIBLE} = 1 AND ${Calendars.CALENDAR_ACCESS_LEVEL} >= ?",
            arrayOf(Calendars.CAL_ACCESS_CONTRIBUTOR.toString()),
            null,
        )?.use { while (it.moveToNext()) ids += it.getLong(0) }
        var used = 0
        for (id in ids) {
            var took = false
            for (e in events) {
                val values = ContentValues().apply {
                    put(Events.CALENDAR_ID, id)
                    put(Events.TITLE, e.title)
                    put(Events.DTSTART, e.start)
                    put(Events.DTEND, e.end)
                    put(Events.ALL_DAY, if (e.allDay) 1 else 0)
                    put(Events.EVENT_TIMEZONE, if (e.allDay) "UTC" else ZoneId.systemDefault().id)
                    e.location?.let { put(Events.EVENT_LOCATION, it) }
                    put(Events.DESCRIPTION, e.notes)
                }
                if (contentResolver.insert(Events.CONTENT_URI, values) != null) took = true
            }
            if (took) used++
        }
        return used
    }

    private fun fallback() {
        try {
            startActivity(events.first().toIntent())
        } catch (e: Exception) {
            Timber.w(e, "No calendar app")
            Toast.makeText(this, R.string.msg_no_calendar, Toast.LENGTH_SHORT).show()
        }
        finish()
    }

    companion object {
        private const val EXTRA_EVENTS = "events"
        private val PERMISSIONS = arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)

        fun intent(context: Context, events: List<CalendarEvent>): Intent =
            Intent(context, AddEventActivity::class.java).putExtra(EXTRA_EVENTS, toJson(events))

        private fun toJson(events: List<CalendarEvent>) = JSONArray(events.map {
            JSONObject()
                .put("title", it.title).put("start", it.start).put("end", it.end).put("allDay", it.allDay)
                .put("location", it.location).put("notes", it.notes)
        }).toString()

        private fun fromJson(json: String): List<CalendarEvent> {
            val array = JSONArray(json)
            return List(array.length()) {
                val o = array.getJSONObject(it)
                CalendarEvent(
                    o.getString("title"), o.getLong("start"), o.getLong("end"), o.getBoolean("allDay"),
                    o.optString("location").takeIf { l -> l.isNotEmpty() && l != "null" }, o.optString("notes"),
                )
            }
        }
    }
}
