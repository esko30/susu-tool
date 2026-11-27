package ru.susu.calendarsync

import android.content.Context
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * Отметка посещаемости в Moodle ЮУрГУ (edu.susu.ru) — так же, как это делает официальное приложение Moodle.
 *
 * Прямой API посещаемости на сайте закрыт, но плагин «Посещаемость» отдаёт приложению свои экраны через
 * tool_mobile_get_content: на экране занятия (mobile_view_activity) появляется кнопка, когда отметка открыта;
 * форма (mobile_user_form) даёт статусы; отметка — тот же экран занятия с выбранным статусом.
 *
 * Разбор экранов терпим к изменениям вёрстки: атрибуты в любом порядке, разные форматы даты и тире,
 * статус «Присутствовал» по слову или первый в списке. Любой сбой Moodle не ломает ленту.
 */
object Moodle {
    /** Адрес Moodle и параметры входа мобильного приложения. */
    private const val SITE = "https://edu.susu.ru"
    private const val SERVICE = "moodle_mobile_app"
    private const val COMPONENT = "mod_attendance"
    private const val VIEW = "mobile_view_activity"
    private const val FORM = "mobile_user_form"

    /** Список «Посещаемостей» в курсах живёт 12 часов; «Обновить» перечитывает его, если старше 30 минут. */
    private const val CACHE_MS = 12 * 3600_000L
    private const val CACHE_FORCED_MS = 30 * 60_000L

    /** Открытое занятие и пара связываются, если пересекаются по времени; без времени — только по дате. */
    data class Open(
        val course: String, val name: String, val cmid: String, val courseid: String, val sessid: String,
        val date: LocalDate, val begin: LocalTime?, val end: LocalTime?,
    )

    /** «Посещаемость» в курсе. */
    data class Activity(val course: String, val name: String, val cmid: String, val courseid: String)

    // ---------- Сеть.

