package ru.susu.calendarsync

import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.LinearLayout
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.slider.Slider

/**
 * «Дежурный»: на время ожидания оценки или переноса приложение проверяет портал каждые 5 минут.
 * Длительность от 1 до 12 часов, по умолчанию 4. Включить можно только когда телефон не ограничивает
 * работу в фоне, иначе проверки будут откладываться и смысла в Дежурном нет.
 */
object DutySheet {
    fun show(ctx: Context, onChanged: () -> Unit) {
        val sheet = BottomSheetDialog(ctx)
        val prefs = Prefs(ctx)
        val box = ctx.vertical(20)

        val head = ctx.horizontal().apply { gravity = Gravity.CENTER_VERTICAL }
        head.addView(badge(ctx, Duty.active(ctx)))
        val title = ctx.text("", 20f, bold = true)
        head.addView(title.weight())
        box.addView(head)

        box.addView(ctx.text("Пока Дежурный работает, приложение проверяет портал каждые 5 минут и сразу сообщает " +
            "о новых баллах, оценках и переносах. Пригодится, например, когда ждёте оценку.", 15f)
            .apply { setPadding(0, ctx.dp(12), 0, 0) })

        // Работа в фоне: без неё Дежурный не включается.
        val battery = ctx.vertical().apply {
            addView(ctx.text("Сначала разрешите работу в фоне", 15f, bold = true))
            addView(ctx.secondary("Сейчас телефон бережёт батарею и откладывает фоновые проверки, " +
                "поэтому Дежурный не сможет проверять каждые 5 минут.", 13f).apply { setPadding(0, ctx.dp(4), 0, 0) })
            Battery.vendorHint()?.let { addView(ctx.secondary(it, 13f).apply { setPadding(0, ctx.dp(6), 0, 0) }) }
            addView(ctx.button("Разрешить работу в фоне", outlined = true) { Battery.open(ctx) }.apply {
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    .apply { topMargin = ctx.dp(8) }
            })
        }
        val batteryCard = ctx.card(battery).apply {
            (layoutParams as? LinearLayout.LayoutParams)?.topMargin = ctx.dp(12)
        }
        box.addView(batteryCard)

        val hoursLabel = ctx.text("", 14f, bold = true).apply { setPadding(0, ctx.dp(16), 0, 0) }
        box.addView(hoursLabel)
        val slider = Slider(ctx).apply {
            valueFrom = 1f; valueTo = 12f; stepSize = 1f
            value = prefs.dutyHours.coerceIn(1, 12).toFloat()
            setLabelFormatter { "${it.toInt()} ч" }
        }
        box.addView(slider)

        lateinit var button: com.google.android.material.button.MaterialButton
        fun refresh() {
            val on = Duty.active(ctx)
            val blocked = !on && Battery.restricted(ctx)
            val h = slider.value.toInt()
            title.text = if (on) "Дежурный до " + java.text.SimpleDateFormat("HH:mm", java.util.Locale("ru"))
                .format(java.util.Date(prefs.ultraUntil)) else "Дежурный"
            hoursLabel.text = "Дежурить " + Remind.plural(h, "час", "часа", "часов")
            button.text = if (on) "Выключить" else "Включить на " + Remind.plural(h, "час", "часа", "часов")
            button.isEnabled = !blocked
            batteryCard.visibility = if (blocked) View.VISIBLE else View.GONE
        }
        slider.addOnChangeListener { _, v, _ -> prefs.dutyHours = v.toInt(); refresh() }

        button = ctx.button("") {
            if (Duty.active(ctx)) Duty.stop(ctx.applicationContext)
            else if (!Battery.restricted(ctx)) Duty.start(ctx.applicationContext, slider.value.toInt())
            box.haptic()
            sheet.dismiss()
            onChanged()
        }.apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = ctx.dp(8) }
        } as com.google.android.material.button.MaterialButton
        box.addView(button)
        // Точные будильники (Android 12+): без них во сне система может растягивать круг до 10–15 минут.
        val exact = ctx.vertical().apply {
            addView(ctx.secondary("Чтобы проверки шли ровно каждые 5 минут и во сне, разрешите точные будильники.", 13f)
                .apply { setPadding(0, ctx.dp(12), 0, 0) })
            addView(ctx.textButton("Разрешить точные будильники") {
                runCatching {
                    ctx.startActivity(android.content.Intent(android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
                        .setData(android.net.Uri.parse("package:${ctx.packageName}")))
                }
            })
        }
        box.addView(exact)
        box.addView(View(ctx).apply { layoutParams = LinearLayout.LayoutParams(1, ctx.dp(8)) })
        fun refreshExact() {
            val am = ctx.getSystemService(android.app.AlarmManager::class.java)
            exact.visibility = if (android.os.Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) View.VISIBLE else View.GONE
        }
        refreshExact()
        refresh()

        // Вернулись из системных настроек батареи — сразу обновляем, можно ли включать.
        val focus = ViewTreeObserver.OnWindowFocusChangeListener { has -> if (has) { refresh(); refreshExact() } }
        box.viewTreeObserver.addOnWindowFocusChangeListener(focus)
        sheet.setOnDismissListener { runCatching { box.viewTreeObserver.removeOnWindowFocusChangeListener(focus) } }

        sheet.setContentView(ctx.scroll(box))
        sheet.show()
    }

    private const val YELLOW = 0xFFFFC107.toInt()
    private const val ON_YELLOW = 0xFF3D2E00.toInt()

    /** Кнопка Дежурного в шапке: круг с радаром. */
    fun button(ctx: Context, onClick: () -> Unit): View = badge(ctx, Duty.active(ctx), ctx.dp(36)).apply {
        contentDescription = "Дежурный"
        (layoutParams as LinearLayout.LayoutParams).apply { marginEnd = ctx.dp(4); marginStart = ctx.dp(8) }
        isClickable = true
        foreground = android.graphics.drawable.RippleDrawable(
            android.content.res.ColorStateList.valueOf(0x33000000), null,
            android.graphics.drawable.GradientDrawable().apply { shape = android.graphics.drawable.GradientDrawable.OVAL; setColor(-1) })
        setOnClickListener { onClick() }
    }

    /**
     * Выключен — обычный серый круг с жёлтой каймой. Включён — сплошной жёлтый, и круг мягко «дышит».
     */
    fun badge(ctx: Context, on: Boolean, size: Int = ctx.dp(44)): View = android.widget.ImageView(ctx).apply {
        setImageResource(R.drawable.ic_radar)
        setColorFilter(if (on) ON_YELLOW else ctx.color(com.google.android.material.R.attr.colorOnSurfaceVariant))
        val pad = size / 4
        setPadding(pad, pad, pad, pad)
        background = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.OVAL
            if (on) setColor(YELLOW)
            else {
                setColor(ctx.color(com.google.android.material.R.attr.colorSurfaceContainerHighest))
                setStroke(ctx.dp(2), YELLOW)
            }
        }
        layoutParams = LinearLayout.LayoutParams(size, size).apply { marginEnd = ctx.dp(12) }
        if (on) android.animation.ObjectAnimator.ofFloat(this, View.ALPHA, 1f, 0.55f).apply {
            duration = 900
            repeatMode = android.animation.ValueAnimator.REVERSE
            repeatCount = android.animation.ValueAnimator.INFINITE
            start()
        }
    }
}
