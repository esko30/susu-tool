package ru.susu.calendarsync

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

/** Уведомления об изменениях в расписаниях и оценках. */
object Notifier {
    const val CHANNEL_SCHEDULE = "schedule_changes"
    const val CHANNEL_GRADES = "grade_changes"
    /** Баллы и новые КРМ: без звука и всплывающего окна. */
    const val CHANNEL_GRADES_QUIET = "grade_points"
    const val EXTRA_SUBJECT = "subjectKey"
    const val EXTRA_TERM = "subjectTerm"
    private const val GROUP_GRADES = "grades"
    const val EXTRA_TAB = "tab"

    /** Встроенные звуки (res/raw) и служебные варианты. Порядок — как в списке выбора. */
    val SOUNDS = linkedMapOf(
        "chime" to "Колокольчик",
        "arpeggio" to "Арпеджио",
        "bell" to "Звонок",
        "soft" to "Мягкий",
        "drop" to "Капля",
        "default" to "Системный звук",
        "silent" to "Без звука",
    )
    private val RAW = mapOf(
        "chime" to R.raw.snd_chime, "arpeggio" to R.raw.snd_arpeggio, "bell" to R.raw.snd_bell,
        "soft" to R.raw.snd_soft, "drop" to R.raw.snd_drop,
    )

    fun soundLabel(context: Context, key: String): String =
        if (key.startsWith("uri:")) runCatching {
            RingtoneManager.getRingtone(context, Uri.parse(key.removePrefix("uri:"))).getTitle(context)
        }.getOrDefault("Из телефона")
        else SOUNDS[key] ?: SOUNDS.getValue("default")

    /** Uri звука по ключу; null — без звука. */
    fun soundUri(context: Context, key: String): Uri? = when {
        key == "silent" -> null
        key.startsWith("uri:") -> Uri.parse(key.removePrefix("uri:"))
        key in RAW -> Uri.parse("${ContentResolver.SCHEME_ANDROID_RESOURCE}://${context.packageName}/${RAW.getValue(key)}")
        else -> RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
    }

    /** Прослушать звук при выборе. */
    fun preview(context: Context, key: String) {
        val uri = soundUri(context, key) ?: return
        runCatching { RingtoneManager.getRingtone(context, uri)?.play() }
    }

    private fun soundKey(context: Context, base: String) = Prefs(context).let {
        if (base == CHANNEL_GRADES) it.soundGrades else it.soundSchedule
    }

    /**
     * Звук канала в Android 8+ задаётся только при создании, поэтому у каждого звука свой канал:
     * «schedule_changes_<звук>». Старые каналы того же типа удаляются.
     */
    private fun channelId(context: Context, base: String) =
        base + "_" + Integer.toHexString(soundKey(context, base).hashCode())

