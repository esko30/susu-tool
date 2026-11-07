package ru.susu.calendarsync

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import ru.susu.calendarsync.SusuApi.str
import java.io.File
import java.time.LocalDate

/** Контрольное мероприятие (КРМ), лабораторная, бонус и т. п. из журнала дисциплины. */
data class PointEvent(
    val category: String,
    val name: String,
    val point: Double?,
    val maxPoint: Double?,
    val rating: Double?,
    val weight: String,
) {
    val key get() = "$category|$name"
}

data class SubjectGrades(
    val disciplineId: String,
    val name: String,
    val term: Int,
    val checkNumber: String,
    val controlTypeId: String,
    val controlType: String,
    val mark: String,
    val rating: Double?,
    val instructors: String,
    val date: String,
    val currentRating: Double?,
    val totalRating: Double?,
    val events: List<PointEvent>,
) {
    val key get() = "$disciplineId|$checkNumber|$controlTypeId"
    val controlLabel get() = controlType.ifBlank { Grades.CONTROL_TYPES[controlTypeId] ?: "Дисциплина" }
    val bestRating get() = totalRating ?: rating
}

data class GradesSnapshot(val term: Int, val termCount: Int, val updated: Long, val subjects: List<SubjectGrades>)

object Grades {
    val CONTROL_TYPES = mapOf(
        "d888a219-c6a5-5037-1ee8-94037bd95d56" to "Экзамен",
        "a5b9c411-63f1-29e1-235a-9387f80b85e7" to "Зачёт",
        "01ae3eb6-2ffa-46c1-a8cc-370d10b1ecc7" to "Дифф. зачёт",
        "96da8807-2b8b-b170-971f-430b9a19dd8a" to "Курсовая работа",
        "5058be9b-5bef-8e6e-07fb-acd318c1a6ce" to "Курсовой проект",
        "d8c42892-e377-5376-f238-0dbdac65541b" to "Практика",
    )
    private val COURSE_WORK = setOf("96da8807-2b8b-b170-971f-430b9a19dd8a", "5058be9b-5bef-8e6e-07fb-acd318c1a6ce")
    private val CATEGORIES = linkedMapOf(
        "currentControl" to "Текущий контроль",
        "labs" to "Лабораторные",
        "bonuses" to "Бонусы",
        "attestation" to "Аттестация",
        "courseWorksOrProjects" to "Курсовая",
    )

    fun markText(mark: String): String = when (mark.trim()) {
        "5" -> "5 (отлично)"
        "4" -> "4 (хорошо)"
        "3" -> "3 (удовл.)"
        "2" -> "2 (неудовл.)"
        "" -> "—"
        else -> mark
    }

    fun fmt(v: Double?): String = when {
        v == null -> "—"
        v == Math.floor(v) -> v.toLong().toString()
        else -> String.format(java.util.Locale.US, "%.2f", v).trimEnd('0').trimEnd('.')
    }

    /** (текущий семестр, всего семестров) */
    fun studyPlan(token: String): Pair<Int, Int> {
        val o = JSONObject(SusuApi.get(token, "/api/StudyActivity/StudyPlan"))
        val current = o.optInt("currentTerm", 1).coerceAtLeast(1)
        return current to o.optInt("termCount", current).coerceAtLeast(current)
    }

    /** Загружает предметы семестра и журналы по каждому (баллы за КРМ). */
    fun fetch(token: String, term: Int, termCount: Int): GradesSnapshot {
        val merged = LinkedHashMap<String, JSONObject>()
        fun addAll(json: String, filterTerm: Boolean) {
            val arr = runCatching { JSONArray(json) }.getOrNull() ?: return
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                if (filterTerm && o.optInt("termNumber", term) != term) continue
                val k = "${o.str("disciplineId")}|${o.str("checkNumber")}|${o.str("controlTypeId")}"
                merged[k] = merged[k]?.let { prev -> mergeJson(prev, o) } ?: o
            }
        }
        // Портал отдаёт дисциплины всех семестров сразу — оставляем только нужный.
        addAll(SusuApi.get(token, "/api/StudyActivity/Subjects/$term/ru"), true)
        runCatching { addAll(SusuApi.get(token, "/api/StudyActivity/Practices/ru"), true) }
        // Итоговые оценки из ведомостей перекрывают данные из списка дисциплин.
        runCatching { addAll(SusuApi.get(token, "/api/StudyActivity/UserCheckout/Term/$term/ru"), true) }

