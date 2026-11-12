package ru.susu.calendarsync

/**
 * Что изменилось в оценках — для уведомлений, обычными фразами: предмет в заголовке и одна короткая
 * фраза под ним. Без тире, двоеточий, рейтингов и стрелок.
 */
sealed class GradeNews {
    abstract val subject: SubjectGrades

    /** Итоговая оценка за предмет (экзамен, зачёт, курсовая…). */
    data class Final(override val subject: SubjectGrades) : GradeNews()
    /** Поставили или подняли баллы за КРМ. */
    data class Points(override val subject: SubjectGrades, val item: String, val point: Double, val max: Double?) : GradeNews()
    /** Баллы за КРМ снизили. */
    data class Down(override val subject: SubjectGrades, val item: String, val from: Double, val to: Double) : GradeNews()
    /** Новое КРМ, баллов ещё нет. */
    data class NewKrm(override val subject: SubjectGrades, val item: String) : GradeNews()

    /** Фраза под названием предмета. */
    val text: String get() = when (this) {
        is Final -> finalText(subject)
        is Points -> Grades.fmt(point) + (max?.let { " из " + Grades.fmt(it) } ?: "") + " за «$item»"
        is Down -> "Балл за «$item» снизили с ${Grades.fmt(from)} до ${Grades.fmt(to)}"
        is NewKrm -> "Новое КРМ «$item»"
    }

    companion object {
        /** Сравнение двух снимков одного семестра. Изменение одного рейтинга новостью не считается. */
        fun of(old: GradesSnapshot, new: GradesSnapshot): List<GradeNews> {
            if (old.term != new.term) return emptyList()
            val oldMap = old.subjects.associateBy { it.key }
            val out = ArrayList<GradeNews>()
            for (s in new.subjects) {
                val o = oldMap[s.key] ?: continue
                val prevEvents = o.events.associateBy { it.key }
                for (e in s.events) {
                    val item = e.name.ifBlank { e.category }.trim()
                    val prev = prevEvents[e.key]
                    val p = e.point
                    when {
                        prev == null && p == null -> out += NewKrm(s, item)
                        prev == null && p != null -> out += Points(s, item, p, e.maxPoint)
                        p == null || prev!!.point == p -> Unit
                        prev.point == null || p > prev.point -> out += Points(s, item, p, e.maxPoint)
                        else -> out += Down(s, item, prev.point, p)
                    }
                }
                if (o.mark.trim() != s.mark.trim() && s.mark.isNotBlank()) out += Final(s)
            }
            return out
        }

        /** «Экзамен сдан на 5», «Зачёт сдан», «Курсовая сдана на 4», «Экзамен не сдан». */
        fun finalText(s: SubjectGrades): String {
            val (noun, fem) = when (Diploma.kindOf(s.controlLabel, s.name)) {
                Diploma.Kind.CREDIT -> "Зачёт" to false
                Diploma.Kind.DIFF -> "Диффзачёт" to false
                Diploma.Kind.COURSE -> "Курсовая" to true
                Diploma.Kind.PRACTICE -> "Практика" to true
                else -> "Экзамен" to false
            }
            val passed = if (fem) "сдана" else "сдан"
            val raw = s.mark.trim().lowercase().replace('ё', 'е')
            val mark = Diploma.parseMark(raw)
            return when {
                "не зач" in raw || "незач" in raw || (mark != null && mark <= 2) -> "$noun не $passed"
                "зач" in raw -> "$noun $passed"
                mark != null -> "$noun $passed на $mark"
                else -> "Итог ${s.mark.trim()}"
            }
        }

        /**
         * Короткое название для уведомления: без пояснения после двоеточия или в скобках, а длинное — до первого
         * предлога после двух слов. «Теории личности в клинической психологии» → «Теории личности».
         */
        fun shortName(name: String): String {
            val s = name.substringBefore(":").substringBefore("(").trim().ifEmpty { name.trim() }
            if (s.length <= 24) return s
            val words = s.split(Regex("""\s+"""))
            val cut = (2 until words.size).firstOrNull { words[it].lowercase() in PREPOSITIONS }
            return if (cut != null) words.take(cut).joinToString(" ") else s
        }

        /** Пример для кнопки в настройках: по одному уведомлению каждого вида. */
        fun sample(): List<GradeNews> {
            fun s(name: String, mark: String = "", control: String = "Экзамен") =
                SubjectGrades("sample-$name", name, 0, "", "", control, mark, null, "", "", null, null, emptyList())
            return listOf(
                Final(s("Психосоматика", "5")),
                Points(s("Теории личности в клинической психологии"), "Тест 3", 7.0, 10.0),
                Points(s("Психосоматика"), "Реферат", 5.0, 5.0),
                Down(s("Методы диагностики"), "Тест 2", 8.0, 5.0),
                NewKrm(s("Психология личности: норма и патология"), "Контрольная работа 2"),
            )
        }

        private val PREPOSITIONS = setOf("в", "во", "на", "для", "при", "по", "под", "и", "с", "со", "о", "об")
    }
}