    fun ensureChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        // Канал «Текущая пара» из пробной сборки: убираем вместе с его уведомлением.
        if (nm.getNotificationChannel("live_pair") != null) nm.deleteNotificationChannel("live_pair")
        val specs = listOf(
            Triple(CHANNEL_SCHEDULE, "Изменения в расписании", "Переносы, отмены и новые пары"),
            Triple(CHANNEL_GRADES, "Оценки", "Итоговые оценки и снижение баллов"),
        )
        if (nm.getNotificationChannel(Planner.CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(Planner.CHANNEL, "Напоминания планера", NotificationManager.IMPORTANCE_HIGH)
                .apply { description = "Напоминания о записях в планере" })
        }
        for ((base, name, desc) in specs) {
            val id = channelId(context, base)
            nm.notificationChannels.filter { (it.id == base || it.id.startsWith(base + "_")) && it.id != id }
                .forEach { nm.deleteNotificationChannel(it.id) }
            if (nm.getNotificationChannel(id) != null) continue
            nm.createNotificationChannel(NotificationChannel(id, name, NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = desc
                setSound(
                    soundUri(context, soundKey(context, base)),
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build(),
                )
            })
        }
    }

    fun canNotify(context: Context) = Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    /**
     * Уведомления об оценках. Итоговая оценка и снижение балла — каждое отдельно и со звуком,
     * новые баллы и новые КРМ — тихими сводками. Если речь об одном предмете, нажатие открывает его.
     */
    fun notifyGrades(context: Context, term: Int, news: List<GradeNews>) {
        if (news.isEmpty() || !canNotify(context)) return
        ensureChannels(context)
        if (NotificationManagerCompat.from(context).getNotificationChannel(CHANNEL_GRADES_QUIET) == null) {
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_GRADES_QUIET, "Баллы и новые КРМ", NotificationManager.IMPORTANCE_LOW)
                    .apply { description = "Новые баллы и КРМ, без звука" })
        }
        val loud = channelId(context, CHANNEL_GRADES)
        val green = android.graphics.Color.rgb(47, 154, 92)
        val blue = android.graphics.Color.rgb(58, 111, 216)
        val red = android.graphics.Color.rgb(192, 80, 77)
        val gray = android.graphics.Color.rgb(120, 120, 120)

        news.filterIsInstance<GradeNews.Final>().forEach { n ->
            val failed = " не " in n.text
            post(context, loud, "final|" + n.subject.key, if (failed) R.drawable.ic_notif_down else R.drawable.ic_notif_final,
                if (failed) red else green, GradeNews.shortName(n.subject.name), listOf(n.text), term, n.subject.key)
        }
        news.filterIsInstance<GradeNews.Down>().groupBy { it.subject.key }.forEach { (key, list) ->
            post(context, loud, "down|$key|" + System.currentTimeMillis(), R.drawable.ic_notif_down, red,
                GradeNews.shortName(list.first().subject.name), list.map { it.text }, term, key)
        }
        summary(context, news.filterIsInstance<GradeNews.Points>(), "Новые баллы", R.drawable.ic_notif_points, blue, term)
        summary(context, news.filterIsInstance<GradeNews.NewKrm>(), "Новые КРМ", R.drawable.ic_notif_krm, gray, term)
    }

    /** Тихая сводка: по одному предмету — его название в заголовке, по нескольким — «Новые баллы по 3 предметам». */
    private fun summary(context: Context, list: List<GradeNews>, what: String, icon: Int, color: Int, term: Int) {
        if (list.isEmpty()) return
        val bySubject = list.groupBy { it.subject.key }
        val single = bySubject.size == 1
        val title = if (single) GradeNews.shortName(list.first().subject.name)
            else "$what по " + Remind.plural(bySubject.size, "предмету", "предметам", "предметам")
        val lines = if (single) list.map { it.text } else list.map { GradeNews.shortName(it.subject.name) + ", " + it.text.replaceFirstChar { c -> c.lowercase() } }
        post(context, CHANNEL_GRADES_QUIET, what + "|" + System.currentTimeMillis(), icon, color, title, lines, term,
            if (single) bySubject.keys.first() else null)
    }

    private fun post(context: Context, channel: String, tag: String, icon: Int, color: Int, title: String,
                     lines: List<String>, term: Int, subjectKey: String?) {
        val id = tag.hashCode()
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(EXTRA_TAB, Syncer.TAB_GRADES)
            .apply { if (subjectKey != null) putExtra(EXTRA_SUBJECT, subjectKey).putExtra(EXTRA_TERM, term) }
        val pending = PendingIntent.getActivity(context, id, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val b = NotificationCompat.Builder(context, channel)
            .setSmallIcon(icon)
            .setColor(color)
            .setContentTitle(title)
            .setContentText(lines.first())
            .setGroup(GROUP_GRADES)
            .setContentIntent(pending)
            .setAutoCancel(true)
        if (lines.size > 1) b.setStyle(NotificationCompat.InboxStyle().also { s ->
            lines.take(6).forEach(s::addLine)
            if (lines.size > 6) s.setSummaryText("и ещё ${lines.size - 6}")
        })
        try {
            NotificationManagerCompat.from(context).notify(id, b.build())
        } catch (e: SecurityException) {
            // Разрешение на уведомления отозвано — изменения всё равно видны в приложении.
        }
    }

    fun notifyChanges(context: Context, channel: String, title: String, changes: List<Change>, tab: Int) {
        if (changes.isEmpty() || !canNotify(context)) return
        ensureChannels(context)

        val id = (channel + title).hashCode()
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(EXTRA_TAB, tab)
        val pending = PendingIntent.getActivity(
            context, id, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val lines = changes.map { "${it.title}, ${it.kind.label.lowercase()}. ${it.text}" }
        val style = NotificationCompat.InboxStyle()
        lines.take(6).forEach(style::addLine)
        if (lines.size > 6) style.setSummaryText("и ещё ${lines.size - 6}")

        val notification = NotificationCompat.Builder(context, channelId(context, channel))
            .setSmallIcon(R.drawable.ic_notif_schedule)
            .setContentTitle("$title (${changes.size})")
            .setContentText(lines.first())
            .setStyle(style)
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()

        try {
            NotificationManagerCompat.from(context).notify(id, notification)
        } catch (e: SecurityException) {
            // Разрешение на уведомления отозвано — изменения всё равно видны в приложении.
        }
    }
}
