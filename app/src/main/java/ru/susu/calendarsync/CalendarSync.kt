package ru.susu.calendarsync

import android.content.ContentProviderOperation
import android.content.ContentValues
import android.content.Context
import android.graphics.Color
import android.net.Uri
import android.provider.CalendarContract
import android.provider.CalendarContract.Calendars
import android.provider.CalendarContract.Events
import android.provider.CalendarContract.Reminders
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters

/** Даты [from, to] включительно; null — без ограничения. */
data class DateWindow(val from: LocalDate?, val to: LocalDate?) {
    fun contains(date: String): Boolean {
        val d = runCatching { LocalDate.parse(date) }.getOrNull() ?: return false
        return (from == null || !d.isBefore(from)) && (to == null || !d.isAfter(to))
    }

    companion object {
        fun of(prefs: Prefs): DateWindow {
            val today = LocalDate.now(CalendarSync.SUSU_ZONE)
            val monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            return when (prefs.calendarRange) {
                CalendarRange.WEEK -> DateWindow(monday, monday.plusDays(13))
                CalendarRange.MONTH -> DateWindow(today, today.plusMonths(1))
                CalendarRange.SEMESTER -> DateWindow(null, null)
                CalendarRange.CUSTOM -> DateWindow(
                    prefs.customFrom.takeIf { it != 0L }?.let(LocalDate::ofEpochDay),
                    prefs.customTo.takeIf { it != 0L }?.let(LocalDate::ofEpochDay),
                )
            }
        }
    }
}

/**
 * Работа с отдельными локальными календарями приложения.
 * Календари создаются от имени локального аккаунта (ACCOUNT_TYPE_LOCAL): видны в любом
 * приложении-календаре, но не синхронизируются с Google.
 */
class CalendarSync(private val context: Context) {

    companion object {
        const val ACCOUNT_NAME = "ЮУрГУ-Онлайн"
        val SUSU_ZONE: ZoneId = ZoneId.of("Asia/Yekaterinburg")
        private val TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
        private val COLORS = intArrayOf(
            Color.rgb(0, 84, 166), Color.rgb(0, 137, 123), Color.rgb(142, 36, 170),
            Color.rgb(230, 81, 0), Color.rgb(84, 110, 122), Color.rgb(198, 40, 40),
        )
    }

    private val resolver get() = context.contentResolver

    private fun Uri.asSyncAdapter(): Uri = buildUpon()
        .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
        .appendQueryParameter(Calendars.ACCOUNT_NAME, ACCOUNT_NAME)
        .appendQueryParameter(Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL)
        .build()

    fun findCalendarId(key: String): Long? {
        resolver.query(
            Calendars.CONTENT_URI,
            arrayOf(Calendars._ID),
            "${Calendars.ACCOUNT_NAME}=? AND ${Calendars.ACCOUNT_TYPE}=? AND ${Calendars.NAME}=?",
            arrayOf(ACCOUNT_NAME, CalendarContract.ACCOUNT_TYPE_LOCAL, key),
            null,
        )?.use { if (it.moveToFirst()) return it.getLong(0) }
        return null
    }

    private fun getOrCreateCalendar(key: String, title: String): Long {
        val values = ContentValues().apply {
            put(Calendars.CALENDAR_DISPLAY_NAME, title)
        }
        findCalendarId(key)?.let { id ->
            // Обновляем название (например, после переименования приложения).
            resolver.update(
                Calendars.CONTENT_URI.asSyncAdapter().buildUpon().appendPath(id.toString()).build(),
                values, null, null,
            )
            return id
        }
        values.apply {
            put(Calendars.ACCOUNT_NAME, ACCOUNT_NAME)
            put(Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL)
            put(Calendars.NAME, key)
            put(Calendars.CALENDAR_COLOR, COLORS[Math.floorMod(key.hashCode(), COLORS.size)])
            put(Calendars.CALENDAR_ACCESS_LEVEL, Calendars.CAL_ACCESS_OWNER)
            put(Calendars.OWNER_ACCOUNT, ACCOUNT_NAME)
            put(Calendars.CALENDAR_TIME_ZONE, SUSU_ZONE.id)
            put(Calendars.VISIBLE, 1)
            put(Calendars.SYNC_EVENTS, 1)
        }
        val uri = resolver.insert(Calendars.CONTENT_URI.asSyncAdapter(), values)
            ?: error("Не получилось создать календарь")
        return uri.lastPathSegment!!.toLong()
    }

    fun deleteCalendar(key: String) {
        val id = findCalendarId(key) ?: return
        resolver.delete(
            Calendars.CONTENT_URI.asSyncAdapter().buildUpon().appendPath(id.toString()).build(),
            null, null,
        )
    }

    /** Удаляет все календари приложения. */
    fun deleteAll() {
        resolver.delete(
            Calendars.CONTENT_URI.asSyncAdapter(),
            "${Calendars.ACCOUNT_NAME}=? AND ${Calendars.ACCOUNT_TYPE}=?",
            arrayOf(ACCOUNT_NAME, CalendarContract.ACCOUNT_TYPE_LOCAL),
        )
    }

    private data class Existing(val id: Long, val hash: String)

