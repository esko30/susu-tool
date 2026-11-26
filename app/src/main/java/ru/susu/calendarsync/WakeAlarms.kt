package ru.susu.calendarsync

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.AlarmClock
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.Toast
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.slider.Slider
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Будильники по расписанию: на каждый день недели — за выбранное время до первой пары.
 * Будильники ставятся в «Часы» телефона: так они звонят надёжно, в беззвучном режиме и во сне.
 */
object WakeAlarms {
    /** Один будильник: день, во сколько разбудить и к какой паре. */
    data class Wake(val date: LocalDate, val at: LocalTime, val lesson: Lesson)

    /**
     * План на ближайшие 7 дней: первая пара каждого дня минус [leadMin]. В четверг это пятница, суббота
     * и дальше понедельник–четверг следующей недели. На каждый день недели — один будильник (в «Часах» они
     * привязаны ко дню недели): если сегодняшний уже прошёл, берётся тот же день через неделю.
     * Прошедшие будильники и те, что выпали бы на прошлые сутки, не ставятся.
     */
    fun plan(lessons: List<Lesson>, now: LocalDateTime, leadMin: Int): List<Wake> {
        val today = now.toLocalDate()
        val until = today.plusDays(8)
        return lessons.distinctBy { it.key }
            .filter { it.date >= today.toString() && it.date < until.toString() }
            .groupBy { it.date }
            .mapNotNull { (date, day) ->
                val first = day.minByOrNull { it.beginTime } ?: return@mapNotNull null
                val start = runCatching { LocalDateTime.parse("${date}T${first.beginTime}") }.getOrNull() ?: return@mapNotNull null
                val at = start.minusMinutes(leadMin.toLong())
                if (at.toLocalDate() != start.toLocalDate() || !at.isAfter(now)) null
                else Wake(start.toLocalDate(), at.toLocalTime(), first)
            }
            .sortedBy { it.date }
            .distinctBy { it.date.dayOfWeek }
    }

    /** «1 ч 30 мин», «45 мин». */
    fun leadText(min: Int) = Timeline.duration(min.toLong())

    /**
     * Ставит будильники в «Часы» по одному с паузой: если отправить все сразу, «Часы» (например, у Samsung)
     * принимают только первый. [done] вызывается после последнего с числом поставленных.
     */
    fun set(context: Context, wakes: List<Wake>, done: (Int) -> Unit) {
        val app = context.applicationContext
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        var ok = 0
        wakes.forEachIndexed { i, w ->
            handler.postDelayed({
                val intent = Intent(AlarmClock.ACTION_SET_ALARM)
                    .putExtra(AlarmClock.EXTRA_HOUR, w.at.hour)
                    .putExtra(AlarmClock.EXTRA_MINUTES, w.at.minute)
                    .putExtra(AlarmClock.EXTRA_MESSAGE, "Пара в ${w.lesson.beginTime}")
                    // День недели: у «Часов» неделя начинается с воскресенья (1).
                    .putExtra(AlarmClock.EXTRA_DAYS, arrayListOf(w.date.dayOfWeek.value % 7 + 1))
                    .putExtra(AlarmClock.EXTRA_VIBRATE, true)
                    .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                try {
                    app.startActivity(intent)
                    ok++
                } catch (e: ActivityNotFoundException) {
                    // «Часов» нет — остальные тоже не поставятся.
                }
                if (i == wakes.lastIndex) done(ok)
            }, i * STEP_MS)
        }
    }

    private const val STEP_MS = 1200L

    /** Окно: за сколько будить, план на неделю и кнопка «Поставить». */
    fun show(ctx: Context, lessons: List<Lesson>) {
        val prefs = Prefs(ctx)
        val sheet = BottomSheetDialog(ctx)
        val box = ctx.vertical(20)
        box.addView(ctx.text("Будильники на 7 дней", 20f, bold = true))
        box.addView(ctx.secondary("Разбужу перед первой парой каждого дня. Будильники появятся в приложении «Часы».", 14f)
            .apply { setPadding(0, ctx.dp(4), 0, 0) })

        val leadLabel = ctx.text("", 14f, bold = true).apply { setPadding(0, ctx.dp(16), 0, 0) }
        box.addView(leadLabel)
        val slider = Slider(ctx).apply {
            valueFrom = 30f; valueTo = 180f; stepSize = 15f
            value = prefs.wakeLead.coerceIn(30, 180).let { it - it % 15 }.toFloat()
            setLabelFormatter { leadText(it.toInt()) }
        }
        box.addView(slider)

        val list = ctx.vertical()
        box.addView(list)
        val ru = Locale("ru")
        val dayFmt = DateTimeFormatter.ofPattern("EE, d MMMM", ru)
        lateinit var button: com.google.android.material.button.MaterialButton
        var wakes = emptyList<Wake>()
        fun refresh() {
            val lead = slider.value.toInt()
            leadLabel.text = "Будить за ${leadText(lead)} до пары"
            wakes = plan(lessons, Clock.now(), lead)
            list.removeAllViews()
            if (wakes.isEmpty()) list.addView(ctx.secondary("В ближайшие 7 дней пар нет, будить не к чему.", 14f)
                .apply { setPadding(0, ctx.dp(8), 0, 0) })
            for (w in wakes) {
                val row = ctx.horizontal().apply { setPadding(0, ctx.dp(6), 0, ctx.dp(6)); gravity = android.view.Gravity.CENTER_VERTICAL }
                row.addView(ctx.text(w.at.toString(), 18f, bold = true).apply { width = ctx.dp(64) })
                row.addView(ctx.vertical().apply {
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    addView(ctx.text(w.date.format(dayFmt).replaceFirstChar { it.uppercase() }, 14f))
                    addView(ctx.secondary("пара в ${w.lesson.beginTime}, ${w.lesson.subject}", 12f).apply {
                        maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
                    })
                })
                list.addView(row)
            }
            button.isEnabled = wakes.isNotEmpty()
            button.text = if (wakes.isEmpty()) "Ставить нечего"
                else "Поставить " + Remind.plural(wakes.size, "будильник", "будильника", "будильников")
        }
        slider.addOnChangeListener { _, v, _ -> prefs.wakeLead = v.toInt(); refresh() }

        button = ctx.button("") {
            val app = ctx.applicationContext
            val count = wakes.size
            sheet.dismiss()
            if (count > 1) Toast.makeText(app, "Ставлю будильники по одному, это займёт пару секунд", Toast.LENGTH_SHORT).show()
            set(ctx, wakes) { n ->
                Toast.makeText(app,
                    if (n == 0) "Не нашлось приложения «Часы»" else "Готово, " + Remind.plural(n, "будильник", "будильника", "будильников") + " в «Часах»",
                    Toast.LENGTH_LONG).show()
            }
        }.apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = ctx.dp(12) }
        } as com.google.android.material.button.MaterialButton
        box.addView(button)
        box.addView(ctx.secondary("«Часы» повторяют будильник каждую неделю в тот же день. " +
            "Если расписание поменяется, выключите старые будильники в «Часах» и поставьте заново.", 12f)
            .apply { setPadding(0, ctx.dp(10), 0, ctx.dp(8)) })
        refresh()

        sheet.setContentView(ctx.scroll(box))
        sheet.show()
    }
}
