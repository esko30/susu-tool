package ru.susu.calendarsync

import android.app.Dialog
import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout

/**
 * «Что нового»: отдельный экран с иконками новых функций. После обновления показывается один раз сам,
 * в любое время — из настроек. Новую версию добавляйте в начало списка.
 */
object WhatsNew {
    private data class Feature(val icon: Int, val tint: Int, val title: String, val text: String)
    private data class Release(val version: String, val features: List<Feature>)

    private const val BLUE = 0xFF378ADD.toInt()
    private const val GREEN = 0xFF1D9E75.toInt()
    private const val TEAL = 0xFF00897B.toInt()
    private const val PURPLE = 0xFF8E24AA.toInt()
    private const val ORANGE = 0xFFE65100.toInt()
    private const val SLATE = 0xFF546E7A.toInt()
    private const val INDIGO = 0xFF3F51B5.toInt()
    private const val PINK = 0xFFD81B60.toInt()

    private val RELEASES = listOf(
        Release("2.5", listOf(
            Feature(R.drawable.ic_notif_final, PURPLE, "Красный диплом",
                "В «Оценках» видно, получится ли красный диплом и сколько четвёрок ещё можно. Считаются все семестры, и будущие тоже."),
            Feature(R.drawable.ic_radar, ORANGE, "Дежурный",
                "Ждёте оценку? Нажмите на радар в «Оценках», и приложение будет проверять портал каждые 5 минут, от 1 до 12 часов."),
            Feature(R.drawable.ic_notif_points, BLUE, "Понятные уведомления",
                "Предмет и одна короткая фраза, например «7 из 10 за тест». Итоговая оценка приходит со звуком, баллы и новые КРМ тихо."),
            Feature(R.drawable.ic_add, PINK, "Своя пара",
                "Пару перенесли устно? Добавьте её сами через ⋮ в ленте. Она жёлтая и видна только вам."),
            Feature(R.drawable.ic_widgets, INDIGO, "Новые виджеты",
                "Таймер до конца пары и строка с ближайшей парой."),
            Feature(R.drawable.ic_nav_grades, GREEN, "Баллы в вашу пользу",
                "Округление как на портале, 59,01 уже зачёт, 84,1 уже пятёрка. Сданный зачёт сразу зелёный."),
            Feature(R.drawable.ic_nav_planner, TEAL, "Планер помнит",
                "Недописанная запись сохраняется черновиком. У пар видно, лекция это или практика."),
            Feature(R.drawable.ic_today, SLATE, "Проще и спокойнее",
                "Тексты по-человечески, вкладка «Лента», чистый «Внешний вид» с кнопкой «Вернуть стандартные»."),
        )),
        // 2.3 и 2.4 вышли подряд, поэтому одним списком.
        Release("2.4", listOf(
            Feature(R.drawable.ic_today, BLUE, "Новый вход",
                "Приложение здоровается по имени и дарит цитату дня, а пары красиво выезжают одна за другой."),
            Feature(R.drawable.ic_eye_off, SLATE, "Скрытие пар",
                "Пару отменили устно или вас на ней не будет? Смахните её, и она свернётся в полоску. " +
                    "Нажмите на полоску, чтобы вернуть."),
            Feature(R.drawable.ic_nav_settings, PURPLE, "Профиль",
                "В настройках ваше имя, группа и аватарка. Нажмите на аватарку, чтобы поставить своё фото."),
            Feature(R.drawable.ic_nav_search, GREEN, "Свободная шапка",
                "Поиск переехал в список расписаний, звонки в меню ⋮. " +
                    "Кнопка «Вернуться к сегодня» появляется сама, когда листаете ленту."),
            Feature(R.drawable.ic_grid, INDIGO, "Карточки «Мини»",
                "Маленькие карточки, и весь день влезает на экран. Включаются во «Внешнем виде». " +
                    "Прошедшие пары сворачиваются в одну строку."),
            Feature(R.drawable.ic_bolt, TEAL, "Удобнее во всём",
                "Настройки разложены по разделам, появилась лёгкая вибрация и плавные переходы. " +
                    "Поиск находит фамилии с буквой «ё»."),
            Feature(R.drawable.ic_nav_pass, ORANGE, "Быстрые ярлыки",
                "Зажмите иконку приложения, и сразу откроются «Пропуск» или «Новая запись»."),
            Feature(R.drawable.ic_settings_font, BLUE, "Шрифт на выбор",
                "Во «Внешнем виде» можно выбрать шрифт и межстрочный интервал."),
            Feature(R.drawable.ic_bell, ORANGE, "Надёжные уведомления",
                "Проверка не слетает после перезагрузки и обновления. В настройках видно, когда она была."),
            Feature(R.drawable.ic_update, BLUE, "Обновления сразу",
                "Приложение само проверяет, нет ли новой версии."),
            Feature(R.drawable.ic_heart, PINK, "Поддержка проекта",
                "Иногда приложение спросит, нравится ли оно вам. Можно поддержать через СБП или просто нажать «Позже»."),
        )),
        Release("2.2", listOf(
            Feature(R.drawable.ic_widgets, INDIGO, "Виджет",
                "Ближайшие пары на главном экране. Растяните виджет, чтобы видеть больше."),
            Feature(R.drawable.ic_grid, BLUE, "Неделя и окна",
                "Расписание сеткой по дням, свободные окна между парами прямо в ленте."),
            Feature(R.drawable.ic_nav_planner, TEAL, "Планер",
                "Контрольные, коллоквиумы и задания к нужной паре, свои записи и несколько напоминаний."),
            Feature(R.drawable.ic_photo, PINK, "Фото к записям",
                "Сфотографируйте доску или задание и прикрепите к записи. Можно поделиться."),
            Feature(R.drawable.ic_nav_grades, PURPLE, "«Что если» по формуле портала",
                "Прогноз оценки по баллам или с экзаменом."),
            Feature(R.drawable.ic_nav_pass, ORANGE, "Пропуск на панели",
                "QR-код в одно касание с нижней панели."),
        )),
        Release("2.1", listOf(
            Feature(R.drawable.ic_launcher_foreground, 0xFF0054A6.toInt(), "Первый выпуск",
                "Расписание, оценки и КРМ, пропуск, календарь телефона, обновления из приложения."),
        )),
    )

