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

        root.addView(ctx.section("Шрифт"))
        root.addView(ctx.spinner(listOf("Noto Sans", "Как в телефоне"), if (prefs.systemFont) 1 else 0) { i ->
            prefs.systemFont = i == 1
            // Шрифт задаётся темой: пересоздаём экран, позиция в настройках сохраняется.
            requireActivity().recreate()
        })
        root.addView(ctx.text("Межстрочный интервал", 14f).apply { setPadding(0, ctx.dp(10), 0, 0) })
        root.addView(ctx.spinner(listOf("Обычный", "Компактный"), if (prefs.tightLines) 1 else 0) { i ->
            prefs.tightLines = i == 1
            requireActivity().recreate()
        })

        root.addView(ctx.section("Карточки пар"))
        root.addView(ctx.text("Размер карточек", 14f))
        val size = when { d.mini -> 2; d.compact -> 1; else -> 0 }
        root.addView(ctx.spinner(listOf("Нормальный", "Компактный", "Мини"), size) { i ->
            prefs.display = prefs.display.copy(compact = i >= 1, mini = i == 2)
        })
        opt("Аудитория крупно", d.bigRoom) { o, v -> o.copy(bigRoom = v) }
        opt("Тип пары значком, например ЛЕК", d.typeTag) { o, v -> o.copy(typeTag = v) }
        opt("Цвет карточки по типу пары", d.gradient) { o, v -> o.copy(gradient = v) }
        opt("Преподаватель", d.teacher) { o, v -> o.copy(teacher = v) }
        opt("Группы", d.groups) { o, v -> o.copy(groups = v) }
        opt("Номер пары", d.pairNumber) { o, v -> o.copy(pairNumber = v) }

        root.addView(ctx.section("Лента"))
        opt("Полоса прогресса у текущей пары", d.progress) { o, v -> o.copy(progress = v, glow = v) }
        opt("Сколько осталось до следующих пар", d.countdown) { o, v -> o.copy(countdown = v) }
        opt("Свободные окна между парами", d.windows) { o, v -> o.copy(windows = v) }
        opt("Сворачивать прошедшие пары", d.collapsePast) { o, v -> o.copy(collapsePast = v) }
        opt("Скрывать прошедшие дни", d.hidePast) { o, v -> o.copy(hidePast = v) }
        root.addView(ctx.button("Вернуть стандартные", outlined = true) {
            com.google.android.material.dialog.MaterialAlertDialogBuilder(ctx)
                .setMessage("Тема, шрифт, карточки и все переключатели станут как после установки.")
                .setPositiveButton("Вернуть") { _, _ ->
                    prefs.display = DisplayOptions(
                        pairNumber = false, shortTypes = prefs.display.shortTypes, bigRoom = true, teacher = true,
                        groups = true, progress = true, countdown = true, hidePast = false,
                    )
                    prefs.systemFont = false
                    prefs.tightLines = false
                    AppCompatDelegate.setDefaultNightMode(nightMode(0))
                    requireActivity().recreate()
                }
                .setNegativeButton("Отмена", null)
                .show()
        }.apply {
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = ctx.dp(20) }
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
