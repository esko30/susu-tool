package ru.susu.calendarsync

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import ru.susu.calendarsync.SusuApi.str
import java.io.File

/**
 * Красный диплом. Правила: в итоге все оценки 5, допускается не более 25% четвёрок, троек быть не должно.
 * Зачёты («зачтено») в расчёт не входят. За одинаковые дисциплины в дипломе ставится последняя
 * экзаменационная оценка; курсовые работы и практики считаются отдельными строками.
 */
object Diploma {
    /** Одна строка ведомости: дисциплина, форма контроля, семестр и оценка (null — ещё нет). */
    data class Rec(val name: String, val kind: Kind, val term: Int, val mark: Int?)

    enum class Kind(val title: String) { EXAM("экзамен"), DIFF("дифф. зачёт"), COURSE("курсовая"), PRACTICE("практика"), CREDIT("зачёт"),
        /** Итоговая аттестация (госэкзамен, ВКР): в 25% не входит, нужна только 5. */
        GIA("ГИА") }

    /** Итоговая строка диплома: дисциплина и оценка, которая в него пойдёт. */
    data class Line(val name: String, val kind: Kind, val term: Int, val mark: Int?)

    data class Result(
        val lines: List<Line>,
        val fives: Int, val fours: Int, val threes: Int,
        /** Ещё без оценки (текущая сессия). */
        val pending: Int,
        /** Сколько ещё четвёрок можно получить, чтобы диплом остался красным; меньше 0 — уже перебор. */
        val foursLeft: Int,
        /** Итоговая аттестация: отдельно, только на 5. */
        val gia: List<Line> = emptyList(),
    ) {
        val graded get() = fives + fours + threes
        val total get() = graded + pending
        val possible get() = threes == 0 && foursLeft >= 0
        val foursShare get() = if (graded == 0) 0.0 else fours * 100.0 / graded
    }

    /**
     * Вид строки диплома. Практики и ГИА узнаём по названию: в ведомости у практики форма «Дифф. зачёт»,
     * а в учебном плане формы может не быть вовсе — без этого одна практика превращалась в две строки.
     */
    private val PRACTICE_NAME = Regex("""практик[аиуе]\b""")

    fun kindOf(controlLabel: String, name: String = ""): Kind {
        val t = controlLabel.lowercase().replace('ё', 'е')
        val n = name.lowercase().replace('ё', 'е')
        return when {
            "государствен" in n && "экзамен" in n || "выпускная квалификационная" in n || "итоговая аттестация" in n -> Kind.GIA
            "зачет" in t && "диф" !in t -> Kind.CREDIT
            PRACTICE_NAME.containsMatchIn(n) -> Kind.PRACTICE // «практика», но не «практикум»
            "курсов" in t -> Kind.COURSE
            "практик" in t -> Kind.PRACTICE
            "диф" in t -> Kind.DIFF
            "зачет" in t -> Kind.CREDIT
            else -> Kind.EXAM
        }
    }

    /** Оценка из текста ведомости: «5», «отлично», «хорошо (4)»… Зачёт/незачёт и пустое — null. */
    fun parseMark(raw: String?): Int? {
        val t = raw?.trim()?.lowercase()?.replace('ё', 'е') ?: return null
        if (t.isEmpty() || t == "null") return null
        return when {
            "неуд" in t -> 2
            "удовл" in t -> 3
            "хорош" in t -> 4
            "отлич" in t -> 5
            else -> Regex("""\b([2-5])\b""").find(t)?.groupValues?.get(1)?.toInt()
        }
    }

    private fun norm(name: String) = name.lowercase().replace('ё', 'е').replace(Regex("""\s+"""), " ").trim()