        val subjects = merged.values.map { o ->
            val id = o.str("disciplineId")
            val ct = o.str("controlTypeId")
            val journal = runCatching {
                JSONObject(SusuApi.get(token, "/api/StudyActivity/Journal/$id/$term/IsCourseWorkOrProject/${ct in COURSE_WORK}/ru"))
            }.getOrNull()
            val events = ArrayList<PointEvent>()
            if (journal != null) {
                for ((field, label) in CATEGORIES) {
                    val arr = journal.optJSONArray(field) ?: continue
                    for (i in 0 until arr.length()) {
                        val e = arr.optJSONObject(i) ?: continue
                        events += PointEvent(
                            label, e.str("name").ifEmpty { "Мероприятие ${i + 1}" },
                            num(e, "point"), num(e, "maxPoint"), num(e, "rating"), e.str("weight"),
                        )
                    }
                }
            }
            val instructors = o.optJSONArray("instructors")?.let { a ->
                (0 until a.length()).mapNotNull { i ->
                    a.optJSONObject(i)?.let { p ->
                        listOf("lastName", "firstName", "middleName").map { p.str(it) }.filter { it.isNotBlank() }
                            .joinToString(" ").ifEmpty { p.str("name") }
                    } ?: a.optString(i)
                }
            }.orEmpty().ifEmpty { listOf(o.str("instructor")) }.filter { it.isNotBlank() }.joinToString(", ")
            SubjectGrades(
                disciplineId = id,
                name = o.str("disciplineName").ifEmpty { journal?.str("disciplineName").orEmpty() },
                term = term,
                checkNumber = o.str("checkNumber"),
                controlTypeId = ct,
                controlType = o.str("controlType"),
                mark = o.str("mark"),
                rating = num(o, "rating"),
                instructors = instructors,
                date = o.str("date").take(10),
                currentRating = journal?.let { num(it, "currentRating") },
                totalRating = journal?.let { num(it, "totalRating") },
                events = events,
            )
        }.sortedBy { it.name }
        return GradesSnapshot(term, termCount, System.currentTimeMillis(), subjects)
    }

    private fun mergeJson(a: JSONObject, b: JSONObject): JSONObject {
        val r = JSONObject(a.toString())
        b.keys().forEach { k -> if (!b.isNull(k)) r.put(k, b.get(k)) }
        return r
    }

    private fun num(o: JSONObject, key: String): Double? {
        if (!o.has(key) || o.isNull(key)) return null
        val v = o.opt(key)
        return when (v) {
            is Number -> v.toDouble()
            is String -> v.replace(',', '.').trim().toDoubleOrNull()
            else -> null
        }
    }

    /** Изменения в оценках между двумя снимками одного семестра. */
    fun diff(old: GradesSnapshot, new: GradesSnapshot): List<Change> {
        if (old.term != new.term) return emptyList()
        val today = LocalDate.now(CalendarSync.SUSU_ZONE).toString()
        val oldMap = old.subjects.associateBy { it.key }
        val result = ArrayList<Change>()
        for (s in new.subjects) {
            val o = oldMap[s.key] ?: continue
            val lines = ArrayList<Change>()
            val oldEvents = o.events.associateBy { it.key }
            for (e in s.events) {
                val prev = oldEvents[e.key]
                if (prev == null) {
                    val pts = if (e.point != null) " — ${fmt(e.point)} из ${fmt(e.maxPoint)}" else ""
                    lines += Change(Change.Kind.KRM_NEW, today, s.name, "${e.category}: ${e.name}$pts")
                } else if (prev.point != e.point || prev.maxPoint != e.maxPoint) {
                    lines += Change(
                        Change.Kind.GRADE, today, s.name,
                        "${e.name}: ${fmt(prev.point)} → ${fmt(e.point)} из ${fmt(e.maxPoint)}",
                    )
                }
            }
            if (o.mark != s.mark) {
                lines += Change(Change.Kind.GRADE, today, s.name, "${s.controlLabel}: ${markText(o.mark)} → ${markText(s.mark)}")
            }
            if (o.bestRating != s.bestRating) {
                val text = "рейтинг ${fmt(o.bestRating)} → ${fmt(s.bestRating)}%"
                if (lines.isEmpty()) lines += Change(Change.Kind.GRADE, today, s.name, text.replaceFirstChar { it.uppercase() })
                else lines[0] = lines[0].copy(text = lines[0].text + "; $text")
            }
            result += lines
        }
        return result
    }
}

