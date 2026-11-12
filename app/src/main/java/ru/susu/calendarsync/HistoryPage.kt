package ru.susu.calendarsync

import android.graphics.Color
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.View
import androidx.core.text.inSpans
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** История изменений расписаний и оценок — что именно поменялось. */
class HistoryPage : Page() {
    private companion object {
        var filter = 0 // 0 — всё, 1 — расписания, 2 — оценки
    }

    override fun build(): View {
        val ctx = requireContext()
        val log = ChangeLog(ctx)
        val root = ctx.vertical(16)
        root.addView(ctx.textButton("‹ Назад") { parentFragmentManager.popBackStack() }.apply {
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        })
        root.addView(ctx.title("Изменения"))

        val all = log.load()
        // Открыли историю — всё просмотрено, счётчик на колокольчике обнуляется.
        all.maxOfOrNull { it.timestamp }?.let { Prefs(ctx).historySeen = maxOf(Prefs(ctx).historySeen, it) }
        val sets = all.filter {
            val grades = it.source.startsWith("Оценки")
            filter == 0 || (filter == 1 && !grades) || (filter == 2 && grades)
        }
        root.addView(ctx.spinner(listOf("Все изменения", "Только расписания", "Только оценки и КРМ"), filter) {
            filter = it
            render()
        })
        if (all.isNotEmpty()) {
            root.addView(ctx.textButton("Очистить историю") { log.clear(); render() })
        }
        if (sets.isEmpty()) {
            root.addView(ctx.secondary(
                "Пока ничего не менялось. " +
                    "Здесь появятся переносы, отмены, новые КРМ и оценки."
            ).apply { setPadding(0, ctx.dp(12), 0, 0) })
        }

        val fmt = SimpleDateFormat("d MMMM yyyy, HH:mm", Locale("ru"))
        for (set in sets) {
            root.addView(ctx.section(set.source))
            root.addView(ctx.secondary("Замечено ${fmt.format(Date(set.timestamp))}"))
            for (change in set.changes) root.addView(ctx.card(changeText(change)))
        }
        return ctx.scroll(root)
    }

    private fun changeText(change: Change): View {
        val ctx = requireContext()
        val color = when (change.kind) {
            Change.Kind.ADDED -> Color.rgb(46, 125, 50)
            Change.Kind.REMOVED -> Color.rgb(198, 40, 40)
            Change.Kind.MOVED -> Color.rgb(239, 108, 0)
            Change.Kind.MODIFIED -> Color.rgb(21, 101, 192)
            Change.Kind.KRM_NEW -> Color.rgb(142, 36, 170)
            Change.Kind.GRADE -> Color.rgb(0, 137, 123)
        }
        val text = SpannableStringBuilder()
            .inSpans(ForegroundColorSpan(color), StyleSpan(Typeface.BOLD)) { append(change.kind.label) }
            .append("  ")
            .inSpans(StyleSpan(Typeface.BOLD)) { append(change.title) }
            .append("\n")
            .append(change.text)
        return ctx.text(text, 14f)
    }
}
