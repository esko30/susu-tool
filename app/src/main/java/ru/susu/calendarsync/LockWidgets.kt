package ru.susu.calendarsync

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.os.SystemClock
import android.view.View
import android.widget.RemoteViews
import java.time.Duration
import java.time.LocalDateTime

/** Что сейчас: идущая пара и ближайшая следующая (без скрытых, со своими). */
private data class NowNext(val current: Lesson?, val next: Lesson?, val list: List<Lesson>, val now: LocalDateTime)

private fun nowNext(context: Context): NowNext {
    val now = Clock.now()
    val list = Timeline.upcoming(NowWidget.lessons(context), now)
    val current = list.firstOrNull { !now.isBefore(NowWidget.start(it)) }
    val next = list.firstOrNull { NowWidget.start(it).isAfter(now) }
    return NowNext(current, next, list, now)
}

private fun room(l: Lesson) = l.location.substringBefore(";").trim()

/**
 * Маленький виджет «Таймер пары»: живой обратный отсчёт до конца текущей пары или до начала следующей.
 * Отсчёт ведёт системный счётчик, приложение не будится каждую минуту.
 */
class PairTimerWidget : AppWidgetProvider() {
    companion object {
        fun updateAll(context: Context) {
            val mgr = AppWidgetManager.getInstance(context)
            val ids = mgr.getAppWidgetIds(ComponentName(context, PairTimerWidget::class.java))
            if (ids.isEmpty()) return
            val v = render(context)
            ids.forEach { mgr.updateAppWidget(it, v) }
        }

        private fun render(context: Context): RemoteViews {
            val v = RemoteViews(context.packageName, R.layout.widget_timer)
            val (current, next, list, now) = nowNext(context)
            val today = now.toLocalDate().toString()
            fun countdown(to: LocalDateTime) {
                val ms = Duration.between(now, to).toMillis().coerceAtLeast(0)
                v.setChronometer(R.id.t_timer, SystemClock.elapsedRealtime() + ms, null, true)
                v.setChronometerCountDown(R.id.t_timer, true)
                v.setViewVisibility(R.id.t_timer, View.VISIBLE)
                v.setViewVisibility(R.id.t_big, View.GONE)
            }
            fun big(text: String) {
                v.setTextViewText(R.id.t_big, text)
                v.setViewVisibility(R.id.t_big, View.VISIBLE)
                v.setViewVisibility(R.id.t_timer, View.GONE)
            }
            val shown = current ?: next
            when {
                current != null -> { v.setTextViewText(R.id.t_label, "До конца пары"); countdown(NowWidget.end(current)) }
                next != null && next.date == today -> { v.setTextViewText(R.id.t_label, "До начала пары"); countdown(NowWidget.start(next)) }
                next != null -> {
                    v.setTextViewText(R.id.t_label, NowWidget.dayTitle(next.date, now.toLocalDate()).substringBefore(","))
                    big(next.beginTime)
                }
                else -> { v.setTextViewText(R.id.t_label, "Пар нет"); big("") }
            }
            val accent = shown?.let { lessonTypeColor(it.eventType) }
            accent?.let { v.setInt(R.id.t_dot, "setColorFilter", it) }
            v.setViewVisibility(R.id.t_dot, if (shown != null) View.VISIBLE else View.GONE)
            v.setTextViewText(R.id.t_sub, shown?.let { listOf(it.subject, room(it)).filter { s -> s.isNotBlank() }.joinToString(" · ") }
                ?: "На ближайшей неделе")
            v.setOnClickPendingIntent(R.id.t_root, NowWidget.openApp(context))
            NowWidget.scheduleTick(context, list, now)
            return v
        }
    }

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) = updateAll(context)
}

/** Узкий виджет «Ближайшая пара» — строка под часы: что сейчас или что дальше, крупно аудитория. */
class NextPairWidget : AppWidgetProvider() {
    companion object {
        fun updateAll(context: Context) {
            val mgr = AppWidgetManager.getInstance(context)
            val ids = mgr.getAppWidgetIds(ComponentName(context, NextPairWidget::class.java))
            if (ids.isEmpty()) return
            val v = render(context)
            ids.forEach { mgr.updateAppWidget(it, v) }
        }

        private fun render(context: Context): RemoteViews {
            val v = RemoteViews(context.packageName, R.layout.widget_compact)
            val (current, next, list, now) = nowNext(context)
            val today = now.toLocalDate()
            val shown = current ?: next
            val label = when {
                current != null -> "Сейчас · до ${current.endTime}"
                next == null -> "Пар на неделе нет"
                next.date == today.toString() -> "Далее · в ${next.beginTime}"
                next.date == today.plusDays(1).toString() -> "Завтра · в ${next.beginTime}"
                else -> NowWidget.dayTitle(next.date, today) + " · в ${next.beginTime}"
            }
            v.setTextViewText(R.id.c_label, label)
            v.setTextViewText(R.id.c_title, shown?.subject ?: "Отдыхаем")
            v.setTextViewText(R.id.c_room, shown?.let { room(it) }.orEmpty())
            if (shown != null) {
                val accent = lessonTypeColor(shown.eventType)
                v.setInt(R.id.c_bar, "setColorFilter", accent)
                v.setTextColor(R.id.c_room, accent)
            }
            v.setViewVisibility(R.id.c_bar, if (shown != null) View.VISIBLE else View.GONE)
            v.setOnClickPendingIntent(R.id.c_root, NowWidget.openApp(context))
            NowWidget.scheduleTick(context, list, now)
            return v
        }
    }

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) = updateAll(context)
}
