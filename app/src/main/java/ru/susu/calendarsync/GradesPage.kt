package ru.susu.calendarsync

import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Успеваемость: рейтинг и оценки по дисциплинам семестра, баллы за КРМ. */
class GradesPage : Page() {
    private companion object {
        var term = 0 // 0 — текущий
    }

    private var status = ""
    private var loading = false
    private var autoLoaded = false

    override fun build(): View {
        val ctx = requireContext()
        val prefs = Prefs(ctx)
        val root = ctx.vertical(16)
        val header = ctx.horizontal().apply { gravity = android.view.Gravity.CENTER_VERTICAL }
        header.addView(ctx.title("Успеваемость").weight())
        if (prefs.hasCredentials) header.addView(DutySheet.button(ctx) { DutySheet.show(ctx) { render() } })
        root.addView(header)

        if (!prefs.hasCredentials) {
            root.addView(ctx.secondary("Войдите в «Настройках», и здесь появятся оценки."))
            return ctx.scroll(root)
        }
        if (Store(ctx).own()?.kind == ScheduleKind.TEACHER) {
            root.addView(ctx.secondary("Оценки есть только у студентов."))
            return ctx.scroll(root)
        }

        val current = prefs.currentTerm
        val shownTerm = if (term > 0) term else current
        val snapshot = if (shownTerm > 0) GradesStore(ctx).load(shownTerm) else null

        val count = maxOf(snapshot?.termCount ?: 0, current, shownTerm)
        if (count > 0) {
            val terms = (1..count).toList()
            root.addView(ctx.spinner(terms.map { "$it семестр" + if (it == current) " (текущий)" else "" },
                terms.indexOf(shownTerm)) { i ->
                term = terms[i]
                status = ""
                render()
                if (GradesStore(ctx).load(term) == null) refresh()
            })
        }
        root.addView(ctx.button(if (loading) "Загрузка…" else "Обновить") { refresh() }.apply { isEnabled = !loading })

        snapshot?.updated?.takeIf { it > 0 }?.let {
            root.addView(ctx.secondary("Обновлено " + SimpleDateFormat("d MMMM, HH:mm", Locale("ru")).format(Date(it))))
        }
        if (status.isNotBlank()) root.addView(ctx.secondary(status).apply { setPadding(0, ctx.dp(6), 0, 0) })

        // Красный диплом: короткий итог, подробности на отдельном экране.
        val dip = Diploma.load(ctx)?.let { Diploma.analyze(it) }
        root.addView(ctx.card(
            ctx.text("Красный диплом  ›", 16f, bold = true),
            ctx.secondary(when {
                dip == null -> "Посчитать, получится ли"
                dip.threes > 0 -> "Сейчас не получится, есть тройка"
                dip.foursLeft < 0 -> "Четвёрок больше 25%"
                dip.foursLeft == 0 -> "Возможен, дальше только пятёрки"
                else -> "Возможен, можно ещё " + Remind.plural(dip.foursLeft, "четвёрку", "четвёрки", "четвёрок")
            }),
        ) { main.openDiploma() }.apply {
            (layoutParams as? LinearLayout.LayoutParams)?.topMargin = ctx.dp(10)
        })

        if (snapshot == null) {
            if (!autoLoaded && !loading) {
                autoLoaded = true
                root.post { refresh() }
            }
            return ctx.scroll(root)
        }

        if (snapshot.subjects.isEmpty()) root.addView(ctx.secondary("В этом семестре предметов нет"))

        // Разделы по форме контроля; внутри — по убыванию рейтинга, без баллов — в конце.
        snapshot.subjects.groupBy { category(it) }.toSortedMap(compareBy { it.ordinal }).forEach { (cat, list) ->
            root.addView(ctx.section("${cat.title} · ${list.size}"))
            list.sortedByDescending { it.bestRating ?: -1.0 }.forEach { root.addView(subjectCard(it, snapshot.term)) }
        }
        return ctx.scroll(root)
    }

    private enum class Category(val title: String) {
        EXAM("Экзамены"), DIFF("Дифф. зачёты"), CREDIT("Зачёты"), COURSE_WORK("Курсовые работы"),
        COURSE_PROJECT("Курсовые проекты"), PRACTICE("Практики"), OTHER("Прочее"),
    }

    private fun category(s: SubjectGrades): Category {
        val t = s.controlLabel.lowercase().replace('ё', 'е')
        return when {
            "экзам" in t -> Category.EXAM
            "диф" in t -> Category.DIFF
            "зачет" in t -> Category.CREDIT
            "курсовая" in t || "курсовой работ" in t -> Category.COURSE_WORK
            "курсовой" in t || "проект" in t -> Category.COURSE_PROJECT
            "практик" in t -> Category.PRACTICE
            else -> Category.OTHER
        }
    }

    private fun ring(r: Double?, credit: Boolean = false) = RatingRing(requireContext(), requireContext().dp(60)).apply {
        layoutParams = LinearLayout.LayoutParams(context.dp(60), context.dp(60)).apply { setMargins(context.dp(12), 0, 0, 0) }
        this.credit = credit
        set(r)
    }

    /** Карточка дисциплины: рейтинг, оценка, сколько КРМ с баллами; нажатие — экран дисциплины с «Что если». */
    private fun subjectCard(s: SubjectGrades, termNo: Int): View {
        val ctx = requireContext()
        val info = ctx.vertical().apply { layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) }
        info.addView(ctx.text(s.name, 16f, bold = true).apply { setPadding(0, ctx.dp(2), 0, ctx.dp(4)) })
        if (s.mark.isNotBlank()) {
            info.addView(ctx.chip("Оценка ${Grades.markText(s.mark)}", ctx.ratingColor(s.mark.toDoubleOrNull()?.let { it * 20 })))
        }
        if (s.instructors.isNotBlank()) info.addView(ctx.secondary(s.instructors).apply { setPadding(0, ctx.dp(4), 0, 0) })
        val scored = s.events.count { (it.point ?: 0.0) > 0 }
        if (s.events.isNotEmpty()) info.addView(ctx.secondary("Баллы стоят за $scored КРМ из ${s.events.size}", 13f).apply {
            setPadding(0, ctx.dp(4), 0, 0)
        })
        val head = ctx.horizontal()
        head.addView(info)
        head.addView(ring(if (s.events.isNotEmpty()) Forecast.projected(s, emptyMap()) else s.bestRating, Forecast.isCredit(s)))
        return ctx.card(head) { main.openSubject(termNo, s.key) }
    }

    private fun refresh() {
        val ctx = requireContext().applicationContext
        loading = true
        status = ""
        render()
        val t = term.takeIf { it > 0 }
        background({ Syncer.syncGrades(ctx, t) }) { r ->
            loading = false
            r.onSuccess { status = "Предметов ${it.subjects.size}" }.onFailure { status = errorText(it) }
            render()
        }
    }
}
