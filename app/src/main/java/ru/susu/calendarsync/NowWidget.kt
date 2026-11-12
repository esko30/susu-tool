package ru.susu.calendarsync

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.RemoteViews
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Виджет «Пары»: ближайшие пары списком в стиле карточек ленты — время, цветная полоса по типу,
 * предмет и аудитория; идущая подсвечена. Сколько строк — зависит от высоты виджета (до 6),
 * при смене дня в списке появляется подпись дня. Обновляется при синхронизации, правке планера
 * и сам — к началу и концу пар.
 */
class NowWidget : AppWidgetProvider() {
    companion object {
        private const val ACTION_TICK = "ru.susu.calendarsync.WIDGET_TICK"
        private val ru = Locale("ru")

        private class Slot(val root: Int, val day: Int, val bg: Int, val begin: Int, val end: Int, val bar: Int,
                           val subject: Int, val room: Int, val badge: Int)

        private val SLOTS = listOf(
            Slot(R.id.s0, R.id.s0_day, R.id.s0_bg, R.id.s0_begin, R.id.s0_end, R.id.s0_bar, R.id.s0_subject, R.id.s0_room, R.id.s0_badge),
            Slot(R.id.s1, R.id.s1_day, R.id.s1_bg, R.id.s1_begin, R.id.s1_end, R.id.s1_bar, R.id.s1_subject, R.id.s1_room, R.id.s1_badge),
            Slot(R.id.s2, R.id.s2_day, R.id.s2_bg, R.id.s2_begin, R.id.s2_end, R.id.s2_bar, R.id.s2_subject, R.id.s2_room, R.id.s2_badge),
            Slot(R.id.s3, R.id.s3_day, R.id.s3_bg, R.id.s3_begin, R.id.s3_end, R.id.s3_bar, R.id.s3_subject, R.id.s3_room, R.id.s3_badge),
            Slot(R.id.s4, R.id.s4_day, R.id.s4_bg, R.id.s4_begin, R.id.s4_end, R.id.s4_bar, R.id.s4_subject, R.id.s4_room, R.id.s4_badge),
            Slot(R.id.s5, R.id.s5_day, R.id.s5_bg, R.id.s5_begin, R.id.s5_end, R.id.s5_bar, R.id.s5_subject, R.id.s5_room, R.id.s5_badge),
        )

        /** Перерисовать все виджеты (если они есть на экране). */
        fun update(context: Context) {
            val mgr = AppWidgetManager.getInstance(context)
            mgr.getAppWidgetIds(ComponentName(context, NowWidget::class.java)).forEach { draw(context, mgr, it) }
            // Маленькие виджеты (таймер, строка под часы) обновляются вместе с основным.
            PairTimerWidget.updateAll(context)
            NextPairWidget.updateAll(context)
        }

        private fun draw(context: Context, mgr: AppWidgetManager, id: Int) =
            mgr.updateAppWidget(id, render(context, capacity(mgr.getAppWidgetOptions(id))))

        /** Сколько строк пар влезает: по высоте виджета (в портрете — максимальная высота). */
        private fun capacity(options: Bundle?): Int {
            val h = options?.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT)?.takeIf { it > 0 }
                ?: options?.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT)?.takeIf { it > 0 }
                ?: return 3
            return ((h - 64) / 58).coerceIn(1, SLOTS.size)
        }

        internal fun lessons(context: Context): List<Lesson> {
            val store = Store(context)
            val s = store.own() ?: store.saved().firstOrNull() ?: return emptyList()
            val hidden = Prefs(context).hiddenLessons
            return (store.lessons(s.uid) + MyLessons.lessons(context, s.uid)).filterNot { Hidden.isHidden(hidden, it) }
        }

        internal fun start(l: Lesson) = LocalDateTime.parse("${l.date}T${l.beginTime}")
        internal fun end(l: Lesson) = LocalDateTime.parse("${l.date}T${l.endTime}")

        /** «Сегодня, 10 ноября», «Завтра, 11 ноября», «Четверг, 12 ноября». */
        internal fun dayTitle(date: String, today: LocalDate): String = runCatching {
            val d = LocalDate.parse(date)
            val dm = d.format(DateTimeFormatter.ofPattern("d MMMM", ru))
            when (d) {
                today -> "Сегодня, $dm"
                today.plusDays(1) -> "Завтра, $dm"
                else -> d.format(DateTimeFormatter.ofPattern("EEEE, d MMMM", ru)).replaceFirstChar { it.uppercase() }
            }
        }.getOrDefault(date)

        private fun render(context: Context, capacity: Int): RemoteViews {
            val v = RemoteViews(context.packageName, R.layout.widget_now)
            val prefs = Prefs(context)
            val now = Clock.now()
            val today = now.toLocalDate()
            val all = lessons(context)
            val list = Timeline.upcoming(all, now)
            val shown = list.take(capacity)
            val first = shown.firstOrNull()
            val current = shown.filter { !now.isBefore(start(it)) }.toSet()

            if (first == null) {
                v.setTextViewText(R.id.w_label, if (!prefs.hasCredentials && all.isEmpty()) "SUSU Tool" else "Расписание")
                v.setViewVisibility(R.id.w_chip, View.GONE)
                v.setViewVisibility(R.id.w_empty, View.VISIBLE)
                v.setTextViewText(R.id.w_empty,
                    if (!prefs.hasCredentials && all.isEmpty()) "Войдите в приложении, и здесь появятся пары" else "На ближайшей неделе пар нет")
            } else {
                v.setViewVisibility(R.id.w_empty, View.GONE)
                v.setViewVisibility(R.id.w_chip, View.VISIBLE)
                v.setTextViewText(R.id.w_label, dayTitle(first.date, today))
                val nextStart = list.firstOrNull { start(it).isAfter(now) }
                v.setTextViewText(R.id.w_chip, when {
                    current.isNotEmpty() ->
                        "ещё ${Timeline.duration(Duration.between(now, end(current.first())).toMinutes().coerceAtLeast(1))}"
                    first.date == today.toString() ->
                        "через ${Timeline.duration(Duration.between(now, start(first)).toMinutes().coerceAtLeast(1))}"
                    else -> {
                        val days = Duration.between(today.atStartOfDay(), LocalDate.parse(first.date).atStartOfDay()).toDays().toInt()
                        if (days == 1) "в ${first.beginTime}" else "через ${Remind.plural(days, "день", "дня", "дней")}"
                    }
                })
                if (nextStart == null && current.isEmpty()) v.setViewVisibility(R.id.w_chip, View.GONE)
            }

            SLOTS.forEachIndexed { i, slot ->
                val l = shown.getOrNull(i)
                if (l == null) { v.setViewVisibility(slot.root, View.GONE); return@forEachIndexed }
                v.setViewVisibility(slot.root, View.VISIBLE)
                // Подпись дня — когда список переходит на следующий день.
                val newDay = i > 0 && shown[i - 1].date != l.date
                v.setViewVisibility(slot.day, if (newDay) View.VISIBLE else View.GONE)
                if (newDay) v.setTextViewText(slot.day, dayTitle(l.date, today))

                val accent = lessonTypeColor(l.eventType)
                val live = l in current
                v.setInt(slot.bg, "setColorFilter", accent)
                v.setInt(slot.bg, "setImageAlpha", if (live) 72 else 30)
                v.setInt(slot.bar, "setColorFilter", accent)
                v.setTextViewText(slot.begin, l.beginTime)
                v.setTextViewText(slot.end, l.endTime)
                v.setTextViewText(slot.subject, l.subject)
                val tag = DisplayOptions.typeTag(l.eventType)
                val room = l.location.substringBefore(";").trim()
                v.setTextViewText(slot.room, listOf(room, tag).filter { it.isNotBlank() }.joinToString("  ·  "))
                v.setTextColor(slot.room, accent)
                v.setViewVisibility(slot.badge, if (live) View.VISIBLE else View.GONE)
                if (live) v.setTextViewText(slot.badge, "сейчас")
            }

            // Внизу: сколько пар не влезло на этот день и записи планера на сегодня.
            val hiddenSameDay = first?.let { f -> list.drop(shown.size).count { it.date == shown.last().date } } ?: 0
            val planned = Planner.all(context).count { !it.done && it.date == today.toString() }
            val more = listOfNotNull(
                hiddenSameDay.takeIf { it > 0 }?.let { "ещё ${Remind.plural(it, "пара", "пары", "пар")}, растяните виджет" },
                planned.takeIf { it > 0 }?.let { "✓ сегодня в планере ${Remind.plural(it, "запись", "записи", "записей")}" },
            ).joinToString("   ")
            v.setTextViewText(R.id.w_more, more)
            v.setViewVisibility(R.id.w_more, if (more.isBlank()) View.GONE else View.VISIBLE)

            v.setOnClickPendingIntent(R.id.widget_root, openApp(context))
            scheduleTick(context, list, now)
            return v
        }

        /**
         * Следующая перерисовка: к концу или началу ближайшей пары; пока пара идёт или до неё
         * меньше трёх часов — чаще, ради отсчёта «ещё …» / «через …».
         */
        /** Нажатие на любой виджет открывает расписание. */
        internal fun openApp(context: Context): PendingIntent = PendingIntent.getActivity(
            context, 77,
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra(Notifier.EXTRA_TAB, Syncer.TAB_SCHEDULE),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        internal fun scheduleTick(context: Context, list: List<Lesson>, now: LocalDateTime) {
            val candidates = buildList {
                list.take(3).forEach { add(start(it)); add(end(it)) }
                list.firstOrNull()?.let { if (Duration.between(now, start(it)).toMinutes() < 180) add(now.plusMinutes(5)) }
                add(now.toLocalDate().plusDays(1).atTime(0, 5)) // «завтра» становится «сегодня»
            }
            val at = candidates.filter { it.isAfter(now) }.minOrNull() ?: return
            val am = context.getSystemService(AlarmManager::class.java)
            am.set(AlarmManager.RTC, at.atZone(CalendarSync.SUSU_ZONE).toInstant().toEpochMilli(), tick(context))
        }

        private fun tick(context: Context) = PendingIntent.getBroadcast(
            context, 78, Intent(context, NowWidget::class.java).setAction(ACTION_TICK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        ids.forEach { draw(context, manager, it) }
    }

    /** Виджет растянули или сжали — пересчитываем, сколько пар влезает. */
    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: Bundle) {
        draw(context, manager, id)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_TICK) update(context)
    }

    override fun onDisabled(context: Context) {
        context.getSystemService(AlarmManager::class.java).cancel(tick(context))
    }
}
