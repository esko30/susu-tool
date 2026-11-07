package ru.susu.calendarsync

import android.content.Context
import com.google.android.material.bottomsheet.BottomSheetDialog

/**
 * «Что нового»: список изменений по версиям. После обновления показывается один раз сам,
 * в любое время — из настроек. Новую версию добавляйте в начало списка.
 */
object WhatsNew {
    private data class Release(val version: String, val items: List<String>)

    private val RELEASES = listOf(
        Release("2.2", listOf(
            "Виджет на главный экран с ближайшими парами и записями планера на сегодня.",
            "Вид «неделя»: расписание сеткой по парам и дням. Включается в меню ⋮ расписания.",
            "Свободные окна между парами прямо в ленте.",
            "Планер: контрольные, коллоквиумы и домашние задания к нужной паре.",
            "Свои записи в планере с любой датой и временем.",
            "Фото к записям планера: из галереи или с камеры, можно поделиться.",
            "Прогноз оценки по формуле портала: по БРС и с экзаменом при отказе от БРС.",
            "Поиск находит фамилии с буквой «ё».",
            "Несколько напоминаний к одной записи, за любое число минут, часов или дней.",
            "«Что если» на отдельном экране дисциплины: задайте будущие баллы за КРМ и посмотрите прогноз.",
            "Оценки разбиты по форме контроля и отсортированы по рейтингу.",
            "Поиск и изменения теперь в шапке расписания, пропуск на нижней панели.",
            "Нажатие на группу открывает её расписание.",
            "Приветствие и тур по приложению.",
        )),
        Release("2.1", listOf(
            "Первый выпуск: расписание, оценки и КРМ, пропуск, синхронизация с календарём телефона.",
            "Обновления через GitHub Releases прямо из приложения.",
        )),
    )

    val current: String get() = BuildConfig.VERSION_NAME

    /** Показать сам после обновления — если в этой версии есть что рассказать и это ещё не видели. */
    fun showIfUpdated(context: Context) {
        val prefs = Prefs(context)
        // Пусто — обновились с 2.1, где этого раздела ещё не было (новая установка отмечается в приветствии).
        val seen = prefs.whatsNewSeen.ifEmpty { "2.1" }
        prefs.whatsNewSeen = current
        if (seen == current) return
        val fresh = RELEASES.filter { newer(it.version, seen) }
        if (fresh.isNotEmpty()) show(context, fresh)
    }

    fun showAll(context: Context) = show(context, RELEASES)

    /** Свежая установка: рассказывать «что нового» незачем, приветствие уже всё показало. */
    fun markSeen(context: Context) { Prefs(context).whatsNewSeen = current }

    private fun show(context: Context, releases: List<Release>) {
        val ctx = context
        val sheet = BottomSheetDialog(ctx)
        val box = ctx.vertical(20)
        box.addView(ctx.text("Что нового", 22f, bold = true))
        for (r in releases) {
            box.addView(ctx.section("Версия ${r.version}" + if (r.version == current) " · установлена" else ""))
            for (item in r.items) {
                box.addView(ctx.horizontal().apply {
                    gravity = android.view.Gravity.TOP
                    setPadding(0, ctx.dp(4), 0, ctx.dp(4))
                    addView(ctx.text("•", 15f, bold = true, colorAttr = com.google.android.material.R.attr.colorPrimary).apply {
                        setPadding(0, 0, ctx.dp(10), 0)
                    })
                    addView(ctx.text(item, 15f).weight())
                })
            }
        }
        box.addView(ctx.button("Понятно") { sheet.dismiss() }.apply {
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = ctx.dp(16) }
        })
        sheet.setContentView(ctx.scroll(box))
        sheet.show()
    }

    /** a > b по номерам версий («2.10» > «2.9»). */
    internal fun newer(a: String, b: String): Boolean {
        val x = a.split(".").map { it.toIntOrNull() ?: 0 }
        val y = b.split(".").map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(x.size, y.size)) {
            val d = x.getOrElse(i) { 0 } - y.getOrElse(i) { 0 }
            if (d != 0) return d > 0
        }
        return false
    }
}
