package ru.susu.calendarsync

import android.view.View
import androidx.appcompat.app.AppCompatDelegate

/** Настройки внешнего вида: тема, карточки пар, анимации, пропуск. */
class AppearancePage : Page() {

    override fun build(): View {
        val ctx = requireContext()
        val prefs = Prefs(ctx)
        val d = prefs.display
        val root = ctx.vertical(16)

        root.addView(ctx.textButton("‹ Настройки") { parentFragmentManager.popBackStack() }.apply {
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        })
        root.addView(ctx.title("Внешний вид"))

        fun opt(label: String, value: Boolean, change: (DisplayOptions, Boolean) -> DisplayOptions) =
            root.addView(ctx.switch(label, value) { v ->
                prefs.display = change(prefs.display, v)
            })

        root.addView(ctx.section("Тема"))
        val themes = listOf("Как в системе", "Светлая", "Тёмная")
        root.addView(ctx.spinner(themes, d.theme) { i ->
            prefs.display = prefs.display.copy(theme = i)
            AppCompatDelegate.setDefaultNightMode(nightMode(i))
        })

        root.addView(ctx.section("Карточки пар"))
        opt("Аудитория крупно", d.bigRoom) { o, v -> o.copy(bigRoom = v) }
        opt("Лейбл типа занятия (ЛЕК, СЕМ)", d.typeTag) { o, v -> o.copy(typeTag = v) }
        opt("Цветной градиент по типу занятия", d.gradient) { o, v -> o.copy(gradient = v) }
        opt("Преподаватель", d.teacher) { o, v -> o.copy(teacher = v) }
        opt("Группы", d.groups) { o, v -> o.copy(groups = v) }
        opt("Номер пары", d.pairNumber) { o, v -> o.copy(pairNumber = v) }
        opt("Компактные карточки", d.compact) { o, v -> o.copy(compact = v) }
        opt("Короткие типы в календаре («Практики»)", d.shortTypes) { o, v -> o.copy(shortTypes = v) }

        root.addView(ctx.section("Текущая пара"))
        opt("Полоса прогресса", d.progress) { o, v -> o.copy(progress = v) }
        opt("Анимация полосы (свечение и волна)", d.glow) { o, v -> o.copy(glow = v) }
        opt("«Начнётся через …» у следующих пар", d.countdown) { o, v -> o.copy(countdown = v) }

        root.addView(ctx.section("Лента"))
        opt("Скрывать прошедшие дни", d.hidePast) { o, v -> o.copy(hidePast = v) }
        opt("Свободные окна между парами", d.windows) { o, v -> o.copy(windows = v) }

        root.addView(ctx.section("Пропуск"))
        root.addView(ctx.button("Показать пропуск", outlined = true) { main.openPass() }.apply {
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = ctx.dp(8) }
        })
        return ctx.scroll(root)
    }

    companion object {
        fun nightMode(theme: Int) = when (theme) {
            1 -> AppCompatDelegate.MODE_NIGHT_NO
            2 -> AppCompatDelegate.MODE_NIGHT_YES
            else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        }
    }
}