    private fun post(path: String, form: List<Pair<String, String>>): String {
        val conn = URL(SITE + path).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 20_000
            conn.readTimeout = 40_000
            conn.doOutput = true
            conn.setRequestProperty("Accept", "application/json")
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
            val body = form.joinToString("&") { (k, v) -> URLEncoder.encode(k, "UTF-8") + "=" + URLEncoder.encode(v, "UTF-8") }
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) throw IOException("Moodle ответил ошибкой $code")
            return text
        } finally {
            conn.disconnect()
        }
    }

    /** Ответ — JSON; если пришла страница (техработы, ошибка прокси), понятная ошибка вместо падения. */
    private fun json(text: String): Any = try {
        val t = text.trimStart()
        if (t.startsWith("[")) JSONArray(t) else JSONObject(t)
    } catch (e: JSONException) {
        throw IOException("Moodle сейчас недоступен")
    }

    private fun token(c: Context, fresh: Boolean = false): String {
        val prefs = Prefs(c)
        if (!fresh) prefs.moodleToken.takeIf { it.isNotBlank() }?.let { return it }
        check(prefs.hasCredentials) { "Войдите в «Настройках»" }
        val o = json(post("/login/token.php", listOf(
            "username" to prefs.login, "password" to prefs.password, "service" to SERVICE))) as? JSONObject
            ?: throw IOException("Moodle сейчас недоступен")
        val t = o.optString("token")
        if (t.isBlank()) {
            prefs.moodleToken = ""
            throw IOException(if (o.optString("errorcode") == "invalidlogin")
                "Moodle не принял логин и пароль портала" else o.optString("error").ifBlank { "Не получилось войти в Moodle" })
        }
        prefs.moodleToken = t
        return t
    }

    /** Вызов функции Moodle; истёкший или отозванный токен обновляется один раз. */
    private fun call(c: Context, fn: String, params: List<Pair<String, String>> = emptyList(), retry: Boolean = true): Any {
        val v = json(post("/webservice/rest/server.php",
            listOf("wstoken" to token(c), "wsfunction" to fn, "moodlewsrestformat" to "json") + params))
        if (v is JSONObject && v.has("exception")) {
            val code = v.optString("errorcode")
            if (retry && code in setOf("invalidtoken", "accessexception", "servicerequireslogin")) {
                token(c, fresh = true)
                return call(c, fn, params, retry = false)
            }
            throw IOException(v.optString("message").ifBlank { "Moodle: $code" })
        }
        return v
    }

    private fun content(c: Context, method: String, args: Map<String, String>): String {
        val params = mutableListOf("component" to COMPONENT, "method" to method)
        args.entries.forEachIndexed { i, (k, v) -> params += "args[$i][name]" to k; params += "args[$i][value]" to v }
        val o = call(c, "tool_mobile_get_content", params) as? JSONObject ?: return ""
        val t = o.optJSONArray("templates") ?: return ""
        return (0 until t.length()).joinToString("") { t.optJSONObject(it)?.optString("html").orEmpty() }
    }

    // ---------- Какие «Посещаемости» есть в текущих курсах.

    /** Текущий учебный год в названиях курсов: с августа «2026/2027», раньше — «2025/2026». */
    fun studyYear(d: LocalDate) = if (d.monthValue >= 8) "${d.year}/${d.year + 1}" else "${d.year - 1}/${d.year}"

    private val YEAR_PREFIX = Regex("""^\s*\d{4}\s*/\s*\d{4}""")

    /**
     * Текущий ли курс: в названии этот учебный год; если года в названии нет — по дате окончания курса
     * (не закончился), а курсы прошлых лет с годом в названии пропускаются.
     */
    fun isCurrent(fullname: String, endDateSec: Long, today: LocalDate): Boolean {
        val name = fullname.trim()
        val year = studyYear(today)
        if (YEAR_PREFIX.containsMatchIn(name)) return name.replace(" ", "").startsWith(year)
        return endDateSec > 0 && endDateSec * 1000 > System.currentTimeMillis()
    }

    private fun cacheFile(c: Context) = File(c.filesDir, "moodle_activities.json")

    fun activities(c: Context, force: Boolean = false): List<Activity> {
        val f = cacheFile(c)
        val age = if (f.exists()) System.currentTimeMillis() - f.lastModified() else Long.MAX_VALUE
        if (age < (if (force) CACHE_FORCED_MS else CACHE_MS)) runCatching {
            val a = JSONArray(f.readText())
            return (0 until a.length()).map { a.getJSONObject(it).let { o ->
                Activity(o.getString("course"), o.getString("name"), o.getString("cmid"), o.getString("courseid")) } }
        }
        val info = call(c, "core_webservice_get_site_info") as JSONObject
        val courses = call(c, "core_enrol_get_users_courses", listOf("userid" to info.getLong("userid").toString())) as? JSONArray
            ?: JSONArray()
        val today = Clock.today()
        val out = ArrayList<Activity>()
        for (i in 0 until courses.length()) {
            val course = courses.optJSONObject(i) ?: continue
            val name = course.optString("fullname").trim()
            if (!isCurrent(name, course.optLong("enddate"), today)) continue
            val id = course.optLong("id").takeIf { it > 0 }?.toString() ?: continue
            val sections = runCatching { call(c, "core_course_get_contents", listOf("courseid" to id)) as? JSONArray }.getOrNull() ?: continue
            for (s in 0 until sections.length()) {
                val mods = sections.optJSONObject(s)?.optJSONArray("modules") ?: continue
                for (m in 0 until mods.length()) {
                    val mod = mods.optJSONObject(m) ?: continue
                    if (mod.optString("modname") == "attendance" && mod.optBoolean("uservisible", true))
                        out += Activity(name, mod.optString("name"), mod.optLong("id").toString(), id)
                }
            }
        }
        val a = JSONArray()
        out.forEach { a.put(JSONObject().put("course", it.course).put("name", it.name).put("cmid", it.cmid).put("courseid", it.courseid)) }
        f.writeAtomic(a.toString())
        return out
    }

    /** Все открытые сейчас для отметки занятия. Сбой одной «Посещаемости» не мешает остальным. */
    fun openSessions(c: Context, force: Boolean = false): List<Open> = activities(c, force).flatMap { a ->
        runCatching { parseOpen(a, content(c, VIEW, mapOf("cmid" to a.cmid, "courseid" to a.courseid))) }
            .getOrDefault(emptyList())
    }

    // ---------- Разбор экранов плагина (покрыт тестами).

    /** Дата «27.11.26» или «27.11.2026»; время «09:45 - 11:20» с любым тире. */
    private val DATE = Regex("""(\d{1,2})\.(\d{1,2})\.(\d{2,4})""")
    private val TIMES = Regex("""(\d{1,2}):(\d{2})\s*[-–—]\s*(\d{1,2}):(\d{2})""")
    private val TAG = Regex("""<ion-button\b[^>]*>""", RegexOption.IGNORE_CASE)
    // Скобки экранированы все: движок регулярных выражений Android (ICU) не принимает голые ] и }.
    private val ARGS = Regex("""\[args\]\s*=\s*"\{([^"]*)\}"""")
    private val ARG = Regex("""(\w+)\s*:\s*'?([\w.-]+)'?""")

    /** Параметры кнопки, ведущей на форму отметки; атрибуты в теге могут идти в любом порядке. */
    private fun markButtonArgs(item: String): Map<String, String>? {
        val tag = TAG.findAll(item).map { it.value }.firstOrNull { "\"$FORM\"" in it } ?: return null
        val raw = ARGS.find(tag)?.groupValues?.get(1) ?: return null
        return ARG.findAll(raw).associate { it.groupValues[1] to it.groupValues[2] }.takeIf { "sessid" in it }
    }

    /** Открытые занятия на экране «Посещаемости»: у каждого — дата, время (если есть) и параметры кнопки. */
    fun parseOpen(a: Activity, html: String): List<Open> = html.split(Regex("""<ion-item\b""")).mapNotNull { item ->
        val args = markButtonArgs(item) ?: return@mapNotNull null
        val d = DATE.find(item) ?: return@mapNotNull null
        val (dd, mm, yy) = d.destructured
        val year = yy.toInt().let { if (it < 100) 2000 + it else it }
        val date = runCatching { LocalDate.of(year, mm.toInt(), dd.toInt()) }.getOrNull() ?: return@mapNotNull null
        val t = TIMES.find(item, d.range.last)
        val begin = t?.let { runCatching { LocalTime.of(it.groupValues[1].toInt(), it.groupValues[2].toInt()) }.getOrNull() }
        val end = t?.let { runCatching { LocalTime.of(it.groupValues[3].toInt(), it.groupValues[4].toInt()) }.getOrNull() }
        Open(a.course, a.name, args["cmid"] ?: a.cmid, args["courseid"] ?: a.courseid, args.getValue("sessid"), date, begin, end)
    }

    /** Статусы из формы отметки: подпись и номер, в порядке формы. */
    fun parseStatuses(html: String): List<Pair<String, String>> =
        Regex("""<ion-label[^>]*>\s*([^<]+?)\s*</ion-label>\s*<ion-radio[^>]*\bvalue\s*=\s*"([^"]+)"""").findAll(html)
            .map { it.groupValues[1].trim() to it.groupValues[2] }.toList()

    /**
     * Статус «присутствовал» — только если он узнаётся уверенно: «Присутствовал», «Присутствие», «Был»,
     * «Present». Отрицания («Не присутствовал», «Не был», «Отсутствовал», «Нет») не подходят никогда.
     * Не узнали — null: тогда приложение спросит, какой статус выбрать, а не угадывает.
     */
    fun presentStatus(statuses: List<Pair<String, String>>): Pair<String, String>? = statuses.firstOrNull { (label, _) ->
        val t = label.lowercase().replace('ё', 'е').trim()
        val negative = t.startsWith("не") || Regex("""\bне\b|отсутств|нет\b|absent|not\b""").containsMatchIn(t)
        val positive = "присутств" in t || t.startsWith("был") || t.startsWith("present")
        positive && !negative
    }

    /** Нужен ли пароль занятия (преподаватель может его задать). */
    fun needsPassword(html: String) = Regex("""type\s*=\s*"password"|name\s*=\s*"studentpass"""").containsMatchIn(html) &&
        Regex("""<ion-input|<input""").containsMatchIn(html)

    // ---------- Какую открытую отметку показать на карточке пары.

    private fun words(s: String) = s.lowercase().replace('ё', 'е')
        .replace(YEAR_PREFIX, "").substringBefore("(")
        .split(Regex("""[^\p{L}\d]+""")).filter { it.length > 2 }.toSet()

    /**
     * Мягкая привязка. Главное — время: занятие в тот же день, пересекающееся с парой (если у занятия нет
     * времени — подходит, только если оно одно в этот день). Если подходят несколько, побеждает то, где больше
     * совпадений: слова названия курса с предметом, тип пары с названием «Посещаемости» (лекция / семинар,
     * практика), группа студента в названии.
     */
    fun matchFor(lesson: Lesson, open: List<Open>, group: String = ""): Open? {
        val start = runCatching { LocalDateTime.parse("${lesson.date}T${lesson.beginTime}") }.getOrNull() ?: return null
        val end = runCatching { LocalDateTime.parse("${lesson.date}T${lesson.endTime}") }.getOrNull() ?: start.plusMinutes(95)
        val sameDay = open.filter { it.date.toString() == lesson.date }
        val candidates = sameDay.filter { o ->
            if (o.begin == null) return@filter sameDay.size == 1
            val s = LocalDateTime.of(o.date, o.begin)
            val e = o.end?.let { LocalDateTime.of(o.date, it) }?.takeIf { it.isAfter(s) } ?: s.plusMinutes(95)
            s.isBefore(end) && e.isAfter(start)
        }
        if (candidates.size <= 1) return candidates.firstOrNull()
        val subject = words(lesson.subject)
        val type = lesson.eventType.lowercase()
        val g = group.lowercase().replace(" ", "")
        val isLecture = type.startsWith("лекц")
        val isSeminar = type.startsWith("практ") || type.startsWith("семин") || type.startsWith("лаб")
        fun score(o: Open): Int {
            val name = o.name.lowercase().replace('ё', 'е')
            val lectureName = "лекц" in name
            val seminarName = "семин" in name || "практ" in name || "лаб" in name
            var sc = (words(o.course) intersect subject).size * 2
            // Тип пары: совпал — плюс, явно другой — минус.
            if (isLecture) sc += if (lectureName) 3 else if (seminarName) -3 else 0
            if (isSeminar) sc += if (seminarName) 3 else if (lectureName) -3 else 0
            // Группа важна только для семинаров и практик: лекция общая для потока.
            if (!isLecture && g.isNotBlank() && g in name.replace(" ", "")) sc += 4
            return sc
        }
        return candidates.maxByOrNull(::score)
    }

    // ---------- Отметка.

    sealed class Result {
        object Marked : Result()
        object NeedPassword : Result()
        data class Failed(val message: String) : Result()
        /** Статус «присутствовал» не узнан — пусть выберет студент. */
        data class ChooseStatus(val statuses: List<Pair<String, String>>) : Result()
    }

    /**
     * Ставит «Присутствовал» (или статус [statusId], выбранный студентом). Если статус не узнан — просит выбрать,
     * если занятие с паролем и [password] не передан — просит пароль.
     */
    fun markPresent(c: Context, o: Open, password: String? = null, statusId: String? = null): Result {
        val form = content(c, FORM, mapOf("cmid" to o.cmid, "courseid" to o.courseid, "sessid" to o.sessid))
        val statuses = parseStatuses(form)
        if (statuses.isEmpty()) return Result.Failed("Отметка на этом занятии уже закрыта")
        val present = statusId?.let { id -> statuses.firstOrNull { it.second == id } ?: return Result.Failed("Такого статуса больше нет") }
            ?: presentStatus(statuses) ?: return Result.ChooseStatus(statuses)
        if (needsPassword(form) && password == null) return Result.NeedPassword
        val after = content(c, VIEW, mapOf(
            "cmid" to o.cmid, "courseid" to o.courseid, "sessid" to o.sessid,
            "status" to present.second, "studentpass" to (password ?: "")))
        // Отметка принята, если у этого занятия больше нет кнопки.
        val still = parseOpen(Activity(o.course, o.name, o.cmid, o.courseid), after).any { it.sessid == o.sessid }
        return if (!still) Result.Marked
            else Result.Failed(if (password != null) "Неверный пароль занятия" else "Moodle не принял отметку")
    }

    fun clear(c: Context) {
        Prefs(c).moodleToken = ""
        cacheFile(c).delete()
    }
}
