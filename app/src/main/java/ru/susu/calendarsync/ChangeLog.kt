package ru.susu.calendarsync

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Одно изменение в расписании или успеваемости, уже в человекочитаемом виде. */
data class Change(val kind: Kind, val date: String, val title: String, val text: String) {
    enum class Kind(val label: String) {
        ADDED("Добавлено"),
        REMOVED("Отменено"),
        MOVED("Перенесено"),
        MODIFIED("Изменено"),
        KRM_NEW("Новое КРМ"),
        GRADE("Оценка"),
    }
}

/** Изменения, найденные за одну синхронизацию в одном источнике (расписание или оценки). */
data class ChangeSet(val timestamp: Long, val source: String, val changes: List<Change>)

/** Сравнение старой и новой версии расписания (только занятия с сегодняшнего дня). */
fun diffLessons(old: List<Lesson>, new: List<Lesson>, today: String): List<Change> {
    fun Lesson.snap() = LessonSnapshot(date, beginTime, endTime, subject, title, location, details)
    val oldMap = old.filter { it.date >= today }.associateBy { it.key }
    val newMap = new.filter { it.date >= today }.associateBy { it.key }
    val added = newMap.filterKeys { it !in oldMap }.values.map { it.snap() }
    val removed = oldMap.filterKeys { it !in newMap }.values.map { it.snap() }
    val modified = newMap.mapNotNull { (k, n) ->
        val o = oldMap[k] ?: return@mapNotNull null
        if (o.title != n.title || o.location != n.location || o.details != n.details) o.snap() to n.snap() else null
    }
    return ChangeDiff.build(added, removed, modified)
}

/** Снимок занятия для сравнения старой и новой версии. */
data class LessonSnapshot(
    val date: String,
    val begin: String,
    val end: String,
    val subject: String,
    val title: String,
    val location: String,
    val details: String,
)

object ChangeDiff {
    private val DAY = DateTimeFormatter.ofPattern("EE, d MMMM", Locale("ru"))

    fun day(date: String): String = try {
        LocalDate.parse(date).format(DAY)
    } catch (e: Exception) {
        date
    }

    private fun LessonSnapshot.whenText() = "${day(date)}, $begin–$end"

    private fun LessonSnapshot.full() = buildString {
        append(whenText())
        if (location.isNotBlank()) append(", ").append(location)
    }

    /**
     * Строит список изменений. Пары «отменено + добавлено» с тем же предметом в тот же день
     * считаются переносом по времени.
     */
    fun build(
        added: List<LessonSnapshot>,
        removed: List<LessonSnapshot>,
        modified: List<Pair<LessonSnapshot, LessonSnapshot>>,
    ): List<Change> {
        val result = ArrayList<Change>()
        val addedLeft = added.toMutableList()

        for (old in removed) {
            val new = addedLeft.firstOrNull { it.date == old.date && it.subject == old.subject }
            if (new != null) {
                addedLeft.remove(new)
                val parts = mutableListOf("${day(old.date).replaceFirstChar { it.uppercase() }} теперь ${new.begin}–${new.end}, было ${old.begin}–${old.end}")
                parts += fieldDiff(old, new)
                result += Change(Change.Kind.MOVED, old.date, old.title, parts.joinToString(". "))
            } else {
                result += Change(Change.Kind.REMOVED, old.date, old.title, old.full())
            }
        }
        for (new in addedLeft) {
            result += Change(Change.Kind.ADDED, new.date, new.title, new.full())
        }
        for ((old, new) in modified) {
            val diff = fieldDiff(old, new)
            if (diff.isEmpty()) continue // поменялось только напоминание
            result += Change(Change.Kind.MODIFIED, new.date, new.title, "${new.whenText()}. " + diff.joinToString(". "))
        }
        return result.sortedWith(compareBy({ it.date }, { it.text }))
    }

    /** Разница в месте и строках описания вида «Поле: значение». */
    private fun fieldDiff(old: LessonSnapshot, new: LessonSnapshot): List<String> {
        val out = mutableListOf<String>()
        if (old.title != new.title) out += "Теперь называется «${new.title}»"
        if (old.location != new.location) out += if (new.location.isBlank()) "Аудиторию убрали" else "Теперь в ${new.location}"
        val o = parseDetails(old.details)
        val n = parseDetails(new.details)
        for (field in (o.keys + n.keys).distinct()) {
            if (field == "Тип" && old.title != new.title) continue // уже видно по названию
            val a = o[field].orEmpty()
            val b = n[field].orEmpty()
            if (a != b) out += if (b.isBlank()) "$field теперь не указан" else "$field теперь $b"
        }
        return out
    }

    private fun parseDetails(details: String): Map<String, String> =
        details.lines().filter { it.isNotBlank() }.associate { line ->
            val i = line.indexOf(':')
            if (i > 0) line.substring(0, i).trim() to line.substring(i + 1).trim() else line to ""
        }
}

/** История изменений, хранится в JSON-файле в личной папке приложения. */
class ChangeLog(context: Context) {
    private val file = File(context.filesDir, "changes.json")

    @Synchronized
    fun load(): List<ChangeSet> {
        if (!file.exists()) return emptyList()
        return try {
            val arr = JSONArray(file.readText())
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                val ch = o.getJSONArray("changes")
                ChangeSet(o.getLong("ts"), o.optString("src", "Моё расписание"), (0 until ch.length()).map { j ->
                    val c = ch.getJSONObject(j)
                    Change(Change.Kind.valueOf(c.getString("kind")), c.getString("date"), c.getString("title"), c.getString("text"))
                })
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    @Synchronized
    fun add(set: ChangeSet) {
        val all = (listOf(set) + load()).take(MAX_SETS)
        val arr = JSONArray()
        for (s in all) {
            val ch = JSONArray()
            for (c in s.changes) {
                ch.put(JSONObject().put("kind", c.kind.name).put("date", c.date).put("title", c.title).put("text", c.text))
            }
            arr.put(JSONObject().put("ts", s.timestamp).put("src", s.source).put("changes", ch))
        }
        file.writeText(arr.toString())
    }

    @Synchronized
    fun clear() {
        file.delete()
    }

    companion object {
        private const val MAX_SETS = 100
    }
}
