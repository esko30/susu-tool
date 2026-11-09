package ru.susu.calendarsync

import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import com.google.android.material.checkbox.MaterialCheckBox
import java.time.LocalDate

/** Вкладка «Планер»: записи к парам — предстоящие по дням, пропущенные и выполненные. */
class PlannerPage : Page() {
    private companion object {
        var showDone = false
    }

    override fun build(): View {
        val ctx = requireContext()
        val root = ctx.vertical(16)
        root.addView(ctx.title("Планер"))
        root.addView(ctx.button("＋ Добавить запись") { PlannerSheet.showCustom(requireActivity(), null) { render() } }.apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        })

        val today = Clock.today().toString()
        val items = Planner.all(ctx).sortedWith(compareBy({ it.date }, { it.begin }))
        // Занятия всех сохранённых расписаний — чтобы при правке можно было перенести запись на другую пару.
        val store = Store(ctx)
        val lessons = store.saved().flatMap { store.lessons(it.uid) }

        val upcoming = items.filter { !it.done && it.date >= today }
        val missed = items.filter { !it.done && it.date < today }
        val done = items.filter { it.done }

        if (items.isEmpty()) {
            root.addView(ctx.card(
                ctx.text("Записей пока нет", 16f, bold = true),
                ctx.secondary(
                    "Нажмите на пару в расписании, чтобы отметить контрольную, коллоквиум или домашнее задание " +
                        "и поставить напоминание. Можно добавить и свою запись с любой датой и временем.", 14f,
                ).apply { setPadding(0, ctx.dp(6), 0, 0) },
            ))
            return ctx.scroll(root)
        }

        if (upcoming.isEmpty()) root.addView(ctx.secondary("Предстоящих записей нет.").apply { setPadding(0, ctx.dp(8), 0, 0) })
        upcoming.groupBy { it.date }.forEach { (date, list) ->
            root.addView(ctx.section(ChangeDiff.day(date).replaceFirstChar { it.uppercase() } + if (date == today) " · сегодня" else ""))
            list.forEach { root.addView(itemCard(it, lessons)) }
        }
        if (missed.isNotEmpty()) {
            root.addView(ctx.section("Прошедшие · ${missed.size}"))
            missed.forEach { root.addView(itemCard(it, lessons)) }
        }
        if (done.isNotEmpty()) {
            root.addView(ctx.textButton(if (showDone) "▲ Скрыть выполненные" else "▼ Выполненные · ${done.size}") {
                showDone = !showDone
                render()
            }.apply {
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    .apply { topMargin = ctx.dp(16) }
            })
            if (showDone) {
                done.sortedByDescending { it.date }.forEach { root.addView(itemCard(it, lessons)) }
                root.addView(ctx.textButton("Удалить выполненные") {
                    done.forEach { Planner.delete(ctx, it.id) }
                    render()
                })
            }
        }
        return ctx.scroll(root)
    }

    private fun itemCard(item: PlannerItem, lessons: List<Lesson>): View {
        val ctx = requireContext()
        val row = ctx.horizontal().apply { gravity = android.view.Gravity.TOP }
        row.addView(MaterialCheckBox(ctx).apply {
            isChecked = item.done
            contentDescription = "Выполнено"
            setOnCheckedChangeListener { box, v ->
                box.haptic()
                Planner.save(ctx, item.copy(done = v))
                render()
            }
        })
        val col = ctx.vertical().apply { layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) }
        if (item.label.isNotBlank()) col.addView(ctx.chip(item.label, Planner.labelColor(item.label)))
        col.addView(ctx.text(item.subject, 15f, bold = true).apply { setPadding(0, ctx.dp(4), 0, 0) })
        val whenText = ChangeDiff.day(item.date) + if (item.begin.isNotBlank()) ", ${item.begin}" else ""
        col.addView(ctx.secondary(listOf(whenText, item.location, if (item.custom) "своя запись" else "")
            .filter { it.isNotBlank() }.joinToString(" · ")))
        if (item.note.isNotBlank()) col.addView(ctx.text(item.note, 14f).apply { setPadding(0, ctx.dp(4), 0, 0) })
        if (item.photos.isNotEmpty()) col.addView(PlannerPhotos.strip(ctx, item.photos) { name ->
            PlannerPhotos.view(ctx, name) {
                Planner.save(ctx, item.copy(photos = item.photos - name))
                PlannerPhotos.delete(ctx, listOf(name))
                render()
            }
        })
        if (item.reminders.isNotEmpty() && !item.done) col.addView(ctx.secondary(
            (if (item.reminders.size == 1) "Напоминание: " else "Напоминания: ") +
                item.reminders.joinToString(", ") { it.label(!item.custom).lowercase() }, 12f,
        ).apply {
            setPadding(0, ctx.dp(4), 0, 0)
        })
        row.addView(col)
        return ctx.card(row) {
            if (item.custom) PlannerSheet.showCustom(requireActivity(), item) { render() }
            else PlannerSheet.show(ctx, null, item, lessons) { render() }
        }.apply {
            if (item.done) alpha = 0.55f
        }
    }
}
