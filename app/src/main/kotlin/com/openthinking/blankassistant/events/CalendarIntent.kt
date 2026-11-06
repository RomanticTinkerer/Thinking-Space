package com.openthinking.blankassistant.events

import android.content.Intent
import android.provider.CalendarContract

/** Opens the user's calendar app with this event filled in. They check it and press save, so no permission is needed. */
fun CalendarEvent.toIntent(): Intent = Intent(Intent.ACTION_INSERT, CalendarContract.Events.CONTENT_URI)
    .putExtra(CalendarContract.Events.TITLE, title)
    .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, start)
    .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, end)
    .putExtra(CalendarContract.EXTRA_EVENT_ALL_DAY, allDay)
    .putExtra(CalendarContract.Events.EVENT_LOCATION, location)
    .putExtra(CalendarContract.Events.DESCRIPTION, notes)
