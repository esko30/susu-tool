package ru.susu.calendarsync

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/** Текущее время для расписания и виджета; [demo] подменяет его (идёт дальше от заданной точки). */
object Clock {
    @Volatile private var demo: LocalDateTime? = null
    @Volatile private var setAt = 0L

    fun setDemo(at: LocalDateTime?) { demo = at; setAt = System.currentTimeMillis() }

    fun now(): LocalDateTime = demo?.plusSeconds((System.currentTimeMillis() - setAt) / 1000)
        ?: LocalDateTime.now(CalendarSync.SUSU_ZONE)

    fun today(): LocalDate = now().toLocalDate()
}

/** Скрытые пары: свайп в ленте сжимает пару в полоску, окна и виджет её не учитывают. */
object Hidden {
    fun key(l: Lesson) = "${l.key}|${l.location}"

    fun isHidden(set: Set<String>, l: Lesson) = key(l) in set

    /** Убираем скрытые пары старше месяца, чтобы список не рос бесконечно. */
    fun prune(set: Set<String>, today: LocalDate): Set<String> {
        val limit = today.minusDays(31).toString()
        return set.filterTo(HashSet()) { it.substringBefore('|') >= limit }
    }
}

/** Расчёты по ленте занятий без интерфейса: окна между парами, «сейчас / дальше», недели. Покрыты тестами. */
object Timeline {
    /** Свободное окно между парами одного дня. */
    data class Window(val from: String, val to: String, val minutes: Long)

    /** Окно — перерыв не меньше этого (обычные перемены 10 минут, большая — 30). */
    const val MIN_WINDOW = 40L

    private fun time(s: String) = runCatching { LocalTime.parse(s) }.getOrNull()

    /**
     * Окна внутри дня. Пары одного времени (подгруппы) и накладывающиеся считаются одним блоком,
     * так что окно — это промежуток, когда ни одной пары нет.
     */
    fun windows(day: List<Lesson>, min: Long = MIN_WINDOW): List<Window> {
        val slots = day.mapNotNull { l -> time(l.beginTime)?.let { b -> time(l.endTime)?.let { e -> b to e } } }
            .sortedBy { it.first }
        val out = ArrayList<Window>()
        var end: LocalTime? = null
        for ((b, e) in slots) {
            val prev = end
            if (prev != null && b.isAfter(prev)) {
                val gap = java.time.Duration.between(prev, b).toMinutes()
                if (gap >= min) out += Window(prev.toString(), b.toString(), gap)
            }
            if (prev == null || e.isAfter(prev)) end = e
        }
        return out
    }

    /** «1 ч 40 мин», «45 мин», «2 ч». */
    fun duration(min: Long): String = when {
        min < 60 -> "$min мин"
        min % 60 == 0L -> "${min / 60} ч"
        else -> "${min / 60} ч ${min % 60} мин"
    }

    /** Что идёт сейчас и что будет следующим (не дальше конца [horizonDays] дней). */
    data class Now(val current: Lesson?, val next: Lesson?)

    fun now(lessons: List<Lesson>, at: LocalDateTime, horizonDays: Long = 7): Now {
        fun start(l: Lesson) = runCatching { LocalDateTime.parse("${l.date}T${l.beginTime}") }.getOrNull()
        fun end(l: Lesson) = runCatching { LocalDateTime.parse("${l.date}T${l.endTime}") }.getOrNull()
        val sorted = lessons.distinctBy { it.key }.mapNotNull { l -> start(l)?.let { it to l } }.sortedBy { it.first }
        val current = sorted.firstOrNull { (s, l) -> !at.isBefore(s) && end(l)?.let { at.isBefore(it) } == true }?.second
        val limit = at.plusDays(horizonDays)
        val next = sorted.firstOrNull { (s, _) -> s.isAfter(at) && s.isBefore(limit) }?.second
        return Now(current, next)
    }

    /**
     * Какую неделю показывать по умолчанию: текущую, а если пары этой недели уже закончились
     * (вечер пятницы, суббота, воскресенье) и на следующей что-то есть — следующую.
     */
    fun defaultWeek(lessons: List<Lesson>, at: LocalDateTime): Long {
        val monday = weekStart(at.toLocalDate())
        val nextMonday = monday.plusWeeks(1)
        val left = lessons.any { l ->
            l.date >= monday.toString() && l.date < nextMonday.toString() &&
                runCatching { LocalDateTime.parse("${l.date}T${l.endTime}") }.getOrNull()?.isAfter(at) == true
        }
        if (left) return 0
        val nextHas = lessons.any { it.date >= nextMonday.toString() && it.date < nextMonday.plusWeeks(1).toString() }
        return if (nextHas) 1 else 0
    }

    /** Пары, которые ещё не закончились, по порядку (не дальше [horizonDays] дней) — для виджета. */
    fun upcoming(lessons: List<Lesson>, at: LocalDateTime, horizonDays: Long = 7): List<Lesson> {
        val limit = at.plusDays(horizonDays)
        return lessons.distinctBy { it.key }.mapNotNull { l ->
            val s = runCatching { LocalDateTime.parse("${l.date}T${l.beginTime}") }.getOrNull()
            val e = runCatching { LocalDateTime.parse("${l.date}T${l.endTime}") }.getOrNull()
            if (s == null || e == null || !e.isAfter(at) || !s.isBefore(limit)) null else s to l
        }.sortedWith(compareBy({ it.first }, { it.second.subject })).map { it.second }
    }

    /** Понедельник недели, в которую входит [d]. */
    fun weekStart(d: LocalDate): LocalDate = d.minusDays((d.dayOfWeek.value - 1).toLong())

    /** Номер пары по звонкам; если время нестандартное — ближайшая по началу. */
    fun pairNumber(begin: String): Int {
        Bells.find(begin)?.let { return it.number }
        val b = time(begin) ?: return 0
        return Bells.pairs.minByOrNull { kotlin.math.abs(java.time.Duration.between(it.start1, b).toMinutes()) }?.number ?: 0
    }
}
