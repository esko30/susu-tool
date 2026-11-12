package ru.susu.calendarsync

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDateTime
import java.util.UUID

/** Запись планера, привязанная к конкретной паре (предмет + дата + время начала). */
data class PlannerItem(
    val id: String = UUID.randomUUID().toString(),
    val subject: String,
    val date: String,      // yyyy-MM-dd
    val begin: String,     // HH:mm
    val location: String,
    val label: String,     // «Контрольная», «Коллоквиум»… или пусто
    val note: String,
    /** Напоминания: можно несколько, пусто — без напоминаний. */
    val reminders: List<Remind> = emptyList(),
    val done: Boolean = false,
    /** Своя запись, не привязанная к паре: subject — название, begin может быть пустым (весь день). */
    val custom: Boolean = false,
    /** Имена файлов фото в папке планера. */
    val photos: List<String> = emptyList(),
) {
    /** Совпадает с Lesson.key без времени окончания — пара узнаётся, даже если её продлили. */
    val lessonKey get() = "$date|$begin|$subject"

    /** Моменты срабатывания напоминаний (мс). У записи без времени отсчёт ведём от 9:00 этого дня. */
    fun remindTimes(): List<Long> {
        val start = runCatching { LocalDateTime.parse("${date}T${begin.ifBlank { "09:00" }}") }.getOrNull() ?: return emptyList()
        return reminders.mapNotNull { r ->
            runCatching { r.at(start).atZone(CalendarSync.SUSU_ZONE).toInstant().toEpochMilli() }.getOrNull()
        }
    }

    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("s", subject).put("d", date).put("b", begin).put("l", location)
        .put("label", label).put("note", note).put("r", JSONArray(reminders.map { it.spec() })).put("done", done).put("c", custom).put("ph", JSONArray(photos))

    companion object {
        fun fromJson(o: JSONObject) = PlannerItem(
            o.getString("id"), o.optString("s"), o.optString("d"), o.optString("b"), o.optString("l"),
            o.optString("label"), o.optString("note"),
            o.optJSONArray("r")?.let { a -> (0 until a.length()).mapNotNull { Remind.parse(a.optString(it)) } }
                ?: legacy(o.optString("remind")),
            o.optBoolean("done"),
            o.optBoolean("c"),
            o.optJSONArray("ph")?.let { a -> (0 until a.length()).map { a.optString(it) }.filter { it.isNotBlank() } }.orEmpty(),
        )

        /** Записи прежних версий хранили одно напоминание из списка. */
        private fun legacy(name: String) = when (name) {
            "AT_START" -> listOf(Remind(0))
            "MIN_15" -> listOf(Remind(15))
            "HOUR" -> listOf(Remind(60))
            "EVENING" -> listOf(Remind.EVENING)
            else -> emptyList()
        }
    }
}

/**
 * Напоминание: за [minutes] минут до начала либо (если задано [time]) за [days] дней в указанное время.
 * Хранится строкой: «m15», «d1@20:00».
 */
data class Remind(val minutes: Int = 0, val days: Int = 0, val time: String = "") {
    private val byDay get() = time.isNotBlank()

    fun at(start: LocalDateTime): LocalDateTime =
        if (byDay) start.toLocalDate().minusDays(days.toLong()).atTime(java.time.LocalTime.parse(time))
        else start.minusMinutes(minutes.toLong())

    fun spec() = if (byDay) "d$days@$time" else "m$minutes"

    /** [lesson] — запись к паре («в начале пары») или своя («в назначенное время»). */
    fun label(lesson: Boolean = true): String = if (byDay) when (days) {
        0 -> "В тот же день в $time"
        1 -> "Накануне в $time"
        else -> "За ${plural(days, "день", "дня", "дней")} в $time"
    } else when {
        minutes == 0 -> if (lesson) "В начале пары" else "В назначенное время"
        minutes % 1440 == 0 -> "За ${plural(minutes / 1440, "день", "дня", "дней")}"
        minutes % 60 == 0 -> "За ${plural(minutes / 60, "час", "часа", "часов")}".replace("За 1 час", "За час")
        minutes > 60 -> "За ${minutes / 60} ч ${minutes % 60} мин"
        else -> "За $minutes мин"
    }

    companion object {
        val EVENING = Remind(days = 1, time = "20:00")
        /** Быстрые варианты в панели записи. */
        val PRESETS = listOf(Remind(0), Remind(15), Remind(60), Remind(180), EVENING, Remind(days = 0, time = "08:00"), Remind(1440 * 3))

        fun parse(s: String): Remind? = when {
            s.startsWith("m") -> s.drop(1).toIntOrNull()?.takeIf { it >= 0 }?.let { Remind(it) }
            s.startsWith("d") && "@" in s -> {
                val d = s.drop(1).substringBefore("@").toIntOrNull()
                val t = s.substringAfter("@")
                if (d != null && d >= 0 && Regex("""\d{2}:\d{2}""").matches(t)) Remind(days = d, time = t) else null
            }
            else -> null
        }

        fun plural(n: Int, one: String, few: String, many: String): String {
            val m10 = n % 10; val m100 = n % 100
            val w = when { m10 == 1 && m100 != 11 -> one; m10 in 2..4 && m100 !in 12..14 -> few; else -> many }
            return "$n $w"
        }
    }
}

