package ru.susu.calendarsync

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Занятие из расписания портала online.susu.ru. Время — местное (Asia/Yekaterinburg, +05:00). */
data class Lesson(
    val subject: String,
    val eventType: String,
    val date: String,       // yyyy-MM-dd
    val beginTime: String,  // HH:mm
    val endTime: String,    // HH:mm
    val location: String,
    val details: String,
) {
    val title get() = if (eventType.isNotBlank()) "$subject ($eventType)" else subject
    val key get() = "$date|$beginTime|$endTime|$subject"

    fun toJson(): JSONObject = JSONObject()
        .put("s", subject).put("t", eventType).put("d", date)
        .put("b", beginTime).put("e", endTime).put("l", location).put("x", details)

    companion object {
        fun fromJson(o: JSONObject) = Lesson(
            o.optString("s"), o.optString("t"), o.optString("d"),
            o.optString("b"), o.optString("e"), o.optString("l"), o.optString("x"),
        )
    }
}

/** Вид расписания на портале. */
enum class ScheduleKind(val label: String, val endpoint: String) {
    GROUP("Группа", "GetGroupSchedule"),
    TEACHER("Преподаватель", "GetInstructorSchedule"),
    ROOM("Аудитория", "GetRoomSchedule"),
}

/** Результат поиска расписания. */
data class ScheduleInfo(
    val kind: ScheduleKind,
    val id: String,
    val name: String,
    val description: String,
)

data class LoginResult(
    val accessToken: String,
    val scheduleId: String,
    val isInstructor: Boolean,
    val displayName: String,
)

class AuthException(message: String) : IOException(message)

/**
 * Клиент API ЮУрГУ-Онлайн. Эндпоинты взяты из веб-приложения портала (/microgateway/api/...):
 *  auth/login, Schedule/Get{Group,Instructor,Room}Schedule/{id}, Schedule/SearchSchedules,
 *  StudyActivity/StudyPlan, StudyActivity/Subjects/{term}/ru, StudyActivity/UserCheckout/Term/{term}/ru,
 *  StudyActivity/Journal/{disciplineId}/{term}/IsCourseWorkOrProject/{bool}/ru.
 */
object SusuApi {
    private const val BASE = "https://online.susu.ru/microgateway"

    fun login(login: String, password: String): LoginResult {
        val body = form(
            "identity" to "",
            "LoginForm[login]" to login,
            "LoginForm[password]" to password,
        )
        val (code, text) = request("POST", "$BASE/api/auth/login", body = body)
        when (code) {
            in 200..299 -> Unit
            400, 401, 403 -> throw AuthException("Неверный логин или пароль")
            else -> throw IOException("Ошибка входа: HTTP $code")
        }
        val json = JSONObject(text)
        val token = json.optString("accessToken").ifEmpty {
            throw AuthException(json.optString("message").ifEmpty { "Портал не выдал токен доступа" })
        }

        val instructor = json.opt("instructor")
        val isInstructor = instructor != null && instructor != JSONObject.NULL && instructor != false
        val scheduleId = if (isInstructor) {
            json.optString("id")
        } else {
            json.optJSONArray("student")?.optJSONObject(0)?.optString("groupId").orEmpty()
        }
        if (scheduleId.isEmpty()) throw IOException("Не удалось определить группу или преподавателя")

        val name = listOf("lastName", "firstName", "middleName")
            .map { json.optString(it) }.filter { it.isNotEmpty() && it != "null" }
            .joinToString(" ").ifEmpty { login }
        return LoginResult(token, scheduleId, isInstructor, name)
    }

    fun schedule(token: String, kind: ScheduleKind, scheduleId: String): List<Lesson> {
        val arr = JSONArray(get(token, "/api/Schedule/${kind.endpoint}/$scheduleId"))
        val lessons = (0 until arr.length()).mapNotNull { i ->
            arr.optJSONObject(i)?.let { parseLesson(it) }
        }
        if (arr.length() > 0 && lessons.isEmpty()) {
            throw IOException("Не удалось разобрать расписание: формат ответа изменился")
        }
        return lessons
    }

