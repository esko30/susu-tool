package ru.susu.calendarsync

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * Свои неофициальные пары: добавляются вручную к расписанию (например, перенос, о котором сказали устно).
 * Показываются жёлтыми с лейблом «МОЯ», хранятся только на телефоне.
 */
object MyLessons {
    const val TYPE = "Своя пара"
    const val NOTE = "Неофициально"

    data class Item(
        val id: String = UUID.randomUUID().toString(),
        val uid: String,
        val date: String, val begin: String, val end: String,
        val subject: String, val location: String, val note: String,
    ) {
        fun toLesson() = Lesson(subject, TYPE, date, begin, end, location,
            listOf(NOTE, note).filter { it.isNotBlank() }.joinToString(" · "))
    }

    private fun file(c: Context) = File(c.filesDir, "my_lessons.json")

    @Synchronized
    fun all(c: Context): List<Item> = runCatching {
        val a = JSONArray(file(c).readText())
        (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            Item(o.getString("id"), o.optString("uid"), o.optString("d"), o.optString("b"), o.optString("e"),
                o.optString("s"), o.optString("l"), o.optString("n"))
        }
    }.getOrDefault(emptyList())

    @Synchronized
    private fun write(c: Context, items: List<Item>) {
        val a = JSONArray()
        items.forEach { a.put(JSONObject().put("id", it.id).put("uid", it.uid).put("d", it.date).put("b", it.begin)
            .put("e", it.end).put("s", it.subject).put("l", it.location).put("n", it.note)) }
        file(c).writeText(a.toString())
    }

    fun save(c: Context, item: Item) { write(c, all(c).filter { it.id != item.id } + item); runCatching { NowWidget.update(c) } }
    fun delete(c: Context, id: String) { write(c, all(c).filter { it.id != id }); runCatching { NowWidget.update(c) } }
    fun clear(c: Context) { file(c).delete() }

    /** Свои пары этого расписания в виде обычных занятий. */
    fun lessons(c: Context, uid: String) = all(c).filter { it.uid == uid }.map { it.toLesson() }

    fun isMine(l: Lesson) = l.eventType == TYPE

    /** Найти свою пару по занятию из ленты. */
    fun find(c: Context, uid: String, l: Lesson) = all(c).firstOrNull {
        it.uid == uid && it.date == l.date && it.begin == l.beginTime && it.subject == l.subject
    }
}
