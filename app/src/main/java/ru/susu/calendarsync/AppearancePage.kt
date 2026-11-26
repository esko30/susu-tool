package ru.susu.calendarsync

import android.view.View
import androidx.appcompat.app.AppCompatDelegate

/** Настройки внешнего вида: тема и что показывать на карточке пары. */
class AppearancePage : Page() {

    override fun build(): View {
        val ctx = requireContext()
        val prefs = Prefs(ctx)
        val d = prefs.display
        val root = ctx.vertical(16)

        root.addView(backButton("Настройки"))
        root.addView(ctx.title("Внешний вид"))

        fun opt(label: String, value: Boolean, change: (DisplayOptions, Boolean) -> DisplayOptions) =
            root.addView(ctx.switch(label, value) { v -> prefs.display = change(prefs.display, v) })

        root.addView(ctx.section("Тема"))
        root.addView(ctx.spinner(listOf("Как в системе", "Светлая", "Тёмная"), d.theme) { i ->
            prefs.display = prefs.display.copy(theme = i)
            AppCompatDelegate.setDefaultNightMode(nightMode(i))
        })

        root.addView(ctx.switch("Новогоднее оформление", prefs.snow) { v ->
            prefs.snow = v
            SnowView.apply(requireActivity())
        })

        root.addView(ctx.section("Карточка пары"))
        opt("Тип пары значком, например ЛЕК", d.typeTag) { o, v -> o.copy(typeTag = v) }
        opt("Преподаватель", d.teacher) { o, v -> o.copy(teacher = v) }
        opt("Группы", d.groups) { o, v -> o.copy(groups = v) }
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
