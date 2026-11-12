package ru.susu.calendarsync

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.slider.Slider
import kotlin.math.roundToInt

/**
 * Экран дисциплины. Обычный режим — реальные баллы портала и что нужно для оценки.
 * Режим «Что если» ([whatIf]) — отдельный экран с ползунками: будущие баллы задаются вручную,
 * прогноз пересчитывается сразу. Введённое хранится только в этом экране и сбрасывается при выходе.
 */
class SubjectPage : Page() {
    companion object {
        fun of(term: Int, key: String, whatIf: Boolean = false) = SubjectPage().apply {
            arguments = Bundle().apply { putInt("term", term); putString("key", key); putBoolean("whatIf", whatIf) }
        }
    }

    /** Баллы «что если» — поле экрана, а не общее хранилище: закрыли экран — значения пропали. */
    private val ov = HashMap<String, Double>()
    private val whatIf get() = requireArguments().getBoolean("whatIf")

    private lateinit var summaryNow: TextView
    private lateinit var summaryForecast: TextView
    private lateinit var summaryMark: TextView
    private lateinit var ring: RatingRing
    private lateinit var neededBox: LinearLayout

    override fun build(): View {
        val ctx = requireContext()
        val root = ctx.vertical(16)
        root.addView(ctx.textButton(if (whatIf) "‹ Дисциплина" else "‹ Оценки") { parentFragmentManager.popBackStack() }.apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        })
        val term = requireArguments().getInt("term")
        val key = requireArguments().getString("key").orEmpty()
        val s = GradesStore(ctx).load(term)?.subjects?.firstOrNull { it.key == key }
        if (s == null) {
            root.addView(ctx.secondary("Предмет не нашёлся, обновите оценки"))
            return ctx.scroll(root)
        }
        val credit = Forecast.isCredit(s)

        root.addView(ctx.secondary(
            if (whatIf) "Что если" else s.controlLabel.replaceFirstChar { it.uppercase() } + " · $term семестр",
        ))
        root.addView(ctx.text(s.name, 22f, bold = true).apply { setPadding(0, ctx.dp(4), 0, ctx.dp(4)) })
        if (s.instructors.isNotBlank()) root.addView(ctx.secondary(s.instructors))

