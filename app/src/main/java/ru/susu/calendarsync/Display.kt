package ru.susu.calendarsync

import java.time.LocalTime

/** Расписание звонков ЮУрГУ: у каждой пары две половины по 45 минут. */
object Bells {
    data class Pair(val number: Int, val start1: LocalTime, val end1: LocalTime, val start2: LocalTime, val end2: LocalTime) {
        val text get() = "$start1–$end1, $start2–$end2"
    }

    private fun t(s: String) = LocalTime.parse(s)

    val pairs = listOf(
        Pair(1, t("08:00"), t("08:45"), t("08:50"), t("09:35")),
        Pair(2, t("09:45"), t("10:30"), t("10:35"), t("11:20")),
        Pair(3, t("11:30"), t("12:15"), t("12:20"), t("13:05")),
        Pair(4, t("13:35"), t("14:20"), t("14:25"), t("15:10")),
        Pair(5, t("15:20"), t("16:05"), t("16:10"), t("16:55")),
        Pair(6, t("17:05"), t("17:50"), t("17:55"), t("18:40")),
        Pair(7, t("18:45"), t("19:30"), t("19:35"), t("20:20")),
        Pair(8, t("20:25"), t("21:10"), t("21:15"), t("22:00")),
    )

    /** Пара по времени начала занятия (с допуском, если на портале время чуть отличается). */
    fun find(begin: String): Pair? {
        val b = runCatching { LocalTime.parse(begin) }.getOrNull() ?: return null
        return pairs.firstOrNull { it.start1 == b }
            ?: pairs.firstOrNull { !b.isBefore(it.start1.minusMinutes(10)) && b.isBefore(it.end2) }
    }
}

/** Цвет полосы и метки по типу занятия — общий для ленты, сетки недели и виджета. */
fun lessonTypeColor(type: String): Int {
    val t = type.lowercase()
    return when {
        t.startsWith("своя") -> android.graphics.Color.rgb(242, 183, 5)
        t.startsWith("лекц") -> android.graphics.Color.rgb(79, 142, 247)
        t.startsWith("практ") || t.startsWith("семин") -> android.graphics.Color.rgb(67, 175, 108)
        t.startsWith("лаб") -> android.graphics.Color.rgb(245, 146, 54)
        t.contains("экзам") || t.contains("зачет") || t.contains("зачёт") || t.contains("консульт") -> android.graphics.Color.rgb(229, 83, 83)
        else -> android.graphics.Color.rgb(156, 120, 230)
    }
}

/** Настройки отображения расписания. */
data class DisplayOptions(
    val pairNumber: Boolean,
    val shortTypes: Boolean,
    val bigRoom: Boolean,
    val teacher: Boolean,
    val groups: Boolean,
    val progress: Boolean,
    val countdown: Boolean,
    val hidePast: Boolean,
    val glow: Boolean = true,
    val typeTag: Boolean = true,
    val gradient: Boolean = true,
    val compact: Boolean = false,
    /** Карточки «Мини»: название в две строки, короткое ФИО. Отдельно от «Компактных» (те только уменьшают отступы). */
    val mini: Boolean = false,
    /** Прошедшие пары сегодняшнего дня свёрнуты в одну строку «2 пары прошли». */
    val collapsePast: Boolean = true,
    /** Плашка «Окно 1 ч 40 мин» между парами. */
    val windows: Boolean = true,
    /** 0 — как в системе, 1 — светлая, 2 — тёмная. */
    val theme: Int = 0,
) {
    fun typeName(type: String): String = if (!shortTypes) type else shortType(type)

    /** Тип одним словом для подписи под временем пары: «лекция», «практика», «лаба». */
    fun typeWord(type: String): String {
        val t = type.lowercase()
        return when {
            !shortTypes -> t
            t.startsWith("лекц") -> "лекция"
            t.startsWith("практ") || t.startsWith("семин") -> "практика"
            t.startsWith("лаборатор") -> "лаба"
            else -> t
        }
    }

    companion object {
        /** Короткий лейбл типа занятия для карточки: ЛЕК, СЕМ, ЛАБ, ЭКЗ, ЗАЧ… */
        fun typeTag(type: String): String {
            val t = type.lowercase().trim()
            return when {
                t.isEmpty() -> ""
                t.startsWith("своя") -> "МОЯ"
                t.startsWith("лекц") -> "ЛЕК"
                t.startsWith("практ") || t.startsWith("семин") -> "СЕМ"
                t.startsWith("лаб") -> "ЛАБ"
                t.contains("экзам") -> "ЭКЗ"
                t.contains("зачет") || t.contains("зачёт") -> "ЗАЧ"
                t.contains("консульт") -> "КОНС"
                else -> t.take(3).uppercase()
            }
        }

        fun shortType(type: String): String {
            val t = type.lowercase()
            return when {
                t.startsWith("практическ") -> "Практики"
                t.startsWith("лаборатор") -> "Лабораторные"
                else -> type
            }
        }
    }
}