    fun search(token: String, query: String): List<ScheduleInfo> {
        val (code, text) = request(
            "POST", "$BASE/api/Schedule/SearchSchedules", body = form("searchValue" to query), token = token,
        )
        if (code == 401) throw AuthException("Сессия истекла")
        if (code !in 200..299) throw IOException("Ошибка поиска: HTTP $code")
        val arr = JSONArray(text)
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val kind = when {
                o.optBoolean("isLecturer") -> ScheduleKind.TEACHER
                o.optBoolean("isRoom") -> ScheduleKind.ROOM
                else -> ScheduleKind.GROUP
            }
            val id = o.str("id").ifEmpty { return@mapNotNull null }
            val name = o.str("fullName").ifEmpty { o.str("shortName") }
            val desc = if (kind == ScheduleKind.GROUP) o.str("shortDescription").ifEmpty { o.str("description") }
            else o.str("description").ifEmpty { o.str("shortDescription") }
            ScheduleInfo(kind, id, name, desc)
        }
    }

    /** Сырой GET с токеном; 401 превращается в AuthException, чтобы вызывающий мог перелогиниться. */
    fun get(token: String, path: String): String {
        val (code, text) = request("GET", BASE + path, token = token)
        if (code == 401) throw AuthException("Сессия истекла")
        if (code !in 200..299) throw IOException("Ошибка запроса к порталу: HTTP $code")
        return text
    }

    private fun parseLesson(o: JSONObject): Lesson? {
        val date = o.str("eventDate").take(10)
        val begin = o.str("beginTime").take(5)
        val end = o.str("endTime").take(5)
        if (date.length != 10 || begin.length != 5 || end.length != 5) return null

        val instructors = o.optJSONArray("instructors")
        val names = mutableListOf<String>()
        val places = mutableListOf<String>()
        if (instructors != null) {
            for (i in 0 until instructors.length()) {
                val ins = instructors.optJSONObject(i) ?: continue
                ins.str("name").takeIf { it.isNotBlank() }?.let(names::add)
                ins.str("location").takeIf { it.isNotBlank() }?.let(places::add)
            }
        }
        o.str("location").takeIf { it.isNotBlank() }?.let(places::add)

        val groups = o.optJSONArray("groups")?.let { g -> (0 until g.length()).map { g.optString(it) } }
            .orEmpty().filter { it.isNotBlank() }

        val details = buildList {
            o.str("eventType").takeIf { it.isNotBlank() }?.let { add("Тип: $it") }
            if (names.isNotEmpty()) add("Преподаватель: ${names.distinct().joinToString(", ")}")
            if (groups.isNotEmpty()) add("Группы: ${groups.joinToString(", ")}")
        }.joinToString("\n")

        return Lesson(
            subject = o.str("subject").ifBlank { "Занятие" },
            eventType = o.str("eventType"),
            date = date,
            beginTime = begin,
            endTime = end,
            location = places.distinct().joinToString("; "),
            details = details,
        )
    }

    fun JSONObject.str(key: String): String =
        if (isNull(key)) "" else optString(key).trim()

    private fun form(vararg pairs: Pair<String, String>) = pairs.joinToString("&") { (k, v) ->
        URLEncoder.encode(k, "UTF-8") + "=" + URLEncoder.encode(v, "UTF-8")
    }

    private fun request(method: String, url: String, body: String? = null, token: String? = null): Pair<Int, String> {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = 30_000
            conn.readTimeout = 60_000
            conn.setRequestProperty("Accept", "application/json")
            token?.let { conn.setRequestProperty("Authorization", "Bearer $it") }
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            return code to text
        } finally {
            conn.disconnect()
        }
    }
}