        // Итог: рейтинг сейчас, прогноз и оценка.
        ring = RatingRing(ctx, ctx.dp(96)).apply {
            this.credit = credit
            layoutParams = LinearLayout.LayoutParams(ctx.dp(96), ctx.dp(96)).apply { setMargins(0, 0, ctx.dp(16), 0) }
        }
        summaryNow = ctx.secondary("", 14f)
        summaryForecast = ctx.text("", 20f, bold = true)
        summaryMark = ctx.secondary("", 13f)
        val summary = ctx.horizontal()
        summary.addView(ring)
        summary.gravity = android.view.Gravity.CENTER_VERTICAL
        summary.addView(ctx.vertical().apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            addView(summaryNow); addView(summaryForecast); addView(summaryMark)
            if (s.mark.isNotBlank()) addView(ctx.secondary("На портале ${Grades.markText(s.mark)}", 13f))
        })
        root.addView(ctx.card(summary))

        // Сколько нужно на оставшихся КРМ.
        neededBox = ctx.vertical()
        root.addView(ctx.card(ctx.text("Сколько нужно", 15f, bold = true), neededBox))

        val sliders = ArrayList<Pair<PointEvent, Slider>>()
        if (whatIf) {
            root.addView(ctx.secondary(
                "Двигайте ползунки, и прогноз сразу пересчитается. " +
                    "Расчёт примерный, при выходе всё сбросится.", 13f,
            ).apply { setPadding(0, ctx.dp(12), 0, 0) })
            root.addView(ctx.textButton("Сбросить к баллам портала") {
                ov.clear()
                sliders.forEach { (e, sl) -> sl.value = snap(e.point ?: 0.0, e) }
                update(s, ov, credit)
            }.apply {
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            })
        } else if (s.events.any { (it.maxPoint ?: 0.0) > 0 }) {
            root.addView(ctx.button("Что если…") { main.openWhatIf(term, s.key) }.apply {
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    .apply { topMargin = ctx.dp(8) }
            })
        }

        for ((cat, events) in s.events.groupBy { it.category }) {
            root.addView(ctx.section(cat))
            for (e in events) root.addView(eventCard(s, e, ov, credit, sliders))
        }
        if (s.events.isEmpty()) root.addView(ctx.secondary("Портал не прислал КРМ по этому предмету"))

        update(s, ov, credit)
        return ctx.scroll(root)
    }

    private fun step(e: PointEvent) = if ((e.maxPoint ?: 0.0) <= 20) 0.5 else 1.0

    private fun snap(v: Double, e: PointEvent): Float {
        val st = step(e)
        return ((v / st).roundToInt() * st).coerceIn(0.0, e.maxPoint ?: 0.0).toFloat()
    }

    private fun eventCard(
        s: SubjectGrades, e: PointEvent, ov: HashMap<String, Double>, credit: Boolean,
        sliders: MutableList<Pair<PointEvent, Slider>>,
    ): View {
        val ctx = requireContext()
        val max = e.maxPoint ?: 0.0
        val head = ctx.horizontal()
        head.addView(ctx.text(e.name, 15f, bold = true).weight())
        val value = ctx.text("", 15f, bold = true)
        head.addView(value)
        val views = mutableListOf<View>(head)
        val meta = buildList {
            // Балл уже справа в заголовке; «на портале» нужно только в «Что если», где справа значение ползунка.
            if (e.point == null) add("баллов пока нет")
            else if (whatIf) add("на портале ${Grades.fmt(e.point)} из ${Grades.fmt(max)}")
            e.weight.takeIf { it.isNotBlank() }?.let { add("вес $it") }
        }.joinToString(" · ")
        views += ctx.secondary(meta, 12f)

        fun show(v: Double) {
            val pct = if (max > 0) v / max * 100 else 0.0
            value.text = "${Grades.fmt(v)} / ${Grades.fmt(max)}"
            value.setTextColor(if (e.key in ov) ctx.color(com.google.android.material.R.attr.colorPrimary) else ctx.ratingColor(pct))
        }
        if (max > 0 && whatIf) {
            val slider = Slider(ctx).apply {
                valueFrom = 0f
                valueTo = max.toFloat()
                stepSize = step(e).toFloat()
                this.value = snap(ov[e.key] ?: e.point ?: 0.0, e)
                addOnChangeListener { _, v, fromUser ->
                    if (!fromUser) return@addOnChangeListener
                    val actual = e.point ?: 0.0
                    if (v.toDouble() == snap(actual, e).toDouble()) ov.remove(e.key) else ov[e.key] = v.toDouble()
                    show(v.toDouble())
                    update(s, ov, credit)
                }
            }
            sliders += e to slider
            views += slider
        }
        show(ov[e.key] ?: e.point ?: 0.0)
        return ctx.card(*views.toTypedArray())
    }

    /** Пересчёт итога и «что нужно» без перестройки экрана — ползунки не сбрасываются. */
    private fun update(s: SubjectGrades, ov: Map<String, Double>, credit: Boolean) {
        val ctx = requireContext()
        fun r1(v: Double) = Grades.fmt(Math.round(v * 100) / 100.0)
        val brs = Forecast.current(s, ov)
        val exam = Forecast.examPercent(s, ov)
        val p = Forecast.projected(s, ov)
        ring.set(p, animate = true)
        val mark = Forecast.markFor(p, credit).substringBefore(" ·")
        val noPoints = ov.isEmpty() && s.events.none { it.point != null }
        summaryNow.text = "Сейчас ${r1(s.bestRating ?: 0.0)}%"
        summaryNow.visibility = if (ov.isEmpty()) View.GONE else View.VISIBLE
        summaryForecast.text = when {
            noPoints -> "Баллов пока нет"
            credit -> mark.replaceFirstChar { it.uppercase() }
            ov.isEmpty() -> "Сейчас выходит $mark"
            else -> "Выйдет $mark"
        }
        summaryForecast.setTextColor(if (noPoints) ctx.color(com.google.android.material.R.attr.colorOnSurface) else ctx.ratingColor(p))
        summaryMark.text = if (exam != null) "Без экзамена ${r1(brs)}%, с экзаменом ${r1(p)}%" else ""
        summaryMark.visibility = if (exam != null) View.VISIBLE else View.GONE

        neededBox.removeAllViews()
        val rest = Forecast.remaining(s, ov)
        val targets = if (credit) listOf(60.0 to "Зачёт") else listOf(85.0 to "5", 75.0 to "4", 60.0 to "3")
        for ((t, name) in targets) {
            val need = Forecast.needed(s, ov, t)
            val needExam = Forecast.neededExam(s, ov, t)
            val byBrs = when {
                Forecast.reaches(brs, t) -> "уже набрано"
                need == null -> null
                need > 100 -> null
                else -> "по ${need.roundToInt().coerceAtLeast(1)}% за ${rest.size} КРМ"
            }
            val byExam = when {
                Forecast.reaches(brs, t) || needExam == null -> null
                needExam > 100 -> null
                else -> "${needExam.roundToInt().coerceAtLeast(1)}% на экзамене"
            }
            val text = listOfNotNull(byBrs, byExam).joinToString(" или ").ifEmpty { "уже не набрать" }
            val row = ctx.horizontal().apply { setPadding(0, ctx.dp(6), 0, 0); gravity = android.view.Gravity.CENTER_VERTICAL }
            row.addView(ctx.text(name, if (credit) 15f else 18f, bold = true).apply {
                setTextColor(ctx.ratingColor(t))
                layoutParams = LinearLayout.LayoutParams(ctx.dp(if (credit) 64 else 28), ViewGroup.LayoutParams.WRAP_CONTENT)
            })
            row.addView(ctx.text(text, 14f).weight())
            neededBox.addView(row)
        }
    }
}