    /**
     * Приводит календарь к расписанию в пределах [window]: добавляет новые занятия, обновляет
     * изменённые, удаляет лишние. Прошедшие занятия (до сегодняшнего дня) не удаляются.
     * Ключ занятия хранится в SYNC_DATA1, хэш содержимого — в SYNC_DATA2.
     * Возвращает число занятий в календаре после синхронизации.
     */
    fun sync(key: String, title: String, lessons: List<Lesson>, reminderMinutes: Int, window: DateWindow): Int {
        val calId = getOrCreateCalendar(key, title)
        val eventsUri = Events.CONTENT_URI.asSyncAdapter()
        val remindersUri = Reminders.CONTENT_URI.asSyncAdapter()

        val existing = HashMap<String, Existing>()
        val junk = ArrayList<Long>()
        resolver.query(
            eventsUri,
            arrayOf(Events._ID, Events.SYNC_DATA1, Events.SYNC_DATA2),
            "${Events.CALENDAR_ID}=? AND ${Events.DELETED}=0",
            arrayOf(calId.toString()),
            null,
        )?.use { c ->
            while (c.moveToNext()) {
                val k = c.getString(1)
                if (k == null || k.split("|").size < 4 || existing.containsKey(k)) junk += c.getLong(0)
                else existing[k] = Existing(c.getLong(0), c.getString(2).orEmpty())
            }
        }

        val todayStart = LocalDate.now(SUSU_ZONE).atStartOfDay(SUSU_ZONE).toInstant().toEpochMilli()
        val units = ArrayList<(Int) -> List<ContentProviderOperation>>()
        val seen = HashSet<String>()

        for (lesson in lessons) {
            if (!window.contains(lesson.date)) continue
            val k = lesson.key
            if (!seen.add(k)) continue
            val start = millis(lesson.date, lesson.beginTime) ?: continue
            val end = millis(lesson.date, lesson.endTime) ?: continue
            val hash = listOf(lesson.title, lesson.location, lesson.details, reminderMinutes).joinToString("\u0001")
                .hashCode().toString()

            val values = ContentValues().apply {
                put(Events.CALENDAR_ID, calId)
                put(Events.TITLE, lesson.title)
                put(Events.EVENT_LOCATION, lesson.location)
                put(Events.DESCRIPTION, lesson.details)
                put(Events.DTSTART, start)
                put(Events.DTEND, end)
                put(Events.EVENT_TIMEZONE, SUSU_ZONE.id)
                put(Events.HAS_ALARM, if (reminderMinutes > 0) 1 else 0)
                put(Events.AVAILABILITY, Events.AVAILABILITY_BUSY)
                put(Events.SYNC_DATA1, k)
                put(Events.SYNC_DATA2, hash)
            }

            val old = existing[k]
            if (old == null) {
                units.add { base ->
                    buildList {
                        add(ContentProviderOperation.newInsert(eventsUri).withValues(values).build())
                        if (reminderMinutes > 0) {
                            add(
                                ContentProviderOperation.newInsert(remindersUri)
                                    .withValueBackReference(Reminders.EVENT_ID, base)
                                    .withValue(Reminders.MINUTES, reminderMinutes)
                                    .withValue(Reminders.METHOD, Reminders.METHOD_ALERT)
                                    .build()
                            )
                        }
                    }
                }
            } else if (old.hash != hash) {
                units.add { _ ->
                    buildList {
                        add(ContentProviderOperation.newUpdate(eventUri(old.id)).withValues(values).build())
                        add(
                            ContentProviderOperation.newDelete(remindersUri)
                                .withSelection("${Reminders.EVENT_ID}=?", arrayOf(old.id.toString())).build()
                        )
                        if (reminderMinutes > 0) {
                            add(
                                ContentProviderOperation.newInsert(remindersUri)
                                    .withValue(Reminders.EVENT_ID, old.id)
                                    .withValue(Reminders.MINUTES, reminderMinutes)
                                    .withValue(Reminders.METHOD, Reminders.METHOD_ALERT)
                                    .build()
                            )
                        }
                    }
                }
            }
        }

        for ((k, old) in existing) {
            if (k in seen) continue
            val parts = k.split("|")
            val start = millis(parts[0], parts[1]) ?: Long.MAX_VALUE
            if (start >= todayStart) units.add { _ -> listOf(ContentProviderOperation.newDelete(eventUri(old.id)).build()) }
        }
        for (id in junk) units.add { _ -> listOf(ContentProviderOperation.newDelete(eventUri(id)).build()) }

        // Применяем пачками, чтобы не упереться в лимит размера транзакции Binder.
        // Каждый unit получает индекс своей первой операции в пачке — для back-reference.
        val batch = ArrayList<ContentProviderOperation>()
        for (unit in units) {
            batch += unit(batch.size)
            if (batch.size >= 200) {
                resolver.applyBatch(CalendarContract.AUTHORITY, ArrayList(batch))
                batch.clear()
            }
        }
        if (batch.isNotEmpty()) resolver.applyBatch(CalendarContract.AUTHORITY, batch)
        return seen.size
    }

    private fun eventUri(id: Long): Uri =
        Events.CONTENT_URI.buildUpon().appendPath(id.toString()).build().asSyncAdapter()

    private fun millis(date: String, time: String): Long? = try {
        LocalDateTime.parse("$date $time", TIME).atZone(SUSU_ZONE).toInstant().toEpochMilli()
    } catch (e: Exception) {
        null
    }
}
