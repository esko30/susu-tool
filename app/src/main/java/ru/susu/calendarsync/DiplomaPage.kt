package ru.susu.calendarsync

import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout

/** «Красный диплом»: сколько пятёрок и четвёрок, сколько четвёрок ещё можно и что нужно в этой сессии. */
class DiplomaPage : Page() {
    private var loading = false
    private var status = ""

    override fun build(): View {
        val ctx = requireContext()
        val prefs = Prefs(ctx)
        val root = ctx.vertical(16)
        root.addView(ctx.textButton("‹ Оценки") { parentFragmentManager.popBackStack() }.apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        })
        root.addView(ctx.title("Красный диплом"))

        val recs = Diploma.load(ctx)
        if (recs == null) {
            root.addView(ctx.secondary(if (loading) "Загружаю ведомости за все семестры…" else
                "Посчитаю по оценкам из ведомостей за все семестры."))
            if (!loading) root.addView(ctx.button("Посчитать") { refresh() })
            if (status.isNotBlank()) root.addView(ctx.text(status, 13f, colorAttr = com.google.android.material.R.attr.colorError))
            return ctx.scroll(root)
        }
        val r = Diploma.analyze(recs)

        // Итог.
        val (title, color) = when {
            r.threes > 0 -> "Сейчас невозможен" to ctx.ratingColor(40.0)
            !r.possible -> "Слишком много четвёрок" to ctx.ratingColor(40.0)
            r.foursLeft == 0 && r.pending > 0 -> "Возможен, нужны только пятёрки" to ctx.ratingColor(70.0)
            else -> "Возможен" to ctx.ratingColor(100.0)
        }
        val head = ctx.vertical()
        head.addView(ctx.text(title, 22f, bold = true).apply { setTextColor(color) })
        head.addView(ctx.secondary(buildString {
            append("Пятёрок ${r.fives}, четвёрок ${r.fours}")
            if (r.threes > 0) append(", троек ${r.threes}")
            if (r.pending > 0) append(", ещё без оценки ${r.pending}")
        }, 14f).apply { setPadding(0, ctx.dp(4), 0, 0) })
        root.addView(ctx.card(head))

        // Как посчитано — по шагам, чтобы можно было проверить.
        val calc = ctx.vertical()
        calc.addView(ctx.text("Как посчитано", 15f, bold = true))
        val maxFours = r.foursLeft + r.fours
        val current = prefs.currentTerm
        val future = r.lines.count { it.mark == null && it.term > current }
        val lastTerm = r.lines.maxOfOrNull { it.term } ?: current
        calc.addView(ctx.secondary(buildString {
            append("• В диплом идут ${r.total} оценок за $lastTerm семестров. Это экзамены, диффзачёты, курсовые и практики")
            if (r.pending > 0) {
                append(", включая ${r.pending} ещё не сданных")
                if (future > 0) append(" (из них $future в следующих семестрах)")
            }
            append(". Зачёты и госэкзамены не считаются. Если предмет сдавали несколько раз, берётся последняя оценка.\n")
            append("• Четверть от ${r.total} это ${Grades.fmt(r.total * 0.25)}, значит четвёрок можно не больше $maxFours.\n")
            append("• Уже есть ${r.fours}")
            append(when {
                r.foursLeft > 0 -> ", значит можно ещё ${r.foursLeft}."
                r.foursLeft == 0 -> ", запаса больше нет."
                else -> ", это на ${-r.foursLeft} больше лимита."
            })
            if (future == 0 && lastTerm <= current)
                append("\n• План на следующие семестры портал не прислал. Когда он появится, оценок станет больше и лимит вырастет.")
        }, 13f).apply { setPadding(0, ctx.dp(6), 0, 0); setLineSpacing(0f, 1.15f) })
        root.addView(ctx.card(calc))