/** Кэш оценок по семестрам. */
class GradesStore(context: Context) {
    private val dir = context.filesDir

    private fun file(term: Int) = File(dir, "grades_v2_$term.json")

    fun load(term: Int): GradesSnapshot? {
        val f = file(term)
        if (!f.exists()) return null
        return try {
            val o = JSONObject(f.readText())
            val arr = o.getJSONArray("subjects")
            GradesSnapshot(o.getInt("term"), o.optInt("termCount", o.getInt("term")), o.optLong("updated"),
                (0 until arr.length()).map { i ->
                    val s = arr.getJSONObject(i)
                    val ev = s.getJSONArray("events")
                    SubjectGrades(
                        s.getString("id"), s.getString("name"), s.getInt("term"), s.optString("check"),
                        s.optString("ct"), s.optString("ctl"), s.optString("mark"), s.optDoubleOrNull("rating"), s.optString("ins"),
                        s.optString("date"), s.optDoubleOrNull("cur"), s.optDoubleOrNull("total"),
                        (0 until ev.length()).map { j ->
                            val e = ev.getJSONObject(j)
                            PointEvent(e.getString("cat"), e.getString("name"), e.optDoubleOrNull("p"),
                                e.optDoubleOrNull("max"), e.optDoubleOrNull("r"), e.optString("w"))
                        },
                    )
                })
        } catch (e: Exception) {
            null
        }
    }

    fun save(s: GradesSnapshot) {
        val arr = JSONArray()
        for (g in s.subjects) {
            val ev = JSONArray()
            g.events.forEach { e ->
                ev.put(JSONObject().put("cat", e.category).put("name", e.name).putOpt("p", e.point)
                    .putOpt("max", e.maxPoint).putOpt("r", e.rating).put("w", e.weight))
            }
            arr.put(JSONObject().put("id", g.disciplineId).put("name", g.name).put("term", g.term)
                .put("check", g.checkNumber).put("ct", g.controlTypeId).put("ctl", g.controlType).put("mark", g.mark)
                .putOpt("rating", g.rating).put("ins", g.instructors).put("date", g.date)
                .putOpt("cur", g.currentRating).putOpt("total", g.totalRating).put("events", ev))
        }
        file(s.term).writeText(
            JSONObject().put("term", s.term).put("termCount", s.termCount).put("updated", s.updated)
                .put("subjects", arr).toString()
        )
    }

    fun clearAll() {
        dir.listFiles()?.filter { it.name.startsWith("grades_") }?.forEach { it.delete() }
    }

    private fun JSONObject.optDoubleOrNull(key: String): Double? =
        if (has(key) && !isNull(key)) optDouble(key).takeIf { !it.isNaN() } else null
}