    /** Расчёт по всем строкам ведомостей. */
    fun analyze(recs: List<Rec>): Result {
        val graded = recs.filter { it.kind != Kind.CREDIT && it.kind != Kind.GIA }
        // Ключ строки диплома: дисциплина; курсовая и практика по ней — отдельные строки.
        val byKey = graded.groupBy { r ->
            norm(r.name) + "|" + when (r.kind) { Kind.COURSE -> "course"; Kind.PRACTICE -> "practice"; else -> "subject" }
        }
        val lines = byKey.values.map { group ->
            // Последняя по семестру; в одном семестре запись с оценкой важнее пустой, экзамен — диффзачёта.
            val last = group.sortedWith(compareBy<Rec>({ it.term }, { if (it.mark != null) 1 else 0 },
                { if (it.kind == Kind.EXAM) 1 else 0 })).last()
            Line(last.name, last.kind, last.term, last.mark)
        }.sortedWith(compareBy({ it.term }, { it.name }))
        val fives = lines.count { it.mark == 5 }
        val fours = lines.count { it.mark == 4 }
        val threes = lines.count { it.mark != null && it.mark <= 3 }
        val pending = lines.count { it.mark == null }
        val maxFours = Math.floorDiv(lines.size * 25, 100)
        val gia = recs.filter { it.kind == Kind.GIA }.groupBy { norm(it.name) }.values
            .map { g -> g.sortedWith(compareBy({ it.term }, { if (it.mark != null) 1 else 0 })).last() }
            .map { Line(it.name, it.kind, it.term, it.mark) }.sortedBy { it.term }
        return Result(lines, fives, fours, threes, pending, maxFours - fours, gia)
    }

    // ---------- Загрузка ведомостей с портала (все семестры) и кэш.

    private fun file(c: Context) = File(c.filesDir, "diploma.json")

    fun load(c: Context): List<Rec>? = runCatching {
        val a = JSONArray(file(c).readText())
        (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            Rec(o.getString("n"), Kind.valueOf(o.getString("k")), o.getInt("t"), if (o.has("m")) o.getInt("m") else null)
        }
    }.getOrNull()

    private fun save(c: Context, recs: List<Rec>) {
        val a = JSONArray()
        recs.forEach { r -> a.put(JSONObject().put("n", r.name).put("k", r.kind.name).put("t", r.term).apply { r.mark?.let { put("m", it) } }) }
        file(c).writeText(a.toString())
    }

    fun clear(c: Context) { file(c).delete() }

    /** Оценка из записи ведомости: пробуем известные поля, затем любое текстовое поле с «отлично/хорошо/…». */
    private fun markOf(o: JSONObject): Int? {
        for (k in listOf("mark", "markName", "markValue", "grade", "result", "assessment")) parseMark(o.str(k))?.let { return it }
        for (k in o.keys()) if (k.contains("mark", true) || k.contains("grade", true)) parseMark(o.str(k))?.let { return it }
        return null
    }

    /** Все семестры: дисциплины (с формой контроля) и оценки из ведомостей. Сеть — вызывать в фоне. */
    /**
     * Все строки будущего диплома: дисциплины и практики по учебному плану за все семестры, включая будущие
     * (у них оценки ещё нет), и итоговые оценки из ведомостей прошедших семестров.
     */
    fun fetch(c: Context, token: String, currentTerm: Int, termCount: Int): List<Rec> {
        val recs = LinkedHashMap<String, Rec>()
        fun add(o: JSONObject, term: Int, markFromRecord: Boolean) {
            val name = o.str("disciplineName").ifEmpty { return }
            val label = o.str("controlType").ifEmpty { Grades.CONTROL_TYPES[o.str("controlTypeId")].orEmpty() }
            val key = "${o.str("disciplineId")}|${o.str("controlTypeId")}|$term"
            val prev = recs[key]
            val mark = if (markFromRecord) markOf(o) else null
            recs[key] = Rec(name, kindOf(label, name), term, mark ?: prev?.mark)
        }
        // Дисциплины и практики портал отдаёт сразу за все семестры учебного плана, в том числе будущие.
        val last = maxOf(termCount, currentTerm)
        for (path in listOf("/api/StudyActivity/Subjects/$currentTerm/ru", "/api/StudyActivity/Practices/ru")) runCatching {
            val all = JSONArray(SusuApi.get(token, path))
            for (i in 0 until all.length()) all.optJSONObject(i)?.let { o ->
                val t = o.optInt("termNumber", 0)
                if (t in 1..last) add(o, t, t <= currentTerm)
            }
        }
        // Итоговые оценки — из ведомостей каждого семестра.
        for (t in 1..currentTerm) runCatching {
            val a = JSONArray(SusuApi.get(token, "/api/StudyActivity/UserCheckout/Term/$t/ru"))
            for (i in 0 until a.length()) a.optJSONObject(i)?.let { add(it, it.optInt("termNumber", t), true) }
        }
        val list = recs.values.toList()
        if (list.isNotEmpty()) save(c, list)
        return list
    }
}
