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
            "Из телефона: " + RingtoneManager.getRingtone(context, Uri.parse(key.removePrefix("uri:"))).getTitle(context)
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
        val specs = listOf(
            Triple(CHANNEL_SCHEDULE, "Изменения в расписании", "Переносы, отмены и новые занятия"),
            Triple(CHANNEL_GRADES, "Оценки и КРМ", "Новые контрольные мероприятия, баллы и итоговые оценки"),
        )
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

        val lines = changes.map { "${it.kind.label}: ${it.title} — ${it.text}" }
        val style = NotificationCompat.InboxStyle()
        lines.take(6).forEach(style::addLine)
        if (lines.size > 6) style.setSummaryText("и ещё ${lines.size - 6}")

        val notification = NotificationCompat.Builder(context, channelId(context, channel))
            .setSmallIcon(R.drawable.ic_nav_schedule)
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
