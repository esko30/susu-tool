package ru.susu.calendarsync

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Сохранённое расписание (своё, группы, преподавателя или аудитории). */
data class SavedSchedule(
    val kind: ScheduleKind,
    val id: String,
    val name: String,
    val description: String,
    val own: Boolean = false,
    val monitor: Boolean = true,
    val toCalendar: Boolean = false,
    val lastUpdate: Long = 0,
) {
    val uid get() = "${kind.name}_$id"

    /** Имя календаря на телефоне. У своего расписания — прежнее, чтобы не потерять уже созданный календарь. */
    val calendarKey get() = if (own) "susu_schedule" else "suss_$uid"
    val calendarTitle get() = if (own) "SUSU Tool · моё расписание" else "SUSU Tool · $name"
    val label get() = if (own) "Моё расписание" else "$name"

    fun toJson(): JSONObject = JSONObject()
        .put("kind", kind.name).put("id", id).put("name", name).put("desc", description)
        .put("own", own).put("monitor", monitor).put("cal", toCalendar).put("upd", lastUpdate)

    companion object {
        fun fromJson(o: JSONObject) = SavedSchedule(
            ScheduleKind.valueOf(o.getString("kind")), o.getString("id"), o.optString("name"),
            o.optString("desc"), o.optBoolean("own"), o.optBoolean("monitor", true),
            o.optBoolean("cal"), o.optLong("upd"),
        )

        /** Сохранённое из поиска расписание по умолчанию не отслеживается — включается вручную. */
        fun from(info: ScheduleInfo) = SavedSchedule(info.kind, info.id, info.name, info.description, monitor = false)
    }
}

/** Файловое хранилище: список сохранённых расписаний и кэш занятий для офлайн-просмотра. */
class Store(context: Context) {
    private val appContext = context.applicationContext
    private val dir = context.filesDir
    private val listFile = File(dir, "saved_schedules.json")

    @Synchronized
    fun saved(): List<SavedSchedule> {
        if (!listFile.exists()) return emptyList()
        return try {
            val arr = JSONArray(listFile.readText())
            (0 until arr.length()).map { SavedSchedule.fromJson(arr.getJSONObject(it)) }
                .sortedWith(compareByDescending<SavedSchedule> { it.own }.thenBy { it.kind }.thenBy { it.name })
        } catch (e: Exception) {
            emptyList()
        }
    }

    @Synchronized
    private fun write(list: List<SavedSchedule>) {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        listFile.writeText(arr.toString())
    }

    fun find(uid: String) = saved().firstOrNull { it.uid == uid }

    @Synchronized
    fun upsert(s: SavedSchedule) {
        write(saved().filter { it.uid != s.uid } + s)
    }

    @Synchronized
    fun remove(uid: String) {
        write(saved().filter { it.uid != uid })
        cacheFile(uid).delete()
    }

    @Synchronized
    fun ensureOwnSchedule(auth: LoginResult) {
        val kind = if (auth.isInstructor) ScheduleKind.TEACHER else ScheduleKind.GROUP
        val current = saved()
        val own = current.firstOrNull { it.own }
        if (own != null && own.kind == kind && own.id == auth.scheduleId) return
        val name = own?.name?.takeIf { own.id == auth.scheduleId } ?: auth.displayName
        val updated = SavedSchedule(
            kind, auth.scheduleId, name, "", own = true,
            monitor = own?.monitor ?: true, toCalendar = own?.toCalendar ?: true,
        )
        // Если такое расписание уже было сохранено как «чужое» — делаем его своим.
        write(current.filter { !it.own && it.uid != updated.uid } + updated)
        if (own != null && own.uid != updated.uid) cacheFile(own.uid).delete()
    }

    fun own() = saved().firstOrNull { it.own }

    private fun cacheFile(uid: String) = File(dir, "lessons_$uid.json")

    fun hasCache(uid: String) = cacheFile(uid).exists()

    fun lessons(uid: String): List<Lesson> {
        val f = cacheFile(uid)
        if (!f.exists()) return emptyList()
        return try {
            val arr = JSONArray(f.readText())
            (0 until arr.length()).map { Lesson.fromJson(arr.getJSONObject(it)) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun saveLessons(uid: String, lessons: List<Lesson>) {
        saveLessonsOnly(uid, lessons)
        runCatching { NowWidget.update(appContext) }
    }

    private fun saveLessonsOnly(uid: String, lessons: List<Lesson>) {
        val arr = JSONArray()
        lessons.forEach { arr.put(it.toJson()) }
        cacheFile(uid).writeText(arr.toString())
    }

    fun clearAll() {
        dir.listFiles()?.filter { it.name.startsWith("lessons_") || it.name == listFile.name }?.forEach { it.delete() }
    }
}
