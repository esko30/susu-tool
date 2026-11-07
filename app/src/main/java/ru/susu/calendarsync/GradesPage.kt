package ru.susu.calendarsync

import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import com.google.android.material.progressindicator.CircularProgressIndicator
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Успеваемость: рейтинг и оценки по дисциплинам семестра, баллы за КРМ. */
class GradesPage : Page() {
    private companion object {
        var term = 0 // 0 — текущий
        val expanded = HashSet<String>()
    }

    private var status = ""
    private var loading = false
    private var autoLoaded = false

    override fun build(): View {
        val ctx = requireContext()
        val prefs = Prefs(ctx)
        val root = ctx.vertical(16)
        root.addView(ctx.title("Успеваемость"))

        if (!prefs.hasCredentials) {
            root.addView(ctx.secondary("Войдите в аккаунт на вкладке «Настройки»."))
            return ctx.scroll(root)
        }
        if (Store(ctx).own()?.kind == ScheduleKind.TEACHER) {
            root.addView(ctx.secondary("Раздел оценок доступен только для студентов."))
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
        root.addView(ctx.switch("Следить за оценками и новыми КРМ", prefs.monitorGrades) { prefs.monitorGrades = it })

        snapshot?.updated?.takeIf { it > 0 }?.let {
            root.addView(ctx.secondary("Обновлено " + SimpleDateFormat("d MMMM, HH:mm", Locale("ru")).format(Date(it))))
        }
        if (status.isNotBlank()) root.addView(ctx.secondary(status).apply { setPadding(0, ctx.dp(6), 0, 0) })

        if (snapshot == null) {
            if (!autoLoaded && !loading) {
                autoLoaded = true
                root.post { refresh() }
            }
            return ctx.scroll(root)
        }

        val rated = snapshot.subjects.mapNotNull { it.bestRating }
        if (rated.isNotEmpty()) {
            val avg = rated.average()
            val summary = ctx.horizontal()
            summary.addView(ctx.vertical().apply {
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                addView(ctx.secondary("Средний рейтинг за семестр"))
                addView(ctx.text("${Grades.fmt(Math.round(avg * 10) / 10.0)}%", 26f, bold = true))
                addView(ctx.secondary("Дисциплин: ${snapshot.subjects.size}"))
            })
            summary.addView(ratingRing(avg))
            root.addView(ctx.card(summary))
            root.addView(ctx.section("Дисциплины"))
        }
        if (snapshot.subjects.isEmpty()) root.addView(ctx.secondary("Дисциплин в этом семестре нет."))

        for (s in snapshot.subjects) root.addView(subjectCard(s))
        return ctx.scroll(root)
    }

    private fun ratingColor(r: Double?) = when {
        // Нет баллов (ещё не выставлены) — серым, а не красным.
        r == null || r == 0.0 -> requireContext().color(com.google.android.material.R.attr.colorOnSurfaceVariant)
        r >= 85 -> Color.rgb(76, 175, 80)
        r >= 75 -> Color.rgb(192, 202, 51)
        r >= 60 -> Color.rgb(255, 152, 0)
        else -> Color.rgb(239, 83, 80)
    }

    /** Кольцо рейтинга с числом внутри. */
    private fun ratingRing(r: Double?): View {
        val ctx = requireContext()
        val size = ctx.dp(60)
        return android.widget.FrameLayout(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(size, size).apply { setMargins(ctx.dp(12), 0, 0, 0) }
            addView(CircularProgressIndicator(ctx).apply {
                indicatorSize = size
                indicatorInset = 0
                layoutParams = android.widget.FrameLayout.LayoutParams(size, size)
                trackThickness = ctx.dp(5)
                trackCornerRadius = ctx.dp(3)
                max = 100
                progress = (r ?: 0.0).coerceIn(0.0, 100.0).toInt()
                setIndicatorColor(ratingColor(r))
                trackColor = ctx.color(com.google.android.material.R.attr.colorSurfaceContainerHighest)
            })
            addView(ctx.text(if (r == null) "—" else Grades.fmt(Math.round(r * 10) / 10.0), 15f, bold = true).apply {
                setTextColor(ratingColor(r))
                gravity = android.view.Gravity.CENTER
                layoutParams = android.widget.FrameLayout.LayoutParams(size, size)
            })
        }
    }

    private fun subjectCard(s: SubjectGrades): View {
        val ctx = requireContext()
        val views = ArrayList<View>()
        val info = ctx.vertical().apply { layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) }
        info.addView(ctx.secondary(s.controlLabel.replaceFirstChar { it.uppercase() }, 12f))
        info.addView(ctx.text(s.name, 16f, bold = true).apply { setPadding(0, ctx.dp(2), 0, ctx.dp(4)) })
        if (s.mark.isNotBlank()) {
            info.addView(ctx.chip("Оценка: ${Grades.markText(s.mark)}", ratingColor(s.mark.toDoubleOrNull()?.let { it * 20 })))
        }
        if (s.instructors.isNotBlank()) info.addView(ctx.secondary(s.instructors).apply { setPadding(0, ctx.dp(4), 0, 0) })
        if (s.currentRating != null && s.currentRating != s.totalRating) {
            info.addView(ctx.secondary("Текущий рейтинг: ${Grades.fmt(s.currentRating)}%"))
        }
        val head = ctx.horizontal()
        head.addView(info)
        head.addView(ratingRing(s.bestRating))
        views += head

        val open = s.key in expanded
        if (s.events.isNotEmpty()) {
            views += ctx.secondary(if (open) "▲ Скрыть КРМ" else "▼ КРМ и баллы (${s.events.size})")
                .apply { setTextColor(ctx.color(com.google.android.material.R.attr.colorPrimary)); setPadding(0, ctx.dp(6), 0, 0) }
        }
        if (open) {
            for ((cat, events) in s.events.groupBy { it.category }) {
                views += ctx.text(cat, 13f, bold = true).apply { setPadding(0, ctx.dp(8), 0, ctx.dp(2)) }
                for (e in events) {
                    val row = ctx.horizontal()
                    row.addView(ctx.text(e.name, 13f).weight())
                    val pts = if (e.point == null) "—" else "${Grades.fmt(e.point)} / ${Grades.fmt(e.maxPoint)}"
                    val pct = e.rating?.let { "  ${Grades.fmt(it)}%" }.orEmpty()
                    row.addView(ctx.text(pts + pct, 13f, bold = true).apply { ratingColor(e.rating)?.let(::setTextColor) })
                    views += row
                    if (e.weight.isNotBlank()) views += ctx.secondary("вес ${e.weight}", 12f)
                }
            }
        }
        return ctx.card(*views.toTypedArray()) {
            if (s.events.isEmpty()) return@card
            if (!expanded.add(s.key)) expanded.remove(s.key)
            render()
        }
    }

    private fun refresh() {
        val ctx = requireContext().applicationContext
        loading = true
        status = ""
        render()
        val t = term.takeIf { it > 0 }
        background({ Syncer.syncGrades(ctx, t) }) { r ->
            loading = false
            r.onSuccess { status = "Дисциплин: ${it.subjects.size}" }.onFailure { status = errorText(it) }
            render()
        }
    }
}