        // Текущая сессия: сколько четвёрок можно позволить среди ещё не сданных.
        val pendingNow = r.lines.filter { it.mark == null && it.term == current }
        if (pendingNow.isNotEmpty() && r.threes == 0) {
            root.addView(ctx.section("Эта сессия · ${pendingNow.size}"))
            val box = ctx.vertical()
            val n = pendingNow.size
            box.addView(ctx.text(when {
                r.foursLeft < 0 -> "Нужны только пятёрки, и четвёрки придётся пересдать."
                r.foursLeft == 0 -> "Все ${Remind.plural(n, "оценку", "оценки", "оценок")} нужно получить на 5."
                r.foursLeft >= n && future > 0 ->
                    "Даже если все $n сдать на 4, диплом останется возможным, но на следующие семестры останется " +
                        Remind.plural(r.foursLeft - n, "четвёрка", "четвёрки", "четвёрок") + "."
                r.foursLeft >= n -> "Даже если все $n сдать на 4, диплом останется возможным."
                else -> "Из $n можно сдать на 4 не больше ${r.foursLeft}, остальные на 5."
            }, 15f))
            box.addView(ctx.secondary(pendingNow.joinToString("\n") { "• ${it.name} · ${it.kind.title}" }, 13f)
                .apply { setPadding(0, ctx.dp(8), 0, 0); setLineSpacing(0f, 1.15f) })
            root.addView(ctx.card(box))
        }

        // Что ещё впереди по учебному плану.
        val ahead = r.lines.filter { it.mark == null && it.term > current }
        if (ahead.isNotEmpty()) {
            root.addView(ctx.section("Дальше по плану · ${ahead.size}"))
            val box = ctx.vertical()
            box.addView(ctx.secondary(ahead.sortedWith(compareBy({ it.term }, { it.name }))
                .joinToString("\n") { "• ${it.name} · ${it.kind.title} · ${it.term} семестр" }, 13f)
                .apply { setLineSpacing(0f, 1.15f) })
            root.addView(ctx.card(box))
        }

        // Итоговая аттестация — отдельное требование.
        if (r.gia.isNotEmpty()) {
            root.addView(ctx.section("Итоговая аттестация"))
            val box = ctx.vertical()
            box.addView(ctx.text("Нужна только 5, в четверть четвёрок это не входит.", 15f))
            box.addView(ctx.secondary(r.gia.joinToString("\n") { "• ${it.name} · ${it.term} семестр" + (it.mark?.let { m -> " · $m" } ?: "") }, 13f)
                .apply { setPadding(0, ctx.dp(8), 0, 0); setLineSpacing(0f, 1.15f) })
            root.addView(ctx.card(box))
        }

        // Все строки будущего диплома.
        root.addView(ctx.section("Оценки в диплом · ${r.lines.size}"))
        for (l in r.lines.filter { it.mark != null }.sortedWith(compareBy({ it.mark }, { it.name }))) {
            val row = ctx.horizontal().apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, ctx.dp(6), 0, ctx.dp(6)) }
            row.addView(ctx.vertical().apply {
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                addView(ctx.text(l.name, 14f))
                addView(ctx.secondary("${l.kind.title} · ${l.term} семестр", 12f))
            })
            row.addView(ctx.text("${l.mark}", 20f, bold = true).apply { setTextColor(ctx.ratingColor(l.mark!! * 20.0 - 1)) })
            root.addView(row)
        }
        root.addView(ctx.secondary("Расчёт примерный, точный ответ даст деканат.", 12f)
            .apply { setPadding(0, ctx.dp(16), 0, 0) })
        root.addView(ctx.textButton(if (loading) "Обновляю…" else "Обновить из ведомостей") { if (!loading) refresh() })
        if (status.isNotBlank()) root.addView(ctx.text(status, 13f, colorAttr = com.google.android.material.R.attr.colorError))
        return ctx.scroll(root)
    }

    private fun refresh() {
        val app = requireContext().applicationContext
        loading = true
        status = ""
        render()
        background({
            Session.call(app) { t ->
                val (current, count) = Grades.studyPlan(t)
                Diploma.fetch(app, t, current, count)
            }
        }) { res ->
            loading = false
            res.onSuccess { if (it.isEmpty()) status = "Портал не прислал ведомости" }
                .onFailure { status = errorText(it) }
            render()
        }
    }
}