    val current: String get() = BuildConfig.VERSION_NAME

    /** Показать сам после обновления — если в этой версии есть что рассказать и это ещё не видели. */
    fun showIfUpdated(context: Context): Boolean {
        val prefs = Prefs(context)
        // Пусто — обновились с 2.1, где этого раздела ещё не было (новая установка отмечается в приветствии).
        val seen = prefs.whatsNewSeen.ifEmpty { "2.1" }
        prefs.whatsNewSeen = current
        if (seen == current) return false
        val fresh = RELEASES.filter { newer(it.version, seen) }
        if (fresh.isNotEmpty()) show(context, fresh)
        return fresh.isNotEmpty()
    }

    fun showAll(context: Context) = show(context, RELEASES)

    /** Свежая установка: рассказывать «что нового» незачем, приветствие уже всё показало. */
    fun markSeen(context: Context) { Prefs(context).whatsNewSeen = current }

    private fun show(context: Context, releases: List<Release>) {
        val ctx = context
        val dialog = Dialog(ctx, Prefs(ctx).themeRes)
        val root = ctx.vertical().apply {
            setBackgroundColor(ctx.color(com.google.android.material.R.attr.colorSurface))
        }
        val list = ctx.vertical().apply { setPadding(ctx.dp(24), ctx.dp(40), ctx.dp(24), ctx.dp(16)) }
        val first = releases.first()
        list.addView(ctx.text("Что нового", 30f, bold = true))
        list.addView(ctx.secondary("SUSU Tool ${first.version}", 15f).apply { setPadding(0, ctx.dp(4), 0, ctx.dp(8)) })

        releases.forEachIndexed { i, r ->
            if (i > 0) list.addView(ctx.section("Версия ${r.version}").apply { setPadding(0, ctx.dp(24), 0, ctx.dp(4)) })
            r.features.forEach { list.addView(row(ctx, it)) }
        }
        root.addView(ctx.scroll(list).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        })
        root.addView(ctx.button("Понятно") { dialog.dismiss() }.apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { setMargins(ctx.dp(24), ctx.dp(8), ctx.dp(24), ctx.dp(28)) }
        })

        root.padForSystemBars()
        dialog.setContentView(root)
        root.alpha = 0f
        dialog.show()
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        root.animate().alpha(1f).setDuration(250).start()
    }

    /** Строка функции: цветная плитка с иконкой, заголовок и пояснение. */
    private fun row(ctx: Context, f: Feature) = ctx.horizontal().apply {
        gravity = Gravity.TOP
        setPadding(0, ctx.dp(14), 0, ctx.dp(4))
        val size = ctx.dp(48)
        addView(ImageView(ctx).apply {
            setImageResource(f.icon)
            if (f.icon != R.drawable.ic_launcher_foreground) {
                setColorFilter(android.graphics.Color.WHITE)
                setPadding(ctx.dp(12), ctx.dp(12), ctx.dp(12), ctx.dp(12))
            }
            background = GradientDrawable().apply { cornerRadius = ctx.dp(14).toFloat(); setColor(f.tint) }
            layoutParams = LinearLayout.LayoutParams(size, size).apply { marginEnd = ctx.dp(16) }
        })
        addView(ctx.vertical().apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            addView(ctx.text(f.title, 17f, bold = true))
            addView(ctx.secondary(f.text, 14f).apply { setPadding(0, ctx.dp(2), 0, 0); setLineSpacing(0f, 1.1f) })
        })
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
