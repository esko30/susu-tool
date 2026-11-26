package ru.susu.calendarsync

import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Расписание звонков: текущая пара подсвечена. */
object BellsDialog {
    fun show(ctx: android.content.Context) {
        val box = ctx.vertical(20)
        val now = Clock.now().toLocalTime()
        for (p in Bells.pairs) {
            val row = ctx.horizontal().apply { setPadding(0, ctx.dp(6), 0, ctx.dp(6)) }
            val current = !now.isBefore(p.start1) && now.isBefore(p.end2)
            row.addView(ctx.text("${p.number}", 18f, bold = true).apply {
                width = ctx.dp(36)
                if (current) setTextColor(ctx.color(com.google.android.material.R.attr.colorPrimary))
            })
            row.addView(ctx.text(p.text, 16f, bold = current).apply {
                if (current) setTextColor(ctx.color(com.google.android.material.R.attr.colorPrimary))
            })
            box.addView(row)
        }
        MaterialAlertDialogBuilder(ctx).setTitle("Расписание звонков").setView(box).setPositiveButton("OK", null).show()
    }
}

/** Предпросмотр чужого расписания (группа, аудитория, преподаватель) с кнопками «Открыть» и «Сохранить». */
object QuickScheduleSheet {
    /**
     * Нижняя панель с ближайшим расписанием группы, аудитории или преподавателя.
     * Если [known] не передан, расписание ищется по названию через поиск портала.
     */
    fun show(
        main: MainActivity, scope: androidx.lifecycle.LifecycleCoroutineScope, display: DisplayOptions,
        kind: ScheduleKind, query: String, known: ScheduleInfo?,
        onOpenSaved: (uid: String) -> Unit, onSaved: () -> Unit,
    ) {
        val ctx: android.content.Context = main
        val app = ctx.applicationContext
        val sheet = com.google.android.material.bottomsheet.BottomSheetDialog(ctx)
        val box = ctx.vertical(20)
        box.addView(ctx.secondary(kind.label))
        box.addView(ctx.text(query, 22f, bold = true))
        val body = ctx.vertical().apply { setPadding(0, ctx.dp(12), 0, 0) }
        body.addView(ctx.secondary("Загрузка…"))
        box.addView(body)
        sheet.setContentView(ctx.scroll(box))
        sheet.show()

        scope.launch {
          val r = withContext(Dispatchers.IO) { runCatching {
            Session.call(app) { token ->
                val found = if (known != null) listOf(known) else SusuApi.search(token, query).filter { it.kind == kind }
                val info = found.firstOrNull { it.name.equals(query, ignoreCase = true) }
                    ?: found.firstOrNull { it.name.startsWith(query, ignoreCase = true) }
                    ?: found.firstOrNull()
                    ?: throw java.io.IOException("Расписание «$query» не найдено")
                info to SusuApi.schedule(token, info.kind, info.id)
            }
          } }
            body.removeAllViews()
            r.onFailure { body.addView(ctx.secondary(errorText(it))) }
            r.onSuccess { (info, lessons) ->
                if (info.description.isNotBlank()) body.addView(ctx.secondary(info.description))
                val saved = Store(app).find("${info.kind.name}_${info.id}") != null
                val actions = ctx.horizontal().apply { setPadding(0, ctx.dp(12), 0, ctx.dp(4)) }
                actions.addView(ctx.button("Открыть") {
                    sheet.dismiss()
                    if (saved) onOpenSaved("${info.kind.name}_${info.id}")
                    else main.openTemp(SavedSchedule.from(info), lessons)
                }.weight())
                actions.addView(View(ctx).apply { layoutParams = LinearLayout.LayoutParams(ctx.dp(8), 1) })
                actions.addView(ctx.button(if (saved) "✓ Сохранено" else "★ Сохранить", outlined = true) {
                    Store(app).upsert(SavedSchedule.from(info))
                    Store(app).saveLessons("${info.kind.name}_${info.id}", lessons)
                    sheet.dismiss()
                    onSaved()
                }.apply { isEnabled = !saved }.weight())
                body.addView(actions)
                val today = Clock.today()
                val upcoming = lessons.distinctBy { it.key }
                    .filter { it.date >= today.toString() && it.date <= today.plusDays(13).toString() }
                    .sortedWith(compareBy({ it.date }, { it.beginTime })).groupBy { it.date }
                if (upcoming.isEmpty()) body.addView(ctx.secondary("В ближайшие две недели пар нет").apply { setPadding(0, ctx.dp(12), 0, 0) })
                for ((date, items) in upcoming) {
                    val d = LocalDate.parse(date)
                    body.addView(ctx.text(
                        d.format(DateTimeFormatter.ofPattern("EEEE, d MMMM", Locale("ru"))).replaceFirstChar { it.uppercase() } +
                            if (d == today) " · сегодня" else "", 15f, bold = true,
                    ).apply { setPadding(0, ctx.dp(14), 0, ctx.dp(4)) })
                    for (l in items) {
                        val line = ctx.horizontal().apply { setPadding(0, ctx.dp(4), 0, ctx.dp(4)); gravity = android.view.Gravity.TOP }
                        line.addView(ctx.text(l.beginTime, 14f, bold = true).apply { width = ctx.dp(52) })
                        val col = ctx.vertical().apply { layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) }
                        col.addView(ctx.text(l.subject, 14f))
                        // Для аудитории — преподаватель и группы, для преподавателя — группы, для группы — преподаватель.
                        val wanted = when (kind) {
                            ScheduleKind.ROOM -> listOf("Преподавател", "Группы")
                            ScheduleKind.TEACHER -> listOf("Группы")
                            ScheduleKind.GROUP -> listOf("Преподавател")
                        }
                        val meta = listOf(display.typeWord(l.eventType), if (kind == ScheduleKind.ROOM) "" else l.location) +
                            l.details.lines().filter { line -> wanted.any { line.startsWith(it) } }.map(::detailValue)
                        col.addView(ctx.secondary(meta.filter { it.isNotBlank() }.joinToString(" · "), 12f))
                        line.addView(col)
                        body.addView(line)
                    }
                }
            }
        }
    }
}
