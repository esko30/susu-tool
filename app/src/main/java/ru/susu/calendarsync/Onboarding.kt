package ru.susu.calendarsync

import android.app.Dialog
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2

/**
 * Приветствие при первом запуске: несколько страниц со свайпом, точки-индикатор,
 * в конце — вход в аккаунт или тур по приложению.
 */
object Onboarding {
    private data class PageData(val icon: Int, val tint: Int, val title: String, val text: String)

    fun show(activity: MainActivity) {
        val ctx = activity
        val prefs = Prefs(ctx)
        val primary = ctx.color(com.google.android.material.R.attr.colorPrimary)
        val pages = listOf(
            PageData(R.drawable.ic_launcher_foreground, 0xFF0054A6.toInt(), "SUSU Tool",
                "Расписание, оценки и пропуск ЮУрГУ в одном приложении."),
            PageData(R.drawable.ic_nav_schedule, 0xFF378ADD.toInt(), "Расписание",
                "Лента открывается на сегодня и показывает, сколько осталось до пары. " +
                    "Нажмите на аудиторию или преподавателя, чтобы открыть их расписание."),
            PageData(R.drawable.ic_nav_search, 0xFF1D9E75.toInt(), "Любые расписания",
                "Найдите группу, преподавателя или аудиторию и сохраните. " +
                    "Если пару перенесут или отменят, я сообщу."),
            PageData(R.drawable.ic_nav_planner, 0xFF00897B.toInt(), "Планер",
                "Нажмите на пару и отметьте контрольную или домашку. Я напомню заранее."),
            PageData(R.drawable.ic_nav_grades, 0xFF8E24AA.toInt(), "Оценки и «Что если»",
                "Баллы по каждому предмету и сколько нужно до пятёрки. В «Что если» можно прикинуть будущие баллы."),
            PageData(R.drawable.ic_today, 0xFFE65100.toInt(), "Пропуск",
                "QR-код всегда под рукой, даже без интернета. Яркость сама становится максимальной."),
            PageData(R.drawable.ic_nav_settings, 0xFF546E7A.toInt(), "Ваши данные",
                "Приложение общается только с порталом ЮУрГУ и никому ничего не передаёт. " +
                    "Логин и пароль зашифрованы на телефоне."),
        )

        val dialog = Dialog(ctx, Prefs(ctx).themeRes)
        val root = ctx.vertical().apply {
            setBackgroundColor(ctx.color(com.google.android.material.R.attr.colorSurface))
            setPadding(0, ctx.dp(24), 0, ctx.dp(24))
        }

        val pager = ViewPager2(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
                override fun getItemCount() = pages.size
                override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
                    object : RecyclerView.ViewHolder(FrameLayout(ctx).apply {
                        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                    }) {}

                override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
                    val p = pages[position]
                    val box = holder.itemView as FrameLayout
                    box.removeAllViews()
                    val col = ctx.vertical().apply {
                        gravity = Gravity.CENTER_HORIZONTAL
                        setPadding(ctx.dp(32), 0, ctx.dp(32), 0)
                    }
                    val size = ctx.dp(132)
                    col.addView(ImageView(ctx).apply {
                        setImageResource(p.icon)
                        if (p.icon != R.drawable.ic_launcher_foreground) {
                            setColorFilter(android.graphics.Color.WHITE)
                            setPadding(ctx.dp(36), ctx.dp(36), ctx.dp(36), ctx.dp(36))
                        }
                        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(p.tint) }
                        layoutParams = LinearLayout.LayoutParams(size, size).apply { bottomMargin = ctx.dp(36) }
                    })
                    col.addView(ctx.text(p.title, 28f, bold = true).apply { gravity = Gravity.CENTER })
                    col.addView(ctx.secondary(p.text, 16f).apply {
                        gravity = Gravity.CENTER
                        setLineSpacing(0f, 1.2f)
                        setPadding(0, ctx.dp(14), 0, 0)
                    })
                    box.addView(col, FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER,
                    ))
                }
            }
        }
        root.addView(pager)

        // Точки-индикатор страниц.
        val dots = ctx.horizontal().apply { gravity = Gravity.CENTER; setPadding(0, ctx.dp(8), 0, ctx.dp(20)) }
        val dotViews = pages.map {
            View(ctx).also { v ->
                v.layoutParams = LinearLayout.LayoutParams(ctx.dp(8), ctx.dp(8)).apply { setMargins(ctx.dp(4), 0, ctx.dp(4), 0) }
                v.background = GradientDrawable().apply { cornerRadius = ctx.dp(4).toFloat(); setColor(primary) }
                dots.addView(v)
            }
        }
        root.addView(dots)

        val buttons = ctx.horizontal().apply { setPadding(ctx.dp(20), 0, ctx.dp(20), 0) }
        val skip = ctx.textButton("Пропустить") { finish(activity, dialog, tour = false) }
        val next = ctx.button("Далее") {}
        buttons.addView(skip.weight())
        buttons.addView(View(ctx).apply { layoutParams = LinearLayout.LayoutParams(ctx.dp(12), 1) })
        buttons.addView(next.weight())
        root.addView(buttons)

        fun update(pos: Int) {
            dotViews.forEachIndexed { i, v ->
                v.animate().alpha(if (i == pos) 1f else 0.3f).scaleX(if (i == pos) 1.6f else 1f).setDuration(200).start()
            }
            val last = pos == pages.lastIndex
            val loggedIn = prefs.hasCredentials
            next.text = when {
                !last -> "Далее"
                loggedIn -> "Тур по приложению"
                else -> "Войти"
            }
            skip.text = if (last) (if (loggedIn) "Начать" else "Позже") else "Пропустить"
            next.setOnClickListener {
                if (!last) pager.currentItem = pos + 1
                else if (loggedIn) finish(activity, dialog, tour = true)
                else {
                    finish(activity, dialog, tour = false)
                    activity.showTab(Syncer.TAB_SETTINGS)
                }
            }
        }
        pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) = update(position)
        })
        update(0)

        root.padForSystemBars()
        dialog.setContentView(root)
        dialog.setCancelable(false)
        root.alpha = 0f
        dialog.show()
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        root.animate().alpha(1f).setDuration(300).start()
    }

    private fun finish(activity: MainActivity, dialog: Dialog, tour: Boolean) {
        Prefs(activity).onboardingDone = true
        WhatsNew.markSeen(activity)
        dialog.dismiss()
        if (tour) activity.startTour()
    }
}