/** Хранилище планера (JSON в личной папке приложения) и напоминания через AlarmManager. */
object Planner {
    /** Готовые метки; цвет — для чипов на карточках пар. */
    val LABELS = linkedMapOf(
        "Контрольная" to 0xFFE53935.toInt(),
        "Коллоквиум" to 0xFF8E24AA.toInt(),
        "Тест" to 0xFFF4511E.toInt(),
        "Домашнее задание" to 0xFF1E88E5.toInt(),
        "Сдать работу" to 0xFF00897B.toInt(),
        "Важно" to 0xFFFDD835.toInt(),
    )
    const val CHANNEL = "planner_reminders"

    fun labelColor(label: String) = LABELS[label] ?: 0xFF546E7A.toInt()

    private fun file(context: Context) = File(context.filesDir, "planner.json")

    @Synchronized
    fun all(context: Context): List<PlannerItem> {
        val f = file(context)
        if (!f.exists()) return emptyList()
        return runCatching {
            val arr = JSONArray(f.readText())
            (0 until arr.length()).map { PlannerItem.fromJson(arr.getJSONObject(it)) }
        }.getOrDefault(emptyList())
    }

    @Synchronized
    private fun write(context: Context, items: List<PlannerItem>) {
        val arr = JSONArray()
        items.forEach { arr.put(it.toJson()) }
        file(context).writeText(arr.toString())
    }

    /** Записи по ключу пары — для меток на карточках. */
    fun byLesson(context: Context): Map<String, List<PlannerItem>> = all(context).groupBy { it.lessonKey }

    fun save(context: Context, item: PlannerItem) {
        write(context, all(context).filter { it.id != item.id } + item)
        schedule(context, item)
        runCatching { NowWidget.update(context) }
    }

    fun delete(context: Context, id: String) {
        all(context).firstOrNull { it.id == id }?.let { cancel(context, it); PlannerPhotos.delete(context, it.photos) }
        write(context, all(context).filter { it.id != id })
        runCatching { NowWidget.update(context) }
    }

    fun clear(context: Context) {
        all(context).forEach { cancel(context, it) }
        file(context).delete()
        PlannerPhotos.clear(context)
    }

    /** Своё напоминание у каждой записи под номером [n]. */
    private const val MAX_REMINDERS = 8

    private fun pending(context: Context, item: PlannerItem, n: Int) = PendingIntent.getBroadcast(
        context, "${item.id}#$n".hashCode(),
        Intent(context, PlannerReceiver::class.java).putExtra("id", item.id),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /**
     * Неточный будильник (setAndAllowWhileIdle): точные требуют отдельного разрешения в Android 14,
     * а опоздание на несколько минут для напоминания о паре некритично.
     */
    fun schedule(context: Context, item: PlannerItem) {
        val am = context.getSystemService(AlarmManager::class.java)
        cancel(context, item)
        if (item.done) return
        val now = System.currentTimeMillis()
        item.remindTimes().take(MAX_REMINDERS).forEachIndexed { n, at ->
            if (at > now) am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending(context, item, n))
        }
    }

    private fun cancel(context: Context, item: PlannerItem) {
        val am = context.getSystemService(AlarmManager::class.java)
        for (n in 0 until MAX_REMINDERS) am.cancel(pending(context, item, n))
        // Будильник версии с одним напоминанием.
        am.cancel(PendingIntent.getBroadcast(context, item.id.hashCode(),
            Intent(context, PlannerReceiver::class.java).putExtra("id", item.id),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
    }

    /** После перезагрузки телефона будильники сбрасываются — ставим их заново. */
    fun rescheduleAll(context: Context) = all(context).forEach { schedule(context, it) }

    /** Ближайшие пары этого же предмета, начиная с [from], — для выбора «эта / следующая / через одну». */
    fun occurrences(lessons: List<Lesson>, subject: String, from: Lesson): List<Lesson> =
        lessons.asSequence()
            .filter { it.subject == subject }
            .distinctBy { it.key }
            .sortedWith(compareBy({ it.date }, { it.beginTime }))
            .dropWhile { "${it.date} ${it.beginTime}" < "${from.date} ${from.beginTime}" }
            .take(8).toList()
}

/** Показывает напоминание планера. */
class PlannerReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val item = Planner.all(context).firstOrNull { it.id == intent.getStringExtra("id") } ?: return
        if (item.done || !Notifier.canNotify(context)) return
        Notifier.ensureChannels(context)
        val open = PendingIntent.getActivity(
            context, item.id.hashCode(),
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra(Notifier.EXTRA_TAB, Syncer.TAB_PLANNER),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val title = listOf(item.label, item.subject).filter { it.isNotBlank() }.joinToString(" · ")
        val text = buildString {
            append(ChangeDiff.day(item.date))
            if (item.begin.isNotBlank()) append(", ").append(item.begin)
            if (item.location.isNotBlank()) append(", ").append(item.location)
            if (item.note.isNotBlank()) append("\n").append(item.note)
        }
        val n = NotificationCompat.Builder(context, Planner.CHANNEL)
            .setSmallIcon(R.drawable.ic_notif_planner)
            .setContentTitle(title)
            .setContentText(text.lineSequence().first())
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(item.id.hashCode(), n)
        } catch (e: SecurityException) {
            // Уведомления запрещены — запись всё равно видна в планере.
        }
    }
}

/** Восстанавливает напоминания планера после перезагрузки телефона. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            Planner.rescheduleAll(context)
            runCatching { SyncWorker.ensure(context) }
            runCatching { Duty.resume(context) }
            runCatching { NowWidget.update(context) }
        }
    }
}
